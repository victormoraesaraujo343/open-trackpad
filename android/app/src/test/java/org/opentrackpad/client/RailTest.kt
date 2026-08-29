package org.opentrackpad.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the design encodes, tested rather than trusted: a rail is always
 * five slots, and the fifth one means the same thing everywhere.
 */
class RailTest {

    private val profile = DefaultProfiles.desktop

    @Test
    fun `a rail is always five slots`() {
        assertEquals(Rails.SLOTS, Rails.shortcuts(profile).size)
        assertEquals(Rails.SLOTS, Rails.overflow(profile).size)
    }

    @Test
    fun `the quick ring is the fifth slot and is the only filled one`() {
        val rail = Rails.shortcuts(profile)
        assertEquals(SlotPress.QuickRing, rail[4]?.press)
        assertEquals(SlotStyle.PRIMARY, rail[4]?.style)
        assertEquals(1, rail.count { it?.style == SlotStyle.PRIMARY })
    }

    @Test
    fun `the first four are the profile's own shortcuts`() {
        val rail = Rails.shortcuts(profile)
        for (index in 0 until Profile.RAIL_SLOTS) {
            assertEquals(SlotPress.Send(profile.shortcuts[index].action), rail[index]?.press)
            assertEquals(profile.shortcuts[index].label, rail[index]?.label)
        }
    }

    @Test
    fun `a short profile leaves slots empty rather than growing the others`() {
        // The surface is used without looking, so a button may never move
        // because the profile beside it got shorter.
        val small = Profile("Small", listOf(Slot("Copy", Action.KeyChord("ctrl+c"))))
        val rail = Rails.shortcuts(small)
        assertEquals(Rails.SLOTS, rail.size)
        assertNotNull(rail[0])
        assertNull(rail[1])
        assertNull(rail[2])
        assertNull(rail[3])
        // The fifth slot keeps its meaning even when the four above it are bare.
        assertEquals(SlotPress.QuickRing, rail[4]?.press)
    }

    @Test
    fun `a long profile does not push the quick ring out of slot five`() {
        val long = Profile("Long", List(20) { Slot("K$it", Action.KeyChord("f1")) })
        val rail = Rails.shortcuts(long)
        assertEquals(Rails.SLOTS, rail.size)
        assertEquals(SlotPress.QuickRing, rail[4]?.press)
    }

    @Test
    fun `the opposite rail fills all five slots`() {
        // Nothing is held back for the recently used applications: when they
        // arrive they replace this rail whole rather than moving into a space
        // kept for them.
        val rail = Rails.overflow(profile)
        assertEquals(Rails.SLOTS, rail.size)
        assertTrue(rail.all { it != null })
        for (index in 0 until Rails.SLOTS) {
            assertEquals(profile.ring[index].label, rail[index]?.label)
        }
    }

    @Test
    fun `both rails together show nine shortcuts and repeat none`() {
        for (profile in DefaultProfiles.all) {
            val shown = (Rails.shortcuts(profile) + Rails.overflow(profile))
                .filterNotNull()
                .filter { it.press is SlotPress.Send }
            assertEquals("${profile.name} does not fill both rails", 9, shown.size)
            assertEquals("${profile.name} repeats a slot", 9, shown.map { it.label }.toSet().size)
        }
    }

    @Test
    fun `no default shortcut closes anything`() {
        // Shortcuts fire the moment a finger lands, so nothing that destroys
        // work may ship on a rail somebody has not chosen.
        val destructive = setOf("alt+f4", "ctrl+w", "ctrl+q", "ctrl+shift+q")
        for (slot in DefaultProfiles.desktop.shortcuts) {
            val chord = (slot.action as Action.KeyChord).chord
            assertTrue("$chord ships on the Desktop rail", chord !in destructive)
        }
    }

    @Test
    fun `a dead rail keeps its shape and refuses every press`() {
        val dead = Rails.shortcuts(profile).deadened()
        assertEquals(Rails.SLOTS, dead.size)
        for (index in Rails.shortcuts(profile).indices) {
            assertEquals(Rails.shortcuts(profile)[index] == null, dead[index] == null)
        }
        assertTrue(dead.filterNotNull().all { it.press == SlotPress.None })
        assertTrue(dead.filterNotNull().all { it.style == SlotStyle.DEAD })
    }

    @Test
    fun `every slot has a glyph, even one nobody drew`() {
        for (profile in DefaultProfiles.all) {
            for (slot in Rails.shortcuts(profile).filterNotNull()) {
                assertTrue("${slot.label} has no glyph", slot.icon.isNotBlank())
            }
        }
        assertEquals(RailIcons.path(RailIcons.FALLBACK), RailIcons.path("no such glyph"))
    }
}
