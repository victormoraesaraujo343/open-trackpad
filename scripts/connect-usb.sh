#!/usr/bin/env bash
#
# Points the phone's localhost:4242 at the host's, over the USB cable.
#
#   ./scripts/connect-usb.sh            set it up once, for the phone plugged in now
#   ./scripts/connect-usb.sh --watch    keep it set up, across unplugging and replugging
#   ./scripts/connect-usb.sh --status   say whether the bridge is up, and exit
#
# The Android client connects to 127.0.0.1 on the phone; adb carries that to the
# daemon listening on the computer. The forwarding lives on the adb connection,
# so it disappears whenever the cable does and has to be re-established. --watch
# is what makes plugging the phone in enough on its own; see
# packaging/opentrackpad-usb.service for running it automatically.
#
# Every mode is idempotent: setting up a bridge that is already up does nothing
# and reports success. Firing this repeatedly — from a udev rule, from the tray,
# from a person who is not sure — is safe by construction.

set -uo pipefail

PORT=${OPENTRACKPAD_PORT:-4242}

# Long enough not to spin when adb is unhappy, short enough not to be noticed.
RETRY_SECONDS=2

# Consecutive failures before we stop believing adb and restart its server. The
# server is a long-lived daemon that can outlive the truth: it has been observed
# holding a device in a state where every reverse fails while `adb devices`
# still lists it as ready. Restarting it is what a person does by hand, so the
# watcher should do it rather than wait to be rescued.
FAILURES_BEFORE_RESET=5

command -v adb >/dev/null 2>&1 || {
  echo "adb is required. Install the Android platform tools first." >&2
  echo "  Arch and derivatives:  sudo pacman -S android-tools" >&2
  echo "  Debian and Ubuntu:     sudo apt install adb" >&2
  exit 1
}

# Repeats are the enemy here. A failure that lasts twelve hours should be one
# line in the journal, not twenty thousand; the useful signal is the change of
# state, not its persistence.
last_note=""
note() {
  [ "$1" = "$last_note" ] && return 0
  last_note=$1
  echo "$1"
}
warn() {
  [ "$1" = "$last_note" ] && return 0
  last_note=$1
  echo "$1" >&2
}

# The phone, specifically.
#
# `adb -d` means "the one USB device", which is right until it is not: it is
# resolved fresh by adb on every call and says nothing about *which* phone, so
# a second device arriving changes what it means. Reading the serial ourselves
# is both narrower and more stable — every later call names the phone it first
# found, and a device appearing alongside cannot silently redirect the bridge.
#
# Emulators are excluded by construction rather than by name: `adb devices -l`
# gives a `usb:` field only to something on the bus. Matching that is why a
# running emulator is not a competing answer, and why it cannot become one by
# being renamed.
usb_serials() {
  adb devices -l 2>/dev/null | awk '$2 == "device" && /[[:space:]]usb:/ { print $1 }'
}

resolve_serial() {
  local wanted=${1:-} serials found
  mapfile -t serials < <(usb_serials)

  # An explicit choice outranks ours, and a remembered phone outranks a guess:
  # if the phone we were bridging is still on the bus, it stays the phone, even
  # if another has joined it.
  for found in "${serials[@]}"; do
    [ -n "${ANDROID_SERIAL:-}" ] && [ "$found" = "$ANDROID_SERIAL" ] && { printf '%s' "$found"; return 0; }
  done
  [ -n "${ANDROID_SERIAL:-}" ] && return 1

  for found in "${serials[@]}"; do
    [ -n "$wanted" ] && [ "$found" = "$wanted" ] && { printf '%s' "$found"; return 0; }
  done

  case ${#serials[@]} in
    0) return 1 ;;
    1) printf '%s' "${serials[0]}"; return 0 ;;
    *) return 2 ;;   # more than one phone, and no reason to prefer either
  esac
}

is_forwarded() {
  adb -s "$1" reverse --list 2>/dev/null | grep -q "tcp:$PORT tcp:$PORT"
}

# Returns 0 when the bridge is up afterwards, whether or not we are what put it
# there. "Already correct" is success, not a special case.
forward() {
  local serial=$1
  is_forwarded "$serial" && return 0
  adb -s "$serial" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1 && return 0
  return 1
}

explain_failure() {
  local status=$1
  case "$status" in
    2) warn "more than one phone is plugged in and adb will not choose between them; set ANDROID_SERIAL to the one you want" ;;
    1) warn "no phone on the USB bus; is the cable in, with USB debugging enabled and authorised?" ;;
    *) warn "could not set up USB forwarding; is USB debugging enabled and authorised?" ;;
  esac
}

# adb has stopped telling the truth often enough that the watcher has to be able
# to disbelieve it. Cheap remedy first: `reconnect` re-handshakes the transport.
# Only if that does not take do we restart the server, which is heavier because
# it drops every client's forwards, not just ours.
recover() {
  local serial=${1:-}
  if [ -n "$serial" ]; then
    adb -s "$serial" reconnect >/dev/null 2>&1 || adb reconnect offline >/dev/null 2>&1
  else
    adb reconnect offline >/dev/null 2>&1
  fi
  sleep 1
  [ -n "$serial" ] && is_forwarded "$serial" && return 0

  warn "adb has not recovered on its own; restarting its server"
  adb kill-server >/dev/null 2>&1
  adb start-server >/dev/null 2>&1
  sleep 1
  return 0
}

once() {
  local serial status
  serial=$(resolve_serial); status=$?
  [ $status -ne 0 ] && { explain_failure "$status"; return 1; }
  if forward "$serial"; then
    echo "USB forwarding active: phone localhost:$PORT -> this computer's localhost:$PORT"
    return 0
  fi
  explain_failure 0
  return 1
}

watch() {
  local serial="" status failures=0

  echo "waiting for a phone with USB debugging enabled..."
  while true; do
    serial=$(resolve_serial "$serial"); status=$?

    if [ $status -ne 0 ]; then
      # Nothing to bridge. Block until something arrives rather than spinning;
      # the timeout is what stops a phone that appears during the wait from
      # going unnoticed until the next event.
      explain_failure "$status"
      adb wait-for-usb-device >/dev/null 2>&1 &
      local waiter=$!
      ( sleep 10; kill "$waiter" 2>/dev/null ) >/dev/null 2>&1 &
      local timer=$!
      wait "$waiter" 2>/dev/null
      kill "$timer" 2>/dev/null
      continue
    fi

    if forward "$serial"; then
      failures=0
      note "USB forwarding active: phone localhost:$PORT -> this computer's localhost:$PORT"

      # Nothing to do until the cable comes out; the forwarding goes with it.
      local waited_from=$SECONDS
      adb -s "$serial" wait-for-disconnect >/dev/null 2>&1

      # A wait that returns instantly has not waited for anything. Whichever
      # way that happens — an adb that does not know the verb, a transport that
      # never attached — the loop must not turn into a spin, and if the phone
      # is still on the bus there is nothing to announce. Slow is recoverable;
      # a hot loop on a laptop is not.
      if [ $((SECONDS - waited_from)) -lt 1 ]; then
        sleep "$RETRY_SECONDS"
        is_forwarded "$serial" && continue
      fi

      last_note=""   # the next connection is news again, even if it reads the same
      echo "phone disconnected; waiting for it to come back"
      continue
    fi

    failures=$((failures + 1))
    explain_failure 0
    if [ "$failures" -ge "$FAILURES_BEFORE_RESET" ]; then
      recover "$serial"
      failures=0
    fi
    sleep "$RETRY_SECONDS"
  done
}

case "${1:-}" in
  --watch)  watch ;;
  --status)
    serial=$(resolve_serial); status=$?
    [ $status -ne 0 ] && { explain_failure "$status"; exit 1; }
    if is_forwarded "$serial"; then
      echo "USB forwarding active on $serial: phone localhost:$PORT -> this computer's localhost:$PORT"
      exit 0
    fi
    echo "phone $serial is attached but the bridge is not up" >&2
    exit 1
    ;;
  "")       once ;;
  *)
    echo "usage: ${0##*/} [--watch|--status]" >&2
    exit 2
    ;;
esac
