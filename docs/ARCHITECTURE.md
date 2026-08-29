# Architecture

## Goal

Expose an Android touchscreen to Linux as a virtual multi-touch touchpad, rather than a relative mouse.

## Components

### Android client

The client will run an immersive touch surface and capture every active pointer from Android `MotionEvent` objects:

- pointer ID
- X and Y coordinates
- pressure
- contact major axis
- monotonic event timestamp

It sends complete contact snapshots. Sending all active contacts makes recovery from a dropped frame deterministic.

### USB transport

The first transport uses a TCP socket exposed through:

```bash
adb reverse tcp:4242 tcp:4242
```

The Android client connects to `127.0.0.1:4242`; ADB carries that connection to the daemon on the Linux host. This avoids the shared Wi-Fi network and charges the device at the same time.

Production packaging should replace the development-only ADB workflow if a stable no-debug USB transport is found. That decision is intentionally deferred until the prototype is measured.

### Linux daemon

The daemon validates frame order and contact bounds, maintains the active-contact
state, and replays it on a virtual button-pad-style device using Linux
multi-touch slots.

The capability set it declares today:

| Group | Codes |
| --- | --- |
| Properties | `INPUT_PROP_POINTER`, `INPUT_PROP_BUTTONPAD` |
| Multi-touch | `ABS_MT_SLOT`, `ABS_MT_TRACKING_ID`, `ABS_MT_POSITION_X/Y`, `ABS_MT_TOUCH_MAJOR` |
| Single-touch | `ABS_X`, `ABS_Y` |
| Buttons | `BTN_TOUCH`, `BTN_TOOL_FINGER`/`DOUBLETAP`/`TRIPLETAP`/`QUADTAP`/`QUINTTAP`, `BTN_LEFT` |

Three decisions in that table are worth stating explicitly, because they are the
reasons udev tags the device `ID_INPUT_TOUCHPAD` rather than something else:

- The single-touch axes are not redundant. udev's `input_id` builtin looks for
  `ABS_X`/`ABS_Y`, and libinput uses them as the emulated pointer. The kernel
  synthesises them for real drivers; a `uinput` device has to emit them itself.
- `BTN_TOOL_FINGER` present and `BTN_TOOL_PEN` absent, with no
  `INPUT_PROP_DIRECT`, is what separates a touchpad from a touchscreen or a
  tablet.
- `BTN_LEFT` is declared but never pressed in v0.1. Clicks come from libinput's
  own tap-to-click handling.

### Why there is no pressure axis

The device deliberately reports no pressure, and this is load-bearing rather
than an omission.

Declaring `ABS_MT_PRESSURE` makes libinput derive a palm-detection threshold
from the axis range — roughly 13% of it when no per-model quirk exists. Every
contact above that is discarded as a resting palm. On a 0..1024 range that left
a usable window seven units wide: touch detection began at 123 and palm
rejection started at 130. Contacts reporting a plausible 600 were silently
thrown away, which is why the device passed every classification check and still
moved nothing.

Real touchpads escape this because libinput ships a calibration quirk per model.
A phone reporting vendor-specific pressure has none, and shipping one per phone
is not realistic. Without the axis, libinput falls back to `BTN_TOUCH` and the
multi-touch tracking-ID lifecycle, both of which the daemon controls exactly.

This also matches the project guardrail that Android pressure semantics vary by
hardware and must not carry essential behaviour. `ABS_MT_TOUCH_MAJOR` is kept
only because libinput acts on contact size solely when a quirk defines its
range, so it stays inert instead of misfiring.

### Coordinate space

The virtual touchpad has a fixed size of its own — 140 mm by 63 mm at 40 units
per millimetre — and the daemon scales the phone's pixel coordinates into it.
The device therefore exists before any phone connects, and a different phone can
reconnect without recreating it. The declared physical size is an assumption to
validate, not a measurement: libinput reasons about touchpads in millimetres, so
it affects pointer speed and gesture thresholds.

Exact capabilities must be verified against `libinput record` and `libinput
debug-events`; merely emitting events does not prove the device is classified as
a touchpad. See [testing and validation](TESTING.md) for what has actually been
observed.

## Trust boundary

The daemon receives input capable of controlling the desktop. The first version therefore listens on loopback only. Any future Bluetooth or network transport must authenticate the client before enabling input injection.

## Latency measurement

Frames carry the Android monotonic timestamp and a sequence number. End-to-end measurement will require clock-offset calibration or a round-trip probe; timestamps from two devices must not be compared directly without calibration.
