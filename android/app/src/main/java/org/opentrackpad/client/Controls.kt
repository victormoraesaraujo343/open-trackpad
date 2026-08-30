package org.opentrackpad.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The two form controls the settings screens are built from.
 *
 * Millimetres like everything else, multiplied by the font scale. There was a
 * note here saying the opposite — that a settings screen is read rather than
 * aimed at, so it should follow the system — and it was wrong for a reason
 * worth keeping: the system setting it was actually following was the
 * display-size slider, which is a layout preference. The one that carries the
 * accessibility argument is the font scale, and that multiplies a physical size
 * rather than replacing it. See [Artboard].
 */

/**
 * A switch, drawn as the design draws it: a 28 by 16 pill with a 12 across knob.
 *
 * Not `SwitchCompat`, which brings Material's own shape and colours and would
 * be the one control on screen that came from somewhere else.
 */
class PillToggle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private companion object {
        const val WIDTH = 28f
        const val HEIGHT = 16f
        const val KNOB = 12f
        const val INSET = 2f
    }

    var onChange: ((Boolean) -> Unit)? = null

    var checked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** How this is painted. See [Skin]. */
    private val skin: Skin = MinimalSkin(context)

    private val artboard = Artboard.measure(
        resources.displayMetrics,
        resources.displayMetrics.widthPixels,
        resources.configuration.fontScale,
    )
    /** How this feels under a finger. Set by whoever builds the screen. */
    var haptics: Haptics? = null

    private val track = RectF()

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            checked = !checked
            // A switch is a mechanism too: down and let go.
            haptics?.press()
            haptics?.release()
            onChange?.invoke(checked)
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(
            resolveSize(artboard.size(WIDTH), widthSpec),
            resolveSize(artboard.size(HEIGHT), heightSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        track.set(0f, 0f, width.toFloat(), height.toFloat())
        skin.track(canvas, track, radius, checked)

        val knob = artboard.px(KNOB)
        val inset = artboard.px(INSET)
        val x = if (checked) width - inset - knob / 2f else inset + knob / 2f
        skin.knob(canvas, x, height / 2f, knob / 2f, checked)
    }
}

/**
 * A row of choices where exactly one is taken, from the design's own shape:
 * an inset rounded container with the chosen one filled.
 *
 * Wraps onto more than one line when the choices do not fit, because "Back to
 * the trackpad after" has five of them and a screen narrower than the artboard
 * would otherwise push the last one off the edge.
 */
class SegmentedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private companion object {
        const val RADIUS = 12f
        const val CHIP_RADIUS = 8f
        const val PADDING = 4f
        const val CHIP_H = 9f
        const val CHIP_V = 4f
        const val GAP = 4f
        const val TEXT = Artboard.MIN_READABLE_UNITS
        // The hairline moved to the skin, which owns its own edge: a stroke is
        // drawn centred on the path, so a border at the very edge of a shape
        // loses half of itself outside it, and that correction was the same
        // three lines in every view that drew one.
    }

    var onChoose: ((Int) -> Unit)? = null

    var options: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    var chosen: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** How this is painted. See [Skin]. */
    private val skin: Skin = MinimalSkin(context)

    private val artboard = Artboard.measure(
        resources.displayMetrics,
        resources.displayMetrics.widthPixels,
        resources.configuration.fontScale,
    )

    // Named `dp` no longer; these are artboard units, like everything else.
    private fun px(value: Float) = artboard.px(value)

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = artboard.text(TEXT)
        typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** Where each option sits, recomputed whenever the size or the list changes. */
    private val chips = mutableListOf<RectF>()
    private val container = RectF()

    init {
        isClickable = true
    }

    /** How this feels under a finger. Set by whoever builds the screen. */
    var haptics: Haptics? = null

    private fun chipWidth(option: String) = text.measureText(option) + px(CHIP_H) * 2f
    private fun chipHeight() = text.fontMetrics.let { it.descent - it.ascent } + px(CHIP_V) * 2f

    /** Lays the chips out inside [available] and returns the height needed. */
    private fun arrange(available: Float): Float {
        chips.clear()
        val inner = available - px(PADDING) * 2f
        var x = px(PADDING)
        var y = px(PADDING)
        val height = chipHeight()
        for (option in options) {
            val width = chipWidth(option)
            if (x > px(PADDING) && x + width - px(PADDING) > inner) {
                x = px(PADDING)
                y += height + px(GAP)
            }
            chips += RectF(x, y, x + width, y + height)
            x += width + px(GAP)
        }
        return y + height + px(PADDING)
    }

    /** The width this would like if nothing constrained it: one row of chips. */
    private fun naturalWidth(): Float =
        options.sumOf { chipWidth(it).toDouble() }.toFloat() +
            px(GAP) * (options.size - 1).coerceAtLeast(0) +
            px(PADDING) * 2f

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // The measure mode has to be honoured rather than the size taken at
        // face value. Asked for `wrap_content` beside a label, an EXACTLY-sized
        // answer swallows the whole row and squeezes the label to nothing —
        // which is exactly what it did, and what the label vanishing looked
        // like on screen.
        val limit = MeasureSpec.getSize(widthSpec)
        val width = when (MeasureSpec.getMode(widthSpec)) {
            MeasureSpec.EXACTLY -> limit
            // Rounded up, never down. Rounding the natural width down leaves
            // the last chip overflowing by a fraction of a pixel, and the wrap
            // test then puts it on a line of its own — which looks like a
            // deliberate two-row layout and is really half a pixel.
            MeasureSpec.AT_MOST -> minOf(ceil(naturalWidth()).toInt(), limit)
            else -> ceil(naturalWidth()).toInt()
        }
        val height = arrange(width.toFloat())
        setMeasuredDimension(width, resolveSize(height.roundToInt(), heightSpec))
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        arrange(width.toFloat())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val index = chips.indexOfFirst { it.contains(event.x, event.y) }
            if (index >= 0 && index != chosen) {
                chosen = index
                // Chosen on release, so the whole click lands here.
                haptics?.click()
                onChoose?.invoke(index)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (chips.isEmpty()) return
        container.set(0f, 0f, width.toFloat(), height.toFloat())
        skin.inset(canvas, container, px(RADIUS))

        val metrics = text.fontMetrics
        for ((index, chip) in chips.withIndex()) {
            skin.segment(canvas, chip, px(CHIP_RADIUS), chosen = index == chosen)
            text.color = skin.segmentInk(chosen = index == chosen)
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(
                options[index],
                chip.centerX(),
                chip.centerY() - (metrics.ascent + metrics.descent) / 2f,
                text,
            )
        }
    }
}
