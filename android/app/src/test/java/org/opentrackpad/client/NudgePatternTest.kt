package org.opentrackpad.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NudgePatternTest {

    @Test
    fun `the cycle returns to where it started`() {
        assertEquals(NudgePattern.at(0), NudgePattern.at(NudgePattern.size))
    }

    @Test
    fun `no step repeats the one before it`() {
        for (step in 1..NudgePattern.size) {
            assertNotEquals(
                "step $step did not move",
                NudgePattern.at(step - 1),
                NudgePattern.at(step),
            )
        }
    }

    @Test
    fun `the offsets stay small enough not to be noticed`() {
        for (step in 0 until NudgePattern.size) {
            val (x, y) = NudgePattern.at(step)
            assertTrue("$x, $y is too far", x in -2..2 && y in -2..2)
        }
    }

    @Test
    fun `the pattern is balanced, so nothing drifts off centre`() {
        val offsets = (0 until NudgePattern.size).map { NudgePattern.at(it) }
        assertEquals(0, offsets.sumOf { it.first })
        assertEquals(0, offsets.sumOf { it.second })
    }

    @Test
    fun `steps wrap in both directions rather than failing`() {
        assertEquals(NudgePattern.at(1), NudgePattern.at(1 + NudgePattern.size * 3))
        assertEquals(NudgePattern.at(NudgePattern.size - 1), NudgePattern.at(-1))
    }
}
