#!/usr/bin/env bash
#
# Proves — or disproves — that the virtual touchpad works on this machine.
#
#   sudo ./scripts/validate-touchpad.sh
#
# Starts the daemon, watches its device with libinput, feeds it synthetic one-,
# two-, three- and four-finger strokes over the protocol socket, and reports
# what libinput made of each one.
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

# ---------------------------------------------------------------- daemon setup

"$BIN" "$ADDRESS" > "$DAEMON_LOG" 2>&1 &
DAEMON=$!
sleep 1
kill -0 "$DAEMON" 2>/dev/null || { cat "$DAEMON_LOG"; fail "the daemon exited immediately"; }

NODE=$(grep -o '/dev/input/event[0-9]*' "$DAEMON_LOG" | head -1)
[ -n "$NODE" ] || { cat "$DAEMON_LOG"; fail "the daemon did not report a device node"; }

echo "device:  $NODE"
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
sleep 2

# ------------------------------------------------------------- synthetic input
# The touch surface the synthetic client claims: a landscape 2400x1080 phone.

SURFACE_WIDTH=2400
SURFACE_HEIGHT=1080
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

exec 3<>"/dev/tcp/127.0.0.1/$PORT" || fail "could not connect to the daemon"
printf 'HELLO OTP/1 %d %d 10\n' "$SURFACE_WIDTH" "$SURFACE_HEIGHT" >&3

echo "== injecting synthetic strokes =="
echo "   one finger, sideways"
glide 1 300 540 2100 540 60
echo "   two fingers, downwards"
glide 2 800 250 800 850 40
echo "   three fingers, sideways"
glide 3 500 540 1400 540 40
echo "   four fingers, downwards"
glide 4 400 250 400 800 40

exec 3>&-
exec 3<&-
sleep 1

kill "$RECORDER" "$WATCHER" 2>/dev/null
wait "$RECORDER" "$WATCHER" 2>/dev/null
RECORDER=; WATCHER=

# ------------------------------------------------------------------ conclusion

count() { grep -c "$1" "$VERBOSE"; }

kernel_events=$(grep -c '^\s*- \[' "$RECORD" 2>/dev/null || echo 0)
motion=$(count POINTER_MOTION)
scroll=$(count POINTER_SCROLL_FINGER)
swipe=$(count GESTURE_SWIPE)
palm=$(count 'palm detected')

echo
echo "== results =="
printf '  %-34s %s\n' "evdev events the kernel saw" "$kernel_events"
printf '  %-34s %s\n' "pointer motion (one finger)" "$motion"
printf '  %-34s %s\n' "scroll (two fingers)" "$scroll"
printf '  %-34s %s\n' "swipe gestures (three, four)" "$swipe"
printf '  %-34s %s\n' "contacts discarded as palms" "$palm"

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
fi

echo
echo "Record this run in docs/TESTING.md:"
echo "  kernel:     $(uname -r)"
echo "  libinput:   $(libinput --version)"
echo "  desktop:    ${XDG_CURRENT_DESKTOP:-unknown} ${XDG_SESSION_TYPE:-unknown}"

exit $status
