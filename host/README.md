# Linux host daemon

`opentrackpadd` receives OTP/2 contact snapshots on a loopback socket and
replays them on a virtual multi-touch touchpad created through `/dev/uinput`.

```bash
cargo test              # parser, session rules, contact state, event encoding
cargo run -- --self-test  # replay synthetic contacts on the real device
cargo run               # listen on 127.0.0.1:4242
```

## Options

| Option | Effect |
| --- | --- |
| `ADDRESS` | Loopback address to listen on. Default `127.0.0.1:4242`. |
| `--self-test` | Replay a scripted contact sequence and exit, without listening. |
| `--dry-run` | Do not create a virtual device; print pad events instead. |
| `--soak MINUTES` | Replay them on a loop for that long, then report stuck contacts and memory growth. |
| `--print-events` | Also print every pad event while a client is connected. |
| `--trace-timing` | Log how frames are spaced, on the phone and on arrival. |

## Validating

```bash
sudo ../scripts/validate-touchpad.sh
```

Injects synthetic one-, two-, three- and four-finger strokes and reports what
libinput made of them. See [testing and validation](../docs/TESTING.md).

## Permissions

The daemon needs write access to `/dev/uinput`. It does not need root, and it
should not be given root.

On a normal desktop session, systemd-logind already grants the seat's active
user an ACL on `/dev/uinput`, so nothing needs configuring. Check with:

```bash
getfacl /dev/uinput
```

If your own user does not appear there with `rw`, install the udev rule:

```bash
sudo cp ../packaging/60-opentrackpad-uinput.rules /etc/udev/rules.d/
sudo udevadm control --reload-rules
sudo udevadm trigger --name-match=uinput
```

It tags the device for `uaccess`, which asks logind to grant an ACL to whoever
is logged in at the local seat and to withdraw it at logout. Do **not**
`chmod 666 /dev/uinput`: that hands every process on the machine the ability to
synthesise input, permanently.

## Running it as a service

```bash
cargo build --release
install -Dm755 target/release/opentrackpadd ~/.local/bin/opentrackpadd
install -Dm644 ../packaging/opentrackpad.service \
  ~/.config/systemd/user/opentrackpad.service
systemctl --user daemon-reload
systemctl --user enable --now opentrackpad.service
```

A user service rather than a system one, deliberately: the daemon injects input
into one person's desktop session and should live and die with it. Logs go to
the journal:

```bash
journalctl --user -u opentrackpad.service -f
```

## Checking stability

```bash
cargo run -- --soak 30
```

Replays synthetic contacts on a loop for thirty minutes and reports stuck
contacts and memory growth at the end. It moves the pointer the whole time, so
run it when you are away from the machine.

## Layout

| Module | Responsibility |
| --- | --- |
| `protocol` | Parsing OTP/2 lines and per-connection validation. |
| `state` | Pointer IDs to multi-touch slots, and what changed since the last frame. |
| `pad` | Touchpad geometry and the device-agnostic event vocabulary. |
| `uinput` | The only module that knows about Linux input events. |
| `sink` | Where pad events go: the real device, or the terminal. |
| `selftest` | Synthetic contact sequences for validating without a phone. |
| `timing` | Whether frames arrive spaced out enough for acceleration to work. |

Everything except `uinput` is testable without a device, which is why the tests
run anywhere.

## Security

The daemon injects input into your desktop. It binds loopback only, has no
authentication, and must not be exposed to a network interface. Any wireless
transport added later has to authenticate before it may inject anything.
