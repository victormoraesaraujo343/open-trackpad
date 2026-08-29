//! Free text on a line, and how it survives being one.
//!
//! Two places need this and they need the same answer: device and application
//! names on the wire, and the names people type into the shortcut recorder,
//! which are kept one per line in a file. Both are text this host did not
//! author — a window title comes from whatever page a browser has open, and a
//! shortcut name comes from whoever typed it.
//!
//! It used to live with the protocol. It moved here when the file started using
//! it, because a rule that only one of two callers follows is not a rule.

/// Renders free text so it cannot be mistaken for protocol.
///
/// This is load-bearing, not tidiness. Device descriptions and application
/// names are free text the host does not author: a window title comes from
/// whatever page a browser has open. Pasted raw into a line-framed protocol,
/// a name containing a newline would let a web page write its own messages into
/// the stream the phone is reading. Everything outside printable ASCII, and the
/// space and percent themselves, becomes `%XX` per UTF-8 byte — so a name can
/// never contain a separator, a newline, or anything else structural.
pub fn escape_text(text: &str) -> String {
    let mut escaped = String::with_capacity(text.len());
    for byte in text.as_bytes() {
        match byte {
            b'!'..=b'~' if *byte != b'%' => escaped.push(*byte as char),
            other => escaped.push_str(&format!("%{other:02X}")),
        }
    }
    if escaped.is_empty() {
        // An empty field would vanish into the whitespace separation and shift
        // every field after it.
        escaped.push_str("%20");
    }
    escaped
}

/// Reads back what `escape_text` wrote, or nothing if it was not written by it.
///
/// Needed because the same encoding now protects the file custom shortcuts are
/// kept in: a name is free text a person typed, and a name containing a newline
/// would otherwise be able to add lines to that file. One encoding, used in both
/// places, for the same reason.
///
/// Strict on the way back. A field holding a raw space, a control character or
/// a truncated escape was not produced by `escape_text`, so it is refused rather
/// than half-read — a hand-edited file is exactly where a guess would hurt.
pub fn unescape_text(text: &str) -> Option<String> {
    let bytes = text.as_bytes();
    let mut decoded = Vec::with_capacity(bytes.len());
    let mut position = 0;
    while position < bytes.len() {
        match bytes[position] {
            b'%' => {
                let digits = bytes.get(position + 1..position + 3)?;
                let digits = std::str::from_utf8(digits).ok()?;
                decoded.push(u8::from_str_radix(digits, 16).ok()?);
                position += 3;
            }
            byte @ b'!'..=b'~' => {
                decoded.push(byte);
                position += 1;
            }
            _ => return None,
        }
    }
    String::from_utf8(decoded).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn free_text_cannot_write_its_own_lines() {
        // A window title is written by whatever page a browser has open, and a
        // shortcut name by whoever typed it. Neither may become a second line.
        let hostile = "ok\nCHANGED audio 1 output 53 1000 0 1 - pwned";
        let escaped = escape_text(hostile);
        assert!(!escaped.contains('\n'));
        assert!(!escaped.contains(' '));
        assert_eq!(escaped.lines().count(), 1);
        assert_eq!(unescape_text(&escaped).as_deref(), Some(hostile));
    }

    #[test]
    fn ordinary_names_stay_readable_and_awkward_ones_survive() {
        assert_eq!(escape_text("Firefox"), "Firefox");
        assert_eq!(escape_text("a b"), "a%20b");
        assert_eq!(escape_text("100%"), "100%25");
        assert_eq!(escape_text("\t"), "%09");
        // Non-ASCII goes out a byte at a time, so the other side rebuilds the
        // exact string rather than a guess at it.
        assert_eq!(escape_text("ç"), "%C3%A7");
        // An empty field would otherwise vanish and shift every field after it.
        assert_eq!(escape_text(""), "%20");
    }

    #[test]
    fn everything_written_can_be_read_back() {
        for original in ["Firefox", "a b", "100%", "ção", "x\ty", "", "  ", "%%%"] {
            let restored = unescape_text(&escape_text(original));
            let expected = if original.is_empty() { " " } else { original };
            assert_eq!(restored.as_deref(), Some(expected), "{original:?}");
        }
    }

    #[test]
    fn something_this_did_not_write_is_refused_rather_than_half_read() {
        // A hand-edited file is exactly where a guess would hurt.
        assert_eq!(unescape_text("a b"), None);
        assert_eq!(unescape_text("%"), None);
        assert_eq!(unescape_text("%2"), None);
        assert_eq!(unescape_text("%zz"), None);
        assert_eq!(unescape_text("a\nb"), None);
        // A percent sequence that is not valid UTF-8 is not a name.
        assert_eq!(unescape_text("%FF"), None);
    }
}
