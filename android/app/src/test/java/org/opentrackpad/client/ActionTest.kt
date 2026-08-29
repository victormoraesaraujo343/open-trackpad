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
}
