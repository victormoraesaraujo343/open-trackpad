//! The virtual keyboard the control surface types on.
//!
//! A device of its own, separate from the touchpad. Mixing keys into the
//! touchpad would confuse udev about what the device is, and a shortcut going
//! wrong must never be able to corrupt touch state.
//!
//! Its one hard rule: nothing stays held. A stuck Ctrl is worse than a shortcut
//! that never fired, because the desktop becomes unusable and the cause is
//! invisible.

use std::io;
use std::path::PathBuf;
use std::time::Instant;

use evdev::uinput::VirtualDevice;
use evdev::{
    AttributeSet, BusType, InputEvent, InputId, KeyCode, KeyEvent, SynchronizationCode,
    SynchronizationEvent,
};

use crate::keys::{all_keys, Chord};
use crate::timing::TokenBucket;

pub const DEVICE_NAME: &str = "OpenTrackpad Keyboard";

pub struct VirtualKeyboard {
    device: VirtualDevice,
    /// Keys currently held down, so they can be let go of unconditionally.
    held: Vec<KeyCode>,
    buffer: Vec<InputEvent>,
}

impl VirtualKeyboard {
    pub fn create() -> io::Result<Self> {
        let mut keys = AttributeSet::<KeyCode>::new();
        for key in all_keys() {
            keys.insert(key);
        }

        let device = VirtualDevice::builder()?
            .name(DEVICE_NAME)
            // Shares a vendor with the touchpad, with its own product, so a
            // desktop storing settings per device tells them apart.
            .input_id(InputId::new(BusType::BUS_VIRTUAL, 0x1d6b, 0x0f10, 0x0001))
            .with_keys(&keys)?
            .build()?;

        Ok(Self {
            device,
            held: Vec::new(),
            buffer: Vec::with_capacity(16),
        })
    }

    /// Presses a chord and lets it go again.
    ///
    /// Modifiers go down before the key they modify and come up after it, which
    /// is the order applications expect. The whole chord is one report, so the
    /// desktop sees it as a single keystroke rather than a race.
    ///
    /// If the release fails, the keys stay recorded as held so the next cleanup
    /// can free them.
    pub fn press_chord(&mut self, chord: &Chord) -> io::Result<()> {
        self.buffer.clear();
        for key in chord.keys() {
            self.held.push(*key);
            self.buffer.push(KeyEvent::new(*key, 1).into());
        }
        self.buffer.push(sync());
        for key in chord.keys().iter().rev() {
            self.buffer.push(KeyEvent::new(*key, 0).into());
        }
        self.buffer.push(sync());

        self.device.emit(&self.buffer)?;
        // Only forget them once the kernel has the release.
        for key in chord.keys() {
            if let Some(index) = self.held.iter().position(|held| held == key) {
                self.held.remove(index);
            }
        }
        Ok(())
    }

    /// Lets go of everything, whatever state the caller thinks it is in.
    ///
    /// Called when a session ends for any reason. Releasing a key that is not
    /// held is harmless; leaving one held is not.
    pub fn release_all(&mut self) -> io::Result<usize> {
        if self.held.is_empty() {
            return Ok(0);
        }
        let count = self.held.len();
        self.buffer.clear();
        for key in self.held.drain(..) {
            self.buffer.push(KeyEvent::new(key, 0).into());
        }
        self.buffer.push(sync());
        self.device.emit(&self.buffer)?;
        Ok(count)
    }

    pub fn device_nodes(&mut self) -> io::Result<Vec<PathBuf>> {
        Ok(self
            .device
            .enumerate_dev_nodes_blocking()?
            .filter_map(Result::ok)
            .collect())
    }
}

impl Drop for VirtualKeyboard {
    fn drop(&mut self) {
        // The kernel releases keys when the device goes, but saying so
        // explicitly costs nothing and does not depend on that being true.
        let _ = self.release_all();
    }
}

fn sync() -> InputEvent {
    SynchronizationEvent::new(SynchronizationCode::SYN_REPORT, 0).into()
}

/// Where key chords go: the real virtual keyboard, or the terminal.
///
/// The device is created when a session starts, not when the first shortcut
/// arrives. A desktop opens an input device on udev hotplug, and anything typed
/// before it finishes is discarded — so a keyboard built on demand loses the
/// very keystroke that built it. Measured: of three key presses sent as a device
/// was created, the first vanished and the rest landed.
pub enum Controls {
    Device(Option<VirtualKeyboard>),
    /// Prints instead of typing, for `--dry-run`.
    Debug,
}

impl Controls {
    pub fn new(dry_run: bool) -> Self {
        if dry_run {
            Controls::Debug
        } else {
            Controls::Device(None)
        }
    }

    /// Builds the keyboard, so it is open and being watched before anyone can
    /// press anything.
    pub fn prepare(&mut self) -> io::Result<()> {
        if let Controls::Device(keyboard @ None) = self {
            *keyboard = Some(VirtualKeyboard::create()?);
        }
        Ok(())
    }

    pub fn press_chord(&mut self, chord: &Chord) -> io::Result<()> {
        match self {
            Controls::Debug => {
                println!("    would press {:?}", chord.keys());
                Ok(())
            }
            Controls::Device(Some(keyboard)) => keyboard.press_chord(chord),
            Controls::Device(None) => Err(io::Error::other(
                "no virtual keyboard; the session did not start one",
            )),
        }
    }

    /// Lets go of every held key. Safe when nothing is held, and when no
    /// keyboard has been created at all.
    pub fn release_all(&mut self) -> io::Result<usize> {
        match self {
            Controls::Debug => Ok(0),
            Controls::Device(Some(keyboard)) => keyboard.release_all(),
            Controls::Device(None) => Ok(0),
        }
    }

    pub fn describe(&mut self) -> Option<String> {
        let Controls::Device(Some(keyboard)) = self else {
            return None;
        };
        let nodes = keyboard
            .device_nodes()
            .map(|nodes| {
                nodes
                    .iter()
                    .map(|path| path.display().to_string())
                    .collect::<Vec<_>>()
                    .join(", ")
            })
            .unwrap_or_else(|error| format!("(device node lookup failed: {error})"));
        Some(format!("virtual keyboard \"{DEVICE_NAME}\" at {nodes}"))
    }
}

/// Limits how fast shortcuts may be sent.
///
/// A finger on a button tops out around fifteen presses a second, and pressing
/// two chords microseconds apart is something only a bug does — measured, the
/// desktop collapses those into one anyway. This is not there to make typing
/// feel right; it is there so a client stuck in a loop cannot hammer the
/// desktop with keystrokes.
///
/// A token bucket rather than a minimum gap, so a short flurry of deliberate
/// presses goes through while a sustained flood does not. The panel's requests
/// are limited the same way and with different numbers; the shape they share
/// lives in `crate::timing`.
pub struct ActionRate(TokenBucket);

impl ActionRate {
    /// How many may arrive back to back before the rate starts to bite.
    const BURST: f64 = 20.0;

    /// The sustained ceiling, comfortably above what a hand can do.
    const PER_SECOND: f64 = 50.0;

    pub fn new(now: Instant) -> Self {
        Self(TokenBucket::new(now, Self::BURST, Self::PER_SECOND))
    }

    /// Whether an action arriving at `now` may proceed.
    pub fn allow(&mut self, now: Instant) -> bool {
        self.0.allow(now)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn a_flurry_of_deliberate_presses_goes_through() {
        let start = Instant::now();
        let mut rate = ActionRate::new(start);
        for press in 0..ActionRate::BURST as u32 {
            assert!(rate.allow(start), "press {press} was refused");
        }
    }

    #[test]
    fn a_client_stuck_in_a_loop_is_cut_off() {
        let start = Instant::now();
        let mut rate = ActionRate::new(start);
        for _ in 0..ActionRate::BURST as u32 {
            rate.allow(start);
        }
        assert!(!rate.allow(start), "the flood was not stopped");
    }

    #[test]
    fn the_allowance_comes_back_over_time() {
        let start = Instant::now();
        let mut rate = ActionRate::new(start);
        for _ in 0..ActionRate::BURST as u32 {
            rate.allow(start);
        }
        assert!(!rate.allow(start));

        // Half a second at fifty a second is twenty-five, so the bucket refills.
        let later = start + Duration::from_millis(500);
        assert!(rate.allow(later));
    }

    #[test]
    fn a_hand_pressing_buttons_is_never_refused() {
        // Fifteen a second for two seconds: faster than anyone taps, sustained
        // longer than anyone sustains it.
        let mut now = Instant::now();
        let mut rate = ActionRate::new(now);
        for press in 0..30 {
            now += Duration::from_millis(66);
            assert!(rate.allow(now), "press {press} was refused");
        }
    }
}
