//! Custom shortcuts: recorded on this machine, kept on this machine.
//!
//! The closed vocabulary is what stops a control surface from being a remote
//! shell with buttons on it. It stays closed — it just becomes editable by the
//! person sitting at the keyboard. Nothing here is reachable from the phone:
//! the client may *ask* for the recorder to open, which is only another action,
//! but the chord itself is authored by somebody physically pressing keys.
//!
//! # A recorded chord is not a privileged chord
//!
//! It is written down as text, read back, and put through `Chord::parse` — the
//! same function, with the same rules, that a chord arriving on the socket goes
//! through. There is no second path into the keyboard that skips the first
//! path's checks, and a file edited by hand gets no more trust than a client
//! does.
//!
//! # Names are escaped, on disk as well as on the wire
//!
//! A name is free text a person typed, and it reaches the phone. It is written
//! with the same encoding device names use, for the same reason: a name is
//! stored on one line, and a name containing a newline would otherwise be able
//! to write its own lines into this file. Somebody will type a `%` or an
//! accented character on the first day.
//!
//! # The file
//!
//! `$XDG_CONFIG_HOME/opentrackpad/shortcuts`, one record per line, in the same
//! plain shape as everything else here so it can be read without a tool:
//!
//! ```text
//! version 1
//! next 7
//! shortcut 3 ctrl+shift+t Reopen%20closed%20tab
//! ```

use std::fmt;
use std::fs;
use std::path::PathBuf;

use evdev::KeyCode;

use crate::keys::{Chord, ChordError};
use crate::protocol::{escape_text, unescape_text};

/// The format this host writes. Read on load so a later change can tell what it
/// is looking at rather than misreading it.
#[allow(
    dead_code,
    reason = "written by save(), which the recorder window calls"
)]
const VERSION: u32 = 1;

/// How many custom shortcuts may be kept.
///
/// The phone shows these in a list that needs grouping and search already. A
/// ceiling keeps the file, the list and the snapshot that carries it bounded;
/// nobody records two hundred shortcuts, and a runaway recorder should stop
/// rather than fill a disk.
pub const MAX_SHORTCUTS: usize = 200;

/// The longest name a shortcut may carry, in characters.
///
/// It is drawn on a rail slot and in a list. Nobody reads sixty-four characters
/// on a button, but the list has room for a sentence and refusing one would be
/// rude.
pub const MAX_NAME_CHARS: usize = 64;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Shortcut {
    /// Stable for the life of the shortcut, and never reused once it is gone.
    ///
    /// The phone holds these to say which button is which. Handing a number
    /// back out after a delete would mean a stale button quietly firing
    /// somebody else's chord.
    pub id: u32,
    pub name: String,
    pub chord: Chord,
}

/// Recording, renaming and removing are the recorder window's to call, and
/// that window is the next piece. The whole editing half of this file is
/// therefore unreferenced today and tested rather than exercised; the attribute
/// comes off with the first line of the window.
#[allow(dead_code)]
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RecordError {
    /// The list is full.
    TooMany,
    /// A name of spaces is a name nobody can read on a button.
    NameEmpty,
    NameTooLong,
    /// The captured keys are not a chord this host can express.
    Chord(ChordError),
    /// Nothing here has that number.
    UnknownId,
}

impl fmt::Display for RecordError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            RecordError::TooMany => {
                write!(formatter, "at most {MAX_SHORTCUTS} shortcuts can be kept")
            }
            RecordError::NameEmpty => formatter.write_str("a shortcut needs a name"),
            RecordError::NameTooLong => {
                write!(
                    formatter,
                    "a name may be at most {MAX_NAME_CHARS} characters"
                )
            }
            RecordError::Chord(error) => write!(formatter, "{error}"),
            RecordError::UnknownId => formatter.write_str("no shortcut with that number"),
        }
    }
}

impl std::error::Error for RecordError {}

/// Everything the person at this machine has recorded.
pub struct Shortcuts {
    /// Absent when there is nowhere to keep them, so a daemon started outside a
    /// session still runs — it just forgets at the end, like the status file.
    path: Option<PathBuf>,
    entries: Vec<Shortcut>,
    #[allow(
        dead_code,
        reason = "read by record(), which the recorder window calls"
    )]
    next_id: u32,
    /// Lines the file held that could not be read. Kept so the daemon can say
    /// so once, rather than silently dropping somebody's shortcut.
    damaged: Vec<String>,
}

#[allow(dead_code)]
impl Shortcuts {
    /// Reads what is on disk, or starts empty.
    pub fn open() -> Self {
        let path = config_path();
        let text = path
            .as_ref()
            .and_then(|path| fs::read_to_string(path).ok())
            .unwrap_or_default();
        let mut shortcuts = Self::parse(&text);
        shortcuts.path = path;
        shortcuts
    }

    /// Reads the file's contents. Free of I/O, so every shape it can arrive in
    /// is testable — including the shapes only a text editor produces.
    pub fn parse(text: &str) -> Self {
        let mut entries: Vec<Shortcut> = Vec::new();
        let mut damaged = Vec::new();
        let mut declared_next = None;

        for line in text.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let mut parts = line.split_whitespace();
            match parts.next() {
                Some("version") => {}
                Some("next") => declared_next = parts.next().and_then(|value| value.parse().ok()),
                Some("shortcut") => match read_shortcut(&mut parts) {
                    // One damaged line must not cost every other shortcut, and
                    // a duplicate number is damage: two buttons answering to
                    // one id is worse than one button missing.
                    Some(shortcut)
                        if !entries.iter().any(|existing| existing.id == shortcut.id) =>
                    {
                        entries.push(shortcut)
                    }
                    _ => damaged.push(line.to_owned()),
                },
                _ => damaged.push(line.to_owned()),
            }
            if entries.len() > MAX_SHORTCUTS {
                damaged.push(format!(
                    "more than {MAX_SHORTCUTS} shortcuts; the rest were dropped"
                ));
                entries.truncate(MAX_SHORTCUTS);
                break;
            }
        }

        // Never below what the entries already use, whatever the file claimed:
        // a `next` that has been edited backwards would otherwise hand out a
        // number that is already taken.
        let highest = entries.iter().map(|entry| entry.id).max();
        let floor = highest.map_or(1, |id| id.saturating_add(1));
        let next_id = declared_next.unwrap_or(floor).max(floor);

        Self {
            path: None,
            entries,
            next_id,
            damaged,
        }
    }

    /// How the file is written.
    pub fn render(&self) -> String {
        let mut text = format!("version {VERSION}\nnext {}\n", self.next_id);
        for entry in &self.entries {
            text.push_str(&format!(
                "shortcut {} {} {}\n",
                entry.id,
                entry.chord,
                escape_text(&entry.name)
            ));
        }
        text
    }

    pub fn list(&self) -> &[Shortcut] {
        &self.entries
    }

    pub fn find(&self, id: u32) -> Option<&Shortcut> {
        self.entries.iter().find(|entry| entry.id == id)
    }

    /// Lines the file held that could not be read.
    pub fn damaged(&self) -> &[String] {
        &self.damaged
    }

    /// Whether this chord is one somebody recorded here.
    ///
    /// The question the phone's requests will be answered against once it is
    /// decided whether the recorded set gates what may be fired. Kept now so
    /// that decision does not need this file reorganised to make it.
    pub fn contains(&self, chord: &Chord) -> bool {
        self.entries.iter().any(|entry| &entry.chord == chord)
    }

    /// Adds what the recorder captured.
    ///
    /// Takes the keys that were actually pressed, not text, so nothing invents
    /// a chord on the way in. They go through the same validation a chord off
    /// the socket does.
    pub fn record(&mut self, name: &str, keys: &[KeyCode]) -> Result<u32, RecordError> {
        let name = clean_name(name)?;
        if self.entries.len() >= MAX_SHORTCUTS {
            return Err(RecordError::TooMany);
        }
        let chord = Chord::from_keys(keys).map_err(RecordError::Chord)?;
        let id = self.next_id;
        self.next_id = self.next_id.saturating_add(1);
        self.entries.push(Shortcut { id, name, chord });
        self.save();
        Ok(id)
    }

    pub fn rename(&mut self, id: u32, name: &str) -> Result<(), RecordError> {
        let name = clean_name(name)?;
        let entry = self
            .entries
            .iter_mut()
            .find(|entry| entry.id == id)
            .ok_or(RecordError::UnknownId)?;
        entry.name = name;
        self.save();
        Ok(())
    }

    /// Removes one. Anything creatable that cannot be undone is a list that
    /// only grows.
    pub fn remove(&mut self, id: u32) -> Result<(), RecordError> {
        let position = self
            .entries
            .iter()
            .position(|entry| entry.id == id)
            .ok_or(RecordError::UnknownId)?;
        self.entries.remove(position);
        // `next_id` is deliberately not wound back. A number handed out twice
        // would let a button the phone still remembers fire a different chord.
        self.save();
        Ok(())
    }

    /// Writes to a temporary file and renames it, so a reader never sees a
    /// half-written list and a crash mid-write cannot lose the old one.
    ///
    /// Failures are swallowed on purpose, as with the status file: nobody
    /// should lose their trackpad because a shortcut could not be saved. The
    /// change stays in memory for this session either way.
    fn save(&self) {
        let Some(path) = &self.path else {
            return;
        };
        if let Some(directory) = path.parent() {
            let _ = fs::create_dir_all(directory);
        }
        let temporary = path.with_extension("tmp");
        if fs::write(&temporary, self.render()).is_ok() {
            let _ = fs::rename(&temporary, path);
        }
    }
}

fn config_path() -> Option<PathBuf> {
    // These outlive a session, so they belong in the config directory rather
    // than the runtime one the status file uses.
    let mut path = match std::env::var_os("XDG_CONFIG_HOME") {
        Some(config) => PathBuf::from(config),
        None => {
            let mut home = PathBuf::from(std::env::var_os("HOME")?);
            home.push(".config");
            home
        }
    };
    path.push("opentrackpad");
    path.push("shortcuts");
    Some(path)
}

fn read_shortcut(parts: &mut std::str::SplitWhitespace<'_>) -> Option<Shortcut> {
    let id = parts.next()?.parse().ok()?;
    // The same parse a chord off the socket goes through. A file naming a key
    // this host does not know is refused exactly as a client naming one is.
    let chord = Chord::parse(parts.next()?).ok()?;
    let name = unescape_text(parts.next()?)?;
    if parts.next().is_some() {
        return None;
    }
    let name = clean_name(&name).ok()?;
    Some(Shortcut { id, name, chord })
}

fn clean_name(name: &str) -> Result<String, RecordError> {
    let name = name.trim();
    if name.is_empty() {
        return Err(RecordError::NameEmpty);
    }
    if name.chars().count() > MAX_NAME_CHARS {
        return Err(RecordError::NameTooLong);
    }
    Ok(name.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    const CTRL_SHIFT_T: &[KeyCode] = &[
        KeyCode::KEY_LEFTCTRL,
        KeyCode::KEY_LEFTSHIFT,
        KeyCode::KEY_T,
    ];

    fn empty() -> Shortcuts {
        Shortcuts::parse("")
    }

    #[test]
    fn a_recorded_shortcut_can_be_written_down_and_read_back() {
        let mut shortcuts = empty();
        let id = shortcuts.record("Reopen closed tab", CTRL_SHIFT_T).unwrap();

        let reloaded = Shortcuts::parse(&shortcuts.render());
        assert!(reloaded.damaged().is_empty());
        let entry = reloaded.find(id).expect("the shortcut should survive");
        assert_eq!(entry.name, "Reopen closed tab");
        assert_eq!(entry.chord.to_string(), "ctrl+shift+t");
    }

    #[test]
    fn a_single_key_is_a_shortcut_on_its_own() {
        // Decided in the roadmap: `print` and `f11` need no modifier.
        let mut shortcuts = empty();
        assert!(shortcuts
            .record("Screenshot", &[KeyCode::KEY_SYSRQ])
            .is_ok());
        assert!(shortcuts.record("Full screen", &[KeyCode::KEY_F11]).is_ok());
        assert_eq!(shortcuts.list()[0].chord.to_string(), "print");
        assert_eq!(shortcuts.list()[1].chord.to_string(), "f11");
    }

    #[test]
    fn a_lone_modifier_records_rather_than_being_refused() {
        // It does nothing when tapped, which is what the hold shape is for.
        // Refusing it would mean explaining that to somebody mid-recording.
        let mut shortcuts = empty();
        assert!(shortcuts
            .record("Just ctrl", &[KeyCode::KEY_LEFTCTRL])
            .is_ok());
        assert_eq!(shortcuts.list()[0].chord.to_string(), "ctrl");
    }

    #[test]
    fn a_name_a_person_typed_survives_the_file() {
        // Somebody will type a percent sign or an accent on the first day.
        let mut shortcuts = empty();
        for name in ["100% zoom", "Ação rápida", "a  b", "tab\tname"] {
            shortcuts.record(name, CTRL_SHIFT_T).unwrap();
        }
        let reloaded = Shortcuts::parse(&shortcuts.render());
        assert!(reloaded.damaged().is_empty());
        let names: Vec<_> = reloaded
            .list()
            .iter()
            .map(|entry| entry.name.as_str())
            .collect();
        assert_eq!(names, vec!["100% zoom", "Ação rápida", "a  b", "tab\tname"]);
    }

    #[test]
    fn a_name_cannot_write_its_own_lines_into_the_file() {
        // The same reason device names are escaped on the wire. Here the
        // damage would be a second shortcut nobody recorded.
        let mut shortcuts = empty();
        shortcuts
            .record("ok\nshortcut 99 alt+print+b pwned", CTRL_SHIFT_T)
            .unwrap();
        let rendered = shortcuts.render();
        assert_eq!(rendered.lines().count(), 3);

        let reloaded = Shortcuts::parse(&rendered);
        assert_eq!(reloaded.list().len(), 1);
        assert!(reloaded.find(99).is_none());
    }

    #[test]
    fn a_name_of_nothing_is_refused() {
        let mut shortcuts = empty();
        assert_eq!(
            shortcuts.record("   ", CTRL_SHIFT_T),
            Err(RecordError::NameEmpty)
        );
        assert_eq!(
            shortcuts.record(&"x".repeat(MAX_NAME_CHARS + 1), CTRL_SHIFT_T),
            Err(RecordError::NameTooLong)
        );
        assert!(shortcuts
            .record(&"x".repeat(MAX_NAME_CHARS), CTRL_SHIFT_T)
            .is_ok());
    }

    #[test]
    fn a_recorded_chord_gets_no_more_trust_than_one_off_the_socket() {
        // The same rules: nothing repeated, nothing over the ceiling, nothing
        // this host has no name for.
        let mut shortcuts = empty();
        assert_eq!(
            shortcuts.record("Doubled", &[KeyCode::KEY_LEFTCTRL, KeyCode::KEY_LEFTCTRL]),
            Err(RecordError::Chord(ChordError::Repeated("ctrl".into())))
        );
        assert_eq!(
            shortcuts.record("Nothing", &[]),
            Err(RecordError::Chord(ChordError::Empty))
        );
        assert_eq!(
            shortcuts.record(
                "Too many",
                &[
                    KeyCode::KEY_LEFTCTRL,
                    KeyCode::KEY_LEFTSHIFT,
                    KeyCode::KEY_LEFTALT,
                    KeyCode::KEY_LEFTMETA,
                    KeyCode::KEY_A,
                    KeyCode::KEY_B,
                ]
            ),
            Err(RecordError::Chord(ChordError::TooManyKeys))
        );
        // A key the host has no name for cannot become a shortcut, however it
        // was pressed.
        assert!(matches!(
            shortcuts.record("Unnamed", &[KeyCode::KEY_F24]),
            Err(RecordError::Chord(ChordError::Unknown(_)))
        ));
        assert!(shortcuts.list().is_empty());
    }

    #[test]
    fn a_hand_edited_file_gets_no_more_trust_either() {
        let shortcuts = Shortcuts::parse(
            "version 1\n\
             next 9\n\
             shortcut 1 ctrl+c Copy\n\
             shortcut 2 ctrl+nonsense Broken\n\
             shortcut 3 KEY_A Raw%20code\n\
             shortcut notanumber ctrl+v Paste\n\
             shortcut 5 ctrl+x\n\
             shortcut 6 ctrl+z has a space\n\
             nonsense line\n\
             shortcut 7 ctrl+b Bold\n",
        );
        let kept: Vec<_> = shortcuts.list().iter().map(|entry| entry.id).collect();
        assert_eq!(kept, vec![1, 7]);
        assert_eq!(shortcuts.damaged().len(), 6);
    }

    #[test]
    fn two_records_with_the_same_number_do_not_both_answer_to_it() {
        let shortcuts =
            Shortcuts::parse("version 1\nshortcut 4 ctrl+c First\nshortcut 4 ctrl+v Second\n");
        assert_eq!(shortcuts.list().len(), 1);
        assert_eq!(shortcuts.find(4).unwrap().name, "First");
        assert_eq!(shortcuts.damaged().len(), 1);
    }

    #[test]
    fn a_deleted_number_is_never_handed_out_again() {
        // The phone remembers these. Reusing one would let a button it still
        // holds quietly fire somebody else's chord.
        let mut shortcuts = empty();
        let first = shortcuts.record("First", CTRL_SHIFT_T).unwrap();
        shortcuts.remove(first).unwrap();
        let second = shortcuts.record("Second", CTRL_SHIFT_T).unwrap();
        assert_ne!(first, second);

        // And it survives a round trip through the file.
        let mut reloaded = Shortcuts::parse(&shortcuts.render());
        let third = reloaded.record("Third", CTRL_SHIFT_T).unwrap();
        assert!(third > second);
    }

    #[test]
    fn a_counter_edited_backwards_cannot_hand_out_a_number_already_taken() {
        let mut shortcuts = Shortcuts::parse("version 1\nnext 2\nshortcut 40 ctrl+c Copy\n");
        let id = shortcuts.record("New", CTRL_SHIFT_T).unwrap();
        assert!(
            id > 40,
            "handed out {id}, which collides with an existing 40"
        );
    }

    #[test]
    fn renaming_and_removing_report_a_number_that_is_not_there() {
        let mut shortcuts = empty();
        assert_eq!(shortcuts.rename(7, "New name"), Err(RecordError::UnknownId));
        assert_eq!(shortcuts.remove(7), Err(RecordError::UnknownId));

        let id = shortcuts.record("Before", CTRL_SHIFT_T).unwrap();
        shortcuts.rename(id, "  After  ").unwrap();
        assert_eq!(shortcuts.find(id).unwrap().name, "After");
        assert_eq!(shortcuts.rename(id, ""), Err(RecordError::NameEmpty));
        assert_eq!(shortcuts.find(id).unwrap().name, "After");

        shortcuts.remove(id).unwrap();
        assert!(shortcuts.find(id).is_none());
    }

    #[test]
    fn the_list_stops_growing_at_the_ceiling() {
        let mut shortcuts = empty();
        for index in 0..MAX_SHORTCUTS {
            shortcuts
                .record(&format!("Shortcut {index}"), CTRL_SHIFT_T)
                .unwrap();
        }
        assert_eq!(
            shortcuts.record("One too many", CTRL_SHIFT_T),
            Err(RecordError::TooMany)
        );
        assert_eq!(shortcuts.list().len(), MAX_SHORTCUTS);
    }

    #[test]
    fn a_file_longer_than_the_ceiling_is_cut_rather_than_believed() {
        let mut text = String::from("version 1\n");
        for id in 1..=(MAX_SHORTCUTS + 20) {
            text.push_str(&format!("shortcut {id} ctrl+c Copy\n"));
        }
        let shortcuts = Shortcuts::parse(&text);
        assert_eq!(shortcuts.list().len(), MAX_SHORTCUTS);
        assert!(!shortcuts.damaged().is_empty());
    }

    #[test]
    fn an_absent_file_is_an_empty_list_rather_than_an_error() {
        let shortcuts = Shortcuts::parse("");
        assert!(shortcuts.list().is_empty());
        assert!(shortcuts.damaged().is_empty());
        assert_eq!(shortcuts.render(), "version 1\nnext 1\n");
    }

    #[test]
    fn a_recorded_chord_can_be_recognised_again() {
        // What the phone's requests would be checked against, if the recorded
        // set is what gates them.
        let mut shortcuts = empty();
        shortcuts.record("Reopen", CTRL_SHIFT_T).unwrap();
        assert!(shortcuts.contains(&Chord::parse("ctrl+shift+t").unwrap()));
        assert!(!shortcuts.contains(&Chord::parse("ctrl+shift+n").unwrap()));
    }

    #[test]
    fn the_same_chord_may_be_recorded_twice_under_different_names() {
        // Two profiles can reasonably want one chord under two labels, and
        // refusing the second would mean explaining why mid-recording.
        let mut shortcuts = empty();
        let first = shortcuts.record("Reopen tab", CTRL_SHIFT_T).unwrap();
        let second = shortcuts.record("Undo close", CTRL_SHIFT_T).unwrap();
        assert_ne!(first, second);
        assert_eq!(shortcuts.list().len(), 2);
    }
}
