package org.opentrackpad.client

import android.content.Context
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
 */
class TouchSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Receives every snapshot. Set by the activity. */
    var onFrame: ((TouchFrame) -> Unit)? = null

    /** Called when the usable surface changes size, including at first layout. */
    var onSurfaceSize: ((SurfaceMetrics) -> Unit)? = null

    init {
        isFocusable = true
        isClickable = true
        // Android would otherwise batch and smooth on our behalf; we want the
        // samples as they arrive.
        isHapticFeedbackEnabled = false
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width > 0 && height > 0) {
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

            MotionEvent.ACTION_MOVE -> {
                // Android batches several samples into one event. Sending the
                // history as well preserves the sampling rate of the digitiser
                // rather than the refresh rate of the display.
                for (position in 0 until event.historySize) {
                    emit(event, position, releasing = -1, critical = false)
                }
                emit(event, event.historySize, releasing = -1, critical = false)
            }

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
