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
use crate::shortcuts::Group;

/// One shortcut found on this computer, waiting to be offered.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Candidate {
    /// Which of the one vocabulary this belongs to, worked out here from the
    /// desktop's own untranslated action key. The raw `kwin` and
    /// `org.gnome.shell` never leave the host.
    pub group: Group,
    /// Whether to offer this one first. Seventy-five arrive on a KDE machine
    /// and about a dozen are things anyone would want on a phone rail.
    pub recommended: bool,
    pub name: String,
    pub chord: Chord,
}

/// Where the shortcuts on this computer are kept.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Source {
    /// `~/.config/kglobalshortcutsrc`, a plain text file.
    Kde,
    /// GNOME's settings store, asked through `gsettings`. Not a file: the
    /// shortcuts are spread over several schemas, and a person's own ones sit
    /// one relocatable schema apiece.
    Gnome,
    /// A desktop this host has not been taught to read.
    Unknown,
}

impl fmt::Display for Source {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Source::Kde => formatter.write_str("KDE"),
            Source::Gnome => formatter.write_str("GNOME"),
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
        // GNOME calls itself several things — plain, Classic, and prefixed by
        // whoever packaged it — so this matches the family rather than a name.
        let upper = name.to_ascii_uppercase();
        if upper == "GNOME" || upper.starts_with("GNOME-") {
            return Source::Gnome;
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
        Source::Gnome => read_gnome(),
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
    let mut found: Vec<(String, String, String, Chord)> = Vec::new();
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
        found.push((section.clone(), key.to_owned(), name, chord));
    }

    let mut candidates: Vec<Candidate> = found
        .into_iter()
        .map(|(section, key, name, chord)| {
            // The friendly name is only used to help classify; what travels is
            // the normalised group, never the desktop's own word for it.
            let friendly = groups
                .iter()
                .find(|(raw, _)| *raw == section)
                .map(|(_, friendly)| friendly.as_str())
                .unwrap_or(section.as_str());
            Candidate {
                group: classify(&format!("{section} {friendly}"), &key),
                recommended: is_recommended(&key),
                name,
                chord,
            }
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

/// The GNOME schemas worth reading, and what to call each group.
///
/// GNOME keeps shortcuts in its settings store rather than a file, so this is a
/// list of places to ask rather than a path to read. Schemas that are not
/// installed simply answer nothing, which is what makes it safe to ask for all
/// of them on a machine that has only some.
const GNOME_SCHEMAS: &[(&str, &str)] = &[
    ("org.gnome.desktop.wm.keybindings", "Windows"),
    ("org.gnome.mutter.keybindings", "Windows"),
    ("org.gnome.mutter.wayland.keybindings", "Windows"),
    ("org.gnome.shell.keybindings", "System"),
    (
        "org.gnome.settings-daemon.plugins.media-keys",
        "Sound and Media",
    ),
];

/// Where a person's own shortcuts live, one relocatable schema per entry.
const GNOME_CUSTOM_SCHEMA: &str = "org.gnome.settings-daemon.plugins.media-keys.custom-keybinding";

/// Real words for the GNOME settings keys somebody would actually put on a rail.
///
/// GNOME keeps no readable name anywhere a program can reach, so without this
/// every row on the review screen is a settings key with its hyphens taken out
/// — and `screensaver` for "lock the screen" is not merely plain, it is wrong.
/// The person looking at that screen has no idea one desktop stores readable
/// names and the other does not; they would read it as this list being sloppier
/// on their machine.
///
/// Two rules keep the table safe, and both are pinned by tests:
///
/// - it maps a key to **words, never to a chord**. The chord always comes from
///   that person's own settings, so an entry here can change what a shortcut is
///   called and can never change what it does;
/// - an entry matching nothing simply never fires. A GNOME release that renames
///   a key degrades to the prettified key rather than showing a name that has
///   quietly become wrong.
///
/// Short on purpose. Everything outside it still gets a readable name, just a
/// plainer one.
const GNOME_NAMES: &[(&str, &str)] = &[
    // Where the derived name is actively misleading rather than merely plain.
    ("screensaver", "Lock the screen"),
    ("panel-run-dialog", "Run a command"),
    ("show-screenshot-ui", "Take a screenshot"),
    ("toggle-application-view", "All applications"),
    ("www", "Web browser"),
    ("mic-mute", "Mute microphone"),
    ("play", "Play or pause"),
    ("unmaximize", "Restore window"),
    // Windows.
    ("close", "Close window"),
    ("maximize", "Maximise window"),
    ("minimize", "Minimise window"),
    ("toggle-fullscreen", "Full screen"),
    ("begin-move", "Move window"),
    ("begin-resize", "Resize window"),
    ("activate-window-menu", "Window menu"),
    ("switch-windows", "Switch windows"),
    ("switch-applications", "Switch applications"),
    ("show-desktop", "Show desktop"),
    // Workspaces, which are most of what anyone puts on a rail.
    ("switch-to-workspace-left", "Workspace left"),
    ("switch-to-workspace-right", "Workspace right"),
    ("switch-to-workspace-up", "Workspace up"),
    ("switch-to-workspace-down", "Workspace down"),
    ("move-to-workspace-left", "Move window one workspace left"),
    ("move-to-workspace-right", "Move window one workspace right"),
    // The shell.
    ("toggle-overview", "Overview"),
    ("toggle-message-tray", "Notifications"),
    ("toggle-quick-settings", "Quick settings"),
    ("screenshot", "Screenshot"),
    ("screenshot-window", "Screenshot a window"),
    // Sound, media and the keys above the number row.
    ("volume-up", "Volume up"),
    ("volume-down", "Volume down"),
    ("volume-mute", "Mute"),
    ("next", "Next track"),
    ("previous", "Previous track"),
    ("screen-brightness-up", "Brightness up"),
    ("screen-brightness-down", "Brightness down"),
    ("logout", "Log out"),
];

/// The words for a settings key: curated where it matters, prettified otherwise.
fn gnome_name(key: &str) -> String {
    GNOME_NAMES
        .iter()
        .find(|(known, _)| *known == key)
        .map(|(_, words)| (*words).to_owned())
        .unwrap_or_else(|| readable(key))
}

/// Sorts a shortcut into the one vocabulary, from its desktop's own words.
///
/// # Why this reads the action key and not the name
///
/// The key is a stable identifier — KDE's `Window Close`, GNOME's
/// `switch-to-workspace-left` — and it is the same on a machine in any
/// language. The *name* beside it is translated, so a rule that read names
/// would work until somebody installed a language pack. That is exactly why
/// this belongs on the host: the client only ever sees the translated name.
///
/// Unrecognised goes to `Other`, deliberately, rather than to whichever bucket
/// looks closest. Somebody hunting in "Windows" for something we filed there
/// wrongly will not find it and will not think to look in "Other"; somebody
/// hunting for a thing that is nowhere else will open "Other".
fn classify(source_group: &str, key: &str) -> Group {
    let text = format!("{source_group} {key}").to_ascii_lowercase();
    let has = |needle: &str| text.contains(needle);

    // Order matters: the first confident answer wins, and the narrower tests
    // come before the broader ones. "Screenshot window" is a screenshot, not a
    // window.
    if has("screenshot") || has("screencast") || has("screenrecord") {
        return Group::Screenshot;
    }
    if has("accessib") || has("screen reader") || has("magnif") || has("kaccess") {
        return Group::Accessibility;
    }
    if has("keyboard layout") || has("switch keyboard") || has("input method") {
        return Group::Keyboard;
    }
    // Phrases rather than a bare "lock": Caps Lock and Scroll Lock are keys,
    // not session actions, and filing them under Session would put them
    // somewhere nobody would ever look.
    if has("lock session")
        || has("lock screen")
        || has("screen lock")
        || has("screensaver")
        || has("log out")
        || has("logout")
        || has("session")
        || has("switch user")
        || has("shut down")
        || has("reboot")
    {
        return Group::Session;
    }
    if has("suspend") || has("hibernate") || has("sleep") || has("power") || has("brightness") {
        return Group::Power;
    }
    if has("volume") || has("microphone") || has("mic") || has("mute") || has("audio") {
        return Group::Sound;
    }
    if has("play") || has("pause") || has("track") || has("media") || has("song") {
        return Group::Media;
    }
    if has("desktop")
        || has("workspace")
        || has("overview")
        || has("activity")
        || has("panel")
        || has("launcher")
        || has("dashboard")
        || has("krunner")
        || has("plasmashell")
    {
        return Group::Desktop;
    }
    if has("window")
        || has("maximize")
        || has("minimize")
        || has("maximise")
        || has("minimise")
        || has("tile")
        || has("resize")
        || has("kwin")
        || has("mutter")
    {
        return Group::Windows;
    }
    Group::Other
}

/// The shortcuts worth offering first, out of the seventy-five a desktop has.
///
/// Deliberately a short list of exact things rather than a rule with judgement
/// in it. Everything here is something a person would plausibly reach for
/// without looking, from a phone, while doing something else.
///
/// Two hard exclusions do the heavy lifting: anything **numbered** — "Switch to
/// Desktop 7", "Activate Task Manager Entry 3" — is never offered however
/// useful it is to whoever bound it, and anything that only makes sense with a
/// window already chosen is not a rail button.
fn is_recommended(key: &str) -> bool {
    // A digit anywhere means it is one of a numbered series. Twenty of those on
    // a review screen is what makes the screen useless.
    if key.chars().any(|character| character.is_ascii_digit()) {
        return false;
    }
    let key = key.to_ascii_lowercase();
    const WORTH_OFFERING: &[&str] = &[
        // Session and screen.
        "lock session",
        "screensaver",
        "show-screenshot-ui",
        "screenshot",
        // The desktop as a whole.
        "overview",
        "toggle-overview",
        // Tapping Meta to open the launcher may be the single best rail button
        // on a KDE machine.
        "activate application launcher",
        "activate-application-launcher",
        "panel-run-dialog",
        "show desktop",
        "show-desktop",
        "expose",
        // Windows, the two anybody uses.
        "window close",
        "close",
        "window maximize",
        "maximize",
        // Moving between workspaces, but only left and right.
        "switch to next desktop",
        "switch to previous desktop",
        "switch-to-workspace-left",
        "switch-to-workspace-right",
        "switch one desktop to the left",
        "switch one desktop to the right",
        // Sound and media, which are the reason anybody wants a rail.
        "increase_volume",
        "decrease_volume",
        "mute",
        "mic_mute",
        "volume-up",
        "volume-down",
        "volume-mute",
        "mic-mute",
        "play",
        "playpause",
        "next",
        "previous",
    ];
    WORTH_OFFERING.iter().any(|worth| key == *worth)
}

/// Reads one schema's worth of `gsettings list-recursively`.
///
/// The shape:
///
/// ```text
/// org.gnome.desktop.wm.keybindings close ['<Alt>F4']
/// org.gnome.desktop.wm.keybindings cycle-group ['<Alt>F6', '<Super>x']
/// org.gnome.desktop.wm.keybindings lower @as []
/// ```
///
/// The value is a list of accelerators, and `@as []` is how an empty list
/// prints. Older versions store a bare string for some media keys instead of a
/// list, so every quoted run is taken and each is tried in turn — which covers
/// both shapes without having to know which version is answering.
///
/// GNOME has no readable name for these anywhere a program can reach: the words
/// a person sees in Settings live in that application's translations, not in
/// the settings store. The keys worth putting on a rail are named by hand in
/// `GNOME_NAMES`; everything else has its key made readable, so
/// `toggle-tiled-left` becomes "Toggle tiled left" — plainer than KDE's, and
/// honest about where it came from.
pub fn from_gnome(listing: &str, group: &str) -> Vec<Candidate> {
    let mut candidates = Vec::new();
    for line in listing.lines() {
        let mut parts = line.split_whitespace();
        let (Some(_schema), Some(key)) = (parts.next(), parts.next()) else {
            continue;
        };
        let value = line
            .splitn(3, char::is_whitespace)
            .nth(2)
            .unwrap_or_default();
        let Some(chord) = quoted_runs(value)
            .into_iter()
            .find_map(|accelerator| read_accelerator(&accelerator))
        else {
            continue;
        };
        // GNOME keeps two keys per media action: `play` is the one somebody can
        // change, usually empty, and `play-static` is the fixed hardware key,
        // which is where the binding that actually works normally lives. They
        // are the same action, so the suffix comes off before anything is named
        // or classified — otherwise the working half arrives as "Play static".
        let key = key.strip_suffix("-static").unwrap_or(key);
        candidates.push(Candidate {
            group: classify(group, key),
            recommended: is_recommended(key),
            name: gnome_name(key),
            chord,
        });
    }
    candidates.dedup_by(|left, right| left == right);
    candidates
}

/// Reads one of a person's own shortcuts.
///
/// These carry a name somebody typed, which is better than anything that can be
/// derived, so it is used as-is. The command they run is deliberately ignored:
/// this takes the chord and the label, and firing the chord makes GNOME run the
/// command exactly as pressing the keys would. Nothing here runs anything.
pub fn from_gnome_custom(listing: &str) -> Option<Candidate> {
    let mut name = None;
    let mut binding = None;
    for line in listing.lines() {
        let mut parts = line.split_whitespace();
        let (Some(_schema), Some(key)) = (parts.next(), parts.next()) else {
            continue;
        };
        let value = line
            .splitn(3, char::is_whitespace)
            .nth(2)
            .unwrap_or_default();
        let first = quoted_runs(value).into_iter().next();
        match key {
            "name" => name = first,
            "binding" => binding = first,
            _ => {}
        }
    }
    let chord = read_accelerator(&binding?)?;
    let name = name.filter(|name| !name.trim().is_empty())?;
    Some(Candidate {
        // Somebody bound this themselves, so it is theirs rather than the
        // desktop's, and nothing about it says what it is about. `Other` is
        // the honest answer and it is where they will look for it.
        group: Group::Other,
        // Never offered first: a person's own shortcut is the one thing we
        // have no basis at all for recommending.
        recommended: false,
        name,
        chord,
    })
}

/// Every single-quoted run in a settings value.
///
/// Covers a bare string and a list with one call, so neither shape needs
/// detecting. `@as []` holds no quotes and yields nothing.
fn quoted_runs(value: &str) -> Vec<String> {
    let mut runs = Vec::new();
    let mut rest = value;
    while let Some(start) = rest.find('\'') {
        rest = &rest[start + 1..];
        let Some(end) = rest.find('\'') else {
            break;
        };
        runs.push(rest[..end].to_owned());
        rest = &rest[end + 1..];
    }
    runs
}

/// Turns a settings key into something a person can read on a button.
fn readable(key: &str) -> String {
    let spaced = key.replace(['-', '_'], " ");
    let mut characters = spaced.chars();
    match characters.next() {
        Some(first) => first.to_uppercase().collect::<String>() + characters.as_str(),
        None => spaced,
    }
}

/// Reads a GTK accelerator: `<Shift><Control><Alt>Escape`.
fn read_accelerator(accelerator: &str) -> Option<Chord> {
    let mut rest = accelerator.trim();
    // Both of these are how GNOME writes "no shortcut".
    if rest.is_empty() || rest == "disabled" {
        return None;
    }
    let mut names: Vec<&'static str> = Vec::new();
    while rest.starts_with('<') {
        let close = rest.find('>')?;
        names.push(match &rest[1..close] {
            // `Primary` is Control written portably; GNOME uses both.
            "Control" | "Primary" | "Ctrl" => "ctrl",
            "Shift" => "shift",
            "Alt" => "alt",
            "Super" | "Meta" | "Mod4" => "super",
            _ => return None,
        });
        rest = &rest[close + 1..];
    }
    names.push(gnome_key_name(rest)?);
    // Through the same parse as everything else. An imported chord is not a
    // trusted chord.
    Chord::parse(&names.join("+")).ok()
}

/// Translates one key from what GNOME calls it to what this host calls it.
fn gnome_key_name(gnome: &str) -> Option<&'static str> {
    if gnome.len() == 1 {
        let character = gnome.chars().next()?;
        if character.is_ascii_alphanumeric() {
            return crate::keys::canonical_name(&character.to_ascii_lowercase().to_string());
        }
    }
    let name = match gnome {
        "space" => "space",
        "Return" | "KP_Enter" => "enter",
        "Tab" => "tab",
        "Escape" => "escape",
        "BackSpace" => "backspace",
        "Delete" | "KP_Delete" => "delete",
        "Insert" | "KP_Insert" => "insert",
        "Home" => "home",
        "End" => "end",
        "Page_Up" | "Prior" => "pageup",
        "Page_Down" | "Next" => "pagedown",
        "Left" => "left",
        "Right" => "right",
        "Up" => "up",
        "Down" => "down",
        "Menu" => "menu",
        "Print" | "Sys_Req" => "print",
        "Pause" => "pause",
        "Scroll_Lock" => "scrolllock",
        "Caps_Lock" => "capslock",
        "Num_Lock" => "numlock",
        "minus" => "minus",
        "equal" => "equal",
        "bracketleft" => "leftbracket",
        "bracketright" => "rightbracket",
        "backslash" => "backslash",
        "semicolon" => "semicolon",
        "apostrophe" => "apostrophe",
        "grave" => "grave",
        "comma" => "comma",
        "period" => "period",
        "slash" => "slash",
        // The keys a laptop has above the number row. X11 names them XF86.
        "XF86AudioRaiseVolume" => "volumeup",
        "XF86AudioLowerVolume" => "volumedown",
        "XF86AudioMute" => "mute",
        "XF86AudioMicMute" => "micmute",
        "XF86AudioPlay" | "XF86AudioPause" => "playpause",
        "XF86AudioNext" => "nexttrack",
        "XF86AudioPrev" => "previoustrack",
        "XF86AudioStop" => "stop",
        "XF86MonBrightnessUp" => "brightnessup",
        "XF86MonBrightnessDown" => "brightnessdown",
        other if other.len() > 1 && other.starts_with('F') => {
            return other[1..]
                .parse::<u8>()
                .ok()
                .filter(|number| (1..=12).contains(number))
                .and_then(|number| crate::keys::canonical_name(&format!("f{number}")));
        }
        other if other.starts_with("KP_") => {
            return other[3..]
                .parse::<u8>()
                .ok()
                .filter(|number| *number <= 9)
                .and_then(|number| crate::keys::canonical_name(&format!("kp{number}")));
        }
        _ => return None,
    };
    Some(name)
}

/// Asks `gsettings` for one schema.
fn gsettings(arguments: &[&str]) -> Option<String> {
    let output = std::process::Command::new("gsettings")
        .args(arguments)
        .stdin(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .output()
        .ok()?;
    // A schema this machine does not have is not an error worth reporting: it
    // is a version of GNOME that never had it, or a component not installed.
    if !output.status.success() {
        return None;
    }
    String::from_utf8(output.stdout).ok()
}

fn read_gnome() -> Vec<Candidate> {
    let mut candidates = Vec::new();
    for (schema, group) in GNOME_SCHEMAS {
        if let Some(listing) = gsettings(&["list-recursively", schema]) {
            candidates.extend(from_gnome(&listing, group));
        }
    }
    // A person's own shortcuts live one relocatable schema per entry, listed by
    // path, so each one is a second question.
    if let Some(listing) = gsettings(&[
        "get",
        "org.gnome.settings-daemon.plugins.media-keys",
        "custom-keybindings",
    ]) {
        for path in quoted_runs(&listing) {
            let target = format!("{GNOME_CUSTOM_SCHEMA}:{path}");
            if let Some(entry) = gsettings(&["list-recursively", &target])
                .as_deref()
                .and_then(from_gnome_custom)
            {
                candidates.push(entry);
            }
        }
    }
    candidates
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
        assert_eq!(maximize.group, Group::Windows);
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
        assert_eq!(find(&found, "Lock Session").unwrap().group, Group::Session);
        assert_eq!(find(&found, "Mute").unwrap().group, Group::Sound);
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
        assert_eq!(detect(Some("GNOME")), Source::Gnome);
        assert_eq!(detect(Some("gnome")), Source::Gnome);
        assert_eq!(detect(Some("ubuntu:GNOME")), Source::Gnome);
        assert_eq!(detect(Some("GNOME-Classic:GNOME")), Source::Gnome);
        assert_eq!(detect(Some("sway")), Source::Unknown);
        assert_eq!(detect(Some("XFCE")), Source::Unknown);
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

    // Real `gsettings list-recursively org.gnome.desktop.wm.keybindings` output,
    // captured from an installed GNOME schema. The empty-list spelling and the
    // multi-accelerator line are the two shapes that matter.
    const GNOME_WM: &str = "\
org.gnome.desktop.wm.keybindings activate-window-menu ['<Alt>space']
org.gnome.desktop.wm.keybindings always-on-top @as []
org.gnome.desktop.wm.keybindings begin-move ['<Alt>F7']
org.gnome.desktop.wm.keybindings close ['<Alt>F4']
org.gnome.desktop.wm.keybindings cycle-panels-backward ['<Shift><Control><Alt>Escape']
org.gnome.desktop.wm.keybindings lower @as []
org.gnome.desktop.wm.keybindings maximize ['<Super>Up']
org.gnome.desktop.wm.keybindings minimize ['<Super>h']
org.gnome.desktop.wm.keybindings switch-to-workspace-left ['<Super><Alt>Left', '<Control><Alt>Left']
";

    #[test]
    fn reads_what_gsettings_prints() {
        let found = from_gnome(GNOME_WM, "Windows");
        assert_eq!(found.len(), 7);
        // Curated names, not the prettified keys: see GNOME_NAMES.
        assert_eq!(
            find(&found, "Close window").unwrap().chord.to_string(),
            "alt+f4"
        );
        assert_eq!(
            find(&found, "Maximise window").unwrap().chord.to_string(),
            "super+up"
        );
        assert_eq!(
            find(&found, "Minimise window").unwrap().group,
            Group::Windows
        );
    }

    #[test]
    fn an_empty_list_is_not_a_shortcut() {
        // `@as []` is how GNOME prints "nothing bound". It holds no quotes, so
        // it yields nothing rather than an empty chord.
        let found = from_gnome(GNOME_WM, "Windows");
        assert!(find(&found, "Always on top").is_none());
        assert!(find(&found, "Lower").is_none());
    }

    #[test]
    fn the_first_accelerator_that_can_be_said_is_the_one_taken() {
        let found = from_gnome(GNOME_WM, "Windows");
        assert_eq!(
            find(&found, "Workspace left").unwrap().chord.to_string(),
            "super+alt+left"
        );
    }

    #[test]
    fn modifiers_stack_in_the_order_they_are_written() {
        let found = from_gnome(GNOME_WM, "Windows");
        assert_eq!(
            find(&found, "Cycle panels backward")
                .unwrap()
                .chord
                .to_string(),
            "shift+ctrl+alt+escape"
        );
    }

    #[test]
    fn a_settings_key_is_made_readable_because_gnome_has_no_name_for_it() {
        // The words a person sees live in the Settings application's
        // translations, not anywhere a program can reach.
        assert_eq!(
            readable("switch-to-workspace-left"),
            "Switch to workspace left"
        );
        assert_eq!(readable("close"), "Close");
        assert_eq!(readable("screenshot-window"), "Screenshot window");
        assert_eq!(readable(""), "");
    }

    #[test]
    fn primary_is_control_written_portably() {
        let found = from_gnome("s k ['<Primary>c']\n", "x");
        assert_eq!(found[0].chord.to_string(), "ctrl+c");
    }

    #[test]
    fn an_older_gnome_storing_a_bare_string_is_read_the_same_way() {
        // Some media keys were a plain string before they became a list, and
        // taking every quoted run covers both without detecting the version.
        let found = from_gnome(
            "org.gnome.settings-daemon.plugins.media-keys volume-up 'XF86AudioRaiseVolume'\n",
            "Sound and Media",
        );
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].chord.to_string(), "volumeup");
        assert_eq!(found[0].name, "Volume up");
    }

    #[test]
    fn the_keys_above_the_number_row_are_understood() {
        for (gnome, ours) in [
            ("XF86AudioMute", "mute"),
            ("XF86AudioPlay", "playpause"),
            ("XF86AudioNext", "nexttrack"),
            ("XF86MonBrightnessDown", "brightnessdown"),
        ] {
            let found = from_gnome(&format!("s k ['{gnome}']\n"), "x");
            assert_eq!(found[0].chord.to_string(), ours, "{gnome}");
        }
    }

    #[test]
    fn a_key_gnome_names_and_this_host_does_not_drops_that_entry_alone() {
        let found = from_gnome(
            "s above-tab ['<Super>Above_Tab']\ns fine ['<Super>m']\n",
            "x",
        );
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].name, "Fine");
    }

    #[test]
    fn disabled_and_empty_are_both_nothing() {
        assert!(from_gnome("s k ['']\n", "x").is_empty());
        assert!(from_gnome("s k ['disabled']\n", "x").is_empty());
        assert!(from_gnome("s k @as []\n", "x").is_empty());
        assert!(from_gnome("", "x").is_empty());
    }

    #[test]
    fn an_imported_gnome_chord_gets_no_more_trust_than_a_recorded_one() {
        // Same rule as KDE's: refused here means never offered.
        assert!(from_gnome("s k ['<Alt>Print']\n", "x").is_empty());
        assert!(
            from_gnome("s k ['<Super><Control><Alt><Shift>a']\n", "x")[0]
                .chord
                .to_string()
                .contains("super")
        );
    }

    #[test]
    fn a_persons_own_shortcut_keeps_the_name_they_typed() {
        // Better than anything derivable, so it is used as written. The command
        // is deliberately ignored: this takes the chord and the label, and
        // nothing here runs anything.
        let entry = from_gnome_custom(
            "org.gnome.settings-daemon.plugins.media-keys.custom-keybinding binding '<Super>t'\n\
             org.gnome.settings-daemon.plugins.media-keys.custom-keybinding command 'kgx'\n\
             org.gnome.settings-daemon.plugins.media-keys.custom-keybinding name 'Open a terminal'\n",
        )
        .expect("a custom shortcut");
        assert_eq!(entry.name, "Open a terminal");
        assert_eq!(entry.chord.to_string(), "super+t");
        assert_eq!(entry.group, Group::Other);
    }

    #[test]
    fn a_custom_shortcut_missing_a_half_is_not_offered() {
        assert!(from_gnome_custom("s binding '<Super>t'\ns command 'kgx'\n").is_none());
        assert!(from_gnome_custom("s name 'Thing'\ns command 'kgx'\n").is_none());
        assert!(from_gnome_custom("s binding ''\ns name 'Thing'\n").is_none());
        assert!(from_gnome_custom("s binding '<Super>t'\ns name '  '\n").is_none());
        assert!(from_gnome_custom("").is_none());
    }

    #[test]
    fn every_quoted_run_is_found_and_nothing_else_is() {
        assert_eq!(quoted_runs("['a', 'b']"), vec!["a", "b"]);
        assert_eq!(quoted_runs("'solo'"), vec!["solo"]);
        assert!(quoted_runs("@as []").is_empty());
        assert!(quoted_runs("").is_empty());
        assert!(quoted_runs("uint32 400").is_empty());
    }

    #[test]
    fn the_keys_worth_naming_get_real_words() {
        // `screensaver` is the one that matters most: prettifying it gives
        // "Screensaver", which is not merely plain but the wrong idea.
        let found = from_gnome(
            "s screensaver ['<Super>l']\n\
             s panel-run-dialog ['<Alt>F2']\n\
             s show-screenshot-ui ['Print']\n\
             s www ['XF86AudioMute']\n",
            "x",
        );
        let names: Vec<_> = found.iter().map(|found| found.name.as_str()).collect();
        assert_eq!(
            names,
            vec![
                "Lock the screen",
                "Run a command",
                "Take a screenshot",
                "Web browser"
            ]
        );
    }

    #[test]
    fn a_key_nobody_curated_still_gets_a_readable_name() {
        let found = from_gnome("s toggle-tiled-left ['<Super>Left']\n", "x");
        assert_eq!(found[0].name, "Toggle tiled left");
    }

    #[test]
    fn the_name_table_can_never_change_what_a_shortcut_does() {
        // Rule one, stated as a test. The table maps a key to words; the chord
        // comes from that person's own settings and nothing here can touch it.
        // Same curated key, two different machines, two different chords.
        let one = from_gnome("s screensaver ['<Super>l']\n", "x");
        let other = from_gnome("s screensaver ['<Control><Alt>Delete']\n", "x");
        assert_eq!(one[0].name, other[0].name);
        assert_eq!(one[0].chord.to_string(), "super+l");
        assert_eq!(other[0].chord.to_string(), "ctrl+alt+delete");
    }

    #[test]
    fn a_renamed_gnome_key_degrades_rather_than_showing_the_wrong_name() {
        // Rule two. If a GNOME release renames `screensaver`, the entry simply
        // stops matching — the new key gets a plain name, and nothing inherits
        // words that are no longer true of it.
        let found = from_gnome("s lock-screen ['<Super>l']\n", "x");
        assert_eq!(found[0].name, "Lock screen");
        assert_eq!(found[0].chord.to_string(), "super+l");
    }

    #[test]
    fn the_name_table_holds_words_and_not_chords() {
        // A chord that found its way into this column would be a name nobody
        // can read, and the wrong kind of thing entirely.
        let mut seen = std::collections::HashSet::new();
        for (key, words) in GNOME_NAMES {
            assert!(seen.insert(*key), "{key} appears twice");
            assert!(!words.is_empty(), "{key} has no words");
            assert!(
                Chord::parse(words).is_err(),
                "{key} maps to something that parses as a chord: {words}"
            );
            assert!(
                words.chars().next().is_some_and(char::is_uppercase),
                "{words} does not start like a name"
            );
        }
    }

    #[test]
    fn a_persons_own_shortcut_is_never_renamed_by_the_table() {
        // Custom shortcuts carry a name somebody typed. Even one that collides
        // with a curated key keeps what they wrote.
        let entry =
            from_gnome_custom("s binding '<Super>l'\ns command 'x'\ns name 'screensaver'\n")
                .expect("a custom shortcut");
        assert_eq!(entry.name, "screensaver");
    }

    #[test]
    fn both_desktops_sort_the_same_kind_of_thing_into_the_same_bucket() {
        // The whole point of normalising: KDE stores "Session Management" and
        // GNOME stores "System" for the same idea, and a screen showing
        // whichever word the local machine happened to keep looks arbitrary.
        assert_eq!(
            classify("ksmserver Session Management", "Lock Session"),
            Group::Session
        );
        assert_eq!(classify("System", "screensaver"), Group::Session);

        assert_eq!(classify("kwin KWin", "Window Close"), Group::Windows);
        assert_eq!(classify("Windows", "close"), Group::Windows);

        assert_eq!(
            classify("kmix Audio Volume", "increase_volume"),
            Group::Sound
        );
        assert_eq!(classify("Sound and Media", "volume-up"), Group::Sound);
    }

    #[test]
    fn the_narrower_answer_wins_over_the_broader_one() {
        // "Screenshot a window" is a screenshot, not a window, and it is filed
        // where somebody would go looking for it.
        assert_eq!(
            classify("kwin KWin", "screenshot-window"),
            Group::Screenshot
        );
        assert_eq!(classify("Windows", "show-screenshot-ui"), Group::Screenshot);
        // A workspace lives in the window manager's config and is not a window.
        assert_eq!(
            classify("kwin KWin", "Switch to Next Desktop"),
            Group::Desktop
        );
        assert_eq!(
            classify("Windows", "switch-to-workspace-left"),
            Group::Desktop
        );
    }

    #[test]
    fn what_cannot_be_placed_goes_to_other_rather_than_somewhere_close() {
        // A wrong bucket is worse than an honest one: nobody hunting in
        // "Windows" for something filed there wrongly will find it, but they
        // will open "Other" when a thing is nowhere else.
        assert_eq!(
            classify("ActivityManager", "switch-to-activity-2e9c"),
            Group::Desktop
        );
        assert_eq!(classify("SomeApp", "do-a-thing"), Group::Other);
        assert_eq!(classify("", ""), Group::Other);
    }

    #[test]
    fn classification_reads_the_action_key_and_never_the_translated_name() {
        // The key is stable in any language; the name beside it is translated.
        // A rule that read names would work until a language pack was
        // installed, which is exactly why this is on the host at all.
        let found =
            from_kde("[kmix]\n_k_friendly_name=Lautstärke\nincrease_volume=Volume Up,x,Lauter\n");
        assert_eq!(found[0].group, Group::Sound);
        assert_eq!(found[0].name, "Lauter");
    }

    #[test]
    fn nothing_numbered_is_ever_offered_first() {
        // Forty-five window shortcuts arrive on a KDE machine and a third of
        // them are one of a numbered series. Twenty of those pre-ticked is what
        // makes a review screen useless.
        for numbered in [
            "Switch to Desktop 7",
            "Activate Task Manager Entry 3",
            "switch-to-workspace-5",
            "Window to Screen 2",
        ] {
            assert!(!is_recommended(numbered), "{numbered} was offered");
        }
    }

    #[test]
    fn the_things_anybody_would_want_are_offered() {
        for worth in [
            "Lock Session",
            "screensaver",
            "increase_volume",
            "volume-up",
            "mute",
            "play",
            "Window Close",
            "close",
            "switch-to-workspace-left",
            "show-screenshot-ui",
        ] {
            assert!(is_recommended(worth), "{worth} was not offered");
        }
    }

    #[test]
    fn a_persons_own_shortcut_is_never_offered_first() {
        // We have no basis at all for recommending one: it is theirs, and its
        // name tells us nothing about what it does.
        let entry =
            from_gnome_custom("s binding '<Super>t'\ns command 'kgx'\ns name 'Open a terminal'\n")
                .expect("a custom shortcut");
        assert!(!entry.recommended);
        assert_eq!(entry.group, Group::Other);
    }

    #[test]
    fn a_lock_key_is_not_a_session_action() {
        // Caps Lock and Scroll Lock are keys. A bare "lock" test would have
        // filed them under Session, which is nowhere anybody would look.
        assert_ne!(classify("kwin", "Toggle Caps Lock"), Group::Session);
        assert_ne!(classify("kwin", "Scroll Lock"), Group::Session);
        assert_eq!(classify("ksmserver", "Lock Session"), Group::Session);
    }

    #[test]
    fn gnomes_fixed_hardware_binding_is_the_same_action_as_the_changeable_one() {
        // GNOME keeps two keys per media action: `play` is what somebody can
        // change and is usually empty, `play-static` is the fixed hardware key
        // and is where the binding that actually works normally lives. Reading
        // the suffix as part of the name gives "Play static", which is not a
        // thing anybody would recognise on a button.
        let found = from_gnome(
            "s play-static ['XF86AudioPlay']\n\
             s mic-mute-static ['XF86AudioMicMute']\n\
             s volume-up-static ['XF86AudioRaiseVolume']\n",
            "Sound and Media",
        );
        let names: Vec<_> = found.iter().map(|f| f.name.as_str()).collect();
        assert_eq!(names, vec!["Play or pause", "Mute microphone", "Volume up"]);
        // And they are still recommended, which they would not be under a name
        // the curated list has never heard of.
        assert!(found.iter().all(|f| f.recommended), "{names:?}");
    }

    #[test]
    fn both_halves_of_a_media_action_survive_when_both_are_bound() {
        // Somebody with a chord *and* the hardware key bound has two real ways
        // to do it, and hiding one would lose a binding they set.
        let found = from_gnome(
            "s screensaver ['<Super>l']\ns screensaver-static ['<Super>Escape']\n",
            "System",
        );
        assert_eq!(found.len(), 2);
        assert!(found.iter().all(|f| f.name == "Lock the screen"));
        assert_ne!(found[0].chord, found[1].chord);
    }
}
