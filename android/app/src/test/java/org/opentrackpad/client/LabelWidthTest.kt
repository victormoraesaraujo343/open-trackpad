package org.opentrackpad.client

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every default rail label fits the rail, measured in the face that ships.
 *
 * "Screenshot" sits at 97% of the room a label has and "Full screen" at 92%.
 * Both fit and neither has any margin, and that is exactly the kind of fact
 * that lives in somebody's memory until it quietly stops being true — a wider
 * glyph in a future Inter, a longer default, or the day this is translated.
 *
 * So it is a test rather than a note. It reads the shipped file's own metrics
 * rather than an approximation, because an approximation would have to be
 * generous enough to be useless at 97%.
 */
class LabelWidthTest {

    private val face = File("src/main/res/font/inter_medium.ttf")

    @Test
    fun `no default label is wider than the rail it sits on`() {
        val metrics = TypeMetrics.read(face)
        val room = Artboard.RAIL_LABEL_ROOM
        val tight = mutableListOf<String>()

        for (profile in DefaultProfiles.all) {
            for (slot in profile.shortcuts.filterNotNull()) {
                val width = metrics.width(slot.label, Artboard.LABEL_UNITS)
                assertTrue(
                    "\"${slot.label}\" is %.1f units wide and the rail gives %.1f"
                        .format(width, room),
                    width <= room,
                )
                val note = "${slot.label} at ${percent(width, room)}"
                if (width > room * 0.85f && note !in tight) tight += note
            }
        }

        // Not a failure — a record. Anything this close is one glyph away from
        // being a failure, and the names belong where somebody changing a
        // default will see them.
        println("labels with little room to spare: " + tight.joinToString(", ").ifEmpty { "none" })
    }

    @Test
    fun `the two known-tight labels are still the only tight ones`() {
        // Pins the shape of the problem rather than the numbers. If a third
        // label joins these, somebody has added one that barely fits and should
        // be told before it ships rather than after somebody reads a clipped
        // word on a rail.
        val metrics = TypeMetrics.read(face)
        val room = Artboard.RAIL_LABEL_ROOM
        val tight = DefaultProfiles.all
            .flatMap { it.shortcuts.filterNotNull() }
            .map { it.label }
            .distinct()
            .filter { metrics.width(it, Artboard.LABEL_UNITS) > room * 0.85f }
            .toSet()

        assertTrue(
            "labels close to overflowing changed: $tight",
            tight == setOf("Screenshot", "Full screen"),
        )
    }

    private fun percent(width: Float, room: Float) = "%.0f%%".format(width / room * 100)

}
