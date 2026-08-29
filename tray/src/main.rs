//! A system tray indicator for OpenTrackpad.
//!
//! Shows whether a phone is connected, and offers to stop and start the daemon
//! without opening a terminal. It is a convenience: the trackpad works whether
//! or not this is running, and nothing here can affect input.
//!
//! It reads the daemon's status file rather than talking to it. The daemon
//! injects input into the desktop and should not grow a second thing to listen
//! on just so an icon can change colour.

use std::path::PathBuf;
use std::process::Command;
use std::thread::sleep;
use std::time::Duration;

use ksni::blocking::TrayMethods;
use ksni::menu::{StandardItem, SubMenu};
use ksni::{Icon, MenuItem, Status, ToolTip, Tray};

mod icon;
mod state;
use state::State;

/// How often the status file is re-read.
///
/// Polling rather than watching for changes: the file is on tmpfs, the read is
/// a few dozen bytes, and a second of lag on an icon nobody is staring at is
/// not worth an inotify loop.
const POLL_INTERVAL: Duration = Duration::from_secs(1);

/// The systemd units this offers to control.
const DAEMON_UNIT: &str = "opentrackpad.service";
const BRIDGE_UNIT: &str = "opentrackpad-usb.service";

fn status_path() -> Option<PathBuf> {
    let runtime = std::env::var_os("XDG_RUNTIME_DIR")?;
    let mut path = PathBuf::from(runtime);
    path.push("opentrackpad");
    path.push("state");
    Some(path)
}

/// Reads the daemon's state, treating any problem as "not running".
///
/// An absent file is the daemon's way of saying it has exited, so a missing or
/// unreadable file is not an error to report — it is the answer.
fn read_state(path: Option<&PathBuf>) -> State {
    let Some(path) = path else {
        return State::Stopped;
    };
    match std::fs::read_to_string(path) {
        Ok(contents) => contents.trim().parse().unwrap_or(State::Stopped),
        Err(_) => State::Stopped,
    }
}

/// Runs a `systemctl --user` verb on both units, ignoring the outcome.
///
/// Nothing useful can be done about a failure from a menu item, and the next
/// poll will show whether it worked.
fn systemctl(verb: &str) {
    let _ = Command::new("systemctl")
        .args(["--user", verb, DAEMON_UNIT, BRIDGE_UNIT])
        .status();
}

struct OpenTrackpadTray {
    state: State,
}

impl OpenTrackpadTray {
    /// Lime while there is something to represent, grey once there is not.
    fn colour(&self) -> (u8, u8, u8) {
        match self.state {
            State::Connected { .. } | State::Waiting => icon::ACTIVE,
            State::Stopped => icon::IDLE,
        }
    }
}

impl Tray for OpenTrackpadTray {
    fn id(&self) -> String {
        "opentrackpad".into()
    }

    fn title(&self) -> String {
        "OpenTrackpad".into()
    }

    /// The app's own mark, drawn at whatever size the panel wants.
    ///
    /// A pixmap rather than a theme icon name, so the phone and the tray carry
    /// the same identity instead of whatever the icon theme happens to have.
    fn icon_pixmap(&self) -> Vec<Icon> {
        icon::pixmaps(self.colour())
    }

    /// Greyed out when there is nothing to talk to, so the icon reads at a
    /// glance without being read.
    fn status(&self) -> Status {
        match self.state {
            State::Connected { .. } => Status::Active,
            State::Waiting | State::Stopped => Status::Passive,
        }
    }

    fn tool_tip(&self) -> ToolTip {
        ToolTip {
            title: "OpenTrackpad".into(),
            description: self.state.describe(),
            icon_name: String::new(),
            icon_pixmap: icon::pixmaps(self.colour()),
        }
    }

    fn menu(&self) -> Vec<MenuItem<Self>> {
        let running = self.state != State::Stopped;
        vec![
            // The state, as a disabled entry: a label, not a control.
            StandardItem {
                label: self.state.describe(),
                enabled: false,
                ..Default::default()
            }
            .into(),
            MenuItem::Separator,
            StandardItem {
                label: "Stop OpenTrackpad".into(),
                enabled: running,
                icon_name: "media-playback-stop".into(),
                activate: Box::new(|_: &mut Self| systemctl("stop")),
                ..Default::default()
            }
            .into(),
            StandardItem {
                label: "Start OpenTrackpad".into(),
                enabled: !running,
                icon_name: "media-playback-start".into(),
                activate: Box::new(|_: &mut Self| systemctl("start")),
                ..Default::default()
            }
            .into(),
            SubMenu {
                label: "Troubleshooting".into(),
                submenu: vec![
                    StandardItem {
                        label: "Restart OpenTrackpad".into(),
                        icon_name: "view-refresh".into(),
                        activate: Box::new(|_: &mut Self| systemctl("restart")),
                        ..Default::default()
                    }
                    .into(),
                    StandardItem {
                        label: "Show the log".into(),
                        icon_name: "utilities-terminal".into(),
                        activate: Box::new(|_: &mut Self| show_log()),
                        ..Default::default()
                    }
                    .into(),
                ],
                ..Default::default()
            }
            .into(),
            MenuItem::Separator,
            StandardItem {
                label: "Hide this icon".into(),
                icon_name: "window-close".into(),
                activate: Box::new(|_: &mut Self| std::process::exit(0)),
                ..Default::default()
            }
            .into(),
        ]
    }
}

/// Opens the daemon's log in a terminal, trying the common ones in turn.
///
/// There is no portable way to ask for "a terminal", so this is a list rather
/// than a lookup. Failing silently is fine: the menu entry is a shortcut for
/// something the journal already holds.
fn show_log() {
    let command = format!("journalctl --user -u {DAEMON_UNIT} -u {BRIDGE_UNIT} -f");
    for terminal in ["konsole", "gnome-terminal", "xfce4-terminal", "xterm"] {
        let launched = Command::new(terminal)
            .args(["-e", "sh", "-c", &command])
            .spawn();
        if launched.is_ok() {
            return;
        }
    }
}

fn main() {
    let path = status_path();
    let mut current = read_state(path.as_ref());

    let handle = match (OpenTrackpadTray {
        state: current.clone(),
    })
    .spawn()
    {
        Ok(handle) => handle,
        Err(error) => {
            eprintln!("opentrackpad-tray: no system tray available: {error}");
            std::process::exit(1);
        }
    };

    loop {
        sleep(POLL_INTERVAL);
        if handle.is_closed() {
            return;
        }
        let latest = read_state(path.as_ref());
        if latest == current {
            continue;
        }
        current = latest.clone();
        // Redrawing only on a change keeps the desktop from repainting the
        // icon once a second for no reason.
        handle.update(|tray: &mut OpenTrackpadTray| tray.state = latest.clone());
    }
}
