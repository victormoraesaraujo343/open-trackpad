# Contributing

OpenTrackpad is in its protocol and hardware-validation phase.

## Principles

1. Preserve raw multi-touch information until the Linux input layer.
2. Keep the USB path functional before adding wireless transports.
3. Measure latency instead of describing it as low without evidence.
4. Do not require root on Android for the primary workflow.
5. Keep permissions and privileged Linux setup explicit and minimal.
6. Say out loud what a thing does. It is where the defects are.

## On that sixth principle

It reads like process and it is not. Of four real defects found on 2026-08-29,
three were found by somebody writing down what something meant rather than by
something failing:

- listing the refusal reasons for another developer exposed that two of them
  could not be parsed at all;
- writing a checklist for the owner exposed that pressing an unsupported key in
  the recorder did nothing whatsoever — no message, no change — in the one
  window whose entire job is to answer a key press;
- describing a fix exposed that `REFUSED` named a sequence number nothing
  guaranteed to be unique.

The fourth is the counterexample worth keeping beside them. The daemon had been
serving a stale build for four and a half hours, across a restart where "the
service is active" was checked and "the service is running the code I just
wrote" was not. It was found by refusing to conclude anything from silence: a
version 3 host never replies, so a zero-contact frame — which moves no pointer —
was sent, and the socket stayed open.

So: write the explanation before you are asked for it, and do not accept an
absence of evidence as evidence. Both are cheaper than the bug.

## Development flow

1. Open an issue describing the device, Android version, Linux distribution, and intended change.
2. Keep protocol changes backward-compatible or bump the protocol version.
3. Add tests for parsers and input-state transitions.
4. Report what was tested on physical hardware.
