//! Translates protocol frames into multi-touch slot updates.
//!
//! Android pointer IDs are recycled the moment a finger lifts, so they cannot
//! be used as kernel tracking IDs. This module owns the mapping from pointer ID
//! to a stable slot plus a never-reused tracking ID, and it is the only place
//! that knows whether a contact is currently down.

use crate::pad::*;
use crate::protocol::{Frame, Hello};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Slot {
    pointer_id: u8,
    tracking_id: i32,
    /// Monotonic birth order, used to find the oldest surviving contact.
    age: u64,
    x: i32,
    y: i32,
    major: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Scaled {
    x: i32,
    y: i32,
    major: i32,
}

/// Maps the phone's touch surface onto the virtual touchpad's coordinate space.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Surface {
    width: u32,
    height: u32,
}

impl Default for Surface {
    fn default() -> Self {
        Self {
            width: 1,
            height: 1,
        }
    }
}

#[derive(Debug)]
pub struct ContactState {
    slots: [Option<Slot>; MAX_SLOTS],
    /// Counts every contact ever started; drives both the kernel tracking ID
    /// and contact age ordering.
    next_contact: u64,
    /// Last slot selected on the device, so redundant `ABS_MT_SLOT` is skipped.
    selected_slot: Option<i32>,
    /// Last single-touch emulation position, to skip redundant `ABS_X`/`ABS_Y`.
    emulated: Option<(i32, i32)>,
    fingers: u8,
    touching: bool,
    surface: Surface,
    /// How big the virtual pad currently is, in device units.
    geometry: PadGeometry,
    dropped_contacts: u64,
}

impl Default for ContactState {
    fn default() -> Self {
        Self::new()
    }
}

impl ContactState {
    pub fn new() -> Self {
        Self {
            slots: [None; MAX_SLOTS],
            next_contact: 0,
            selected_slot: None,
            emulated: None,
            fingers: 0,
            touching: false,
            surface: Surface::default(),
            geometry: PadGeometry::DEFAULT,
            dropped_contacts: 0,
        }
    }

    /// Contacts silently discarded because every slot was already in use.
    pub fn dropped_contacts(&self) -> u64 {
        self.dropped_contacts
    }

    pub fn active_contacts(&self) -> usize {
        self.slots.iter().flatten().count()
    }

    /// Starts a new client session. Any contact left over from a previous
    /// session is released first, so a reconnect can never inherit a stuck
    /// finger.
    pub fn begin_session(&mut self, hello: &Hello) -> Vec<PadEvent> {
        let events = self.release_all();
        self.surface = Surface {
            width: hello.width,
            height: hello.height,
        };
        self.geometry = hello.geometry();
        events
    }

    /// Forgets everything known about the device, without emitting anything.
    ///
    /// Called after the virtual device is recreated: the replacement starts with
    /// every slot free and no axis set, so cached state about the old one would
    /// suppress events the new one has never seen.
    pub fn device_replaced(&mut self) {
        self.slots = [None; MAX_SLOTS];
        self.selected_slot = None;
        self.emulated = None;
        self.fingers = 0;
        self.touching = false;
    }

    /// Releases every active contact. Safe to call when nothing is down, in
    /// which case it emits nothing.
    pub fn release_all(&mut self) -> Vec<PadEvent> {
        let mut events = Vec::new();
        for index in 0..MAX_SLOTS {
            if self.slots[index].is_some() {
                self.select_slot(&mut events, index as i32);
                events.push(PadEvent::TrackingId(-1));
                self.slots[index] = None;
            }
        }
        if self.touching {
            events.push(PadEvent::Touch(false));
            self.touching = false;
        }
        if self.fingers != 0 {
            events.push(PadEvent::ToolFingers(0));
            self.fingers = 0;
        }
        if !events.is_empty() {
            events.push(PadEvent::Sync);
        }
        self.emulated = None;
        events
    }

    /// Applies a complete contact snapshot.
    ///
    /// A contact absent from `frame` is released; a pointer ID not currently
    /// held gets a free slot and a fresh tracking ID.
    pub fn apply(&mut self, frame: &Frame) -> Vec<PadEvent> {
        let mut events = Vec::new();

        // Release first: freeing slots before allocating lets a frame that
        // swaps one finger for another reuse the slot it just vacated.
        for index in 0..MAX_SLOTS {
            let Some(slot) = self.slots[index] else {
                continue;
            };
            if !frame
                .contacts
                .iter()
                .any(|contact| contact.id == slot.pointer_id)
            {
                self.select_slot(&mut events, index as i32);
                events.push(PadEvent::TrackingId(-1));
                self.slots[index] = None;
            }
        }

        for contact in &frame.contacts {
            let scaled = self.scale(contact.x, contact.y, contact.major);
            match self.slot_of(contact.id) {
                Some(index) => {
                    let previous = self.slots[index].expect("slot_of returns occupied slots");
                    let updated = Slot {
                        pointer_id: contact.id,
                        tracking_id: previous.tracking_id,
                        age: previous.age,
                        x: scaled.x,
                        y: scaled.y,
                        major: scaled.major,
                    };
                    if updated == previous {
                        continue;
                    }
                    self.select_slot(&mut events, index as i32);
                    push_axes(&mut events, Some(previous), &updated);
                    self.slots[index] = Some(updated);
                }
                None => {
                    let Some(index) = self.slots.iter().position(Option::is_none) else {
                        self.dropped_contacts += 1;
                        continue;
                    };
                    let (tracking_id, age) = self.take_contact_id();
                    let slot = Slot {
                        pointer_id: contact.id,
                        tracking_id,
                        age,
                        x: scaled.x,
                        y: scaled.y,
                        major: scaled.major,
                    };
                    self.select_slot(&mut events, index as i32);
                    events.push(PadEvent::TrackingId(tracking_id));
                    push_axes(&mut events, None, &slot);
                    self.slots[index] = Some(slot);
                }
            }
        }

        self.push_pointer_emulation(&mut events);

        if !events.is_empty() {
            events.push(PadEvent::Sync);
        }
        events
    }

    /// `BTN_TOUCH`, `BTN_TOOL_*` and the `ABS_X`/`ABS_Y` single-touch axes.
    ///
    /// The kernel synthesises these for real drivers via
    /// `input_mt_report_pointer_emulation`; a uinput device has to do it
    /// itself, and libinput refuses to treat a device as a touchpad without
    /// them.
    fn push_pointer_emulation(&mut self, events: &mut Vec<PadEvent>) {
        let fingers = (self.active_contacts() as u8).min(MAX_REPORTED_FINGERS);
        if fingers != self.fingers {
            events.push(PadEvent::ToolFingers(fingers));
            self.fingers = fingers;
        }

        let touching = self.active_contacts() > 0;
        if touching != self.touching {
            events.push(PadEvent::Touch(touching));
            self.touching = touching;
        }

        // The oldest surviving contact drives the emulated pointer, matching the
        // kernel. Slot index is not age: a freed low slot is handed to the next
        // new contact, so ordering has to come from `age`.
        let Some(oldest) = self
            .slots
            .iter()
            .flatten()
            .min_by_key(|slot| slot.age)
            .copied()
        else {
            self.emulated = None;
            return;
        };
        let current = (oldest.x, oldest.y);
        if self.emulated == Some(current) {
            return;
        }
        let previous = self.emulated;
        if previous.map(|value| value.0) != Some(current.0) {
            events.push(PadEvent::PositionX(current.0));
        }
        if previous.map(|value| value.1) != Some(current.1) {
            events.push(PadEvent::PositionY(current.1));
        }
        self.emulated = Some(current);
    }

    fn slot_of(&self, pointer_id: u8) -> Option<usize> {
        self.slots
            .iter()
            .position(|slot| slot.is_some_and(|slot| slot.pointer_id == pointer_id))
    }

    fn select_slot(&mut self, events: &mut Vec<PadEvent>, index: i32) {
        if self.selected_slot != Some(index) {
            events.push(PadEvent::Slot(index));
            self.selected_slot = Some(index);
        }
    }

    /// Reserves a kernel tracking ID and an age for a contact that is going down.
    ///
    /// Only `-1` means "released", so every other value is a valid ID. The
    /// tracking ID wraps astronomically far beyond the slot count, while `age`
    /// stays strictly increasing so contact ordering never inverts.
    fn take_contact_id(&mut self) -> (i32, u64) {
        let age = self.next_contact;
        self.next_contact += 1;
        ((age % i32::MAX as u64) as i32, age)
    }

    fn scale(&self, x: u32, y: u32, major: u16) -> Scaled {
        Scaled {
            x: scale_axis(x, self.surface.width, self.geometry.max_x),
            y: scale_axis(y, self.surface.height, self.geometry.max_y),
            // Contact size is a width, so it scales with the horizontal axis.
            major: scale_axis(u32::from(major), self.surface.width, self.geometry.max_x),
        }
    }
}

fn push_axes(events: &mut Vec<PadEvent>, previous: Option<Slot>, slot: &Slot) {
    if previous.map(|value| value.x) != Some(slot.x) {
        events.push(PadEvent::MtPositionX(slot.x));
    }
    if previous.map(|value| value.y) != Some(slot.y) {
        events.push(PadEvent::MtPositionY(slot.y));
    }
    if previous.map(|value| value.major) != Some(slot.major) {
        events.push(PadEvent::MtTouchMajor(slot.major));
    }
}

/// Maps `value` from a `span`-pixel axis onto `0..=to_max`.
fn scale_axis(value: u32, span: u32, to_max: i32) -> i32 {
    let from_max = u64::from(span.saturating_sub(1)).max(1);
    let clamped = u64::from(value).min(from_max);
    ((clamped * to_max as u64) / from_max) as i32
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::Contact;

    const PAD: PadGeometry = PadGeometry {
        max_x: 156 * PAD_RESOLUTION,
        max_y: 69 * PAD_RESOLUTION,
    };

    fn surface() -> Hello {
        Hello {
            version: crate::protocol::Version::Four,
            width: 2400,
            height: 1080,
            max_contacts: 10,
            width_um: 156_000,
            height_um: 69_000,
            capabilities: crate::protocol::Capabilities::NONE,
        }
    }

    fn frame(sequence: u64, contacts: &[(u8, u32, u32)]) -> Frame {
        Frame {
            sequence,
            event_time_ns: sequence * 1_000_000,
            contacts: contacts
                .iter()
                .map(|&(id, x, y)| Contact {
                    id,
                    x,
                    y,
                    pressure: 600,
                    major: 12,
                })
                .collect(),
        }
    }

    fn started() -> ContactState {
        let mut state = ContactState::new();
        state.begin_session(&surface());
        state
    }

    fn tracking_ids(events: &[PadEvent]) -> Vec<i32> {
        events
            .iter()
            .filter_map(|event| match event {
                PadEvent::TrackingId(id) => Some(*id),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn a_single_contact_goes_down_moves_and_lifts() {
        let mut state = started();

        let down = state.apply(&frame(1, &[(0, 1200, 540)]));
        assert_eq!(down.first(), Some(&PadEvent::Slot(0)));
        assert_eq!(tracking_ids(&down).len(), 1);
        assert_ne!(tracking_ids(&down)[0], -1);
        assert!(down.contains(&PadEvent::Touch(true)));
        assert!(down.contains(&PadEvent::ToolFingers(1)));
        assert_eq!(down.last(), Some(&PadEvent::Sync));

        let moved = state.apply(&frame(2, &[(0, 1300, 540)]));
        // The contact is already down: no new tracking ID, no button churn.
        assert!(tracking_ids(&moved).is_empty());
        assert!(!moved.contains(&PadEvent::Touch(true)));
        assert!(moved
            .iter()
            .any(|event| matches!(event, PadEvent::MtPositionX(_))));
        // Y did not change, so it must not be re-sent.
        assert!(!moved
            .iter()
            .any(|event| matches!(event, PadEvent::MtPositionY(_))));

        let up = state.apply(&frame(3, &[]));
        assert_eq!(tracking_ids(&up), vec![-1]);
        assert!(up.contains(&PadEvent::Touch(false)));
        assert!(up.contains(&PadEvent::ToolFingers(0)));
        assert_eq!(state.active_contacts(), 0);
    }

    #[test]
    fn an_unchanged_frame_emits_nothing() {
        let mut state = started();
        state.apply(&frame(1, &[(0, 1200, 540)]));
        assert!(state.apply(&frame(2, &[(0, 1200, 540)])).is_empty());
    }

    #[test]
    fn two_three_and_four_contacts_report_their_own_tool_bits() {
        let mut state = started();
        for (sequence, count) in [(1u64, 1usize), (2, 2), (3, 3), (4, 4)] {
            let contacts: Vec<_> = (0..count)
                .map(|index| (index as u8, 200 + 300 * index as u32, 540))
                .collect();
            let events = state.apply(&frame(sequence, &contacts));
            assert!(
                events.contains(&PadEvent::ToolFingers(count as u8)),
                "expected {count} fingers reported, got {events:?}"
            );
            assert_eq!(state.active_contacts(), count);
        }
    }

    #[test]
    fn each_contact_keeps_its_own_slot() {
        let mut state = started();
        state.apply(&frame(1, &[(0, 300, 200), (1, 900, 800)]));

        // Only the first contact moves: the device is still pointed at slot 1,
        // so slot 0 has to be selected before its axes are written.
        let events = state.apply(&frame(2, &[(0, 600, 200), (1, 900, 800)]));
        assert_eq!(events.first(), Some(&PadEvent::Slot(0)));
        assert!(!events.contains(&PadEvent::Slot(1)));

        // ...and back the other way.
        let events = state.apply(&frame(3, &[(0, 600, 200), (1, 1500, 800)]));
        assert_eq!(events.first(), Some(&PadEvent::Slot(1)));
        assert!(!events.contains(&PadEvent::Slot(0)));
    }

    #[test]
    fn a_reused_pointer_id_gets_a_fresh_tracking_id() {
        let mut state = started();
        let first = state.apply(&frame(1, &[(0, 300, 200)]));
        state.apply(&frame(2, &[]));
        let second = state.apply(&frame(3, &[(0, 900, 400)]));

        let first_id = tracking_ids(&first)[0];
        let second_id = tracking_ids(&second)[0];
        assert_ne!(
            first_id, second_id,
            "the kernel must see a new contact, not a resumed one"
        );
    }

    #[test]
    fn releasing_one_of_two_contacts_leaves_the_other_down() {
        let mut state = started();
        state.apply(&frame(1, &[(0, 300, 200), (1, 900, 800)]));
        let events = state.apply(&frame(2, &[(1, 900, 800)]));

        assert_eq!(tracking_ids(&events), vec![-1]);
        assert!(events.contains(&PadEvent::ToolFingers(1)));
        assert!(!events.contains(&PadEvent::Touch(false)));
        assert_eq!(state.active_contacts(), 1);
    }

    #[test]
    fn release_all_clears_every_slot_and_is_idempotent() {
        let mut state = started();
        state.apply(&frame(1, &[(0, 300, 200), (1, 900, 800), (2, 1500, 400)]));

        let events = state.release_all();
        assert_eq!(tracking_ids(&events), vec![-1, -1, -1]);
        assert!(events.contains(&PadEvent::Touch(false)));
        assert!(events.contains(&PadEvent::ToolFingers(0)));
        assert_eq!(state.active_contacts(), 0);

        assert!(
            state.release_all().is_empty(),
            "a second release must be a no-op, not a duplicate report"
        );
    }

    #[test]
    fn a_new_session_releases_contacts_left_by_the_previous_one() {
        let mut state = started();
        state.apply(&frame(1, &[(0, 300, 200)]));

        let events = state.begin_session(&surface());
        assert_eq!(tracking_ids(&events), vec![-1]);
        assert_eq!(state.active_contacts(), 0);
    }

    #[test]
    fn coordinates_map_onto_the_full_pad_surface() {
        let mut state = started();
        let events = state.apply(&frame(1, &[(0, 0, 0)]));
        assert!(events.contains(&PadEvent::MtPositionX(0)));
        assert!(events.contains(&PadEvent::MtPositionY(0)));

        let mut state = started();
        let events = state.apply(&frame(1, &[(0, 2399, 1079)]));
        assert!(events.contains(&PadEvent::MtPositionX(PAD.max_x)));
        assert!(events.contains(&PadEvent::MtPositionY(PAD.max_y)));
    }

    #[test]
    fn a_one_pixel_surface_does_not_divide_by_zero() {
        let mut state = ContactState::new();
        state.begin_session(&Hello {
            version: crate::protocol::Version::Four,
            width: 1,
            height: 1,
            max_contacts: 1,
            width_um: 156_000,
            height_um: 69_000,
            capabilities: crate::protocol::Capabilities::NONE,
        });
        let events = state.apply(&frame(1, &[(0, 0, 0)]));
        assert!(events.contains(&PadEvent::MtPositionX(0)));
    }

    #[test]
    fn contacts_beyond_the_slot_count_are_dropped_not_mixed_up() {
        let mut state = started();
        let contacts: Vec<_> = (0..(MAX_SLOTS as u8 + 2))
            .map(|index| (index, 100 + 100 * u32::from(index), 500))
            .collect();
        state.apply(&frame(1, &contacts));

        assert_eq!(state.active_contacts(), MAX_SLOTS);
        assert_eq!(state.dropped_contacts(), 2);
    }

    #[test]
    fn pressure_never_reaches_the_device() {
        // The virtual touchpad deliberately has no pressure axis; see the note
        // in `uinput.rs`. A pressure change on its own must therefore produce
        // nothing at all, not an empty report.
        let mut state = started();
        let contact = |pressure| Frame {
            sequence: 1,
            event_time_ns: 1,
            contacts: vec![Contact {
                id: 0,
                x: 1200,
                y: 540,
                pressure,
                major: 12,
            }],
        };
        state.apply(&contact(600));

        let mut changed = contact(1024);
        changed.sequence = 2;
        assert!(state.apply(&changed).is_empty());
    }

    #[test]
    fn pointer_emulation_follows_the_oldest_contact_across_slot_reuse() {
        let mut state = started();
        // Contact 0 takes slot 0, contact 1 takes slot 1.
        state.apply(&frame(1, &[(0, 300, 200), (1, 900, 800)]));
        // Contact 0 lifts, freeing slot 0; contact 2 arrives and reuses it.
        state.apply(&frame(2, &[(1, 900, 800)]));
        let events = state.apply(&frame(3, &[(1, 900, 800), (2, 1500, 300)]));

        // Slot 0 now holds the *newest* contact, so emulation must keep
        // following contact 1 in slot 1 rather than jumping to slot 0.
        assert!(
            !events
                .iter()
                .any(|event| matches!(event, PadEvent::PositionX(_) | PadEvent::PositionY(_))),
            "the emulated pointer moved to a newer contact: {events:?}"
        );
    }

    #[test]
    fn slot_selection_is_not_repeated_for_the_same_slot() {
        let mut state = started();
        state.apply(&frame(1, &[(0, 300, 200)]));
        let events = state.apply(&frame(2, &[(0, 400, 200)]));
        assert!(
            !events.contains(&PadEvent::Slot(0)),
            "slot 0 is already selected on the device"
        );
    }
}
