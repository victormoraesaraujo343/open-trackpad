//! Measures how touch frames are spaced out.
//!
//! libinput derives pointer acceleration from velocity, which is distance over
//! time. Frames that describe different moments but arrive in the same instant
//! therefore break acceleration, and the pointer feels linear and jittery
//! without anything looking wrong in the event stream itself.
//!
//! This compares two intervals per frame: how far apart the phone says they
//! were, and how far apart they actually arrived. Healthy input has the two
//! roughly equal.

use std::time::Instant;

pub struct TimingTrace {
    previous: Option<(u64, Instant)>,
    frames: u64,
    bunched: u64,
}

/// Arrivals closer together than this are treated as bunched, whatever the
/// phone claims about when they happened. A touchpad reporting at 125 Hz has
/// 8 ms between samples; anything under a millisecond is a burst.
const BUNCHED_MS: f64 = 1.0;

impl TimingTrace {
    pub fn new() -> Self {
        Self {
            previous: None,
            frames: 0,
            bunched: 0,
        }
    }

    /// Records one frame and returns a line to log, if there is one.
    ///
    /// `separation_mm` is how far apart two contacts are, when there are exactly
    /// two. Pinch zoom is a ratio of that distance, so it is the figure that
    /// says whether a weak zoom is the gesture running out of room or the
    /// application converting it timidly.
    pub fn observe(
        &mut self,
        event_time_ns: u64,
        contacts: usize,
        separation_mm: Option<f64>,
    ) -> Option<String> {
        let now = Instant::now();
        let line = self.previous.map(|(previous_ns, previous_at)| {
            let phone_ms = event_time_ns.saturating_sub(previous_ns) as f64 / 1_000_000.0;
            let host_ms = now.duration_since(previous_at).as_secs_f64() * 1_000.0;
            if host_ms < BUNCHED_MS {
                self.bunched += 1;
            }
            let span = match separation_mm {
                Some(millimetres) => format!("   apart {millimetres:5.1} mm"),
                None => String::new(),
            };
            format!(
                "phone {phone_ms:6.2} ms   arrived {host_ms:6.2} ms   contacts {contacts}{span}"
            )
        });
        self.previous = Some((event_time_ns, now));
        self.frames += 1;
        line
    }

    /// A verdict on the session, for when the client disconnects.
    pub fn summary(&self) -> String {
        if self.frames == 0 {
            return "no frames".to_owned();
        }
        let percent = self.bunched as f64 * 100.0 / self.frames as f64;
        let verdict = if percent > 10.0 {
            "frames are arriving in bursts, which breaks pointer acceleration"
        } else {
            "frames are evenly spaced"
        };
        format!(
            "{} frames, {} arrived within {BUNCHED_MS} ms of the last ({percent:.1}%): {verdict}",
            self.frames, self.bunched
        )
    }
}

impl Default for TimingTrace {
    fn default() -> Self {
        Self::new()
    }
}
