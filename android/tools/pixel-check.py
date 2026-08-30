#!/usr/bin/env python3
"""Proves the default identity did not move.

The second visual identity is built one view at a time, and after each one the
current look must be **identical** to what it was before that view was touched.
Not similar. Identical.

## Why identical and not close enough

A tolerance wide enough to absorb a pulsing dot is wide enough to absorb a
shadow that moved two pixels, and nothing would ever say which it had absorbed.
Worse, a check that reports a difference nobody caused teaches everyone to
ignore the next one that somebody did — **a flaky check is worse than no
check**, because it spends the credibility that makes a real failure land.

So the pixels must match exactly, and anything that genuinely cannot hold still
is excluded **by name**, with a line saying why, and looked at by eye. A named
exception is honest. A threshold quietly stops testing.

## What is made deterministic, and how

- the fade-when-idle setting is turned off, or the surface dims between captures
- window and transition animations are off on the emulator
- the host is `standin-host.py`, which always says the same thing
- coordinates are read from the live view tree rather than written down, because
  a coordinate measured from one build and reused after a layout change is a
  stale number, and that has already cost this project two wrong diagnoses

Usage:
    pixel-check.py baseline      capture the reference set
    pixel-check.py check         capture again and compare
"""
import os
import re
import subprocess
import sys
import time
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
BASELINE = os.path.join(HERE, "pixel-baseline")
CURRENT = os.path.join(HERE, "pixel-current")
SERIAL = os.environ.get("OTP_EMULATOR", "emulator-5554")
PACKAGE = "org.opentrackpad.client.v2"
ACTIVITY = PACKAGE + "/org.opentrackpad.client.MainActivity"

# Screens whose content cannot be held still, excluded by name rather than by a
# tolerance. Each needs looking at by eye after a conversion that touches it.
ANIMATED = {
    "unplugged": "the seeking dot pulses on a 750 ms loop with no way to pause it",
}


def adb(*args, binary=False):
    out = subprocess.run(
        ["adb", "-s", SERIAL, *args], capture_output=True, check=False
    )
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def tree():
    """The live view hierarchy, as {resource-id: (left, top, right, bottom)}."""
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    found = {}
    for node in re.finditer(r"<node[^>]*>", xml):
        text = node.group(0)
        rid = re.search(r'resource-id="([^"]*)"', text)
        box = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', text)
        if rid and box and rid.group(1):
            found[rid.group(1).split("/")[-1]] = tuple(int(n) for n in box.groups())
    return found


def tap(x, y, settle=1.2):
    adb("shell", "input", "tap", str(int(x)), str(int(y)))
    time.sleep(settle)


def pad():
    """Returns to the trackpad, however many screens deep we are.

    Presses back until the rails are visible again rather than counting the
    screens on the way in. Counting is how a walk drifts: one screen that does
    not open leaves every later step one level off, and the failure appears
    somewhere unrelated.
    """
    for _ in range(8):
        views = tree()
        if "rail_start" in views and "rail_end" in views:
            return views
        # Back from the trackpad itself leaves the app, and then no amount of
        # further backs brings the rails home. Relaunching is the only way out
        # of that, and pressing one too many is easy to do when the walk cannot
        # see how deep it is.
        if PACKAGE not in adb("shell", "dumpsys", "activity", "activities"):
            restart()
            continue
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.8)
    raise SystemExit("could not get back to the trackpad")


def slot(rail, index, views):
    """The centre of slot [index] on [rail], derived rather than remembered."""
    left, top, right, bottom = views[rail]
    height = (bottom - top) / 5.0
    return (left + right) / 2.0, top + height * (index + 0.5)


def capture(name, into):
    print("  %s" % name, flush=True)
    png = adb("exec-out", "screencap", "-p", binary=True)
    with open(os.path.join(into, name + ".png"), "wb") as handle:
        handle.write(png)
    return zlib.crc32(png)


def settle():
    """Puts the emulator and the app into the state a comparison can rely on."""
    for key, value in (
        ("window_animation_scale", "0"),
        ("transition_animation_scale", "0"),
        ("animator_duration_scale", "0"),
    ):
        adb("shell", "settings", "put", "global", key, value)
    adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    adb("shell", "settings", "put", "system", "user_rotation", "1")


def restart():
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-n", ACTIVITY)
    time.sleep(4.5)


def screens(into):
    """Walks the app, capturing each screen. Returns {name: checksum}."""
    seen = {}
    restart()

    # The import offer opens by itself on a fresh session, which makes it the
    # one screen reached by arriving rather than by navigating.
    views = tree()
    if "import_panel" in views:
        seen["import"] = capture("import", into)
        header = views["import_panel"]
        tap(header[2] - (header[2] - header[0]) * 0.12, header[1] + 40)
        time.sleep(1.0)

    views = pad()
    seen["main"] = capture("main", into)

    # Everything else hangs off the Quick Ring, which is slot five of the
    # shortcut rail — whichever side that is today.
    rail = "rail_end" if "rail_end" in views else "rail_start"
    ring_x, ring_y = slot(rail, 4, views)

    tap(ring_x, ring_y)
    seen["ring"] = capture("ring", into)

    # The ring's four destinations, at the quarters. Read from the ring's own
    # bounds rather than assumed, for the same reason as everything else here.
    surface = tree().get("pad_holder") or tree()["root"]
    cx, cy = (surface[0] + surface[2]) / 2.0, (surface[1] + surface[3]) / 2.0
    reach = (surface[3] - surface[1]) * 0.28
    quarters = {
        "audio": (cx, cy - reach),
        "import_wedge": (cx + reach, cy),
        "profiles": (cx, cy + reach),
        "settings": (cx - reach, cy),
    }

    for name in ("audio", "profiles", "settings"):
        tap(ring_x, ring_y)          # open the ring
        tap(*quarters[name])         # choose the wedge
        seen[name] = capture(name, into)
        if name == "audio":
            # The audio rail's four pages, which replace the far rail while it
            # is open. Slot one is Close, so pages are two through five.
            views = tree()
            far = "rail_start" if rail == "rail_end" else "rail_end"
            for page, index in (("audio_input", 2), ("audio_apps", 3), ("audio_settings", 4)):
                tap(*slot(far, index, views))
                seen[page] = capture(page, into)
            tap(*slot(far, 0, views))   # Close
        elif name == "profiles":
            # The editor and the naming screen live behind the profile menu.
            rows = tree()
            panel = rows.get("profile_menu") or rows["root"]
            tap((panel[0] + panel[2]) / 2.0, panel[1] + (panel[3] - panel[1]) * 0.62)
            seen["editor"] = capture("editor", into)
            views = tree()
            if "editor_duplicate" in views:
                box = views["editor_duplicate"]
                tap((box[0] + box[2]) / 2.0, (box[1] + box[3]) / 2.0)
                seen["naming"] = capture("naming", into)
        views = pad()

    # All windows: slot five of the far rail, which is the windows rail.
    far = "rail_start" if rail == "rail_end" else "rail_end"
    tap(*slot(far, 4, views))
    seen["all_windows"] = capture("all_windows", into)
    tap(*slot(far, 4, views))

    return seen


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "check"
    into = BASELINE if mode == "baseline" else CURRENT
    os.makedirs(into, exist_ok=True)
    settle()
    seen = screens(into)

    if mode == "baseline":
        print("captured %d screens into %s" % (len(seen), os.path.basename(into)))
        for name in sorted(seen):
            print("  %s" % name)
        for name, why in ANIMATED.items():
            print("  (not captured) %s — %s" % (name, why))
        return 0

    missing, moved = [], []
    for name in sorted(seen):
        reference = os.path.join(BASELINE, name + ".png")
        if not os.path.exists(reference):
            missing.append(name)
            continue
        with open(reference, "rb") as handle:
            if zlib.crc32(handle.read()) != seen[name]:
                moved.append(name)

    for name in missing:
        print("NEW      %s — no baseline to compare against" % name)
    for name in moved:
        print("MOVED    %s — pixels differ from the baseline" % name)
    for name in sorted(set(seen) - set(moved) - set(missing)):
        print("same     %s" % name)
    if ANIMATED:
        print()
        for name, why in ANIMATED.items():
            print("by eye   %s — %s" % (name, why))
    return 1 if moved else 0


if __name__ == "__main__":
    raise SystemExit(main())
