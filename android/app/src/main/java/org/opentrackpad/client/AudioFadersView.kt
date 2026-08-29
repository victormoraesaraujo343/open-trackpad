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
import android.util.SparseIntArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A row of vertical faders, one per thing that makes or takes sound.
 *
 * Drawn from `AudioOutput.dc.html` and `AudioApps.dc.html`, which are the same
 * fader wearing two hats: a device shows which one the machine is using and a
 * stream shows whether it has been pushed past 100%.
 *
 * Millimetres, because a fader is aimed at rather than read — and because a
 * fader whose physical length changed with a display setting would mean a given
 * finger movement was a different number of decibels on different phones.
 */
class AudioFadersView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private companion object {
        // Artboard units.
        const val COLUMN = 130f
        const val COLUMN_GAP = 8f
        const val TRACK = 4f
        const val TRACK_INSET = 16f
        const val KNOB = 30f
        const val KNOB_BORDER = 1f
        const val KNOB_ICON = 14f
        const val ROW_GAP = 7f
        const val VALUE = 11f
        const val NAME = 11f
        const val PORT = 9f

        val TRACK_BED = Color.parseColor("#24272A")
        val GREY = Color.parseColor("#6B7178")
        val GREY_MUTED = Color.parseColor("#2E3236")
        val INSET = Color.parseColor("#1B1D1F")
        val HAIRLINE = Color.parseColor("#2A2D30")
        val KNOB_EDGE = Color.parseColor("#3A3F45")
        val GROUND = Color.parseColor("#0E0F10")
        val INK = Color.parseColor("#E6E8EA")
        val SECONDARY = Color.parseColor("#C6CBD1")
        val BODY = Color.parseColor("#8A9099")
        val MUTED = Color.parseColor("#6B7178")
        val FAINT = Color.parseColor("#4E545B")
        val LIME = Color.parseColor("#A3E635")
        val AMBER = Color.parseColor("#F5A524")
        val AMBER_MUTED = Color.parseColor("#3A3020")

        /** A speaker, and a speaker with a cross, on the design's 20-unit grid. */
        const val ICON_ON = "M4.2 8.2h2.6L10.4 5v10L6.8 11.8H4.2zM13.4 7.6a3.4 3.4 0 0 1 0 4.8"
        const val ICON_MUTED =
            "M4.2 8.2h2.6L10.4 5v10L6.8 11.8H4.2zM13.2 8.2l3.6 3.6M16.8 8.2l-3.6 3.6"

        /** Past this much movement a press is a drag, and no longer a mute. */
        const val SLOP = 6f
    }

    /** A level was dragged to [level] per mille. Sent as it moves. */
    var onLevel: ((AudioEntity, Int) -> Unit)? = null

    /** A knob was tapped. */
    var onMute: ((AudioEntity, Boolean) -> Unit)? = null

    var hapticsEnabled: Boolean = true

    /**
     * Whether the scale runs past 100%.
     *
     * Off, the fader tops out at the reference level and nothing can be pushed
     * beyond it from here. On, it is drawn against the full scale with the
     * boosted part in amber, as the protocol describes.
     */
    var allowBoost: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var faders: List<AudioEntity> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val artboard = Artboard.measure(
        resources.displayMetrics,
        resources.displayMetrics.widthPixels,
    )

    private fun px(units: Float) = artboard.px(units)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = px(KNOB_BORDER).coerceAtLeast(1f)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val bar = RectF()

    /** Which fader each finger took hold of. */
    private val holding = SparseIntArray(4)

    /** Where each finger started, so a tap can be told from a drag. */
    private val startY = SparseIntArray(4)
    private val moved = SparseIntArray(4)

    init {
        isClickable = true
        isHapticFeedbackEnabled = true
    }

    /** The top of this fader's scale, per mille. */
    private fun ceiling() = if (allowBoost) Audio.CEILING else Audio.REFERENCE

    private fun columnWidth() = px(COLUMN)

    /** Where column [index] starts, with the row centred as the design centres it. */
    private fun columnLeft(index: Int): Float {
        val total = faders.size * columnWidth() + (faders.size - 1).coerceAtLeast(0) * px(COLUMN_GAP)
        val start = (width - total) / 2f
        return start + index * (columnWidth() + px(COLUMN_GAP))
    }

    /** The vertical span the track occupies. */
    private fun trackTop() = px(TRACK_INSET)
    private fun trackBottom() = height - labelBlockHeight() - px(TRACK_INSET)

    private fun labelBlockHeight(): Float {
        text.textSize = px(VALUE)
        val line = text.fontMetrics.let { it.descent - it.ascent }
        return line * 3f + px(ROW_GAP) * 3f
    }

    private fun columnAt(x: Float): Int {
        for (index in faders.indices) {
            val left = columnLeft(index)
            if (x >= left && x <= left + columnWidth()) return index
        }
        return -1
    }

    /** The level a finger at [y] is asking for. */
    private fun levelAt(y: Float): Int {
        val top = trackTop()
        val bottom = trackBottom()
        if (bottom <= top) return 0
        val fraction = ((bottom - y) / (bottom - top)).coerceIn(0f, 1f)
        return (fraction * ceiling()).roundToInt().coerceIn(0, ceiling())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val column = columnAt(event.getX(index))
                if (column < 0) return true
                val pointer = event.getPointerId(index)
                holding.put(pointer, column)
                startY.put(pointer, event.getY(index).toInt())
                moved.put(pointer, 0)
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val pointer = event.getPointerId(index)
                    val at = holding.indexOfKey(pointer)
                    if (at < 0) continue
                    val column = holding.valueAt(at)
                    val entity = faders.getOrNull(column) ?: continue
                    val travelled = abs(event.getY(index) - startY.get(pointer))
                    if (travelled < px(SLOP)) continue
                    moved.put(pointer, 1)
                    // Sent as it moves rather than on release. The host gathers
                    // requests for 50 ms and drops any a later one overtook, so
                    // a drag across the screen costs one change rather than
                    // forty — and the fader follows the finger meanwhile.
                    onLevel?.invoke(entity, levelAt(event.getY(index)))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointer = event.getPointerId(event.actionIndex)
                val at = holding.indexOfKey(pointer)
                if (at >= 0) {
                    val column = holding.valueAt(at)
                    val entity = faders.getOrNull(column)
                    // A press that never moved is a mute, which is what the
                    // header on the screen promises. One that moved was a drag
                    // and has already done its work.
                    if (entity != null && moved.get(pointer) == 0) {
                        if (hapticsEnabled) {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        onMute?.invoke(entity, !entity.muted)
                    }
                    holding.removeAt(at)
                }
                startY.delete(pointer)
                moved.delete(pointer)
            }

            MotionEvent.ACTION_CANCEL -> {
                holding.clear()
                startY.clear()
                moved.clear()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0 || faders.isEmpty()) return
        for (index in faders.indices) drawFader(canvas, index, faders[index])
    }

    private fun drawFader(canvas: Canvas, index: Int, entity: AudioEntity) {
        val left = columnLeft(index)
        val centre = left + columnWidth() / 2f
        val top = trackTop()
        val bottom = trackBottom()
        if (bottom <= top) return

        // Where the knob sits. A muted fader keeps its place: the level is
        // remembered, so unmuting puts you back where you were rather than
        // somewhere the machine chose.
        val fraction = (entity.volume.toFloat() / ceiling()).coerceIn(0f, 1f)
        val knobY = bottom - fraction * (bottom - top)
        val boosted = entity.volume > Audio.REFERENCE
        val radius = px(TRACK) / 2f

        fill.color = TRACK_BED
        bar.set(centre - radius, top, centre + radius, bottom)
        canvas.drawRoundRect(bar, radius, radius, fill)

        // The bar below the reference is one colour and anything above it is
        // amber, so a glance says you are in the distorting range without
        // reading the number.
        val reference = bottom - (Audio.REFERENCE.toFloat() / ceiling()) * (bottom - top)
        fill.color = when {
            entity.muted -> GREY_MUTED
            entity.isDefault -> LIME
            else -> GREY
        }
        bar.set(centre - radius, maxOf(knobY, reference), centre + radius, bottom)
        canvas.drawRoundRect(bar, radius, radius, fill)
        if (boosted) {
            fill.color = if (entity.muted) AMBER_MUTED else AMBER
            bar.set(centre - radius, knobY, centre + radius, reference)
            canvas.drawRoundRect(bar, radius, radius, fill)
        }

        fill.color = when {
            entity.muted -> INSET
            boosted -> AMBER
            entity.isDefault -> LIME
            else -> HAIRLINE
        }
        border.color = if (boosted && !entity.muted) AMBER else KNOB_EDGE
        canvas.drawCircle(centre, knobY, px(KNOB) / 2f, fill)
        canvas.drawCircle(centre, knobY, px(KNOB) / 2f, border)

        stroke.color = when {
            entity.muted -> MUTED
            boosted || entity.isDefault -> GROUND
            else -> BODY
        }
        stroke.strokeWidth = 1.6f
        val icon = px(KNOB_ICON)
        canvas.save()
        canvas.translate(centre - icon / 2f, knobY - icon / 2f)
        canvas.scale(icon / 20f, icon / 20f)
        canvas.drawPath(RailIcons.parsed(if (entity.muted) ICON_MUTED else ICON_ON), stroke)
        canvas.restore()

        drawLabels(canvas, entity, centre, columnWidth(), bottom + px(TRACK_INSET), boosted)
    }

    private fun drawLabels(
        canvas: Canvas,
        entity: AudioEntity,
        centre: Float,
        room: Float,
        from: Float,
        boosted: Boolean,
    ) {
        text.textSize = px(VALUE)
        val metrics = text.fontMetrics
        val line = metrics.descent - metrics.ascent
        var y = from - metrics.ascent

        text.color = when {
            entity.muted -> FAINT
            boosted -> AMBER
            else -> BODY
        }
        val value = if (entity.muted) {
            context.getString(R.string.audio_muted)
        } else {
            context.getString(R.string.audio_percent, entity.percent)
        }
        canvas.drawText(value, centre, y, text)

        y += line + px(ROW_GAP)
        text.textSize = px(NAME)
        text.color = when {
            entity.muted -> MUTED
            entity.isDefault -> INK
            else -> SECONDARY
        }
        val name = TextUtils.ellipsize(entity.name, text, room, TextUtils.TruncateAt.END)
        canvas.drawText(name, 0, name.length, centre, y, text)

        // The third line is what a device is plugged into. A stream has none,
        // and an empty line is left empty rather than closed up, so the names
        // above stay on the same baseline across all three pages.
        val detail = entity.detail() ?: return
        y += line + px(ROW_GAP)
        text.textSize = px(PORT)
        text.color = FAINT
        val shown = TextUtils.ellipsize(detail, text, room, TextUtils.TruncateAt.END)
        canvas.drawText(shown, 0, shown.length, centre, y, text)
    }

    /** The small line under the name, or null when there is nothing to say. */
    private fun AudioEntity.detail(): String? = when {
        kind == AudioKind.STREAM -> null
        isDefault -> context.getString(R.string.audio_in_use)
        else -> null
    }
}
