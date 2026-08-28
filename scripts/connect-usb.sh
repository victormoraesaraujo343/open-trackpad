#!/usr/bin/env bash
set -euo pipefail

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is required. Install Android platform-tools first." >&2
  exit 1
fi

adb reverse tcp:4242 tcp:4242
echo "USB forwarding active: Android localhost:4242 -> Linux localhost:4242"
