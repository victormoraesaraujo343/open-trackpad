//! OpenTrackpad Linux host daemon.
//!
//! Receives multi-touch contact snapshots from the Android client over a
//! loopback TCP socket and replays them on a virtual multi-touch touchpad, so
//! libinput and the desktop interpret gestures natively.

mod pad;
mod protocol;
mod selftest;
mod sink;
mod state;
mod timing;
mod uinput;

use std::io::{self, BufRead, BufReader};
use std::net::{TcpListener, TcpStream};

use protocol::{Accepted, Session};
use sink::{DebugSink, LazyTouchpad, PadSink};
use state::ContactState;
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
}

fn parse_options(arguments: impl Iterator<Item = String>) -> Result<Options, String> {
    let mut options = Options {
        address: DEFAULT_ADDRESS.to_owned(),
        dry_run: false,
        self_test: false,
        soak_minutes: None,
        print_events: false,
        trace_timing: false,
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

/// Serves one client until it disconnects or breaks the protocol.
///
/// Returns `Ok` for a clean disconnect and `Err` for a protocol violation; the
/// caller releases contacts either way, so neither path can strand a finger.
fn handle_client(
    stream: TcpStream,
    state: &mut ContactState,
    sink: &mut dyn PadSink,
    trace_timing: bool,
) -> io::Result<()> {
    let peer = stream.peer_addr()?;
    println!("client connected: {peer}");
    let mut session = Session::new();
    let mut timing = TimingTrace::new();

    for line in BufReader::new(stream).lines() {
        let line = line?;
        let accepted = session.accept(&line).map_err(|error| {
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
                if sink.configure(hello.geometry())? {
                    state.device_replaced();
                    println!("  {}", sink.describe());
                    // Classification is what actually matters, and only the real
                    // host can answer it. Point at the check rather than
                    // claiming success.
                    println!("  verify with: libinput list-devices | grep -A9 OpenTrackpad");
                }
            }
            Accepted::Frame(frame) => {
                if trace_timing {
                    if let Some(line) = timing.observe(frame.event_time_ns, frame.contacts.len()) {
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

    let listener = TcpListener::bind(&options.address)?;
    println!("  listening on {}", options.address);

    for connection in listener.incoming() {
        match connection {
            Ok(stream) => {
                let dropped_before = state.dropped_contacts();
                if let Err(error) = handle_client(stream, &mut state, sink, options.trace_timing) {
                    eprintln!("client failed: {error}");
                }
                end_session(&mut state, sink, dropped_before);
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
