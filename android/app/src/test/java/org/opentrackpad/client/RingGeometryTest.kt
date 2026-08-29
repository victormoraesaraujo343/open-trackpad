package org.opentrackpad.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The ring's angles, checked from both directions.
 *
 * The bug this is really about is a menu that highlights one wedge and fires
 * another, which happens when the drawing maths and the hit-testing maths drift
 * apart. The last test here is the one that would catch it.
 */
class RingGeometryTest {

    private val inner = 46f
    private val outer = 112f

    /** A point at [degrees] and [radius] from the centre. */
    private fun point(degrees: Float, radius: Float): Pair<Float, Float> {
        val angle = Math.toRadians(degrees.toDouble())
        return cos(angle).toFloat() * radius to sin(angle).toFloat() * radius
    }

    private fun wedgeAt(degrees: Float, radius: Float = 79f): Int {
        val (x, y) = point(degrees, radius)
        return RingGeometry.at(x, y, inner, outer)
    }

    @Test
    fun `the hub and the space beyond the ring are not wedges`() {
        assertEquals(RingGeometry.HUB, wedgeAt(0f, radius = 0f))
        assertEquals(RingGeometry.HUB, wedgeAt(137f, radius = inner - 1f))
        assertEquals(RingGeometry.OUTSIDE, wedgeAt(137f, radius = outer + 1f))
        // The boundaries themselves belong to the ring rather than to nothing.
        assertNotEquals(RingGeometry.HUB, wedgeAt(0f, radius = inner))
        assertNotEquals(RingGeometry.OUTSIDE, wedgeAt(0f, radius = outer))
    }

    @Test
    fun `straight up is the middle of a wedge, not a seam`() {
        // The one direction a thumb can find without looking. A boundary there
        // would waste it, so a wedge is centred on it — and the proof is that a
        // degree either side of straight up is still the same wedge.
        val up = wedgeAt(-90f)
        assertEquals(up, wedgeAt(-91f))
        assertEquals(up, wedgeAt(-89f))
        assertEquals(-90f, RingGeometry.middleOf(up), 0.01f)
    }

    @Test
    fun `every direction lands on exactly one wedge`() {
        val seen = mutableSetOf<Int>()
        var degrees = -180f
        while (degrees < 180f) {
            val index = wedgeAt(degrees)
            assertEquals(
                "$degrees fell outside the ring",
                true,
                index in 0 until RingGeometry.WEDGES,
            )
            seen += index
            degrees += 0.5f
        }
        assertEquals(RingGeometry.WEDGES, seen.size)
    }

    @Test
    fun `the wedges run in order all the way round`() {
        for (index in 0 until RingGeometry.WEDGES) {
            val middle = RingGeometry.middleOf(index)
            assertEquals("wedge $index", index, wedgeAt(middle))
        }
    }

    @Test
    fun `angles either side of a seam land in neighbouring wedges`() {
        for (index in 1 until RingGeometry.WEDGES) {
            val seam = RingGeometry.startOf(index)
            assertEquals("just after the seam at $seam", index, wedgeAt(seam + 0.5f))
            assertEquals("just before the seam at $seam", index - 1, wedgeAt(seam - 0.5f))
        }
    }

    @Test
    fun `the point a glyph is drawn at is inside the wedge it belongs to`() {
        // This is the whole reason the geometry is one object. Drawing goes
        // from an index to a point and hit-testing goes from a point to an
        // index; if they ever disagree, the ring highlights one wedge and fires
        // another, and nothing about the screen would say why.
        val radius = (inner + outer) / 2f
        for (index in 0 until RingGeometry.WEDGES) {
            val (x, y) = RingGeometry.centreOf(index, radius)
            assertEquals(
                "wedge $index draws its glyph outside itself",
                index,
                RingGeometry.at(x, y, inner, outer),
            )
        }
    }
}
