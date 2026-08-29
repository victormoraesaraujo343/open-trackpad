//! Virtual touchpad geometry and the device-agnostic event vocabulary.
//!
//! The state machine in [`crate::state`] speaks `PadEvent` so it can be tested
//! without `/dev/uinput`; [`crate::uinput`] is the only module that knows how a
//! `PadEvent` becomes a Linux input event.

/// Physical size the virtual touchpad claims to have.
///
/// libinput reasons about touchpads in millimetres, so the declared size and
/// resolution change pointer speed, scroll distance and gesture thresholds.
/// These are deliberate defaults chosen to look like a large laptop touchpad;
/// they are an assumption to validate on hardware, not a measurement. See
/// `docs/TESTING.md`.
pub const PAD_WIDTH_MM: i32 = 140;
pub const PAD_HEIGHT_MM: i32 = 63;

/// Device units per millimetre. Higher means finer motion quantisation.
pub const PAD_RESOLUTION: i32 = 40;

pub const PAD_MAX_X: i32 = PAD_WIDTH_MM * PAD_RESOLUTION;
pub const PAD_MAX_Y: i32 = PAD_HEIGHT_MM * PAD_RESOLUTION;

/// Multi-touch slots the virtual device exposes. The protocol allows up to 32
/// contacts, but no realistic touchpad gesture needs more than this.
pub const MAX_SLOTS: usize = 10;

/// The highest finger count that has a dedicated `BTN_TOOL_*` bit.
pub const MAX_REPORTED_FINGERS: u8 = 5;

/// One state change to apply to the virtual touchpad.
///
/// The ordering of a batch matters: `Slot` selects which contact the following
/// `Mt*` events describe, and `Sync` ends a complete report.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PadEvent {
    /// `ABS_MT_SLOT` — selects the slot subsequent `Mt*` events apply to.
    Slot(i32),
    /// `ABS_MT_TRACKING_ID` — a fresh id starts a contact, `-1` releases it.
    TrackingId(i32),
    MtPositionX(i32),
    MtPositionY(i32),
    MtTouchMajor(i32),
    /// Single-touch emulation of the oldest contact, for `ABS_X`.
    PositionX(i32),
    PositionY(i32),
    /// `BTN_TOUCH`.
    Touch(bool),
    /// Expands to the `BTN_TOOL_FINGER`/`DOUBLETAP`/... bits in the backend.
    ToolFingers(u8),
    /// `SYN_REPORT` — everything before it is one atomic frame.
    Sync,
}
