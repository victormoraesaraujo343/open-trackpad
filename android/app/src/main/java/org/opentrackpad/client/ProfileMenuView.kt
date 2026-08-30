package org.opentrackpad.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat

/**
 * The profile menu: which set of shortcuts the rails are showing.
 *
 * A small panel over the pad rather than a screen of its own, from
 * `ProfileMenu.dc.html`. Switching profile is a glance and a tap, and taking
 * the whole surface away for it would be out of proportion to what it does —
 * it is also the one panel where you want to see the rails change behind it.
 *
 * Rows are millimetres like everything else that is aimed at rather than read.
 */
class ProfileMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private companion object {
        // Artboard units, from the drawing.
        private const val WIDTH = 196f
        private const val MARGIN_SIDE = 26f
        private const val MARGIN_TOP = 34f
        private const val PADDING = 8f
        private const val RADIUS = 12f
        private const val ROW_RADIUS = 12f
        private const val BORDER = 1f

        private const val HEADING = Artboard.MIN_READABLE_UNITS
        private const val HEADING_TOP = 4f
        private const val HEADING_BOTTOM = 6f
        private const val HEADING_INSET = 10f

        private const val ROW_TEXT = 13f
        private const val ROW_HEIGHT = 27f
        private const val ROW_INSET = 10f
        private const val ROW_GAP = 2f

        private const val RULE_MARGIN = 6f
        private const val TICK = 14f

        private val SCRIM = Color.parseColor("#7308090A")
        private val PANEL = Color.parseColor("#1B1D1F")
        private val HAIRLINE = Color.parseColor("#2A2D30")
        private val SECONDARY = Color.parseColor("#C6CBD1")
        private val BODY = Color.parseColor("#8A9099")
        private val MUTED = Color.parseColor("#6B7178")
        private val INK = Color.parseColor("#E6E8EA")
        private val LIME = Color.parseColor("#A3E635")

        /** The tick beside the profile in use, on the design's 20-unit grid. */
        private const val TICK_PATH = "M4.4 10.4 8.2 14.2 15.6 6"

        private const val NOTHING_HELD = -1
    }

    /** One line of the menu. */
    sealed interface Row {
        /** A profile that can be switched to. */
        data class Profile(val name: String, val active: Boolean) : Row

        /** A way somewhere else, below the rule. */
        data class Destination(val label: String, val press: SlotPress) : Row
    }

    var onChooseProfile: ((String) -> Unit)? = null
    var onChooseDestination: ((SlotPress) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    var hapticsEnabled: Boolean = true

    /** Which side of the pad it sits on. Follows the shortcut rail, as the ring does. */
    var side: Side = Side.RIGHT
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var rows: List<Row> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val artboard = Artboard.measure(
        resources.displayMetrics,
        resources.displayMetrics.widthPixels,
        resources.configuration.fontScale,
    )

    private fun px(units: Float) = artboard.px(units)

    private val scrim = Paint().apply { color = SCRIM }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = px(BORDER).coerceAtLeast(1f)
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = LIME
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val panel = RectF()
    private val row = RectF()
    private var held = NOTHING_HELD

    init {
        isClickable = true
        isHapticFeedbackEnabled = true
    }

    /** Where the rule sits, as an index: the row it comes before. */
    private fun ruleBefore(): Int = rows.indexOfFirst { it is Row.Destination }

    private fun panelHeight(): Float {
        val heading = px(HEADING_TOP) + px(HEADING) + px(HEADING_BOTTOM)
        val body = rows.size * px(ROW_HEIGHT) + (rows.size - 1).coerceAtLeast(0) * px(ROW_GAP)
        val rule = if (ruleBefore() > 0) px(RULE_MARGIN) * 2f + border.strokeWidth else 0f
        return px(PADDING) * 2f + heading + body + rule
    }

    private fun layOutPanel() {
        val width = px(WIDTH)
        val left = if (side == Side.RIGHT) this.width - px(MARGIN_SIDE) - width else px(MARGIN_SIDE)
        panel.set(left, px(MARGIN_TOP), left + width, px(MARGIN_TOP) + panelHeight())
    }

    /** The bounds of row [index] inside the panel. */
    private fun rowBounds(index: Int, into: RectF) {
        var y = panel.top + px(PADDING) + px(HEADING_TOP) + px(HEADING) + px(HEADING_BOTTOM)
        val rule = ruleBefore()
        for (before in 0 until index) {
            y += px(ROW_HEIGHT) + px(ROW_GAP)
            if (rule == before + 1) y += px(RULE_MARGIN) * 2f + border.strokeWidth
        }
        if (rule == 0 && index == 0) y += px(RULE_MARGIN) * 2f + border.strokeWidth
        into.set(
            panel.left + px(PADDING),
            y,
            panel.right - px(PADDING),
            y + px(ROW_HEIGHT),
        )
    }

    private fun rowAt(x: Float, y: Float): Int {
        for (index in rows.indices) {
            rowBounds(index, row)
            if (row.contains(x, y)) return index
        }
        return NOTHING_HELD
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                held = rowAt(event.x, event.y)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val now = rowAt(event.x, event.y)
                if (now != held) {
                    held = now
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                val chosen = rowAt(event.x, event.y)
                held = NOTHING_HELD
                invalidate()
                // Anywhere that is not a row closes it, including the panel's
                // own padding. Same rule as the ring: the way out is every
                // direction that is not a way further in.
                if (chosen == NOTHING_HELD) {
                    onDismiss?.invoke()
                    return true
                }
                if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                when (val picked = rows[chosen]) {
                    is Row.Profile -> onChooseProfile?.invoke(picked.name)
                    is Row.Destination -> onChooseDestination?.invoke(picked.press)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                held = NOTHING_HELD
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0 || rows.isEmpty()) return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)

        layOutPanel()
        fill.color = PANEL
        border.color = HAIRLINE
        canvas.drawRoundRect(panel, px(RADIUS), px(RADIUS), fill)
        canvas.drawRoundRect(panel, px(RADIUS), px(RADIUS), border)

        text.textAlign = Paint.Align.LEFT
        text.textSize = artboard.text(HEADING)
        text.color = MUTED
        val headingBaseline = panel.top + px(PADDING) + px(HEADING_TOP) - text.fontMetrics.ascent
        canvas.drawText(
            context.getString(R.string.profile_heading),
            panel.left + px(PADDING) + px(HEADING_INSET),
            headingBaseline,
            text,
        )

        val rule = ruleBefore()
        for (index in rows.indices) {
            rowBounds(index, row)
            if (rule == index && index > 0) {
                val y = row.top - px(RULE_MARGIN) - border.strokeWidth / 2f
                fill.color = HAIRLINE
                canvas.drawRect(
                    panel.left + px(PADDING) + px(ROW_INSET),
                    y,
                    panel.right - px(PADDING) - px(ROW_INSET),
                    y + border.strokeWidth,
                    fill,
                )
            }
            drawRow(canvas, index)
        }
    }

    private fun drawRow(canvas: Canvas, index: Int) {
        val entry = rows[index]
        val on = index == held
        val active = entry is Row.Profile && entry.active

        if (on || active) {
            fill.color = HAIRLINE
            canvas.drawRoundRect(row, px(ROW_RADIUS), px(ROW_RADIUS), fill)
        }

        text.textAlign = Paint.Align.LEFT
        text.textSize = artboard.text(ROW_TEXT)
        text.color = when {
            entry is Row.Destination -> BODY
            active -> INK
            else -> SECONDARY
        }
        val label = when (entry) {
            is Row.Profile -> entry.name
            is Row.Destination -> entry.label
        }
        val room = row.width() - px(ROW_INSET) * 2f - if (active) px(TICK) * 2f else 0f
        val shown = TextUtils.ellipsize(label, text, room, TextUtils.TruncateAt.END)
        val metrics = text.fontMetrics
        canvas.drawText(
            shown, 0, shown.length,
            row.left + px(ROW_INSET),
            row.centerY() - (metrics.ascent + metrics.descent) / 2f,
            text,
        )

        if (!active) return
        val size = px(TICK)
        canvas.save()
        canvas.translate(row.right - px(ROW_INSET) - size, row.centerY() - size / 2f)
        canvas.scale(size / 20f, size / 20f)
        canvas.drawPath(RailIcons.parsed(TICK_PATH), tick)
        canvas.restore()
    }
}
