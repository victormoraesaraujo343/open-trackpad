# OpenTrackpad Protocol

Status: draft version 2, text framing for prototyping.

Version 2 added the physical size of the touch surface to the handshake.
Version 1 had no way to express it, so the host had to assume one.

Each message is one UTF-8 line separated by `\n`. Fields are separated by ASCII spaces. The host closes the connection on malformed input.

## Session header

```text
HELLO OTP/2 <width> <height> <max_contacts> <width_um> <height_um>
```

Example, a 6.7-inch phone held in landscape:

```text
HELLO OTP/2 2412 1080 10 155000 69000
```

`width` and `height` describe the Android touch coordinate space in pixels.
Values must be positive. `max_contacts` must be between 1 and 32.

`width_um` and `height_um` are the *physical* size of that touch surface in
micrometres, in the same orientation. They are not optional and the host will
not guess them: libinput reasons about touchpads in millimetres, so this figure
sets pointer speed, scroll distance, gesture thresholds and edge zones. Only the
client knows how big its screen is, and every phone is different — a host that
assumed a size would be wrong on every device but one.

The host creates its virtual touchpad at the reported size, replacing the
existing one if a client reports a different screen. The kernel cannot resize an
input device, so this appears to the desktop as an ordinary hotplug.

## Contact frame

```text
FRAME <sequence> <event_time_ns> <count> [<id> <x> <y> <pressure> <major>]...
```

Each frame is a complete snapshot of active contacts. A contact missing from the next frame is considered released.

- `sequence`: monotonically increasing unsigned integer
- `event_time_ns`: Android monotonic event timestamp
- `count`: number of contacts in this frame
- `id`: Android pointer ID
- `x`, `y`: integer pixel coordinates
- `pressure`: normalized integer from 0 through 1024
- `major`: contact major axis in pixels

Example with two contacts:

```text
FRAME 42 9912345678 2 0 210 780 650 11 1 810 782 620 10
```

## Disconnect behavior

The host releases every active contact when the socket disconnects or a protocol error occurs. This prevents stuck fingers in the virtual device.

## Future binary framing

Text framing is intentionally easy to inspect during the prototype. A compact binary representation may replace it only after physical-device latency and CPU measurements show that framing is a material bottleneck.
