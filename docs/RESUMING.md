# Resuming

Where OpenTrackpad stands, what is waiting on a decision, and how the sessions
that built it were arranged. Written to be picked up cold.

Last updated 2026-08-30.

## The short version

v0.2 is functionally complete. Every drawn screen is built, the host serves four
domains, and the tag `v0.2-one-identity` is the point to come back to.

What is *not* settled is mostly not code. Two control shapes need a protocol
decision before their screens mean anything, a haptic vocabulary has never been
felt by a human, and a second visual identity is drawn, corrected and
deliberately unbuilt.

## Waiting on the owner

Nothing below can be decided by reading the repository.

| | |
| --- | --- |
| **Haptics: mechanism or notification?** | The whole vocabulary rests on one claim — that a press and a softer release read as a mechanism rather than a notification. Nobody has felt any of it. The development phone reports **no composition primitives at all**, so it plays the four-effect fallback, which on that evidence is what most people will feel. If the answer is "notification" the design needs replacing rather than tuning, which is why nothing was tuned. |
| **`KEY_RO` and `KEY_102ND`** | The `/ ? °` and `< >` keys on a Brazilian keyboard. The host has no name for either and refuses them, saying so. Adding them is trivial; naming them is not — every name is right on one layout and wrong on another, and a protocol token outlives the machine it was chosen on. Left out until somebody actually wants one. |
| **Two screens of the second identity** | `ImportShortcuts` and `NameProfile` were never drawn in the skeuomorphic look. A skin missing two screens falls back to the default in two places, which is worse than either look alone. |
| **The two-level Quick Ring** | Drawn as `QuickRingProfiles.dc.html`: hold a ring destination and its own choices open in place, release on one to choose. It fits the gesture the ring already has, since it chooses on release. Not built. |
| **`super+shift+s` in the recorder** | The only key never pressed. `super+d` and `alt+tab` are captured, which proves inhibition works; neither rules out another client holding a grab on that specific chord. The recorder now reports whether the compositor accepted the inhibit, so one run answers it. |
| **A GNOME machine** | Import is verified against real GNOME schemas but with default values, on a KDE box. One run of `opentrackpadd --shortcuts` on a real GNOME session would close it. |

## Deliberately unbuilt, and why

**The dial and the held key.** Both are drawn; neither has a wire decision.

A dial has to decide what it sends, and repeated `volumeup` presses is the
plus-and-minus mistake wearing a circle. Volume has an honest answer already —
the audio domain's `VOLUME` request — but that only helps the one thing with a
domain, and brightness has none. So continuous control is either a verb of its
own or it is per-domain, and there are only ever as many as there are domains.

A held key needs the wire to express down and up separately, which was
deliberately refused for `BUTTON`: a stuck mouse button cannot be clicked out
of. Whatever allows it must carry the same release guarantee.

**The second visual identity.** Drawn in full, light and dark, and corrected
against everything learned on real hardware. It is paused because a palette is
not a skin: the 42 artboards carry roughly sixteen gradients and fifteen shadows
per screen, so repainting colours would give the same flat drawing in new
colours — neither identity.

What a theme has to select is a **surface description** — how a key is painted
at rest, lit and pressed — which means the default identity's own painting moves
behind that interface first, unchanged. The unit of that work is the **view**,
not the screen, because screens share views.

`Controls.kt` is done. `RailView` is the widest and the trackpad surface is
last. The switch in Settings stays hidden until every view is converted, because
a half-themed app reads as broken.

**Icons for recent applications.** Refused with a reason worth keeping: the
development machine's icon theme has fifty thousand SVGs and no PNGs, so real
icons would mean rasterising inside a daemon that carries twelve crates and
cannot decode an image. On that desktop it would show a real icon for Firefox
and generic glyphs for Dolphin and System Settings, which reads as broken rather
than as absent. The honest route, if it is ever wanted, is a separate helper
that rasterises through the desktop's own machinery and caches PNGs — a piece of
work, not a tweak.

## The pixel check

`android/tools/` holds a screenshot comparison that captures thirteen distinct
screens and fails if any pixel moves. It exists so the default identity can be
proved unchanged while the second one is built.

Two things about it are load-bearing and easy to undo by tidying:

- **It refuses a capture set where screens that should differ are identical.**
  Without that it once reported ten screens verified while capturing the same
  screen nine times, and read green throughout.
- **Exclusions are named, not absorbed by a tolerance.** A threshold wide enough
  to swallow a pulsing dot is wide enough to swallow a shadow that moved.

## How the work was organised

Four roles, and the shape matters more than the names.

- **Product** — design, decisions, `docs/`, the screens. The only session that
  hands work to executors, and the one the owner talks to.
- **Host executor** — `host/`, `tray/`, `recorder/`, `scripts/`, `packaging/`.
- **Android executor** — `android/`.
- **Panel** — summarises executor output for someone who is not a developer.

**One file, one owner.** Executors flag problems outside their folder rather
than fixing them; the host executor proposes protocol wording and product
writes it.

The panel exists because an orchestrator plus an executor alone floods the
conversation with build detail. It is the first filter; product is the second.

Two working rules earned the hard way. **Executors commit and never push** —
pushing is the owner's, and a commit message is where the reasoning lives.
**Emulator first while the owner is at his desk**: a development convenience
reaching his machine cost him a confusing minute three separate times, and an
install that puts a broken screen in front of him is worse than a slow one.

`CONTRIBUTING.md` carries the lessons this project kept rediscovering. They are
there because each one cost an hour at least once.
