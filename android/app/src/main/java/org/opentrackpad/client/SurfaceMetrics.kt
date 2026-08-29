package org.opentrackpad.client

import android.util.DisplayMetrics

/**
 * The touch surface, in pixels and in real millimetres.
 *
 * The physical size is the part the host cannot work out for itself, and it is
 * what makes the virtual touchpad behave the same whatever phone is plugged in.
 */
data class SurfaceMetrics(
    val widthPixels: Int,
    val heightPixels: Int,
    val widthMicrometres: Int,
    val heightMicrometres: Int,
) {
    companion object {
        private const val MICROMETRES_PER_INCH = 25_400.0

        /**
         * Below or above this, a reported dpi is not believable. Some devices
         * report zero, and some report the density bucket instead of a
         * measurement.
         */
        private const val MIN_PLAUSIBLE_DPI = 80.0
        private const val MAX_PLAUSIBLE_DPI = 1200.0

        /**
         * Measures a view of [widthPixels] by [heightPixels].
         *
         * A single dpi figure is used for both axes on purpose. `xdpi` and
         * `ydpi` are reported in the screen's natural orientation, and working
         * out which one applies after a rotation is fiddly and easy to get
         * backwards. Phone pixels are square to well under a percent — this
         * device reports 397.6 and 392.7 — so averaging them is both simpler and
         * more robust than guessing at the rotation.
         */
        fun measure(
            metrics: DisplayMetrics,
            widthPixels: Int,
            heightPixels: Int,
        ): SurfaceMetrics {
            val micrometresPerPixel = MICROMETRES_PER_INCH / plausibleDpi(metrics)
            return SurfaceMetrics(
                widthPixels = widthPixels,
                heightPixels = heightPixels,
                widthMicrometres = (widthPixels * micrometresPerPixel).toInt().coerceAtLeast(1),
                heightMicrometres = (heightPixels * micrometresPerPixel).toInt().coerceAtLeast(1),
            )
        }

        /**
         * The screen's real dpi, falling back through progressively less exact
         * sources rather than returning something absurd.
         */
        private fun plausibleDpi(metrics: DisplayMetrics): Double {
            // Each axis is judged before averaging, never after. A device that
            // reports zero for one axis and a good figure for the other would
            // otherwise average to something that looks believable and is off by
            // a factor of two.
            val believable = listOf(metrics.xdpi.toDouble(), metrics.ydpi.toDouble())
                .filter { it.isPlausible() }
            if (believable.isNotEmpty()) return believable.average()

            // Last resort: the density bucket. Coarse, but it is a real number
            // and keeps the pad within a factor of a phone rather than a wall.
            val bucket = metrics.densityDpi.toDouble()
            return if (bucket.isPlausible()) bucket else 400.0
        }

        private fun Double.isPlausible() =
            !isNaN() && this >= MIN_PLAUSIBLE_DPI && this <= MAX_PLAUSIBLE_DPI
    }
}
