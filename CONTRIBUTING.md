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

A fourth case sharpened it further. A panel appeared to render with no padding;
the code was read, every call site checked, and the conclusion reasoned to. The
value was then logged and had been correct all along — the screenshot came from
a stale install. The build had been verified by hash, but *after* a later build
rather than at the moment the screenshot was taken: the right check, at the
wrong time, giving a true answer to a question nobody was asking.

**An observation is only as fresh as the build it was taken from, and verifying
identity afterwards does not make an old screenshot new.**

A fifth case moved it somewhere worse. A screenshot comparison written
specifically to be trustworthy carried a note saying it disabled the screen fade
and did not disable it — so a walk that took twenty seconds compared a dimmed
ground against a bright one and reported every screen as changed. **A note
describing what code does not do is worse in a verification tool than anywhere
else, because everything downstream trusts its answer instead of looking.**

What ended each of these was the same thing, and it was not thinking harder. It
was going back and looking at the thing again after having already explained it.

One more, about repetition rather than verification. Three times in one session a
pattern was removed from one file and written again in another within the hour —
a blind tap loop, a guessed offset, a counted sequence of steps — twice inside
the very tool built to prevent it. **Removing a bad pattern from a file does not
remove it from your hands.** A neighbouring shape, three times in the same day:
**a command whose target is implicit resolves against a world that moves.**
`pgrep -f` matched the shell that ran it. `adb -d` meant a different device once
an emulator appeared. `git commit --amend` rewrote another session's commit,
because HEAD is not private in a worktree three sessions share. Name the target —
a serial, a hash, a pattern that cannot match the searcher — or the command will
one day mean something you did not say. The fix that generalises is not remembering harder;
it is deriving from the thing itself rather than from a number about it, and
asserting arrival rather than assuming it.
Three separate symptoms this project reported were each covered by a confident,
correct, complete-sounding explanation that was also not the whole cause.

## Development flow

1. Open an issue describing the device, Android version, Linux distribution, and intended change.
2. Keep protocol changes backward-compatible or bump the protocol version.
3. Add tests for parsers and input-state transitions.
4. Report what was tested on physical hardware.
