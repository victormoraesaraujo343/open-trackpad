package org.opentrackpad.client

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Sizes the text on a screen in artboard units.
 *
 * The screens built from XML declare their type as a `tag` holding the number
 * of units — `android:tag="13"` — and this walks the tree once and applies it.
 * The `sp` value in the layout stays only so the editor preview is not
 * nonsense; it never reaches a device.
 *
 * A tag rather than a dimension resource because the conversion is not knowable
 * until runtime: a unit is a fixed physical length, and how many pixels that is
 * depends on the panel this happens to be running on. A `dimen` cannot say
 * "one fifth of a millimetre".
 *
 * A walk rather than a line per view because these screens have upwards of
 * thirty pieces of text between them, and thirty hand-written conversions is
 * thirty chances to miss one — which is exactly how half a product ends up
 * sized one way and half the other.
 */
object Typography {

    /**
     * Applies every unit tag under [root].
     *
     * Views without a numeric tag are left alone, so a tag used for anything
     * else is ignored rather than misread.
     */
    fun apply(root: View, artboard: Artboard) {
        val units = (root.tag as? String)?.toFloatOrNull()
        if (units != null && root is TextView) {
            root.setTextSize(TypedValue.COMPLEX_UNIT_PX, artboard.text(units))
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) apply(root.getChildAt(index), artboard)
        }
    }
}
