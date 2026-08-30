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

### Meeting an older client

The host accepts `OTP/3` as well, and serves it exactly what version 3 had: a
touchpad and key chords. No `WELCOME`, because nothing had ever answered a
version 3 client and one arriving would be written into a socket nobody reads.
No capabilities, no buttons, no `RECORD`, no requests — a version 3 client
cannot know those exist, so one asking is not the client it claims to be.

This is not politeness. The `v0.1-light` client is a shipped, tagged thing that
people use, and it speaks version 3 with no lower version to retry at. A
version 4 host that refused it would take a working trackpad off somebody's desk
and give the phone no way to explain why. The newer client copes and the shipped
one cannot, which is exactly the asymmetry that makes this easy to miss.

One version back and only one. Versions 1 and 2 are refused; no client that
shipped speaks them.

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

### A chord is a position, not a letter

The recorder captures the hardware key, not the symbol printed on it. A shortcut
is a place your hand goes, and every desktop treats it that way: `ctrl+a`
recorded on one layout must not fire somewhere else when the layout changes.

The visible consequence is that a chord names the key by its US-layout symbol
whichever keyboard recorded it. On a Brazilian keyboard `ç` records as
`semicolon`, because that is the key. Someone who switches layouts keeps the
position rather than the letter, which is what a shortcut has always meant.

### Separate paths

Actions travel to a separate virtual device from touch, and a failure to type
one is reported without ending the session. A shortcut going wrong must not be
able to corrupt the trackpad.

Everything the host holds down is released when the session ends, however it
ended. A stuck modifier makes the desktop unusable and gives no clue why.

### Rate

Actions arriving faster than fifty a second are dropped. A finger on a button
tops out around fifteen, so this only bites on a client stuck in a loop.

### Mouse buttons

```text
ACTION <sequence> BUTTON <name>
```

`name` is `left`, `right` or `middle`. Anything else is rejected the way an
unknown key name is, and closes the connection. There is no way to name a button
by number, to attach a repeat count, or to hold one down.

A button is pressed and released as two reports, one after the other. There is
no held button and no drag: a drag needs the pointer moving while the button is
down, which is touch's job on the other path, and coordinating a held button
across two paths is where stuck buttons come from. A stuck mouse button is worse
than a stuck modifier — it makes the desktop unusable *and* unfixable, because
nothing can be clicked to escape it. Everything is released when the session
ends, however it ended.

Buttons exist because tap-to-click is a setting. Someone who turns it off has
told their system that taps should not click, and the touchpad honouring that is
correct rather than broken — but until this message there was no alternative, so
that person had no click at all and nothing said so.

### Why buttons are not gated and chords are

Chords are limited to what somebody recorded, because a hundred and thirty key
names combine into anything a desktop can do. Buttons are three fixed names that
can do nothing a mouse cannot already do, so the closed set is the whole
protection and there is nothing meaningful to record or delete. The asymmetry is
chosen rather than overlooked.

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

An `<entity>` is nine fields:

```text
<kind> <id> <volume> <muted> <default> <target> <port> <paused> <name>
```

- `kind`: `output`, `input` or `stream`
- `id`: the sound daemon's own number. The only identifier that crosses the wire
- `volume`: 0 to 1500, where 1000 is 100%. Per-mille because a fader that runs
  the height of a phone screen has room for far more than a hundred steps, and
  the ceiling sits above the reference because louder than 100% is offered
- `muted`, `default`: `0` or `1`. `default` is always `0` for a stream
- `target`: for a stream, the output it plays through; `-` for a device
- `port`: how a device is attached — `usb`, `pci`, `bluetooth`, `hdmi`,
  `analog`, `digital`. Transport before connector, so a USB sound card with an
  analog output says `usb`: that is what tells it apart from the built-in one.
  `-` for a stream
- `paused`: `1` when the application has stopped the stream, `0` otherwise;
  `-` for a device, which is never paused. Named for what it is rather than for
  what a screen wants: paused certainly means silent, but not-paused only means
  the stream is open, and an application can hold one writing silence for hours
- `name`: percent-encoded (see below)

A client should draw `port` only where two devices on the same page carry
exactly the same name — the case where the name resolves nothing at all. Shown
always it is noise: on the development machine four devices in seven have a name
that already says it, and one reads as a contradiction, `usb` beside a name
ending "Analog Stereo". Both are true, one being the transport and the other the
profile, and nobody reading a phone screen knows that. The comparison is exact
equality between two names the host wrote; anything cleverer means interpreting
free text, which is the thing this protocol keeps refusing to do.

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

**Sequences must increase across the whole session, not within a domain.** A
client counting per domain would have an audio request 1 and a shortcuts
request 1, and `REFUSED 1` would name both — landing on the wrong screen or on
none. That surfaces as a button that sometimes does nothing, and it is slow to
trace precisely because every line on the wire looks correct. Gaps are fine, so
one counter shared with actions is fine; requests and frames keep separate
counts.

The alternative was putting the domain in `REFUSED` as well, which is redundant
with a unique sequence and adds a second field that can disagree with the first.
Making the ambiguity impossible beats describing a way around it.

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

- an unknown domain, verb or kind. Verbs are dispatched per domain, so asking
  the shortcut list to change a volume is a protocol error rather than a
  refusal: a client doing that is broken or probing, and neither deserves a
  polite answer
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
- `stale` — the offer being accepted has moved on
- `full` — the shortcut list cannot hold them all
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

## The shortcuts domain

What the host will fire, which is what the profile editor offers.

```text
ENTRY shortcuts <generation> shortcut <id> <chord> <origin> <group> <name>
```

- `origin`: `convention`, `imported` or `recorded`
- `group`: one of `windows desktop screenshot sound media session power
  keyboard accessibility text browser terminal other`, or `-`
- `name`: percent-encoded

A recorded shortcut always sends `-` for its group. The person never said what
theirs is for, and inferring it from a chord would be worse than silence.

```text
REQUEST <sequence> shortcuts RENAME <id> <escaped name>
REQUEST <sequence> shortcuts DELETE <id>
```

### What may be renamed is not what may be deleted

Deliberately, and the two lists differ for two unrelated reasons:

- `recorded` — both. It is theirs.
- `imported` — rename yes, delete no. Once accepted it lives in the file like
  anything else, so a rename sticks. But deleting it only returns it to the
  offer, so delete would not mean what somebody pressing it expects.
- `convention` — neither. These are rewritten from the seed table, so a rename
  would quietly reappear and nobody could work out why.

Renaming is the one place a client's own free text crosses into the host. It
arrives percent-encoded like every other name, and a field carrying a raw space
was not written by anything that knows the rules, so it is refused rather than
half-read.

## The import domain

What this computer already has that OpenTrackpad does not — the desktop's own
keyboard shortcuts, offered for review.

```text
ENTRY import <generation> candidate <id> <chord> <group> <recommended> <name>
```

`recommended` is `0` or `1`. A candidate carries no origin and a shortcut
carries no recommendation: each would say the same thing every time in its own
domain, and a field that never varies teaches people to stop reading it.

The recommendation matters more than it sounds. The development machine offers
seventy-five candidates and forty-five are window management, a good third of
those "Switch to Desktop 7" and "Activate Task Manager Entry 3" — real
shortcuts nobody wants on a phone rail. So the review screen arrives with about
a dozen chosen rather than everything ticked, and the choosing happens here,
where the stable action keys are, rather than in a client guessing from the
shape of a name.

```text
REQUEST <sequence> import ACCEPT <generation> <id>,<id>,...
```

Accepted candidates become recorded and appear in `shortcuts`; their chords are
then filtered out of the next offer. Declining is not a state worth storing, so
there is no reject.

The offer is not a first-run event. It is available whenever asked, because
someone who binds a new desktop shortcut next month should be able to pick it
up, and a one-shot offer makes the only chance to say yes the moment somebody is
least ready to decide.

### Why ACCEPT carries a generation, and is all or nothing

Candidate numbers are the host's and mean nothing outside the offer they were
made in, so an accept quoting a stale generation is refused rather than applied
to whatever those numbers mean now.

A set naming anything unknown is refused whole. A partly applied set leaves
somebody looking at a screen that half agrees with the machine, with no way to
tell which half — the worst outcome available here, and worth the strictness to
make impossible.

## The windows domain

The desktop's recently used windows, most recent first, so the far rail can
switch between them.

```text
ENTRY windows <generation> window <id> <application> <title>
REQUEST <sequence> windows ACTIVATE <id>
```

- `id`: this host's own number, never reused. The compositor's identifiers are
  UUIDs and never cross the wire — a stale rail button must not be able to
  switch to whatever window inherited an identifier
- `application`: what owns the window, which is what tells four browser windows
  apart
- `title`: percent-encoded

**Switching is the only request.** Not close, not minimise, not move. The design
is "tap one and switch to it", and anything more is a window manager on a phone.

### Titles are the worst free text in this product

An application name comes from an application. A window title comes from
whatever a web page decided to call itself, and it is the string most directly
under a stranger's control anywhere in this protocol. It is percent-encoded like
every other name, and a title containing a newline cannot become a second line.

### No icons

The compositor offers them as raw 64×64 pixel data — sixteen kilobytes per
window on a line-framed text protocol. Framing that stays inspectable is worth
more than an icon, so the client draws generic glyphs and the application name
carries the identification.

### Recency is the whole point, and it is the part that lies

An ordering that looks plausible and never changes is the failure this domain
invites: it works for an hour and is wrong forever after. Whatever a desktop
offers, establish that the list reorders when a window is actually used, by
using one and watching it move — not by reading that the interface exists.

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
