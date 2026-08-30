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
import contextlib
import os
import re
import socket
import subprocess
import sys
import time
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
BASELINE = os.path.join(HERE, "pixel-baseline")
CURRENT = os.path.join(HERE, "pixel-current")
SERIAL = os.environ.get("OTP_EMULATOR", "emulator-5554")
STANDIN_PORT = 4343

# Artboard units, from the views that draw with them. A rail is exactly
# RAIL_UNITS wide, which is what turns a pixel back into a unit — its height
# depends on the screen's edges and would have to have them subtracted first.
RAIL_UNITS = 78.0
RING_INNER = 46.0
RING_OUTER = 112.0
RING_SIZE = 240.0
RING_MARGIN = 22.0

# ProfileMenuView draws its own rows, so there is no id to aim at and these
# mirror its constants — the same duplication the ring needed, accepted for the
# same reason: every step asserts it arrived, so a drawing that moves stops the
# run instead of quietly photographing the wrong screen.
MENU_WIDTH = 196.0
MENU_MARGIN_SIDE = 26.0
MENU_MARGIN_TOP = 34.0
MENU_PADDING = 8.0
MENU_HEADING = 4.0 + 12.0 + 6.0     # room above, the word, room below
MENU_ROW_HEIGHT = 27.0
MENU_ROW_GAP = 2.0
MENU_RULE = 6.0 * 2                 # the margin either side of the divider

# The one recorded shortcut standin-host.py serves, and the only kind of chip
# that opens the edit screen.
RECORDED = "My shortcut"

# Anything that can be open while the rails are still showing. Each is the root
# id of a panel that covers the pad rather than the screen.
OVER_THE_PAD = {"quick_ring", "audio_panel", "profile_menu", "all_windows_panel"}
PACKAGE = "org.opentrackpad.client.v2"
ACTIVITY = PACKAGE + "/org.opentrackpad.client.MainActivity"

# Screens whose content cannot be held still, excluded by name rather than by a
# tolerance. Each needs looking at by eye after a conversion that touches it.
ANIMATED = {
    "unplugged": "the seeking dot pulses on a 750 ms loop with no way to pause it",
}

# Screens the walk cannot reach yet, named rather than silently absent. The
# whole failure this tool was corrected for was a coverage claim that was not
# true, so what is *not* covered is stated as plainly as what is.
UNREACHED = {
    "mismatch": "needs a host that answers in an older language than the client "
                "speaks, which the stand-in does not yet do",
    "recording": "reached by sending ACTION RECORD, which the stand-in accepts "
                 "and does nothing with — the screen would draw, but a capture "
                 "of it would prove nothing about a recorder that never ran",
}


def adb(*args, binary=False):
    out = subprocess.run(
        ["adb", "-s", SERIAL, *args], capture_output=True, check=False
    )
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def labelled():
    """The live view hierarchy keyed by visible text, for things with no id.

    A library chip has an id shared with every other chip, so the only way to
    aim at one in particular is the word on it. Reading the word is honest;
    guessing an offset from a neighbour is the tap-and-hope this file exists to
    avoid, and I wrote one of those here before noticing.
    """
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    found = {}
    for node in re.finditer(r"<node[^>]*>", xml):
        text = node.group(0)
        label = re.search(r'text="([^"]*)"', text)
        box = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', text)
        if label and box and label.group(1):
            found[label.group(1)] = tuple(int(n) for n in box.groups())
    return found


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
        # Rails alone are not proof of being back: the ring and the audio panel
        # are drawn *over* the pad and leave both rails in the tree. Checking
        # only for rails meant this returned with the ring still open, the next
        # tap closed it instead of opening it, and the tap after that landed on
        # bare surface — which is how nine screens were captured as the pad.
        if (
            "rail_start" in views
            and "rail_end" in views
            and not (OVER_THE_PAD & views.keys())
        ):
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


def back_to(marker, what):
    """Presses back until [marker] is on screen.

    Once is not enough from the naming screen: it opens the keyboard, and the
    first back press closes the keyboard rather than the screen. Counting
    presses would have to know that; looking for where it has arrived does not.
    """
    for _ in range(4):
        if marker in tree():
            return
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.9)
    raise SystemExit("could not get back to %s" % what)


def arrived(marker, what):
    """Refuses to continue unless the screen that should be open is open.

    Every navigation step used to be a tap and a hope. When the taps stopped
    landing, the walk carried on and captured whatever was in front of it — nine
    screens under nine names, all of them the trackpad, all of them matching
    their own baseline. Checking for an id that only the intended screen has is
    what turns a silent wrong answer into a stopped run.
    """
    if marker not in tree():
        raise SystemExit("expected to be on %s and was not (no %s)" % (what, marker))


def capture(name, into):
    print("  %s" % name, flush=True)
    png = adb("exec-out", "screencap", "-p", binary=True)
    with open(os.path.join(into, name + ".png"), "wb") as handle:
        handle.write(png)
    return zlib.crc32(png)


@contextlib.contextmanager
def standin():
    """Runs the stand-in host for the length of the walk, and owns it.

    Started here rather than assumed to be running, because both ways of
    assuming have already gone wrong in one afternoon. First there was no host
    at all and every screen captured the unplugged card. Then a *different*
    stand-in from an earlier experiment held the port — one that served only
    windows — so the import offer never appeared and the audio wedge was dim,
    and nothing said so.

    "Wrong host" is the more dangerous of the two, because a run against it
    still produces plausible screenshots. A baseline captured that way would
    have been wrong and confident.

    So the tool starts its own, on a port it chooses, and stops it afterwards.
    A precondition that is established cannot be assumed wrongly.
    """
    here = os.path.join(HERE, "standin-host.py")
    process = subprocess.Popen(
        [sys.executable, here],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
    )
    try:
        for _ in range(50):
            with socket.socket() as probe:
                if probe.connect_ex(("127.0.0.1", STANDIN_PORT)) == 0:
                    break
            if process.poll() is not None:
                raise SystemExit(
                    "the stand-in would not start:\n"
                    + process.stderr.read().decode("utf-8", "replace")
                )
            time.sleep(0.2)
        else:
            raise SystemExit("the stand-in never came up on %d" % STANDIN_PORT)
        yield
    finally:
        process.terminate()
        with contextlib.suppress(subprocess.TimeoutExpired):
            process.wait(timeout=5)


def settle():
    """Puts the emulator and the app into the state a comparison can rely on.

    The fade is the one that bit. `ScreenCare` lowers the window's brightness
    after twenty seconds without a touch, which darkens the whole framebuffer —
    the ground goes from `0e0f10` to `0b0b0c` and every screen reports as
    moved. It is written into the app's own settings file rather than toggled
    through the interface, because a walk that has to press a switch first can
    fail before it has captured anything.

    This was documented here and not implemented, which is the exact defect
    this project keeps finding in other people's code: a note describing what
    the code does not do. The first baseline was captured with the fade live
    and had to be thrown away.
    """
    # The reverse is not permanent — it does not survive an adb server restart,
    # and losing it is silent: the app shows the unplugged card, every capture
    # is of that card, and the run reports every screen as moved for a reason
    # that has nothing to do with any change. Established here rather than
    # assumed, and checked again after the app is up.
    subprocess.run(
        ["adb", "-s", SERIAL, "reverse", "tcp:4242", "tcp:%d" % STANDIN_PORT],
        capture_output=True,
        check=False,
    )
    adb(
        "shell",
        "run-as",
        PACKAGE,
        "sh",
        "-c",
        # The directory does not exist until the app has saved something, and
        # `>` into a missing directory fails silently as far as this is
        # concerned — which would leave the fade on and every screen reporting
        # as moved for a reason unrelated to any change.
        "mkdir -p files && printf 'setting\\tfade\\tfalse\\n' > files/settings.tsv",
    )
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


def demand_a_host():
    """Stops the run if the app cannot see the stand-in.

    Without this the failure is silent and total: the trouble card covers the
    pad, every screen captures as that card, and the comparison reports
    everything moved. A tool that answers confidently from a broken setup is
    worse than one that does not answer.
    """
    if "trouble_title" in tree():
        raise SystemExit(
            "the app cannot reach a host — is standin-host.py running, and is\n"
            "`adb -s %s reverse tcp:4242 tcp:%d` in place?" % (SERIAL, STANDIN_PORT)
        )


def screens(into):
    """Walks the app, capturing each screen. Returns {name: checksum}."""
    seen = {}
    restart()
    demand_a_host()

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
    rails = views
    ring_x, ring_y = slot(rail, 4, views)

    tap(ring_x, ring_y)
    arrived("quick_ring", "the Quick Ring")
    seen["ring"] = capture("ring", into)
    # Closed again before the loop below, which opens it each time. The slot
    # toggles, so leaving it open meant the loop's first tap shut it and the
    # wedge tap after that landed on the bare pad.
    views = pad()

    # The ring's four destinations, at the quarters. Read from the ring's own
    # bounds rather than assumed, for the same reason as everything else here.
    # Where the ring actually is, which is not the middle of the pad.
    #
    # It sits against the rail that opened it — QuickRingView insets it by a
    # margin plus its own radius from that side — and it is only centred
    # vertically. The first version of this assumed the pad's centre and a reach
    # of 28% of its height, checked neither, and every tap fell on bare surface.
    # That dismisses the ring, so nine screens were captured as the trackpad.
    #
    # These four numbers mirror constants in QuickRingView. Duplication is a
    # real cost and is accepted here only because every step now asserts it
    # arrived: if the ring moves, the next run stops rather than quietly
    # photographing the wrong thing.
    surface = tree().get("pad_holder") or tree()["root"]
    unit = (rails[rail][2] - rails[rail][0]) / RAIL_UNITS
    diameter = min(
        unit * RING_SIZE,
        (surface[2] - surface[0]) - unit * RING_MARGIN * 2,
        (surface[3] - surface[1]) - unit * RING_MARGIN * 2,
    )
    inset = unit * RING_MARGIN + diameter / 2.0
    on_the_right = rail == "rail_end"
    cx = (surface[2] - inset) if on_the_right else (surface[0] + inset)
    cy = (surface[1] + surface[3]) / 2.0
    reach = unit * (RING_INNER + RING_OUTER) / 2.0
    quarters = {
        "audio": (cx, cy - reach),
        "import_wedge": (cx + reach, cy),
        "profiles": (cx, cy + reach),
        "settings": (cx - reach, cy),
    }

    # The editor and the naming screen behind it, reached through the profile
    # menu. Its rows are: the profiles, then a rule, then the ways out — so
    # "Manage profiles" is the row after however many profiles there are.
    def menu_row(index, profiles, surface, unit):
        left = surface[2] - unit * (MENU_MARGIN_SIDE + MENU_WIDTH)
        top = surface[1] + unit * MENU_MARGIN_TOP
        y = top + unit * (MENU_PADDING + MENU_HEADING)
        y += index * unit * (MENU_ROW_HEIGHT + MENU_ROW_GAP)
        if index >= profiles:
            y += unit * MENU_RULE
        return left + unit * MENU_WIDTH / 2.0, y + unit * MENU_ROW_HEIGHT / 2.0

    markers = {
        "audio": "audio_panel",
        "profiles": "profile_menu",
        "settings": "settings_panel",
    }
    for name in ("audio", "profiles", "settings"):
        tap(ring_x, ring_y)          # open the ring
        tap(*quarters[name])         # choose the wedge
        arrived(markers[name], name)
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
        if name == "profiles":
            # Three profiles ship by default, so "Manage profiles" is row three.
            tap(*menu_row(3, profiles=3, surface=surface, unit=unit))
            arrived("editor_library", "the profile editor")
            seen["editor"] = capture("editor", into)

            box = tree()["editor_duplicate"]
            tap((box[0] + box[2]) / 2.0, (box[1] + box[3]) / 2.0)
            arrived("name_field", "the naming screen")
            seen["naming"] = capture("naming", into)

            # Back to the editor, then the one chip that opens the edit screen.
            # Only a recorded shortcut can be renamed or deleted, and the
            # stand-in serves exactly one — aimed at by its word, since every
            # chip shares the same id.
            back_to("editor_library", "the profile editor")
            chip = labelled().get(RECORDED)
            if chip is None:
                raise SystemExit("no chip named %r to open the edit screen" % RECORDED)
            tap((chip[0] + chip[2]) / 2.0, (chip[1] + chip[3]) / 2.0)
            arrived("edit_name", "the edit-shortcut screen")
            seen["edit_shortcut"] = capture("edit_shortcut", into)

        views = pad()

    # All windows: slot five of the far rail, which is the windows rail.
    far = "rail_start" if rail == "rail_end" else "rail_end"
    tap(*slot(far, 4, views))
    arrived("all_windows_grid", "all windows")
    seen["all_windows"] = capture("all_windows", into)
    tap(*slot(far, 4, views))

    return seen


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "check"
    into = BASELINE if mode == "baseline" else CURRENT
    # Emptied first. A file left from a previous run is not evidence about this
    # one, and one that is no longer captured at all sits there looking like it
    # is — which showed up immediately as an eleventh image in a ten-screen run.
    if os.path.isdir(into):
        for stale in os.listdir(into):
            os.remove(os.path.join(into, stale))
    os.makedirs(into, exist_ok=True)
    with standin():
        settle()
        seen = screens(into)

    duplicates = {}
    for name, sum_ in seen.items():
        duplicates.setdefault(sum_, []).append(name)
    repeated = [names for names in duplicates.values() if len(names) > 1]
    if repeated:
        # The failure that ran for an hour undetected: every capture matched its
        # own baseline because nine of them were the same screen under nine
        # names. Comparing each against its own past self cannot see that. Only
        # comparing them against each other can.
        print("these captures are identical to each other, so the walk did not")
        print("reach the screens it named:")
        for names in repeated:
            print("  " + ", ".join(sorted(names)))
        return 2

    if mode == "baseline":
        print("captured %d screens into %s" % (len(seen), os.path.basename(into)))
        for name in sorted(seen):
            print("  %s" % name)
        for name, why in ANIMATED.items():
            print("  (by eye) %s — %s" % (name, why))
        for name, why in UNREACHED.items():
            print("  (not reached) %s — %s" % (name, why))
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
    print()
    for name, why in ANIMATED.items():
        print("by eye   %s — %s" % (name, why))
    for name, why in UNREACHED.items():
        print("not seen %s — %s" % (name, why))
    return 1 if moved else 0


if __name__ == "__main__":
    raise SystemExit(main())
