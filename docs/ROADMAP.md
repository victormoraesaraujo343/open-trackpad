# Roadmap

## Milestone 0 — repository foundation

- [x] Define the USB-first architecture
- [x] Draft a debuggable touch-frame protocol
- [x] Add a host protocol receiver and parser tests
- [ ] Record the target Android model and OS version

## Milestone 1 — host virtual touchpad

- [ ] Implement the Linux `uinput` backend
- [ ] Add safe release-all behavior on disconnect
- [ ] Add a minimal udev rule and systemd user service
- [ ] Verify classification with `libinput list-devices`
- [ ] Verify two-, three-, and four-finger events on Zorin OS 18

## Milestone 2 — Android sender

- [ ] Build an immersive Kotlin touch surface
- [ ] Send complete multi-contact snapshots
- [ ] Add orientation and coordinate normalization
- [ ] Prevent sleep while connected
- [ ] Show connection and latency status without consuming touch area

## Milestone 3 — dedicated appliance mode

- [ ] Auto-connect after launch
- [ ] Optional start-on-boot instructions
- [ ] Burn-in protection and configurable brightness
- [ ] Accidental-edge and palm filtering
- [ ] Calibration UI

## Milestone 4 — wireless fallback

- [ ] Evaluate authenticated Bluetooth Classic and BLE transports
- [ ] Measure latency and reconnection behavior against USB
- [ ] Document security and pairing behavior

## Non-goals for the first release

- Emulating Apple's proprietary Magic Trackpad protocol
- Windows Precision Touchpad certification
- Screen mirroring or remote desktop
- Requiring a rooted Android device
