//! The parts of the daemon that something other than the daemon needs.
//!
//! The shortcut recorder is a separate program — see `recorder/` — and it has
//! to speak about chords in exactly the same terms this daemon does. Not
//! similar terms: the same ones. A recorded chord is validated by the same
//! function, against the same vocabulary, with the same refusals, because a
//! second copy of any of that would drift and the drift would show up as a
//! shortcut that records fine and then will not fire.
//!
//! So those three modules are a library, and the daemon is one of its two
//! consumers rather than their owner. Everything else — the protocol, the
//! devices, the panel — stays private to the binary, because nothing outside it
//! has any business touching input.
//!
//! Deliberately not a separate `core` crate. That is the tidier shape for a
//! project that does not exist yet; this one has a working daemon and no reason
//! to move code that is currently correct.

pub mod keys;
pub mod shortcuts;
pub mod text;
