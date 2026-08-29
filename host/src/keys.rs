//! Symbolic key names, and the chords built from them.
//!
//! The client sends names rather than Linux key codes, so the two sides are not
//! coupled to the kernel's numbering and a malformed action is caught here
//! instead of becoming a wrong keystroke.
//!
//! The set is deliberately closed. Anything outside it is rejected, which is
//! what stops a control surface from turning into a way to press arbitrary keys
//! on someone's desktop.
//!
//! # Why it covers a whole keyboard now
//!
//! It used to hold the handful of keys the built-in profiles reach for. The
//! recorder captures whatever key was actually pressed on the host, so a
//! vocabulary with gaps in it fails at the moment somebody presses one — and it
//! fails by telling them their keyboard is wrong.
//!
//! Widening the names does not widen who may add a shortcut. What the phone is
//! allowed to *fire* is a separate question, kept in `crate::shortcuts`; a name
//! existing here only means the host can say what a key is called.
//!
//! # `print` is `KEY_SYSRQ`, and that is worth knowing
//!
//! Print Screen and SysRq are one key. On a kernel with `kernel.sysrq` enabled,
//! Alt held with it is the magic SysRq sequence — `alt+print+b` reboots the
//! machine without unmounting anything, and a virtual keyboard can produce it
//! as readily as a real one.
//!
//! It is in the list because the person at the machine may reasonably want Print
//! Screen on a rail button, which is the decision recorded in the roadmap. The
//! protection is not this list: it is that a chord has to be recorded by
//! somebody physically pressing it here before the phone can fire it. That
//! protection only holds if `crate::shortcuts` is what decides what may be
//! fired, which is the open question in the notes for the orchestrator.

use evdev::KeyCode;

/// The most keys one chord may hold down at once.
///
/// Real chords are two or three keys. A ceiling means a hostile client cannot
/// pin every modifier on the keyboard down at once.
pub const MAX_CHORD_KEYS: usize = 5;

/// Every key this host knows a name for.
///
/// Grouped as a keyboard is laid out rather than as the profiles use it, since
/// the recorder has to name whatever gets pressed. Adding to this list is how
/// the vocabulary grows; nothing else needs to change.
const NAMED_KEYS: &[(&str, KeyCode)] = &[
    // Modifiers.
    //
    // The left-hand key of each pair, and only that one. A shortcut is about
    // the symbol, not about which of two physical keys produced it, and a
    // recorder that told them apart would let someone save a chord their own
    // right hand could not fire.
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
    ("insert", KeyCode::KEY_INSERT),
    ("home", KeyCode::KEY_HOME),
    ("end", KeyCode::KEY_END),
    ("pageup", KeyCode::KEY_PAGEUP),
    ("pagedown", KeyCode::KEY_PAGEDOWN),
    ("left", KeyCode::KEY_LEFT),
    ("right", KeyCode::KEY_RIGHT),
    ("up", KeyCode::KEY_UP),
    ("down", KeyCode::KEY_DOWN),
    ("menu", KeyCode::KEY_COMPOSE),
    // The lock and system cluster.
    //
    // `print` is `KEY_SYSRQ`; see the note above this table.
    ("print", KeyCode::KEY_SYSRQ),
    ("scrolllock", KeyCode::KEY_SCROLLLOCK),
    ("pause", KeyCode::KEY_PAUSE),
    ("capslock", KeyCode::KEY_CAPSLOCK),
    ("numlock", KeyCode::KEY_NUMLOCK),
    // Letters. All of them now, not the handful the built-in profiles reach
    // for: the recorder captures whatever key was actually pressed, and a
    // vocabulary with gaps in it fails at the moment somebody presses one.
    ("a", KeyCode::KEY_A),
    ("b", KeyCode::KEY_B),
    ("c", KeyCode::KEY_C),
    ("d", KeyCode::KEY_D),
    ("e", KeyCode::KEY_E),
    ("f", KeyCode::KEY_F),
    ("g", KeyCode::KEY_G),
    ("h", KeyCode::KEY_H),
    ("i", KeyCode::KEY_I),
    ("j", KeyCode::KEY_J),
    ("k", KeyCode::KEY_K),
    ("l", KeyCode::KEY_L),
    ("m", KeyCode::KEY_M),
    ("n", KeyCode::KEY_N),
    ("o", KeyCode::KEY_O),
    ("p", KeyCode::KEY_P),
    ("q", KeyCode::KEY_Q),
    ("r", KeyCode::KEY_R),
    ("s", KeyCode::KEY_S),
    ("t", KeyCode::KEY_T),
    ("u", KeyCode::KEY_U),
    ("v", KeyCode::KEY_V),
    ("w", KeyCode::KEY_W),
    ("x", KeyCode::KEY_X),
    ("y", KeyCode::KEY_Y),
    ("z", KeyCode::KEY_Z),
    // Digits, on the number row.
    ("0", KeyCode::KEY_0),
    ("1", KeyCode::KEY_1),
    ("2", KeyCode::KEY_2),
    ("3", KeyCode::KEY_3),
    ("4", KeyCode::KEY_4),
    ("5", KeyCode::KEY_5),
    ("6", KeyCode::KEY_6),
    ("7", KeyCode::KEY_7),
    ("8", KeyCode::KEY_8),
    ("9", KeyCode::KEY_9),
    // Punctuation, by the symbol on an unshifted US keyboard. Named rather
    // than spelled, so a name never collides with the `+` that joins a chord
    // or with the spaces that separate protocol fields.
    ("minus", KeyCode::KEY_MINUS),
    ("equal", KeyCode::KEY_EQUAL),
    ("leftbracket", KeyCode::KEY_LEFTBRACE),
    ("rightbracket", KeyCode::KEY_RIGHTBRACE),
    ("backslash", KeyCode::KEY_BACKSLASH),
    ("semicolon", KeyCode::KEY_SEMICOLON),
    ("apostrophe", KeyCode::KEY_APOSTROPHE),
    ("grave", KeyCode::KEY_GRAVE),
    ("comma", KeyCode::KEY_COMMA),
    ("period", KeyCode::KEY_DOT),
    ("slash", KeyCode::KEY_SLASH),
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
    // The keypad, which reports its own codes and is not the number row.
    ("kp0", KeyCode::KEY_KP0),
    ("kp1", KeyCode::KEY_KP1),
    ("kp2", KeyCode::KEY_KP2),
    ("kp3", KeyCode::KEY_KP3),
    ("kp4", KeyCode::KEY_KP4),
    ("kp5", KeyCode::KEY_KP5),
    ("kp6", KeyCode::KEY_KP6),
    ("kp7", KeyCode::KEY_KP7),
    ("kp8", KeyCode::KEY_KP8),
    ("kp9", KeyCode::KEY_KP9),
    ("kpplus", KeyCode::KEY_KPPLUS),
    ("kpminus", KeyCode::KEY_KPMINUS),
    ("kpasterisk", KeyCode::KEY_KPASTERISK),
    ("kpslash", KeyCode::KEY_KPSLASH),
    ("kpenter", KeyCode::KEY_KPENTER),
    ("kpdot", KeyCode::KEY_KPDOT),
    // Media.
    ("playpause", KeyCode::KEY_PLAYPAUSE),
    ("nexttrack", KeyCode::KEY_NEXTSONG),
    ("previoustrack", KeyCode::KEY_PREVIOUSSONG),
    ("stop", KeyCode::KEY_STOPCD),
    ("volumeup", KeyCode::KEY_VOLUMEUP),
    ("volumedown", KeyCode::KEY_VOLUMEDOWN),
    ("mute", KeyCode::KEY_MUTE),
    ("micmute", KeyCode::KEY_MICMUTE),
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

/// The name this host uses for a key, if it has one.
///
/// The reverse of `key_by_name`, needed because a recorded chord arrives as key
/// codes and has to be written down: stored on disk, drawn as key caps in the
/// recorder, and listed on the phone.
pub fn name_of(code: KeyCode) -> Option<&'static str> {
    NAMED_KEYS
        .iter()
        .find(|(_, known)| *known == code)
        .map(|(name, _)| *name)
}

/// The table's own copy of a name, if it holds one.
///
/// Import builds chord text out of names it has translated from another
/// desktop's spelling. Asking the table for the name rather than trusting the
/// translation means a name that has been renamed or removed here fails at the
/// lookup instead of turning into a chord that no longer parses.
pub fn canonical_name(name: &str) -> Option<&'static str> {
    NAMED_KEYS
        .iter()
        .find(|(known, _)| *known == name)
        .map(|(known, _)| *known)
}

/// Every key the virtual keyboard must declare it can produce.
pub fn all_keys() -> impl Iterator<Item = KeyCode> {
    NAMED_KEYS.iter().map(|(_, code)| *code)
}

/// Folds a right-hand modifier onto its left-hand twin.
///
/// A hand reaches for whichever Shift is nearer, and a shortcut is about the
/// symbol rather than which of two keys produced it. Refusing the right-hand
/// one instead would mean somebody presses the Shift their hand naturally finds
/// and the app tells them their keyboard is wrong — they would not conclude it
/// was being careful.
///
/// Applied when recording, not when parsing: text arriving on the socket names
/// `shift`, which is already the left-hand key, and there is nothing to fold.
///
/// One caveat worth knowing. Right Alt is AltGr on most layouts outside the
/// United States, where it is a level-three shift for typing accented
/// characters rather than a modifier — so folding it onto Alt is the least
/// certain of the four. It is folded anyway, for consistency and because a
/// shortcut built on AltGr would collide with typing on those layouts, which is
/// its own reason not to have one.
fn normalise(code: KeyCode) -> KeyCode {
    match code {
        KeyCode::KEY_RIGHTCTRL => KeyCode::KEY_LEFTCTRL,
        KeyCode::KEY_RIGHTSHIFT => KeyCode::KEY_LEFTSHIFT,
        KeyCode::KEY_RIGHTALT => KeyCode::KEY_LEFTALT,
        KeyCode::KEY_RIGHTMETA => KeyCode::KEY_LEFTMETA,
        other => other,
    }
}

/// The one combination this host will not press, however it is asked.
///
/// Alt with SysRq is not a shortcut; it is the kernel's escape hatch. On a
/// machine with `kernel.sysrq` enabled, `alt+print+b` reboots immediately
/// without unmounting anything, and there is no application in between that
/// could refuse or undo it. A uinput device produces it exactly as a real
/// keyboard does.
///
/// `print` on its own stays available, because Print Screen on a rail button is
/// the thing the roadmap actually decided to allow. This refuses only the pair,
/// and it refuses it on both ways in — off the socket and out of the recorder —
/// because a rule that only one path enforces is a rule with a way around it.
///
/// If somebody one day genuinely wants this on a phone button, that is a
/// deliberate decision to take with the reasoning above in front of them, not
/// something to arrive at by adding a key name.
fn refuses_to_hold(keys: &[KeyCode]) -> Option<ChordError> {
    if keys.contains(&KeyCode::KEY_SYSRQ) && keys.contains(&KeyCode::KEY_LEFTALT) {
        return Some(ChordError::MagicSysRq);
    }
    None
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
        if let Some(refusal) = refuses_to_hold(&keys) {
            return Err(refusal);
        }
        Ok(Self(keys))
    }

    /// Builds a chord from keys the recorder actually saw pressed.
    ///
    /// Goes through the same checks a chord off the wire does — every key must
    /// be one this host has a name for and the ceiling still applies. A
    /// recorded chord is not a privileged chord: it is written down, read back
    /// and validated exactly like any other, so there is no second way into the
    /// keyboard that skips the first way's rules.
    ///
    /// The one thing it does that parsing does not is `normalise`: a hand on a
    /// keyboard reaches for whichever Shift is nearer, and a chord is about the
    /// symbol rather than which key produced it.
    ///
    /// Not called yet: the recorder window is the only caller and it is the
    /// next piece. Kept here because it is the validated way in, and the window
    /// should find it waiting rather than invent its own.
    #[allow(dead_code)]
    pub fn from_keys(keys: &[KeyCode]) -> Result<Self, ChordError> {
        let mut chord: Vec<KeyCode> = Vec::with_capacity(keys.len());
        for key in keys {
            let key = normalise(*key);
            // Both Shifts held at once is a hand, not a mistake. Once they are
            // the same key it is one key, and saying so out loud would be
            // telling somebody their keyboard is wrong.
            if chord.contains(&key) {
                continue;
            }
            if chord.len() >= MAX_CHORD_KEYS {
                return Err(ChordError::TooManyKeys);
            }
            if name_of(key).is_none() {
                return Err(ChordError::Unknown(format!("{key:?}")));
            }
            chord.push(key);
        }
        if chord.is_empty() {
            return Err(ChordError::Empty);
        }
        if let Some(refusal) = refuses_to_hold(&chord) {
            return Err(refusal);
        }
        Ok(Self(chord))
    }
}

impl std::fmt::Display for Chord {
    /// Writes the chord the way it is read: `ctrl+shift+t`.
    ///
    /// Every key in a chord came from the table, so every one has a name and
    /// this always round-trips through `parse`.
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        for (position, key) in self.0.iter().enumerate() {
            if position > 0 {
                formatter.write_str("+")?;
            }
            formatter.write_str(name_of(*key).unwrap_or("?"))?;
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ChordError {
    Empty,
    /// Alt with SysRq: the kernel escape hatch, not a shortcut.
    MagicSysRq,
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
            ChordError::MagicSysRq => {
                formatter.write_str("alt with print is the kernel magic SysRq sequence")
            }
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

    #[test]
    fn a_single_key_is_a_chord_on_its_own() {
        // Decided in the roadmap: `print` and `f11` need no modifier.
        assert_eq!(Chord::parse("print").unwrap().to_string(), "print");
        assert_eq!(Chord::parse("f11").unwrap().to_string(), "f11");
    }

    #[test]
    fn a_lone_modifier_is_accepted_rather_than_refused() {
        // It does nothing when tapped, which is what the hold shape exists for.
        // Refusing it would mean explaining that to somebody mid-recording.
        assert_eq!(
            Chord::parse("ctrl").unwrap().keys(),
            [KeyCode::KEY_LEFTCTRL]
        );
        assert_eq!(
            Chord::parse("super").unwrap().keys(),
            [KeyCode::KEY_LEFTMETA]
        );
    }

    #[test]
    fn alt_with_print_is_refused_on_both_ways_in() {
        // Magic SysRq: `alt+print+b` reboots without unmounting, with no
        // application in between that could refuse it. A rule only one path
        // enforces is a rule with a way around it, so both paths carry it.
        assert_eq!(Chord::parse("alt+print"), Err(ChordError::MagicSysRq));
        assert_eq!(Chord::parse("alt+print+b"), Err(ChordError::MagicSysRq));
        assert_eq!(Chord::parse("print+alt"), Err(ChordError::MagicSysRq));
        assert_eq!(
            Chord::from_keys(&[KeyCode::KEY_LEFTALT, KeyCode::KEY_SYSRQ]),
            Err(ChordError::MagicSysRq)
        );

        // Print Screen itself is untouched, which is the thing that was
        // actually asked for.
        assert!(Chord::parse("print").is_ok());
        assert!(Chord::parse("ctrl+print").is_ok());
        assert!(Chord::parse("shift+print").is_ok());
        assert!(Chord::from_keys(&[KeyCode::KEY_SYSRQ]).is_ok());
    }

    #[test]
    fn a_chord_survives_being_written_down_and_read_back() {
        // The recorder captures key codes and has to store them as text; a
        // chord that did not round-trip would come back as a different one.
        for text in [
            "ctrl+shift+t",
            "print",
            "super",
            "kp5",
            "ctrl+alt+period",
            "shift+leftbracket",
            "f11",
        ] {
            let chord = Chord::parse(text).expect("a valid chord");
            assert_eq!(chord.to_string(), text);
            assert_eq!(Chord::parse(&chord.to_string()), Ok(chord));
        }
    }

    #[test]
    fn keys_captured_by_the_recorder_become_the_same_chord_as_the_text_would() {
        // The one path, stated as a test: however a chord arrives, it is the
        // same chord and it passed the same checks.
        assert_eq!(
            Chord::from_keys(&[
                KeyCode::KEY_LEFTCTRL,
                KeyCode::KEY_LEFTSHIFT,
                KeyCode::KEY_T
            ]),
            Chord::parse("ctrl+shift+t")
        );
    }

    #[test]
    fn a_key_this_host_has_no_name_for_cannot_become_a_chord() {
        // However it was physically pressed. The recorder cannot widen the
        // vocabulary by capturing something outside it.
        assert!(matches!(
            Chord::from_keys(&[KeyCode::KEY_F24]),
            Err(ChordError::Unknown(_))
        ));
    }

    #[test]
    fn the_shift_a_hand_reaches_for_is_the_shift_that_records() {
        // Either one, and both at once. Telling somebody their keyboard is
        // wrong is not the same as being careful.
        assert_eq!(
            Chord::from_keys(&[KeyCode::KEY_RIGHTSHIFT, KeyCode::KEY_T]),
            Chord::parse("shift+t")
        );
        assert_eq!(
            Chord::from_keys(&[
                KeyCode::KEY_RIGHTCTRL,
                KeyCode::KEY_RIGHTSHIFT,
                KeyCode::KEY_T
            ]),
            Chord::parse("ctrl+shift+t")
        );
        assert_eq!(
            Chord::from_keys(&[KeyCode::KEY_RIGHTALT, KeyCode::KEY_A]),
            Chord::parse("alt+a")
        );
        assert_eq!(
            Chord::from_keys(&[KeyCode::KEY_RIGHTMETA]),
            Chord::parse("super")
        );
    }

    #[test]
    fn holding_both_shifts_is_a_hand_rather_than_a_mistake() {
        assert_eq!(
            Chord::from_keys(&[
                KeyCode::KEY_LEFTSHIFT,
                KeyCode::KEY_RIGHTSHIFT,
                KeyCode::KEY_T
            ]),
            Chord::parse("shift+t")
        );
    }

    #[test]
    fn the_right_hand_key_cannot_slip_past_the_sysrq_rule() {
        // Folding happens before the refusal is checked, so the pair is caught
        // whichever Alt was pressed.
        assert_eq!(
            Chord::from_keys(&[KeyCode::KEY_RIGHTALT, KeyCode::KEY_SYSRQ]),
            Err(ChordError::MagicSysRq)
        );
    }

    #[test]
    fn every_name_in_the_table_can_be_written_back_out() {
        for (name, code) in NAMED_KEYS {
            assert_eq!(name_of(*code), Some(*name), "{name} has no way back");
        }
    }
}
