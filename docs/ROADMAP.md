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

## Non-goals for the first release

- Emulating Apple's proprietary Magic Trackpad protocol
- Windows Precision Touchpad certification
- Screen mirroring or remote desktop
- Requiring a rooted Android device
