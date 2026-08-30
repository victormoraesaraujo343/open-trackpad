package org.opentrackpad.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {

    private val profile = DefaultProfiles.desktop

    @Test
    fun `the first four sit on the rail and the rest on the rail opposite`() {
        assertEquals(Profile.RAIL_SLOTS, profile.rail.size)
        assertEquals(profile.shortcuts.size - Profile.RAIL_SLOTS, profile.overflow.size)
        assertEquals(profile.shortcuts, profile.rail + profile.overflow)
    }

    @Test
    // Nothing calls `reorder` — the Quick Ring holds destinations, so there is
    // nothing in it to promote. Kept because it is still the correct operation
    // on this list, and tested so that it still is if a second way to rearrange
    // ever arrives.
    fun `moving a shortcut forward puts it on the rail`() {
        val promoted = profile.reorder(from = 5, to = 0)
        assertEquals(profile.shortcuts[5], promoted.rail.first())
        assertTrue(profile.shortcuts[5] !in promoted.overflow)
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
        assertTrue(small.overflow.isEmpty())
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
            for (slot in profile.shortcuts.filterNotNull()) {
                val chord = (slot.action as Action.KeyChord).chord
                assertTrue("$chord is not a plausible chord", allowed.matches(chord))
                assertTrue("${slot.label} has no label", slot.label.isNotBlank())
            }
        }
    }

    @Test
    fun `emptying a slot leaves every other slot where it was`() {
        // The reason the list is positions rather than contents. A rail is used
        // without looking, so removing the second button may not pull the third
        // one up into its place — that is a button moving, and a button that
        // moves is a button pressed by mistake.
        val cleared = profile.clear(1)
        assertEquals(profile.shortcuts.size, cleared.shortcuts.size)
        assertNull(cleared.shortcuts[1])
        for (index in profile.shortcuts.indices) {
            if (index == 1) continue
            assertEquals("position $index moved", profile.shortcuts[index], cleared.shortcuts[index])
        }
    }

    @Test
    fun `a shortcut lands in the position it was dropped on`() {
        val put = profile.clear(2).put(2, Slot("Find", Action.KeyChord("ctrl+f")))
        assertEquals("Find", put.shortcuts[2]?.label)
        assertEquals(profile.shortcuts.size, put.shortcuts.size)
        assertEquals(profile.shortcuts[3], put.shortcuts[3])
    }

    @Test
    fun `dropping past the end grows the list with holes, not with filler`() {
        val small = Profile("Small", listOf(Slot("Copy", Action.KeyChord("ctrl+c"))))
        val put = small.put(4, Slot("Paste", Action.KeyChord("ctrl+v")))
        assertEquals(5, put.shortcuts.size)
        assertEquals("Paste", put.shortcuts[4]?.label)
        // The positions in between are empty rather than filled with something
        // that would appear on the rail as a real button nobody asked for.
        for (index in 1..3) assertNull(put.shortcuts[index])
    }

    @Test
    fun `an empty position survives being written and read`() {
        // Skipped on save, it would vanish on reload and pull every position
        // after it up one, which is the shuffling this model exists to prevent.
        val holed = profile.clear(1).clear(5)
        val back = ProfileStore.decode(
            ProfileStore.encode(ProfileStore.Stored(DefaultProfiles.settings, listOf(holed)))
        ).profiles.single()
        assertEquals(holed.shortcuts.size, back.shortcuts.size)
        assertNull(back.shortcuts[1])
        assertNull(back.shortcuts[5])
        assertEquals(holed.shortcuts, back.shortcuts)
    }
}
