package org.opentrackpad.client

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How many columns of shortcut chips fit, worked out rather than copied.
 *
 * `ProfileEditor.dc.html` draws three, and it was drawn when the type floor was
 * ten units. The floor is thirteen now, for a reason that came from Victor
 * being unable to read the rail on glass — so the drawing's column count is the
 * one number in it that cannot simply be taken.
 *
 * **Two columns of readable chips beat three of clipped ones.** A picker exists
 * to let somebody recognise a shortcut; a name cut to "Toggle Present W…"
 * defeats the only thing the screen is for.
 *
 * Measured against the shipped face at the size the chips actually use, for the
 * same reason [LabelWidthTest] is: an approximation carries an error larger
 * than the margin being judged.
 */
class LibraryGridTest {

    private val face = File("src/main/res/font/inter_medium.ttf")

    // Artboard units, from ProfileEditor.dc.html.
    private val panel = 468f          // the library column, between the rails and the edge
    private val panelPadding = 10f
    private val chipPaddingX = 8f
    private val glyph = 14f
    private val glyphGap = 7f
    private val columnGap = 6f

    /** The room a name has inside one chip, at [columns] across. */
    private fun room(columns: Int): Float {
        val usable = panel - panelPadding * 2f
        val chip = (usable - columnGap * (columns - 1)) / columns
        return chip - chipPaddingX * 2f - glyph - glyphGap
    }

    /**
     * What the picker actually has to hold.
     *
     * The app's own defaults plus the shapes a real desktop sends — a KDE
     * window action is the long end of this, and it is exactly what Victor's
     * import offered.
     */
    private val names = DefaultProfiles.all
        .flatMap { it.shortcuts.filterNotNull() }
        .map { it.label }
        .distinct() + listOf(
        "Switch to Next Keyboard Layout",
        "Toggle Present Windows",
        "Show Desktop Grid",
        "Open Terminal",
    )

    private val size = 13f   // What a library chip draws its name at.

    @Test
    fun `three columns cut ordinary names, so the drawing's three does not stand`() {
        val metrics = TypeMetrics.read(face)
        val cut = names.filter { metrics.width(it, size) > room(3) }

        println("a name has %.1f units at three columns and %.1f at two"
            .format(room(3), room(2)))
        println("cut at three: $cut")

        // Not one outlier — "Toggle Present Windows" and "Show Desktop Grid"
        // are ordinary KDE shortcut names, and both came off Victor's own
        // desktop in the import offer he was shown.
        assertTrue(
            "three columns now fit everything, so the drawing may be taken literally",
            cut.size > 1,
        )
    }

    @Test
    fun `two columns fit everything but the longest outlier`() {
        val metrics = TypeMetrics.read(face)
        val cut = names.filter { metrics.width(it, size) > room(2) }
        println("cut at two: $cut")

        // One name of twenty-seven, and it is thirty-one characters. A picker
        // cannot be made wide enough for every name a desktop can invent; it
        // can be made wide enough that truncation is the exception rather than
        // the rule, and that is the difference between two columns and three.
        assertTrue("two columns cut more than the one outlier: $cut", cut.size <= 1)
    }
}
