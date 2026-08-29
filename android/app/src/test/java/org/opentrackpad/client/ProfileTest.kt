package org.opentrackpad.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {

    private val profile = DefaultProfiles.desktop

    @Test
    fun `the first four sit on the rail and the rest in the ring`() {
        assertEquals(Profile.RAIL_SLOTS, profile.rail.size)
        assertEquals(profile.shortcuts.size - Profile.RAIL_SLOTS, profile.ring.size)
        assertEquals(profile.shortcuts, profile.rail + profile.ring)
    }

    @Test
    fun `promoting from the ring puts it on the rail`() {
        val promoted = profile.reorder(from = 5, to = 0)
        assertEquals(profile.shortcuts[5], promoted.rail.first())
        assertTrue(profile.shortcuts[5] !in promoted.ring)
    }

    @Test
    fun `promoting one demotes another rather than losing it`() {
        val promoted = profile.reorder(from = 5, to = 0)
        assertEquals(profile.shortcuts.size, promoted.shortcuts.size)
        assertEquals(profile.shortcuts.toSet(), promoted.shortcuts.toSet())
    }

    @Test
    fun `a drag that ends nowhere useful changes nothing`() {
        assertEquals(profile, profile.reorder(from = 0, to = 0))
        assertEquals(profile, profile.reorder(from = -1, to = 0))
        assertEquals(profile, profile.reorder(from = 0, to = profile.shortcuts.size))
    }

    @Test
    fun `a profile shorter than the rail still works`() {
        val small = Profile("Small", listOf(Slot("Copy", Action.KeyChord("ctrl+c"))))
        assertEquals(1, small.rail.size)
        assertTrue(small.ring.isEmpty())
    }

    @Test
    fun `the two rails take opposite sides`() {
        assertEquals(Side.RIGHT, Side.LEFT.opposite())
        assertEquals(Side.LEFT, Settings("x", Side.RIGHT).applicationsSide)
        assertEquals(Side.RIGHT, Settings("x", Side.LEFT).applicationsSide)
    }

    @Test
    fun `every default shortcut is a key chord the host could accept`() {
        // Not a substitute for the host's own check, but it catches a typo in
        // the defaults before anyone installs them.
        val allowed = Regex("[a-z0-9]+(\\+[a-z0-9]+)*")
        for (profile in DefaultProfiles.all) {
            for (slot in profile.shortcuts) {
                val chord = (slot.action as Action.KeyChord).chord
                assertTrue("$chord is not a plausible chord", allowed.matches(chord))
                assertTrue("${slot.label} has no label", slot.label.isNotBlank())
            }
        }
    }
}
