//! A small JSON reader, just large enough for `pactl --format=json`.
//!
//! The host has one dependency and this does not add a second. `pactl` is the
//! only interface that speaks for both PipeWire and PulseAudio, its JSON is the
//! only stable shape it offers, and reading that shape needs a real parser
//! rather than a scan for likely-looking substrings: device descriptions carry
//! quotes, braces and non-ASCII, and a scanner would mis-read them silently.
//!
//! Free of I/O, like the rest of the parsing in this daemon, so the whole
//! surface is unit-testable.

use std::fmt;

/// How deeply values may nest.
///
/// `pactl` output is three or four deep. A ceiling means a damaged or hostile
/// document cannot exhaust the stack, which a recursive parser otherwise
/// invites.
const MAX_DEPTH: usize = 32;

#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    Null,
    Bool(bool),
    /// Every JSON number, held as a double.
    ///
    /// The largest thing `pactl` reports is a 32-bit index, which a double
    /// represents exactly, so nothing here loses precision.
    Number(f64),
    String(String),
    Array(Vec<Value>),
    /// Members in the order they were written.
    ///
    /// A list rather than a map: these objects have a few dozen keys at most
    /// and are read a handful of times, so the linear lookup costs nothing and
    /// saves carrying a hash map around.
    Object(Vec<(String, Value)>),
}

impl Value {
    pub fn get(&self, key: &str) -> Option<&Value> {
        let Value::Object(members) = self else {
            return None;
        };
        members
            .iter()
            .find(|(name, _)| name == key)
            .map(|(_, value)| value)
    }

    pub fn as_str(&self) -> Option<&str> {
        match self {
            Value::String(text) => Some(text),
            _ => None,
        }
    }

    pub fn as_bool(&self) -> Option<bool> {
        match self {
            Value::Bool(value) => Some(*value),
            _ => None,
        }
    }

    pub fn as_f64(&self) -> Option<f64> {
        match self {
            Value::Number(value) => Some(*value),
            _ => None,
        }
    }

    /// The value as an index, rejecting anything that is not a whole number in
    /// range. `pactl` uses `4294967295` as "none", which is in range and means
    /// the caller must still decide what it signifies.
    pub fn as_u32(&self) -> Option<u32> {
        let number = self.as_f64()?;
        if !number.is_finite() || number.fract() != 0.0 {
            return None;
        }
        if number < 0.0 || number > f64::from(u32::MAX) {
            return None;
        }
        Some(number as u32)
    }

    pub fn as_array(&self) -> Option<&[Value]> {
        match self {
            Value::Array(items) => Some(items),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JsonError(pub String);

impl fmt::Display for JsonError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.0)
    }
}

impl std::error::Error for JsonError {}

pub fn parse(text: &str) -> Result<Value, JsonError> {
    let mut reader = Reader {
        bytes: text.as_bytes(),
        position: 0,
    };
    reader.skip_whitespace();
    let value = reader.value(0)?;
    reader.skip_whitespace();
    if reader.position != reader.bytes.len() {
        return Err(JsonError("trailing data after the document".into()));
    }
    Ok(value)
}

struct Reader<'a> {
    bytes: &'a [u8],
    position: usize,
}

impl Reader<'_> {
    fn peek(&self) -> Option<u8> {
        self.bytes.get(self.position).copied()
    }

    fn skip_whitespace(&mut self) {
        while matches!(self.peek(), Some(b' ' | b'\t' | b'\n' | b'\r')) {
            self.position += 1;
        }
    }

    fn expect(&mut self, byte: u8) -> Result<(), JsonError> {
        if self.peek() != Some(byte) {
            return Err(JsonError(format!(
                "expected {:?} at byte {}",
                byte as char, self.position
            )));
        }
        self.position += 1;
        Ok(())
    }

    fn literal(&mut self, word: &str, value: Value) -> Result<Value, JsonError> {
        if self.bytes[self.position..].starts_with(word.as_bytes()) {
            self.position += word.len();
            return Ok(value);
        }
        Err(JsonError(format!(
            "invalid literal at byte {}",
            self.position
        )))
    }

    fn value(&mut self, depth: usize) -> Result<Value, JsonError> {
        if depth > MAX_DEPTH {
            return Err(JsonError("nesting is too deep".into()));
        }
        match self.peek() {
            Some(b'{') => self.object(depth),
            Some(b'[') => self.array(depth),
            Some(b'"') => Ok(Value::String(self.string()?)),
            Some(b't') => self.literal("true", Value::Bool(true)),
            Some(b'f') => self.literal("false", Value::Bool(false)),
            Some(b'n') => self.literal("null", Value::Null),
            Some(_) => self.number(),
            None => Err(JsonError("unexpected end of document".into())),
        }
    }

    fn object(&mut self, depth: usize) -> Result<Value, JsonError> {
        self.expect(b'{')?;
        let mut members = Vec::new();
        self.skip_whitespace();
        if self.peek() == Some(b'}') {
            self.position += 1;
            return Ok(Value::Object(members));
        }
        loop {
            self.skip_whitespace();
            let key = self.string()?;
            self.skip_whitespace();
            self.expect(b':')?;
            self.skip_whitespace();
            let value = self.value(depth + 1)?;
            members.push((key, value));
            self.skip_whitespace();
            match self.peek() {
                Some(b',') => self.position += 1,
                Some(b'}') => {
                    self.position += 1;
                    return Ok(Value::Object(members));
                }
                _ => {
                    return Err(JsonError(format!(
                        "unterminated object at byte {}",
                        self.position
                    )))
                }
            }
        }
    }

    fn array(&mut self, depth: usize) -> Result<Value, JsonError> {
        self.expect(b'[')?;
        let mut items = Vec::new();
        self.skip_whitespace();
        if self.peek() == Some(b']') {
            self.position += 1;
            return Ok(Value::Array(items));
        }
        loop {
            self.skip_whitespace();
            items.push(self.value(depth + 1)?);
            self.skip_whitespace();
            match self.peek() {
                Some(b',') => self.position += 1,
                Some(b']') => {
                    self.position += 1;
                    return Ok(Value::Array(items));
                }
                _ => {
                    return Err(JsonError(format!(
                        "unterminated array at byte {}",
                        self.position
                    )))
                }
            }
        }
    }

    fn string(&mut self) -> Result<String, JsonError> {
        self.expect(b'"')?;
        let mut text = String::new();
        loop {
            let byte = self
                .peek()
                .ok_or_else(|| JsonError("unterminated string".into()))?;
            match byte {
                b'"' => {
                    self.position += 1;
                    return Ok(text);
                }
                b'\\' => {
                    self.position += 1;
                    let escape = self
                        .peek()
                        .ok_or_else(|| JsonError("unterminated escape".into()))?;
                    self.position += 1;
                    match escape {
                        b'"' => text.push('"'),
                        b'\\' => text.push('\\'),
                        b'/' => text.push('/'),
                        b'b' => text.push('\u{0008}'),
                        b'f' => text.push('\u{000c}'),
                        b'n' => text.push('\n'),
                        b'r' => text.push('\r'),
                        b't' => text.push('\t'),
                        b'u' => text.push(self.unicode_escape()?),
                        other => {
                            return Err(JsonError(format!("unknown escape: \\{}", other as char)))
                        }
                    }
                }
                _ => {
                    // Multi-byte UTF-8 passes through untouched: the input is
                    // already a `str`, so every sequence in it is valid.
                    let start = self.position;
                    let mut end = self.position + 1;
                    while end < self.bytes.len() && self.bytes[end] & 0xc0 == 0x80 {
                        end += 1;
                    }
                    text.push_str(
                        std::str::from_utf8(&self.bytes[start..end])
                            .map_err(|_| JsonError("invalid UTF-8 in string".into()))?,
                    );
                    self.position = end;
                }
            }
        }
    }

    /// Reads `\uXXXX`, joining a surrogate pair when one follows.
    fn unicode_escape(&mut self) -> Result<char, JsonError> {
        let first = self.hex4()?;
        // A lone high surrogate is not a character on its own; JSON writes
        // astral scalars as a pair.
        if (0xd800..0xdc00).contains(&first) {
            if self.bytes[self.position..].starts_with(b"\\u") {
                self.position += 2;
                let second = self.hex4()?;
                if (0xdc00..0xe000).contains(&second) {
                    let combined = 0x1_0000
                        + ((u32::from(first) - 0xd800) << 10)
                        + (u32::from(second) - 0xdc00);
                    return char::from_u32(combined)
                        .ok_or_else(|| JsonError("invalid surrogate pair".into()));
                }
            }
            // Unpaired: substitute rather than fail. A mangled character in a
            // device name must not cost the whole mixer.
            return Ok('\u{fffd}');
        }
        char::from_u32(u32::from(first)).ok_or_else(|| JsonError("invalid escape".into()))
    }

    fn hex4(&mut self) -> Result<u16, JsonError> {
        let end = self.position + 4;
        let digits = self
            .bytes
            .get(self.position..end)
            .ok_or_else(|| JsonError("truncated \\u escape".into()))?;
        let text = std::str::from_utf8(digits).map_err(|_| JsonError("bad \\u escape".into()))?;
        let value =
            u16::from_str_radix(text, 16).map_err(|_| JsonError("bad \\u escape".into()))?;
        self.position = end;
        Ok(value)
    }

    fn number(&mut self) -> Result<Value, JsonError> {
        let start = self.position;
        if self.peek() == Some(b'-') {
            self.position += 1;
        }
        while matches!(
            self.peek(),
            Some(b'0'..=b'9' | b'.' | b'e' | b'E' | b'+' | b'-')
        ) {
            self.position += 1;
        }
        let text = std::str::from_utf8(&self.bytes[start..self.position])
            .map_err(|_| JsonError("bad number".into()))?;
        text.parse::<f64>()
            .map(Value::Number)
            .map_err(|_| JsonError(format!("bad number: {text}")))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_shape_pactl_produces() {
        let document = parse(
            r#"[{"index":53,"mute":false,"description":"Built-in Audio","volume":{"front-left":{"value":62259}}}]"#,
        )
        .expect("valid document");
        let entries = document.as_array().expect("an array");
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].get("index").and_then(Value::as_u32), Some(53));
        assert_eq!(entries[0].get("mute").and_then(Value::as_bool), Some(false));
        assert_eq!(
            entries[0].get("description").and_then(Value::as_str),
            Some("Built-in Audio")
        );
        assert_eq!(
            entries[0]
                .get("volume")
                .and_then(|volume| volume.get("front-left"))
                .and_then(|channel| channel.get("value"))
                .and_then(Value::as_u32),
            Some(62259)
        );
    }

    #[test]
    fn reads_escapes_and_non_ascii() {
        // Device descriptions are free text from the hardware and from window
        // titles. Both routinely contain quotes and non-ASCII.
        let value = parse(r#""a \"quoted\" nome com acento: ção\n\u00e9\ud83d\ude00""#).unwrap();
        assert_eq!(
            value.as_str(),
            Some("a \"quoted\" nome com acento: ção\né😀")
        );
    }

    #[test]
    fn an_unpaired_surrogate_becomes_a_replacement_rather_than_an_error() {
        // One mangled character in a name must not cost the whole mixer.
        let value = parse(r#""\ud800ok""#).unwrap();
        assert_eq!(value.as_str(), Some("\u{fffd}ok"));
    }

    #[test]
    fn reads_empty_containers() {
        assert_eq!(parse("[]"), Ok(Value::Array(vec![])));
        assert_eq!(parse("{}"), Ok(Value::Object(vec![])));
        assert_eq!(parse("  [ ]  "), Ok(Value::Array(vec![])));
    }

    #[test]
    fn reads_numbers_and_literals() {
        assert_eq!(parse("0"), Ok(Value::Number(0.0)));
        assert_eq!(parse("-1.5e2"), Ok(Value::Number(-150.0)));
        assert_eq!(parse("4294967295").unwrap().as_u32(), Some(u32::MAX));
        assert_eq!(parse("true"), Ok(Value::Bool(true)));
        assert_eq!(parse("null"), Ok(Value::Null));
    }

    #[test]
    fn an_index_must_be_a_whole_number_in_range() {
        assert_eq!(parse("1.5").unwrap().as_u32(), None);
        assert_eq!(parse("-1").unwrap().as_u32(), None);
        assert_eq!(parse("4294967296").unwrap().as_u32(), None);
    }

    #[test]
    fn damaged_documents_are_errors_rather_than_panics() {
        for damaged in [
            "",
            "{",
            "[",
            "[1,",
            "{\"a\"}",
            "{\"a\":}",
            "\"unterminated",
            "\"\\q\"",
            "\"\\u00\"",
            "tru",
            "[] extra",
            "{\"a\":1,}",
        ] {
            assert!(parse(damaged).is_err(), "{damaged:?} should not parse");
        }
    }

    #[test]
    fn deep_nesting_is_refused_rather_than_exhausting_the_stack() {
        let deep = "[".repeat(MAX_DEPTH + 10) + &"]".repeat(MAX_DEPTH + 10);
        assert!(parse(&deep).is_err());
    }

    #[test]
    fn missing_members_read_as_absent_rather_than_failing() {
        let value = parse(r#"{"a":1}"#).unwrap();
        assert_eq!(value.get("b"), None);
        assert_eq!(value.get("a").and_then(Value::as_str), None);
    }
}
