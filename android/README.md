# Android client

The Android client is not implemented yet.

The first client should be a native Kotlin application targeting Android 9 or newer. Its initial scope is intentionally small:

1. Open an immersive edge-to-edge touch surface.
2. Capture complete multi-pointer snapshots from `MotionEvent`.
3. Connect to `127.0.0.1:4242`, forwarded to the host with `adb reverse`.
4. Send the `HELLO` line once and a `FRAME` line for every relevant touch event.
5. Send a zero-contact frame on cancellation and before a clean disconnect.

Before choosing the final minimum SDK, validate the actual dedicated device model and Android version.
