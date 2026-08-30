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

    /**
     * The ring under test.
     *
     * Five, because that is what a connected session makes today and the
     * awkward number: an odd count means no wedge is opposite another, so a
     * sign error that would cancel out on an even ring shows up here.
     */
    private val ring = RingGeometry.of(5)

    private fun wedgeAt(degrees: Float, radius: Float = 79f, on: RingGeometry = ring): Int {
        val (x, y) = point(degrees, radius)
        return on.at(x, y, inner, outer)
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
        assertEquals(-90f, ring.middleOf(up), 0.01f)
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
                index in 0 until ring.wedges,
            )
            seen += index
            degrees += 0.5f
        }
        assertEquals(ring.wedges, seen.size)
    }

    @Test
    fun `the wedges run in order all the way round`() {
        for (index in 0 until ring.wedges) {
            val middle = ring.middleOf(index)
            assertEquals("wedge $index", index, wedgeAt(middle))
        }
    }

    @Test
    fun `angles either side of a seam land in neighbouring wedges`() {
        for (index in 1 until ring.wedges) {
            val seam = ring.startOf(index)
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
        for (index in 0 until ring.wedges) {
            val (x, y) = ring.centreOf(index, radius)
            assertEquals(
                "wedge $index draws its glyph outside itself",
                index,
                ring.at(x, y, inner, outer),
            )
        }
    }

    @Test
    fun `every size of ring covers every direction exactly once`() {
        // The count now comes from how many destinations exist, so the shape is
        // a family rather than one drawing. Two is a connection-less session,
        // five is a fully granted one, and the sizes between are what a host
        // granting some domains and not others produces.
        for (count in 2..8) {
            val sized = RingGeometry.of(count)
            val seen = mutableSetOf<Int>()
            var degrees = -180f
            while (degrees < 180f) {
                val index = wedgeAt(degrees, on = sized)
                assertEquals(
                    "$degrees fell outside a ring of $count",
                    true,
                    index in 0 until count,
                )
                seen += index
                degrees += 0.5f
            }
            assertEquals("a ring of $count did not use every wedge", count, seen.size)
        }
    }

    @Test
    fun `straight up stays the middle of a wedge at every size`() {
        // The property that makes the ring usable without looking, and the one
        // most likely to break when the sweep stops being a constant.
        for (count in 2..8) {
            val sized = RingGeometry.of(count)
            val up = wedgeAt(-90f, on = sized)
            assertEquals("a ring of $count moved straight up", -90f, sized.middleOf(up), 0.01f)
        }
    }

    @Test
    fun `a ring is never a single wedge`() {
        // One wedge is a whole circle: no direction to aim in, and a swipe
        // anywhere chooses it. There is never one in practice, so this is a
        // floor rather than a case — but a floor that is not enforced is a
        // comment.
        assertEquals(2, RingGeometry.of(1).wedges)
        assertEquals(2, RingGeometry.of(0).wedges)
    }
}
