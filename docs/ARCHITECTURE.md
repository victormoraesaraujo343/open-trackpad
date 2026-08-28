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

The daemon validates frame order and contact bounds, then maintains the active-contact state. The `uinput` backend will emit a button-pad-style device using Linux multi-touch slots, including at least:

- `EV_ABS`
- `ABS_MT_SLOT`
- `ABS_MT_TRACKING_ID`
- `ABS_MT_POSITION_X`
- `ABS_MT_POSITION_Y`
- `ABS_MT_PRESSURE`
- `BTN_TOUCH`
- `BTN_TOOL_FINGER`
- `INPUT_PROP_POINTER`
- `INPUT_PROP_BUTTONPAD`

Exact capabilities must be verified against `libinput record` and `libinput debug-events`; merely emitting events does not prove the device is classified as a touchpad.

## Trust boundary

The daemon receives input capable of controlling the desktop. The first version therefore listens on loopback only. Any future Bluetooth or network transport must authenticate the client before enabling input injection.

## Latency measurement

Frames carry the Android monotonic timestamp and a sequence number. End-to-end measurement will require clock-offset calibration or a round-trip probe; timestamps from two devices must not be compared directly without calibration.
