//! The `/dev/uinput` backend: the only module that knows about Linux input.
//!
//! Creating a device that emits touch events is not the same as creating a
//! device libinput classifies as a touchpad. The capability set below is chosen
//! to satisfy udev's `input_id` builtin (which decides `ID_INPUT_TOUCHPAD`) and
//! libinput's touchpad backend, but it is an assumption to verify on real
//! hardware — see `docs/TESTING.md`.

use std::io;
use std::path::PathBuf;

use evdev::uinput::VirtualDevice;
use evdev::{
    AbsInfo, AbsoluteAxisCode, AbsoluteAxisEvent, AttributeSet, BusType, InputEvent, InputId,
    KeyCode, KeyEvent, PropType, SynchronizationCode, SynchronizationEvent, UinputAbsSetup,
};

use crate::pad::*;

pub const DEVICE_NAME: &str = "OpenTrackpad Touchpad";

/// Reported in `ABS_MT_SLOT`'s range, so the kernel allocates that many slots.
const MAX_SLOT_INDEX: i32 = MAX_SLOTS as i32 - 1;

/// The `BTN_TOOL_*` bits, indexed by finger count minus one.
const TOOL_KEYS: [KeyCode; MAX_REPORTED_FINGERS as usize] = [
    KeyCode::BTN_TOOL_FINGER,
    KeyCode::BTN_TOOL_DOUBLETAP,
    KeyCode::BTN_TOOL_TRIPLETAP,
    KeyCode::BTN_TOOL_QUADTAP,
    KeyCode::BTN_TOOL_QUINTTAP,
];

pub struct UinputTouchpad {
    device: VirtualDevice,
    /// Reused across reports to keep the hot path allocation-free.
    buffer: Vec<InputEvent>,
}

impl UinputTouchpad {
    pub fn create() -> io::Result<Self> {
        let mut keys = AttributeSet::<KeyCode>::new();
        // BTN_TOOL_FINGER without BTN_TOOL_PEN, and no INPUT_PROP_DIRECT, is
        // what makes udev tag this a touchpad rather than a touchscreen.
        keys.insert(KeyCode::BTN_TOUCH);
        for key in TOOL_KEYS {
            keys.insert(key);
        }
        // Declared but never pressed in v0.1. libinput expects a touchpad to
        // have a left button; clicks come from its own tap-to-click handling.
        keys.insert(KeyCode::BTN_LEFT);

        let mut properties = AttributeSet::<PropType>::new();
        properties.insert(PropType::POINTER);
        // Clickpad semantics: no separate physical buttons under the surface.
        properties.insert(PropType::BUTTONPAD);

        let position = |maximum: i32| AbsInfo::new(0, 0, maximum, 0, 0, PAD_RESOLUTION);

        let device = VirtualDevice::builder()?
            .name(DEVICE_NAME)
            .input_id(InputId::new(BusType::BUS_VIRTUAL, 0x1d6b, 0x0f0f, 0x0001))
            .with_properties(&properties)?
            .with_keys(&keys)?
            // Single-touch axes must exist alongside the MT ones: udev looks for
            // ABS_X/ABS_Y, and libinput uses them as the emulated pointer.
            .with_absolute_axis(&UinputAbsSetup::new(
                AbsoluteAxisCode::ABS_X,
                position(PAD_MAX_X),
            ))?
            .with_absolute_axis(&UinputAbsSetup::new(
                AbsoluteAxisCode::ABS_Y,
                position(PAD_MAX_Y),
            ))?
            .with_absolute_axis(&UinputAbsSetup::new(
                AbsoluteAxisCode::ABS_MT_SLOT,
                AbsInfo::new(0, 0, MAX_SLOT_INDEX, 0, 0, 0),
            ))?
            .with_absolute_axis(&UinputAbsSetup::new(
                AbsoluteAxisCode::ABS_MT_TRACKING_ID,
                AbsInfo::new(0, -1, i32::MAX, 0, 0, 0),
            ))?
            .with_absolute_axis(&UinputAbsSetup::new(
                AbsoluteAxisCode::ABS_MT_POSITION_X,
                position(PAD_MAX_X),
            ))?
            .with_absolute_axis(&UinputAbsSetup::new(
                AbsoluteAxisCode::ABS_MT_POSITION_Y,
                position(PAD_MAX_Y),
            ))?
            .with_absolute_axis(&UinputAbsSetup::new(
                AbsoluteAxisCode::ABS_MT_TOUCH_MAJOR,
                position(PAD_MAX_X),
            ))?
            .build()?;

        Ok(Self {
            device,
            buffer: Vec::with_capacity(64),
        })
    }

    /// Writes one batch of pad events to the kernel.
    ///
    /// The batch is written in a single `write`, so a report terminated by
    /// [`PadEvent::Sync`] reaches the input core atomically.
    pub fn emit(&mut self, events: &[PadEvent]) -> io::Result<()> {
        if events.is_empty() {
            return Ok(());
        }
        self.buffer.clear();
        for event in events {
            encode(*event, &mut self.buffer);
        }
        self.device.emit(&self.buffer)
    }

    /// `/sys` path of the created device, for `udevadm info`.
    pub fn syspath(&mut self) -> io::Result<PathBuf> {
        self.device.get_syspath()
    }

    /// `/dev/input/event*` nodes the kernel created for this device.
    pub fn device_nodes(&mut self) -> io::Result<Vec<PathBuf>> {
        Ok(self
            .device
            .enumerate_dev_nodes_blocking()?
            .filter_map(Result::ok)
            .collect())
    }
}

fn absolute(axis: AbsoluteAxisCode, value: i32) -> InputEvent {
    AbsoluteAxisEvent::new(axis, value).into()
}

fn key(code: KeyCode, pressed: bool) -> InputEvent {
    KeyEvent::new(code, i32::from(pressed)).into()
}

fn encode(event: PadEvent, out: &mut Vec<InputEvent>) {
    match event {
        PadEvent::Slot(index) => out.push(absolute(AbsoluteAxisCode::ABS_MT_SLOT, index)),
        PadEvent::TrackingId(id) => out.push(absolute(AbsoluteAxisCode::ABS_MT_TRACKING_ID, id)),
        PadEvent::MtPositionX(value) => {
            out.push(absolute(AbsoluteAxisCode::ABS_MT_POSITION_X, value))
        }
        PadEvent::MtPositionY(value) => {
            out.push(absolute(AbsoluteAxisCode::ABS_MT_POSITION_Y, value))
        }
        PadEvent::MtTouchMajor(value) => {
            out.push(absolute(AbsoluteAxisCode::ABS_MT_TOUCH_MAJOR, value))
        }
        PadEvent::PositionX(value) => out.push(absolute(AbsoluteAxisCode::ABS_X, value)),
        PadEvent::PositionY(value) => out.push(absolute(AbsoluteAxisCode::ABS_Y, value)),
        PadEvent::Touch(down) => out.push(key(KeyCode::BTN_TOUCH, down)),
        PadEvent::ToolFingers(count) => {
            // Exactly one tool bit may be set at a time, so every bit is
            // rewritten; the input core drops the ones that did not change.
            for (index, code) in TOOL_KEYS.into_iter().enumerate() {
                out.push(key(code, count as usize == index + 1));
            }
        }
        PadEvent::Sync => {
            out.push(SynchronizationEvent::new(SynchronizationCode::SYN_REPORT, 0).into())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn encoded(event: PadEvent) -> Vec<InputEvent> {
        let mut out = Vec::new();
        encode(event, &mut out);
        out
    }

    #[test]
    fn a_finger_count_sets_exactly_one_tool_bit() {
        let events = encoded(PadEvent::ToolFingers(3));
        assert_eq!(events.len(), TOOL_KEYS.len());
        let pressed: Vec<_> = events
            .iter()
            .filter(|event| event.value() == 1)
            .map(|event| event.code())
            .collect();
        assert_eq!(pressed, vec![KeyCode::BTN_TOOL_TRIPLETAP.0]);
    }

    #[test]
    fn zero_fingers_clears_every_tool_bit() {
        let events = encoded(PadEvent::ToolFingers(0));
        assert!(events.iter().all(|event| event.value() == 0));
    }

    #[test]
    fn a_release_encodes_the_sentinel_tracking_id() {
        let events = encoded(PadEvent::TrackingId(-1));
        assert_eq!(events[0].code(), AbsoluteAxisCode::ABS_MT_TRACKING_ID.0);
        assert_eq!(events[0].value(), -1);
    }
}
