use std::fmt;
use std::io::{self, BufRead, BufReader};
use std::net::{TcpListener, TcpStream};

const DEFAULT_ADDRESS: &str = "127.0.0.1:4242";
const MAX_CONTACTS: u8 = 32;

#[derive(Debug, Clone, PartialEq, Eq)]
struct Hello {
    width: u32,
    height: u32,
    max_contacts: u8,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct Contact {
    id: u8,
    x: u32,
    y: u32,
    pressure: u16,
    major: u16,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct Frame {
    sequence: u64,
    event_time_ns: u64,
    contacts: Vec<Contact>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum Message {
    Hello(Hello),
    Frame(Frame),
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ProtocolError(String);

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

fn parse_message(line: &str) -> Result<Message, ProtocolError> {
    let mut parts = line.split_whitespace();
    match parts.next() {
        Some("HELLO") => {
            if parts.next() != Some("OTP/1") {
                return Err(ProtocolError("unsupported protocol version".into()));
            }
            let hello = Hello {
                width: parse_number(parts.next(), "width")?,
                height: parse_number(parts.next(), "height")?,
                max_contacts: parse_number(parts.next(), "max_contacts")?,
            };
            ensure_finished(&mut parts)?;
            if hello.width == 0 || hello.height == 0 {
                return Err(ProtocolError("touch dimensions must be positive".into()));
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
                if contact.pressure > 1024 {
                    return Err(ProtocolError("contact pressure exceeds 1024".into()));
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
        Some(other) => Err(ProtocolError(format!("unknown message type: {other}"))),
        None => Err(ProtocolError("empty message".into())),
    }
}

fn handle_client(stream: TcpStream) -> io::Result<()> {
    let peer = stream.peer_addr()?;
    println!("client connected: {peer}");
    let mut hello: Option<Hello> = None;
    let mut last_sequence: Option<u64> = None;

    for line in BufReader::new(stream).lines() {
        let line = line?;
        let message = parse_message(&line).map_err(|error| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                format!("protocol error: {error}"),
            )
        })?;

        match message {
            Message::Hello(value) => {
                if hello.is_some() {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "protocol error: duplicate HELLO",
                    ));
                }
                println!(
                    "touch surface: {}x{}, up to {} contacts",
                    value.width, value.height, value.max_contacts
                );
                hello = Some(value);
            }
            Message::Frame(frame) => {
                let capabilities = hello.as_ref().ok_or_else(|| {
                    io::Error::new(
                        io::ErrorKind::InvalidData,
                        "protocol error: FRAME before HELLO",
                    )
                })?;
                if frame.contacts.len() > capabilities.max_contacts as usize {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "protocol error: frame exceeds declared max_contacts",
                    ));
                }
                if frame.contacts.iter().any(|contact| {
                    contact.x >= capabilities.width || contact.y >= capabilities.height
                }) {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "protocol error: contact outside touch bounds",
                    ));
                }
                if last_sequence.is_some_and(|previous| frame.sequence <= previous) {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "protocol error: sequence did not increase",
                    ));
                }
                last_sequence = Some(frame.sequence);
                println!(
                    "frame={} time_ns={} contacts={}",
                    frame.sequence,
                    frame.event_time_ns,
                    frame.contacts.len()
                );
            }
        }
    }

    println!("client disconnected: {peer}; releasing all contacts (uinput TODO)");
    Ok(())
}

fn main() -> io::Result<()> {
    let address = std::env::args()
        .nth(1)
        .unwrap_or_else(|| DEFAULT_ADDRESS.to_owned());
    let listener = TcpListener::bind(&address)?;
    println!("OpenTrackpad protocol receiver listening on {address}");
    println!("uinput output is not implemented yet; frames are validation-only");

    for connection in listener.incoming() {
        match connection {
            Ok(stream) => {
                if let Err(error) = handle_client(stream) {
                    eprintln!("client failed: {error}");
                }
            }
            Err(error) => eprintln!("connection failed: {error}"),
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_hello() {
        assert_eq!(
            parse_message("HELLO OTP/1 1080 2400 10"),
            Ok(Message::Hello(Hello {
                width: 1080,
                height: 2400,
                max_contacts: 10,
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
        let error =
            parse_message("HELLO OTP/1 1080 2400 10 extra").expect_err("trailing input must fail");
        assert_eq!(error, ProtocolError("unexpected trailing fields".into()));
    }
}
