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

/// A ceiling on how often something may happen, with room for a burst.
///
/// A token bucket rather than a minimum gap, so a short flurry of deliberate
/// input goes through while a sustained flood does not. Shortcuts and panel
/// requests both need this and need it set differently — a button is tapped, a
/// fader is dragged — so the shape is here and the numbers live with the thing
/// being limited.
pub struct TokenBucket {
    tokens: f64,
    last: Instant,
    burst: f64,
    per_second: f64,
}

impl TokenBucket {
    pub fn new(now: Instant, burst: f64, per_second: f64) -> Self {
        Self {
            tokens: burst,
            last: now,
            burst,
            per_second,
        }
    }

    /// Whether something arriving at `now` may proceed.
    pub fn allow(&mut self, now: Instant) -> bool {
        let elapsed = now.saturating_duration_since(self.last).as_secs_f64();
        self.last = now;
        self.tokens = (self.tokens + elapsed * self.per_second).min(self.burst);
        if self.tokens < 1.0 {
            return false;
        }
        self.tokens -= 1.0;
        true
    }
}

#[cfg(test)]
mod bucket_tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn a_burst_goes_through_and_the_flood_after_it_does_not() {
        let start = Instant::now();
        let mut bucket = TokenBucket::new(start, 5.0, 10.0);
        for allowed in 0..5 {
            assert!(bucket.allow(start), "{allowed} was refused");
        }
        assert!(!bucket.allow(start));
    }

    #[test]
    fn the_allowance_comes_back_over_time() {
        let start = Instant::now();
        let mut bucket = TokenBucket::new(start, 5.0, 10.0);
        for _ in 0..5 {
            bucket.allow(start);
        }
        assert!(!bucket.allow(start));
        assert!(bucket.allow(start + Duration::from_millis(200)));
    }

    #[test]
    fn a_long_quiet_spell_does_not_bank_an_unlimited_allowance() {
        let start = Instant::now();
        let mut bucket = TokenBucket::new(start, 5.0, 10.0);
        let later = start + Duration::from_secs(3600);
        for allowed in 0..5 {
            assert!(bucket.allow(later), "{allowed} was refused");
        }
        assert!(!bucket.allow(later));
    }
}
