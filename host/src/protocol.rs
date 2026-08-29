//! OTP/2 wire protocol: line parsing plus per-connection validation.
//!
//! See `docs/PROTOCOL.md`. Parsing is deliberately free of I/O so the whole
//! surface is unit-testable.

use std::fmt;

use crate::keys::Chord;

/// The protocol version this host speaks.
///
/// Version 2 added the physical size of the touch surface to the handshake.
/// Without it the host had to assume a size, which was wrong on every phone
/// but the one it was guessed for.
pub const VERSION: &str = "OTP/3";

/// Hard ceiling on contacts in any message, independent of what a client
/// declares. Bounds the allocation a single line can trigger.
pub const MAX_CONTACTS: u8 = 32;

/// Highest pressure value the protocol allows.
pub const MAX_PROTOCOL_PRESSURE: u16 = 1024;

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

fn ensure_finished(parts: &mut std::str::SplitWhitespace<'_>) -> Result<(), ProtocolError> {
    if parts.next().is_some() {
        return Err(ProtocolError("unexpected trailing fields".into()));
    }
    Ok(())
}

pub fn parse_message(line: &str) -> Result<Message, ProtocolError> {
    let mut parts = line.split_whitespace();
    match parts.next() {
        Some("HELLO") => {
            if parts.next() != Some(VERSION) {
                return Err(ProtocolError(format!(
                    "unsupported protocol version, expected {VERSION}"
                )));
            }
            let hello = Hello {
                width: parse_number(parts.next(), "width")?,
                height: parse_number(parts.next(), "height")?,
                max_contacts: parse_number(parts.next(), "max_contacts")?,
                width_um: parse_number(parts.next(), "width_um")?,
                height_um: parse_number(parts.next(), "height_um")?,
            };
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
                Some(other) => Err(ProtocolError(format!("unknown action kind: {other}"))),
                None => Err(ProtocolError("missing action kind".into())),
            }
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
}

/// Per-connection protocol state: enforces the rules a single line cannot
/// check on its own (handshake order, sequence monotonicity, touch bounds).
#[derive(Debug, Default)]
pub struct Session {
    hello: Option<Hello>,
    last_sequence: Option<u64>,
}

impl Session {
    pub fn new() -> Self {
        Self::default()
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

    #[test]
    fn parses_hello() {
        assert_eq!(
            parse_message("HELLO OTP/3 1080 2400 10 69000 156000"),
            Ok(Message::Hello(Hello {
                width: 1080,
                height: 2400,
                max_contacts: 10,
                width_um: 69_000,
                height_um: 156_000,
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
        let error = parse_message("HELLO OTP/3 1080 2400 10 69000 156000 extra")
            .expect_err("trailing input must fail");
        assert_eq!(error, ProtocolError("unexpected trailing fields".into()));
    }

    #[test]
    fn rejects_zero_physical_dimensions() {
        // A client that cannot measure its own screen must say so by failing,
        // not by sending zero and letting the host invent a size.
        assert!(parse_message("HELLO OTP/3 1080 2400 10 0 156000").is_err());
        assert!(parse_message("HELLO OTP/3 1080 2400 10 69000 0").is_err());
    }

    #[test]
    fn rejects_older_protocol_versions() {
        // Version 1 had no physical size and version 2 had no actions, so
        // accepting either would mean guessing at what the client can do.
        assert!(parse_message("HELLO OTP/1 1080 2400 10").is_err());
        assert!(parse_message("HELLO OTP/2 1080 2400 10 69000 156000").is_err());
    }

    #[test]
    fn a_handshake_yields_the_pad_geometry() {
        let Ok(Message::Hello(hello)) = parse_message("HELLO OTP/3 2412 1080 10 156000 69000")
        else {
            panic!("expected a handshake");
        };
        assert_eq!(hello.geometry().width_mm(), 156);
        assert_eq!(hello.geometry().height_mm(), 69);
    }

    #[test]
    fn rejects_zero_dimensions() {
        assert!(parse_message("HELLO OTP/3 0 2400 10 69000 156000").is_err());
        assert!(parse_message("HELLO OTP/3 1080 0 10 69000 156000").is_err());
    }

    #[test]
    fn rejects_out_of_range_max_contacts() {
        assert!(parse_message("HELLO OTP/3 1080 2400 0 69000 156000").is_err());
        assert!(parse_message("HELLO OTP/3 1080 2400 33 69000 156000").is_err());
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
    fn frame_before_hello_is_rejected() {
        let mut session = Session::new();
        assert!(session.accept("FRAME 1 1000 0").is_err());
    }

    #[test]
    fn duplicate_hello_is_rejected() {
        let mut session = Session::new();
        session
            .accept("HELLO OTP/3 2400 1080 10 156000 69000")
            .unwrap();
        assert!(session
            .accept("HELLO OTP/3 2400 1080 10 156000 69000")
            .is_err());
    }

    #[test]
    fn sequence_must_increase() {
        let mut session = Session::new();
        session
            .accept("HELLO OTP/3 2400 1080 10 156000 69000")
            .unwrap();
        session.accept("FRAME 5 1000 0").unwrap();
        assert!(session.accept("FRAME 5 1001 0").is_err());
        assert!(session.accept("FRAME 4 1002 0").is_err());
    }

    #[test]
    fn sequence_gaps_are_allowed() {
        let mut session = Session::new();
        session
            .accept("HELLO OTP/3 2400 1080 10 156000 69000")
            .unwrap();
        session.accept("FRAME 5 1000 0").unwrap();
        // Frames are complete snapshots, so a gap needs no recovery.
        assert!(session.accept("FRAME 900 1001 0").is_ok());
    }

    #[test]
    fn contacts_outside_the_declared_surface_are_rejected() {
        let mut session = Session::new();
        session
            .accept("HELLO OTP/3 2400 1080 10 156000 69000")
            .unwrap();
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
        session
            .accept("HELLO OTP/3 2400 1080 10 156000 69000")
            .unwrap();
        session.accept("FRAME 10 1000 0").unwrap();
        session.accept("ACTION 1 KEY ctrl+c").unwrap();
        assert!(session.accept("FRAME 11 1001 0").is_ok());
    }

    #[test]
    fn frames_may_not_exceed_the_declared_contact_limit() {
        let mut session = Session::new();
        session
            .accept("HELLO OTP/3 2400 1080 2 156000 69000")
            .unwrap();
        assert!(session
            .accept("FRAME 1 1000 3 0 1 1 1 1 1 2 2 1 1 2 3 3 1 1")
            .is_err());
    }
}
