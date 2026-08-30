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
        const val VALUE = 13f
        const val NAME = 13f

        /**
         * The port, which is the smallest thing on this screen and still
         * at the floor. Nothing anybody has to read goes below what a rail
         * label measures.
         */
        const val PORT = Artboard.MIN_READABLE_UNITS

        /** A speaker, and a speaker with a cross, on the design's 20-unit grid. */
        const val ICON_ON = "M4.2 8.2h2.6L10.4 5v10L6.8 11.8H4.2zM13.4 7.6a3.4 3.4 0 0 1 0 4.8"
        const val ICON_MUTED =
            "M4.2 8.2h2.6L10.4 5v10L6.8 11.8H4.2zM13.2 8.2l3.6 3.6M16.8 8.2l-3.6 3.6"

        /** Past this much movement a press is a drag, and no longer a mute. */
        const val SLOP = 6f

        /**
         * How far apart the notches are, in per-mille of full volume.
         *
         * Every tenth. Fine enough that a slow drag feels textured rather than
         * empty, coarse enough that a fast one is a run of ticks rather than a
         * buzz.
         */
        const val DETENT = 100

        /** The notch at 100%, above which the level is boosted past the source. */
        const val FULL_DETENT = Audio.REFERENCE / DETENT

        const val NO_DETENT = -1
    }

    /** A level was dragged to [level] per mille. Sent as it moves. */
    var onLevel: ((AudioEntity, Int) -> Unit)? = null

    /** A knob was tapped. */
    var onMute: ((AudioEntity, Boolean) -> Unit)? = null

    /**
     * A device's name was tapped: make it the one in use.
     *
     * A tap rather than a long press. A press-and-hold on a control whose whole
     * job is press-and-drag is a collision waiting to happen, and starting a
     * drag is the one gesture this surface cannot afford to get wrong. The name
     * is already its own region, big enough to hit without looking, and "tap
     * the thing's name to pick it" needs no teaching.
     */
    var onMakeDefault: ((AudioEntity) -> Unit)? = null

    /**
     * How this feels under a finger. Null until the activity supplies it.
     *
     * The switch in settings lives on the object itself, so every view either
     * feels right or feels like nothing, and no view can forget to check.
     */
    var haptics: Haptics? = null

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

    /** Every colour this draws with, from the theme. See [Palette]. */
    private val palette = Palette.of(context)

    private val artboard = Artboard.measure(
        resources.displayMetrics,
        resources.displayMetrics.widthPixels,
        resources.configuration.fontScale,
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

    /** Which detent each finger was last in, so a crossing can be noticed. */
    private val lastDetent = SparseIntArray(4)

    /** Whether each finger landed on the name rather than on the fader. */
    private val onName = SparseIntArray(4)

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

    /**
     * Names that appear on this page more than once.
     *
     * Exact string equality on names the host wrote, with no interpretation of
     * any kind. It is deliberately *not* "does the port disagree with the
     * name": that would mean parsing free text and guessing whether two strings
     * mean the same thing, which is how you end up telling somebody their
     * "Analog Stereo" device is USB. Both are true — one is the profile, the
     * other the transport — and no one reading a phone screen knows that.
     */
    private fun duplicated(): Set<String> = faders
        .groupingBy { it.name }
        .eachCount()
        .filterValues { it > 1 }
        .keys

    /** How tall the block under every track is. One height, so the tracks line up. */
    private fun labelBlockHeight(): Float {
        text.textSize = artboard.text(VALUE)
        val valueLine = text.fontMetrics.let { it.descent - it.ascent }
        text.textSize = artboard.text(NAME)
        val nameLine = text.fontMetrics.let { it.descent - it.ascent }
        text.textSize = artboard.text(PORT)
        val portLine = text.fontMetrics.let { it.descent - it.ascent }

        val room = columnWidth() - px(4f)
        // A long name wraps rather than being cut. These names are long exactly
        // because they are trying to tell themselves apart, so "Built-in Aud…"
        // throws away the part that was doing the work.
        val nameLines = faders.maxOfOrNull { wrapName(it.name, room).size } ?: 1
        val ports = if (duplicated().isEmpty()) 0f else portLine + px(ROW_GAP)
        return valueLine + px(ROW_GAP) + nameLine * nameLines + px(ROW_GAP) * nameLines + ports
    }

    /**
     * Breaks a name over at most two lines.
     *
     * Two rather than any number: past that the fader has no room left, and a
     * name needing three lines is one the person will read from the computer
     * instead. The second line is cut short if even two will not hold it.
     */
    private fun wrapName(name: String, room: Float): List<String> {
        text.textSize = artboard.text(NAME)
        if (text.measureText(name) <= room) return listOf(name)
        val fits = text.breakText(name, true, room, null)
        // Break at a space if there is one to break at, so a word is not split
        // down the middle when the line could simply have ended sooner.
        val space = name.lastIndexOf(' ', fits.coerceAtMost(name.length - 1))
        val cut = if (space > 0) space else fits
        val first = name.substring(0, cut).trim()
        val rest = name.substring(cut).trim()
        return listOf(first, rest)
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

    /**
     * Ticks as the finger crosses a marked position.
     *
     * A fader with no detents is a smooth surface: nothing tells a finger where
     * it is, so setting a level means looking. Detents are what make a
     * continuous control feel like an object — the eye can leave and the hand
     * still knows.
     *
     * Two weights, because two different things are being said. Every tenth is
     * a light [Haptics.cross], the same shape as a wedge passing. **Full volume
     * is a [Haptics.land]**, heavier and distinct, because it is the one place
     * on this fader where crossing means something rather than merely counting:
     * above it the level is boosted past what the source asked for, and that is
     * worth feeling rather than reading.
     */
    private fun feelDetent(pointer: Int, level: Int) {
        val notch = level / DETENT
        val was = lastDetent.get(pointer, NO_DETENT)
        lastDetent.put(pointer, notch)
        if (was == NO_DETENT || notch == was) return
        val crossedFull = (was < FULL_DETENT) != (notch < FULL_DETENT)
        if (crossedFull) haptics?.land() else haptics?.cross()
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
                // Below the track is the name, and a name is tapped rather than
                // dragged. Marking it here keeps a finger that started on the
                // name from ever moving a level.
                onName.put(pointer, if (event.getY(index) >= trackBottom()) 1 else 0)
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val pointer = event.getPointerId(index)
                    val at = holding.indexOfKey(pointer)
                    if (at < 0) continue
                    val column = holding.valueAt(at)
                    val entity = faders.getOrNull(column) ?: continue
                    if (onName.get(pointer) == 1) continue
                    val travelled = abs(event.getY(index) - startY.get(pointer))
                    if (travelled < px(SLOP)) continue
                    moved.put(pointer, 1)
                    val level = levelAt(event.getY(index))
                    feelDetent(pointer, level)
                    // Sent as it moves rather than on release. The host gathers
                    // requests for 50 ms and drops any a later one overtook, so
                    // a drag across the screen costs one change rather than
                    // forty — and the fader follows the finger meanwhile.
                    onLevel?.invoke(entity, level)
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
                        val name = onName.get(pointer) == 1
                        // A stream has no default to be, so its name does
                        // nothing rather than doing something else.
                        val switching = name && entity.kind != AudioKind.STREAM
                        if (!name || switching) {
                            haptics?.click()
                            if (switching) onMakeDefault?.invoke(entity)
                            else if (!name) onMute?.invoke(entity, !entity.muted)
                        }
                    }
                    holding.removeAt(at)
                }
                startY.delete(pointer)
                moved.delete(pointer)
                lastDetent.delete(pointer)
                onName.delete(pointer)
            }

            MotionEvent.ACTION_CANCEL -> {
                holding.clear()
                startY.clear()
                moved.clear()
                onName.clear()
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

        fill.color = palette.raised
        bar.set(centre - radius, top, centre + radius, bottom)
        canvas.drawRoundRect(bar, radius, radius, fill)

        // The bar below the reference is one colour and anything above it is
        // amber, so a glance says you are in the distorting range without
        // reading the number.
        val reference = bottom - (Audio.REFERENCE.toFloat() / ceiling()) * (bottom - top)
        fill.color = when {
            entity.muted -> palette.raisedEdge
            entity.isDefault -> palette.lime
            else -> palette.muted
        }
        bar.set(centre - radius, maxOf(knobY, reference), centre + radius, bottom)
        canvas.drawRoundRect(bar, radius, radius, fill)
        if (boosted) {
            fill.color = if (entity.muted) palette.amberDim else palette.amber
            bar.set(centre - radius, knobY, centre + radius, reference)
            canvas.drawRoundRect(bar, radius, radius, fill)
        }

        fill.color = when {
            entity.muted -> palette.inset
            boosted -> palette.amber
            entity.isDefault -> palette.lime
            else -> palette.hairline
        }
        border.color = if (boosted && !entity.muted) palette.amber else palette.stroke
        canvas.drawCircle(centre, knobY, px(KNOB) / 2f, fill)
        canvas.drawCircle(centre, knobY, px(KNOB) / 2f, border)

        stroke.color = when {
            entity.muted -> palette.muted
            boosted || entity.isDefault -> palette.ground
            else -> palette.body
        }
        stroke.strokeWidth = 1.6f
        val icon = px(KNOB_ICON)
        canvas.save()
        canvas.translate(centre - icon / 2f, knobY - icon / 2f)
        canvas.scale(icon / 20f, icon / 20f)
        canvas.drawPath(RailIcons.parsed(if (entity.muted) ICON_MUTED else ICON_ON), stroke)
        canvas.restore()

        drawLabels(canvas, entity, centre, columnWidth() - px(4f), bottom + px(TRACK_INSET), boosted)
    }

    private fun drawLabels(
        canvas: Canvas,
        entity: AudioEntity,
        centre: Float,
        room: Float,
        from: Float,
        boosted: Boolean,
    ) {
        text.textSize = artboard.text(VALUE)
        val metrics = text.fontMetrics
        var y = from - metrics.ascent

        text.color = when {
            entity.muted -> palette.faint
            boosted -> palette.amber
            else -> palette.body
        }
        val value = if (entity.muted) {
            context.getString(R.string.audio_muted)
        } else {
            context.getString(R.string.audio_percent, entity.percent)
        }
        canvas.drawText(value, centre, y, text)

        text.textSize = artboard.text(NAME)
        val nameLine = text.fontMetrics.let { it.descent - it.ascent }
        text.color = when {
            entity.muted -> palette.muted
            entity.isDefault -> palette.ink
            else -> palette.secondary
        }
        for (part in wrapName(entity.name, room)) {
            y += nameLine + px(ROW_GAP)
            val shown = TextUtils.ellipsize(part, text, room, TextUtils.TruncateAt.END)
            canvas.drawText(shown, 0, shown.length, centre, y, text)
        }

        // The port only where the name cannot tell two things apart. Everywhere
        // else it repeats what the name already says, and a row that is
        // redundant most of the time teaches people to stop reading it.
        val port = entity.port ?: return
        if (entity.name !in duplicated()) return
        text.textSize = artboard.text(PORT)
        y += text.fontMetrics.let { it.descent - it.ascent } + px(ROW_GAP)
        text.color = palette.faint
        canvas.drawText(context.getString(port.label()), centre, y, text)
    }

    private fun AudioPort.label(): Int = when (this) {
        AudioPort.ANALOG -> R.string.audio_port_analog
        AudioPort.USB -> R.string.audio_port_usb
        AudioPort.HDMI -> R.string.audio_port_hdmi
        AudioPort.DIGITAL -> R.string.audio_port_digital
        AudioPort.BLUETOOTH -> R.string.audio_port_bluetooth
    }
}
