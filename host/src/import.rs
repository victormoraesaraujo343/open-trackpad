//! Reading the shortcuts already configured on this computer, so a fresh
//! install can offer them rather than asking for them.
//!
//! Now that the recorded list is what decides which chords the phone may fire,
//! a fresh install cannot start empty — that is a phone whose buttons all
//! silently do nothing. The universal application conventions ship recorded
//! (see `crate::shortcuts`). This is the other half: locking the screen,
//! showing all windows, taking a screenshot. Those differ per desktop *and* per
//! person, they cannot be guessed, and they are sitting in a file.
//!
//! # Why this costs nothing in security
//!
//! An imported chord was configured on this machine by this person and already
//! fires on this keyboard. Reading it is the same trust as watching them press
//! it, and importing a chord that already works cannot make anything reachable
//! that was not reachable before.
//!
//! Two rules keep that true, and neither is incidental:
//!
//! - the reading happens here, on the host. A list is never taken from the
//!   client — nothing the phone says is involved in deciding what the phone may
//!   fire;
//! - an imported chord goes through `Chord::parse`, the identical function a
//!   hand-recorded chord and a chord off the socket go through. There is no
//!   trusted path for our own list. A shortcut this machine has that this host
//!   refuses stays refused, and is simply not offered.
//!
//! # Reading a desktop it has never heard of
//!
//! It offers nothing and says so. An unknown desktop means the conventions
//! alone, which still work, plus recording by hand. Failing a session over a
//! missing config file would be absurd.

use std::fmt;

use crate::keys::Chord;

/// One shortcut found on this computer, waiting to be offered.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Candidate {
    /// What the desktop calls the group it belongs to — "Audio Volume",
    /// "Window Management". The review screen groups by it.
    pub group: String,
    pub name: String,
    pub chord: Chord,
}

/// Where the shortcuts on this computer are kept.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Source {
    /// `~/.config/kglobalshortcutsrc`, a plain text file.
    Kde,
    /// A desktop this host has not been taught to read.
    Unknown,
}

impl fmt::Display for Source {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Source::Kde => formatter.write_str("KDE"),
            Source::Unknown => formatter.write_str("an unrecognised desktop"),
        }
    }
}

/// Which desktop is running, from what it says about itself.
///
/// `XDG_CURRENT_DESKTOP` may hold several names separated by colons — a session
/// can be "KDE" and something else at once — so this looks through all of them
/// rather than at the first.
pub fn detect(current_desktop: Option<&str>) -> Source {
    let Some(names) = current_desktop else {
        return Source::Unknown;
    };
    for name in names.split(':') {
        if name.eq_ignore_ascii_case("KDE") || name.eq_ignore_ascii_case("plasma") {
            return Source::Kde;
        }
    }
    Source::Unknown
}

/// Everything this computer has that could be offered.
///
/// Never fails: a desktop with nothing to read, or nothing readable, offers an
/// empty list.
pub fn read() -> (Source, Vec<Candidate>) {
    let source = detect(std::env::var("XDG_CURRENT_DESKTOP").ok().as_deref());
    let candidates = match source {
        Source::Kde => std::env::var_os("HOME")
            .map(|home| {
                std::path::PathBuf::from(home)
                    .join(".config")
                    .join("kglobalshortcutsrc")
            })
            .and_then(|path| std::fs::read_to_string(path).ok())
            .map(|text| from_kde(&text))
            .unwrap_or_default(),
        Source::Unknown => Vec::new(),
    };
    (source, candidates)
}

/// Reads KDE's `kglobalshortcutsrc`.
///
/// The shape, which is worth stating because nothing documents it:
///
/// ```text
/// [kwin]
/// _k_friendly_name=KWin
/// Window Maximize=Meta+PgUp,Meta+PgUp,Maximize Window
/// Lock Session=Screensaver\tMeta+L,Screensaver\tMeta+L,Lock Session
/// ```
///
/// The value is the binding now in force, the binding it shipped with, and the
/// name a person reads — in that order. `none` means unbound. A literal `\t`
/// separates alternative bindings for one action, which is why `Lock Session`
/// above has both a laptop's lock key and a chord: the first that this host can
/// express is the one taken, so an entry naming a key we have no word for still
/// yields its chord.
///
/// `_k_friendly_name` names the group and may appear anywhere in its section,
/// so groups are resolved after the whole file has been read rather than as it
/// goes.
pub fn from_kde(text: &str) -> Vec<Candidate> {
    let mut groups: Vec<(String, String)> = Vec::new();
    let mut found: Vec<(String, String, Chord)> = Vec::new();
    let mut section = String::new();

    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if let Some(name) = line
            .strip_prefix('[')
            .and_then(|rest| rest.strip_suffix(']'))
        {
            section = name.to_owned();
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        let key = key.trim();
        if key == "_k_friendly_name" {
            groups.push((section.clone(), unescape(value.trim())));
            continue;
        }
        // The name is the last field and may itself contain commas, so the
        // value is cut from the left exactly twice and the remainder is left
        // whole. Guessing at the escaping instead would mangle every name with
        // a comma in it.
        let mut fields = value.splitn(3, ',');
        let binding = fields.next().unwrap_or_default();
        let _shipped_with = fields.next();
        let name = fields.next().unwrap_or_default().trim();

        let Some(chord) = read_binding(binding) else {
            continue;
        };
        let name = if name.is_empty() {
            key.to_owned()
        } else {
            unescape(name)
        };
        found.push((section.clone(), name, chord));
    }

    let mut candidates: Vec<Candidate> = found
        .into_iter()
        .map(|(section, name, chord)| Candidate {
            group: groups
                .iter()
                .find(|(raw, _)| *raw == section)
                .map(|(_, friendly)| friendly.clone())
                .unwrap_or(section),
            name,
            chord,
        })
        .collect();
    // A desktop can name the same action twice under different config keys —
    // KDE really does, for a widget that has moved between panels. Two
    // identical rows on the review screen is somebody wondering which to tick.
    candidates.dedup_by(|left, right| left == right);
    candidates
}

/// Takes the first of an entry's alternative bindings that this host can say.
fn read_binding(binding: &str) -> Option<Chord> {
    let binding = binding.trim();
    if binding.is_empty() || binding.eq_ignore_ascii_case("none") {
        return None;
    }
    // A literal backslash-t, not a tab: the file escapes it.
    binding.split("\\t").find_map(|alternative| {
        let names = alternative
            .trim()
            .split('+')
            .map(str::trim)
            .map(key_name)
            .collect::<Option<Vec<_>>>()?;
        // Through the same parse everything else goes through. An imported
        // chord is not a trusted chord: if a rule refuses it — the SysRq pair,
        // the chord ceiling — it is simply not offered.
        Chord::parse(&names.join("+")).ok()
    })
}

/// Translates one key from what KDE calls it to what this host calls it.
///
/// Returns nothing for a key with no word here, which drops that binding rather
/// than the whole entry.
fn key_name(kde: &str) -> Option<&'static str> {
    // Single letters and digits are already the name, once lowered.
    if kde.len() == 1 {
        let character = kde.chars().next()?;
        if character.is_ascii_alphanumeric() {
            return crate::keys::canonical_name(&character.to_ascii_lowercase().to_string());
        }
    }
    let name = match kde {
        "Meta" | "Super" => "super",
        "Ctrl" | "Control" => "ctrl",
        "Alt" => "alt",
        "Shift" => "shift",
        "Esc" | "Escape" => "escape",
        "Tab" => "tab",
        "Return" | "Enter" => "enter",
        "Space" => "space",
        "Backspace" => "backspace",
        "Del" | "Delete" => "delete",
        "Ins" | "Insert" => "insert",
        "Home" => "home",
        "End" => "end",
        "PgUp" | "PageUp" => "pageup",
        "PgDown" | "PageDown" => "pagedown",
        "Left" => "left",
        "Right" => "right",
        "Up" => "up",
        "Down" => "down",
        "Menu" => "menu",
        "Print" | "SysReq" => "print",
        "Pause" => "pause",
        "ScrollLock" => "scrolllock",
        "CapsLock" => "capslock",
        "NumLock" => "numlock",
        // The keys a laptop has above the number row. KDE names them as words.
        "Volume Up" => "volumeup",
        "Volume Down" => "volumedown",
        "Volume Mute" => "mute",
        "Microphone Mute" => "micmute",
        "Media Play" | "Media Pause" | "Media Play/Pause" | "Toggle Media Play/Pause" => {
            "playpause"
        }
        "Media Next" => "nexttrack",
        "Media Previous" => "previoustrack",
        "Media Stop" => "stop",
        "Monitor Brightness Up" => "brightnessup",
        "Monitor Brightness Down" => "brightnessdown",
        "-" => "minus",
        "=" => "equal",
        "[" => "leftbracket",
        "]" => "rightbracket",
        "\\" => "backslash",
        ";" => "semicolon",
        "'" => "apostrophe",
        "`" => "grave",
        "," => "comma",
        "." => "period",
        "/" => "slash",
        other if other.len() > 1 && other.starts_with('F') => {
            return other[1..]
                .parse::<u8>()
                .ok()
                .filter(|number| (1..=12).contains(number))
                .and_then(|number| crate::keys::canonical_name(&format!("f{number}")));
        }
        _ => return None,
    };
    Some(name)
}

/// Undoes the escaping KDE writes into a name.
fn unescape(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut characters = text.chars();
    while let Some(character) = characters.next() {
        if character != '\\' {
            out.push(character);
            continue;
        }
        match characters.next() {
            Some('\\') => {
                // A doubled backslash before a comma is how a comma inside a
                // name survives the file. Anywhere else it is just a backslash.
                if characters.as_str().starts_with(',') {
                    characters.next();
                    out.push(',');
                } else {
                    out.push('\\');
                }
            }
            Some(',') => out.push(','),
            Some('t') => out.push(' '),
            Some('n') => out.push(' '),
            Some('s') => out.push(' '),
            Some(other) => {
                out.push('\\');
                out.push(other);
            }
            None => out.push('\\'),
        }
    }
    out.trim().to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = "\
[kwin]
_k_friendly_name=KWin
Window Maximize=Meta+PgUp,Meta+PgUp,Maximize Window
Show Desktop=Meta+D,Meta+D,Peek at Desktop
Overview=Meta+W,Meta+W,Toggle Overview
Cycle Overview=none,none,Cycle through Overview and Grid View

[ksmserver]
Lock Session=Screensaver\\tMeta+L,Screensaver\\tMeta+L,Lock Session
Log Out=Ctrl+Alt+Del,Ctrl+Alt+Del,Show Logout Screen
_k_friendly_name=Session Management

[kmix]
_k_friendly_name=Audio Volume
increase_volume=Volume Up,Volume Up,Increase Volume
mute=Volume Mute,Volume Mute,Mute
push_to_talk=none,none,Push to talk
";

    fn find<'a>(candidates: &'a [Candidate], name: &str) -> Option<&'a Candidate> {
        candidates.iter().find(|candidate| candidate.name == name)
    }

    #[test]
    fn reads_the_shortcuts_this_computer_actually_has() {
        let found = from_kde(SAMPLE);
        assert_eq!(found.len(), 7);
        let maximize = find(&found, "Maximize Window").expect("found");
        assert_eq!(maximize.chord.to_string(), "super+pageup");
        assert_eq!(maximize.group, "KWin");
    }

    #[test]
    fn an_unbound_shortcut_is_not_offered() {
        let found = from_kde(SAMPLE);
        assert!(find(&found, "Cycle through Overview and Grid View").is_none());
        assert!(find(&found, "Push to talk").is_none());
    }

    #[test]
    fn a_group_is_named_however_the_file_orders_it() {
        // `_k_friendly_name` comes first in one section here and last in
        // another, which is exactly what the real file does.
        let found = from_kde(SAMPLE);
        assert_eq!(
            find(&found, "Lock Session").unwrap().group,
            "Session Management"
        );
        assert_eq!(find(&found, "Mute").unwrap().group, "Audio Volume");
    }

    #[test]
    fn the_first_binding_this_host_can_say_is_the_one_taken() {
        // "Lock Session" is bound to a laptop's lock key and to a chord. There
        // is no word here for the lock key, and dropping the entry over it
        // would lose a shortcut this machine really has.
        let found = from_kde(SAMPLE);
        assert_eq!(
            find(&found, "Lock Session").unwrap().chord.to_string(),
            "super+l"
        );
    }

    #[test]
    fn keys_a_laptop_has_above_the_number_row_are_understood() {
        let found = from_kde(SAMPLE);
        assert_eq!(
            find(&found, "Increase Volume").unwrap().chord.to_string(),
            "volumeup"
        );
        assert_eq!(find(&found, "Mute").unwrap().chord.to_string(), "mute");
    }

    #[test]
    fn a_name_with_a_comma_in_it_survives() {
        // The name is the last field and the file escapes commas inside it.
        // Splitting on every comma would cut this one in half.
        let found = from_kde(
            "[x]\nSwitch=Meta+K,Meta+K,Switch to English (US\\\\, intl.\\\\, with dead keys)\n",
        );
        assert_eq!(found.len(), 1);
        assert_eq!(
            found[0].name,
            "Switch to English (US, intl., with dead keys)"
        );
    }

    #[test]
    fn an_entry_with_no_name_falls_back_to_its_key() {
        let found = from_kde("[x]\nWindow Maximize=Meta+P,Meta+P,\n");
        assert_eq!(found[0].name, "Window Maximize");
    }

    #[test]
    fn a_key_with_no_word_here_drops_that_entry_and_nothing_else() {
        let found = from_kde(
            "[x]\n\
             Weird=Meta+Launch (A),Meta+Launch (A),Launch something\n\
             Fine=Meta+M,Meta+M,Something normal\n",
        );
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].name, "Something normal");
    }

    #[test]
    fn an_imported_chord_gets_no_more_trust_than_a_recorded_one() {
        // The rule this whole file rests on. A shortcut this machine really has
        // that this host refuses stays refused — it is simply not offered.
        let found = from_kde("[x]\nReboot=Alt+Print+B,Alt+Print+B,Emergency reboot\n");
        assert!(found.is_empty());

        let found = from_kde("[x]\nHuge=Meta+Ctrl+Alt+Shift+A+B,x,Six keys\n");
        assert!(found.is_empty());
    }

    #[test]
    fn nonsense_in_the_file_is_skipped_rather_than_fatal() {
        let found = from_kde(
            "not a section\n\
             [x]\n\
             = \n\
             novalue\n\
             Fine=Meta+M,Meta+M,Something normal\n\
             #comment=Meta+Q,Meta+Q,Commented out\n",
        );
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].name, "Something normal");
    }

    #[test]
    fn an_empty_or_absent_file_offers_nothing() {
        assert!(from_kde("").is_empty());
        assert!(from_kde("[kwin]\n_k_friendly_name=KWin\n").is_empty());
    }

    #[test]
    fn function_keys_are_read_and_nonsense_ones_are_not() {
        let found = from_kde(
            "[x]\n\
             A=Ctrl+F8,x,Desktop grid\n\
             B=Ctrl+F99,x,Not a key\n\
             C=Ctrl+Foo,x,Also not a key\n",
        );
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].chord.to_string(), "ctrl+f8");
    }

    #[test]
    fn a_desktop_names_itself_however_many_ways_it_likes() {
        assert_eq!(detect(Some("KDE")), Source::Kde);
        assert_eq!(detect(Some("plasma")), Source::Kde);
        assert_eq!(detect(Some("KDE:plasmawayland")), Source::Kde);
        assert_eq!(detect(Some("X-Cinnamon:KDE")), Source::Kde);
        assert_eq!(detect(Some("GNOME")), Source::Unknown);
        assert_eq!(detect(Some("")), Source::Unknown);
        assert_eq!(detect(None), Source::Unknown);
    }

    #[test]
    fn the_same_action_named_twice_is_offered_once() {
        // KDE really does this: a widget that has moved between panels keeps
        // an entry under each config key it has had. Two identical rows on the
        // review screen is somebody wondering which one to tick.
        let found = from_kde(
            "[plasmashell]\n\
             activate widget 104=Alt+F1,none,Activate Application Launcher Widget\n\
             activate widget 51=Alt+F1,none,Activate Application Launcher Widget\n",
        );
        assert_eq!(found.len(), 1);

        // Two genuinely different actions on one chord are still both offered:
        // that is a clash the person should see, not one to hide.
        let found = from_kde("[x]\nA=Meta+K,none,First thing\nB=Meta+K,none,Second thing\n");
        assert_eq!(found.len(), 2);
    }
}
