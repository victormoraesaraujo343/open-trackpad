# OpenTrackpad Protocol

Status: draft version 4, text framing for prototyping.

Version 2 added the physical size of the touch surface to the handshake.
Version 3 added actions. Version 4 opened a channel in the other direction.

Each message is one UTF-8 line separated by `\n`. Fields are separated by ASCII spaces. The host closes the connection on malformed input.

No line may exceed 4096 bytes. A frame with thirty-two contacts is under six
hundred, and the longest request is a few dozen; the limit exists so a client
that never sends a newline cannot make the host buffer without end.

## Session header

```text
HELLO OTP/4 <width> <height> <max_contacts> <width_um> <height_um> [<capabilities>]
```

Example, a 6.7-inch phone held in landscape that wants the audio panel:

```text
HELLO OTP/4 2412 1080 10 155000 69000 audio
```

`width` and `height` describe the Android touch coordinate space in pixels.
Values must be positive. `max_contacts` must be between 1 and 32.

`width_um` and `height_um` are the *physical* size of that touch surface in
micrometres, in the same orientation. They are not optional and the host will
not guess them: libinput reasons about touchpads in millimetres, so this figure
sets pointer speed, scroll distance, gesture thresholds and edge zones. Only the
client knows how big its screen is, and every phone is different — a host that
assumed a size would be wrong on every device but one.

`capabilities` is optional and is a comma-separated list, or `-` for none. A
client that only wants a trackpad sends the handshake it always sent, one
version number higher. Names the host does not recognise are **ignored, not
refused** — that is what lets a later panel be added without another version
bump.

The host creates its virtual touchpad at the reported size, replacing the
existing one if a client reports a different screen. The kernel cannot resize an
input device, so this appears to the desktop as an ordinary hotplug.

The host then answers, for the first time in this protocol's life:

```text
WELCOME OTP/4 <capabilities>
```

The capabilities in the answer are what the client asked for, kept to what this
machine can actually serve. A host with no sound daemon answers `WELCOME OTP/4 -`
and the phone draws no panel — absent rather than broken. Nothing else is sent
until the client has had this line.

### Meeting an older host

The version is checked before anything else, and a mismatch closes the
connection immediately with no reply. A client that speaks version 4 and meets a
version 3 host therefore learns so at once, and **must reconnect with an OTP/3
handshake and no panel** rather than waiting. Nothing wedges, because nobody is
ever waiting for an answer that is not coming.

The bump could not be avoided. Older hosts treat an unexpected field as fatal, so
there is no way to add one quietly. That strictness is what keeps this from
being a remote shell, and an honest version number is what it costs.

## Contact frame

```text
FRAME <sequence> <event_time_ns> <count> [<id> <x> <y> <pressure> <major>]...
```

Each frame is a complete snapshot of active contacts. A contact missing from the next frame is considered released.

- `sequence`: monotonically increasing unsigned integer
- `event_time_ns`: Android monotonic event timestamp
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

## Actions

```text
ACTION <sequence> KEY <chord>
```

Example:

```text
ACTION 42 KEY ctrl+shift+t
```

A chord is key names joined by `+`, pressed in the order given and released in
reverse, as one report. Modifiers therefore surround the key they modify, which
is what applications expect to see.

`sequence` is required so a client can log and correlate its own actions. The
host parses and discards it: actions are independent of each other and of touch,
so there is nothing to order them against.

### The vocabulary is closed

Only the names the host knows are accepted, and there is no kind that runs a
command. A control surface that can press any key, or run anything, is a remote
shell with buttons on it. Unknown names, empty parts, repeated keys and chords
longer than five keys are all rejected, so a malformed action never becomes a
partially pressed one.

### Separate paths

Actions travel to a separate virtual device from touch, and a failure to type
one is reported without ending the session. A shortcut going wrong must not be
able to corrupt the trackpad.

Everything the host holds down is released when the session ends, however it
ended. A stuck modifier makes the desktop unusable and gives no clue why.

### Rate

Actions arriving faster than fifty a second are dropped. A finger on a button
tops out around fifteen, so this only bites on a client stuck in a loop.

## State the host sends back

One message per line, as everything else. `<domain>` is `audio` today; the
recent-applications rail will be the second.

```text
SNAPSHOT <domain> <generation> <count>
ENTRY    <domain> <generation> <entity>
CHANGED  <domain> <generation> <entity>
REMOVED  <domain> <generation> <kind> <id>
UNAVAILABLE <domain> <reason>
REFUSED  <sequence> <reason>
```

`SNAPSHOT` opens a complete picture and is followed by exactly `count` `ENTRY`
lines of the same generation. `CHANGED` is one entity that has appeared or is no
longer what it was. The generation rises with every snapshot, so a client can
tell that an update belongs to the picture it holds.

An `<entity>` is seven fields:

```text
<kind> <id> <volume> <muted> <default> <target> <name>
```

- `kind`: `output`, `input` or `stream`
- `id`: the sound daemon's own number. The only identifier that crosses the wire
- `volume`: 0 to 1500, where 1000 is 100%. Per-mille because a fader that runs
  the height of a phone screen has room for far more than a hundred steps, and
  the ceiling sits above the reference because louder than 100% is offered
- `muted`, `default`: `0` or `1`. `default` is always `0` for a stream
- `target`: for a stream, the output it plays through; `-` for a device
- `name`: percent-encoded (see below)

Example:

```text
SNAPSHOT audio 1 2
ENTRY audio 1 output 53 950 0 1 - Built-in%20Audio%20Digital%20Stereo
ENTRY audio 1 stream 1348 990 0 0 53 Firefox
```

`UNAVAILABLE` reasons: `no-tool`, `no-daemon`, `lost`. `lost` means it was
working and stopped — the panel empties rather than freezing on values that are
no longer true. The host keeps looking and sends a fresh `SNAPSHOT` if it
returns.

### Names are percent-encoded, and this is load-bearing

Device descriptions and application names are free text the host does not
author: a window title comes from whatever page a browser has open. Pasted raw
into a line-framed protocol, a name containing a newline would let a web page
write its own messages into the stream the phone is reading. Everything outside
printable ASCII, and the space and percent themselves, becomes `%XX` per UTF-8
byte. A name can therefore never contain a separator or a line ending. An empty
name is sent as `%20`, so it cannot vanish and shift the fields after it.

## Requests the client sends back

```text
REQUEST <sequence> <domain> VOLUME  <kind> <id> <level>
REQUEST <sequence> <domain> MUTE    <kind> <id> <0|1>
REQUEST <sequence> <domain> DEFAULT <kind> <id>
REQUEST <sequence> <domain> REFRESH
```

`sequence` is the client's own numbering, echoed back only when a request is
refused, so it knows which fader to put back.

Every request names a **kind as well as an id**. Outputs, inputs and streams are
numbered independently by the sound daemon — sink 53 and source 53 exist at the
same time and are different devices — so an id alone would be ambiguous, and the
way that shows up is a fader moving the wrong thing.

`REFRESH` asks for the whole picture again: a new `SNAPSHOT`, not a difference.
It is what the client sends when it opens the panel, and its way out of any
disagreement about state.

### This vocabulary is closed, for the same reason the key vocabulary is

A channel in the other direction is a second attack surface. Every verb names a
number the host published in a snapshot and does one bounded thing to it. There
is no verb that names a command, a path, or a device by string. Device names
exist only inside the host and never cross the wire in either direction as
something that can be acted on.

Anything else is a protocol error and closes the connection, exactly as an
unknown key name does:

- an unknown domain, verb or kind
- a level outside the accepted range — refused, not clamped: a client that does
  not know the scale is a client whose next message cannot be trusted either
- a mute flag that is not `0` or `1`, or an id that is not a number
- trailing fields
- a request before the handshake
- a request for a domain the host never granted
- asking for a stream to become the default, which cannot mean anything

### Refusals, which are not protocol errors

`REFUSED <sequence> <reason>` answers a request that was legal and could not be
done. The session continues.

- `unknown-id` — no such entity. The ordinary case is a device unplugged while a
  finger is still on its fader
- `wrong-kind` — the verb does not apply to that entity
- `unavailable` — the domain cannot be served right now
- `backend-failed` — the sound daemon refused
- `too-fast` — see below

Success is not acknowledged. The `CHANGED` that follows is the acknowledgement,
and it carries what the value actually became rather than what was asked for.

### Rate

Requests are limited the way actions are, with a higher ceiling: 200 a second
sustained, 80 back to back. A fader dragged on a 120 Hz phone sends a level per
frame and all of those are real; a client stuck in a loop still hits the wall.

The host also gathers requests for 50 ms and drops any that a later one has
overtaken, so a drag across the screen costs one change rather than forty. A
later mute does not overtake an earlier level change: someone who slides a fader
and then mutes expects the level to be there when they unmute.

## Note for the audio panel

Monitor sources are not listed. Every output has one and none of them is a
microphone; showing them would double the input page with rows that mean nothing
to the person reading it.

Two numbers, not one. 1000 is what reads as 100%; 1500 is the highest level
that may be asked for. They were a single constant while they happened to be
equal, which is how a ceiling silently becomes a reference and caps everything
back at 100%.

Above 100% is offered, not hidden. The fader is drawn against the full scale to
150% with a mark at 100, and everything past that mark — bar, knob and number —
turns the amber the rest of the app uses for a degraded state, so a glance shows
you are in the distorting range without reading anything. The client only offers
it once someone turns it on in the panel's settings.

Reported levels are not capped either. A device left at 130% by another tool
reaches the client as 1300, because telling the phone the machine is quieter
than it is actually playing is the wrong half of the decision. Past 150% the
host reports 150, since the client has no way to draw more and a number it
cannot show is worse than the edge it can.

## Future binary framing

Text framing is intentionally easy to inspect during the prototype. A compact binary representation may replace it only after physical-device latency and CPU measurements show that framing is a material bottleneck.
