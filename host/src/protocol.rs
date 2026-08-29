//! OTP/4 wire protocol: line parsing plus per-connection validation.
//!
//! See `docs/PROTOCOL.md`. Parsing is deliberately free of I/O so the whole
//! surface is unit-testable.

use std::fmt;

use crate::audio;
use crate::keys::Chord;
use crate::pointer::Button;

/// The protocol version this host speaks.
///
/// Version 2 added the physical size of the touch surface to the handshake.
/// Version 3 added actions. Version 4 opened a channel in the other direction:
/// the handshake now carries what the client wants to be told about, the host
/// answers with what it can actually serve, and state travels back.
///
/// The bump is not optional. Older hosts treat an unexpected field as fatal, so
/// there is no way to add one quietly — which is the correct trade: a strict
/// parser is what keeps this from being a remote shell, and the cost of that
/// strictness is an honest version number. A client that speaks version 4 and
/// meets an older host is refused at the handshake and reconnects one version
/// down; see `docs/PROTOCOL.md`. Nothing wedges, because the refusal is
/// immediate and the client never waits for an answer that is not coming.
pub const VERSION: &str = "OTP/4";

/// Hard ceiling on contacts in any message, independent of what a client
/// declares. Bounds the allocation a single line can trigger.
pub const MAX_CONTACTS: u8 = 32;

/// Highest pressure value the protocol allows.
pub const MAX_PROTOCOL_PRESSURE: u16 = 1024;

/// The longest line the host will read.
///
/// A frame with thirty-two contacts is under six hundred bytes and the longest
/// request is a few dozen. This exists so a client that never sends a newline
/// cannot make the host buffer without limit — the one unbounded allocation a
/// line-framed protocol otherwise invites.
pub const MAX_LINE_BYTES: usize = 4096;

/// What a client may ask to be kept informed about, and what the host may agree
/// to serve.
///
/// A closed set, like the key vocabulary, but with one deliberate difference:
/// names in this list that the host does not recognise are *ignored* rather
/// than refused. That is what lets a later panel be added without another
/// version bump. Unknown message types stay fatal; only the capability
/// vocabulary is open-ended, and a capability grants nothing on its own — it
/// merely says which closed set of requests becomes legal.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Capabilities {
    pub audio: bool,
}

impl Capabilities {
    pub const NONE: Self = Self { audio: false };

    /// Reads a comma-separated list, or `-` for none.
    ///
    /// Never fails: an unrecognised name is something a newer client wanted and
    /// this host cannot give, which is answered by leaving it out of the reply,
    /// not by hanging up.
    pub fn parse(text: &str) -> Self {
        let mut capabilities = Self::NONE;
        for name in text.split(',') {
            if name == "audio" {
                capabilities.audio = true;
            }
        }
        capabilities
    }

    /// What both sides can do: what the client asked for, kept to what the host
    /// can actually serve.
    pub fn intersect(self, other: Self) -> Self {
        Self {
            audio: self.audio && other.audio,
        }
    }

    pub fn is_empty(self) -> bool {
        !self.audio
    }

    pub fn allows(self, domain: Domain) -> bool {
        match domain {
            Domain::Audio => self.audio,
        }
    }
}

impl fmt::Display for Capabilities {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        if self.audio {
            return formatter.write_str("audio");
        }
        formatter.write_str("-")
    }
}

/// A body of state the host can carry to the client.
///
/// One today. The recent-applications rail is the second, and it is why this is
/// a named domain rather than the messages simply being about audio.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Domain {
    Audio,
}

impl Domain {
    pub fn as_str(self) -> &'static str {
        match self {
            Domain::Audio => "audio",
        }
    }

    pub fn parse(text: &str) -> Option<Self> {
        match text {
            "audio" => Some(Domain::Audio),
            _ => None,
        }
    }
}

impl fmt::Display for Domain {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.as_str())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Hello {
    pub width: u32,
    pub height: u32,
    pub max_contacts: u8,
    /// Physical size of the touch surface, in micrometres, in the same
    /// orientation as `width` and `height`.
    ///
    /// Sent by the client because only it knows how big its screen is, and
    /// every phone is different. The host cannot guess this.
    pub width_um: u32,
    pub height_um: u32,
    /// What this client would like to be told about. Absent means none, which
    /// is what a client that only wants a trackpad sends.
    pub capabilities: Capabilities,
}

impl Hello {
    pub fn geometry(&self) -> crate::pad::PadGeometry {
        crate::pad::PadGeometry::from_micrometres(self.width_um, self.height_um)
    }

    /// Millimetres per pixel on the touch surface.
    ///
    /// Phone pixels are square to well under a percent, so one figure covers
    /// both axes.
    pub fn millimetres_per_pixel(&self) -> f64 {
        if self.width == 0 {
            return 0.0;
        }
        f64::from(self.width_um) / 1000.0 / f64::from(self.width)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Contact {
    pub id: u8,
    pub x: u32,
    pub y: u32,
    pub pressure: u16,
    pub major: u16,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub sequence: u64,
    pub event_time_ns: u64,
    pub contacts: Vec<Contact>,
}

impl Frame {
    /// How far apart the contacts are, when there are exactly two.
    ///
    /// Pinch zoom is a ratio of this distance, so it is what decides whether a
    /// weak zoom is the gesture running out of surface or the application
    /// converting it timidly.
    pub fn separation_mm(&self, millimetres_per_pixel: f64) -> Option<f64> {
        let [first, second] = self.contacts.as_slice() else {
            return None;
        };
        let dx = f64::from(first.x) - f64::from(second.x);
        let dy = f64::from(first.y) - f64::from(second.y);
        Some(dx.hypot(dy) * millimetres_per_pixel)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Message {
    Hello(Hello),
    Frame(Frame),
    Action(Action),
    Request(Request),
}

/// Something the control surface asked for, as opposed to somewhere a finger is.
///
/// Kept apart from touch on purpose: a shortcut going wrong must not be able to
/// corrupt the trackpad, and the two travel to different virtual devices.
///
/// There is no kind that runs a command. The vocabulary is closed by design —
/// a control surface that can press any key, or run anything, is a remote
/// shell with buttons.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Action {
    /// Press a chord and let it go.
    Key(Chord),
    /// Click a mouse button and let it go.
    ///
    /// A second kind rather than a key name, because a button is not a key:
    /// it goes to a different virtual device, for the reasons in
    /// `crate::pointer`, and it is the one thing here that is not gated by
    /// what somebody recorded.
    Button(Button),
}

/// A change the client would like made to something the host told it about.
///
/// The closed vocabulary of the return path, and it is closed the same way and
/// for the same reason as the key vocabulary. Every verb here names a thing the
/// host already published, by the number the host gave it, and does one bounded
/// thing to it. There is no verb that names a device by string, none that names
/// a command, and none that can reach anything the host did not put in a
/// snapshot first.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Request {
    /// The client's own numbering, echoed back when a request is refused so it
    /// knows which fader to put back. Parsed and otherwise not acted on.
    pub sequence: u64,
    pub domain: Domain,
    pub verb: Verb,
}

/// Every verb names *what kind of thing* as well as which one.
///
/// Not redundancy: outputs, inputs and streams are numbered independently by
/// the sound daemon, so sink 53 and source 53 exist at the same time and are
/// different devices. An id alone would be ambiguous, and the way that failure
/// shows up is a fader moving the wrong device's volume.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Verb {
    /// Set one entity's level, nought to `audio::MAX_VOLUME`.
    Volume {
        kind: audio::Kind,
        id: u32,
        level: u16,
    },
    Mute {
        kind: audio::Kind,
        id: u32,
        muted: bool,
    },
    /// Make this device the one new sound goes to.
    MakeDefault { kind: audio::Kind, id: u32 },
    /// Send the whole picture again. What the client asks for when it opens the
    /// panel, and its way out of any disagreement about state.
    Refresh,
}

/// Why a request was not carried out.
///
/// A closed set rather than free text, for the same reason the request
/// vocabulary is closed: the client switches on these, and a phone showing a
/// message needs to be able to translate it. Free text on the wire would make
/// that impossible and would need escaping besides.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Refusal {
    /// No such entity. The ordinary case is a device unplugged mid-gesture.
    UnknownId,
    /// The entity exists but the verb does not apply to it — asking for a
    /// stream to become the default output, say.
    WrongKind,
    /// The domain cannot be served right now.
    Unavailable,
    /// The daemon refused or the command failed.
    BackendFailed,
    /// Requests are arriving faster than a hand can produce them.
    TooFast,
}

impl Refusal {
    pub fn as_str(self) -> &'static str {
        match self {
            Refusal::UnknownId => "unknown-id",
            Refusal::WrongKind => "wrong-kind",
            Refusal::Unavailable => "unavailable",
            Refusal::BackendFailed => "backend-failed",
            Refusal::TooFast => "too-fast",
        }
    }
}

/// Why a domain is not on offer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Absence {
    /// The tool that speaks to the daemon is not installed.
    NoTool,
    /// The tool is there but no daemon answered.
    NoDaemon,
    /// It was working and stopped. The panel empties rather than freezing on
    /// stale values.
    Lost,
}

impl Absence {
    pub fn as_str(self) -> &'static str {
        match self {
            Absence::NoTool => "no-tool",
            Absence::NoDaemon => "no-daemon",
            Absence::Lost => "lost",
        }
    }
}

/// A line travelling from the host to the client.
///
/// The first messages this protocol has ever sent in this direction.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outbound {
    /// The answer to a handshake: what the host will actually serve.
    Welcome(Capabilities),
    /// Opens a complete picture of a domain. Exactly `count` `Entry` lines of
    /// the same generation follow.
    Snapshot {
        domain: Domain,
        generation: u64,
        count: usize,
    },
    /// One entity, as part of the snapshot just opened.
    Entry {
        domain: Domain,
        generation: u64,
        entity: audio::Entity,
    },
    /// One entity that has appeared or is no longer what it was.
    Changed {
        domain: Domain,
        generation: u64,
        entity: audio::Entity,
    },
    Removed {
        domain: Domain,
        generation: u64,
        kind: audio::Kind,
        id: u32,
    },
    /// This domain has nothing to show. The panel should be absent, not broken.
    Unavailable { domain: Domain, reason: Absence },
    /// A request that was understood but not carried out.
    Refused { sequence: u64, reason: Refusal },
}

/// Renders free text so it cannot be mistaken for protocol.
///
/// This is load-bearing, not tidiness. Device descriptions and application
/// names are free text the host does not author: a window title comes from
/// whatever page a browser has open. Pasted raw into a line-framed protocol,
/// a name containing a newline would let a web page write its own messages into
/// the stream the phone is reading. Everything outside printable ASCII, and the
/// space and percent themselves, becomes `%XX` per UTF-8 byte — so a name can
/// never contain a separator, a newline, or anything else structural.
pub fn escape_text(text: &str) -> String {
    let mut escaped = String::with_capacity(text.len());
    for byte in text.as_bytes() {
        match byte {
            b'!'..=b'~' if *byte != b'%' => escaped.push(*byte as char),
            other => escaped.push_str(&format!("%{other:02X}")),
        }
    }
    if escaped.is_empty() {
        // An empty field would vanish into the whitespace separation and shift
        // every field after it.
        escaped.push_str("%20");
    }
    escaped
}

/// Reads back what `escape_text` wrote, or nothing if it was not written by it.
///
/// Needed because the same encoding now protects the file custom shortcuts are
/// kept in: a name is free text a person typed, and a name containing a newline
/// would otherwise be able to add lines to that file. One encoding, used in both
/// places, for the same reason.
///
/// Strict on the way back. A field holding a raw space, a control character or
/// a truncated escape was not produced by `escape_text`, so it is refused rather
/// than half-read — a hand-edited file is exactly where a guess would hurt.
pub fn unescape_text(text: &str) -> Option<String> {
    let bytes = text.as_bytes();
    let mut decoded = Vec::with_capacity(bytes.len());
    let mut position = 0;
    while position < bytes.len() {
        match bytes[position] {
            b'%' => {
                let digits = bytes.get(position + 1..position + 3)?;
                let digits = std::str::from_utf8(digits).ok()?;
                decoded.push(u8::from_str_radix(digits, 16).ok()?);
                position += 3;
            }
            byte @ b'!'..=b'~' => {
                decoded.push(byte);
                position += 1;
            }
            _ => return None,
        }
    }
    String::from_utf8(decoded).ok()
}

fn render_entity(entity: &audio::Entity) -> String {
    let target = match entity.target {
        Some(id) => id.to_string(),
        None => "-".to_owned(),
    };
    format!(
        "{} {} {} {} {} {} {}",
        entity.kind.as_str(),
        entity.id,
        entity.volume,
        u8::from(entity.muted),
        u8::from(entity.default),
        target,
        escape_text(&entity.name),
    )
}

impl fmt::Display for Outbound {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Outbound::Welcome(capabilities) => {
                write!(formatter, "WELCOME {VERSION} {capabilities}")
            }
            Outbound::Snapshot {
                domain,
                generation,
                count,
            } => write!(formatter, "SNAPSHOT {domain} {generation} {count}"),
            Outbound::Entry {
                domain,
                generation,
                entity,
            } => write!(
                formatter,
                "ENTRY {domain} {generation} {}",
                render_entity(entity)
            ),
            Outbound::Changed {
                domain,
                generation,
                entity,
            } => write!(
                formatter,
                "CHANGED {domain} {generation} {}",
                render_entity(entity)
            ),
            Outbound::Removed {
                domain,
                generation,
                kind,
                id,
            } => write!(
                formatter,
                "REMOVED {domain} {generation} {} {id}",
                kind.as_str()
            ),
            Outbound::Unavailable { domain, reason } => {
                write!(formatter, "UNAVAILABLE {domain} {}", reason.as_str())
            }
            Outbound::Refused { sequence, reason } => {
                write!(formatter, "REFUSED {sequence} {}", reason.as_str())
            }
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProtocolError(pub String);

impl fmt::Display for ProtocolError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.0)
    }
}

impl std::error::Error for ProtocolError {}

fn parse_number<T>(value: Option<&str>, field: &str) -> Result<T, ProtocolError>
where
    T: std::str::FromStr,
{
    value
        .ok_or_else(|| ProtocolError(format!("missing {field}")))?
        .parse::<T>()
        .map_err(|_| ProtocolError(format!("invalid {field}")))
}

fn parse_flag(value: Option<&str>, field: &str) -> Result<bool, ProtocolError> {
    match value {
        Some("0") => Ok(false),
        Some("1") => Ok(true),
        _ => Err(ProtocolError(format!("{field} must be 0 or 1"))),
    }
}

/// Reads the `<kind> <id>` pair every request but `REFRESH` begins with.
fn parse_entity(
    parts: &mut std::str::SplitWhitespace<'_>,
) -> Result<(audio::Kind, u32), ProtocolError> {
    let kind = parts
        .next()
        .ok_or_else(|| ProtocolError("missing entity kind".into()))?;
    let kind = audio::Kind::parse(kind)
        .ok_or_else(|| ProtocolError(format!("unknown entity kind: {kind}")))?;
    Ok((kind, parse_number(parts.next(), "entity id")?))
}

fn ensure_finished(parts: &mut std::str::SplitWhitespace<'_>) -> Result<(), ProtocolError> {
    if parts.next().is_some() {
        return Err(ProtocolError("unexpected trailing fields".into()));
    }
    Ok(())
}

pub fn parse_message(line: &str) -> Result<Message, ProtocolError> {
    if line.len() > MAX_LINE_BYTES {
        return Err(ProtocolError("line is too long".into()));
    }
    let mut parts = line.split_whitespace();
    match parts.next() {
        Some("HELLO") => {
            if parts.next() != Some(VERSION) {
                return Err(ProtocolError(format!(
                    "unsupported protocol version, expected {VERSION}"
                )));
            }
            let mut hello = Hello {
                width: parse_number(parts.next(), "width")?,
                height: parse_number(parts.next(), "height")?,
                max_contacts: parse_number(parts.next(), "max_contacts")?,
                width_um: parse_number(parts.next(), "width_um")?,
                height_um: parse_number(parts.next(), "height_um")?,
                capabilities: Capabilities::NONE,
            };
            // Optional: a client that only wants a trackpad sends the handshake
            // it always sent, one version number higher.
            if let Some(capabilities) = parts.next() {
                hello.capabilities = Capabilities::parse(capabilities);
            }
            ensure_finished(&mut parts)?;
            if hello.width == 0 || hello.height == 0 {
                return Err(ProtocolError("touch dimensions must be positive".into()));
            }
            if hello.width_um == 0 || hello.height_um == 0 {
                return Err(ProtocolError("physical dimensions must be positive".into()));
            }
            if hello.max_contacts == 0 || hello.max_contacts > MAX_CONTACTS {
                return Err(ProtocolError(format!(
                    "max_contacts must be between 1 and {MAX_CONTACTS}"
                )));
            }
            Ok(Message::Hello(hello))
        }
        Some("FRAME") => {
            let sequence = parse_number(parts.next(), "sequence")?;
            let event_time_ns = parse_number(parts.next(), "event_time_ns")?;
            let count: u8 = parse_number(parts.next(), "contact count")?;
            if count > MAX_CONTACTS {
                return Err(ProtocolError(format!(
                    "contact count exceeds {MAX_CONTACTS}"
                )));
            }

            let mut contacts = Vec::with_capacity(count as usize);
            for _ in 0..count {
                let contact = Contact {
                    id: parse_number(parts.next(), "contact id")?,
                    x: parse_number(parts.next(), "contact x")?,
                    y: parse_number(parts.next(), "contact y")?,
                    pressure: parse_number(parts.next(), "contact pressure")?,
                    major: parse_number(parts.next(), "contact major")?,
                };
                if contact.pressure > MAX_PROTOCOL_PRESSURE {
                    return Err(ProtocolError(format!(
                        "contact pressure exceeds {MAX_PROTOCOL_PRESSURE}"
                    )));
                }
                if contacts
                    .iter()
                    .any(|existing: &Contact| existing.id == contact.id)
                {
                    return Err(ProtocolError("duplicate contact id".into()));
                }
                contacts.push(contact);
            }
            ensure_finished(&mut parts)?;
            Ok(Message::Frame(Frame {
                sequence,
                event_time_ns,
                contacts,
            }))
        }
        Some("ACTION") => {
            // The sequence is parsed and discarded: actions are independent of
            // each other and of touch, so there is nothing to order them
            // against. It is required so a client can log and correlate them.
            let _sequence: u64 = parse_number(parts.next(), "sequence")?;
            match parts.next() {
                Some("KEY") => {
                    let chord = parts
                        .next()
                        .ok_or_else(|| ProtocolError("missing key chord".into()))?;
                    let chord =
                        Chord::parse(chord).map_err(|error| ProtocolError(error.to_string()))?;
                    ensure_finished(&mut parts)?;
                    Ok(Message::Action(Action::Key(chord)))
                }
                Some("BUTTON") => {
                    let name = parts
                        .next()
                        .ok_or_else(|| ProtocolError("missing button name".into()))?;
                    let button = Button::parse(name)
                        .ok_or_else(|| ProtocolError(format!("unknown button: {name}")))?;
                    ensure_finished(&mut parts)?;
                    Ok(Message::Action(Action::Button(button)))
                }
                Some(other) => Err(ProtocolError(format!("unknown action kind: {other}"))),
                None => Err(ProtocolError("missing action kind".into())),
            }
        }
        Some("REQUEST") => {
            let sequence = parse_number(parts.next(), "sequence")?;
            let domain = parts
                .next()
                .ok_or_else(|| ProtocolError("missing request domain".into()))?;
            let domain = Domain::parse(domain)
                .ok_or_else(|| ProtocolError(format!("unknown request domain: {domain}")))?;
            let verb = match parts.next() {
                Some("VOLUME") => {
                    let (kind, id) = parse_entity(&mut parts)?;
                    let level: u16 = parse_number(parts.next(), "level")?;
                    // Out of range is refused outright rather than clamped, the
                    // same way an unknown key name is refused rather than
                    // guessed at. A client that does not know the scale is a
                    // client whose next message cannot be trusted either.
                    if level > audio::MAX_VOLUME {
                        return Err(ProtocolError(format!(
                            "level exceeds {}",
                            audio::MAX_VOLUME
                        )));
                    }
                    Verb::Volume { kind, id, level }
                }
                Some("MUTE") => {
                    let (kind, id) = parse_entity(&mut parts)?;
                    Verb::Mute {
                        kind,
                        id,
                        muted: parse_flag(parts.next(), "mute")?,
                    }
                }
                Some("DEFAULT") => {
                    let (kind, id) = parse_entity(&mut parts)?;
                    // Refused here rather than left for the panel to discover:
                    // there is no default stream to be, and a request that
                    // cannot mean anything is malformed rather than merely
                    // unlucky.
                    if !kind.has_default() {
                        return Err(ProtocolError(format!(
                            "a {} cannot be made the default",
                            kind.as_str()
                        )));
                    }
                    Verb::MakeDefault { kind, id }
                }
                Some("REFRESH") => Verb::Refresh,
                Some(other) => return Err(ProtocolError(format!("unknown request kind: {other}"))),
                None => return Err(ProtocolError("missing request kind".into())),
            };
            ensure_finished(&mut parts)?;
            Ok(Message::Request(Request {
                sequence,
                domain,
                verb,
            }))
        }
        Some(other) => Err(ProtocolError(format!("unknown message type: {other}"))),
        None => Err(ProtocolError("empty message".into())),
    }
}

/// A validated message, ready to act on.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Accepted {
    Hello(Hello),
    Frame(Frame),
    Action(Action),
    Request(Request),
}

/// Per-connection protocol state: enforces the rules a single line cannot
/// check on its own (handshake order, sequence monotonicity, touch bounds).
#[derive(Debug, Default)]
pub struct Session {
    hello: Option<Hello>,
    last_sequence: Option<u64>,
    granted: Capabilities,
}

impl Session {
    pub fn new() -> Self {
        Self::default()
    }

    /// Records what the host agreed to serve, once it has checked what it can
    /// actually do. Called between accepting the handshake and answering it.
    ///
    /// Until this is called nothing is granted, so a request cannot arrive in
    /// the gap and find an open door.
    pub fn grant(&mut self, capabilities: Capabilities) {
        self.granted = capabilities;
    }

    /// Validates one protocol line against the session so far.
    ///
    /// Every error is fatal for the connection: the caller must release all
    /// contacts and close, so a hostile or buggy client cannot leave the
    /// virtual touchpad in a half-pressed state.
    pub fn accept(&mut self, line: &str) -> Result<Accepted, ProtocolError> {
        match parse_message(line)? {
            Message::Hello(hello) => {
                if self.hello.is_some() {
                    return Err(ProtocolError("duplicate HELLO".into()));
                }
                self.hello = Some(hello);
                Ok(Accepted::Hello(hello))
            }
            Message::Action(action) => {
                // Actions still need a session: a client that has not said who
                // it is should not be pressing keys.
                if self.hello.is_none() {
                    return Err(ProtocolError("ACTION before HELLO".into()));
                }
                Ok(Accepted::Action(action))
            }
            Message::Request(request) => {
                if self.hello.is_none() {
                    return Err(ProtocolError("REQUEST before HELLO".into()));
                }
                // Asking about something never negotiated is a protocol
                // violation, not a refusal: the client is either broken or
                // probing. The refusals are for things that were legal and
                // could not be done.
                if !self.granted.allows(request.domain) {
                    return Err(ProtocolError(format!(
                        "REQUEST for a domain that was not granted: {}",
                        request.domain
                    )));
                }
                Ok(Accepted::Request(request))
            }
            Message::Frame(frame) => {
                let hello = self
                    .hello
                    .ok_or_else(|| ProtocolError("FRAME before HELLO".into()))?;
                if frame.contacts.len() > hello.max_contacts as usize {
                    return Err(ProtocolError("frame exceeds declared max_contacts".into()));
                }
                if frame
                    .contacts
                    .iter()
                    .any(|contact| contact.x >= hello.width || contact.y >= hello.height)
                {
                    return Err(ProtocolError("contact outside touch bounds".into()));
                }
                if self
                    .last_sequence
                    .is_some_and(|previous| frame.sequence <= previous)
                {
                    return Err(ProtocolError("sequence did not increase".into()));
                }
                self.last_sequence = Some(frame.sequence);
                Ok(Accepted::Frame(frame))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const HANDSHAKE: &str = "HELLO OTP/4 2400 1080 10 156000 69000";

    fn session_with_audio() -> Session {
        let mut session = Session::new();
        session
            .accept("HELLO OTP/4 2400 1080 10 156000 69000 audio")
            .unwrap();
        session.grant(Capabilities { audio: true });
        session
    }

    #[test]
    fn parses_hello() {
        assert_eq!(
            parse_message("HELLO OTP/4 1080 2400 10 69000 156000"),
            Ok(Message::Hello(Hello {
                width: 1080,
                height: 2400,
                max_contacts: 10,
                width_um: 69_000,
                height_um: 156_000,
                capabilities: Capabilities::NONE,
            }))
        );
    }

    #[test]
    fn parses_contact_frame() {
        assert_eq!(
            parse_message("FRAME 42 9912345678 2 0 210 780 650 11 1 810 782 620 10"),
            Ok(Message::Frame(Frame {
                sequence: 42,
                event_time_ns: 9_912_345_678,
                contacts: vec![
                    Contact {
                        id: 0,
                        x: 210,
                        y: 780,
                        pressure: 650,
                        major: 11,
                    },
                    Contact {
                        id: 1,
                        x: 810,
                        y: 782,
                        pressure: 620,
                        major: 10,
                    },
                ],
            }))
        );
    }

    #[test]
    fn rejects_duplicate_contacts() {
        let error = parse_message("FRAME 1 1000 2 0 1 2 3 4 0 5 6 7 8")
            .expect_err("duplicate pointer IDs must fail");
        assert_eq!(error, ProtocolError("duplicate contact id".into()));
    }

    #[test]
    fn rejects_trailing_fields() {
        let error = parse_message("HELLO OTP/4 1080 2400 10 69000 156000 audio extra")
            .expect_err("trailing input must fail");
        assert_eq!(error, ProtocolError("unexpected trailing fields".into()));
    }

    #[test]
    fn rejects_zero_physical_dimensions() {
        // A client that cannot measure its own screen must say so by failing,
        // not by sending zero and letting the host invent a size.
        assert!(parse_message("HELLO OTP/4 1080 2400 10 0 156000").is_err());
        assert!(parse_message("HELLO OTP/4 1080 2400 10 69000 0").is_err());
    }

    #[test]
    fn rejects_older_protocol_versions() {
        // Version 1 had no physical size, version 2 had no actions and version
        // 3 had no way to answer, so accepting any of them would mean guessing
        // at what the client can do. The refusal is immediate, which is what
        // lets a newer client drop a version and try again rather than wait.
        assert!(parse_message("HELLO OTP/1 1080 2400 10").is_err());
        assert!(parse_message("HELLO OTP/2 1080 2400 10 69000 156000").is_err());
        assert!(parse_message("HELLO OTP/3 1080 2400 10 69000 156000").is_err());
    }

    #[test]
    fn a_handshake_yields_the_pad_geometry() {
        let Ok(Message::Hello(hello)) = parse_message("HELLO OTP/4 2412 1080 10 156000 69000")
        else {
            panic!("expected a handshake");
        };
        assert_eq!(hello.geometry().width_mm(), 156);
        assert_eq!(hello.geometry().height_mm(), 69);
    }

    #[test]
    fn rejects_zero_dimensions() {
        assert!(parse_message("HELLO OTP/4 0 2400 10 69000 156000").is_err());
        assert!(parse_message("HELLO OTP/4 1080 0 10 69000 156000").is_err());
    }

    #[test]
    fn rejects_out_of_range_max_contacts() {
        assert!(parse_message("HELLO OTP/4 1080 2400 0 69000 156000").is_err());
        assert!(parse_message("HELLO OTP/4 1080 2400 33 69000 156000").is_err());
    }

    #[test]
    fn rejects_excessive_pressure() {
        assert!(parse_message("FRAME 1 1000 1 0 10 10 1025 5").is_err());
    }

    #[test]
    fn truncated_frame_is_an_error_not_a_panic() {
        assert!(parse_message("FRAME 1 1000 2 0 10 10 500 5").is_err());
    }

    #[test]
    fn contact_count_cannot_preallocate_beyond_the_ceiling() {
        // A hostile `count` must be rejected before any allocation happens.
        assert!(parse_message("FRAME 1 1000 255").is_err());
    }

    #[test]
    fn an_overlong_line_is_refused_before_it_is_parsed() {
        // A line-framed protocol otherwise invites one unbounded allocation:
        // a client that never sends a newline.
        let flood = format!("FRAME 1 1000 0{}", " 0".repeat(MAX_LINE_BYTES));
        assert_eq!(
            parse_message(&flood),
            Err(ProtocolError("line is too long".into()))
        );
    }

    #[test]
    fn frame_before_hello_is_rejected() {
        let mut session = Session::new();
        assert!(session.accept("FRAME 1 1000 0").is_err());
    }

    #[test]
    fn duplicate_hello_is_rejected() {
        let mut session = Session::new();
        session.accept(HANDSHAKE).unwrap();
        assert!(session.accept(HANDSHAKE).is_err());
    }

    #[test]
    fn sequence_must_increase() {
        let mut session = Session::new();
        session.accept(HANDSHAKE).unwrap();
        session.accept("FRAME 5 1000 0").unwrap();
        assert!(session.accept("FRAME 5 1001 0").is_err());
        assert!(session.accept("FRAME 4 1002 0").is_err());
    }

    #[test]
    fn sequence_gaps_are_allowed() {
        let mut session = Session::new();
        session.accept(HANDSHAKE).unwrap();
        session.accept("FRAME 5 1000 0").unwrap();
        // Frames are complete snapshots, so a gap needs no recovery.
        assert!(session.accept("FRAME 900 1001 0").is_ok());
    }

    #[test]
    fn contacts_outside_the_declared_surface_are_rejected() {
        let mut session = Session::new();
        session.accept(HANDSHAKE).unwrap();
        assert!(session.accept("FRAME 1 1000 1 0 2400 500 500 5").is_err());
        assert!(session.accept("FRAME 2 1000 1 0 500 1080 500 5").is_err());
    }

    #[test]
    fn parses_a_key_action() {
        assert_eq!(
            parse_message("ACTION 7 KEY ctrl+c"),
            Ok(Message::Action(Action::Key(
                Chord::parse("ctrl+c").unwrap()
            )))
        );
    }

    #[test]
    fn parses_a_button_click() {
        assert_eq!(
            parse_message("ACTION 7 BUTTON right"),
            Ok(Message::Action(Action::Button(Button::Right)))
        );
        assert_eq!(
            parse_message("ACTION 8 BUTTON left"),
            Ok(Message::Action(Action::Button(Button::Left)))
        );
        assert_eq!(
            parse_message("ACTION 9 BUTTON middle"),
            Ok(Message::Action(Action::Button(Button::Middle)))
        );
    }

    #[test]
    fn a_button_can_only_be_one_of_three_names() {
        // The same rule as an unknown key name, and for the same reason: a
        // button named by number, or a click with a count on it, is what turns
        // a vocabulary back into an interface.
        assert!(parse_message("ACTION 1 BUTTON 3").is_err());
        assert!(parse_message("ACTION 1 BUTTON 0x110").is_err());
        assert!(parse_message("ACTION 1 BUTTON BTN_LEFT").is_err());
        assert!(parse_message("ACTION 1 BUTTON Left").is_err());
        assert!(parse_message("ACTION 1 BUTTON back").is_err());
        assert!(parse_message("ACTION 1 BUTTON").is_err());
        assert!(parse_message("ACTION 1 BUTTON left 2").is_err());
        assert!(parse_message("ACTION 1 BUTTON left left").is_err());
    }

    #[test]
    fn there_is_no_way_to_hold_a_button_down() {
        // A held button is a drag, and a drag needs the pointer moving while it
        // is held — the other path's job. There is no message for it, so there
        // is no way to leave one down.
        assert!(parse_message("ACTION 1 BUTTON left down").is_err());
        assert!(parse_message("ACTION 1 BUTTONDOWN left").is_err());
        assert!(parse_message("ACTION 1 PRESS left").is_err());
        assert!(parse_message("ACTION 1 BUTTON left hold").is_err());
    }

    #[test]
    fn a_click_before_the_handshake_is_rejected() {
        let mut session = Session::new();
        assert!(session.accept("ACTION 1 BUTTON left").is_err());
    }

    #[test]
    fn clicks_do_not_disturb_the_frame_sequence() {
        // Same separate path as a chord: a click must not be able to reorder
        // the frames the touchpad depends on.
        let mut session = Session::new();
        session.accept(HANDSHAKE).unwrap();
        session.accept("FRAME 10 1000 0").unwrap();
        session.accept("ACTION 1 BUTTON right").unwrap();
        assert!(session.accept("FRAME 11 1001 0").is_ok());
    }

    #[test]
    fn rejects_actions_it_does_not_understand() {
        // A control surface must not become a way to run things. Anything
        // outside the vocabulary is refused rather than guessed at.
        assert!(parse_message("ACTION 1 RUN rm -rf /").is_err());
        assert!(parse_message("ACTION 1 EXEC something").is_err());
        assert!(parse_message("ACTION 1 KEY ctrl+sysrq").is_err());
        assert!(parse_message("ACTION 1").is_err());
        assert!(parse_message("ACTION 1 KEY").is_err());
        assert!(parse_message("ACTION notanumber KEY c").is_err());
        assert!(parse_message("ACTION 1 KEY ctrl+c extra").is_err());
    }

    #[test]
    fn an_action_before_the_handshake_is_rejected() {
        let mut session = Session::new();
        assert!(session.accept("ACTION 1 KEY ctrl+c").is_err());
    }

    #[test]
    fn actions_and_frames_do_not_share_a_sequence() {
        // They are independent paths; an action must not disturb the frame
        // ordering the touchpad depends on.
        let mut session = Session::new();
        session.accept(HANDSHAKE).unwrap();
        session.accept("FRAME 10 1000 0").unwrap();
        session.accept("ACTION 1 KEY ctrl+c").unwrap();
        assert!(session.accept("FRAME 11 1001 0").is_ok());
    }

    #[test]
    fn frames_may_not_exceed_the_declared_contact_limit() {
        let mut session = Session::new();
        session
            .accept("HELLO OTP/4 2400 1080 2 156000 69000")
            .unwrap();
        assert!(session
            .accept("FRAME 1 1000 3 0 1 1 1 1 1 2 2 1 1 2 3 3 1 1")
            .is_err());
    }

    // --- capabilities ---

    #[test]
    fn a_handshake_may_name_what_the_client_wants_to_be_told_about() {
        let Ok(Message::Hello(hello)) =
            parse_message("HELLO OTP/4 2400 1080 10 156000 69000 audio")
        else {
            panic!("expected a handshake");
        };
        assert!(hello.capabilities.audio);
    }

    #[test]
    fn a_client_that_only_wants_a_trackpad_names_nothing() {
        let Ok(Message::Hello(hello)) = parse_message(HANDSHAKE) else {
            panic!("expected a handshake");
        };
        assert!(hello.capabilities.is_empty());
    }

    #[test]
    fn capabilities_this_host_does_not_know_are_ignored_rather_than_fatal() {
        // The hinge that lets a later panel be added without another version
        // bump. Unknown message types stay fatal; only this list is open.
        let capabilities = Capabilities::parse("apps,brightness,audio,something-new");
        assert!(capabilities.audio);

        let Ok(Message::Hello(hello)) =
            parse_message("HELLO OTP/4 2400 1080 10 156000 69000 apps,notyet")
        else {
            panic!("a handshake naming unknown capabilities must still be accepted");
        };
        assert!(hello.capabilities.is_empty());
    }

    #[test]
    fn none_is_written_and_read_as_a_dash() {
        assert_eq!(Capabilities::NONE.to_string(), "-");
        assert!(Capabilities::parse("-").is_empty());
    }

    #[test]
    fn the_host_serves_only_what_both_sides_can_do() {
        let wanted = Capabilities { audio: true };
        assert!(wanted.intersect(Capabilities { audio: true }).audio);
        // Asked for, but this machine has no audio daemon.
        assert!(!wanted.intersect(Capabilities::NONE).audio);
        // Servable, but never asked for.
        assert!(!Capabilities::NONE.intersect(wanted).audio);
    }

    // --- requests ---

    #[test]
    fn parses_every_request_in_the_vocabulary() {
        assert_eq!(
            parse_message("REQUEST 3 audio VOLUME output 53 750"),
            Ok(Message::Request(Request {
                sequence: 3,
                domain: Domain::Audio,
                verb: Verb::Volume {
                    kind: audio::Kind::Output,
                    id: 53,
                    level: 750
                },
            }))
        );
        assert_eq!(
            parse_message("REQUEST 4 audio MUTE stream 53 1"),
            Ok(Message::Request(Request {
                sequence: 4,
                domain: Domain::Audio,
                verb: Verb::Mute {
                    kind: audio::Kind::Stream,
                    id: 53,
                    muted: true
                },
            }))
        );
        assert_eq!(
            parse_message("REQUEST 5 audio DEFAULT output 53"),
            Ok(Message::Request(Request {
                sequence: 5,
                domain: Domain::Audio,
                verb: Verb::MakeDefault {
                    kind: audio::Kind::Output,
                    id: 53
                },
            }))
        );
        assert_eq!(
            parse_message("REQUEST 6 audio REFRESH"),
            Ok(Message::Request(Request {
                sequence: 6,
                domain: Domain::Audio,
                verb: Verb::Refresh,
            }))
        );
    }

    #[test]
    fn the_request_vocabulary_is_closed_the_way_the_key_vocabulary_is() {
        // A return channel is a second attack surface. Nothing here may name a
        // command, a path, or a device by string: every request names a number
        // this host published.
        assert!(parse_message("REQUEST 1 audio RUN pactl").is_err());
        assert!(parse_message("REQUEST 1 audio EXEC something").is_err());
        assert!(parse_message("REQUEST 1 audio SETSINK alsa_output.hdmi").is_err());
        assert!(parse_message("REQUEST 1 shell VOLUME output 1 1").is_err());
        assert!(parse_message("REQUEST 1 audio").is_err());
        assert!(parse_message("REQUEST audio VOLUME output 1 1").is_err());
        assert!(parse_message("REQUEST 1 AUDIO VOLUME output 1 1").is_err());
    }

    #[test]
    fn a_level_outside_the_scale_is_refused_rather_than_clamped() {
        // The same rule as an unknown key name: a client that does not know the
        // scale is a client whose next message cannot be trusted either.
        assert!(parse_message("REQUEST 1 audio VOLUME output 53 1501").is_err());
        assert!(parse_message("REQUEST 1 audio VOLUME output 53 99999").is_err());
        assert!(parse_message("REQUEST 1 audio VOLUME output 53 -1").is_err());
        assert!(parse_message("REQUEST 1 audio VOLUME output 53 loud").is_err());
        assert!(parse_message("REQUEST 1 audio VOLUME output 53").is_err());
        // The ends of the scale are both legal, and so is everything between.
        assert!(parse_message("REQUEST 1 audio VOLUME output 53 0").is_ok());
        assert!(parse_message("REQUEST 1 audio VOLUME output 53 1500").is_ok());
    }

    #[test]
    fn the_range_above_a_hundred_percent_is_accepted() {
        // Offered rather than capped: the panel draws the fader against a scale
        // running to 150 with a tick at 100, and turns amber above it, so the
        // amplifying range is visible at a glance instead of hidden.
        for level in [1000, 1001, 1200, 1499, 1500] {
            assert!(
                parse_message(&format!("REQUEST 1 audio VOLUME output 53 {level}")).is_ok(),
                "level {level} should be accepted"
            );
        }
        // And the ceiling is still a wall, not a suggestion.
        for level in [1501, 2000, 65535] {
            assert!(
                parse_message(&format!("REQUEST 1 audio VOLUME output 53 {level}")).is_err(),
                "level {level} should be refused"
            );
        }
    }

    #[test]
    fn a_mute_flag_must_be_zero_or_one() {
        assert!(parse_message("REQUEST 1 audio MUTE output 53 2").is_err());
        assert!(parse_message("REQUEST 1 audio MUTE output 53 true").is_err());
        assert!(parse_message("REQUEST 1 audio MUTE output 53").is_err());
    }

    #[test]
    fn a_request_must_say_what_kind_of_thing_it_is_about() {
        // Sinks, sources and streams are numbered independently by the sound
        // daemon, so an id alone is ambiguous — and the way that shows up is a
        // fader moving the wrong device.
        assert!(parse_message("REQUEST 1 audio VOLUME 53 500").is_err());
        assert!(parse_message("REQUEST 1 audio VOLUME sink 53 500").is_err());
        assert!(parse_message("REQUEST 1 audio VOLUME OUTPUT 53 500").is_err());
        assert!(parse_message("REQUEST 1 audio MUTE 53 1").is_err());
        assert!(parse_message("REQUEST 1 audio DEFAULT 53").is_err());
    }

    #[test]
    fn nothing_but_a_device_can_be_asked_to_become_the_default() {
        // There is no default stream to be, so this is malformed rather than
        // merely unlucky, and it is refused here rather than left for the panel
        // to discover.
        assert!(parse_message("REQUEST 1 audio DEFAULT stream 1348").is_err());
        assert!(parse_message("REQUEST 1 audio DEFAULT input 57").is_ok());
        assert!(parse_message("REQUEST 1 audio DEFAULT output 53").is_ok());
    }

    #[test]
    fn an_entity_id_must_be_a_number() {
        assert!(parse_message("REQUEST 1 audio VOLUME output @DEFAULT_SINK@ 500").is_err());
        assert!(parse_message("REQUEST 1 audio DEFAULT output alsa_output.hdmi").is_err());
        assert!(parse_message("REQUEST 1 audio MUTE output -1 1").is_err());
    }

    #[test]
    fn requests_reject_trailing_fields() {
        assert!(parse_message("REQUEST 1 audio REFRESH now").is_err());
        assert!(parse_message("REQUEST 1 audio VOLUME output 1 1 1").is_err());
        assert!(parse_message("REQUEST 1 audio DEFAULT output 1 1").is_err());
    }

    #[test]
    fn a_request_before_the_handshake_is_rejected() {
        let mut session = Session::new();
        assert!(session.accept("REQUEST 1 audio REFRESH").is_err());
    }

    #[test]
    fn a_request_for_something_never_granted_is_a_protocol_violation() {
        // Not a refusal. A refusal is for something legal that could not be
        // done; asking about a domain nobody agreed to is a client that is
        // broken or probing, and it is hung up on either way.
        let mut session = Session::new();
        session.accept(HANDSHAKE).unwrap();
        assert!(session.accept("REQUEST 1 audio REFRESH").is_err());

        // Even having asked for it, nothing is legal until the host has said
        // it can serve it.
        let mut session = Session::new();
        session
            .accept("HELLO OTP/4 2400 1080 10 156000 69000 audio")
            .unwrap();
        assert!(session.accept("REQUEST 1 audio REFRESH").is_err());
        session.grant(Capabilities { audio: true });
        assert!(session.accept("REQUEST 1 audio REFRESH").is_ok());
    }

    #[test]
    fn requests_do_not_disturb_the_frame_sequence() {
        let mut session = session_with_audio();
        session.accept("FRAME 10 1000 0").unwrap();
        session
            .accept("REQUEST 1 audio VOLUME output 53 500")
            .unwrap();
        assert!(session.accept("FRAME 11 1001 0").is_ok());
    }

    // --- what goes back ---

    fn entity(kind: audio::Kind, id: u32, name: &str) -> audio::Entity {
        audio::Entity {
            kind,
            id,
            volume: 950,
            muted: false,
            default: true,
            target: None,
            name: name.to_owned(),
        }
    }

    #[test]
    fn renders_the_handshake_answer() {
        assert_eq!(
            Outbound::Welcome(Capabilities { audio: true }).to_string(),
            "WELCOME OTP/4 audio"
        );
        assert_eq!(
            Outbound::Welcome(Capabilities::NONE).to_string(),
            "WELCOME OTP/4 -"
        );
    }

    #[test]
    fn renders_a_snapshot_and_its_entries() {
        assert_eq!(
            Outbound::Snapshot {
                domain: Domain::Audio,
                generation: 7,
                count: 2,
            }
            .to_string(),
            "SNAPSHOT audio 7 2"
        );
        assert_eq!(
            Outbound::Entry {
                domain: Domain::Audio,
                generation: 7,
                entity: entity(audio::Kind::Output, 53, "HDMI Digital Stereo"),
            }
            .to_string(),
            "ENTRY audio 7 output 53 950 0 1 - HDMI%20Digital%20Stereo"
        );
    }

    #[test]
    fn a_stream_carries_the_output_it_plays_through() {
        let mut stream = entity(audio::Kind::Stream, 1348, "Firefox");
        stream.default = false;
        stream.target = Some(53);
        assert_eq!(
            Outbound::Changed {
                domain: Domain::Audio,
                generation: 7,
                entity: stream,
            }
            .to_string(),
            "CHANGED audio 7 stream 1348 950 0 0 53 Firefox"
        );
    }

    #[test]
    fn renders_removals_refusals_and_absence() {
        assert_eq!(
            Outbound::Removed {
                domain: Domain::Audio,
                generation: 8,
                kind: audio::Kind::Input,
                id: 57,
            }
            .to_string(),
            "REMOVED audio 8 input 57"
        );
        assert_eq!(
            Outbound::Refused {
                sequence: 12,
                reason: Refusal::UnknownId,
            }
            .to_string(),
            "REFUSED 12 unknown-id"
        );
        assert_eq!(
            Outbound::Unavailable {
                domain: Domain::Audio,
                reason: Absence::NoDaemon,
            }
            .to_string(),
            "UNAVAILABLE audio no-daemon"
        );
    }

    #[test]
    fn free_text_cannot_write_its_own_protocol_lines() {
        // A window title is written by whatever page a browser has open. Pasted
        // raw into a line-framed protocol it would be a way for a web page to
        // inject messages into the stream the phone is reading.
        let hostile = "ok\nCHANGED audio 1 output 53 1000 0 1 - pwned";
        let escaped = escape_text(hostile);
        assert!(!escaped.contains('\n'));
        assert!(!escaped.contains(' '));
        assert_eq!(escaped.lines().count(), 1);

        let rendered = Outbound::Entry {
            domain: Domain::Audio,
            generation: 1,
            entity: entity(audio::Kind::Output, 53, hostile),
        }
        .to_string();
        assert_eq!(rendered.lines().count(), 1);
        assert_eq!(rendered.split_whitespace().count(), 10);
    }

    #[test]
    fn escaping_leaves_ordinary_names_readable_and_survives_the_awkward_ones() {
        assert_eq!(escape_text("Firefox"), "Firefox");
        assert_eq!(escape_text("a b"), "a%20b");
        assert_eq!(escape_text("100%"), "100%25");
        assert_eq!(escape_text("\t"), "%09");
        // Non-ASCII goes out a byte at a time, so the client rebuilds the exact
        // string rather than a guess at it.
        assert_eq!(escape_text("ç"), "%C3%A7");
        // An empty name would otherwise vanish and shift every field after it.
        assert_eq!(escape_text(""), "%20");
    }

    #[test]
    fn every_line_the_host_sends_is_one_line_with_no_gaps_in_it() {
        let awkward = entity(audio::Kind::Output, 1, "a b\tc\nd  e");
        for message in [
            Outbound::Welcome(Capabilities { audio: true }),
            Outbound::Snapshot {
                domain: Domain::Audio,
                generation: 1,
                count: 1,
            },
            Outbound::Entry {
                domain: Domain::Audio,
                generation: 1,
                entity: awkward.clone(),
            },
            Outbound::Changed {
                domain: Domain::Audio,
                generation: 1,
                entity: awkward,
            },
            Outbound::Removed {
                domain: Domain::Audio,
                generation: 1,
                kind: audio::Kind::Stream,
                id: 1,
            },
            Outbound::Unavailable {
                domain: Domain::Audio,
                reason: Absence::Lost,
            },
            Outbound::Refused {
                sequence: 1,
                reason: Refusal::TooFast,
            },
        ] {
            let rendered = message.to_string();
            assert!(!rendered.contains('\n'), "{rendered:?} spans lines");
            assert!(!rendered.contains("  "), "{rendered:?} has an empty field");
            assert!(!rendered.ends_with(' '), "{rendered:?} ends in a separator");
            assert!(
                rendered.len() <= MAX_LINE_BYTES,
                "{rendered:?} is longer than a line may be"
            );
        }
    }
}
