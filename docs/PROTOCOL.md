# OpenTrackpad Protocol

Status: draft version 1, text framing for prototyping.

Each message is one UTF-8 line separated by `\n`. Fields are separated by ASCII spaces. The host closes the connection on malformed input.

## Session header

```text
HELLO OTP/1 <width> <height> <max_contacts>
```

Example:

```text
HELLO OTP/1 1080 2400 10
```

`width` and `height` describe the Android touch coordinate space in pixels. Values must be positive. `max_contacts` must be between 1 and 32.

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
