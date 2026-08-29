# Android client

The v0.1 client is a single full-screen touch surface. It reports where fingers
are and nothing else: it never decides that two fingers mean scrolling or that
three mean Alt+Tab. Linux does that, which is the whole point of the project.

## Building

Needs a JDK 17 and the Android SDK; Gradle downloads itself through the wrapper.

```bash
cd android
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

Point Gradle at your SDK with either `ANDROID_HOME` or a `local.properties`
containing `sdk.dir=/path/to/Android/Sdk`. That file is per-machine and stays
out of version control.

## Running it against the host

1. Enable USB debugging on the phone: Settings, About phone, tap Build number
   seven times, then Developer options, USB debugging.
2. Plug it in and forward the port:

   ```bash
   ./scripts/connect-usb.sh    # adb reverse tcp:4242 tcp:4242
   ```

3. Start the host daemon:

   ```bash
   cd host && cargo run
   ```

   Or install the services described in [the host README](../host/README.md),
   after which the daemon and the USB bridge come up on their own and plugging
   the phone in is enough.

4. Install and launch the app:

   ```bash
   cd android && ./gradlew installDebug
   ```

The status line reads "Connected via USB" once the socket is up. It never shows
a latency figure: the phone's clock and the computer's clock have no shared
origin, so any number would be invented.

## Tests

```bash
./gradlew testDebugUnitTest
```

These run on the JVM, with no device or emulator. They cover the wire format and
the frame queue. The encoded strings in `ProtocolTest` are byte-for-byte the same
as the ones in the host's parser tests, so the two implementations are checked
against a shared literal rather than against each other's assumptions.

## How it is put together

| File | Responsibility |
| --- | --- |
| `Protocol.kt` | The OTP/3 wire format, and nothing else. |
| `Action.kt` | The closed set of things a control button may ask for. |
| `TouchSurfaceView.kt` | Turning `MotionEvent` into complete contact snapshots. |
| `FrameQueue.kt` | What to discard when frames outpace the socket. |
| `HostConnection.kt` | The socket, the sender thread, reconnection. |
| `MainActivity.kt` | Immersive landscape surface and connection status. |
| `ScreenCare.kt` | Dimming while idle, and nudging static views. |

Three decisions worth knowing about:

- **Pointer IDs, never pointer indices.** Indices shuffle as fingers come and
  go; IDs identify the same finger for as long as it is down.
- **`ACTION_POINTER_UP` excludes the lifting finger.** Android still lists it in
  the event, so it has to be removed by hand or the host believes it is down.
- **Historical samples are sent too.** Android batches several touch samples
  into one `MotionEvent`; sending only the latest would throw away the
  digitiser's sampling rate and keep only the display's refresh rate.

Shortcuts share the socket but never queue behind movement: a button has to
feel immediate, while a snapshot that waits is superseded by the next one
anyway. The host treats the two as unrelated, so nothing is lost by letting
shortcuts overtake.

`FrameQueue` is where backpressure is handled. Movement may be dropped, because
every frame is a complete snapshot and a newer one supersedes an older one
entirely. Frames that change *which fingers exist* never are — dropping one
would leave the host believing a finger is still down. See its tests.

## Looking after the screen

A phone being a trackpad sits on a desk for hours, plugged in and showing the
same thing. `ScreenCare` dims the screen after half a minute without a touch —
not off, because a screen that is off cannot feel a finger — and brings it back
on the next contact. It also nudges registered views a couple of pixels on a
slow cycle, so nothing static can leave a ghost on an OLED panel.

It is told which views stay put rather than working it out, and it knows nothing
about the layout. The interface will change; this should not have to. The touch
surface is deliberately never registered: shifting it would slide part of it off
the screen and the edge of the pad would stop responding.

## Not in v0.1

No shortcut rails, radial menu, profiles, themes, left-handed layout, haptics,
Bluetooth or Wi-Fi. Those are v0.2 and later, once the native trackpad is proven
on real hardware.
