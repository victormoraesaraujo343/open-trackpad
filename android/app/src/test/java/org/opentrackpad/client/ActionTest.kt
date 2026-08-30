package org.opentrackpad.client

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionTest {

    @Test
    fun `a key chord matches the wire format`() {
        assertEquals(
            "ACTION 42 KEY ctrl+shift+t",
            Action.KeyChord("ctrl+shift+t").encode(sequence = 42),
        )
    }

    @Test
    fun `a single key needs no chord syntax`() {
        assertEquals("ACTION 1 KEY escape", Action.KeyChord("escape").encode(sequence = 1))
    }

    @Test
    fun `the chord is passed through for the host to judge`() {
        // The host owns the list of key names. Duplicating it here would give
        // two places to keep in step and no extra safety, since a wrong name is
        // refused either way.
        assertEquals("ACTION 1 KEY nonsense", Action.KeyChord("nonsense").encode(sequence = 1))
    }

    @Test
    fun `a click names the button and nothing else`() {
        assertEquals("ACTION 7 BUTTON right", Action.Click(Action.Button.RIGHT).encode(7))
        assertEquals("ACTION 8 BUTTON left", Action.Click(Action.Button.LEFT).encode(8))
        assertEquals("ACTION 9 BUTTON middle", Action.Click(Action.Button.MIDDLE).encode(9))
    }

    @Test
    fun `recording carries no argument at all`() {
        // The host rejects `RECORD` with anything after it, and a rejected
        // action closes the connection. Worth a test rather than a reading of
        // the parser, because a trailing space is invisible in a diff.
        assertEquals("ACTION 3 RECORD", Action.Record.encode(3))
    }

    @Test
    fun `only chords are safe to send to a version three host`() {
        // Not a style question. A version 3 host treats a message it has never
        // heard of as proof the client is lying about what it is, and hangs up
        // — so getting this wrong takes somebody's trackpad away rather than
        // producing an error somewhere.
        assertEquals(false, Action.KeyChord("ctrl+c").afterVersionThree)
        assertEquals(true, Action.Click(Action.Button.RIGHT).afterVersionThree)
        assertEquals(true, Action.Record.afterVersionThree)
    }

    @Test
    fun `every button has an icon of its own`() {
        // Three buttons that all drew the same picture would be three buttons
        // nobody could tell apart on a rail.
        val icons = Action.Button.entries.map { RailIcons.forAction(Action.Click(it)) }
        assertEquals(icons.size, icons.distinct().size)
        for (icon in icons) {
            assertEquals("icon $icon is the fallback", false, icon == RailIcons.FALLBACK)
        }
    }
}
