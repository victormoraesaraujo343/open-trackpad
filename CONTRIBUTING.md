# Contributing

OpenTrackpad is in its protocol and hardware-validation phase.

## Principles

1. Preserve raw multi-touch information until the Linux input layer.
2. Keep the USB path functional before adding wireless transports.
3. Measure latency instead of describing it as low without evidence.
4. Do not require root on Android for the primary workflow.
5. Keep permissions and privileged Linux setup explicit and minimal.

## Development flow

1. Open an issue describing the device, Android version, Linux distribution, and intended change.
2. Keep protocol changes backward-compatible or bump the protocol version.
3. Add tests for parsers and input-state transitions.
4. Report what was tested on physical hardware.
