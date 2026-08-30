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
class RingGeometry private constructor(
    /**
     * How many wedges this ring has: one per destination, and no empties.
     *
     * It used to be a fixed eight, on the reasoning that a fixed count means a
     * destination never moves. That reasoning was sound and the premise was
     * not: eight was the number of *shortcuts* the ring was thought to hold,
     * and it holds destinations — four with a computer attached, two without.
     * Six empty wedges of eight do not read as room for more, they read as an
     * app that failed to load.
     *
     * A thumb can still find these without looking, better than before: two
     * wedges are two halves and five are a fifth of a turn each, which is a
     * coarser target than an eighth was.
     */
    val wedges: Int,
) {

    /** How wide one wedge is. */
    val sweep: Float = 360f / wedges

    /**
     * Where the first wedge begins.
     *
     * Half a wedge back from straight up, so a wedge is *centred* on twelve
     * o'clock rather than a seam landing there. Straight up is the one
     * direction a thumb can find without looking, and putting the boundary
     * between two wedges on it would waste it.
     */
    val firstEdge: Float = -90f - sweep / 2f



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
        val fromFirst = ((degrees - firstEdge) % 360f + 360f) % 360f
        return (fromFirst / sweep).toInt().coerceIn(0, wedges - 1)
    }

    /** Where wedge [index] begins, for drawing its arc. */
    fun startOf(index: Int): Float = firstEdge + index * sweep

    /** The middle of wedge [index], where its glyph and word go. */
    fun middleOf(index: Int): Float = startOf(index) + sweep / 2f

    /** The point at [radius] along the middle of wedge [index]. */
    fun centreOf(index: Int, radius: Float): Pair<Float, Float> {
        val angle = Math.toRadians(middleOf(index).toDouble())
        return cos(angle).toFloat() * radius to sin(angle).toFloat() * radius
    }

    companion object {
        /** [at] returns this for a point on the hub. */
        const val HUB = -1

        /** [at] returns this for a point beyond the outer edge. */
        const val OUTSIDE = -2

        /**
         * A ring for [wedges] destinations.
         *
         * One is refused: a single wedge is a whole circle, which has no
         * direction to aim in and is a button wearing a ring's clothes. There is
         * never one — Profiles and Settings need no computer and are always
         * both there — so this is a floor rather than a case that happens.
         */
        fun of(wedges: Int) = RingGeometry(wedges.coerceAtLeast(2))
    }
}
