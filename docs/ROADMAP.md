# Roadmap

## Milestone 0 — repository foundation

- [x] Define the USB-first architecture
- [x] Draft a debuggable touch-frame protocol
- [x] Add a host protocol receiver and parser tests
- [x] Target any Linux with libinput, and any Android 9 or newer

## Milestone 1 — host virtual touchpad

- [x] Implement the Linux `uinput` backend
- [x] Add safe release-all behavior on disconnect
- [x] Add a synthetic contact self-test for the `uinput` path
- [x] Confirm udev tags the device `ID_INPUT_TOUCHPAD`
- [x] Verify libinput classifies the device as a touchpad
- [x] Verify injected contacts move the desktop pointer
- [x] Verify two-finger scrolling and three- and four-finger swipe gestures
- [ ] Verify on a second distribution and desktop (GNOME as well as KDE)
- [x] Add a udev rule and systemd user service
- [ ] Confirm 30 minutes of use leaves no stuck contact (`--soak 30`)

## Milestone 2 — Android sender

- [x] Build an immersive Kotlin touch surface
- [x] Send complete multi-contact snapshots
- [x] Add orientation and coordinate normalization
- [x] Prevent sleep while connected
- [x] Show connection status without consuming touch area
- [x] Coalesce movement under backpressure without losing lifts
- [x] Reconnect with a fresh handshake
- [x] Report the phone's real screen size so any device maps correctly
- [x] Test on a real phone against the host

## Milestone 3 — dedicated appliance mode

- [x] Auto-connect after launch, with no terminal commands
- [x] Re-establish the USB bridge when the cable comes back
- [x] Start-on-boot through systemd user services
- [x] A tray indicator showing connection state, with stop and start
- [x] Dim the screen when idle, and shift static elements so nothing burns in
- [ ] Accidental-edge and palm filtering
- [ ] Calibration UI: in-app sliders for pointer speed and sensitivity
      Asked for after the first hardware test: the desktop's own pointer speed
      setting is per-device and buried in system settings, and a dedicated
      trackpad wants its feel adjustable from the surface itself.

## v0.2 — control surface

The visual design and its screens live in `docs/DESIGN.md`, which carries the
design system and links to the artifacts the screens are drawn in. What follows
is the parts underneath, which do not depend on how the screens look.

The active screen holds two things: the trackpad, and a rail down each side.
There is no header, no status bar and no settings button — every pixel that is
not a rail belongs to the touch surface. Which side each rail takes is a
setting.

One rail holds shortcuts: four on the rail itself, and a fifth slot that opens
the Quick Ring. The ring is therefore not a shortcut menu but the app's only way
in to anything at all: settings, profile editing, modes, connection detail and
whatever comes later. Which four shortcuts sit on the rail is chosen from inside
it.

That constraint is the point rather than an omission. A dedicated peripheral
should not spend its surface on chrome.

### Three shapes, not one

Treating every control as a button was a mistake worth naming, because it shows
up again wherever a value has a range:

- **Actions** happen once when tapped. Play, next track, escape, copy.
- **Continuous values** are travelled through, not stepped. Volume, brightness,
  seeking, zoom. A pair of plus and minus buttons is the wrong shape: reaching
  30% from 70% becomes eight taps.
- **Holds** keep a key down while the other hand uses the trackpad. Ctrl to
  multi-select, Shift to extend a selection, Space to pan in a design tool.
  These are impossible with a trackpad alone, which makes them the strongest
  argument for a separate surface.

Only the first exists today. The other two need the protocol and the host to
grow before they can be drawn.

### On feeling considered

Simple and fast, but thought through. A continuous control should feel like the
iPod click wheel rather than a slider bolted on, and haptics should confirm that
a movement was understood — the phone is being looked at with peripheral vision
at best, so touch has to carry what sight will not.

A continuous control does not need to know the value it is changing. The desktop
already shows its own volume and brightness overlays, and the person is looking
at that screen. Sending "a little more" and letting the computer display the
result keeps the phone simple and works the same on every desktop.

The other rail holds recently used desktop applications, and is deferred: see
Milestone 5.

- [x] A separate virtual keyboard, so shortcuts cannot corrupt touch state
- [x] A closed vocabulary of key names, with no way to run a command
- [x] `ACTION` messages alongside touch frames
- [x] Release every held key when a session ends, however it ended
- [x] Drop actions arriving faster than a hand could produce them
- [x] Send actions from the Android client
- [ ] Profiles: which shortcuts appear where
- [ ] Mouse buttons as actions

### Decided on 2026-08-29, reviewing the control list

Four decisions came out of reading through every control worth having. They are
recorded here because the reasoning matters more than the list.

**Custom shortcuts, recorded on the host.** The closed vocabulary is what stops
a control surface from becoming a remote shell, so it stays closed — it just
becomes editable by the person sitting at the machine. Recording is a host-side
overlay that captures a real key press and adds that chord to the list. The
client may *ask* for the overlay to open, since that is only another action, but
it never authors a chord itself. The phone stores nothing: shortcuts live on the
host and the client receives the finished list.

Keeping the client thin is deliberate. It has to run well on a wide range of
Android devices, not just a good one.

**Panels are a fourth shape.** Alongside actions, continuous values and holds, a
rail slot may open a panel instead of firing a key. Audio is the first one.

**The audio panel needs state to travel back.** Continuous controls were
designed not to know their own value, and that still holds. A mixer cannot work
that way: it has to name the sinks and sources, their levels, and the streams
playing through them. Per-application volume comes from the same place as device
volume on PipeWire, so it is built alongside rather than deferred.

**One return channel serves both.** The mixer and the recent-applications rail
(Milestone 5) need the same thing: host state carried to the client, and
requests carried back. It is built once.

Every control on the reviewed list is kept. With the ready-made set plus
unlimited custom ones, the picker inside the Quick Ring is a real list needing
grouping and search, not a short menu — that is a constraint on the design, not
an afterthought.

- [ ] Host-side shortcut recording overlay, opened locally or by client request
- [ ] Persist custom chords on the host and extend the accepted vocabulary
- [ ] Accept a single key as a recorded shortcut, not only a modified one
      Decided 2026-08-29: `print` and `f11` are shortcuts on their own. A lone
      modifier records too rather than being refused — it simply does nothing
      when tapped, which is what the hold shape exists for.
- [ ] Carry host state to the client, and requests back
- [ ] Audio panel: devices in and out, with per-application streams
- [ ] Panel as a rail slot kind, alongside action, continuous and hold
- [ ] A panel takes the trackpad's area and turns the far rail into its pages
      Decided 2026-08-29, designing the audio panel. A panel never covers the
      rails, and the rail OPPOSITE the Quick Ring — the side the handedness
      setting frees up — stops being shortcuts while the panel is open and
      becomes its pages: close, then one per group. The Quick Ring never moves,
      so the way out of the app is always in the same corner.

      One group per page rather than all of them at once, with vertical faders
      and sideways scrolling when the channels overrun. Cramming output, input
      and applications into a single screen breaks the moment someone plugs in
      a fourth device, and a fader that travels the height of the screen is
      worth roughly four times the precision of a row-width bar. The gesture
      also means what it does: up is louder.

      This is the pattern for every panel, not just audio, so the navigation is
      learned once.

      A rail is ALWAYS five slots, in every screen and every panel. The first
      four change with what is on screen; the fifth always means "everything
      else about this" — the Quick Ring on the main screen, the panel's own
      settings inside a panel. The audio panel is close, output, input, apps,
      settings.

      Slots one and five hold their meaning everywhere, so only the middle
      three ever move. When a panel has fewer than four sections the leftover
      slot stays empty rather than letting the others grow: a peripheral used
      without looking cannot have its buttons shift position, and a stable grid
      is worth more than a full one.

### Screens

All of them are drawn as of 2026-08-29 and live in the screens artifact linked
from `docs/DESIGN.md`. The eleven counted that day are listed below with what
each one settled, because the list is the brief for building the client.

**The audio panel is one short.**

- [x] Audio · input. Same shape as output, microphones instead of speakers.

**Shortcuts have the largest hole.** The recorder that appears on the computer
is drawn; nothing on the phone yet asks for it, and the profile editor holds
rails and buttons but no shortcuts to put in them.

- [x] The shortcut picker — the list you drag from. Fifty ready-made plus
      unlimited custom ones is a real list needing grouping and search, not the
      short menu the editor implies today.
- [x] `+ New shortcut` inside that picker, which is what asks the computer to
      open its recorder. The moment is right there: you are building a rail,
      you notice one is missing, you make it.
- [x] Renaming and deleting a custom shortcut. Anything that can be created has
      to be undoable or the list only ever grows.
- [x] The profile editor reworked around the picker above.

**Two of the three control shapes were named and never drawn.** Both were
described in this file months before anything could show them; the panel work
has now overtaken them.

- [x] The dial. What a continuous slot looks like while a thumb is on it —
      volume, brightness, seeking. The iPod click wheel is the reference, and
      the value is deliberately absent: the desktop shows its own overlay.
- [x] A held key. The rail slot stays lit while the other hand works the
      trackpad, and everything releases when the session ends.

**Removing the status bar left states with nowhere to go.** The v0.1 screens
carry a compact bar; v0.2 deliberately has none, so the states it used to hold
need a home.

- [x] Cable pulled while in use, and coming back.
- [x] The host refusing the version — the phone must say so in a way that names
      the fix, since the answer is always "update the other side".

**The computer has surfaces too**, and they should not be invented ad hoc in
code the way the recorder nearly was.

- [x] The tray menu. It exists and works, and nobody has ever drawn it.

**The recent-applications rail**, whose blocker is gone.

- [x] Four recent windows on the far rail, and the fifth slot listing the rest.

The return channel that shipped today is what the rail was waiting for, so the
screens can be drawn now. Building it still means one path per desktop —
KDE, wlroots, GNOME, X11 — which is why it stays behind the v0.2 client.

### Open decision: is recording a permission?

Raised 2026-08-29 while widening the vocabulary for custom shortcuts. Adding the
name `print` made `alt+print+b` reachable from the phone, which is the kernel
magic SysRq sequence and reboots the machine without unmounting. It was proven
reachable, refused, and enforced on both the wire and the recorder — a rule one
path enforces is a rule with a way around it.

That patch narrows what is reachable without changing the shape of the problem.
The host accepts any chord built from names it knows, so recording does not gate
anything: it only fills a list to draw buttons from. This file already describes
the other model — the vocabulary "stays closed, it just becomes editable by the
person sitting at the machine" — and under it the phone could only fire chords
somebody physically pressed on this keyboard, so the SysRq patch would be
unnecessary rather than load-bearing.

The cost is a product one, which is why it is not settled here: gating means a
fresh install ships with the built-in shortcuts already recorded, and what a
stranger's first launch contains is a decision, not an implementation detail. The
store has a `contains(chord)` waiting for the answer either way.

## Milestone 5 — recent applications rail

Four recently used desktop windows on a rail, a fifth slot listing the rest, and
tapping one switches to it.

Deferred because Linux has no single way to do it. KDE exposes windows through
`org_kde_plasma_window_management`, confirmed present on the development
machine; wlroots compositors use a different protocol; GNOME needs an extension.
X11 has EWMH, which works everywhere it applies.

- [ ] List open windows and their recent order, per desktop environment
- [ ] Activate a window by request
- [ ] Fetch application icons
- [ ] Carry the window list to the client and requests back
- [ ] Hide the rail entirely where the desktop cannot answer

## Milestone 4 — wireless fallback

- [ ] Evaluate authenticated Bluetooth Classic and BLE transports
- [ ] Measure latency and reconnection behavior against USB
- [ ] Document security and pairing behavior


## Later — how it should feel

Decided on 2026-08-29 as direction, not as work. None of this touches v0.2.

### Two visual languages, one app

A second look was drawn in parallel — skeuomorphic, drawn from a hardware audio
controller, and good enough to keep. Victor wants both available with the person
choosing, and the current one stays exactly as it is.

It ships as a theme, not a second app. Settings already carries a Minimal/HUD
switch, so the slot exists; two published builds would mean two things to
release, two bug surfaces and a person who picked wrong having to reinstall. The
skeuomorphic one is expected to earn its keep on larger screens, where its
weight has room.

### The trackpad's texture is a feature

The surface texture in the shipping light client gives the thing character, and
it survives into v1 rather than being flattened into a plain panel. Worth
saying because it looks like decoration and is not: it is the one place the app
admits to being a physical object.

### The click should feel like a click

The goal is the Apple trackpad: no moving part, yet a press that feels like one.
That machine has a force sensor and a linear actuator; a phone has an actuator
and no force sensor worth the name. The gap is smaller than it sounds, because
what sells the illusion is timing and shape, not force measurement.

Three things this needs, in order of how much they buy:

- **Fire on the real event, not a guess.** libinput decides what a tap is, on
  the host — the phone never learns of a click today. The return channel built
  for the audio panel is what makes it possible to say so. The catch is latency:
  a round trip plus an actuator's own start-up delay lands past the window where
  a tick still reads as the cause of the press. So the client should fire
  locally on the same thresholds libinput uses, and the host's event is how we
  find out when it guessed wrong.
- **A click is two events.** Apple ticks on press and again, softer, on release.
  A single tick reads as a notification; the pair reads as a mechanism.
- **Composed primitives, not a buzz.** Android exposes click, tick and rise/fall
  primitives that can be composed with scale and delay. A crisp scaled click for
  a press, a low tick for a release, a distinct shape for a two-finger click, and
  detents while scrolling. Amplitude control is not universal, so it degrades to
  a plain short pulse on hardware that lacks it.

On the missing force sensor: contact area already crosses the wire — the
protocol carries pressure and the contact major axis, and on a capacitive screen
those are the same measurement wearing two names. A finger pressing harder
spreads wider. That is a real signal and a poor one, and it should be treated as
a possible refinement to test rather than the foundation. The foundation is
timing.

## Non-goals for the first release

- Emulating Apple's proprietary Magic Trackpad protocol
- Windows Precision Touchpad certification
- Screen mirroring or remote desktop
- Requiring a rooted Android device
