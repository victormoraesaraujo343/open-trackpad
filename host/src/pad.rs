//! Virtual touchpad geometry and the device-agnostic event vocabulary.
//!
//! The state machine in [`crate::state`] speaks `PadEvent` so it can be tested
//! without `/dev/uinput`; [`crate::uinput`] is the only module that knows how a
//! `PadEvent` becomes a Linux input event.

/// Device units per millimetre. Higher means finer motion quantisation.
pub const PAD_RESOLUTION: i32 = 40;

/// How much a contact may wander before the movement is believed, in device
/// units — a quarter of a millimetre here.
///
/// A finger is never perfectly still and a touchscreen is never perfectly
/// precise, so raw positions jitter. Real touchpads declare a fuzz value and
/// the jitter is filtered out; declaring zero means every tremor reaches the
/// pointer, which is invisible during fast movement and ruins fine positioning.
///
/// Zero is worse than it looks: libinput turns its own hysteresis off entirely
/// when a device reports no fuzz, on the assumption that the hardware already
/// filters. So the effect is not a weaker filter but no filter at all, in either
/// the kernel or libinput. A quarter of a millimetre is what libinput would
/// otherwise pick for a device of this resolution.
pub const PAD_FUZZ: i32 = PAD_RESOLUTION / 4;

/// Multi-touch slots the virtual device exposes. The protocol allows up to 32
/// contacts, but no realistic touchpad gesture needs more than this.
pub const MAX_SLOTS: usize = 10;

/// The highest finger count that has a dedicated `BTN_TOOL_*` bit.
pub const MAX_REPORTED_FINGERS: u8 = 5;

/// How big the virtual touchpad claims to be.
///
/// This is not cosmetic. libinput reasons about touchpads in millimetres, so the
/// declared size sets pointer speed, scroll distance, gesture thresholds and
/// edge zones. A phone that reports its real touch surface therefore behaves
/// like a touchpad of that size, and every phone behaves consistently — which is
/// the point, since screen sizes differ on every device.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PadGeometry {
    pub max_x: i32,
    pub max_y: i32,
}

impl PadGeometry {
    /// Smallest and largest believable touch surface, in micrometres. Anything
    /// outside this is a client bug or a hostile client, not a phone.
    const MIN_UM: u32 = 20_000;
    const MAX_UM: u32 = 500_000;

    /// Used before any client has said how big its surface is: for the
    /// self-test, and so the device exists for `libinput list-devices` while
    /// nothing is connected. Roughly a large laptop touchpad.
    pub const DEFAULT: Self = Self {
        max_x: 140 * PAD_RESOLUTION,
        max_y: 63 * PAD_RESOLUTION,
    };

    /// Builds geometry from a physical size in micrometres.
    ///
    /// One device unit is 1/[`PAD_RESOLUTION`] mm, so a micrometre count divides
    /// down exactly: 1000 µm per mm over 40 units per mm is 25 µm per unit.
    pub fn from_micrometres(width_um: u32, height_um: u32) -> Self {
        let axis = |micrometres: u32| {
            (micrometres.clamp(Self::MIN_UM, Self::MAX_UM) / (1000 / PAD_RESOLUTION as u32)) as i32
        };
        Self {
            max_x: axis(width_um),
            max_y: axis(height_um),
        }
    }

    pub fn width_mm(&self) -> i32 {
        self.max_x / PAD_RESOLUTION
    }

    pub fn height_mm(&self) -> i32 {
        self.max_y / PAD_RESOLUTION
    }
}

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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_physical_size_becomes_device_units() {
        // 156 mm at 40 units/mm.
        let geometry = PadGeometry::from_micrometres(156_000, 69_000);
        assert_eq!(geometry.max_x, 6240);
        assert_eq!(geometry.max_y, 2760);
        assert_eq!(geometry.width_mm(), 156);
        assert_eq!(geometry.height_mm(), 69);
    }

    #[test]
    fn absurd_sizes_are_clamped_rather_than_trusted() {
        let tiny = PadGeometry::from_micrometres(1, 1);
        assert_eq!(tiny.width_mm(), 20);

        let huge = PadGeometry::from_micrometres(u32::MAX, u32::MAX);
        assert_eq!(huge.width_mm(), 500);
    }
}
