//! The windows open on this desktop, most recently used first.
//!
//! Feeds the recent-applications rail: four windows and a fifth slot for the
//! rest, and tapping one switches to it.
//!
//! # Why this is KDE-only, and why through two tools rather than one
//!
//! `docs/ROADMAP.md` deferred this because Linux has no single way to enumerate
//! windows, and named `org_kde_plasma_window_management` as the KDE answer.
//! **That is not available.** KWin advertises sixty-six Wayland interfaces to an
//! ordinary client and window management is not among them — it is kept for
//! privileged clients like the desktop shell itself. Checked with
//! `wayland-info`, not assumed.
//!
//! What does work is KWin's own scripting engine, reached the way everything
//! else here reaches the system: by running a command. A small script is loaded
//! into KWin over D-Bus; it prints a line whenever a window is opened, closed or
//! switched to; and that line is read back out of the journal. Activation goes
//! through KWin's window runner, which is the same interface KRunner uses when
//! somebody searches for a window and presses return.
//!
//! No new dependency, and the same shape as the sound daemon: exec a tool, read
//! its text, and let a separate process do the talking.
//!
//! # Recency was established, not assumed
//!
//! The script reports windows in stacking order, reversed. That is a claim worth
//! testing rather than believing, so it was tested: with Dolphin last in the
//! list, activating it moved it to the front and left everything else in place.
//! Stacking order follows use. `WindowsRunner`'s own ordering was tried first
//! and does **not** — activating a window left its list identical, which is
//! exactly the kind of wrong that looks right for an hour.
//!
//! # The client never sees a KWin identifier
//!
//! KWin names windows with a UUID. Those are replaced with numbers of this
//! host's own, handed out in order and never reused, so a client can only ever
//! name something this host published — the same rule the audio panel and the
//! shortcut list keep. It also means a stale button cannot switch to whatever
//! window inherited an identifier.
//!
//! # A window list is a privacy surface
//!
//! Titles say what somebody is reading, writing and talking to. This is
//! deliberate rather than incidental: the list is assembled on the host, it goes
//! only to a client that asked for the capability at the handshake, and it goes
//! nowhere else. Titles are percent-encoded on the wire like every other free
//! text — a browser tab title is whatever a web page decided to call itself, and
//! is the most attacker-influenced string this product handles.

use std::collections::HashMap;
use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};

use crate::json;

/// The marker the script prints, and this reads back.
const MARKER: &str = "OTP_WINDOWS";

/// What the script is called inside KWin, for loading and unloading.
const SCRIPT_NAME: &str = "opentrackpad-windows";

/// The most windows carried at once.
///
/// The rail shows four and a fifth slot lists the rest, so a long tail is
/// wanted — but a line is bounded and nobody scrolls past thirty.
pub const MAX_WINDOWS: usize = 30;

/// The longest title carried, in characters.
pub const MAX_TITLE_CHARS: usize = 128;

/// One window, as the rail shows it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Window {
    /// This host's own number for it. Never reused.
    pub id: u32,
    /// What KWin calls it. Never leaves the host.
    pub kwin_id: String,
    /// The application, from the window's resource class — `firefox`,
    /// `org.kde.dolphin`. Four windows of one browser need telling apart.
    pub application: String,
    pub title: String,
}

/// The script loaded into KWin.
///
/// It reports on start and then only when something changes, so the journal
/// carries a line per event rather than a line per poll. Windows with no title
/// are left out: a titleless toplevel is a tooltip or an overlay, and one sits
/// permanently above everything on this machine.
fn script() -> String {
    format!(
        r#"function report(why) {{
    let out = [];
    for (const w of workspace.stackingOrder) {{
        if (w.normalWindow && w.caption.length > 0) {{
            out.unshift([String(w.internalId), w.resourceClass, w.caption]);
        }}
    }}
    print("{MARKER} " + JSON.stringify(out));
}}
workspace.windowActivated.connect(function() {{ report("activated"); }});
workspace.windowAdded.connect(function() {{ report("added"); }});
workspace.windowRemoved.connect(function() {{ report("removed"); }});
report("start");
"#
    )
}

/// Reads one line the script printed.
///
/// Free of I/O, so every shape it can arrive in is testable without a desktop.
pub fn parse_report(line: &str) -> Option<Vec<(String, String, String)>> {
    let payload = line.split_once(MARKER)?.1.trim();
    let parsed = json::parse(payload).ok()?;
    let entries = parsed.as_array()?;

    let mut windows = Vec::with_capacity(entries.len());
    for entry in entries.iter().take(MAX_WINDOWS) {
        let fields = entry.as_array()?;
        let [kwin_id, application, title] = fields else {
            // A row that is not three fields is not a window. One malformed row
            // means a report this host does not understand, and acting on half
            // of it would be worse than waiting for the next one.
            return None;
        };
        windows.push((
            kwin_id.as_str()?.to_owned(),
            application.as_str()?.to_owned(),
            clamp(title.as_str()?),
        ));
    }
    Some(windows)
}

fn clamp(title: &str) -> String {
    if title.chars().count() <= MAX_TITLE_CHARS {
        return title.to_owned();
    }
    title.chars().take(MAX_TITLE_CHARS).collect()
}

/// Hands out this host's numbers, one per window, never reused.
#[derive(Default)]
pub struct Numbering {
    assigned: HashMap<String, u32>,
    next: u32,
}

impl Numbering {
    pub fn new() -> Self {
        Self {
            assigned: HashMap::new(),
            next: 1,
        }
    }

    /// Turns a report into windows, keeping each one's number across reports.
    ///
    /// A window that has gone keeps its number retired rather than freed: the
    /// phone holds these to say which button is which, and handing one back out
    /// would let a stale button switch to whatever window inherited it.
    pub fn number(&mut self, report: Vec<(String, String, String)>) -> Vec<Window> {
        let mut windows = Vec::with_capacity(report.len());
        for (kwin_id, application, title) in report {
            let id = match self.assigned.get(&kwin_id) {
                Some(known) => *known,
                None => {
                    let fresh = self.next;
                    self.next = self.next.saturating_add(1);
                    self.assigned.insert(kwin_id.clone(), fresh);
                    fresh
                }
            };
            windows.push(Window {
                id,
                kwin_id,
                application,
                title,
            });
        }
        windows
    }
}

/// Whether this desktop can be asked about its windows at all.
///
/// Anything else gets no rail rather than an empty one: the capability is never
/// granted, so the phone does not draw it.
pub fn available() -> bool {
    let kde = std::env::var("XDG_CURRENT_DESKTOP")
        .map(|names| {
            names
                .split(':')
                .any(|name| name.eq_ignore_ascii_case("KDE") || name.eq_ignore_ascii_case("plasma"))
        })
        .unwrap_or(false);
    kde && run(&[
        "--user",
        "call",
        "org.kde.KWin",
        "/Scripting",
        "org.kde.kwin.Scripting",
        "isScriptLoaded",
        "s",
        SCRIPT_NAME,
    ])
    .is_some()
}

/// Runs `busctl` and returns its output.
///
/// Fixed arguments, no shell, and the only value ever interpolated is a window
/// identifier this host handed out and then looked back up.
fn run(arguments: &[&str]) -> Option<String> {
    let output = Command::new("busctl")
        .args(arguments)
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    String::from_utf8(output.stdout).ok()
}

/// Switches to a window.
///
/// Through KWin's window runner, which is what KRunner uses when somebody
/// searches for a window and presses return. The identifier it wants is the
/// KWin one prefixed with a screen number, which is why this takes the internal
/// identifier rather than the one on the wire — the caller looks that up first,
/// so nothing a client sent reaches this line unchecked.
pub fn activate(kwin_id: &str) -> bool {
    let target = format!("0_{kwin_id}");
    run(&[
        "--user",
        "call",
        "org.kde.KWin",
        "/WindowsRunner",
        "org.kde.krunner1",
        "Run",
        "ss",
        &target,
        "",
    ])
    .is_some()
}

/// Where to put the script so that both this daemon and KWin can see it.
///
/// Falls back to the temporary directory when there is no runtime directory,
/// which is the case for a daemon started outside a session — where there is no
/// KWin to read it either, so nothing is lost.
fn script_location() -> Option<std::path::PathBuf> {
    match std::env::var_os("XDG_RUNTIME_DIR") {
        Some(runtime) => Some(
            std::path::PathBuf::from(runtime)
                .join("opentrackpad")
                .join("windows.js"),
        ),
        None => Some(std::env::temp_dir().join("opentrackpad-windows.js")),
    }
}

/// The loaded script, and the journal reader following what it prints.
///
/// Both are torn down together: a script left loaded in somebody's KWin after
/// the session that wanted it has gone is litter in their compositor.
pub struct Watcher {
    journal: Child,
    script_path: std::path::PathBuf,
}

impl Watcher {
    /// Loads the script and starts following its output.
    ///
    /// `notify` is called on the reader thread for each report.
    pub fn start(notify: impl Fn(Vec<(String, String, String)>) + Send + 'static) -> Option<Self> {
        // In the runtime directory, not the temporary one. The service runs
        // with `PrivateTmp=true`, so its `/tmp` is a namespace of its own that
        // KWin cannot see — a script written there loads as a file that does
        // not exist, silently, and the rail simply never fills.
        //
        // `$XDG_RUNTIME_DIR` is shared, per-user, on tmpfs and cleared at
        // logout, which is exactly this file's lifetime. The status file already
        // lives there for the same reason.
        let script_path = script_location()?;
        if let Some(directory) = script_path.parent() {
            std::fs::create_dir_all(directory).ok()?;
        }
        std::fs::write(&script_path, script()).ok()?;

        // Following from a moment ago rather than from now. The script prints
        // its first report the instant it loads, and `journalctl -f` takes a
        // little while to actually begin following — so starting exactly now
        // loses the one report that establishes the current state, and the rail
        // stays empty until somebody happens to switch windows.
        //
        // A few seconds of history costs nothing: every line is a whole picture
        // and the newest wins.
        let mut journal = Command::new("journalctl")
            .args(["--user", "-f", "--since", "-5s", "-o", "cat"])
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .ok()?;
        let stdout = journal.stdout.take()?;

        std::thread::Builder::new()
            .name("window-watch".to_owned())
            .spawn(move || {
                for line in BufReader::new(stdout).lines() {
                    let Ok(line) = line else { break };
                    if !line.contains(MARKER) {
                        continue;
                    }
                    if let Some(report) = parse_report(&line) {
                        notify(report);
                    }
                }
            })
            .ok()?;

        // And let it actually get going before the script is loaded, so the
        // first report is printed into a journal somebody is already reading.
        std::thread::sleep(std::time::Duration::from_millis(400));

        let path = script_path.to_string_lossy().into_owned();
        run(&[
            "--user",
            "call",
            "org.kde.KWin",
            "/Scripting",
            "org.kde.kwin.Scripting",
            "loadScript",
            "ss",
            &path,
            SCRIPT_NAME,
        ])?;
        run(&[
            "--user",
            "call",
            "org.kde.KWin",
            "/Scripting",
            "org.kde.kwin.Scripting",
            "start",
        ])?;

        Some(Self {
            journal,
            script_path,
        })
    }
}

impl Drop for Watcher {
    fn drop(&mut self) {
        run(&[
            "--user",
            "call",
            "org.kde.KWin",
            "/Scripting",
            "org.kde.kwin.Scripting",
            "unloadScript",
            "s",
            SCRIPT_NAME,
        ]);
        // Killing the reader is what unblocks its thread: it is parked in a read
        // that ends only when the pipe closes.
        let _ = self.journal.kill();
        let _ = self.journal.wait();
        let _ = std::fs::remove_file(&self.script_path);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const REPORT: &str = r#"OTP_WINDOWS [["{aaa}","firefox","OpenTrackpad — Mozilla Firefox"],["{bbb}","org.kde.dolphin","Screenshots — Dolphin"],["{ccc}","steam","Steam"]]"#;

    #[test]
    fn reads_what_the_script_prints() {
        let windows = parse_report(REPORT).expect("a report");
        assert_eq!(windows.len(), 3);
        assert_eq!(windows[0].1, "firefox");
        assert_eq!(windows[0].2, "OpenTrackpad — Mozilla Firefox");
        assert_eq!(windows[1].0, "{bbb}");
    }

    #[test]
    fn the_first_one_is_the_most_recently_used() {
        // The script reverses stacking order, so position is recency. Verified
        // against a real desktop: activating the last window moved it to the
        // front and left the rest in place.
        let windows = parse_report(REPORT).unwrap();
        let order: Vec<_> = windows.iter().map(|w| w.1.as_str()).collect();
        assert_eq!(order, vec!["firefox", "org.kde.dolphin", "steam"]);
    }

    #[test]
    fn a_journal_line_with_other_things_around_it_is_still_read() {
        // The journal carries everything the session says, so the marker has to
        // survive company.
        let line = format!("kwin_wayland[123]: js: {REPORT}");
        assert_eq!(parse_report(&line).unwrap().len(), 3);
    }

    #[test]
    fn a_report_this_host_does_not_understand_is_refused_whole() {
        // Acting on half a report would show a rail that disagrees with the
        // desktop, and the next report is never far away.
        assert!(parse_report("OTP_WINDOWS [[\"only\",\"two\"]]").is_none());
        assert!(parse_report("OTP_WINDOWS not json").is_none());
        assert!(parse_report("OTP_WINDOWS [[1,2,3]]").is_none());
        assert!(parse_report("nothing to do with us").is_none());
        assert_eq!(parse_report("OTP_WINDOWS []").unwrap().len(), 0);
    }

    #[test]
    fn a_window_keeps_its_number_while_it_lives() {
        let mut numbering = Numbering::new();
        let first = numbering.number(parse_report(REPORT).unwrap());
        assert_eq!(
            first.iter().map(|w| w.id).collect::<Vec<_>>(),
            vec![1, 2, 3]
        );

        // The same windows in a different order keep their numbers: the phone
        // holds these to say which button is which.
        let reordered = r#"OTP_WINDOWS [["{ccc}","steam","Steam"],["{aaa}","firefox","OpenTrackpad — Mozilla Firefox"],["{bbb}","org.kde.dolphin","Screenshots — Dolphin"]]"#;
        let second = numbering.number(parse_report(reordered).unwrap());
        assert_eq!(
            second.iter().map(|w| w.id).collect::<Vec<_>>(),
            vec![3, 1, 2]
        );
    }

    #[test]
    fn a_closed_window_never_hands_its_number_to_another() {
        // A stale button must not switch to whatever inherited an identifier.
        let mut numbering = Numbering::new();
        numbering.number(parse_report(REPORT).unwrap());
        let after = numbering
            .number(parse_report(r#"OTP_WINDOWS [["{ddd}","konsole","Konsole"]]"#).unwrap());
        assert_eq!(after[0].id, 4);
    }

    #[test]
    fn an_overlong_title_is_shortened_rather_than_sent_whole() {
        let long = "x".repeat(400);
        let line = format!(r#"OTP_WINDOWS [["{{a}}","app","{long}"]]"#);
        assert_eq!(
            parse_report(&line).unwrap()[0].2.chars().count(),
            MAX_TITLE_CHARS
        );
    }

    #[test]
    fn more_windows_than_the_rail_can_use_are_cut() {
        let many: Vec<String> = (0..MAX_WINDOWS + 10)
            .map(|n| format!(r#"["{{{n}}}","app","Window {n}"]"#))
            .collect();
        let line = format!("OTP_WINDOWS [{}]", many.join(","));
        assert_eq!(parse_report(&line).unwrap().len(), MAX_WINDOWS);
    }
}
