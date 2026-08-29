# OpenTrackpad

Turn an old Android device into a dedicated, native multi-touch trackpad for Linux.

OpenTrackpad is an early-stage open-source project. Unlike remote-mouse apps, its goal is to send raw touch contacts from Android to a Linux host and expose them through `uinput`, so `libinput` and the desktop environment can handle gestures natively.

> [!IMPORTANT]
> The host daemon creates a virtual multi-touch touchpad through `uinput`. On the one machine tested so far, one finger moves the pointer, two scroll, and three or four produce native swipe gestures. Long-run stability is unverified, only one distribution has been tested, and the Android client is not implemented. See [docs/TESTING.md](docs/TESTING.md) for exactly what has and has not been proven.

## Why

Apps such as KDE Connect and Bluetooth HID remotes translate gestures into mouse movement, scrolling, or keyboard shortcuts. That is useful, but Linux still sees a mouse. OpenTrackpad aims to make Linux see a real multi-touch touchpad.

## Planned experience

- Edge-to-edge, distraction-free Android touch surface
- Native two-, three-, and four-finger Linux gestures
- USB-first transport for low latency, stability, and continuous charging
- Bluetooth transport as a later fallback
- Kiosk mode, auto-connect, screen dimming, and burn-in protection
- Configurable acceleration, tap-to-click, palm rejection, and orientation

## Architecture

```text
Android MotionEvent contacts
          |
          | USB + adb reverse (first transport)
          v
OpenTrackpad host daemon
          |
          | /dev/uinput
          v
Linux input subsystem -> libinput -> GNOME/KDE gestures
```

See [Architecture](docs/ARCHITECTURE.md), [wire protocol](docs/PROTOCOL.md), and [roadmap](docs/ROADMAP.md).

## Try the host daemon

Until the Android client exists, the daemon can prove itself against a scripted
sequence of one, two, three and four contacts. The pointer will move on its own
for a few seconds:

```bash
cd host
cargo run -- --self-test
```

Add `--dry-run` to watch what it decides without creating a device at all.

To run it as a daemon and drive it by hand:

```bash
cd host
cargo run
```

In another terminal:

```bash
printf 'HELLO OTP/1 1080 2400 10\nFRAME 1 1000000 1 0 500 800 700 12\n' | socat - TCP:127.0.0.1:4242
```

Run the tests with:

```bash
cd host
cargo test
```

## Validating on your machine

OpenTrackpad aims to work on any Linux running libinput. To check whether it
does on yours:

```bash
sudo ./scripts/validate-touchpad.sh
```

It injects synthetic one-, two-, three- and four-finger strokes and reports
whether libinput turned them into motion, scrolling and gestures — ending in
PASS or FAIL rather than asking you to watch the cursor. Results from new
distributions and desktops are genuinely useful; see
[testing and validation](docs/TESTING.md).

## Target platforms

- Host: any Linux with `uinput` and `libinput`. Nothing in the design is
  distribution-specific; the desktop only has to run libinput, which every
  mainstream GNOME, KDE, X11 and Wayland session does.
- Client: any Android 9 or newer.

## Contributing

The project is at the prototype stage. See [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

## License

[MIT](LICENSE)
