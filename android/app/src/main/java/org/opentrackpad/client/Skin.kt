package org.opentrackpad.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * How a surface is painted — which is what a visual identity actually is.
 *
 * [Palette] answers *what colour*, and that turned out to be the smaller half.
 * The second identity Victor approved is skeuomorphic: chamfered keycaps, cast
 * shadows, an inner highlight, an LED in the corner of a lit key. Counted
 * across its forty-two artboards it uses roughly sixteen gradients and fifteen
 * shadows **per screen**. None of that is a colour. Repainting the palette
 * would produce the same flat drawing in different hues, which would be neither
 * identity and worse than either.
 *
 * So a theme selects a **drawing routine**, not a set of numbers, and every
 * view has to ask for a surface instead of painting one. That is why the
 * current look moves behind this interface first, painting exactly what it
 * paints today: the second identity is then a second implementation rather than
 * a rewrite, and the first one can be proved not to have moved.
 *
 * ## Two axes, not one list of states
 *
 * The first version of this had a single `KeyState` of REST, PRESSED, LIT and
 * DEAD, and `PillToggle` broke it inside ten minutes. "Selected" is not one
 * appearance in this app: a chosen segment fills with the hairline, an active
 * rail slot takes a lime *border*, a primary slot takes a lime *fill*, and a
 * switch that is on is lime all over. Folding those into one LIT gave a value
 * that had to mean four things.
 *
 * They are two independent questions. **What a control means** — plain, this
 * one now, the important one, unavailable — is [SlotStyle], which this app
 * already had and which every rail slot already carries. **What a finger is
 * doing to it** is a boolean. Pressing an active slot is both at once, which a
 * single list cannot say at all.
 *
 * So the app's own vocabulary is reused rather than shadowed by a parallel one.
 * A second enum meaning almost the same thing is how two files drift.
 *
 * ## Kept deliberately small
 *
 * One method per **component**, not per shape, and that is a choice rather than
 * an oversight. A segment in a picker looked like a key with a flag until the
 * pixels were compared: an unchosen segment draws *nothing at all*, sitting in
 * the recess that holds it, while a plain rail slot always draws its own
 * background and edge. Folding them together would have meant a "plain" that
 * paints in one place and not in the other, which is not one component.
 *
 * The cost is that this interface grows a method whenever a genuinely new
 * object appears. That is the right cost: the second identity draws each of
 * these differently anyway — a key gets a chamfer, a switch gets a real
 * throw, a recess gets an inner shadow — so a shared abstraction would have to
 * be split again the moment it was implemented.
 *
 * It will keep growing as views move behind it. Guessing at those now is the
 * thing to avoid: an interface designed against imagined callers fits none of
 * them, and this one is being argued on two small controls precisely so being
 * wrong is cheap here rather than expensive after the rail.
 */
interface Skin {

    /** A raised area that holds things: a card, a menu, the trouble card. */
    fun panel(canvas: Canvas, bounds: RectF, radius: Float)

    /** A recessed area that things sit *in*: a segmented picker, a fader track. */
    fun inset(canvas: Canvas, bounds: RectF, radius: Float)

    /**
     * Something you press: a rail slot, a segment, a chip.
     *
     * [style] is what it means and [pressed] is what a finger is doing, and
     * both at once is a real combination rather than an edge case — a lit slot
     * under a thumb is the commonest thing on this surface.
     */
    fun key(canvas: Canvas, bounds: RectF, radius: Float, style: SlotStyle, pressed: Boolean)

    /** What colour a word or a glyph goes on such a key. */
    fun ink(style: SlotStyle, pressed: Boolean): Int

    /**
     * One option in a picker.
     *
     * Its own component rather than a key, because an unchosen one is invisible
     * — it is the recess showing through, not a surface of its own.
     */
    fun segment(canvas: Canvas, bounds: RectF, radius: Float, chosen: Boolean)

    /** The word on one. */
    fun segmentInk(chosen: Boolean): Int

    /** The channel a switch's knob runs in. */
    fun track(canvas: Canvas, bounds: RectF, radius: Float, on: Boolean)

    /** The knob itself, centred on [x], [y]. */
    fun knob(canvas: Canvas, x: Float, y: Float, radius: Float, on: Boolean)
}

/**
 * The identity this app has always had: flat, dark, one hairline, no depth.
 *
 * Every number here is lifted from what the views drew before they asked
 * anything, and there is a pixel check that fails if any of it moved. It should
 * read as boring — that is the evidence that nothing was redesigned on the way
 * behind the interface.
 */
class MinimalSkin(context: Context) : Skin {

    private val palette = Palette.of(context)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /**
     * The hairline, and why it is measured here rather than passed in.
     *
     * A stroke is drawn centred on the path, so a one-pixel line at the very
     * edge of a shape loses half of itself outside it. Every view that drew a
     * bordered thing used to inset the rectangle by half the stroke before
     * stroking it, which is the same three lines in six places and the same
     * three lines to get wrong. The surface owns its own edge now.
     */
    private val hairline: Float

    init {
        val artboard = Artboard.measure(
            context.resources.displayMetrics,
            context.resources.displayMetrics.widthPixels,
            context.resources.configuration.fontScale,
        )
        hairline = artboard.px(1f).coerceAtLeast(1f)
        line.strokeWidth = hairline
    }

    override fun panel(canvas: Canvas, bounds: RectF, radius: Float) {
        fill.color = palette.panel
        canvas.drawRoundRect(bounds, radius, radius, fill)
        edge(canvas, bounds, radius, palette.hairline)
    }

    override fun inset(canvas: Canvas, bounds: RectF, radius: Float) {
        fill.color = palette.inset
        canvas.drawRoundRect(bounds, radius, radius, fill)
        edge(canvas, bounds, radius, palette.hairline)
    }

    override fun key(
        canvas: Canvas,
        bounds: RectF,
        radius: Float,
        style: SlotStyle,
        pressed: Boolean,
    ) {
        fill.color = when {
            style == SlotStyle.PRIMARY && pressed -> palette.limeBright
            style == SlotStyle.PRIMARY -> palette.lime
            pressed -> palette.hairline
            style == SlotStyle.ACTIVE -> palette.hairline
            else -> palette.inset
        }
        canvas.drawRoundRect(bounds, radius, radius, fill)
        edge(
            canvas,
            bounds,
            radius,
            when {
                style == SlotStyle.PRIMARY && pressed -> palette.limeBright
                style == SlotStyle.PRIMARY -> palette.lime
                style == SlotStyle.ACTIVE -> palette.lime
                else -> palette.hairline
            },
        )
    }

    override fun ink(style: SlotStyle, pressed: Boolean): Int = when {
        style == SlotStyle.PRIMARY -> palette.ground
        style == SlotStyle.ACTIVE -> palette.lime
        style == SlotStyle.DEAD -> palette.faint
        pressed -> palette.ink
        else -> palette.secondary
    }

    override fun segment(canvas: Canvas, bounds: RectF, radius: Float, chosen: Boolean) {
        // Nothing when it is not chosen. The recess behind it is the background,
        // and drawing one here would put a second surface inside the first.
        if (!chosen) return
        fill.color = palette.hairline
        canvas.drawRoundRect(bounds, radius, radius, fill)
    }

    override fun segmentInk(chosen: Boolean): Int =
        if (chosen) palette.ink else palette.muted

    override fun track(canvas: Canvas, bounds: RectF, radius: Float, on: Boolean) {
        fill.color = if (on) palette.lime else palette.hairline
        canvas.drawRoundRect(bounds, radius, radius, fill)
    }

    override fun knob(canvas: Canvas, x: Float, y: Float, radius: Float, on: Boolean) {
        // The ground colour when the track is lit and a grey when it is not, so
        // "on" reads as a hole punched in the lime rather than as a second
        // colour arriving.
        fill.color = if (on) palette.ground else palette.muted
        canvas.drawCircle(x, y, radius, fill)
    }

    /** A border drawn inside its shape rather than straddling the edge of it. */
    private fun edge(canvas: Canvas, bounds: RectF, radius: Float, colour: Int) {
        line.color = colour
        val half = hairline / 2f
        canvas.drawRoundRect(
            bounds.left + half,
            bounds.top + half,
            bounds.right - half,
            bounds.bottom - half,
            radius,
            radius,
            line,
        )
    }
}
