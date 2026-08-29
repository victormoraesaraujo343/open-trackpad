//! The return channel: state carried to the client, and requests carried back.
//!
//! Until now the client spoke and the host only listened. That was deliberate
//! and it still holds for the trackpad and for continuous controls — a volume
//! dial sends "a little more" and the desktop shows its own overlay, so the
//! phone never needs to know the value. A mixer cannot work that way: it has to
//! name the devices, show their levels, and change them.
//!
//! ## Why this is threaded, when nothing else here is
//!
//! Reading the sound daemon costs milliseconds and asking it to change
//! something costs milliseconds more. The socket carries touch frames at over a
//! hundred a second, and a frame arriving late is a pointer that stutters. So
//! nothing in this file runs on the thread that reads the socket: requests are
//! handed over and the answer comes back when it comes back.
//!
//! Three threads per session, and each exists for a reason that is not
//! tidiness:
//!
//! - the **outbox**, because writing to a socket the client has stopped reading
//!   blocks, and that must never be the touch thread;
//! - the **worker**, because every `pactl` call blocks;
//! - the **subscription reader** (in `crate::pactl`), because waiting for the
//!   daemon to say something blocks, possibly for hours.
//!
//! ## What a wedged sound daemon may not do
//!
//! It may freeze the panel. It may not stop the trackpad, and it may not stop
//! the daemon from serving the next client — which is why the worker is never
//! waited for when a session ends. A `pactl` that never returns leaves one
//! thread parked; the session still closes, the contacts are still released,
//! and the next phone still connects.

use std::io::{BufWriter, Write};
use std::net::{Shutdown, TcpStream};
use std::sync::mpsc::{self, Receiver, SyncSender};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use crate::audio::{self, Entity, Snapshot};
use crate::pactl::{self, Facility, Subscription};
use crate::protocol::{Absence, Domain, Outbound, Refusal, Request, Verb};
use crate::timing::TokenBucket;

/// How many lines may be waiting to go out.
///
/// A snapshot of a busy machine is perhaps thirty lines, so this holds several
/// without blocking anyone. It is bounded because a client that stops reading
/// must cost a fixed amount of memory rather than a growing one.
const OUTBOX_DEPTH: usize = 256;

/// How many requests and change notices may be waiting for the worker.
const INBOX_DEPTH: usize = 64;

/// How long the worker gathers before acting.
///
/// A finger dragging a fader produces a request per frame and the daemon
/// answers each with a change notice. Acting on every one would mean a process
/// spawn per frame; gathering for a moment turns a drag into a few dozen, and
/// nobody can see the difference at this distance.
const GATHER: Duration = Duration::from_millis(50);

/// How often to look again once the sound daemon has gone.
///
/// Change notices arrive over a connection to that daemon, so when it dies
/// there is nothing left to wake this up. It has to ask.
const RETRY: Duration = Duration::from_secs(2);

/// The ceiling on requests, in the same spirit as the one on shortcuts.
///
/// Higher than the shortcut limit because a fader is dragged, not tapped: a
/// phone reporting at 120 Hz sends a level per frame, and all of those are
/// real. A client stuck in a loop still hits the wall.
const REQUESTS_PER_SECOND: f64 = 200.0;
const REQUEST_BURST: f64 = 80.0;

/// Everything the host sends back, written on its own thread.
///
/// Cloneable: the read loop refuses requests through it while the worker
/// reports state through it, and one thread doing the writing is what keeps
/// their lines from interleaving.
#[derive(Clone)]
pub struct Sender {
    lines: SyncSender<Outbound>,
}

impl Sender {
    /// Queues one line. Returns whether it was taken.
    ///
    /// Never blocks. A full outbox means a client that has stopped reading, and
    /// the answer to that is to drop the line rather than to stall the sender —
    /// which, for the read loop, would mean stalling touch.
    pub fn send(&self, message: Outbound) -> bool {
        self.lines.try_send(message).is_ok()
    }
}

pub struct Outbox {
    sender: Option<SyncSender<Outbound>>,
    socket: TcpStream,
    writer: Option<JoinHandle<()>>,
}

impl Outbox {
    /// Takes a second handle on the client's socket, for writing.
    pub fn new(socket: TcpStream) -> std::io::Result<Self> {
        let shutdown_handle = socket.try_clone()?;
        let (sender, receiver) = mpsc::sync_channel::<Outbound>(OUTBOX_DEPTH);
        let writer = std::thread::Builder::new()
            .name("outbox".to_owned())
            .spawn(move || write_lines(socket, receiver))
            .ok();
        Ok(Self {
            sender: Some(sender),
            socket: shutdown_handle,
            writer,
        })
    }

    pub fn sender(&self) -> Sender {
        Sender {
            lines: self
                .sender
                .clone()
                .expect("the outbox is only taken apart when it is dropped"),
        }
    }
}

fn write_lines(socket: TcpStream, receiver: Receiver<Outbound>) {
    let mut writer = BufWriter::new(socket);
    // Waits for one line, then takes everything else already queued before
    // flushing. A snapshot is many lines arriving at once and goes out as one
    // write rather than thirty.
    while let Ok(first) = receiver.recv() {
        if writeln!(writer, "{first}").is_err() {
            return;
        }
        while let Ok(next) = receiver.try_recv() {
            if writeln!(writer, "{next}").is_err() {
                return;
            }
        }
        if writer.flush().is_err() {
            return;
        }
    }
}

impl Drop for Outbox {
    fn drop(&mut self) {
        self.sender.take();
        // Closing the writing half is what unblocks a writer parked on a client
        // that stopped reading. Without it, joining here would hang the session
        // teardown on a phone that has wandered off.
        let _ = self.socket.shutdown(Shutdown::Write);
        if let Some(writer) = self.writer.take() {
            let _ = writer.join();
        }
    }
}

/// What wakes the worker up.
enum Wake {
    Request(Request),
    Changed(Facility),
    Stop,
}

/// The audio panel, running behind the socket.
pub struct AudioPanel {
    inbox: SyncSender<Wake>,
    rate: TokenBucket,
}

impl AudioPanel {
    /// Starts the panel, or explains why there is not going to be one.
    ///
    /// A machine with no sound daemon gets no panel rather than a broken one:
    /// the capability is never granted, so the client never draws it.
    pub fn start(out: Sender) -> Result<Self, Absence> {
        pactl::probe()?;

        let (inbox, wakes) = mpsc::sync_channel(INBOX_DEPTH);
        let notifier = inbox.clone();
        std::thread::Builder::new()
            .name("audio-panel".to_owned())
            .spawn(move || Worker::new(out, notifier).run(&wakes))
            .map_err(|_| Absence::NoDaemon)?;

        Ok(Self {
            inbox,
            rate: TokenBucket::new(Instant::now(), REQUEST_BURST, REQUESTS_PER_SECOND),
        })
    }

    /// Hands a request over, or says why it will not be carried out.
    ///
    /// Called on the thread reading the socket, so it does not block: the work
    /// happens on the worker and the answer arrives as a change, or as a
    /// refusal, later.
    pub fn request(&mut self, request: Request, now: Instant) -> Option<Refusal> {
        if !self.rate.allow(now) {
            return Some(Refusal::TooFast);
        }
        match self.inbox.try_send(Wake::Request(request)) {
            Ok(()) => None,
            // A full inbox means the worker is behind, which for a fader means
            // the value in flight is already stale.
            Err(_) => Some(Refusal::TooFast),
        }
    }
}

impl Drop for AudioPanel {
    fn drop(&mut self) {
        // Asked to stop, and deliberately not waited for. A sound daemon that
        // has stopped answering must not be able to hold the session open, nor
        // keep the next phone from connecting.
        let _ = self.inbox.try_send(Wake::Stop);
    }
}

/// The cached lists, refreshed one at a time.
#[derive(Default)]
struct Parts {
    default_sink: String,
    default_source: String,
    outputs: Vec<Entity>,
    inputs: Vec<Entity>,
    streams: Vec<Entity>,
    monitors: Vec<String>,
}

impl Parts {
    fn assemble(&self) -> Snapshot {
        let mut entities = Vec::with_capacity(
            self.outputs.len() + self.inputs.len() + self.streams.len(),
        );
        entities.extend(self.outputs.iter().cloned());
        entities.extend(self.inputs.iter().cloned());
        entities.extend(self.streams.iter().cloned());
        Snapshot::new(entities)
    }
}

struct Worker {
    out: Sender,
    notifier: SyncSender<Wake>,
    generation: u64,
    snapshot: Snapshot,
    parts: Parts,
    subscription: Option<Subscription>,
    /// False once the daemon has stopped answering. The panel empties rather
    /// than freezing on values that are no longer true.
    available: bool,
}

impl Worker {
    fn new(out: Sender, notifier: SyncSender<Wake>) -> Self {
        Self {
            out,
            notifier,
            generation: 0,
            snapshot: Snapshot::default(),
            parts: Parts::default(),
            subscription: None,
            available: false,
        }
    }

    fn run(mut self, wakes: &Receiver<Wake>) {
        self.open();

        loop {
            // Blocking while there is a daemon to hear from; asking again on a
            // timer once there is not, because a dead daemon sends no notices.
            let first = if self.available {
                wakes.recv().ok()
            } else {
                match wakes.recv_timeout(RETRY) {
                    Ok(wake) => Some(wake),
                    Err(mpsc::RecvTimeoutError::Timeout) => {
                        self.open();
                        continue;
                    }
                    Err(mpsc::RecvTimeoutError::Disconnected) => None,
                }
            };
            let Some(first) = first else { return };
            if matches!(first, Wake::Stop) {
                return;
            }

            let mut requests = Vec::new();
            let mut dirty = Vec::new();
            self.sort(first, &mut requests, &mut dirty);

            // Gather for a moment. A drag is dozens of these and they all mean
            // the same work.
            let deadline = Instant::now() + GATHER;
            loop {
                let Some(remaining) = deadline.checked_duration_since(Instant::now()) else {
                    break;
                };
                match wakes.recv_timeout(remaining) {
                    Ok(Wake::Stop) => return,
                    Ok(wake) => self.sort(wake, &mut requests, &mut dirty),
                    Err(_) => break,
                }
            }

            self.carry_out(coalesce(requests), &mut dirty);
            self.refresh(&dirty);
        }
    }

    fn sort(&self, wake: Wake, requests: &mut Vec<Request>, dirty: &mut Vec<Facility>) {
        match wake {
            Wake::Request(request) => requests.push(request),
            Wake::Changed(facility) => note(dirty, facility),
            Wake::Stop => {}
        }
    }

    /// Reads everything and sends a complete picture.
    ///
    /// Also how the panel comes back after the daemon has been away.
    fn open(&mut self) {
        let was_available = self.available;
        self.parts = Parts::default();
        match self.read_all() {
            Ok(parts) => {
                self.parts = parts;
                self.available = true;
                self.watch();
                let snapshot = self.parts.assemble();
                self.snapshot = snapshot;
                self.send_snapshot();
            }
            Err(reason) => {
                self.available = false;
                self.subscription = None;
                self.snapshot = Snapshot::default();
                // Said once, on the way down. Repeating it every two seconds
                // would be noise.
                if was_available || self.generation == 0 {
                    self.generation += 1;
                    let reason = if was_available { Absence::Lost } else { reason };
                    self.out.send(Outbound::Unavailable {
                        domain: Domain::Audio,
                        reason,
                    });
                }
            }
        }
    }

    fn read_all(&self) -> Result<Parts, Absence> {
        let (default_sink, default_source) = pactl::default_names()?;
        let (outputs, monitors) = pactl::sinks(&default_sink)?;
        let inputs = pactl::sources(&default_source, &monitors)?;
        let streams = pactl::streams()?;
        Ok(Parts {
            default_sink,
            default_source,
            outputs,
            inputs,
            streams,
            monitors,
        })
    }

    fn watch(&mut self) {
        let notifier = self.notifier.clone();
        self.subscription = Subscription::start(move |facility| {
            // A full inbox already says "something changed", so a dropped
            // notice loses nothing: the worker is about to look anyway.
            let _ = notifier.try_send(Wake::Changed(facility));
        })
        .ok();
    }

    fn carry_out(&mut self, requests: Vec<Request>, dirty: &mut Vec<Facility>) {
        for request in requests {
            if !self.available {
                self.refuse(request.sequence, Refusal::Unavailable);
                continue;
            }
            if matches!(request.verb, Verb::Refresh) {
                note(dirty, Facility::Devices);
                note(dirty, Facility::Streams);
                self.generation += 1;
                continue;
            }

            let (kind, id) = match request.verb {
                Verb::Volume { kind, id, .. }
                | Verb::Mute { kind, id, .. }
                | Verb::MakeDefault { kind, id } => (kind, id),
                Verb::Refresh => unreachable!("handled above"),
            };
            // The entity must be one this host published. A client can only
            // name a number it was given, and only while that number still
            // means something: unplugging a headset mid-gesture is the ordinary
            // case, not the exceptional one.
            if self.snapshot.find(kind, id).is_none() {
                self.refuse(request.sequence, Refusal::UnknownId);
                continue;
            }

            let outcome = match request.verb {
                Verb::Volume { kind, id, level } => pactl::set_volume(kind, id, level),
                Verb::Mute { kind, id, muted } => pactl::set_mute(kind, id, muted),
                Verb::MakeDefault { kind, id } => {
                    if !kind.has_default() {
                        self.refuse(request.sequence, Refusal::WrongKind);
                        continue;
                    }
                    pactl::set_default(kind, id)
                }
                Verb::Refresh => unreachable!("handled above"),
            };
            if outcome.is_err() {
                self.refuse(request.sequence, Refusal::BackendFailed);
            }
            note(dirty, facility_for(kind));
            if matches!(request.verb, Verb::MakeDefault { .. }) {
                note(dirty, Facility::Devices);
            }
        }
    }

    /// Re-reads the lists that changed and reports the difference.
    ///
    /// The truth is read back rather than assumed. A level the daemon rounded,
    /// or refused, or that something else changed at the same moment, reaches
    /// the phone as what it actually is.
    fn refresh(&mut self, dirty: &[Facility]) {
        if dirty.is_empty() || !self.available {
            return;
        }
        let refresh_devices = dirty.contains(&Facility::Devices);
        if refresh_devices {
            match pactl::default_names() {
                Ok((sink, source)) => {
                    self.parts.default_sink = sink;
                    self.parts.default_source = source;
                }
                Err(_) => return self.lost(),
            }
        }
        if refresh_devices || dirty.contains(&Facility::Sinks) {
            match pactl::sinks(&self.parts.default_sink) {
                Ok((outputs, monitors)) => {
                    self.parts.outputs = outputs;
                    self.parts.monitors = monitors;
                }
                Err(_) => return self.lost(),
            }
        }
        if refresh_devices || dirty.contains(&Facility::Sources) {
            match pactl::sources(&self.parts.default_source, &self.parts.monitors) {
                Ok(inputs) => self.parts.inputs = inputs,
                Err(_) => return self.lost(),
            }
        }
        if dirty.contains(&Facility::Streams) {
            match pactl::streams() {
                Ok(streams) => self.parts.streams = streams,
                Err(_) => return self.lost(),
            }
        }

        let next = self.parts.assemble();
        let changes = audio::diff(&self.snapshot, &next);
        self.snapshot = next;
        for change in changes {
            let message = match change {
                audio::Change::Upserted(entity) => Outbound::Changed {
                    domain: Domain::Audio,
                    generation: self.generation,
                    entity,
                },
                audio::Change::Removed(kind, id) => Outbound::Removed {
                    domain: Domain::Audio,
                    generation: self.generation,
                    kind,
                    id,
                },
            };
            self.out.send(message);
        }
    }

    fn lost(&mut self) {
        self.available = false;
        self.subscription = None;
        self.snapshot = Snapshot::default();
        self.parts = Parts::default();
        self.generation += 1;
        self.out.send(Outbound::Unavailable {
            domain: Domain::Audio,
            reason: Absence::Lost,
        });
    }

    fn send_snapshot(&mut self) {
        self.generation += 1;
        self.out.send(Outbound::Snapshot {
            domain: Domain::Audio,
            generation: self.generation,
            count: self.snapshot.entities.len(),
        });
        for entity in &self.snapshot.entities {
            self.out.send(Outbound::Entry {
                domain: Domain::Audio,
                generation: self.generation,
                entity: entity.clone(),
            });
        }
    }

    fn refuse(&self, sequence: u64, reason: Refusal) {
        self.out.send(Outbound::Refused { sequence, reason });
    }
}

fn facility_for(kind: audio::Kind) -> Facility {
    match kind {
        audio::Kind::Output => Facility::Sinks,
        audio::Kind::Input => Facility::Sources,
        audio::Kind::Stream => Facility::Streams,
    }
}

fn note(dirty: &mut Vec<Facility>, facility: Facility) {
    if !dirty.contains(&facility) {
        dirty.push(facility);
    }
}

/// Throws away requests that a later one has already overtaken.
///
/// A fader dragged across the screen sends a level per frame. Every one of them
/// but the last is a value nobody will ever hear, and each would otherwise cost
/// a process. Kept in the order they arrived, so a mute followed by a level
/// change still happens in that order.
fn coalesce(requests: Vec<Request>) -> Vec<Request> {
    let mut kept: Vec<Request> = Vec::with_capacity(requests.len());
    for request in requests.into_iter().rev() {
        let superseded = kept.iter().any(|later| supersedes(later, &request));
        if !superseded {
            kept.push(request);
        }
    }
    kept.reverse();
    kept
}

/// Whether a later request makes an earlier one pointless.
///
/// Only when they say the same kind of thing about the same entity. A later
/// mute does not make an earlier level change pointless: someone who slides a
/// fader and then mutes expects both, and expects the level to be there when
/// they unmute.
fn supersedes(later: &Request, earlier: &Request) -> bool {
    match (later.verb, earlier.verb) {
        (
            Verb::Volume {
                kind: left, id: left_id, ..
            },
            Verb::Volume {
                kind: right, id: right_id, ..
            },
        ) => left == right && left_id == right_id,
        (
            Verb::Mute {
                kind: left, id: left_id, ..
            },
            Verb::Mute {
                kind: right, id: right_id, ..
            },
        ) => left == right && left_id == right_id,
        (Verb::Refresh, Verb::Refresh) => true,
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::audio::Kind;

    fn volume(sequence: u64, kind: Kind, id: u32, level: u16) -> Request {
        Request {
            sequence,
            domain: Domain::Audio,
            verb: Verb::Volume { kind, id, level },
        }
    }

    fn mute(sequence: u64, kind: Kind, id: u32, muted: bool) -> Request {
        Request {
            sequence,
            domain: Domain::Audio,
            verb: Verb::Mute { kind, id, muted },
        }
    }

    #[test]
    fn a_drag_across_the_screen_costs_one_change_not_forty() {
        let drag: Vec<_> = (0..40)
            .map(|step| volume(step, Kind::Output, 53, step as u16 * 25))
            .collect();
        let kept = coalesce(drag);
        assert_eq!(kept.len(), 1);
        assert_eq!(kept[0].verb, Verb::Volume { kind: Kind::Output, id: 53, level: 975 });
    }

    #[test]
    fn two_faders_moved_at_once_both_survive() {
        let kept = coalesce(vec![
            volume(1, Kind::Output, 53, 100),
            volume(2, Kind::Stream, 53, 200),
            volume(3, Kind::Output, 53, 300),
            volume(4, Kind::Stream, 53, 400),
        ]);
        assert_eq!(kept.len(), 2);
        // Sinks, sources and streams are numbered independently, so the same
        // number is two different things and neither may swallow the other.
        assert_eq!(kept[0].verb, Verb::Volume { kind: Kind::Output, id: 53, level: 300 });
        assert_eq!(kept[1].verb, Verb::Volume { kind: Kind::Stream, id: 53, level: 400 });
    }

    #[test]
    fn a_mute_does_not_swallow_a_level_change() {
        // Someone who slides a fader and then mutes expects the level to be
        // there when they unmute.
        let kept = coalesce(vec![
            volume(1, Kind::Output, 53, 700),
            mute(2, Kind::Output, 53, true),
        ]);
        assert_eq!(kept.len(), 2);
        assert_eq!(kept[0].sequence, 1);
        assert_eq!(kept[1].sequence, 2);
    }

    #[test]
    fn only_the_last_state_of_each_switch_is_acted_on() {
        let kept = coalesce(vec![
            mute(1, Kind::Output, 53, true),
            mute(2, Kind::Output, 53, false),
            mute(3, Kind::Output, 53, true),
        ]);
        assert_eq!(kept.len(), 1);
        assert_eq!(kept[0].sequence, 3);
    }

    #[test]
    fn repeated_refreshes_collapse_into_one() {
        let refresh = |sequence| Request {
            sequence,
            domain: Domain::Audio,
            verb: Verb::Refresh,
        };
        let kept = coalesce(vec![refresh(1), refresh(2), refresh(3)]);
        assert_eq!(kept.len(), 1);
    }

    #[test]
    fn requests_that_have_nothing_to_do_with_each_other_are_all_kept_in_order() {
        let kept = coalesce(vec![
            volume(1, Kind::Output, 1, 100),
            mute(2, Kind::Input, 2, true),
            volume(3, Kind::Stream, 3, 300),
        ]);
        assert_eq!(
            kept.iter().map(|request| request.sequence).collect::<Vec<_>>(),
            vec![1, 2, 3]
        );
    }

    #[test]
    fn nothing_in_means_nothing_out() {
        assert!(coalesce(Vec::new()).is_empty());
    }

    #[test]
    fn each_kind_is_re_read_from_its_own_list() {
        assert_eq!(facility_for(Kind::Output), Facility::Sinks);
        assert_eq!(facility_for(Kind::Input), Facility::Sources);
        assert_eq!(facility_for(Kind::Stream), Facility::Streams);
    }

    #[test]
    fn a_list_is_only_noted_once_however_many_notices_arrive() {
        let mut dirty = Vec::new();
        for _ in 0..10 {
            note(&mut dirty, Facility::Sinks);
            note(&mut dirty, Facility::Streams);
        }
        assert_eq!(dirty, vec![Facility::Sinks, Facility::Streams]);
    }

    #[test]
    fn the_cached_lists_assemble_into_one_ordered_picture() {
        let entity = |kind, id| Entity {
            kind,
            id,
            volume: 500,
            muted: false,
            default: false,
            target: None,
            name: "x".to_owned(),
        };
        let parts = Parts {
            outputs: vec![entity(Kind::Output, 2), entity(Kind::Output, 1)],
            inputs: vec![entity(Kind::Input, 5)],
            streams: vec![entity(Kind::Stream, 9)],
            ..Parts::default()
        };
        let snapshot = parts.assemble();
        assert_eq!(
            snapshot
                .entities
                .iter()
                .map(|entity| (entity.kind, entity.id))
                .collect::<Vec<_>>(),
            vec![
                (Kind::Output, 1),
                (Kind::Output, 2),
                (Kind::Input, 5),
                (Kind::Stream, 9),
            ]
        );
    }
}
