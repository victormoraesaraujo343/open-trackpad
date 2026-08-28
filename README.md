# OpenTrackpad

Turn an old Android device into a dedicated, native multi-touch trackpad for Linux.

OpenTrackpad is an early-stage open-source project. Unlike remote-mouse apps, its goal is to send raw touch contacts from Android to a Linux host and expose them through `uinput`, so `libinput` and the desktop environment can handle gestures natively.

> [!IMPORTANT]
> The repository currently contains the architecture, protocol draft, and a tested host-side protocol receiver. It does **not yet create a virtual trackpad** and the Android client is not implemented yet.

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

## Try the protocol receiver

The current receiver validates and prints frames; it is useful for developing the Android sender before `uinput` lands.

```bash
cd host
cargo run
```

In another terminal:

```bash
printf 'HELLO OTP/1 1080 2400 10\nFRAME 1 1000000 1 0 500 800 700 12\n' | nc 127.0.0.1 4242
```

Run the tests with:

```bash
cd host
cargo test
```

## Target platforms

- Host: Linux with `uinput` and `libinput`; initial validation target is Zorin OS 18
- Client: Android 9 or newer initially; the exact minimum may change after hardware testing

## Contributing

The project is at the prototype stage. See [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

## License

[MIT](LICENSE)
