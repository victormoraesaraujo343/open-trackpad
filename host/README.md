# Linux host daemon

`opentrackpadd` receives OTP/1 contact snapshots on a loopback socket and
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
| `--print-events` | Also print every pad event while a client is connected. |

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

If your distribution does not do that, add the user to the group owning
`/dev/uinput` or ship a udev rule. Do **not** `chmod 666 /dev/uinput`: that hands
every process on the machine the ability to synthesise input.

## Layout

| Module | Responsibility |
| --- | --- |
| `protocol` | Parsing OTP/1 lines and per-connection validation. |
| `state` | Pointer IDs to multi-touch slots, and what changed since the last frame. |
| `pad` | Touchpad geometry and the device-agnostic event vocabulary. |
| `uinput` | The only module that knows about Linux input events. |
| `sink` | Where pad events go: the real device, or the terminal. |
| `selftest` | Synthetic contact sequences for validating without a phone. |

Everything except `uinput` is testable without a device, which is why the tests
run anywhere.

## Security

The daemon injects input into your desktop. It binds loopback only, has no
authentication, and must not be exposed to a network interface. Any wireless
transport added later has to authenticate before it may inject anything.
