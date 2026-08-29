package org.opentrackpad.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import kotlin.math.roundToInt

/**
 * The two form controls the settings screens are built from.
 *
 * In dp rather than millimetres, deliberately, and this is the rule from
 * [Artboard] rather than an exception to it: millimetres are for what a hand
 * aims at without looking, system sizing is for what eyes read at reading
 * distance. A setting is read. Somebody who has turned their display size up
 * has said they want text bigger, and a settings screen is exactly where that
 * should be obeyed.
 */
private object Palette {
    val INSET = Color.parseColor("#1B1D1F")
    val HAIRLINE = Color.parseColor("#2A2D30")
    val GROUND = Color.parseColor("#0E0F10")
    val INK = Color.parseColor("#E6E8EA")
    val MUTED = Color.parseColor("#6B7178")
    val LIME = Color.parseColor("#A3E635")
}

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
        const val WIDTH_DP = 28f
        const val HEIGHT_DP = 16f
        const val KNOB_DP = 12f
        const val INSET_DP = 2f
    }

    var onChange: ((Boolean) -> Unit)? = null

    var checked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            checked = !checked
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onChange?.invoke(checked)
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(
            resolveSize((WIDTH_DP * density).roundToInt(), widthSpec),
            resolveSize((HEIGHT_DP * density).roundToInt(), heightSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        track.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = if (checked) Palette.LIME else Palette.HAIRLINE
        canvas.drawRoundRect(track, radius, radius, paint)

        val knob = KNOB_DP * density
        val inset = INSET_DP * density
        val x = if (checked) width - inset - knob / 2f else inset + knob / 2f
        // The knob is the ground colour when the track is lit and a grey when
        // it is not, so "on" reads as a hole punched in the lime rather than as
        // a second colour.
        paint.color = if (checked) Palette.GROUND else Palette.MUTED
        canvas.drawCircle(x, height / 2f, knob / 2f, paint)
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
        const val RADIUS_DP = 12f
        const val CHIP_RADIUS_DP = 8f
        const val PADDING_DP = 4f
        const val CHIP_H_DP = 9f
        const val CHIP_V_DP = 4f
        const val GAP_DP = 4f
        const val TEXT_DP = 10f
        const val BORDER_DP = 1f
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

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(BORDER_DP)
        color = Palette.HAIRLINE
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(TEXT_DP)
        typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** Where each option sits, recomputed whenever the size or the list changes. */
    private val chips = mutableListOf<RectF>()
    private val container = RectF()

    init {
        isClickable = true
    }

    private fun chipWidth(option: String) = text.measureText(option) + dp(CHIP_H_DP) * 2f
    private fun chipHeight() = text.fontMetrics.let { it.descent - it.ascent } + dp(CHIP_V_DP) * 2f

    /** Lays the chips out inside [available] and returns the height needed. */
    private fun arrange(available: Float): Float {
        chips.clear()
        val inner = available - dp(PADDING_DP) * 2f
        var x = dp(PADDING_DP)
        var y = dp(PADDING_DP)
        val height = chipHeight()
        for (option in options) {
            val width = chipWidth(option)
            if (x > dp(PADDING_DP) && x + width - dp(PADDING_DP) > inner) {
                x = dp(PADDING_DP)
                y += height + dp(GAP_DP)
            }
            chips += RectF(x, y, x + width, y + height)
            x += width + dp(GAP_DP)
        }
        return y + height + dp(PADDING_DP)
    }

    /** The width this would like if nothing constrained it: one row of chips. */
    private fun naturalWidth(): Float =
        options.sumOf { chipWidth(it).toDouble() }.toFloat() +
            dp(GAP_DP) * (options.size - 1).coerceAtLeast(0) +
            dp(PADDING_DP) * 2f

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // The measure mode has to be honoured rather than the size taken at
        // face value. Asked for `wrap_content` beside a label, an EXACTLY-sized
        // answer swallows the whole row and squeezes the label to nothing —
        // which is exactly what it did, and what the label vanishing looked
        // like on screen.
        val limit = MeasureSpec.getSize(widthSpec)
        val width = when (MeasureSpec.getMode(widthSpec)) {
            MeasureSpec.EXACTLY -> limit
            MeasureSpec.AT_MOST -> minOf(naturalWidth().roundToInt(), limit)
            else -> naturalWidth().roundToInt()
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
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onChoose?.invoke(index)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (chips.isEmpty()) return
        container.set(0f, 0f, width.toFloat(), height.toFloat())
        fill.color = Palette.INSET
        canvas.drawRoundRect(container, dp(RADIUS_DP), dp(RADIUS_DP), fill)
        val half = border.strokeWidth / 2f
        container.inset(half, half)
        canvas.drawRoundRect(container, dp(RADIUS_DP), dp(RADIUS_DP), border)

        val metrics = text.fontMetrics
        for ((index, chip) in chips.withIndex()) {
            if (index == chosen) {
                fill.color = Palette.HAIRLINE
                canvas.drawRoundRect(chip, dp(CHIP_RADIUS_DP), dp(CHIP_RADIUS_DP), fill)
            }
            text.color = if (index == chosen) Palette.INK else Palette.MUTED
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
