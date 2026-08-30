package org.opentrackpad.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio wire format, read against `docs/PROTOCOL.md`.
 *
 * Worth being strict here rather than forgiving. A name is the one field a
 * person reads, a level is the one they act on, and both arrive from a host
 * that got them from a sound daemon that got them from an application.
 */
class AudioTest {

    private val example =
        "ENTRY audio 1 output 53 950 0 1 - analog - Built-in%20Audio%20Digital%20Stereo"

    @Test
    fun `the worked example from the protocol reads as it is written`() {
        val message = Audio.parse(example) as AudioMessage.Entry
        assertEquals(1L, message.generation)
        assertEquals(
            AudioEntity(
                kind = AudioKind.OUTPUT,
                id = 53,
                volume = 950,
                muted = false,
                isDefault = true,
                target = null,
                port = AudioPort.ANALOG,
                paused = null,
                name = "Built-in Audio Digital Stereo",
            ),
            message.entity,
        )
        assertEquals(95, message.entity.percent)
        assertFalse(message.entity.boosted)
    }

    @Test
    fun `a stream names the output it plays through`() {
        val message = Audio.parse("ENTRY audio 1 stream 1348 990 0 0 53 - 0 Firefox") as AudioMessage.Entry
        assertEquals(AudioKind.STREAM, message.entity.kind)
        assertEquals(53, message.entity.target)
        assertEquals("Firefox", message.entity.name)
    }

    @Test
    fun `a snapshot says how many entries to expect`() {
        assertEquals(
            AudioMessage.Snapshot(generation = 7, count = 2),
            Audio.parse("SNAPSHOT audio 7 2"),
        )
    }

    @Test
    fun `the other verbs read`() {
        assertEquals(
            AudioMessage.Removed(3, AudioKind.INPUT, 12),
            Audio.parse("REMOVED audio 3 input 12"),
        )
        assertEquals(
            AudioMessage.Unavailable(AudioOutage.LOST),
            Audio.parse("UNAVAILABLE audio lost"),
        )
        assertEquals(
            AudioMessage.Refused(42, AudioRefusal.UNKNOWN_ID),
            Audio.parse("REFUSED 42 unknown-id"),
        )
    }

    @Test
    fun `a line this version has never heard of is ignored, not fatal`() {
        // A later host will send messages this client does not know. Falling
        // over at the first one would mean a phone could never be older than
        // the computer it is plugged into.
        assertNull(Audio.parse("SPECTRUM audio 1 0.4 0.9"))
        assertNull(Audio.parse("SNAPSHOT lighting 1 2"))
        assertNull(Audio.parse(""))
    }

    @Test
    fun `a name is decoded a byte at a time, not a character at a time`() {
        // One character can be several escapes, and decoding them singly turns
        // an accent into two wrong ones.
        assertEquals("Música", Audio.decode("M%C3%BAsica"))
        assertEquals("100%", Audio.decode("100%25"))
        assertEquals(" ", Audio.decode("%20"))
        assertEquals("a b", Audio.decode("a%20b"))
    }

    @Test
    fun `an encoding that cannot be right is refused rather than guessed at`() {
        // A wrong name is worse than a refused line: it is the one field
        // somebody reads and cannot check.
        assertNull(Audio.decode("half %"))
        assertNull(Audio.decode("%2"))
        assertNull(Audio.decode("%zz"))
        // A raw space or newline should have been escaped, so its presence
        // means the line was not built the way the protocol says.
        assertNull(Audio.decode("two words"))
    }

    @Test
    fun `a host confused about what it is describing is refused`() {
        // Only a device can be the default, and only a stream has a target.
        assertNull(Audio.parse("ENTRY audio 1 stream 9 500 0 1 53 - 0 Firefox"))
        assertNull(Audio.parse("ENTRY audio 1 output 9 500 0 1 53 Speakers"))
    }

    @Test
    fun `malformed fields are refused`() {
        assertNull(Audio.parse("ENTRY audio 1 output 53 950 2 1 - Name"))
        assertNull(Audio.parse("ENTRY audio 1 nonsense 53 950 0 1 - Name"))
        assertNull(Audio.parse("ENTRY audio 1 output x 950 0 1 - Name"))
        assertNull(Audio.parse("ENTRY audio 1 output 53 -5 0 1 - Name"))
        // Trailing fields, which the protocol calls an error in both directions.
        assertNull(Audio.parse("ENTRY audio 1 output 53 950 0 1 - analog - Name extra"))
    }

    @Test
    fun `a level above the reference is boosted and drawn against the ceiling`() {
        val loud = Audio.parse("CHANGED audio 1 output 1 1300 0 0 - analog - Speakers") as AudioMessage.Changed
        assertTrue(loud.entity.boosted)
        assertEquals(130, loud.entity.percent)
        assertEquals(1300f / Audio.CEILING, loud.entity.fraction, 0.001f)
        // The two numbers are deliberately different; equal, the ceiling would
        // silently become the reference and cap everything at 100%.
        assertTrue(Audio.CEILING > Audio.REFERENCE)
    }

    @Test
    fun `requests match the wire format`() {
        assertEquals(
            "REQUEST 4 audio VOLUME output 53 1200",
            Audio.volume(4, AudioKind.OUTPUT, 53, 1200),
        )
        assertEquals("REQUEST 5 audio MUTE stream 1348 1", Audio.mute(5, AudioKind.STREAM, 1348, true))
        assertEquals("REQUEST 6 audio DEFAULT input 3", Audio.makeDefault(6, AudioKind.INPUT, 3))
        assertEquals("REQUEST 7 audio REFRESH", Audio.refresh(7))
    }

    @Test
    fun `a stream cannot be asked to become the default`() {
        // It cannot mean anything, and the host closes the connection over it,
        // so it is stopped here rather than sent.
        assertNull(Audio.makeDefault(8, AudioKind.STREAM, 1348))
    }

    @Test
    fun `a level outside the scale is never sent`() {
        // The host refuses rather than clamps, and a refused request means a
        // fader springing back. Clamping here keeps that from ever happening.
        assertTrue(Audio.volume(1, AudioKind.OUTPUT, 1, 9999).endsWith(" ${Audio.CEILING}"))
        assertTrue(Audio.volume(1, AudioKind.OUTPUT, 1, -5).endsWith(" 0"))
    }
}
