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

/**
 * One rail of five slots, drawn and hit-tested as a single view.
 *
 * **One view, not five buttons, and this is the point.** The rail sits beside
 * the trackpad, and the only thing that keeps a button press from becoming
 * pointer movement is which view a finger lands on. A single opaque view that
 * consumes every touch inside its column cannot leak one to the pad, cannot be
 * fallen between, and has no child that might decline an event and let it
 * through. Everything below the rail's own bounds is somebody else's.
 *
 * It draws exactly what `Main.dc.html` draws, at the size it was drawn: five
 * equal slots, 8 units apart, each a 12-unit rounded rectangle holding a
 * 22-unit stroked glyph over a 12-unit label. Units are millimetres on the
 * glass rather than density-independent pixels — see [Artboard].
 */
class RailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private companion object {
        // Everything below is in artboard units, which are a physical length
        // rather than a density-independent pixel. See [Artboard] for why, and
        // note that the numbers themselves are unchanged: they are lifted from
        // the screens, and only what a unit means has moved.

        /** Between slots. The rail's own margins belong to the layout. */
        const val GAP = 8f

        /** Panels are 12, things inside panels are 8. This is a panel. */
        const val RADIUS = 12f

        const val BORDER = 1f

        /** Glyphs are drawn on a 20-unit grid, at 22 units, stroked at 1.4. */
        const val ICON = 22f
        const val ICON_GRID = 20f
        const val ICON_STROKE = 1.4f

        /** Between the glyph and its label. */
        const val LABEL_GAP = 8f

        /**
         * The label, in artboard units and so in real millimetres.
         *
         * Not sp, and this is the same argument the rail itself makes rather
         * than a second one. A label here is not reading, it is the word under
         * a key: it has to be recognisable at a glance on a surface used
         * without looking, in a slot that cannot grow to hold it. Sizing it by
         * the system font scale would overflow the slot or push the glyph out
         * of it, and sizing it in dp made it unreadable on the first real
         * phone. A physical size is the only one that means the same thing
         * twice.
         */
        const val LABEL = 12f

        /** Left and right of the label before it is cut short. */
        const val LABEL_INSET = 5f

        val GROUND = Color.parseColor("#0E0F10")
        val INSET = Color.parseColor("#1B1D1F")
        val HAIRLINE = Color.parseColor("#2A2D30")
        val SECONDARY = Color.parseColor("#C6CBD1")
        val FAINT = Color.parseColor("#4E545B")
        val LIME = Color.parseColor("#A3E635")
        val LIME_BRIGHT = Color.parseColor("#BBEF6B")
    }

    /** What a press asks for. Set by the activity; never called for a dead slot. */
    var onPress: ((SlotPress) -> Unit)? = null

    /** Whether a press should also be felt. Follows the user's setting. */
    var hapticsEnabled: Boolean = true

    /**
     * The five slots, top to bottom. A null is a hole kept open on purpose.
     *
     * Always exactly [Rails.SLOTS] long once set: fewer items leave a slot empty
     * rather than letting the others grow, because a button that moves is a
     * button pressed by mistake.
     */
    var slots: List<RailSlot?> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val artboard = Artboard.measure(
        resources.displayMetrics,
        resources.displayMetrics.widthPixels,
    )
    private val gap = artboard.px(GAP)
    private val radius = artboard.px(RADIUS)
    private val iconSize = artboard.px(ICON)
    private val labelGap = artboard.px(LABEL_GAP)
    private val labelInset = artboard.px(LABEL_INSET)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // A hairline stays a hairline: at one unit it would round to nothing on
        // a low-density screen, and a slot with no edge is a slot that has
        // merged with the one beside it.
        strokeWidth = artboard.px(BORDER).coerceAtLeast(1f)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ICON_STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = artboard.px(LABEL)
        // Inter Medium, the weight the design draws rail labels at. Falls back
        // to the system's own medium if the face cannot be loaded, so a missing
        // font costs the look and not the button.
        typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val bounds = RectF()

    /** [bounds] pulled in by half a border, so the stroke lands inside the slot. */
    private val shape = RectF()

    /** Which slot each finger currently on this rail went down on. */
    private val held = SparseIntArray(4)

    init {
        // The rail answers for its whole column, including the gaps between
        // slots. Nothing behind it may ever see one of these touches.
        isClickable = true
        isHapticFeedbackEnabled = true
    }

    /** The height of one of the five slots at the current size. */
    private fun slotHeight(): Float = (height - gap * (Rails.SLOTS - 1)) / Rails.SLOTS

    /**
     * Which slot a finger at [y] is on.
     *
     * The gap above a slot counts as part of it, so the rail has no dead
     * stripes. A surface used without looking should not have places where a
     * confident press does nothing.
     */
    private fun slotAt(y: Float): Int {
        val step = slotHeight() + gap
        if (step <= 0f) return 0
        return (y / step).toInt().coerceIn(0, Rails.SLOTS - 1)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            -> {
                val index = event.actionIndex
                press(event.getPointerId(index), slotAt(event.getY(index)))
            }

            MotionEvent.ACTION_MOVE -> {
                // A finger that slides off the slot it pressed stops looking
                // pressed. The shortcut already went: this is only the light
                // going out, so a finger resting on the rail does not leave a
                // button lit for as long as it stays there.
                var changed = false
                for (index in 0 until event.pointerCount) {
                    val pointer = event.getPointerId(index)
                    val at = held.indexOfKey(pointer)
                    if (at < 0) continue
                    if (held.valueAt(at) != slotAt(event.getY(index))) {
                        held.removeAt(at)
                        changed = true
                    }
                }
                if (changed) invalidate()
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            -> release(event.getPointerId(event.actionIndex))

            MotionEvent.ACTION_CANCEL -> {
                held.clear()
                invalidate()
            }
        }
        // Always. A rail that ever returned false would hand the rest of that
        // gesture to whatever is behind it, and behind it is the trackpad.
        return true
    }

    /**
     * A finger went down on [slot].
     *
     * Shortcuts fire on the way down, the way a key does, rather than on
     * release: a control surface that answers only when you let go feels slow,
     * and the difference is the whole reason for having buttons instead of a
     * menu.
     */
    private fun press(pointer: Int, slot: Int) {
        held.put(pointer, slot)
        invalidate()

        val target = slots.getOrNull(slot) ?: return
        if (target.style == SlotStyle.DEAD) return
        if (target.press == SlotPress.None) return
        if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onPress?.invoke(target.press)
    }

    private fun release(pointer: Int) {
        val at = held.indexOfKey(pointer)
        if (at < 0) return
        held.removeAt(at)
        invalidate()
    }

    private fun isHeld(slot: Int): Boolean {
        for (index in 0 until held.size()) {
            if (held.valueAt(index) == slot) return true
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        if (height <= 0 || width <= 0) return
        val step = slotHeight()
        for (index in 0 until Rails.SLOTS) {
            val top = index * (step + gap)
            bounds.set(0f, top, width.toFloat(), top + step)
            drawSlot(canvas, slots.getOrNull(index), isHeld(index))
        }
    }

    private fun drawSlot(canvas: Canvas, slot: RailSlot?, pressed: Boolean) {
        val style = slot?.style ?: SlotStyle.PLAIN
        val filled = style == SlotStyle.PRIMARY

        // The palette is three decisions: lime is "this one, now" and is the
        // only colour that ever fills a slot; a pressed slot is the same slot
        // one step brighter; everything else is the inset panel. An empty slot
        // is drawn as the hole it is — the shape stays so the ones around it
        // cannot move, but nothing is offered inside it.
        fill.color = when {
            slot == null -> GROUND
            filled && pressed -> LIME_BRIGHT
            filled -> LIME
            pressed -> HAIRLINE
            else -> INSET
        }
        border.color = when {
            slot == null -> HAIRLINE
            filled && pressed -> LIME_BRIGHT
            filled -> LIME
            style == SlotStyle.ACTIVE -> LIME
            else -> HAIRLINE
        }
        val ink = when {
            filled -> GROUND
            style == SlotStyle.ACTIVE -> LIME
            style == SlotStyle.DEAD -> FAINT
            else -> SECONDARY
        }

        val half = border.strokeWidth / 2f
        shape.set(bounds)
        shape.inset(half, half)
        canvas.drawRoundRect(shape, radius, radius, fill)
        canvas.drawRoundRect(shape, radius, radius, border)
        if (slot == null) return

        val metrics = label.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val block = iconSize + labelGap + textHeight
        val top = bounds.centerY() - block / 2f

        stroke.color = ink
        canvas.save()
        canvas.translate(bounds.centerX() - iconSize / 2f, top)
        canvas.scale(iconSize / ICON_GRID, iconSize / ICON_GRID)
        canvas.drawPath(RailIcons.parsed(slot.icon), stroke)
        canvas.restore()

        label.color = ink
        val room = (bounds.width() - labelInset * 2f).coerceAtLeast(0f)
        val text = TextUtils.ellipsize(slot.label, label, room, TextUtils.TruncateAt.END)
        canvas.drawText(
            text,
            0,
            text.length,
            bounds.centerX(),
            top + iconSize + labelGap - metrics.ascent,
            label,
        )
    }
}
