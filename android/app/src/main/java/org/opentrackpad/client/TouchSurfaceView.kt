package org.opentrackpad.client

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * The touch surface itself.
 *
 * It reports where fingers are and nothing else. It deliberately does not
 * recognise taps, scrolls or swipes: that is the whole point of the project,
 * and interpreting a gesture here would turn OpenTrackpad into a remote mouse.
 *
 * It also paints itself — the panel, its hairline and the dot grid from the
 * design — and that is all it paints. Nothing here ever calls `invalidate`, so
 * drawing happens when the system asks and never as a consequence of a finger:
 * the touch path and the drawing path do not meet.
 */
class TouchSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private companion object {
        val PANEL = Color.parseColor("#121314")
        val HAIRLINE = Color.parseColor("#2A2D30")

        /** rgba(138,144,153,0.16), from the design. */
        val DOT = Color.parseColor("#298A9099")

        // Artboard units, which are a physical length. See [Artboard].
        const val RADIUS = 12f
        const val BORDER = 1f

        /** The grid is one dot every 18 units, offset half a cell. */
        const val CELL = 18f
        const val DOT_RADIUS = 1f
    }

    /** Receives every snapshot. Set by the activity. */
    var onFrame: ((TouchFrame) -> Unit)? = null

    /** Called when the usable surface changes size, including at first layout. */
    var onSurfaceSize: ((SurfaceMetrics) -> Unit)? = null

    private val artboard = Artboard.measure(
        resources.displayMetrics,
        resources.displayMetrics.widthPixels,
        resources.configuration.fontScale,
    )
    private val radius = artboard.px(RADIUS)

    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = PANEL
    }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = artboard.px(BORDER).coerceAtLeast(1f)
        color = HAIRLINE
    }
    private val dots = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = dotGrid()
    }
    private val shape = RectF()

    init {
        isFocusable = true
        isClickable = true
        // Android would otherwise batch and smooth on our behalf; we want the
        // samples as they arrive.
        isHapticFeedbackEnabled = false
    }

    /**
     * One cell of the dot grid, tiled.
     *
     * A repeating shader rather than a loop of circles: the grid covers most of
     * the screen and would otherwise be several hundred draw calls for a
     * background that never changes.
     */
    private fun dotGrid(): Shader {
        val cell = artboard.size(CELL).coerceAtLeast(1)
        val tile = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888)
        Canvas(tile).drawCircle(
            cell / 2f,
            cell / 2f,
            artboard.px(DOT_RADIUS),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DOT },
        )
        return BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val half = hairline.strokeWidth / 2f
        shape.set(half, half, width - half, height - half)
        canvas.drawRoundRect(shape, radius, radius, panel)
        canvas.save()
        canvas.clipRect(shape)
        canvas.drawRoundRect(shape, radius, radius, dots)
        canvas.restore()
        canvas.drawRoundRect(shape, radius, radius, hairline)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width > 0 && height > 0) {
            // The pad's own size, not the screen's. The rails are not part of
            // the touchpad, so the physical size the host is told — which sets
            // pointer speed and every gesture distance — must not include them.
            onSurfaceSize?.invoke(
                SurfaceMetrics.measure(resources.displayMetrics, width, height)
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            -> emit(event, event.historySize, releasing = releasedPointer(event), critical = true)

            MotionEvent.ACTION_MOVE ->
                // Only the newest sample, deliberately.
                //
                // Android batches several samples into one event, and sending
                // the history too looks like better fidelity. It is not: those
                // samples describe different moments but all reach the host in
                // the same instant, and libinput derives pointer acceleration
                // from velocity — distance over time. Delivered with no time
                // between them the velocity estimate collapses, acceleration
                // falls back to roughly one-to-one, and the pointer feels both
                // sluggish and jittery.
                //
                // One sample per event gives the display refresh rate, evenly
                // spaced, which is what a real touchpad reports anyway. Sending
                // the history would only work if the host replayed it against
                // the timestamps, which would add latency to buy back detail
                // that libinput smooths out regardless.
                emit(event, event.historySize, releasing = -1, critical = false)

            MotionEvent.ACTION_CANCEL -> {
                // The gesture was taken over by the system. Whatever we think is
                // down may never be lifted, so declare the surface empty.
                onFrame?.invoke(TouchFrame.empty(eventTimeNanos(event, event.historySize)))
            }

            else -> return false
        }
        return true
    }

    /**
     * The pointer index being lifted, or -1.
     *
     * On `ACTION_POINTER_UP` the lifting finger is still present in the event,
     * so it has to be excluded by hand or the host would believe it is still
     * down until the following frame.
     */
    private fun releasedPointer(event: MotionEvent): Int = when (event.actionMasked) {
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> event.actionIndex
        else -> -1
    }

    /**
     * Builds one snapshot from sample [position] of [event].
     *
     * `position == historySize` means the current sample; anything lower is one
     * of the batched historical ones.
     */
    private fun emit(event: MotionEvent, position: Int, releasing: Int, critical: Boolean) {
        val width = width
        val height = height
        if (width <= 0 || height <= 0) return

        val contacts = ArrayList<Contact>(event.pointerCount)
        for (index in 0 until event.pointerCount) {
            if (index == releasing) continue
            if (contacts.size >= Protocol.MAX_CONTACTS) break
            contacts.add(contactAt(event, index, position, width, height))
        }
        onFrame?.invoke(
            TouchFrame(eventTimeNanos(event, position), contacts, critical)
        )
    }

    private fun contactAt(
        event: MotionEvent,
        index: Int,
        position: Int,
        width: Int,
        height: Int,
    ): Contact {
        val historical = position < event.historySize
        val x = if (historical) event.getHistoricalX(index, position) else event.getX(index)
        val y = if (historical) event.getHistoricalY(index, position) else event.getY(index)
        val pressure =
            if (historical) event.getHistoricalPressure(index, position)
            else event.getPressure(index)
        val major =
            if (historical) event.getHistoricalTouchMajor(index, position)
            else event.getTouchMajor(index)

        return Contact(
            id = event.getPointerId(index),
            // A touch at the very edge can report slightly outside the view, and
            // the host rejects out-of-bounds contacts as a protocol error.
            x = x.toIntClamped(width - 1),
            y = y.toIntClamped(height - 1),
            // Pressure is normally 0..1 but some devices exceed it, and plenty
            // report a constant. The host ignores the value; it is sent for
            // diagnostics only.
            pressure = (pressure * Protocol.MAX_PRESSURE).toIntClamped(Protocol.MAX_PRESSURE),
            major = major.toIntClamped(UShort.MAX_VALUE.toInt()),
        )
    }

    private fun eventTimeNanos(event: MotionEvent, position: Int): Long = when {
        position < event.historySize -> event.getHistoricalEventTime(position) * 1_000_000L
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> event.eventTimeNanos
        else -> event.eventTime * 1_000_000L
    }
}

/** Rounds to an int inside `0..max`, turning NaN into 0 rather than garbage. */
private fun Float.toIntClamped(max: Int): Int {
    if (isNaN()) return 0
    return toInt().coerceIn(0, max)
}
