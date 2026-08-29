#!/usr/bin/env bash
#
# Proves — or disproves — that the virtual touchpad works on this machine.
#
#   sudo ./scripts/validate-touchpad.sh
#
# Starts the daemon, watches its devices with libinput, feeds it synthetic one-,
# two-, three- and four-finger strokes over the protocol socket, then a click on
# each mouse button, and reports what libinput made of each one.
#
# The clicks are checked separately because they are a separate device: the
# buttons cannot live on the touchpad, which libinput re-resolves from finger
# count, so they have one of their own. A click is also a press and a release
# with nothing between them, and this project has measured events sent that
# close together collapsing into one, so it is worth proving rather than
# assuming.
#
# Root is required because libinput must open /dev/input/*. Without it,
# `libinput list-devices` reports nothing at all, which looks exactly like a
# rejected device.
#
# Why this exists: a virtual device can satisfy udev, libinput and the
# compositor and still have every event discarded — see docs/TESTING.md. Only
# `libinput debug-events --verbose` shows that decision, so this script asks it
# directly instead of relying on whether someone saw the cursor twitch.

set -uo pipefail

PORT=${OPENTRACKPAD_PORT:-4343}
ADDRESS=127.0.0.1:$PORT

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
BIN=$REPO/host/target/debug/opentrackpadd

WORK=$(mktemp -d)
DAEMON_LOG=$WORK/daemon.log
VERBOSE=$WORK/libinput.log
CLICKS=$WORK/clicks.log
RECORD=$WORK/record.yml

cleanup() {
  for pid in ${RECORDER:-} ${WATCHER:-} ${DAEMON:-}; do
    kill "$pid" 2>/dev/null
  done
  wait 2>/dev/null
  rm -rf "$WORK"
}
trap cleanup EXIT

fail() {
  echo "error: $*" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] || fail "run this with sudo; libinput cannot open /dev/input/* otherwise"
command -v libinput >/dev/null || fail "libinput debug utilities are missing (Arch: pacman -S libinput-tools)"
[ -x "$BIN" ] || fail "daemon not built; run: cd $REPO/host && cargo build"

# ------------------------------------------------------------- synthetic input
# The touch surface the synthetic client claims: a landscape 2400x1080 phone.

SURFACE_WIDTH=2400
SURFACE_HEIGHT=1080
# Physical size in micrometres: a 6.7-inch phone held in landscape.
SURFACE_WIDTH_UM=155000
SURFACE_HEIGHT_UM=69000

# ---------------------------------------------------------------- daemon setup

"$BIN" "$ADDRESS" > "$DAEMON_LOG" 2>&1 &
DAEMON=$!
sleep 1
kill -0 "$DAEMON" 2>/dev/null || { cat "$DAEMON_LOG"; fail "the daemon exited immediately"; }

# The virtual device is built to match whoever connects, so the handshake comes
# before there is anything for libinput to look at.
exec 3<>"/dev/tcp/127.0.0.1/$PORT" || fail "could not connect to the daemon"
# The version matters: the daemon refuses a handshake it does not recognise and
# closes the connection, so a stale number here fails as "no device node" rather
# than as "wrong version". This said OTP/2 for two versions and the script had
# quietly stopped working.
printf 'HELLO OTP/4 %d %d 10 %d %d\n' \
  "$SURFACE_WIDTH" "$SURFACE_HEIGHT" "$SURFACE_WIDTH_UM" "$SURFACE_HEIGHT_UM" >&3
NODE=
BUTTONS=
# By name, not by position. The daemon reports a keyboard and a pointer before
# the touchpad, so taking the first node in the log watches the wrong device and
# every count below comes out zero.
for _ in $(seq 20); do
  NODE=$(grep 'virtual touchpad' "$DAEMON_LOG" | grep -o '/dev/input/event[0-9]*' | head -1)
  BUTTONS=$(grep 'virtual pointer' "$DAEMON_LOG" | grep -o '/dev/input/event[0-9]*' | head -1)
  [ -n "$NODE" ] && [ -n "$BUTTONS" ] && break
  sleep 0.2
done
[ -n "$NODE" ] || { cat "$DAEMON_LOG"; fail "the daemon did not report a touchpad node"; }
[ -n "$BUTTONS" ] || { cat "$DAEMON_LOG"; fail "the daemon did not report a pointer node"; }

echo "touchpad: $NODE"
echo "buttons:  $BUTTONS"
echo "address: $ADDRESS"
echo

echo "== does libinput accept it? =="
if ! libinput list-devices 2>/dev/null | grep -A9 -i opentrackpad; then
  fail "libinput does not list the device; nothing below would mean anything"
fi
echo

libinput record --output-file="$RECORD" "$NODE" >/dev/null 2>&1 &
RECORDER=$!
libinput debug-events --verbose --device "$NODE" > "$VERBOSE" 2>&1 &
WATCHER=$!
# The buttons are a device of their own, so they need a watcher of their own.
libinput debug-events --device "$BUTTONS" > "$CLICKS" 2>&1 &
CLICKWATCHER=$!
sleep 2

FINGER_SPACING=260
SEQUENCE=0

# Sends one complete contact snapshot. Arguments are (id x y) triples; no
# arguments means every finger lifted.
send_frame() {
  SEQUENCE=$((SEQUENCE + 1))
  printf 'FRAME %d %d %d' "$SEQUENCE" "$((SEQUENCE * 10000000))" "$(($# / 3))" >&3
  while [ $# -gt 0 ]; do
    # Pressure and contact size are sent but the device declares no such axes.
    printf ' %d %d %d 600 12' "$1" "$2" "$3" >&3
    shift 3
  done
  printf '\n' >&3
}

# Moves `fingers` contacts smoothly from one point to another, then lifts them.
# Strokes have to be long and slow: libinput builds up a motion history before
# emitting anything, and a touch lasting a handful of frames reads as a tap.
glide() {
  local fingers=$1 x0=$2 y0=$3 x1=$4 y1=$5 frames=$6
  local tick finger x y contacts

  for tick in $(seq 0 "$frames"); do
    x=$((x0 + (x1 - x0) * tick / frames))
    y=$((y0 + (y1 - y0) * tick / frames))
    contacts=()
    for ((finger = 0; finger < fingers; finger++)); do
      contacts+=("$finger" "$((x + FINGER_SPACING * finger))" "$y")
    done
    send_frame "${contacts[@]}"
    sleep 0.012
  done
  send_frame
  sleep 0.2
}

# Spreads two contacts apart from a common centre, to exercise pinch zoom.
# Distances are pixels on the synthetic 2400-wide surface, which is 15.5 px per
# millimetre, so 400 to 1600 is roughly 26 mm to 103 mm: the span a real hand
# was measured making.
pinch() {
  local from=$1 to=$2 frames=$3
  local tick gap centre=1200 row=540

  for tick in $(seq 0 "$frames"); do
    gap=$((from + (to - from) * tick / frames))
    send_frame 0 $((centre - gap / 2)) "$row" 1 $((centre + gap / 2)) "$row"
    sleep 0.012
  done
  send_frame
  sleep 0.2
}

echo "== injecting synthetic strokes =="
echo "   one finger, sideways"
glide 1 300 540 2100 540 60
echo "   two fingers, downwards"
glide 2 800 250 800 850 40
echo "   three fingers, sideways"
glide 3 500 540 1400 540 40
echo "   four fingers, downwards"
glide 4 400 250 400 800 40
echo "   two fingers, spreading apart (pinch)"
pinch 400 1600 45
echo "   two fingers, closing together (pinch)"
pinch 1600 400 45


# The buttons the rails send, on their own device. Worth its own check because
# a click is press-and-release with nothing between them, and this project has
# already measured that events sent microseconds apart can collapse into one
# somewhere downstream. If a zero-duration click is too fast to survive, this is
# where it shows up.
echo "   mouse buttons, on the pointer device"
for button in left right middle; do
  SEQUENCE=$((SEQUENCE + 1))
  printf 'ACTION %d BUTTON %s\n' "$SEQUENCE" "$button" >&3
  sleep 0.3
done
sleep 0.5
exec 3>&-
exec 3<&-
sleep 1

kill "$RECORDER" "$WATCHER" "$CLICKWATCHER" 2>/dev/null
wait "$RECORDER" "$WATCHER" "$CLICKWATCHER" 2>/dev/null
RECORDER=; WATCHER=; CLICKWATCHER=

# ------------------------------------------------------------------ conclusion

count() { grep -c "$1" "$VERBOSE"; }

kernel_events=$(grep -c '^\s*- \[' "$RECORD" 2>/dev/null || echo 0)
motion=$(count POINTER_MOTION)
scroll=$(count POINTER_SCROLL_FINGER)
swipe=$(count GESTURE_SWIPE)
pinch_events=$(count GESTURE_PINCH)
palm=$(count 'palm detected')
# Each click should be one press and one release on the pointer device.
clicks_pressed=$(grep -c 'POINTER_BUTTON.*pressed' "$CLICKS" 2>/dev/null || echo 0)
clicks_released=$(grep -c 'POINTER_BUTTON.*released' "$CLICKS" 2>/dev/null || echo 0)

echo
echo "== results =="
printf '  %-34s %s\n' "evdev events the kernel saw" "$kernel_events"
printf '  %-34s %s\n' "pointer motion (one finger)" "$motion"
printf '  %-34s %s\n' "scroll (two fingers)" "$scroll"
printf '  %-34s %s\n' "swipe gestures (three, four)" "$swipe"
printf '  %-34s %s\n' "pinch updates (zoom)" "$pinch_events"
printf '  %-34s %s\n' "contacts discarded as palms" "$palm"
printf '  %-34s %s of 3\n' "button presses (left, right, middle)" "$clicks_pressed"
printf '  %-34s %s of 3\n' "button releases" "$clicks_released"

echo
status=0
if [ "$kernel_events" -eq 0 ]; then
  echo "FAIL: the daemon's events never reached the kernel."
  status=1
elif [ "$motion" -eq 0 ]; then
  echo "FAIL: the kernel received the events but libinput produced no motion."
  [ "$palm" -gt 0 ] && echo "      $palm contact(s) were rejected as palms; see docs/TESTING.md."
  echo "      Full reasoning:"
  grep -iE 'palm|thumb|pressure|ignor' "$VERBOSE" | head -10 | sed 's/^/        /'
  status=1
else
  echo "PASS: pointer motion works."
  [ "$scroll" -eq 0 ] && echo "note: no two-finger scrolling was produced."
  [ "$swipe" -eq 0 ] && echo "note: no three- or four-finger swipe gestures were produced."
  [ "$pinch_events" -eq 0 ] && echo "note: no pinch gestures were produced."
  if [ "$clicks_pressed" -lt 3 ] || [ "$clicks_released" -lt 3 ]; then
    # Not a note. The rails have a click on them, and a click that arrives as
    # a press with no release leaves a button held down on the desktop.
    echo "FAIL: the pointer device did not deliver three complete clicks."
    echo "      A press and release sent in the same instant may be too fast;"
    echo "      see the timing note in docs/TESTING.md."
    sed -n '1,12p' "$CLICKS" | sed 's/^/        /'
    status=1
  fi
fi

echo
echo "Record this run in docs/TESTING.md:"
echo "  kernel:     $(uname -r)"
echo "  libinput:   $(libinput --version)"
# sudo drops the session variables, so fall back to asking logind directly.
session_type=${XDG_SESSION_TYPE:-}
if [ -z "$session_type" ]; then
  session=$(loginctl list-sessions --no-legend 2>/dev/null | awk 'NR == 1 { print $1 }')
  session_type=$(loginctl show-session "$session" -p Type --value 2>/dev/null)
fi
echo "  desktop:    ${XDG_CURRENT_DESKTOP:-unknown} ${session_type:-unknown}"

exit $status
