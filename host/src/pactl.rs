//! Talking to whichever sound daemon is running, through `pactl`.
//!
//! ## Why a command and not a library
//!
//! The panel has to work on PipeWire and on PulseAudio, and `pactl` is the one
//! interface that answers for both: PipeWire ships `pipewire-pulse`, PulseAudio
//! speaks it natively, and one code path therefore covers both without
//! detecting which is running. Linking a client library would mean picking one
//! of them, adding a C dependency and a main loop to a daemon that has exactly
//! one dependency today, and still needing the other path for the other daemon.
//!
//! It costs about seven milliseconds a call, measured on the development
//! machine. Which is why the lists are fetched separately rather than all at
//! once: a finger dragging a fader changes the sinks and nothing else, so it
//! pays for one query instead of five. None of it ever happens on the thread
//! carrying touch — see `crate::panel`.
//!
//! ## Why this cannot become a way to run things
//!
//! Every argument below is either a fixed string in this file or an integer
//! this host published in a snapshot and then parsed back out of a request. No
//! text from the client ever reaches a command line, and there is no shell:
//! `Command` execs `pactl` directly, so quoting and metacharacters have nothing
//! to act on. Device names exist only inside this module.

use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};

use crate::audio::{self, Entity};
use crate::protocol::Absence;

const TOOL: &str = "pactl";

/// Runs `pactl` with fixed arguments and returns its standard output.
fn run(arguments: &[&str]) -> Result<String, Absence> {
    let output = Command::new(TOOL)
        .args(arguments)
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .map_err(|error| {
            if error.kind() == std::io::ErrorKind::NotFound {
                Absence::NoTool
            } else {
                Absence::NoDaemon
            }
        })?;
    if !output.status.success() {
        return Err(Absence::NoDaemon);
    }
    String::from_utf8(output.stdout).map_err(|_| Absence::NoDaemon)
}

/// Whether there is a sound daemon here at all.
///
/// Asked once, when a client hands over its handshake. A machine with no daemon
/// is not told the audio panel is broken; it is simply never offered one, which
/// is the difference between absent and broken.
pub fn probe() -> Result<(), Absence> {
    run(&["info"]).map(|_| ())
}

/// Which device new sound goes to, by name.
///
/// Names, not indices, because that is what `pactl` answers with. They are
/// compared against the device lists here and never leave this process: the
/// only identifier that crosses the wire is a number.
pub fn default_names() -> Result<(String, String), Absence> {
    let sink = run(&["get-default-sink"])?;
    let source = run(&["get-default-source"])?;
    Ok((sink.trim().to_owned(), source.trim().to_owned()))
}

/// The outputs, and the monitor names needed to filter the input list.
pub fn sinks(default_name: &str) -> Result<(Vec<Entity>, Vec<String>), Absence> {
    let document = run(&["--format=json", "list", "sinks"])?;
    let entities = audio::read_sinks(&document, default_name).map_err(|_| Absence::NoDaemon)?;
    let monitors = audio::monitor_names(&document).map_err(|_| Absence::NoDaemon)?;
    Ok((entities, monitors))
}

/// The inputs, leaving out every output's monitor.
pub fn sources(default_name: &str, monitors: &[String]) -> Result<Vec<Entity>, Absence> {
    let document = run(&["--format=json", "list", "sources"])?;
    audio::read_sources(&document, default_name, monitors).map_err(|_| Absence::NoDaemon)
}

/// What each application is playing, and how loudly.
pub fn streams() -> Result<Vec<Entity>, Absence> {
    let document = run(&["--format=json", "list", "sink-inputs"])?;
    audio::read_streams(&document).map_err(|_| Absence::NoDaemon)
}

/// Sets one entity's level.
///
/// The raw scale is what `pactl` reports and accepts, so a value set here reads
/// back as the same value rather than one rounded through a percentage.
pub fn set_volume(kind: audio::Kind, id: u32, level: u16) -> Result<(), Absence> {
    let verb = match kind {
        audio::Kind::Output => "set-sink-volume",
        audio::Kind::Input => "set-source-volume",
        audio::Kind::Stream => "set-sink-input-volume",
    };
    let id = id.to_string();
    let raw = audio::volume_to_raw(level).to_string();
    run(&[verb, &id, &raw]).map(|_| ())
}

pub fn set_mute(kind: audio::Kind, id: u32, muted: bool) -> Result<(), Absence> {
    let verb = match kind {
        audio::Kind::Output => "set-sink-mute",
        audio::Kind::Input => "set-source-mute",
        audio::Kind::Stream => "set-sink-input-mute",
    };
    let id = id.to_string();
    run(&[verb, &id, if muted { "1" } else { "0" }]).map(|_| ())
}

/// Makes a device the one new sound goes to.
///
/// `pactl` takes an index here as readily as a name, which is what keeps the
/// request vocabulary numeric: the client never names a device.
pub fn set_default(kind: audio::Kind, id: u32) -> Result<(), Absence> {
    let verb = match kind {
        audio::Kind::Output => "set-default-sink",
        audio::Kind::Input => "set-default-source",
        // Refused by the protocol and again by the panel before this is
        // reached; here so the match is exhaustive without a panic.
        audio::Kind::Stream => return Err(Absence::Lost),
    };
    let id = id.to_string();
    run(&[verb, &id]).map(|_| ())
}

/// Which list a change event affects.
///
/// The point of naming it: a fader drag produces a stream of sink events, and
/// re-reading only the sinks costs one query rather than four.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Facility {
    Sinks,
    Sources,
    Streams,
    /// The default device changed, or a card was reconfigured — either can
    /// rearrange both device lists.
    Devices,
}

/// Reads one line of `pactl subscribe`.
///
/// Returns nothing for the lines that must not cause a re-read. Every `pactl`
/// call this host makes is itself a client connecting and disconnecting, so
/// following client events would be a loop: look, cause an event, look again.
pub fn facility_of(line: &str) -> Option<Facility> {
    let rest = line.split(" on ").nth(1)?;
    match rest.split_whitespace().next()? {
        "sink" => Some(Facility::Sinks),
        "source" => Some(Facility::Sources),
        "sink-input" => Some(Facility::Streams),
        "server" | "card" => Some(Facility::Devices),
        _ => None,
    }
}

/// A running `pactl subscribe`, reporting that something changed.
///
/// It says only *which list* changed, never what it now holds; the panel
/// re-reads that list itself. Deliberate: a picture built up from event notices
/// drifts out of step with reality the first time one is missed, and re-reading
/// one list costs a few milliseconds at the speed a hand turns a dial.
pub struct Subscription {
    child: Child,
}

impl Subscription {
    /// Starts watching. `notify` is called on the reader thread for every
    /// change worth acting on, and must not block for long.
    pub fn start(notify: impl Fn(Facility) + Send + 'static) -> Result<Self, Absence> {
        let mut child = Command::new(TOOL)
            .arg("subscribe")
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|error| {
                if error.kind() == std::io::ErrorKind::NotFound {
                    Absence::NoTool
                } else {
                    Absence::NoDaemon
                }
            })?;

        let stdout = child.stdout.take().ok_or(Absence::NoDaemon)?;

        // Its own thread because this read blocks until the daemon says
        // something, which may be hours.
        std::thread::Builder::new()
            .name("audio-events".to_owned())
            .spawn(move || {
                for line in BufReader::new(stdout).lines() {
                    let Ok(line) = line else { break };
                    if let Some(facility) = facility_of(&line) {
                        notify(facility);
                    }
                }
            })
            .map_err(|_| Absence::NoDaemon)?;

        Ok(Self { child })
    }
}

impl Drop for Subscription {
    fn drop(&mut self) {
        // Killing the child is what unblocks the reader thread: it is parked in
        // a read that ends only when the pipe closes. Without this, every
        // session would leave a thread behind.
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn each_event_names_the_list_it_affects() {
        assert_eq!(
            facility_of("Event 'change' on sink #53"),
            Some(Facility::Sinks)
        );
        assert_eq!(
            facility_of("Event 'remove' on source #57"),
            Some(Facility::Sources)
        );
        assert_eq!(
            facility_of("Event 'new' on sink-input #1348"),
            Some(Facility::Streams)
        );
        assert_eq!(
            facility_of("Event 'change' on server"),
            Some(Facility::Devices)
        );
        assert_eq!(
            facility_of("Event 'change' on card #43"),
            Some(Facility::Devices)
        );
    }

    #[test]
    fn this_hosts_own_queries_do_not_wake_it_up() {
        // Every `pactl` call this host makes is itself a client connecting.
        // Following those would be a loop: look, cause an event, look again.
        assert_eq!(facility_of("Event 'new' on client #1575"), None);
        assert_eq!(facility_of("Event 'change' on client #1575"), None);
        assert_eq!(facility_of("Event 'remove' on client #1576"), None);
    }

    #[test]
    fn noise_on_that_stream_is_ignored_rather_than_misread() {
        assert_eq!(facility_of(""), None);
        assert_eq!(facility_of("Got SIGINT, exiting."), None);
        assert_eq!(facility_of("Connection failure: Connection refused"), None);
        assert_eq!(facility_of("on"), None);
        assert_eq!(facility_of("Event 'new' on module #12"), None);
    }
}
