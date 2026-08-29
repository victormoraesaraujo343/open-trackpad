//! Synthetic contact sequences, so the `uinput` path can be exercised and
//! validated with `libinput debug-events` before the Android client exists.

use std::io;
use std::thread::sleep;
use std::time::{Duration, Instant};

use crate::pad::PadEvent;
use crate::protocol::{Contact, Frame, Hello};
use crate::sink::PadSink;
use crate::state::ContactState;

/// The touch surface the synthetic client pretends to have: a landscape
/// 2400x1080 phone.
const SURFACE: Hello = Hello {
    width: 2400,
    height: 1080,
    max_contacts: 10,
    // A 6.7-inch phone in landscape, so the self-test exercises a realistic
    // pad size rather than the fallback.
    width_um: 156_000,
    height_um: 69_000,
    // The self-test drives the touchpad and nothing else, so it asks to be
    // told about nothing.
    capabilities: crate::protocol::Capabilities::NONE,
};

/// Time the desktop gets to notice the new device before contacts start.
///
/// libinput opens input devices on udev hotplug, and a compositor that has not
/// finished doing that simply drops the events. Without this pause the
/// self-test silently proves nothing.
const HOTPLUG_GRACE: Duration = Duration::from_secs(2);

/// Gap between synthetic frames. Roughly 100 Hz, close enough to a real touch
/// stream that libinput's timing-based logic (tap detection, gesture onset)
/// behaves the way it will in production.
const FRAME_INTERVAL: Duration = Duration::from_millis(10);

/// Horizontal gap between synthetic fingers, in phone pixels.
const FINGER_SPACING: u32 = 260;

struct Step {
    name: &'static str,
    /// Contacts as `(pointer_id, x, y)`.
    contacts: Vec<(u8, u32, u32)>,
}

/// Turns a synthetic `(id, x, y)` triple into a protocol contact.
///
/// Pressure and contact size are filled in with plausible values even though
/// the virtual device declares no such axes, so the parser sees realistic input.
fn contact(&(id, x, y): &(u8, u32, u32)) -> Contact {
    Contact {
        id,
        x,
        y,
        pressure: 600,
        major: 12,
    }
}

fn step(name: &'static str, contacts: &[(u8, u32, u32)]) -> Step {
    Step {
        name,
        contacts: contacts.to_vec(),
    }
}

/// Reads this process's resident memory in kilobytes, for leak checking.
///
/// Returns `None` where /proc is unavailable rather than guessing.
fn resident_kb() -> Option<u64> {
    let status = std::fs::read_to_string("/proc/self/status").ok()?;
    status
        .lines()
        .find_map(|line| line.strip_prefix("VmRSS:"))?
        .split_whitespace()
        .next()?
        .parse()
        .ok()
}

/// Replays the sequence on a loop for `minutes`, to answer the acceptance
/// criterion that half an hour of use causes no stuck touch, crash or leak.
///
/// This moves the pointer continuously for the whole duration, so it is meant to
/// be run on an idle machine.
pub fn soak(state: &mut ContactState, sink: &mut dyn PadSink, minutes: u64) -> io::Result<()> {
    let deadline = Instant::now() + Duration::from_secs(minutes * 60);
    let started_kb = resident_kb();
    let mut cycles = 0u64;
    let mut frames = 0u64;
    let mut reported = Instant::now();

    sink.configure(SURFACE.geometry())?;
    println!("soak: running for {minutes} minute(s); the pointer will move throughout");
    sink.emit(&state.begin_session(&SURFACE))?;

    while Instant::now() < deadline {
        for (index, step) in sequence().into_iter().enumerate() {
            let frame = Frame {
                sequence: frames + index as u64 + 1,
                event_time_ns: (frames + index as u64 + 1) * 10_000_000,
                contacts: step.contacts.iter().map(contact).collect(),
            };
            sink.emit(&state.apply(&frame))?;
            sleep(FRAME_INTERVAL);
        }
        frames += sequence().len() as u64;
        cycles += 1;

        // Every cycle ends with contacts deliberately left down, so this also
        // exercises the release path a dropped connection would take.
        sink.emit(&state.release_all())?;
        if state.active_contacts() != 0 {
            return Err(io::Error::other(format!(
                "soak failed: {} contact(s) stuck after release",
                state.active_contacts()
            )));
        }

        if reported.elapsed() >= Duration::from_secs(60) {
            reported = Instant::now();
            let remaining = deadline.saturating_duration_since(Instant::now());
            println!(
                "  {cycles} cycles, {frames} frames, {} minute(s) left",
                remaining.as_secs() / 60
            );
        }
    }

    println!("soak: finished after {cycles} cycles and {frames} frames");
    println!("  contacts still down: {}", state.active_contacts());
    match (started_kb, resident_kb()) {
        (Some(before), Some(after)) => println!(
            "  resident memory: {before} kB -> {after} kB ({:+} kB)",
            after as i64 - before as i64
        ),
        _ => println!("  resident memory: unavailable"),
    }
    Ok(())
}

/// Replays the synthetic sequence through `state` into `sink`.
/// Moves `count` contacts smoothly from `from` to `to`, spaced apart
/// horizontally so they read as separate fingers.
fn glide(
    name: &'static str,
    count: u8,
    from: (u32, u32),
    to: (u32, u32),
    frames: u32,
) -> Vec<Step> {
    (0..=frames)
        .map(|tick| {
            let interpolate = |start: u32, end: u32| {
                let start = i64::from(start);
                let span = i64::from(end) - start;
                (start + span * i64::from(tick) / i64::from(frames)) as u32
            };
            let x = interpolate(from.0, to.0);
            let y = interpolate(from.1, to.1);
            let contacts: Vec<_> = (0..count)
                .map(|index| (index, x + FINGER_SPACING * u32::from(index), y))
                .collect();
            step(name, &contacts)
        })
        .collect()
}

fn sequence() -> Vec<Step> {
    let mut steps = Vec::new();

    // A long, smooth stroke. libinput builds up a short motion history before
    // it emits any pointer motion at all, and a touch lasting a few frames
    // reads as a tap rather than a drag — so a short sequence here proves
    // nothing even when the device is perfect.
    steps.extend(glide(
        "one contact drags right",
        1,
        (300, 540),
        (2100, 540),
        45,
    ));
    steps.extend(glide(
        "one contact drags up",
        1,
        (2100, 540),
        (2100, 150),
        25,
    ));
    steps.push(step("one contact up", &[]));

    steps.extend(glide("two contacts scroll", 2, (900, 250), (900, 850), 35));
    steps.push(step("two contacts up", &[]));

    steps.extend(glide(
        "three contacts swipe",
        3,
        (500, 540),
        (1500, 540),
        30,
    ));
    steps.push(step("three contacts up", &[]));

    steps.extend(glide("four contacts swipe", 4, (400, 250), (400, 800), 30));
    steps.push(step("four contacts up", &[]));

    // A pointer ID released and immediately reused: the kernel must see two
    // separate contacts, not one that teleported.
    steps.push(step("id 0 down", &[(0, 400, 300)]));
    steps.push(step("id 0 up", &[]));
    steps.push(step("id 0 reused", &[(0, 2000, 800)]));
    steps.push(step("id 0 up again", &[]));

    // Extremes of the surface, to confirm the mapping reaches every edge.
    steps.push(step("top-left corner", &[(0, 0, 0)]));
    steps.push(step("bottom-right corner", &[(0, 2399, 1079)]));
    steps.push(step("corner released", &[]));

    // Ends with contacts still down, so the caller's release-all path is what
    // cleans up — exactly what happens when a phone is unplugged mid-gesture.
    steps.push(step(
        "left down for the disconnect test",
        &[(0, 800, 500), (1, 1600, 500)],
    ));
    steps
}

pub fn run(state: &mut ContactState, sink: &mut dyn PadSink) -> io::Result<()> {
    sink.configure(SURFACE.geometry())?;
    println!(
        "self-test: waiting {}s for the desktop to open the new device",
        HOTPLUG_GRACE.as_secs()
    );
    sleep(HOTPLUG_GRACE);
    println!("self-test: replaying synthetic contacts through the state machine");
    sink.emit(&state.begin_session(&SURFACE))?;

    let mut last_name = "";
    for (index, step) in sequence().into_iter().enumerate() {
        if step.name != last_name {
            println!("  {}", step.name);
            last_name = step.name;
        }
        let frame = Frame {
            sequence: index as u64 + 1,
            event_time_ns: (index as u64 + 1) * 10_000_000,
            contacts: step.contacts.iter().map(contact).collect(),
        };
        sink.emit(&state.apply(&frame))?;
        sleep(FRAME_INTERVAL);
    }

    println!(
        "  releasing {} contact(s) left down, as a disconnect would",
        state.active_contacts()
    );
    let release: Vec<PadEvent> = state.release_all();
    sink.emit(&release)?;
    println!(
        "self-test: done; {} contacts remain",
        state.active_contacts()
    );
    Ok(())
}
