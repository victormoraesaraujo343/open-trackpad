package org.opentrackpad.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat

/**
 * The Quick Ring: eight wedges around a hub, over the trackpad.
 *
 * It is the app's only way in to anything that is not the surface itself, which
 * is why the fifth slot of a rail means the same thing everywhere. Everything
 * the interface has no room for is reached through here.
 *
 * It covers the pad and consumes every touch inside it, so while it is open the
 * trackpad sends nothing. That is deliberate rather than incidental: a menu you
 * can accidentally drag the pointer through is a menu that will move the window
 * you are trying to act on.
 *
 * Drawn from `QuickRing.dc.html` at the size it was drawn — the wedges are
 * targets on a surface used without looking, so they are millimetres like the
 * rails. See [Artboard].
 */
class QuickRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private companion object {
        // Artboard units. The drawing is a 240-unit square.
        private const val SIZE = 240f
        private const val OUTER = 112f
        private const val INNER = 46f
        private const val HUB = 45.5f
        private const val ICON = 20f
        private const val LABEL = Artboard.MIN_READABLE_UNITS
        private const val HUB_LABEL = Artboard.MIN_READABLE_UNITS
        private const val BORDER = 1f

        /** From the near edge of the pad, on the side the shortcut rail is. */
        private const val MARGIN = 22f

        /** Where a wedge's glyph and word sit, measured from the wedge's middle. */
        private const val ICON_ABOVE = 16f
        private const val LABEL_BELOW = 15f

        /** Lime at a fifteenth of its strength: a held wedge, not a lit one. */

        /** What the pad is dimmed by while the ring is up: rgba(8,9,10,0.45). */

        /** Held nothing. Distinct from [RingGeometry.HUB], which is a place. */
        private const val NOTHING_HELD = -1
    }

    /** Chosen a wedge. Not called for an empty one. */
    var onChoose: ((SlotPress) -> Unit)? = null

    /** Asked to go away — the hub, or anywhere outside the ring. */
    var onDismiss: (() -> Unit)? = null

    /**
     * How this feels under a finger. Null until the activity supplies it.
     *
     * The switch in settings lives on the object itself, so every view either
     * feels right or feels like nothing, and no view can forget to check.
     */
    var haptics: Haptics? = null

    /**
     * Which side of the pad the ring sits on.
     *
     * It opens from the fifth slot of the shortcut rail, so it belongs on that
     * rail's side of the pad: reaching across the whole surface for a menu that
     * was summoned by your thumb is the wrong shape.
     */
    var side: Side = Side.RIGHT
        set(value) {
            if (field == value) return
            field = value
            layOut(width, height)
            invalidate()
        }

    /**
     * The eight wedges. A null is an empty one.
     *
     * Empty rather than absent, exactly as a rail slot is: the ring is eight
     * positions and a wedge that moved because its neighbour was removed is a
     * wedge pressed by mistake.
     */
    /**
     * The destinations, in order. The ring is built to fit them.
     *
     * No nulls and no padding: a wedge exists because something is there. See
     * [RingGeometry].
     */
    var wedges: List<RailSlot?> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Every colour this draws with, from the theme. See [Palette]. */
    private val palette = Palette.of(context)

    private val artboard = Artboard.measure(
        resources.displayMetrics,
        resources.displayMetrics.widthPixels,
        resources.configuration.fontScale,
    )

    private val scrim = Paint().apply { color = palette.veil }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = artboard.px(BORDER).coerceAtLeast(1f)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = artboard.text(LABEL)
        typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val wedge = Path()
    private val ovalOuter = RectF()
    private val ovalInner = RectF()

    /**
     * The scale the ring is actually drawn at.
     *
     * Normally one unit of the drawing, like everything else. On a pad too
     * short to hold a 240-unit circle it shrinks to fit, because a ring with
     * its edges cut off is worse than a smaller ring.
     */
    private var unit = 0f
    private var centreX = 0f
    private var centreY = 0f

    /** The wedge a finger is on, or [NOTHING_HELD]. */
    private var held = NOTHING_HELD

    private val hubTop = context.getString(R.string.ring_hub_top)
    private val hubBottom = context.getString(R.string.ring_hub_bottom)

    init {
        isClickable = true
        isHapticFeedbackEnabled = true
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        layOut(width, height)
    }

    private fun layOut(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val wanted = artboard.px(SIZE)
        // Two margins' worth of room, so a shrunken ring is still inset from
        // the pad's own edge rather than touching it.
        val room = minOf(width - artboard.px(MARGIN) * 2f, height - artboard.px(MARGIN) * 2f)
        val diameter = minOf(wanted, room).coerceAtLeast(1f)
        unit = diameter / SIZE

        val inset = artboard.px(MARGIN) + diameter / 2f
        centreX = if (side == Side.RIGHT) width - inset else inset
        // Vertically centred: the drawing leaves the same room above and below.
        centreY = height / 2f
    }

    /** The ring this many destinations makes. Cheap; the shape is four numbers. */
    private fun geometry() = RingGeometry.of(wedges.size)

    private fun px(units: Float) = units * unit

    // -- touch ---------------------------------------------------------------

    /** Which wedge a point in this view is on. See [RingGeometry]. */
    private fun wedgeAt(x: Float, y: Float): Int =
        geometry().at(x - centreX, y - centreY, px(INNER), px(OUTER))

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                held = wedgeAt(event.x, event.y).takeIf { it >= 0 } ?: NOTHING_HELD
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                // A radial menu is chosen by sliding as much as by tapping, so
                // the highlight follows the finger between wedges rather than
                // being fixed at the moment it landed.
                val now = wedgeAt(event.x, event.y).takeIf { it >= 0 } ?: NOTHING_HELD
                if (now != held) {
                    held = now
                    if (held >= 0 && wedges.getOrNull(held) != null) tick()
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                val chosen = wedgeAt(event.x, event.y)
                held = NOTHING_HELD
                invalidate()
                when {
                    // Everything that is not a wedge closes the ring. A ring you
                    // can enter and not obviously leave is the worst version of
                    // this, so the hub and the whole surface around it are both
                    // ways out, on top of the slot that opened it.
                    chosen == RingGeometry.HUB || chosen == RingGeometry.OUTSIDE -> onDismiss?.invoke()

                    else -> {
                        val slot = wedges.getOrNull(chosen)
                        if (slot == null || slot.press == SlotPress.None) {
                            // An empty wedge is a hole, not a button. It does
                            // not close the ring either: closing on a miss
                            // would make the ring feel like it collapsed.
                            return true
                        }
                        run {
                            // Passing a wedge, not choosing one. Light, because
                            // a slow sweep crosses several and anything heavier
                            // would turn the gesture into a rattle.
                            haptics?.cross()
                        }
                        onChoose?.invoke(slot.press)
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                held = NOTHING_HELD
                invalidate()
            }
        }
        return true
    }

    /**
     * Chosen on release, not on press, and this differs from a rail on purpose.
     *
     * A rail slot is a key and fires under the finger, because that is what a
     * key does. A wedge is one of eight neighbours meeting at a point, where a
     * slip lands on the wrong one and there is no way to take it back. Sliding
     * to the right wedge and letting go is how a radial menu has always worked,
     * and it is the affordance that makes eight targets in a circle usable at
     * all.
     */
    private fun tick() {
        // The whole click at once. A ring chooses on release, so there was no
        // earlier moment to put the press half at, and both halves go here.
        haptics?.click()
    }

    // -- drawing -------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (unit <= 0f) layOut(width, height)
        if (unit <= 0f) return

        // The pad goes back but does not go away: it is still the thing this
        // menu is over, and hiding it entirely would read as a different screen.
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)

        ovalOuter.set(
            centreX - px(OUTER), centreY - px(OUTER),
            centreX + px(OUTER), centreY + px(OUTER),
        )
        ovalInner.set(
            centreX - px(INNER), centreY - px(INNER),
            centreX + px(INNER), centreY + px(INNER),
        )

        for (index in wedges.indices) drawWedge(canvas, index)
        drawHub(canvas)
    }

    private fun drawWedge(canvas: Canvas, index: Int) {
        val slot = wedges.getOrNull(index)
        val on = index == held && slot != null

        val ring = geometry()
        val start = ring.startOf(index)
        wedge.reset()
        wedge.arcTo(ovalOuter, start, ring.sweep)
        wedge.arcTo(ovalInner, start + ring.sweep, -ring.sweep)
        wedge.close()

        fill.color = when {
            slot == null -> palette.ground
            on -> palette.limeDim
            else -> palette.inset
        }
        border.color = if (on) palette.lime else palette.hairline
        canvas.drawPath(wedge, fill)
        canvas.drawPath(wedge, border)
        if (slot == null) return

        val ink = when {
            on -> palette.lime
            slot.style == SlotStyle.DEAD -> palette.faint
            else -> palette.secondary
        }

        // The middle of the wedge, halfway between its two radii — from the
        // same place the hit test reads, so the two cannot drift apart.
        val (offsetX, offsetY) = ring.centreOf(index, px((OUTER + INNER) / 2f))
        val x = centreX + offsetX
        val y = centreY + offsetY

        stroke.color = ink
        stroke.strokeWidth = 1.4f
        canvas.save()
        canvas.translate(x - px(ICON) / 2f, y - px(ICON_ABOVE))
        canvas.scale(px(ICON) / ICON, px(ICON) / ICON)
        canvas.drawPath(RailIcons.parsed(slot.icon), stroke)
        canvas.restore()

        label.color = ink
        label.textSize = artboard.text(LABEL)
        val room = px(OUTER - INNER)
        val text = TextUtils.ellipsize(slot.label, label, room, TextUtils.TruncateAt.END)
        canvas.drawText(text, 0, text.length, x, y + px(LABEL_BELOW), label)
    }

    private fun drawHub(canvas: Canvas) {
        fill.color = palette.ground
        border.color = palette.hairline
        canvas.drawCircle(centreX, centreY, px(HUB), fill)
        canvas.drawCircle(centreX, centreY, px(HUB), border)

        label.color = palette.body
        label.textSize = artboard.text(HUB_LABEL)
        // Two lines, from the drawing: the hub says what it is and doubles as
        // the way out, so it is never blank.
        canvas.drawText(hubTop, centreX, centreY - px(3f), label)
        canvas.drawText(hubBottom, centreX, centreY + px(10f), label)
    }

}

