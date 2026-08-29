# Testing and validation

## What is proven, and what is not

Emitting touch events is not the same as being a touchpad. This file records
what has actually been observed, so nobody has to guess which claims are backed
by a real machine.

| Claim | Status |
| --- | --- |
| Parser and state machine behave as specified | proven by `cargo test` |
| The daemon creates a `uinput` device | observed |
| udev classifies the device as a touchpad | observed |
| libinput classifies the device as a touchpad | observed |
| Injected contacts move the desktop pointer | observed |
| Two-finger scrolling reaches the desktop | observed |
| Three- and four-finger swipe gestures reach the desktop | observed |
| Pinch zoom is delivered continuously, not in steps | observed |
| Runs as a systemd user service | observed |
| Plugging the phone in and opening the app is enough, with no terminal | observed |
| The tray indicator registers with the desktop and tracks state | observed |
| The Android client builds and its unit tests pass | proven by `./gradlew testDebugUnitTest` |
| The Android client works against the host on real hardware | observed |
| 30 minutes of use leaves no stuck contact | **not yet verified** |

## Automated tests

```bash
cd host
cargo test
```

These cover the protocol parser, the session rules (handshake order, sequence
monotonicity, touch bounds), the contact/slot state machine, and the encoding of
pad events into Linux input events. They do not touch `/dev/uinput`, so they run
anywhere.

## Self-test against the real device

Replays a scripted sequence of one, two, three and four contacts — including a
recycled pointer ID, both surface corners, and a disconnect with fingers still
down — through the real virtual touchpad:

```bash
cd host
cargo run -- --self-test
```

The pointer will move on its own for a few seconds. Two details in the timing
are deliberate, and both were learned the hard way:

- It pauses two seconds after creating the device. A compositor that has not
  finished its udev hotplug simply discards the events, so without the pause the
  test proves nothing and looks like a failure.
- Every stroke is long and smooth rather than a few frames. libinput builds up a
  short motion history before it emits any pointer motion, and a touch lasting a
  handful of frames reads as a tap rather than a drag. A short sequence produces
  no visible cursor movement even when the device is perfect.

To watch the decisions without creating a device at all:

```bash
cargo run -- --dry-run --self-test
```

## Validating on a new machine

OpenTrackpad targets any Linux running libinput, so "it works here" is not an
answer. This script settles it on whatever machine you run it on:

```bash
sudo ./scripts/validate-touchpad.sh
```

It starts the daemon, watches its device with libinput, injects synthetic one-,
two-, three- and four-finger strokes through the protocol socket, and reports
whether each one survived — ending in PASS or FAIL with libinput's own reasoning
when something is dropped. It needs the libinput debug utilities (Arch:
`pacman -S libinput-tools`) and a built daemon (`cd host && cargo build`).

Please add the result to the observed-results section below, along with your
distribution, kernel and desktop. That table is the only evidence the project
has that it is not accidentally KDE-specific.

## Checking stability

The acceptance criterion is that half an hour of use causes no stuck touch,
crash or leak:

```bash
cd host
cargo run -- --soak 30
```

It replays contacts on a loop and reports stuck contacts and resident memory at
the end. The pointer moves the whole time, so run it while you are away from the
machine.

## Doing it by hand

The script automates the checks below; run them individually when you need to
see more than PASS or FAIL.

> [!NOTE]
> `libinput list-devices` needs root to open `/dev/input/*`. Without `sudo` it
> silently reports nothing at all, which looks exactly like a rejected device.
> Count its output before concluding anything: zero devices means it could not
> read them, not that yours is broken.

On a KDE Plasma Wayland session there is a check that needs no root at all,
because KWin publishes every device libinput handed it over D-Bus:

```bash
busctl --user introspect org.kde.KWin /org/kde/KWin/InputDevice/eventN
```

`touchpad` must be `true`. If the object does not exist, libinput never accepted
the device.

Start the daemon and leave it running:

```bash
cd host
cargo run
```

Then, in another terminal:

```bash
# 1. Does libinput see it, and as what?
libinput list-devices | grep -A12 OpenTrackpad

# 2. What did udev decide?
udevadm info /dev/input/eventN | grep ID_INPUT

# 3. What does libinput make of the events? Run this, then run --self-test.
sudo libinput debug-events --show-keycodes | grep -i opentrackpad
```

`libinput list-devices` must report the device with `Capabilities: pointer
gesture` and a `Tap-to-click` entry. A device that shows up only as a pointer,
or that libinput refuses entirely, means the capability set is wrong — fix that
before working on the Android client.

## Observed results

### 2026-08-29 — CachyOS, kernel 7.2.0-1-cachyos, KDE Plasma on Wayland, libinput 1.31.3

`cargo test`:  39 passed.

The daemon creates `/dev/input/event28`, named `OpenTrackpad Touchpad`.

`/proc/bus/input/devices`:

```text
I: Bus=0006 Vendor=1d6b Product=0f0f Version=0001
N: Name="OpenTrackpad Touchpad"
S: Sysfs=/devices/virtual/input/input29
H: Handlers=event28 mouse2
B: PROP=5
B: EV=b
B: KEY=e520 10000 0 0 0 0
B: ABS=661800001000003
```

`PROP=5` is `INPUT_PROP_POINTER | INPUT_PROP_BUTTONPAD`, as intended.

`udevadm info /dev/input/event28`:

```text
E: ID_INPUT=1
E: ID_INPUT_TOUCHPAD=1
E: ID_INPUT_WIDTH_MM=140
E: ID_INPUT_HEIGHT_MM=63
```

udev classifies it as a touchpad, and derives the physical size correctly from
the declared axis resolution.

KWin's view of the device, over D-Bus, with no root needed:

```text
.name                    "OpenTrackpad Touchpad"
.touchpad                true
.pointer                 true
.keyboard                false
.touch                   false
.enabled                 true
.tapToClick              true
.tapFingerCount          3
.scrollTwoFinger         true
.supportsClickMethodClickfinger  true
.supportsDisableWhileTyping      true
```

libinput accepts the device and treats it as a touchpad with tap-to-click,
three-finger tap and two-finger scrolling. This is the milestone gate, and it
passes on this host.

The same device, created by the daemon running under
`packaging/opentrackpad.service` with its full sandbox applied, is accepted
identically: the hardening does not block `/dev/uinput`.

### The palm-detection trap

The first working device produced no pointer motion at all, despite passing
every classification check above. `libinput record` showed all 195 evdev events
arriving intact, and `libinput debug-events --verbose` explained why nothing came
out:

```text
event28 - using pressure-based touch detection (102:123)
event28 - palm: pressure threshold is 130
event28 - palm: touch 0 (TOUCH_BEGIN), palm detected (pressure)
```

With `ABS_MT_PRESSURE` declared over 0..1024, libinput put touch detection at
123 and palm rejection at 130 — a seven-unit window. Contacts reporting 600 were
discarded as a resting palm before they could become motion. Removing the
pressure axes fixed it; see the architecture notes for the full reasoning.

The lesson generalises: a device that udev, libinput and the compositor all
accept can still discard every event. Only `debug-events --verbose` shows the
decision, and it is worth reaching for early rather than last.

### Why frame timing decides whether acceleration works

Pointer acceleration is computed from velocity, which is distance over time. A
host that receives correct positions with useless timing cannot accelerate, and
the result feels linear and jittery while every event in the stream looks fine.

The client first sent the batched historical samples inside each Android
`MotionEvent`, which looks like better fidelity. Those samples describe
different moments but reach the host in the same instant, so libinput saw
several positions with no time between them, could not estimate speed, and fell
back to roughly one-to-one movement.

Sending one sample per event fixed it. `--trace-timing` shows the difference by
comparing how far apart the phone says frames were with how far apart they
actually arrived:

```text
phone  11.15 ms   arrived  11.15 ms   contacts 1
phone  11.15 ms   arrived  11.13 ms   contacts 1
phone  11.15 ms   arrived  11.19 ms   contacts 1
```

Healthy input has the two columns matching. Frames arriving within a
millisecond of each other, while the phone claims several milliseconds passed,
mean acceleration is being starved. Zero-contact frames are exempt: a lift has
no velocity to estimate.

### First run against a real phone

A Nothing Phone (2412x1080, Android 16) over `adb reverse`, on the same host.
One finger moved the pointer, two scrolled, and three and four produced swipe
gestures — all through the ordinary Linux touchpad stack, with no gesture
interpretation on the phone.

Two things came out of that first run:

- The pointer felt slow. The virtual pad had been hardcoded to 140x63 mm while
  the phone measures 155x69 mm, and the remaining difference is the desktop's
  own pointer speed setting. The handshake now carries the phone's real physical
  size, which is the only way this can work across devices.
- Two-finger horizontal scrolling felt backwards. That is the desktop's natural
  scrolling preference, not a bug: KDE defaults it off, macOS defaults it on.

### Gesture output, measured

`sudo ./scripts/validate-touchpad.sh`:

```text
evdev events the kernel saw        1807
pointer motion (one finger)          62
scroll (two fingers)                 39
swipe gestures (three, four)         78
pinch updates (zoom)                 86
contacts discarded as palms           0
PASS: pointer motion works.
```

The pinch test spans 90 frames and libinput produced 86 updates from it, which
is about one per frame. Zoom that feels stepped in an application is therefore
the application converting a continuous gesture into discrete steps, not
anything this project can fix: browsers commonly zoom a page in fixed
increments regardless of how far the fingers travelled.

### Synthetic validation

`sudo ./scripts/validate-touchpad.sh`, after removing the pressure axes:

```text
evdev events the kernel saw        1233
pointer motion (one finger)          62
scroll (two fingers)                 39
swipe gestures (three, four)         78
contacts discarded as palms           0
PASS: pointer motion works.
```

One finger moves the pointer, two scroll, and three or four produce swipe
gestures — through the ordinary Linux touchpad stack, with no gesture
interpretation on the sending side. That is the milestone gate, and it passes.

**Still open on this host:** whether the device survives 30 minutes of real use.
And only a second distribution and desktop can show that nothing here is
KDE-specific.

## Recording a test session

When reporting results, record:

- phone model and Android version
- distribution and version
- kernel version (`uname -r`)
- libinput version (`libinput --version`)
- desktop environment and session type (`echo $XDG_CURRENT_DESKTOP $XDG_SESSION_TYPE`)
- connection method
- known issues
