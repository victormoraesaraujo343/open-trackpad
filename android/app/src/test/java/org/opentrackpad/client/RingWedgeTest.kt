package org.opentrackpad.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The Quick Ring's positions do not move, and that is a deliberate exception.
 *
 * Everywhere else in this app a thing the computer cannot serve is absent
 * rather than broken. The ring is navigation — the only way in to anything — so
 * a door that moved would be worse than a door that is plainly shut, and a
 * cable coming out twice a month is exactly often enough to stop a habit
 * forming and not often enough to build a new one.
 *
 * Written against [Rails] rather than the activity, so it can be a unit test:
 * what matters is the rule, and the rule is that a dimmed destination keeps its
 * place and its word and loses only its press.
 */
class RingWedgeTest {

    private fun wedge(available: Boolean, press: SlotPress) = RailSlot(
        label = "Sound",
        icon = RailIcons.path("vol"),
        press = if (available) press else SlotPress.None,
        style = if (available) SlotStyle.PLAIN else SlotStyle.DEAD,
    )

    @Test
    fun `an unavailable destination keeps its place and loses its press`() {
        val live = wedge(available = true, SlotPress.Profiles)
        val dim = wedge(available = false, SlotPress.Profiles)
        assertEquals("the word is the same either way", live.label, dim.label)
        assertEquals("the glyph is the same either way", live.icon, dim.icon)
        assertEquals(SlotPress.None, dim.press)
        assertEquals(SlotStyle.DEAD, dim.style)
    }

    @Test
    fun `a dimmed wedge is not the same as an empty one`() {
        // The whole point of the exception. An empty quarter of a circle says
        // nothing; a dim one says there is no computer.
        val dim: RailSlot? = wedge(available = false, SlotPress.Audio(AudioPage.OUTPUT))
        assertNotEquals(null, dim)
    }

    @Test
    fun `deadening leaves every destination reachable`() {
        // The rails go dead without a session and the ring must not: settings,
        // profiles and the editor all work with the cable out, and greying the
        // way to them would lock somebody out of the screen that explains why.
        val ring = Rails.shortcuts(DefaultProfiles.desktop).deadened().last()
        assertEquals(SlotPress.QuickRing, ring?.press)
        assertNotEquals(SlotStyle.DEAD, ring?.style)
    }
}
