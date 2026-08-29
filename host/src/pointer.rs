//! The mouse buttons the control surface can press.
//!
//! # Why this exists at all
//!
//! The touchpad declares `BTN_LEFT` and never presses it: every click in v0.1
//! comes from libinput's own tap-to-click handling. That works, right up until
//! somebody turns tap-to-click off — and then there is no click at all, in
//! silence. The frames go out, the host accepts them, and nothing happens.
//!
//! Turning tap-to-click off is a person telling their system that taps should
//! not click, and the trackpad honouring that is correct rather than broken.
//! What was missing is an *alternative*: an explicit button, on a rail, that
//! does not depend on how anybody has configured tapping.
//!
//! # Why a third device
//!
//! Not the touchpad. It is an `INPUT_PROP_BUTTONPAD`, and libinput does not
//! take a button press on a buttonpad at face value — it re-resolves it from
//! finger count and position, which is how clickfinger and button areas work.
//! Real clickpads only ever report `BTN_LEFT`; right and middle are libinput's
//! invention from fingers. So `BTN_RIGHT` there is not something hardware does,
//! and a `BTN_LEFT` there would be reinterpreted according to touch state —
//! recoupling the button to touch, which is the exact coupling the keyboard was
//! split out to break.
//!
//! Not the keyboard either. udev classifies a device by what it can produce, so
//! a keyboard reporting mouse buttons is tagged as both and turns up in the
//! desktop's mouse settings, inviting pointer settings onto a keyboard.
//!
//! # The relative axes are not decoration
//!
//! udev tags a device as a mouse when it has relative X and Y *and* a mouse
//! button. Without the axes this is not a pointer at all, and a click from
//! something that is not a pointer has no pointer to belong to. They are
//! declared and never moved: moving the pointer stays the touchpad's job, and
//! both devices share one cursor on X11 and Wayland, so a click lands wherever
//! the touchpad left it. That is how a mouse and a trackpad coexist on one desk.
//!
//! # Nothing stays held
//!
//! The same rule the keyboard keeps, and it matters more here. A stuck modifier
//! makes a desktop unusable; a stuck mouse button makes it unusable *and*
//! unfixable, because you cannot click anything to get out of it.

use std::io;
use std::path::PathBuf;

use evdev::uinput::VirtualDevice;
use evdev::{
    AttributeSet, BusType, InputEvent, InputId, KeyCode, KeyEvent, RelativeAxisCode,
    SynchronizationCode, SynchronizationEvent,
};

pub const DEVICE_NAME: &str = "OpenTrackpad Buttons";

/// Every button the control surface may press, by the name the client uses.
///
/// Three, fixed. Unlike the key vocabulary this is not gated by what somebody
/// recorded, and the asymmetry is deliberate: a hundred and thirty key names
/// whose combinations can do anything on a desktop need a list of what was
/// actually chosen, whereas three button names that can do nothing a mouse
/// cannot already do are their own protection. There is nothing meaningful to
/// record or delete here.
///
/// No numbers and no counts. A button named `3`, or a click repeated `n` times,
/// is the kind of thing that turns a vocabulary back into an interface.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Button {
    Left,
    Right,
    Middle,
}

impl Button {
    pub fn parse(name: &str) -> Option<Self> {
        match name {
            "left" => Some(Button::Left),
            "right" => Some(Button::Right),
            "middle" => Some(Button::Middle),
            _ => None,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Button::Left => "left",
            Button::Right => "right",
            Button::Middle => "middle",
        }
    }

    fn code(self) -> KeyCode {
        match self {
            Button::Left => KeyCode::BTN_LEFT,
            Button::Right => KeyCode::BTN_RIGHT,
            Button::Middle => KeyCode::BTN_MIDDLE,
        }
    }

    fn all() -> [Button; 3] {
        [Button::Left, Button::Right, Button::Middle]
    }
}

impl std::fmt::Display for Button {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.as_str())
    }
}

pub struct VirtualPointer {
    device: VirtualDevice,
    /// Buttons currently held, so they can be let go of unconditionally.
    held: Vec<KeyCode>,
    buffer: Vec<InputEvent>,
}

impl VirtualPointer {
    pub fn create() -> io::Result<Self> {
        let mut buttons = AttributeSet::<KeyCode>::new();
        for button in Button::all() {
            buttons.insert(button.code());
        }
        // Declared so udev calls this a mouse, and never moved. See the note at
        // the top of this file: without them the buttons belong to nothing.
        let mut axes = AttributeSet::<RelativeAxisCode>::new();
        axes.insert(RelativeAxisCode::REL_X);
        axes.insert(RelativeAxisCode::REL_Y);

        let device = VirtualDevice::builder()?
            .name(DEVICE_NAME)
            // Shares a vendor with the touchpad and the keyboard, with its own
            // product, so a desktop storing settings per device tells the three
            // apart.
            .input_id(InputId::new(BusType::BUS_VIRTUAL, 0x1d6b, 0x0f11, 0x0001))
            .with_keys(&buttons)?
            .with_relative_axes(&axes)?
            .build()?;

        Ok(Self {
            device,
            held: Vec::new(),
            buffer: Vec::with_capacity(4),
        })
    }

    /// Presses a button and lets it go again.
    ///
    /// Two reports, not one: the press is synchronised before the release, so
    /// the desktop sees a press followed by a release rather than a single
    /// report it has to guess at. The same shape the keyboard uses for a chord,
    /// which is measured to arrive intact.
    ///
    /// There is no hold. A held button is a drag, a drag needs the pointer
    /// moving while it is held, and that is the other path's job — coordinating
    /// a held button across two paths is where stuck buttons come from.
    pub fn click(&mut self, button: Button) -> io::Result<()> {
        let code = button.code();
        self.buffer.clear();
        self.held.push(code);
        self.buffer.push(KeyEvent::new(code, 1).into());
        self.buffer.push(sync());
        self.buffer.push(KeyEvent::new(code, 0).into());
        self.buffer.push(sync());

        self.device.emit(&self.buffer)?;
        // Only forget it once the kernel has the release.
        if let Some(index) = self.held.iter().position(|held| *held == code) {
            self.held.remove(index);
        }
        Ok(())
    }

    /// Lets go of everything, whatever state the caller thinks it is in.
    ///
    /// Releasing a button that is not held is harmless; leaving one held is
    /// not, and is worse than a stuck key because it cannot be clicked away.
    pub fn release_all(&mut self) -> io::Result<usize> {
        if self.held.is_empty() {
            return Ok(0);
        }
        let count = self.held.len();
        self.buffer.clear();
        for code in self.held.drain(..) {
            self.buffer.push(KeyEvent::new(code, 0).into());
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

impl Drop for VirtualPointer {
    fn drop(&mut self) {
        let _ = self.release_all();
    }
}

fn sync() -> InputEvent {
    SynchronizationEvent::new(SynchronizationCode::SYN_REPORT, 0).into()
}

/// Where button presses go: the real virtual device, or the terminal.
///
/// Built when a session starts rather than on the first click, for the reason
/// measured on the keyboard: a desktop discards anything from an input device
/// it has not finished opening, so a device created on demand loses the very
/// event that created it.
pub enum Buttons {
    Device(Option<VirtualPointer>),
    /// Prints instead of clicking, for `--dry-run`.
    Debug,
}

impl Buttons {
    pub fn new(dry_run: bool) -> Self {
        if dry_run {
            Buttons::Debug
        } else {
            Buttons::Device(None)
        }
    }

    pub fn prepare(&mut self) -> io::Result<()> {
        if let Buttons::Device(pointer @ None) = self {
            *pointer = Some(VirtualPointer::create()?);
        }
        Ok(())
    }

    pub fn click(&mut self, button: Button) -> io::Result<()> {
        match self {
            Buttons::Debug => {
                println!("    would click {button}");
                Ok(())
            }
            Buttons::Device(Some(pointer)) => pointer.click(button),
            Buttons::Device(None) => Err(io::Error::other(
                "no virtual pointer; the session did not start one",
            )),
        }
    }

    pub fn release_all(&mut self) -> io::Result<usize> {
        match self {
            Buttons::Debug => Ok(0),
            Buttons::Device(Some(pointer)) => pointer.release_all(),
            Buttons::Device(None) => Ok(0),
        }
    }

    pub fn describe(&mut self) -> Option<String> {
        let Buttons::Device(Some(pointer)) = self else {
            return None;
        };
        let nodes = pointer
            .device_nodes()
            .map(|nodes| {
                nodes
                    .iter()
                    .map(|path| path.display().to_string())
                    .collect::<Vec<_>>()
                    .join(", ")
            })
            .unwrap_or_else(|error| format!("(device node lookup failed: {error})"));
        Some(format!("virtual pointer \"{DEVICE_NAME}\" at {nodes}"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_vocabulary_is_three_names_and_nothing_else() {
        assert_eq!(Button::parse("left"), Some(Button::Left));
        assert_eq!(Button::parse("right"), Some(Button::Right));
        assert_eq!(Button::parse("middle"), Some(Button::Middle));
    }

    #[test]
    fn a_button_cannot_be_named_by_number_or_by_anything_else() {
        // A button named `3`, or a count, is what turns a vocabulary back into
        // an interface.
        for refused in [
            "1", "3", "0x110", "BTN_LEFT", "Left", "LEFT", "", "back", "forward",
        ] {
            assert_eq!(Button::parse(refused), None, "{refused:?} was accepted");
        }
    }

    #[test]
    fn every_name_survives_a_round_trip() {
        for button in Button::all() {
            assert_eq!(Button::parse(button.as_str()), Some(button));
        }
    }

    #[test]
    fn each_button_is_its_own_code() {
        let codes: Vec<_> = Button::all().iter().map(|button| button.code()).collect();
        assert_eq!(
            codes,
            vec![KeyCode::BTN_LEFT, KeyCode::BTN_RIGHT, KeyCode::BTN_MIDDLE]
        );
    }
}
