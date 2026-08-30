//! What the audio panel shows: outputs, inputs and the streams playing through
//! them, and how one `pactl` document turns into that.
//!
//! Free of I/O on purpose. Everything here takes text or values and returns
//! values, so the whole surface is unit-testable without an audio daemon —
//! including the shapes that only appear on somebody else's machine.
//!
//! `crate::pactl` runs the commands; this reads what they say.

use crate::json::{self, Value};

/// The wire level that means 100%.
///
/// Per-mille rather than percent: a fader that travels the height of a phone
/// screen has room for far more than a hundred steps, and integers keep the
/// protocol readable and exact.
pub const FULL_VOLUME: u16 = 1000;

/// The loudest level the protocol accepts: 150%.
///
/// Above 100% the daemon amplifies rather than attenuates, and past some point
/// that distorts. It is offered anyway, because the panel makes the range
/// visible instead of hiding it: the fader is drawn against a scale running to
/// 150 with a tick at 100, and above the tick the bar, the knob and the number
/// all turn amber — the colour this app already uses for degraded-but-working.
/// So it can be seen at a glance, without reading, which is what a peripheral
/// operated without looking needs. It is also behind a setting that is off by
/// default.
///
/// Deliberately not the same number as `FULL_VOLUME`: one is where the scale
/// reads 100%, the other is where it stops. Anything above this is refused
/// outright rather than clamped, for the reason in `docs/PROTOCOL.md` — a
/// client that does not know the scale is a client whose next message cannot be
/// trusted either.
pub const MAX_VOLUME: u16 = 1500;

/// What PulseAudio and PipeWire both call 100%.
///
/// `pactl` reports raw volumes against this reference, and it is what
/// `value_percent` in its own output divides by, so a fader set to half here
/// reads as 50% in `pavucontrol` too.
const RAW_FULL: u32 = 65536;

/// The longest name carried to the client, in characters.
///
/// Names are free text from hardware and from window titles, and a phone shows
/// perhaps thirty characters of one. A ceiling keeps a single line bounded no
/// matter what a media player decides to call itself.
pub const MAX_NAME_CHARS: usize = 128;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Kind {
    /// A sink: somewhere sound comes out.
    Output,
    /// A source: somewhere sound goes in. Monitors are not included; see
    /// `read_sources`.
    Input,
    /// One application's playback, on the apps page.
    Stream,
}

impl Kind {
    pub fn as_str(self) -> &'static str {
        match self {
            Kind::Output => "output",
            Kind::Input => "input",
            Kind::Stream => "stream",
        }
    }

    pub fn parse(text: &str) -> Option<Self> {
        match text {
            "output" => Some(Kind::Output),
            "input" => Some(Kind::Input),
            "stream" => Some(Kind::Stream),
            _ => None,
        }
    }

    /// Whether "make this the default" means anything for this kind.
    ///
    /// A stream plays through whichever device it is attached to; there is no
    /// default stream to be.
    pub fn has_default(self) -> bool {
        matches!(self, Kind::Output | Kind::Input)
    }
}

/// How a device is attached, when the daemon says so plainly.
///
/// Two devices both called "Headset" is not a rare case, and it is exactly
/// where the name stops being enough. This is read from `device.bus` and
/// `active_port` — both stable identifiers — and never parsed out of the
/// description, which is free text in whatever language the machine speaks.
///
/// Transport before connector: a USB sound card with an analog output is
/// usefully "usb", because that is the thing that tells it apart from the one
/// built into the machine. Analog and HDMI have no transport worth naming, so
/// they answer with the connector.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Port {
    Bluetooth,
    Hdmi,
    Usb,
    /// S/PDIF or optical.
    Digital,
    Analog,
}

impl Port {
    pub fn as_str(self) -> &'static str {
        match self {
            Port::Bluetooth => "bluetooth",
            Port::Hdmi => "hdmi",
            Port::Usb => "usb",
            Port::Digital => "digital",
            Port::Analog => "analog",
        }
    }

    /// Works it out, or says nothing rather than guessing.
    fn read(bus: Option<&str>, active_port: Option<&str>) -> Option<Self> {
        let bus = bus.unwrap_or_default().to_ascii_lowercase();
        let port = active_port.unwrap_or_default().to_ascii_lowercase();

        if bus == "bluetooth" {
            return Some(Port::Bluetooth);
        }
        if port.contains("hdmi") || port.contains("displayport") {
            return Some(Port::Hdmi);
        }
        if bus == "usb" {
            return Some(Port::Usb);
        }
        if port.contains("iec958") || port.contains("spdif") {
            return Some(Port::Digital);
        }
        if port.contains("analog") {
            return Some(Port::Analog);
        }
        None
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entity {
    pub kind: Kind,
    /// The daemon's own index. The only identifier that crosses the wire, and
    /// the only thing a client request may name.
    pub id: u32,
    /// Nought to `MAX_VOLUME`.
    pub volume: u16,
    pub muted: bool,
    /// Devices only: whether this is the one new sound goes to by default.
    pub default: bool,
    /// Streams only: the output this plays through.
    pub target: Option<u32>,
    /// Devices only: how it is attached, when the daemon says so plainly.
    pub port: Option<Port>,
    /// Streams only: whether the application has paused it.
    ///
    /// Not quite "is it making sound" — see the note on `read_streams`.
    pub paused: bool,
    pub name: String,
}

impl Entity {
    fn key(&self) -> (Kind, u32) {
        (self.kind, self.id)
    }
}

/// Everything the panel shows, at one moment.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Snapshot {
    /// Sorted by kind and then id, so two snapshots can be compared in one
    /// pass and the client is shown a stable order rather than whatever the
    /// daemon happened to list.
    pub entities: Vec<Entity>,
}

impl Snapshot {
    pub fn new(mut entities: Vec<Entity>) -> Self {
        entities.sort_by_key(Entity::key);
        Self { entities }
    }

    /// Looks one entity up.
    ///
    /// Both halves are needed: outputs, inputs and streams are numbered
    /// independently by the sound daemon, so the same number is three different
    /// things and an id on its own would find whichever came first.
    pub fn find(&self, kind: Kind, id: u32) -> Option<&Entity> {
        self.entities
            .iter()
            .find(|entity| entity.kind == kind && entity.id == id)
    }
}

/// One difference between two snapshots.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Change {
    /// Appeared, or is no longer what it was.
    Upserted(Entity),
    Removed(Kind, u32),
}

/// What changed between two snapshots, in a stable order.
///
/// Both sides are sorted, so this is one merge rather than a search per entry.
pub fn diff(previous: &Snapshot, next: &Snapshot) -> Vec<Change> {
    let mut changes = Vec::new();
    let mut old = previous.entities.iter().peekable();
    let mut new = next.entities.iter().peekable();

    loop {
        match (old.peek(), new.peek()) {
            (None, None) => break,
            (Some(gone), None) => {
                changes.push(Change::Removed(gone.kind, gone.id));
                old.next();
            }
            (None, Some(fresh)) => {
                changes.push(Change::Upserted((*fresh).clone()));
                new.next();
            }
            (Some(left), Some(right)) => match left.key().cmp(&right.key()) {
                std::cmp::Ordering::Less => {
                    changes.push(Change::Removed(left.kind, left.id));
                    old.next();
                }
                std::cmp::Ordering::Greater => {
                    changes.push(Change::Upserted((*right).clone()));
                    new.next();
                }
                std::cmp::Ordering::Equal => {
                    if left != right {
                        changes.push(Change::Upserted((*right).clone()));
                    }
                    old.next();
                    new.next();
                }
            },
        }
    }
    changes
}

/// Turns a raw daemon volume into the nought-to-`MAX_VOLUME` scale.
///
/// Scaled by `FULL_VOLUME` and stopped at `MAX_VOLUME`, which are different
/// numbers: a device someone left at 130% reads back as 1300 rather than being
/// flattened to 100%. Reporting a level quieter than the machine is actually
/// playing would be its own kind of lie, and the phone can draw it — its scale
/// runs to 150 too.
///
/// Something set past 150% by another tool is reported as 150. Beyond that the
/// phone has no way to draw it, and the number it could not show would be worse
/// than the edge of the scale it can.
pub fn volume_to_wire(raw: u32) -> u16 {
    let scaled =
        (u64::from(raw) * u64::from(FULL_VOLUME) + u64::from(RAW_FULL) / 2) / u64::from(RAW_FULL);
    scaled.min(u64::from(MAX_VOLUME)) as u16
}

/// The raw daemon volume for a wire level.
///
/// The inverse of `volume_to_wire`, on the same reference, so a level set from
/// the phone reads back as the level the phone sent rather than one rounded
/// through a percentage on the way.
pub fn volume_to_raw(wire: u16) -> u32 {
    let wire = u32::from(wire.min(MAX_VOLUME));
    (wire * RAW_FULL) / u32::from(FULL_VOLUME)
}

/// The loudest channel of a `pactl` volume object.
///
/// Channels are set together by everything this host does, so they agree in
/// practice; taking the loudest means a device left unbalanced by another tool
/// still reads as loud as it sounds, rather than showing the quiet side.
fn loudest_channel(volume: Option<&Value>) -> u32 {
    let Some(Value::Object(channels)) = volume else {
        return 0;
    };
    channels
        .iter()
        .filter_map(|(_, channel)| channel.get("value").and_then(Value::as_u32))
        .max()
        .unwrap_or(0)
}

/// Shortens free text to something a phone can show and a line can carry.
fn clamp_name(name: &str) -> String {
    if name.chars().count() <= MAX_NAME_CHARS {
        return name.to_owned();
    }
    name.chars().take(MAX_NAME_CHARS).collect()
}

fn property<'a>(entry: &'a Value, key: &str) -> Option<&'a str> {
    entry
        .get("properties")
        .and_then(|properties| properties.get(key))
        .and_then(Value::as_str)
        .filter(|text| !text.is_empty())
}

/// Reads `pactl --format=json list sinks`.
///
/// `default_name` is what `pactl get-default-sink` said, which is a name rather
/// than an index — the only place names are compared, and they never leave the
/// host.
pub fn read_sinks(document: &str, default_name: &str) -> Result<Vec<Entity>, json::JsonError> {
    read_devices(document, default_name, Kind::Output, &[])
}

/// Reads `pactl --format=json list sources`, leaving out monitors.
///
/// Every output has a monitor source that plays back what it is producing.
/// They are not microphones, nobody sets their level from a phone, and showing
/// them would double the length of the input page with entries that mean
/// nothing to the person reading it. `monitors` is the set of names the sinks
/// gave for their own monitors, so this is an exact match rather than a guess
/// at a name ending.
pub fn read_sources(
    document: &str,
    default_name: &str,
    monitors: &[String],
) -> Result<Vec<Entity>, json::JsonError> {
    read_devices(document, default_name, Kind::Input, monitors)
}

fn read_devices(
    document: &str,
    default_name: &str,
    kind: Kind,
    excluded_names: &[String],
) -> Result<Vec<Entity>, json::JsonError> {
    let parsed = json::parse(document)?;
    let entries = parsed
        .as_array()
        .ok_or_else(|| json::JsonError("expected a list of devices".into()))?;

    let mut entities = Vec::with_capacity(entries.len());
    for entry in entries {
        // An entry missing its index is not a device this host can act on, so
        // it is skipped rather than failing the whole list: one odd entry must
        // not cost the panel.
        let Some(id) = entry.get("index").and_then(Value::as_u32) else {
            continue;
        };
        let name = entry
            .get("name")
            .and_then(Value::as_str)
            .unwrap_or_default();
        if excluded_names.iter().any(|excluded| excluded == name) {
            continue;
        }
        let shown = entry
            .get("description")
            .and_then(Value::as_str)
            .filter(|text| !text.is_empty())
            .unwrap_or(name);
        entities.push(Entity {
            kind,
            id,
            volume: volume_to_wire(loudest_channel(entry.get("volume"))),
            muted: entry.get("mute").and_then(Value::as_bool).unwrap_or(false),
            default: !default_name.is_empty() && name == default_name,
            target: None,
            port: Port::read(
                property(entry, "device.bus"),
                entry.get("active_port").and_then(Value::as_str),
            ),
            paused: false,
            name: clamp_name(shown),
        });
    }
    Ok(entities)
}

/// The monitor source name each sink declares, for filtering the source list.
pub fn monitor_names(sinks_document: &str) -> Result<Vec<String>, json::JsonError> {
    let parsed = json::parse(sinks_document)?;
    let entries = parsed
        .as_array()
        .ok_or_else(|| json::JsonError("expected a list of devices".into()))?;
    Ok(entries
        .iter()
        .filter_map(|entry| entry.get("monitor_source").and_then(Value::as_str))
        .map(str::to_owned)
        .collect())
}

/// Reads `pactl --format=json list sink-inputs`: what each application is
/// playing, and how loudly.
///
/// # `paused` is not quite "is it making sound"
///
/// It is the daemon's `corked` flag, which means the application has stopped
/// the stream — what a media player does when you press pause. That is a
/// one-directional truth: paused certainly means silent, but not paused only
/// means the stream is open, and an application can hold one open while writing
/// silence for hours.
///
/// It is sent as what it is rather than as what a screen wants, because there
/// is no second signal to combine it with: a sink input reports nothing else
/// bearing on this. The client can filter on it, which catches the case that
/// actually clutters the list — a paused media player sitting in it.
pub fn read_streams(document: &str) -> Result<Vec<Entity>, json::JsonError> {
    let parsed = json::parse(document)?;
    let entries = parsed
        .as_array()
        .ok_or_else(|| json::JsonError("expected a list of streams".into()))?;

    let mut entities = Vec::with_capacity(entries.len());
    for entry in entries {
        let Some(id) = entry.get("index").and_then(Value::as_u32) else {
            continue;
        };
        // The application's own name first, then the program it runs as. Both
        // are absent often enough to be worth the fallback, and an unnamed row
        // in a mixer is useless.
        let shown = property(entry, "application.name")
            .or_else(|| property(entry, "application.process.binary"))
            .or_else(|| property(entry, "media.name"))
            .unwrap_or("Unknown");
        entities.push(Entity {
            kind: Kind::Stream,
            id,
            volume: volume_to_wire(loudest_channel(entry.get("volume"))),
            muted: entry.get("mute").and_then(Value::as_bool).unwrap_or(false),
            default: false,
            target: entry.get("sink").and_then(Value::as_u32),
            port: None,
            paused: entry
                .get("corked")
                .and_then(Value::as_bool)
                .unwrap_or(false),
            name: clamp_name(shown),
        });
    }
    Ok(entities)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SINKS: &str = r#"[
      {"index":53,"name":"alsa_output.hdmi","description":"HDMI Digital Stereo",
       "mute":false,"volume":{"front-left":{"value":62259},"front-right":{"value":62259}},
       "monitor_source":"alsa_output.hdmi.monitor"},
      {"index":58,"name":"alsa_output.iec958","description":"Built-in Audio",
       "mute":true,"volume":{"front-left":{"value":39321},"front-right":{"value":39321}},
       "monitor_source":"alsa_output.iec958.monitor"}
    ]"#;

    const SOURCES: &str = r#"[
      {"index":53,"name":"alsa_output.hdmi.monitor","description":"Monitor of HDMI",
       "mute":false,"volume":{"front-left":{"value":65536}}},
      {"index":57,"name":"alsa_input.usb","description":"USB-MIC Analog Stereo",
       "mute":false,"volume":{"front-left":{"value":65536}}}
    ]"#;

    const STREAMS: &str = r#"[
      {"index":1348,"sink":53,"mute":false,"volume":{"front-left":{"value":64867}},
       "properties":{"application.name":"Firefox","media.name":"AudioStream",
                     "application.process.binary":"firefox"}}
    ]"#;

    #[test]
    fn reads_outputs_and_marks_the_default_one() {
        let sinks = read_sinks(SINKS, "alsa_output.hdmi").unwrap();
        assert_eq!(sinks.len(), 2);
        assert_eq!(sinks[0].id, 53);
        assert_eq!(sinks[0].name, "HDMI Digital Stereo");
        assert_eq!(sinks[0].volume, 950);
        assert!(sinks[0].default);
        assert!(!sinks[0].muted);
        assert!(sinks[1].muted);
        assert!(!sinks[1].default);
    }

    #[test]
    fn monitors_are_left_out_of_the_input_list() {
        // Every output has one, none of them is a microphone, and showing them
        // would double the page with rows that mean nothing.
        let monitors = monitor_names(SINKS).unwrap();
        assert_eq!(monitors.len(), 2);
        let sources = read_sources(SOURCES, "alsa_input.usb", &monitors).unwrap();
        assert_eq!(sources.len(), 1);
        assert_eq!(sources[0].name, "USB-MIC Analog Stereo");
        assert!(sources[0].default);
    }

    #[test]
    fn reads_streams_with_the_output_they_play_through() {
        let streams = read_streams(STREAMS).unwrap();
        assert_eq!(streams.len(), 1);
        assert_eq!(streams[0].id, 1348);
        assert_eq!(streams[0].name, "Firefox");
        assert_eq!(streams[0].target, Some(53));
        assert_eq!(streams[0].kind, Kind::Stream);
        assert_eq!(streams[0].volume, 990);
    }

    #[test]
    fn a_stream_without_an_application_name_falls_back_rather_than_showing_nothing() {
        let streams = read_streams(
            r#"[{"index":1,"sink":2,"properties":{"application.process.binary":"mpv"}}]"#,
        )
        .unwrap();
        assert_eq!(streams[0].name, "mpv");
        let streams = read_streams(r#"[{"index":1,"sink":2,"properties":{}}]"#).unwrap();
        assert_eq!(streams[0].name, "Unknown");
        let streams = read_streams(r#"[{"index":1}]"#).unwrap();
        assert_eq!(streams[0].name, "Unknown");
        assert_eq!(streams[0].target, None);
    }

    #[test]
    fn an_entry_without_an_index_is_skipped_rather_than_failing_the_list() {
        // One odd entry from a daemon this host has never seen must not cost
        // the whole panel.
        let sinks = read_sinks(r#"[{"name":"nameless"},{"index":3,"name":"real"}]"#, "").unwrap();
        assert_eq!(sinks.len(), 1);
        assert_eq!(sinks[0].id, 3);
    }

    #[test]
    fn a_document_that_is_not_a_list_is_an_error() {
        assert!(read_sinks("{}", "").is_err());
        assert!(read_streams("not json at all").is_err());
    }

    #[test]
    fn volumes_map_onto_the_wire_scale() {
        assert_eq!(volume_to_wire(0), 0);
        assert_eq!(volume_to_wire(32768), 500);
        assert_eq!(volume_to_wire(65536), FULL_VOLUME);
    }

    #[test]
    fn a_device_left_loud_by_something_else_is_reported_loud() {
        // Not flattened to 100%. Reporting a level quieter than the machine is
        // actually playing would be its own kind of lie, and the phone's scale
        // runs to 150 as well.
        assert_eq!(volume_to_wire(85197), 1300);
        assert_eq!(volume_to_wire(98304), MAX_VOLUME);
    }

    #[test]
    fn a_volume_past_the_end_of_the_scale_is_reported_as_the_end_of_it() {
        // Set by some other tool: beyond 150% the phone has no way to draw it,
        // and a number it cannot show is worse than the edge it can.
        assert_eq!(volume_to_wire(131072), MAX_VOLUME);
        assert_eq!(volume_to_wire(u32::MAX), MAX_VOLUME);
    }

    #[test]
    fn a_level_set_from_the_phone_reads_back_as_the_level_the_phone_sent() {
        // A fader that jumps a step when you let go of it is the classic
        // symptom of a scale that does not survive the round trip. The range
        // above 100% has to survive it too, now that it is offered.
        for level in [
            0, 1, 250, 333, 500, 750, 950, 999, 1000, 1001, 1234, 1499, 1500,
        ] {
            assert_eq!(
                volume_to_wire(volume_to_raw(level)),
                level,
                "level {level} did not survive the round trip"
            );
        }
    }

    #[test]
    fn the_two_ends_of_the_scale_are_different_numbers() {
        // One is where the scale reads 100%, the other is where it stops.
        // Conflating them is how the ceiling silently becomes the reference.
        assert_eq!(volume_to_raw(FULL_VOLUME), 65_536);
        assert_eq!(volume_to_raw(MAX_VOLUME), 98_304);
    }

    #[test]
    fn nothing_can_ask_for_more_than_the_ceiling() {
        assert_eq!(volume_to_raw(u16::MAX), volume_to_raw(MAX_VOLUME));
        assert_eq!(volume_to_raw(0), 0);
    }

    #[test]
    fn the_loudest_channel_is_the_one_shown() {
        let streams = read_streams(
            r#"[{"index":1,"volume":{"front-left":{"value":16384},"front-right":{"value":65536}}}]"#,
        )
        .unwrap();
        assert_eq!(streams[0].volume, 1000);
    }

    #[test]
    fn an_overlong_name_is_shortened_rather_than_sent_whole() {
        let long = "x".repeat(500);
        let document = format!(r#"[{{"index":1,"properties":{{"application.name":"{long}"}}}}]"#);
        let streams = read_streams(&document).unwrap();
        assert_eq!(streams[0].name.chars().count(), MAX_NAME_CHARS);
    }

    fn entity(kind: Kind, id: u32, volume: u16) -> Entity {
        Entity {
            kind,
            id,
            volume,
            muted: false,
            default: false,
            target: None,
            port: None,
            paused: false,
            name: format!("{}-{id}", kind.as_str()),
        }
    }

    #[test]
    fn an_unchanged_snapshot_produces_no_changes() {
        let snapshot = Snapshot::new(vec![entity(Kind::Output, 1, 500)]);
        assert!(diff(&snapshot, &snapshot).is_empty());
    }

    #[test]
    fn a_changed_level_is_reported_once() {
        let before = Snapshot::new(vec![entity(Kind::Output, 1, 500)]);
        let after = Snapshot::new(vec![entity(Kind::Output, 1, 700)]);
        assert_eq!(
            diff(&before, &after),
            vec![Change::Upserted(entity(Kind::Output, 1, 700))]
        );
    }

    #[test]
    fn a_device_appearing_and_one_disappearing_are_both_reported() {
        // Unplugging a headset mid-session is the ordinary case, not the
        // exceptional one.
        let before = Snapshot::new(vec![
            entity(Kind::Output, 1, 500),
            entity(Kind::Output, 2, 500),
        ]);
        let after = Snapshot::new(vec![
            entity(Kind::Output, 2, 500),
            entity(Kind::Output, 9, 300),
        ]);
        assert_eq!(
            diff(&before, &after),
            vec![
                Change::Removed(Kind::Output, 1),
                Change::Upserted(entity(Kind::Output, 9, 300)),
            ]
        );
    }

    #[test]
    fn kinds_with_the_same_id_do_not_collide() {
        // Sinks, sources and streams are numbered independently, so an output
        // and a stream can both be number three.
        let before = Snapshot::new(vec![entity(Kind::Output, 3, 100)]);
        let after = Snapshot::new(vec![
            entity(Kind::Output, 3, 100),
            entity(Kind::Stream, 3, 900),
        ]);
        assert_eq!(
            diff(&before, &after),
            vec![Change::Upserted(entity(Kind::Stream, 3, 900))]
        );
    }

    #[test]
    fn everything_disappearing_is_reported_rather_than_looking_unchanged() {
        // What losing the audio daemon looks like from here.
        let before = Snapshot::new(vec![
            entity(Kind::Output, 1, 500),
            entity(Kind::Input, 2, 500),
        ]);
        let after = Snapshot::default();
        assert_eq!(diff(&before, &after).len(), 2);
        assert!(diff(&before, &after)
            .iter()
            .all(|change| matches!(change, Change::Removed(..))));
    }

    #[test]
    fn snapshots_are_ordered_however_the_daemon_listed_them() {
        let snapshot = Snapshot::new(vec![
            entity(Kind::Stream, 5, 0),
            entity(Kind::Output, 9, 0),
            entity(Kind::Input, 1, 0),
            entity(Kind::Output, 2, 0),
        ]);
        let order: Vec<_> = snapshot
            .entities
            .iter()
            .map(|entity| (entity.kind, entity.id))
            .collect();
        assert_eq!(
            order,
            vec![
                (Kind::Output, 2),
                (Kind::Output, 9),
                (Kind::Input, 1),
                (Kind::Stream, 5),
            ]
        );
    }

    #[test]
    fn an_id_is_only_found_together_with_its_kind() {
        // Sinks and sources share a numbering on PulseAudio, so a lookup by id
        // alone would answer with whichever happened to be listed first.
        let snapshot = Snapshot::new(vec![
            entity(Kind::Output, 3, 100),
            entity(Kind::Input, 3, 200),
            entity(Kind::Stream, 7, 900),
        ]);
        assert_eq!(
            snapshot.find(Kind::Output, 3).map(|found| found.volume),
            Some(100)
        );
        assert_eq!(
            snapshot.find(Kind::Input, 3).map(|found| found.volume),
            Some(200)
        );
        assert!(snapshot.find(Kind::Stream, 3).is_none());
        assert!(snapshot.find(Kind::Output, 99).is_none());
    }

    #[test]
    fn only_devices_can_be_made_the_default() {
        assert!(Kind::Output.has_default());
        assert!(Kind::Input.has_default());
        assert!(!Kind::Stream.has_default());
    }

    #[test]
    fn kind_names_survive_a_round_trip() {
        for kind in [Kind::Output, Kind::Input, Kind::Stream] {
            assert_eq!(Kind::parse(kind.as_str()), Some(kind));
        }
        assert_eq!(Kind::parse("sink"), None);
        assert_eq!(Kind::parse(""), None);
    }

    #[test]
    fn how_a_device_is_attached_comes_from_the_daemon_and_not_from_its_name() {
        // Never parsed out of the description. That is free text in whatever
        // language the machine speaks, and it is the one approach that would be
        // wrong on somebody else's computer while looking right here.
        assert_eq!(
            Port::read(Some("bluetooth"), Some("headset-output")),
            Some(Port::Bluetooth)
        );
        assert_eq!(
            Port::read(Some("pci"), Some("hdmi-output-1")),
            Some(Port::Hdmi)
        );
        assert_eq!(
            Port::read(Some("pci"), Some("iec958-stereo-output")),
            Some(Port::Digital)
        );
        assert_eq!(
            Port::read(Some("pci"), Some("analog-output")),
            Some(Port::Analog)
        );
        // Transport before connector: a USB sound card with an analog output is
        // usefully "usb", because that is what tells it apart from the one
        // built into the machine.
        assert_eq!(
            Port::read(Some("usb"), Some("analog-output")),
            Some(Port::Usb)
        );
    }

    #[test]
    fn a_device_that_says_nothing_useful_gets_no_token_rather_than_a_guess() {
        assert_eq!(Port::read(None, None), None);
        assert_eq!(Port::read(Some(""), Some("")), None);
        assert_eq!(Port::read(Some("pci"), Some("some-future-port")), None);
    }

    #[test]
    fn a_stream_says_whether_the_application_paused_it() {
        // The daemon's `corked`. Paused certainly means silent; not paused only
        // means the stream is open.
        let streams = read_streams(
            r#"[{"index":1,"sink":2,"corked":true,"properties":{"application.name":"mpv"}}]"#,
        )
        .unwrap();
        assert!(streams[0].paused);

        let streams = read_streams(
            r#"[{"index":1,"sink":2,"corked":false,"properties":{"application.name":"mpv"}}]"#,
        )
        .unwrap();
        assert!(!streams[0].paused);

        // A daemon that does not say is taken as playing: a stream wrongly
        // hidden is worse than one wrongly shown, because nobody goes looking
        // for what they cannot see.
        let streams = read_streams(r#"[{"index":1,"sink":2}]"#).unwrap();
        assert!(!streams[0].paused);
    }

    #[test]
    fn devices_carry_a_port_and_streams_do_not() {
        let sinks = read_sinks(
            r#"[{"index":1,"name":"a","description":"A","active_port":"hdmi-output-1",
                 "properties":{"device.bus":"pci"}}]"#,
            "",
        )
        .unwrap();
        assert_eq!(sinks[0].port, Some(Port::Hdmi));
        assert!(!sinks[0].paused);

        let streams = read_streams(r#"[{"index":1,"sink":2}]"#).unwrap();
        assert_eq!(streams[0].port, None);
    }
}
