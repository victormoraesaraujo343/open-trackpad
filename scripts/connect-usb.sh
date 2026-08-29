#!/usr/bin/env bash
#
# Points the phone's localhost:4242 at the host's, over the USB cable.
#
#   ./scripts/connect-usb.sh            set it up once, for the phone plugged in now
#   ./scripts/connect-usb.sh --watch    keep it set up, across unplugging and replugging
#
# The Android client connects to 127.0.0.1 on the phone; adb carries that to the
# daemon listening on the computer. The forwarding lives on the adb connection,
# so it disappears whenever the cable does and has to be re-established. --watch
# is what makes plugging the phone in enough on its own; see
# packaging/opentrackpad-usb.service for running it automatically.

set -uo pipefail

PORT=${OPENTRACKPAD_PORT:-4242}

# Long enough not to spin when adb is unhappy, short enough not to be noticed.
RETRY_SECONDS=2

command -v adb >/dev/null 2>&1 || {
  echo "adb is required. Install the Android platform tools first." >&2
  echo "  Arch and derivatives:  sudo pacman -S android-tools" >&2
  echo "  Debian and Ubuntu:     sudo apt install adb" >&2
  exit 1
}

forward() {
  if adb reverse "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1; then
    echo "USB forwarding active: phone localhost:$PORT -> this computer's localhost:$PORT"
    return 0
  fi

  # The usual cause is more than one device, since adb refuses to guess.
  local devices
  devices=$(adb devices | grep -c '\sdevice$')
  if [ "$devices" -gt 1 ]; then
    echo "adb sees $devices devices and will not choose between them." >&2
    echo "Unplug the others, or set ANDROID_SERIAL to the one you want." >&2
  else
    echo "could not set up USB forwarding; is USB debugging enabled and authorised?" >&2
  fi
  return 1
}

case "${1:-}" in
  --watch)
    echo "waiting for a phone with USB debugging enabled..."
    while true; do
      # Blocks until a phone is plugged in and authorised.
      if ! adb wait-for-usb-device 2>/dev/null; then
        sleep "$RETRY_SECONDS"
        continue
      fi
      forward || { sleep "$RETRY_SECONDS"; continue; }

      # Nothing to do until the cable comes out; the forwarding goes with it.
      adb wait-for-usb-disconnect 2>/dev/null || sleep "$RETRY_SECONDS"
      echo "phone disconnected; waiting for it to come back"
    done
    ;;
  "")
    forward
    ;;
  *)
    echo "usage: ${0##*/} [--watch]" >&2
    exit 2
    ;;
esac
