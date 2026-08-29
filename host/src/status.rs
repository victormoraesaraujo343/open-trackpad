//! Publishes what the daemon is doing, for anything that wants to display it.
//!
//! A line of text in the runtime directory, rewritten whenever the state
//! changes and removed on exit. Deliberately not a socket or a D-Bus service:
//! the daemon injects input into the desktop, and a status readout is not worth
//! giving it a second thing to listen on.
//!
//! Every failure here is swallowed. Nobody should lose their trackpad because a
//! status file could not be written.

use std::fmt;
use std::fs;
use std::path::PathBuf;

/// What the daemon is doing, in the words the tray shows.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum State {
    /// Running, with no phone connected.
    Waiting,
    /// A phone is connected and its touch surface is this many pixels.
    Connected { width: u32, height: u32 },
}

impl fmt::Display for State {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            State::Waiting => formatter.write_str("waiting"),
            State::Connected { width, height } => write!(formatter, "connected {width} {height}"),
        }
    }
}

impl std::str::FromStr for State {
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

pub struct StatusFile {
    path: Option<PathBuf>,
}

impl StatusFile {
    /// Opens the status file under `$XDG_RUNTIME_DIR`, which is per-user, on
    /// tmpfs, and cleared at logout — exactly the lifetime this has.
    ///
    /// Returns a no-op writer if there is no runtime directory, so a daemon
    /// started outside a session still works.
    pub fn open() -> Self {
        let path = std::env::var_os("XDG_RUNTIME_DIR").map(|runtime| {
            let mut path = PathBuf::from(runtime);
            path.push("opentrackpad");
            let _ = fs::create_dir_all(&path);
            path.push("state");
            path
        });
        Self { path }
    }

    /// Records the current state, replacing whatever was there.
    ///
    /// Written to a temporary file and renamed, so a reader never sees a
    /// half-written line.
    pub fn set(&self, state: &State) {
        let Some(path) = &self.path else {
            return;
        };
        let temporary = path.with_extension("tmp");
        if fs::write(&temporary, format!("{state}\n")).is_ok() {
            let _ = fs::rename(&temporary, path);
        }
    }
}

impl Drop for StatusFile {
    fn drop(&mut self) {
        if let Some(path) = &self.path {
            // An absent file means "not running", which is the truth once this
            // process is gone.
            let _ = fs::remove_file(path);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn roundtrip(state: State) {
        let rendered = state.to_string();
        assert_eq!(rendered.parse::<State>(), Ok(state));
    }

    #[test]
    fn states_survive_being_written_and_read_back() {
        roundtrip(State::Waiting);
        roundtrip(State::Connected {
            width: 2412,
            height: 1080,
        });
    }

    #[test]
    fn a_damaged_line_is_rejected_rather_than_guessed_at() {
        assert!("".parse::<State>().is_err());
        assert!("connected".parse::<State>().is_err());
        assert!("connected 2412".parse::<State>().is_err());
        assert!("connected wide tall".parse::<State>().is_err());
        assert!("nonsense".parse::<State>().is_err());
    }
}
