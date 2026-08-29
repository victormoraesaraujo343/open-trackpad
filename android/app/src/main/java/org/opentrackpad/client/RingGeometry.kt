package org.opentrackpad.client

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Where the Quick Ring's wedges are, in one place.
 *
 * Separate from the view it draws, for a reason worth stating: **the wedge that
 * lights up and the wedge that fires must be the same wedge.** Drawing works
 * forwards from an index to an angle and hit-testing works backwards from a
 * point to an index, and those are easy to get subtly out of step — half a
 * wedge here, a sign there, and a menu highlights one thing and does another.
 * One set of functions serving both is the only way they cannot disagree, and
 * being free of Android is what lets them be tested at all.
 *
 * Angles are degrees clockwise from three o'clock, which is what `atan2` and
 * `Canvas.drawArc` both use.
 */
object RingGeometry {

    /**
     * Eight wedges, and the count is structural rather than a preference.
     *
     * Eight is what the drawing has, and it is also about the most a thumb can
     * find by direction alone. The ring holds eight positions whether or not
     * eight things exist to put in them.
     */
    const val WEDGES = 8

    /** How wide one wedge is. */
    const val SWEEP = 360f / WEDGES

    /**
     * Where the first wedge begins.
     *
     * Half a wedge back from straight up, so a wedge is *centred* on twelve
     * o'clock rather than a seam landing there. Straight up is the one
     * direction a thumb can find without looking, and putting the boundary
     * between two wedges on it would waste it.
     */
    const val FIRST_EDGE = -90f - SWEEP / 2f

    /** [at] returns this for a point on the hub. */
    const val HUB = -1

    /** [at] returns this for a point beyond the outer edge. */
    const val OUTSIDE = -2

    /**
     * Which wedge a point sits on, measured from the ring's centre.
     *
     * [inner] and [outer] are the two radii, in whatever unit [dx] and [dy] are.
     */
    fun at(dx: Float, dy: Float, inner: Float, outer: Float): Int {
        val distance = hypot(dx, dy)
        if (distance < inner) return HUB
        if (distance > outer) return OUTSIDE
        val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        return indexAt(degrees)
    }

    /**
     * Which wedge an angle falls in.
     *
     * Wrapped into a full turn before dividing, because `atan2` gives -180 to
     * 180 and the first wedge starts before zero: without the wrap the wedges
     * either side of twelve o'clock come out negative and collide.
     */
    fun indexAt(degrees: Float): Int {
        val fromFirst = ((degrees - FIRST_EDGE) % 360f + 360f) % 360f
        return (fromFirst / SWEEP).toInt().coerceIn(0, WEDGES - 1)
    }

    /** Where wedge [index] begins, for drawing its arc. */
    fun startOf(index: Int): Float = FIRST_EDGE + index * SWEEP

    /** The middle of wedge [index], where its glyph and word go. */
    fun middleOf(index: Int): Float = startOf(index) + SWEEP / 2f

    /** The point at [radius] along the middle of wedge [index]. */
    fun centreOf(index: Int, radius: Float): Pair<Float, Float> {
        val angle = Math.toRadians(middleOf(index).toDouble())
        return cos(angle).toFloat() * radius to sin(angle).toFloat() * radius
    }
}
