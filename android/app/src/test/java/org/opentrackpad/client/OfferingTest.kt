package org.opentrackpad.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor's library is the host's plus the phone's own, and the two must not
 * be able to collide.
 */
class OfferingTest {

    private fun buttons() = Offering.buttons(name = { it.name }, detail = "mouse")

    @Test
    fun `the phone's own entries can never take a host id`() {
        // The drop looks a dragged thing up by id across both lists at once. A
        // built-in sharing a number with a host shortcut would put the wrong
        // action on the slot, and it would look like the right one.
        assertTrue(buttons().all { it.id < 0 })
        assertEquals(buttons().size, buttons().map { it.id }.distinct().size)
    }

    @Test
    fun `a host entry keeps its id and its chord`() {
        val entry = LibraryEntry(12, "ctrl+c", Origin.IMPORTED, ShortcutGroup.TEXT, "Copy")
        val offering = Offering.of(entry)
        assertEquals(12, offering.id)
        assertEquals("ctrl+c", offering.detail)
        assertEquals(Action.KeyChord("ctrl+c"), offering.action)
        assertEquals(false, offering.builtIn)
        assertEquals(false, offering.mine)
    }

    @Test
    fun `a recorded shortcut loses its group and earns the dot`() {
        // The editor buckets recorded shortcuts under "Mine" rather than
        // wherever the host filed them, which is a decision made once here
        // rather than repeated at every place that reads a group.
        val entry = LibraryEntry(3, "ctrl+alt+p", Origin.RECORDED, ShortcutGroup.OTHER, "Mine")
        val offering = Offering.of(entry)
        assertEquals(null, offering.group)
        assertEquals(true, offering.mine)
    }

    @Test
    fun `right click comes first`() {
        // It is the one somebody arrives looking for, and a library is a list
        // people scan rather than search.
        assertEquals(Action.Click(Action.Button.RIGHT), buttons().first().action)
    }

    @Test
    fun `a button becomes a slot that sends a button`() {
        val slot = buttons().first().asSlot()
        assertTrue(slot.action is Action.Click)
    }
}
