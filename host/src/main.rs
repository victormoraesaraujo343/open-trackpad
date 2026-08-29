//! OpenTrackpad Linux host daemon.
//!
//! Receives multi-touch contact snapshots from the Android client over a
//! loopback TCP socket and replays them on a virtual multi-touch touchpad, so
//! libinput and the desktop interpret gestures natively.

mod audio;
mod import;
mod json;
mod keyboard;
mod pactl;
mod pad;
mod panel;
mod pointer;
mod protocol;
mod selftest;
mod sink;
mod state;
mod status;
mod timing;
mod uinput;

use std::io::{self, BufRead, BufReader, Read};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, RwLock};
use std::time::Duration;

use opentrackpadd::{keys, shortcuts, text};

use keyboard::{ActionRate, Controls};
use panel::{AudioPanel, Outbox};
use pointer::Buttons;
use protocol::{Accepted, Action, Capabilities, Domain, Outbound, Session};
use shortcuts::Shortcuts;
use sink::{DebugSink, LazyTouchpad, PadSink};
use state::ContactState;
use status::{State, StatusFile};
use timing::TimingTrace;

const DEFAULT_ADDRESS: &str = "127.0.0.1:4242";

const USAGE: &str = "\
usage: opentrackpadd [OPTIONS] [ADDRESS]

  ADDRESS            loopback address to listen on (default 127.0.0.1:4242)

  --dry-run          do not create a virtual device; print pad events instead
  --self-test        replay synthetic contacts and exit, without listening
  --soak MINUTES     replay them on a loop for that long, then report on
                     stuck contacts and memory growth. Moves the pointer
                     continuously, so run it on an idle machine.
  --shortcuts        list what the phone may fire, and what this computer
                     could add to it, then exit
  --print-events     also print every pad event while a client is connected
  --trace-timing     log how frames are spaced, on the phone and on arrival.
                     Pointer acceleration is computed from velocity, so
                     bunched-up frames make it misbehave.
  -h, --help         show this message
";

struct Options {
    address: String,
    dry_run: bool,
    self_test: bool,
    soak_minutes: Option<u64>,
    print_events: bool,
    trace_timing: bool,
    list_shortcuts: bool,
}

fn parse_options(arguments: impl Iterator<Item = String>) -> Result<Options, String> {
    let mut options = Options {
        address: DEFAULT_ADDRESS.to_owned(),
        dry_run: false,
        self_test: false,
        soak_minutes: None,
        print_events: false,
        trace_timing: false,
        list_shortcuts: false,
    };
    let mut address_seen = false;
    let mut expecting_soak = false;

    for argument in arguments {
        if expecting_soak {
            let minutes = argument
                .parse::<u64>()
                .map_err(|_| format!("--soak needs a number of minutes, got: {argument}"))?;
            if minutes == 0 {
                return Err("--soak needs at least one minute".to_owned());
            }
            options.soak_minutes = Some(minutes);
            expecting_soak = false;
            continue;
        }
        match argument.as_str() {
            "--dry-run" => options.dry_run = true,
            "--self-test" => options.self_test = true,
            "--soak" => expecting_soak = true,
            "--shortcuts" => options.list_shortcuts = true,
            "--print-events" => options.print_events = true,
            "--trace-timing" => options.trace_timing = true,
            "-h" | "--help" => return Err(USAGE.to_owned()),
            other if other.starts_with('-') => {
                return Err(format!("unknown option: {other}\n\n{USAGE}"))
            }
            other if address_seen => {
                return Err(format!("unexpected argument: {other}\n\n{USAGE}"))
            }
            other => {
                options.address = other.to_owned();
                address_seen = true;
            }
        }
    }
    if expecting_soak {
        return Err("--soak needs a number of minutes".to_owned());
    }
    Ok(options)
}

/// Wraps a sink so every batch is also printed.
struct Tee<'a> {
    inner: &'a mut dyn PadSink,
    debug: DebugSink,
}

impl PadSink for Tee<'_> {
    fn emit(&mut self, events: &[pad::PadEvent]) -> io::Result<()> {
        self.debug.emit(events)?;
        self.inner.emit(events)
    }

    fn configure(&mut self, geometry: pad::PadGeometry) -> io::Result<bool> {
        self.inner.configure(geometry)
    }

    fn describe(&mut self) -> String {
        self.inner.describe()
    }
}

/// Prints the list and what this computer could add to it.
///
/// The review screen that offers these is still being drawn, and this is how
/// the reading gets checked without it — on a desktop nobody here has, by
/// somebody who has it. `docs/TESTING.md` exists because "it works on this
/// machine" is not an answer, and import is exactly that kind of claim.
fn report_shortcuts(shortcuts: &Shortcuts) {
    println!(
        "Recorded, and firable from the phone ({}):",
        shortcuts.list().len()
    );
    for entry in shortcuts.list() {
        println!("  {:<28} {}", entry.chord.to_string(), entry.name);
    }

    let (source, candidates) = import::read();
    let fresh: Vec<_> = candidates
        .iter()
        .filter(|candidate| !shortcuts.allows(&candidate.chord))
        .collect();
    let already = candidates.len() - fresh.len();

    println!();
    match (source, candidates.is_empty()) {
        (import::Source::Unknown, _) => {
            println!(
                "Found on this computer: nothing — reading shortcuts from {source} \
                 is not supported yet."
            );
            println!("The list above still works, and anything else can be recorded by hand.");
            return;
        }
        (_, true) => {
            println!("Found on {source}: nothing readable.");
            return;
        }
        _ => {}
    }

    println!("Found on {source}, not recorded yet ({}):", fresh.len());
    // In the vocabulary's own order rather than alphabetically, so the screen
    // and this agree about what comes first.
    for group in shortcuts::Group::ALL {
        let members: Vec<_> = fresh.iter().filter(|found| found.group == *group).collect();
        if members.is_empty() {
            continue;
        }
        println!("  {}", group.as_str());
        for found in members {
            let mark = if found.recommended { "*" } else { " " };
            println!("  {mark} {:<26} {}", found.chord.to_string(), found.name);
        }
    }
    println!();
    println!("(* is offered first on the review screen.)");
    if already > 0 {
        println!();
        println!("({already} more match something already recorded.)");
    }
}

/// The name of the program that records a shortcut.
const RECORDER: &str = "opentrackpad-recorder";

/// Opens the recorder, if one is not already open.
///
/// Spawned with **fixed arguments and nothing from the client** — the request
/// carries no data at all, so there is nothing of the client's to pass on. Same
/// discipline as the `pactl` calls: this daemon execs programs it names itself
/// and never a string somebody sent it.
///
/// One at a time. A client sending `RECORD` in a loop must not be able to flash
/// windows across a desktop or stack fifty of them, and a second request while
/// one is open is not an error — it is two people asking for the same window.
///
/// Looked for beside this executable before falling back to the path, so an
/// installed pair finds its own recorder rather than whatever is named that.
fn open_recorder(open: &mut Option<std::process::Child>) -> io::Result<bool> {
    if let Some(running) = open {
        match running.try_wait() {
            // Still up: the window they are asking for is already there.
            Ok(None) => return Ok(false),
            _ => *open = None,
        }
    }

    let beside = std::env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(|directory| directory.join(RECORDER)))
        .filter(|path| path.is_file());
    let program = beside.unwrap_or_else(|| std::path::PathBuf::from(RECORDER));

    let child = std::process::Command::new(program)
        .stdin(std::process::Stdio::null())
        .spawn()?;
    *open = Some(child);
    Ok(true)
}

/// How often to look at the shortcuts file.
///
/// It changes when somebody records a shortcut, which is a human act, so two
/// seconds of latency is invisible. A watch API would be a dependency bought
/// for nothing at that rate.
const RELOAD_INTERVAL: Duration = Duration::from_secs(2);

/// Re-reads the shortcut list when the recorder writes it.
///
/// The recorder is a separate program, and it is opened *while a phone is
/// connected* — that is the whole point of the phone being able to ask for it.
/// So reading the list once at start would mean the shortcut somebody has just
/// recorded is the one thing they cannot fire, until they reconnect, for no
/// reason they could work out.
///
/// Its own thread because the one that matters is blocked on the socket, and a
/// stat every two seconds has no business anywhere near it.
fn watch_shortcuts(shortcuts: Arc<RwLock<Shortcuts>>) {
    let path = shortcuts
        .read()
        .expect("the list is never poisoned")
        .path()
        .map(std::path::Path::to_path_buf);
    let Some(path) = path else {
        // Nowhere to keep them means nothing can change them.
        return;
    };
    let mut seen = shortcuts::file_changed_at(Some(&path));

    let _ = std::thread::Builder::new()
        .name("shortcut-watch".to_owned())
        .spawn(move || loop {
            std::thread::sleep(RELOAD_INTERVAL);
            let now = shortcuts::file_changed_at(Some(&path));
            if now == seen {
                continue;
            }
            seen = now;

            // Re-read through the same door as the first read, so a file the
            // recorder wrote gets exactly the validation a hand-edited one
            // does. Nothing is trusted for having been written by us.
            let fresh = Shortcuts::open();
            for line in fresh.damaged() {
                eprintln!("ignoring a shortcut that could not be read: {line}");
            }
            let count = fresh.list().len();
            match shortcuts.write() {
                Ok(mut held) => *held = fresh,
                Err(_) => return,
            }
            println!("shortcuts reloaded: {count} now available");
        });
}

/// Everything one client session acts on, gathered so the signature says "a
/// session" rather than listing seven unrelated things.
///
/// Borrowed for the length of one connection and handed back, because the
/// caller has to release contacts and held keys afterwards whatever went wrong.
struct Serving<'a> {
    state: &'a mut ContactState,
    sink: &'a mut dyn PadSink,
    controls: &'a mut Controls,
    buttons: &'a mut Buttons,
    /// What the phone is allowed to fire. Read-only here: this list is changed
    /// by somebody at the keyboard, never by anything arriving on the socket.
    shortcuts: &'a RwLock<Shortcuts>,
    status: &'a StatusFile,
    trace_timing: bool,
    dry_run: bool,
}

/// Serves one client until it disconnects or breaks the protocol.
///
/// Returns `Ok` for a clean disconnect and `Err` for a protocol violation; the
/// caller releases contacts either way, so neither path can strand a finger.
fn handle_client(stream: TcpStream, serving: Serving<'_>) -> io::Result<()> {
    let Serving {
        state,
        sink,
        controls,
        buttons,
        shortcuts,
        status,
        trace_timing,
        dry_run,
    } = serving;
    let peer = stream.peer_addr()?;
    println!("client connected: {peer}");

    // A second handle on the same socket, for the answers. Taken before the
    // reader takes ownership of the first.
    let outbox = Outbox::new(stream.try_clone()?)?;
    let out = outbox.sender();

    let mut session = Session::new();
    let mut timing = TimingTrace::new();
    let mut action_rate = ActionRate::new(std::time::Instant::now());
    let mut millimetres_per_pixel = 0.0;
    let mut panel: Option<AudioPanel> = None;
    let mut recorder: Option<std::process::Child> = None;

    let mut reader = BufReader::new(stream);
    let mut line = String::new();
    loop {
        line.clear();
        // Bounded, unlike a plain `lines()`. A client that never sends a
        // newline would otherwise be one unbounded allocation, which is the
        // standing trap in any line-framed protocol.
        let read = (&mut reader)
            .take(protocol::MAX_LINE_BYTES as u64 + 1)
            .read_line(&mut line)?;
        if read == 0 {
            break;
        }
        if !line.ends_with('\n') && read > protocol::MAX_LINE_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "protocol error: line is too long",
            ));
        }
        let accepted = session.accept(line.trim_end()).map_err(|error| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                format!("protocol error: {error}"),
            )
        })?;

        match accepted {
            Accepted::Hello(hello) => {
                println!(
                    "touch surface: {}x{} px over {}x{} mm, up to {} contacts",
                    hello.width,
                    hello.height,
                    hello.width_um / 1000,
                    hello.height_um / 1000,
                    hello.max_contacts
                );

                // Release on the device that exists now, before it may be
                // replaced: contacts left down by a previous session belong to
                // the old device, not the new one.
                let events = state.begin_session(&hello);
                sink.emit(&events)?;

                // Every phone has a different screen, so the virtual pad takes
                // the size the client reports. libinput works in millimetres,
                // so this is what makes pointer speed and gesture thresholds
                // consistent across devices.
                millimetres_per_pixel = hello.millimetres_per_pixel();
                status.set(&State::Connected {
                    width: hello.width,
                    height: hello.height,
                });
                // Built now rather than on the first shortcut: a desktop discards
                // anything typed before it has finished opening the device, so
                // a keyboard created on demand loses the keystroke that
                // created it.
                if let Err(error) = controls.prepare() {
                    eprintln!("could not create the virtual keyboard: {error}");
                } else if let Some(description) = controls.describe() {
                    println!("  {description}");
                }
                // Same reason, same moment: a desktop discards events from a
                // device it has not finished opening, so the first click would
                // be the one lost.
                if let Err(error) = buttons.prepare() {
                    eprintln!("could not create the virtual pointer: {error}");
                } else if let Some(description) = buttons.describe() {
                    println!("  {description}");
                }

                // What the client asked to be told about, kept to what this
                // machine can actually answer. A host with no sound daemon
                // grants no audio, so the phone never draws a panel rather than
                // drawing a broken one.
                let servable = Capabilities {
                    audio: pactl::probe().is_ok(),
                };
                let granted = hello.capabilities.intersect(servable);
                session.grant(granted);
                // Before the panel starts, so the handshake answer is the first
                // line the client reads.
                out.send(Outbound::Welcome(granted));
                if !hello.capabilities.is_empty() {
                    println!(
                        "  audio panel: {}",
                        if granted.audio {
                            "serving"
                        } else {
                            "no sound daemon answered; not offered"
                        }
                    );
                }
                if granted.audio {
                    // Probed a second time, from inside. The first answer was
                    // for the handshake and this one is for the panel; a daemon
                    // that went away in between is reported rather than assumed.
                    match AudioPanel::start(out.clone(), dry_run) {
                        Ok(started) => panel = Some(started),
                        Err(reason) => {
                            out.send(Outbound::Unavailable {
                                domain: Domain::Audio,
                                reason,
                            });
                        }
                    }
                }

                if sink.configure(hello.geometry())? {
                    state.device_replaced();
                    println!("  {}", sink.describe());
                    // Classification is what actually matters, and only the real
                    // host can answer it. Point at the check rather than
                    // claiming success.
                    println!("  verify with: libinput list-devices | grep -A9 OpenTrackpad");
                }
            }
            Accepted::Action(Action::Key(chord)) => {
                // Deliberately not fatal. A shortcut that cannot be typed is worth
                // reporting, but it must not take the trackpad down with it:
                // the two are separate paths for exactly this reason.
                if !action_rate.allow(std::time::Instant::now()) {
                    eprintln!("dropped a shortcut: they are arriving too fast to be real");
                } else if !shortcuts
                    .read()
                    .expect("the list is never poisoned")
                    .allows(&chord)
                {
                    // The gate. A chord the phone may fire is one somebody
                    // recorded, imported or was started with on this machine —
                    // the vocabulary says how a chord is spelled, this says
                    // which ones exist. Not fatal, because the ordinary way to
                    // get here is a button the phone still has on screen for a
                    // shortcut that was deleted a moment ago.
                    eprintln!("ignored a shortcut that is not in the list: {chord}");
                } else if let Err(error) = controls.press_chord(&chord) {
                    eprintln!("could not send shortcut: {error}");
                }
            }
            Accepted::Action(Action::Record) => {
                // Counts against the same allowance as a shortcut or a click:
                // a client looping on this must hit the same wall.
                if !action_rate.allow(std::time::Instant::now()) {
                    eprintln!("dropped a recorder request: they are arriving too fast to be real");
                } else {
                    match open_recorder(&mut recorder) {
                        Ok(true) => println!("opened the shortcut recorder"),
                        Ok(false) => println!("the shortcut recorder is already open"),
                        Err(error) => eprintln!("could not open the shortcut recorder: {error}"),
                    }
                }
            }
            Accepted::Action(Action::Button(button)) => {
                // Not fatal, and not gated. The gate exists because a hundred
                // and thirty key names combine into anything; three button
                // names that can do nothing a mouse cannot already do are
                // their own protection, and there is nothing here to record.
                if !action_rate.allow(std::time::Instant::now()) {
                    eprintln!("dropped a click: they are arriving too fast to be real");
                } else if let Err(error) = buttons.click(button) {
                    eprintln!("could not send click: {error}");
                }
            }
            Accepted::Request(request) => {
                // Handed over rather than carried out here. Talking to the
                // sound daemon costs milliseconds, and this is the thread
                // carrying touch.
                let refusal = match panel.as_mut() {
                    Some(panel) => panel.request(request, std::time::Instant::now()),
                    // Granted at the handshake and gone since; the session
                    // survives it, the panel does not.
                    None => Some(protocol::Refusal::Unavailable),
                };
                if let Some(reason) = refusal {
                    out.send(Outbound::Refused {
                        sequence: request.sequence,
                        reason,
                    });
                }
            }
            Accepted::Frame(frame) => {
                if trace_timing {
                    let separation = frame.separation_mm(millimetres_per_pixel);
                    if let Some(line) =
                        timing.observe(frame.event_time_ns, frame.contacts.len(), separation)
                    {
                        println!("  {line}");
                    }
                }
                let events = state.apply(&frame);
                sink.emit(&events)?;
            }
        }
    }

    println!("client disconnected: {peer}");
    if trace_timing {
        println!("  timing: {}", timing.summary());
    }
    Ok(())
}

/// Releases every contact and reports anything the session got wrong.
fn end_session(state: &mut ContactState, sink: &mut dyn PadSink, dropped_before: u64) {
    let active = state.active_contacts();
    let events = state.release_all();
    if let Err(error) = sink.emit(&events) {
        eprintln!("failed to release {active} contact(s): {error}");
    } else if active > 0 {
        println!("released {active} contact(s) left down by the client");
    }

    let dropped = state.dropped_contacts() - dropped_before;
    if dropped > 0 {
        eprintln!(
            "warning: {dropped} contact(s) were discarded because all {} slots were in use",
            pad::MAX_SLOTS
        );
    }
}

fn run() -> io::Result<()> {
    let options = match parse_options(std::env::args().skip(1)) {
        Ok(options) => options,
        Err(message) => {
            eprintln!("{message}");
            std::process::exit(2);
        }
    };

    // Answered before anything touches a device: listing shortcuts needs no
    // uinput access, and someone checking what import found on their desktop
    // should not have to have set permissions up first.
    if options.list_shortcuts {
        report_shortcuts(&Shortcuts::open());
        return Ok(());
    }

    let mut device = LazyTouchpad::default();
    let mut debug = DebugSink;
    let sink: &mut dyn PadSink = if options.dry_run {
        &mut debug
    } else {
        // The device is built to match the first phone that connects, not now:
        // every screen is a different size, and creating one at a guessed size
        // only to replace it means two hotplugs where one will do. Permissions
        // are still checked up front, so a problem surfaces here rather than
        // mid-session as a mysterious failure.
        uinput::check_access().map_err(|error| {
            io::Error::new(
                error.kind(),
                format!(
                    "cannot open /dev/uinput for writing: {error}\n\
                     Check that it exists and that this user has access (see \
                     host/README.md), or re-run with --dry-run to test the \
                     protocol only."
                ),
            )
        })?;
        &mut device
    };

    println!("OpenTrackpad host daemon");
    println!("  output: {}", sink.describe());

    let mut state = ContactState::new();
    let mut teed;
    let sink: &mut dyn PadSink = if options.print_events && !options.dry_run {
        teed = Tee {
            inner: sink,
            debug: DebugSink,
        };
        &mut teed
    } else {
        sink
    };

    if let Some(minutes) = options.soak_minutes {
        let result = selftest::soak(&mut state, sink, minutes);
        end_session(&mut state, sink, 0);
        return result;
    }

    if options.self_test {
        let result = selftest::run(&mut state, sink);
        end_session(&mut state, sink, 0);
        return result;
    }

    // Read once at start rather than per session: they belong to the machine,
    // not to whichever phone happens to be plugged in.
    //
    // Once at start is not enough for much longer. The recorder is a separate
    // program that writes this file, and it is opened while a phone is
    // connected — so the shortcut somebody has just recorded would not be
    // fireable until this daemon restarted. Re-reading on change belongs with
    // the recorder, which is the first thing that makes it matter.
    let shortcuts = Arc::new(RwLock::new(Shortcuts::open()));
    for line in shortcuts
        .read()
        .expect("the list is never poisoned")
        .damaged()
    {
        // Said rather than swallowed. A shortcut that quietly stopped existing
        // is worse than one that says why, and this is the only place someone
        // would find out that a hand-edited file has a mistake in it.
        eprintln!("ignoring a shortcut that could not be read: {line}");
    }
    match shortcuts
        .read()
        .expect("the list is never poisoned")
        .list()
        .len()
    {
        0 => {}
        1 => println!("  1 custom shortcut"),
        count => println!("  {count} custom shortcuts"),
    }

    watch_shortcuts(Arc::clone(&shortcuts));

    let listener = TcpListener::bind(&options.address)?;
    println!("  listening on {}", options.address);

    // Published for anything that wants to show whether a phone is connected;
    // removed when this process exits, so its absence means "not running".
    let status = StatusFile::open();
    status.set(&State::Waiting);

    // The keyboard is built on the first shortcut, not now: most sessions never
    // send one, and an unused keyboard device would show up in every desktop's
    // input settings for no reason.
    let mut controls = Controls::new(options.dry_run);
    let mut buttons = Buttons::new(options.dry_run);

    for connection in listener.incoming() {
        match connection {
            Ok(stream) => {
                let dropped_before = state.dropped_contacts();
                let serving = Serving {
                    state: &mut state,
                    sink: &mut *sink,
                    controls: &mut controls,
                    buttons: &mut buttons,
                    shortcuts: &shortcuts,
                    status: &status,
                    trace_timing: options.trace_timing,
                    dry_run: options.dry_run,
                };
                if let Err(error) = handle_client(stream, serving) {
                    eprintln!("client failed: {error}");
                }
                end_session(&mut state, sink, dropped_before);

                // A stuck modifier makes the desktop unusable and gives no
                // clue why, so this happens however the session ended.
                match controls.release_all() {
                    Ok(0) => {}
                    Ok(count) => println!("released {count} key(s) still held by the client"),
                    Err(error) => eprintln!("failed to release held keys: {error}"),
                }

                // And the buttons, which matter more: a stuck modifier makes a
                // desktop unusable, a stuck mouse button makes it unusable and
                // unfixable, because nothing can be clicked to get out of it.
                match buttons.release_all() {
                    Ok(0) => {}
                    Ok(count) => println!("released {count} button(s) still held by the client"),
                    Err(error) => eprintln!("failed to release held buttons: {error}"),
                }
                status.set(&State::Waiting);
            }
            Err(error) => eprintln!("connection failed: {error}"),
        }
    }
    Ok(())
}

fn main() {
    if let Err(error) = run() {
        eprintln!("opentrackpadd: {error}");
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_to_the_loopback_address() {
        let options = parse_options(std::iter::empty()).unwrap();
        assert_eq!(options.address, DEFAULT_ADDRESS);
        assert!(!options.dry_run);
    }

    #[test]
    fn a_positional_argument_overrides_the_address() {
        let options = parse_options(["127.0.0.1:9999".to_owned()].into_iter()).unwrap();
        assert_eq!(options.address, "127.0.0.1:9999");
    }

    #[test]
    fn flags_and_an_address_can_be_combined() {
        let options =
            parse_options(["--dry-run".to_owned(), "127.0.0.1:1".to_owned()].into_iter()).unwrap();
        assert!(options.dry_run);
        assert_eq!(options.address, "127.0.0.1:1");
    }

    #[test]
    fn unknown_options_are_rejected_rather_than_treated_as_an_address() {
        assert!(parse_options(["--nope".to_owned()].into_iter()).is_err());
    }

    #[test]
    fn soak_takes_a_duration_in_minutes() {
        let options = parse_options(["--soak".to_owned(), "30".to_owned()].into_iter()).unwrap();
        assert_eq!(options.soak_minutes, Some(30));
    }

    #[test]
    fn soak_rejects_a_missing_or_nonsense_duration() {
        assert!(parse_options(["--soak".to_owned()].into_iter()).is_err());
        assert!(parse_options(["--soak".to_owned(), "0".to_owned()].into_iter()).is_err());
        assert!(parse_options(["--soak".to_owned(), "forever".to_owned()].into_iter()).is_err());
    }

    #[test]
    fn a_soak_duration_is_not_mistaken_for_an_address() {
        let options = parse_options(
            [
                "--soak".to_owned(),
                "5".to_owned(),
                "127.0.0.1:1".to_owned(),
            ]
            .into_iter(),
        )
        .unwrap();
        assert_eq!(options.soak_minutes, Some(5));
        assert_eq!(options.address, "127.0.0.1:1");
    }
}
