# Roadmap

## Milestone 0 — repository foundation

- [x] Define the USB-first architecture
- [x] Draft a debuggable touch-frame protocol
- [x] Add a host protocol receiver and parser tests
- [x] Target any Linux with libinput, and any Android 9 or newer

## Milestone 1 — host virtual touchpad

- [x] Implement the Linux `uinput` backend
- [x] Add safe release-all behavior on disconnect
- [x] Add a synthetic contact self-test for the `uinput` path
- [x] Confirm udev tags the device `ID_INPUT_TOUCHPAD`
- [x] Verify libinput classifies the device as a touchpad
- [x] Verify injected contacts move the desktop pointer
- [x] Verify two-finger scrolling and three- and four-finger swipe gestures
- [ ] Verify on a second distribution and desktop (GNOME as well as KDE)
- [x] Add a udev rule and systemd user service
- [ ] Confirm 30 minutes of use leaves no stuck contact (`--soak 30`)

## Milestone 2 — Android sender

- [x] Build an immersive Kotlin touch surface
- [x] Send complete multi-contact snapshots
- [x] Add orientation and coordinate normalization
- [x] Prevent sleep while connected
- [x] Show connection status without consuming touch area
- [x] Coalesce movement under backpressure without losing lifts
- [x] Reconnect with a fresh handshake
- [x] Report the phone's real screen size so any device maps correctly
- [x] Test on a real phone against the host

## Milestone 3 — dedicated appliance mode

- [x] Auto-connect after launch, with no terminal commands
- [x] Re-establish the USB bridge when the cable comes back
- [x] Start-on-boot through systemd user services
- [x] A tray indicator showing connection state, with stop and start
- [x] Dim the screen when idle, and shift static elements so nothing burns in
- [ ] Accidental-edge and palm filtering
- [ ] Calibration UI: in-app sliders for pointer speed and sensitivity
      Asked for after the first hardware test: the desktop's own pointer speed
      setting is per-device and buried in system settings, and a dedicated
      trackpad wants its feel adjustable from the surface itself.

## Milestone 4 — wireless fallback

- [ ] Evaluate authenticated Bluetooth Classic and BLE transports
- [ ] Measure latency and reconnection behavior against USB
- [ ] Document security and pairing behavior

## Non-goals for the first release

- Emulating Apple's proprietary Magic Trackpad protocol
- Windows Precision Touchpad certification
- Screen mirroring or remote desktop
- Requiring a rooted Android device
