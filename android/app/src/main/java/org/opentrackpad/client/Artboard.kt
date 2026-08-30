package org.opentrackpad.client

import android.util.DisplayMetrics
import kotlin.math.roundToInt

/**
 * The drawing, rendered at the size it was drawn.
 *
 * Every number in the design — a 78-unit rail, a 12-unit margin, a 12-unit
 * label — is a **physical length**, not a density-independent pixel. The
 * artboards are 780 units across the long edge of a 6.7-inch phone held in
 * landscape, so one unit is a fixed fraction of a millimetre and this class is
 * the only place that conversion happens.
 *
 * ## Everything is millimetres, and the font scale multiplies on top
 *
 * There was a rule here that some screens followed the system instead, on the
 * grounds that text somebody reads should honour the size they asked for. It
 * was withdrawn, because the two things were never in conflict.
 *
 * **Size in millimetres so the default is physically right, then multiply by
 * [android.content.res.Configuration.fontScale] so somebody who asked for
 * larger text still gets it.** `fontScale` is the setting that carries the
 * accessibility argument; `densityDpi`, which the old rule was actually
 * reading, is a display-size preference and carries none of it.
 *
 * The measured cost of getting that wrong, on the phone this is drawn for:
 * substituting the system's sizing for millimetres shrank every piece of text
 * by **24%**, flat, at every size. His display-size setting cost a further 11%
 * on top. Sorting every element in the product by physical cap height put
 * every system-sized one below every millimetre-sized one with no interleaving
 * at all — two stacks rather than one range, which is what a systematic error
 * looks like from the outside.
 *
 * dp is relative to a display density the person can change: Android's
 * display-size slider alters `densityDpi` and every dp on the screen with it.
 * That is correct for text you read and wrong for a button you hit without
 * looking, because a trackpad's buttons are a fixed size in the hand and do not
 * move because somebody changed a setting. That distinction is what separates
 * an object from an app, and this design leans on being an object.
 *
 * It also failed in practice, which is how it was found. The artboards are 780
 * units wide; the phone they were drawn for reports **1029 dp**. Reading units
 * as dp therefore rendered everything about a quarter smaller than drawn, and
 * the first anybody knew of it was Victor saying he could not read the labels.
 * Widening the rail from 70 to 78 recovered a third of that gap and would have
 * needed doing again on the next device. One rule beats a number per phone.
 *
 * The measurement comes from [DisplayMetrics.xdpi] and `ydpi`, which describe
 * the panel and do **not** move with the display-size slider. `densityDpi`
 * does, which is exactly why it is not used here.
 *
 * ## The clamps
 *
 * A fixed physical rail takes a larger share of a small phone. Two limits keep
 * that from eating the product:
 *
 * - the two rails together never take more than [RAILS_MAX_FRACTION] of the
 *   width, and
 * - a rail is never narrower than [MIN_TOUCH_TARGET_DP].
 *
 * The ceiling is applied last, so when the two disagree the trackpad wins. It
 * is the reason the thing exists.
 */
class Artboard private constructor(
    /** How many real pixels one artboard unit is worth. */
    val pixelsPerUnit: Float,

    private val pixelsPerMillimetre: Float,

    /** What the person asked text to be multiplied by. 1 unless they changed it. */
    val fontScale: Float,

    /** Whether a limit changed the size, which is worth saying out loud in a report. */
    val clamped: Boolean,
) {
    /** [units] of the drawing, in pixels on this screen. */
    fun px(units: Float): Float = units * pixelsPerUnit

    /**
     * The same, for anything with words in it.
     *
     * Identical to [px] until somebody turns their text size up, which is the
     * one system preference that is about reading rather than about layout.
     */
    fun text(units: Float): Float = px(units) * fontScale

    /** The same, rounded to a whole pixel, for anything that sets a view's size. */
    fun size(units: Float): Int = (units * pixelsPerUnit).roundToInt()

    /**
     * What [units] actually measures on the glass, for reporting.
     *
     * Derived from the scale that was used rather than from [MM_PER_UNIT], so
     * that a clamped layout reports the size it really is instead of the size
     * it was asked to be.
     */
    fun millimetres(units: Float): Float = px(units) / pixelsPerMillimetre

    companion object {
        /**
         * The width of the artboards, in their own units.
         *
         * Every screen is drawn 780 by 360. The height is not needed: a
         * drawing is scaled by one factor or it is not a drawing.
         */
        const val WIDTH_UNITS = 780f

        /**
         * What those 780 units span in the real world.
         *
         * The long edge of a 6.7-inch 20:9 phone held in landscape: a 6.7-inch
         * diagonal across 2412 by 1080 is 6.115 inches wide, or 155.3 mm. It is
         * the same figure `docs/PROTOCOL.md` uses in its worked handshake for
         * "a 6.7-inch phone held in landscape", which is the device the whole
         * design is drawn against.
         */
        const val REFERENCE_WIDTH_MM = 155.3f

        /** One unit of the drawing, as a real length. About a fifth of a millimetre. */
        const val MM_PER_UNIT = REFERENCE_WIDTH_MM / WIDTH_UNITS

        /** A rail, in units of the drawing. */
        const val RAIL_UNITS = 78f

        /** The word under a glyph, and the space kept either side of it. */
        const val LABEL_UNITS = 12f
        const val LABEL_INSET_UNITS = 5f

        /**
         * The smallest anything anybody has to read may be.
         *
         * Twelve units, which is what a rail label measures — the one piece of
         * type in this product confirmed readable on glass by the person who
         * owns the phone. Nothing somebody must read should be smaller than
         * what they read without looking.
         */
        const val MIN_READABLE_UNITS = 12f

        /**
         * How wide a rail label may be before it is cut short.
         *
         * Public, and free of Android, so `LabelWidthTest` can hold every
         * default to it. Two of them sit above 90% of this, which is a fact
         * that should fail a build rather than live in somebody's memory.
         */
        const val RAIL_LABEL_ROOM = RAIL_UNITS - LABEL_INSET_UNITS * 2

        /** Both rails together may not take more of the width than this. */
        const val RAILS_MAX_FRACTION = 0.25f

        /** No control is ever smaller than the platform's own touch target. */
        const val MIN_TOUCH_TARGET_DP = 44f

        private const val MM_PER_INCH = 25.4f

        /**
         * Works out the scale for this screen.
         *
         * [screenWidthPixels] is the whole display in the orientation being
         * drawn, which is what the ceiling is a fraction of.
         */
        fun measure(
            metrics: DisplayMetrics,
            screenWidthPixels: Int,
            fontScale: Float = 1f,
        ): Artboard {
            val pixelsPerMm = SurfaceMetrics.pixelsPerMillimetre(metrics).toFloat()
            val ideal = MM_PER_UNIT * pixelsPerMm

            // Both limits are expressed as what a unit would have to be worth
            // for the rail to come out at the limit, so they can be compared
            // against `ideal` directly rather than applied twice.
            val floor = MIN_TOUCH_TARGET_DP * metrics.density / RAIL_UNITS
            val ceiling = screenWidthPixels * RAILS_MAX_FRACTION / 2f / RAIL_UNITS

            // Floor first, ceiling last: on a screen too small to honour both,
            // the rails give way rather than the pad.
            val scaled = ideal.coerceAtLeast(floor).coerceAtMost(ceiling)
            return Artboard(scaled, pixelsPerMm, fontScale, clamped = scaled != ideal)
        }
    }
}
