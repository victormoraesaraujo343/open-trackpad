package org.opentrackpad.client

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.view.WindowManager

/**
 * Looks after the screen while the phone sits on a desk being a trackpad.
 *
 * A dedicated peripheral spends hours showing the same thing, plugged in and
 * warm. Two things follow from that, and this handles both:
 *
 * - The screen has to stay on to feel a finger, but it does not have to stay
 *   bright. After a while without a touch it dims almost to black, and the next
 *   touch brings it straight back.
 * - Anything static can leave a permanent ghost on an OLED panel. Registered
 *   views are nudged a couple of pixels on a slow cycle so nothing sits in
 *   exactly the same place for hours.
 *
 * It knows nothing about the layout: hand it the views that stay put and it
 * looks after them. That is deliberate, because the interface will change and
 * this should not have to.
 */
class ScreenCare(private val window: Window) {

    companion object {
        /** How long without a touch before the screen dims. */
        const val IDLE_AFTER_MS = 30_000L

        /**
         * Brightness while idle. Not zero: some devices read that as "off",
         * and a screen that is off cannot feel a finger.
         */
        const val DIMMED_BRIGHTNESS = 0.02f

        /** How often static views move. Slow enough never to be noticed. */
        const val NUDGE_EVERY_MS = 90_000L

        /** How far they move, in density-independent pixels. */
        const val NUDGE_SCALE_DP = 2f

        /**
         * Whether the screen should be scheduled to dim at all.
         *
         * Pulled out of the class so it can be tested, because [ScreenCare]
         * needs a [Window] and this rule is the part Victor actually hit: he
         * opened settings, read for half a minute, and the screen went almost
         * black under him.
         *
         * Three conditions and all of them necessary. [running] because a
         * paused activity should not be scheduling anything. [wanted] because
         * it is a setting somebody can turn off. [onThePad] because the setting
         * means *the trackpad surface* fades when nobody is touching it, and on
         * every other screen a minute without a finger means somebody is
         * reading.
         */
        fun mayDim(running: Boolean, wanted: Boolean, onThePad: Boolean): Boolean =
            running && wanted && onThePad
    }

    private val handler = Handler(Looper.getMainLooper())
    private val protected = mutableListOf<View>()
    private var nudgeStep = 0
    private var dimmed = false
    private var running = false

    private val dim = Runnable { setBrightness(DIMMED_BRIGHTNESS).also { dimmed = true } }

    private val nudge = Runnable {
        nudgeStep += 1
        applyNudge()
        scheduleNudge()
    }

    /**
     * Registers views that stay in one place, so they can be moved slightly.
     *
     * The touch surface is deliberately not one of them: shifting it would
     * slide part of it off the screen, and the edge of the pad would stop
     * responding.
     */
    fun protect(vararg views: View) {
        protected += views
        applyNudge()
    }

    /**
     * Whether the screen may dim when nothing is touching it.
     *
     * Turning it off does not stop the burn-in nudging: that protects the panel
     * whatever the person prefers, and it is invisible either way.
     */
    var fadeWhenIdle: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) undim()
            handler.removeCallbacks(dim)
            if (mayDim(running, value, showingPad)) handler.postDelayed(dim, IDLE_AFTER_MS)
        }

    /**
     * Whether the trackpad is what is on screen.
     *
     * **Only the pad ever dims.** The setting says the surface fades when
     * nobody is touching it, and a person reading a settings screen is not
     * idle — they are reading. Victor found this the way anybody would: he
     * opened settings, read for half a minute, and the screen went almost black
     * under him.
     *
     * A touch is the wrong signal for those screens. Anywhere else in the app,
     * time passing without a finger means somebody is looking rather than
     * absent, and the only surface where the two are the same thing is the one
     * whose entire purpose is being touched.
     */
    var showingPad: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                undim()
                handler.removeCallbacks(dim)
            } else {
                onActivity()
            }
        }

    /**
     * Called whenever the person does anything, to undo any dimming.
     *
     * Any touch, not only one on the pad. This used to be called from the touch
     * frame alone — the path a finger on the trackpad takes — so a finger on a
     * rail did not count as activity either.
     */
    fun onActivity() {
        if (!running) return
        undim()
        handler.removeCallbacks(dim)
        if (mayDim(running, fadeWhenIdle, showingPad)) handler.postDelayed(dim, IDLE_AFTER_MS)
    }

    private fun undim() {
        if (!dimmed) return
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        dimmed = false
    }

    fun resume() {
        running = true
        onActivity()
        scheduleNudge()
    }

    fun pause() {
        running = false
        handler.removeCallbacks(dim)
        handler.removeCallbacks(nudge)
        // Leave the screen as the system wants it; this window is going away.
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        dimmed = false
    }

    private fun scheduleNudge() {
        handler.removeCallbacks(nudge)
        handler.postDelayed(nudge, NUDGE_EVERY_MS)
    }

    private fun applyNudge() {
        val (x, y) = NudgePattern.at(nudgeStep)
        val scale = NUDGE_SCALE_DP * window.context.resources.displayMetrics.density
        for (view in protected) {
            view.translationX = x * scale
            view.translationY = y * scale
        }
    }

    private fun setBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
    }
}

/**
 * Where a static view sits at each step of the cycle.
 *
 * A closed loop through neighbouring positions rather than random jitter: it
 * covers a small area evenly, always returns to where it started, and never
 * lands twice in a row on the same pixel.
 */
object NudgePattern {
    private val STEPS = listOf(
        0 to 0,
        1 to 1,
        2 to 0,
        1 to -1,
        0 to 0,
        -1 to 1,
        -2 to 0,
        -1 to -1,
    )

    val size: Int get() = STEPS.size

    fun at(step: Int): Pair<Int, Int> = STEPS[step.mod(STEPS.size)]
}
