package org.opentrackpad.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the picture is assembled, and when it should be thrown away.
 *
 * The generation is what makes this more than a list. Every test here is really
 * asking the same question: does the client know whether what it holds is still
 * true?
 */
class AudioStateTest {

    private val state = AudioState()

    private fun take(vararg lines: String): Boolean {
        var refresh = false
        for (line in lines) {
            val message = Audio.parse(line) ?: error("unparseable: $line")
            if (state.apply(message)) refresh = true
        }
        return refresh
    }

    private val picture = arrayOf(
        "SNAPSHOT audio 1 2",
        "ENTRY audio 1 output 53 950 0 1 - Speakers",
        "ENTRY audio 1 stream 1348 990 0 0 53 Firefox",
    )

    @Test
    fun `a snapshot is not the picture until its last entry arrives`() {
        take("SNAPSHOT audio 1 2")
        assertTrue(state.settling)
        assertEquals(emptyList<AudioEntity>(), state.entities)

        take("ENTRY audio 1 output 53 950 0 1 - Speakers")
        assertTrue("still one short", state.settling)

        take("ENTRY audio 1 stream 1348 990 0 0 53 Firefox")
        assertFalse(state.settling)
        assertEquals(2, state.entities.size)
    }

    @Test
    fun `an empty snapshot is a picture too`() {
        // A machine with no sound devices is not the same as one that has not
        // told us yet, and the panel has to be able to say "nothing" rather
        // than sit waiting.
        take("SNAPSHOT audio 4 0")
        assertFalse(state.settling)
        assertEquals(emptyList<AudioEntity>(), state.entities)
    }

    @Test
    fun `a change to the picture we hold is applied in place`() {
        take(*picture)
        take("CHANGED audio 1 output 53 400 1 1 - Speakers")
        val speakers = state.of(AudioKind.OUTPUT).single()
        assertEquals(400, speakers.volume)
        assertTrue(speakers.muted)
        // In place: the order the host gave is the order shown, and a change
        // must not shuffle a fader out from under a finger.
        assertEquals(0, state.entities.indexOfFirst { it.id == 53 })
    }

    @Test
    fun `a change for something we have never seen is an appearance`() {
        take(*picture)
        take("CHANGED audio 1 output 88 700 0 0 - Headset")
        assertEquals(2, state.of(AudioKind.OUTPUT).size)
        assertEquals("Headset", state.of(AudioKind.OUTPUT).last().name)
    }

    @Test
    fun `an update from a picture we have already replaced is dropped`() {
        take(*picture)
        take("SNAPSHOT audio 2 1", "ENTRY audio 2 output 53 950 0 1 - Speakers")
        assertFalse(take("CHANGED audio 1 output 53 100 0 1 - Speakers"))
        assertEquals("the stale change was applied", 950, state.of(AudioKind.OUTPUT).single().volume)
    }

    @Test
    fun `an update from a picture we never saw asks for the whole thing again`() {
        // A SNAPSHOT went missing. Nothing we hold can be relied on, and the
        // only honest recovery is to ask rather than to patch.
        take(*picture)
        assertTrue(take("CHANGED audio 9 output 53 100 0 1 - Speakers"))
        assertTrue(take("REMOVED audio 9 output 53"))
    }

    @Test
    fun `an entry with no snapshot behind it asks for the whole thing again`() {
        assertTrue(take("ENTRY audio 5 output 53 950 0 1 - Speakers"))
    }

    @Test
    fun `losing sound empties the panel rather than freezing it`() {
        take(*picture)
        take("UNAVAILABLE audio lost")
        assertEquals(AudioOutage.LOST, state.outage)
        assertEquals(emptyList<AudioEntity>(), state.entities)
        // And a fresh snapshot brings it back without anything else happening.
        take("SNAPSHOT audio 3 1", "ENTRY audio 3 output 53 950 0 1 - Speakers")
        assertNull(state.outage)
        assertEquals(1, state.entities.size)
    }

    @Test
    fun `a removal takes only the entity it names`() {
        // Outputs, inputs and streams are numbered independently, so id alone
        // is ambiguous — which shows up as the wrong row disappearing.
        take(
            "SNAPSHOT audio 1 2",
            "ENTRY audio 1 output 53 950 0 1 - Speakers",
            "ENTRY audio 1 input 53 500 0 1 - Microphone",
        )
        take("REMOVED audio 1 input 53")
        assertEquals(1, state.entities.size)
        assertEquals(AudioKind.OUTPUT, state.entities.single().kind)
    }

    @Test
    fun `a session ending forgets everything`() {
        take(*picture)
        state.reset()
        assertEquals(emptyList<AudioEntity>(), state.entities)
        assertNull(state.outage)
        // And a stale update from before the reset cannot resurrect anything.
        assertTrue(take("CHANGED audio 1 output 53 100 0 1 - Speakers"))
    }

    @Test
    fun `a domain the host never granted holds nothing`() {
        take(*picture)
        state.grant(false)
        assertFalse(state.granted)
        assertEquals(emptyList<AudioEntity>(), state.entities)
    }

    @Test
    fun `the default device is found per kind`() {
        take(
            "SNAPSHOT audio 1 3",
            "ENTRY audio 1 output 53 950 0 1 - Speakers",
            "ENTRY audio 1 output 54 500 0 0 - Headset",
            "ENTRY audio 1 input 9 500 0 1 - Microphone",
        )
        assertEquals("Speakers", state.defaultOf(AudioKind.OUTPUT)?.name)
        assertEquals("Microphone", state.defaultOf(AudioKind.INPUT)?.name)
        assertNull(state.defaultOf(AudioKind.STREAM))
    }
}
