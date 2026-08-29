//! What the daemon is doing, as the tray understands it.
//!
//! Deliberately duplicated rather than shared with the daemon: the format is
//! one line of text, and a crate dependency between them would exist only to
//! avoid retyping thirty lines.

use std::str::FromStr;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum State {
    /// No status file, so the daemon is not running.
    Stopped,
    /// Running, with no phone connected.
    Waiting,
    /// A phone is connected, with a touch surface this many pixels across.
    Connected { width: u32, height: u32 },
}

impl State {
    /// One line for the tooltip and the menu's first entry.
    pub fn describe(&self) -> String {
        match self {
            State::Stopped => "Not running".into(),
            State::Waiting => "Waiting for a phone".into(),
            State::Connected { width, height } => {
                format!("Connected — {width}x{height} touch surface")
            }
        }
    }
}

impl FromStr for State {
    type Err = ();

    fn from_str(line: &str) -> Result<Self, Self::Err> {
        let mut parts = line.split_whitespace();
        match parts.next() {
            Some("waiting") => Ok(State::Waiting),
            Some("connected") => {
                let width = parts
                    .next()
                    .and_then(|value| value.parse().ok())
                    .ok_or(())?;
                let height = parts
                    .next()
                    .and_then(|value| value.parse().ok())
                    .ok_or(())?;
                Ok(State::Connected { width, height })
            }
            _ => Err(()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_what_the_daemon_writes() {
        assert_eq!("waiting".parse(), Ok(State::Waiting));
        assert_eq!(
            "connected 2412 1080".parse(),
            Ok(State::Connected {
                width: 2412,
                height: 1080,
            })
        );
    }

    #[test]
    fn a_damaged_line_is_rejected_so_the_caller_can_treat_it_as_stopped() {
        assert!("".parse::<State>().is_err());
        assert!("connected".parse::<State>().is_err());
        assert!("connected 2412".parse::<State>().is_err());
        assert!("garbage".parse::<State>().is_err());
    }

    #[test]
    fn every_state_describes_itself() {
        for state in [
            State::Stopped,
            State::Waiting,
            State::Connected {
                width: 2412,
                height: 1080,
            },
        ] {
            assert!(!state.describe().is_empty());
        }
    }
}
