//! Symbolic key names, and the chords built from them.
//!
//! The client sends names rather than Linux key codes, so the two sides are not
//! coupled to the kernel's numbering and a malformed action is caught here
//! instead of becoming a wrong keystroke.
//!
//! The set is deliberately closed. Anything outside it is rejected, which is
//! what stops a control surface from turning into a way to press arbitrary keys
//! on someone's desktop.

use evdev::KeyCode;

/// The most keys one chord may hold down at once.
///
/// Real chords are two or three keys. A ceiling means a hostile client cannot
/// pin every modifier on the keyboard down at once.
pub const MAX_CHORD_KEYS: usize = 5;

/// Every key the control surface is allowed to press, by the name the client
/// uses for it.
///
/// Grouped the way the v0.2 profiles are: modifiers, navigation, editing, and
/// media. Adding to this list is how the vocabulary grows; nothing else needs
/// to change.
const NAMED_KEYS: &[(&str, KeyCode)] = &[
    // Modifiers.
    ("ctrl", KeyCode::KEY_LEFTCTRL),
    ("shift", KeyCode::KEY_LEFTSHIFT),
    ("alt", KeyCode::KEY_LEFTALT),
    ("super", KeyCode::KEY_LEFTMETA),
    // Navigation and control.
    ("escape", KeyCode::KEY_ESC),
    ("tab", KeyCode::KEY_TAB),
    ("enter", KeyCode::KEY_ENTER),
    ("space", KeyCode::KEY_SPACE),
    ("backspace", KeyCode::KEY_BACKSPACE),
    ("delete", KeyCode::KEY_DELETE),
    ("home", KeyCode::KEY_HOME),
    ("end", KeyCode::KEY_END),
    ("pageup", KeyCode::KEY_PAGEUP),
    ("pagedown", KeyCode::KEY_PAGEDOWN),
    ("left", KeyCode::KEY_LEFT),
    ("right", KeyCode::KEY_RIGHT),
    ("up", KeyCode::KEY_UP),
    ("down", KeyCode::KEY_DOWN),
    // Letters the profiles reach for: copy, paste, cut, undo, redo, find,
    // reload, new tab, close tab, save, quit.
    ("a", KeyCode::KEY_A),
    ("c", KeyCode::KEY_C),
    ("f", KeyCode::KEY_F),
    ("l", KeyCode::KEY_L),
    ("n", KeyCode::KEY_N),
    ("q", KeyCode::KEY_Q),
    ("r", KeyCode::KEY_R),
    ("s", KeyCode::KEY_S),
    ("t", KeyCode::KEY_T),
    ("v", KeyCode::KEY_V),
    ("w", KeyCode::KEY_W),
    ("x", KeyCode::KEY_X),
    ("y", KeyCode::KEY_Y),
    ("z", KeyCode::KEY_Z),
    // Function keys, for full screen and the like.
    ("f1", KeyCode::KEY_F1),
    ("f2", KeyCode::KEY_F2),
    ("f3", KeyCode::KEY_F3),
    ("f4", KeyCode::KEY_F4),
    ("f5", KeyCode::KEY_F5),
    ("f6", KeyCode::KEY_F6),
    ("f7", KeyCode::KEY_F7),
    ("f8", KeyCode::KEY_F8),
    ("f9", KeyCode::KEY_F9),
    ("f10", KeyCode::KEY_F10),
    ("f11", KeyCode::KEY_F11),
    ("f12", KeyCode::KEY_F12),
    // Media.
    ("playpause", KeyCode::KEY_PLAYPAUSE),
    ("nexttrack", KeyCode::KEY_NEXTSONG),
    ("previoustrack", KeyCode::KEY_PREVIOUSSONG),
    ("stop", KeyCode::KEY_STOPCD),
    ("volumeup", KeyCode::KEY_VOLUMEUP),
    ("volumedown", KeyCode::KEY_VOLUMEDOWN),
    ("mute", KeyCode::KEY_MUTE),
    ("brightnessup", KeyCode::KEY_BRIGHTNESSUP),
    ("brightnessdown", KeyCode::KEY_BRIGHTNESSDOWN),
];

/// Looks up one key by name.
pub fn key_by_name(name: &str) -> Option<KeyCode> {
    NAMED_KEYS
        .iter()
        .find(|(known, _)| *known == name)
        .map(|(_, code)| *code)
}

/// Every key the virtual keyboard must declare it can produce.
pub fn all_keys() -> impl Iterator<Item = KeyCode> {
    NAMED_KEYS.iter().map(|(_, code)| *code)
}

/// A set of keys pressed together, in the order they were given.
///
/// Order matters on the wire: modifiers are pressed before the key they modify
/// and released after it, which is what applications expect to see.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Chord(Vec<KeyCode>);

impl Chord {
    pub fn keys(&self) -> &[KeyCode] {
        &self.0
    }

    /// Parses `ctrl+shift+t` and the like.
    ///
    /// Rejects unknown names, empty parts, repeats and over-long chords, so a
    /// malformed action never becomes a partially pressed one.
    pub fn parse(text: &str) -> Result<Self, ChordError> {
        let mut keys = Vec::new();
        for name in text.split('+') {
            if name.is_empty() {
                return Err(ChordError::Empty);
            }
            if keys.len() >= MAX_CHORD_KEYS {
                return Err(ChordError::TooManyKeys);
            }
            let key = key_by_name(name).ok_or_else(|| ChordError::Unknown(name.to_owned()))?;
            if keys.contains(&key) {
                return Err(ChordError::Repeated(name.to_owned()));
            }
            keys.push(key);
        }
        if keys.is_empty() {
            return Err(ChordError::Empty);
        }
        Ok(Self(keys))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ChordError {
    Empty,
    TooManyKeys,
    Unknown(String),
    Repeated(String),
}

impl std::fmt::Display for ChordError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ChordError::Empty => formatter.write_str("empty key chord"),
            ChordError::TooManyKeys => {
                write!(formatter, "a chord may hold at most {MAX_CHORD_KEYS} keys")
            }
            ChordError::Unknown(name) => write!(formatter, "unknown key: {name}"),
            ChordError::Repeated(name) => write!(formatter, "key repeated in chord: {name}"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_a_single_key() {
        assert_eq!(
            Chord::parse("escape").map(|chord| chord.keys().to_vec()),
            Ok(vec![KeyCode::KEY_ESC])
        );
    }

    #[test]
    fn parses_a_chord_in_the_order_given() {
        let chord = Chord::parse("ctrl+shift+t").expect("a valid chord");
        assert_eq!(
            chord.keys(),
            [
                KeyCode::KEY_LEFTCTRL,
                KeyCode::KEY_LEFTSHIFT,
                KeyCode::KEY_T
            ]
        );
    }

    #[test]
    fn rejects_keys_outside_the_vocabulary() {
        // The whole point of a closed set: a control surface must not be a way
        // to press anything at all on someone's desktop.
        assert_eq!(
            Chord::parse("ctrl+sysrq"),
            Err(ChordError::Unknown("sysrq".into()))
        );
        assert!(Chord::parse("KEY_A").is_err());
        assert!(Chord::parse("ctrl+c;rm").is_err());
    }

    #[test]
    fn rejects_malformed_chords_rather_than_pressing_part_of_them() {
        assert_eq!(Chord::parse(""), Err(ChordError::Empty));
        assert_eq!(Chord::parse("ctrl+"), Err(ChordError::Empty));
        assert_eq!(Chord::parse("+c"), Err(ChordError::Empty));
        assert_eq!(
            Chord::parse("ctrl+ctrl"),
            Err(ChordError::Repeated("ctrl".into()))
        );
    }

    #[test]
    fn refuses_to_hold_down_an_unreasonable_number_of_keys() {
        assert_eq!(
            Chord::parse("ctrl+shift+alt+super+a+b"),
            Err(ChordError::TooManyKeys)
        );
    }

    #[test]
    fn every_name_maps_to_a_key_and_no_name_is_duplicated() {
        let mut seen = std::collections::HashSet::new();
        for (name, code) in NAMED_KEYS {
            assert!(seen.insert(*name), "duplicate name: {name}");
            assert_eq!(key_by_name(name), Some(*code));
        }
    }

    #[test]
    fn the_keyboard_declares_every_key_it_can_press() {
        let declared: std::collections::HashSet<_> = all_keys().collect();
        for (name, code) in NAMED_KEYS {
            assert!(declared.contains(code), "{name} is not declared");
        }
    }
}
