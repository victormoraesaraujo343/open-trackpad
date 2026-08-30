# OpenTrackpad shortcut recorder

A window that captures one key combination and writes it down, so the closed
vocabulary can be extended by the person sitting at the machine rather than by
anything arriving over the socket.

Opened two ways, both leading here: the tray menu's **Record a shortcut…**, and
the phone asking, which the daemon turns into a spawn of this program. The phone
route is the point rather than a convenience — the design is that the phone asks
and the computer captures, so the keys get pressed where the keys are.

```bash
cargo run
```

It writes `$XDG_CONFIG_HOME/opentrackpad/shortcuts` and exits. The daemon
notices the file changed within a couple of seconds and the new shortcut becomes
fireable without reconnecting.

## What it deliberately cannot do

It opens no input device, injects nothing, and is not trusted: what it writes is
validated by the daemon on load exactly as a hand-edited file is. See the note
in `Cargo.toml` for why that is what makes its dependencies acceptable.

## When it will not behave

Set `OPENTRACKPAD_TRACE=1` and it narrates itself on stderr: whether it started
at all, whether it asked to inhibit the desktop's shortcuts and at which moment,
whether the window ever became active, and what the idle timer believes.

It also reports whether the compositor **honoured** the request, which separates
two failures that otherwise look the same: a desktop that refused inhibition
outright, and one that agreed while another application holds a grab on one
particular chord. Nothing can say *which* chord — a key another client took
never reaches this process, so from in here it is indistinguishable from a key
nobody pressed.

Those are chosen for the failures that look identical from outside the process.
A window that never becomes active explains both a dropped inhibitor and a
focus-loss close that never fires. A timer that is dead and a timer that is
merely wrong produce the same silence.

```bash
OPENTRACKPAD_TRACE=1 WAYLAND_DEBUG=1 opentrackpad-recorder 2>&1 | tee /tmp/rec.log
```

An environment variable rather than a flag, because this program takes no
arguments — which is what makes it safe for the daemon to spawn on a client's
request.

## It must always be escapable

While it is open it asks the desktop to stop taking keyboard shortcuts for
itself, so that `super+shift+s` reaches this window instead of the screenshot
tool. A window that did that and then hung would leave somebody on a desktop
whose shortcuts had stopped working, with no obvious culprit. So it closes on
Escape, on losing focus, and on its own after half a minute with nothing
pressed.
