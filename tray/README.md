# Tray indicator

An optional system tray icon showing whether a phone is connected, with entries
to stop and start OpenTrackpad without a terminal.

Entirely optional. The trackpad works whether or not this is running, and
nothing here can affect input: it reads a status file and calls `systemctl`.

```bash
cargo build --release
install -Dm755 target/release/opentrackpad-tray ~/.local/bin/opentrackpad-tray
install -Dm644 ../packaging/opentrackpad-tray.service \
  ~/.config/systemd/user/opentrackpad-tray.service
systemctl --user daemon-reload
systemctl --user enable --now opentrackpad-tray.service
```

If the icon does not appear, look under the panel's hidden-icons arrow: desktops
commonly hide unfamiliar tray items by default.

## How it knows

The daemon writes one line to `$XDG_RUNTIME_DIR/opentrackpad/state` whenever its
state changes, and removes it on exit — so an absent file means "not running".
The tray reads that file once a second.

A file rather than a socket or a D-Bus service, deliberately. The daemon injects
input into the desktop; giving it a second thing to listen on so an icon can
change colour is not a trade worth making. The two programs share no code, only
the format of that one line, which is why this is a separate crate: nothing that
injects input has to carry a D-Bus stack.

## The icon

Drawn in code from signed distance functions, at every size a panel is likely to
ask for, rather than shipped as bitmaps. It is the same trackpad-with-two-contacts
mark as the Android launcher icon, so both ends of the project look like one
thing. Lime while there is something to represent, grey once there is not.
