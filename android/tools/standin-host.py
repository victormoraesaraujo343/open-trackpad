#!/usr/bin/env python3
"""A host that always says the same thing, so a screenshot means something.

Not a mock of the daemon and not a test of it: dc's host is the authority on
what is true, and this is a fixed recording of one plausible answer. It exists
because a screen cannot be compared with itself unless what fills it is
identical both times, and a real desktop changes — a window opens, a volume
moves, and two captures differ for reasons that have nothing to do with the
change being checked.

It also means the emulator never needs the real daemon. Victor's phone holds
that session whenever he is at his desk, so an emulator wanting the real host
would be a choice between checking the work and leaving him alone.

Speaks the lines in docs/PROTOCOL.md and nothing more. If it ever disagrees
with the daemon, the daemon is right.
"""
import socket
import sys
import threading

PORT = 4343


def escape(text):
    """Percent-encoding, per byte, as every name on this wire is written."""
    out = []
    for b in text.encode("utf-8"):
        out.append(chr(b) if 0x21 <= b <= 0x7E and b != ord("%") else "%%%02X" % b)
    return "".join(out)


# Fixed data. Chosen to exercise the awkward cases rather than the pretty ones:
# a name with a space, a name that runs together, an acronym, a long title, a
# device at boosted volume, a muted stream.
WINDOWS = [
    ("Warp Preview", "~/ShipStudio/open-trackpad - fish"),
    ("SystemSettings", "Power Management - System Settings"),
    ("VLCMediaPlayer", "Nothing playing"),
    ("Firefox", "OpenTrackpad - Painel - Mozilla Firefox"),
    ("steam", "Steam"),
    ("Dolphin", "Screenshots - Dolphin"),
]

# kind id volume muted default target port paused name
AUDIO = [
    ("sink", 1, 1000, 0, 1, "-", "Line Out", "-", "Built-in Audio"),
    ("sink", 2, 1350, 0, 0, "-", "HDMI", "-", "Built-in Audio"),
    ("source", 3, 800, 1, 1, "-", "Front Mic", "-", "Built-in Audio"),
    ("stream", 4, 650, 0, "-", "1", "-", 0, "Firefox"),
    ("stream", 5, 1000, 1, "-", "1", "-", 1, "VLC"),
]

SHORTCUTS = [
    (1, "ctrl+c", "convention", "text", "Copy"),
    (2, "ctrl+v", "convention", "text", "Paste"),
    (3, "super", "imported", "desktop", "Overview"),
    (4, "ctrl+alt+p", "recorded", "-", "My shortcut"),
]

CANDIDATES = [
    (10, "super+alt+k", "keyboard", 1, "Switch to Next Keyboard Layout"),
    (11, "ctrl+alt+t", "terminal", 1, "Open Terminal"),
    (12, "super+d", "desktop", 0, "Show Desktop"),
]


def serve(conn, granted):
    f = conn.makefile("rw", buffering=1, newline="\n")
    hello = (f.readline() or "").strip()
    if not hello.startswith("HELLO OTP/4"):
        # A version 3 client gets no capability field, as the protocol says.
        conn.close()
        return
    f.write("WELCOME OTP/4 %s\n" % (granted or "-"))

    if "windows" in granted:
        f.write("SNAPSHOT windows 1 %d\n" % len(WINDOWS))
        for i, (app, title) in enumerate(WINDOWS, start=1):
            f.write("ENTRY windows 1 window %d %s %s\n" % (i, escape(app), escape(title)))

    if "audio" in granted:
        f.write("SNAPSHOT audio 1 %d\n" % len(AUDIO))
        for kind, ident, vol, muted, default, target, port, paused, name in AUDIO:
            f.write(
                "ENTRY audio 1 %s %d %d %d %s %s %s %s %s\n"
                % (kind, ident, vol, muted, default, target, escape(port), paused, escape(name))
            )

    if "shortcuts" in granted:
        f.write("SNAPSHOT shortcuts 1 %d\n" % len(SHORTCUTS))
        for ident, chord, origin, group, name in SHORTCUTS:
            f.write(
                "ENTRY shortcuts 1 shortcut %d %s %s %s %s\n"
                % (ident, chord, origin, group, escape(name))
            )

    if "import" in granted:
        f.write("SNAPSHOT import 1 %d\n" % len(CANDIDATES))
        for ident, chord, group, recommended, name in CANDIDATES:
            f.write(
                "ENTRY import 1 candidate %d %s %s %d %s\n"
                % (ident, chord, group, recommended, escape(name))
            )

    f.flush()
    # Read and drop whatever the client sends. Nothing is acknowledged, which is
    # what the real host does too.
    try:
        for _ in f:
            pass
    except OSError:
        pass


def main():
    granted = sys.argv[1] if len(sys.argv) > 1 else "audio,shortcuts,import,windows"
    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", PORT))
    server.listen(4)
    print("stand-in on %d, granting: %s" % (PORT, granted), flush=True)
    while True:
        conn, _ = server.accept()
        threading.Thread(target=serve, args=(conn, granted), daemon=True).start()


if __name__ == "__main__":
    main()
