package org.opentrackpad.client

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * What the surface feels like under a finger.
 *
 * The goal from `docs/ROADMAP.md` is the Apple trackpad: nothing moves and it
 * still feels like a click. That machine has a force sensor and a linear
 * actuator and this has neither, and the gap matters less than it sounds,
 * because **what sells the illusion is timing and shape rather than force**.
 * Everything here is built on that one sentence.
 *
 * Three rules, in the order they buy the most:
 *
 * 1. **Fire locally, at the moment of the touch.** Never on a reply from the
 *    computer. A round trip plus the actuator's own start-up delay lands past
 *    the window where a tick still reads as *caused by* the press — after that
 *    window it is a separate event that happens to follow, which is what a
 *    notification feels like.
 * 2. **A click is two events.** A press and a softer release. One tick reads as
 *    a notification; the pair reads as a mechanism, because a mechanism is
 *    something you can feel let go of you.
 * 3. **Composed primitives, not a buzz.** A buzz is one texture at one length
 *    and everything made of it feels like the same thing happening.
 *
 * ## The shapes, and why each is that shape
 *
 * These are a vocabulary rather than a palette: two of them must never be
 * confusable by a finger, or the feedback stops carrying information.
 *
 * | | shape | why |
 * | --- | --- | --- |
 * | [press] | one crisp click | the moment a key goes down |
 * | [release] | one low tick, softer | the same mechanism letting go |
 * | [cross] | one light tick | passing a thing, not choosing it |
 * | [lift] | a quick rise | something coming up off the surface |
 * | [land] | a soft thud | something being put down |
 * | [refused] | two low knocks | see below |
 *
 * **[refused] is the one that carries information rather than confirmation**,
 * and it is the only repeating shape in the vocabulary. Repetition is what makes
 * it read as "no": every other thing this app does feels like a single event, so
 * a second knock arriving where nothing else has one is unmistakable without
 * being unpleasant. It is deliberately not louder, longer or sharper than a
 * success — a refusal that felt like a bigger success would be worse than
 * silence, because it would confirm the thing it is denying.
 *
 * ## Degrading
 *
 * Composition needs Android 11 and hardware that admits to the primitives; below
 * that there are predefined effects, and below that a plain pulse whose
 * amplitude may or may not be honoured. Each rung keeps the *timing* and gives
 * up the *shape*, in that order, because timing is what the illusion rests on:
 * two pulses 90ms apart still read as a refusal on hardware that cannot say
 * anything about texture at all.
 */
class Haptics(context: Context) {

    /** The Haptics switch in settings. Nothing here happens when it is off. */
    var enabled: Boolean = true

    private val vibrator: Vibrator? = run {
        val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        found?.takeIf { it.hasVibrator() }
    }

    /**
     * Whether this phone can be asked for a shape rather than a length.
     *
     * Asked once. `areAllPrimitivesSupported` is a real call into the vibrator
     * service and this is consulted on every press.
     */
    private val composes: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator != null &&
            vibrator.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
            )

    /** Whether the low tick exists, which is Android 12 and its own hardware question. */
    private val hasLowTick: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && vibrator != null &&
            vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)

    private val hasThud: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && vibrator != null &&
            vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)

    private val hasRise: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator != null &&
            vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE)

    // -- the vocabulary -------------------------------------------------------

    /** A key going down: the crisp half of a click. */
    fun press() = compose(
        { it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, PRESS_SCALE) },
        predefined = VibrationEffect.EFFECT_CLICK,
        pulseMs = 12,
        amplitude = 160,
    )

    /**
     * The same key letting go: softer, and lower if the hardware has a low tick.
     *
     * Softer is not a detail. A release as strong as its press reads as a second
     * press, which is two events where the finger felt one movement.
     */
    fun release() = compose(
        { it.addPrimitive(lowTick(), RELEASE_SCALE) },
        predefined = VibrationEffect.EFFECT_TICK,
        pulseMs = 8,
        amplitude = 70,
    )

    /**
     * A whole click at one instant, for the things chosen on release.
     *
     * The Quick Ring picks a wedge when the finger lifts, so there is no earlier
     * moment to put the press half at. Both halves go here, [GAP_MS] apart,
     * which is close enough to feel like one mechanism and far enough not to
     * blur into a single longer buzz.
     */
    fun click() = compose(
        {
            it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, PRESS_SCALE)
            it.addPrimitive(lowTick(), RELEASE_SCALE, GAP_MS)
        },
        predefined = VibrationEffect.EFFECT_CLICK,
        pulseMs = 14,
        amplitude = 170,
    )

    /**
     * Passing something rather than choosing it: a wedge sliding under the
     * finger, a fader crossing a detent.
     *
     * Much lighter than a press on purpose. These arrive in runs — a dragged
     * fader can cross several in a second — and anything heavier would turn a
     * gesture into a rattle.
     */
    fun cross() = compose(
        { it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, CROSS_SCALE) },
        predefined = VibrationEffect.EFFECT_TICK,
        pulseMs = 6,
        amplitude = 50,
    )

    /** A drag picking something up: a shape that goes upwards. */
    fun lift() = compose(
        {
            if (hasRise) {
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, LIFT_SCALE)
            } else {
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, LIFT_SCALE)
            }
        },
        predefined = VibrationEffect.EFFECT_TICK,
        pulseMs = 10,
        amplitude = 90,
    )

    /** A drag putting it down again, or a slot being emptied. */
    fun land() = compose(
        {
            if (hasThud) {
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, LAND_SCALE)
            } else {
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, LAND_SCALE)
            }
        },
        predefined = VibrationEffect.EFFECT_HEAVY_CLICK,
        pulseMs = 16,
        amplitude = 140,
    )

    /**
     * No. The computer refused, or there was nowhere for that to go.
     *
     * Two knocks, evenly spaced, and nothing else in this app knocks twice.
     * `EFFECT_DOUBLE_CLICK` is the same idea one rung down, and two plain pulses
     * [REFUSAL_GAP_MS] apart is the same idea with no texture left at all — the
     * timing survives every rung, which is the point.
     */
    fun refused() {
        if (!enabled) return
        val buzz = vibrator ?: return
        if (composes) {
            val shape = VibrationEffect.startComposition()
                .addPrimitive(lowTick(), REFUSAL_SCALE)
                .addPrimitive(lowTick(), REFUSAL_SCALE, REFUSAL_GAP_MS)
                .compose()
            emit(buzz, shape)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            emit(buzz, VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
            return
        }
        emit(
            buzz,
            VibrationEffect.createWaveform(
                longArrayOf(0, 10, REFUSAL_GAP_MS.toLong(), 10),
                -1,
            ),
        )
    }

    /**
     * Hands this to every control under [root] that can use one.
     *
     * A walk rather than a line per control, for the same reason [Typography]
     * walks: there are nine toggles and pickers across these screens and a
     * hand-written list is a list somebody forgets to add the tenth to. A
     * control that silently feels like nothing is the hardest kind of miss to
     * notice, because the screen looks right.
     */
    fun reach(root: android.view.View) {
        when (root) {
            is PillToggle -> root.haptics = this
            is SegmentedView -> root.haptics = this
            is android.view.ViewGroup ->
                for (index in 0 until root.childCount) reach(root.getChildAt(index))
        }
    }

    // -- the rungs ------------------------------------------------------------

    /**
     * Plays [shape] if the hardware composes, [predefined] if it has effects, and
     * a plain pulse otherwise.
     */
    private fun compose(
        shape: (VibrationEffect.Composition) -> Unit,
        predefined: Int,
        pulseMs: Long,
        amplitude: Int,
    ) {
        if (!enabled) return
        val buzz = vibrator ?: return
        if (composes) {
            emit(buzz, VibrationEffect.startComposition().also(shape).compose())
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            emit(buzz, VibrationEffect.createPredefined(predefined))
            return
        }
        // Amplitude is a request rather than an instruction: hardware without
        // amplitude control runs everything at one strength and the shapes
        // collapse into each other. The lengths still differ, which is the last
        // thing left to distinguish them by.
        val level =
            if (buzz.hasAmplitudeControl()) amplitude else VibrationEffect.DEFAULT_AMPLITUDE
        emit(buzz, VibrationEffect.createOneShot(pulseMs, level))
    }

    /** The low tick where it exists, and the plain one where it does not. */
    private fun lowTick(): Int =
        if (hasLowTick) VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        else VibrationEffect.Composition.PRIMITIVE_TICK

    /**
     * Sends it, told what it is for.
     *
     * `USAGE_TOUCH` is what keeps these alive when somebody has silenced
     * notifications: a control surface that stopped feeling like one because a
     * message arrived would be a strange thing to own.
     */
    private fun emit(buzz: Vibrator, effect: VibrationEffect) {
        val touch = TOUCH
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && touch != null) {
            buzz.vibrate(effect, touch)
        } else {
            @Suppress("DEPRECATION")
            buzz.vibrate(effect, LEGACY_TOUCH)
        }
    }

    private companion object {
        /**
         * How hard each shape is, as a fraction of what the actuator can do.
         *
         * Relative to each other rather than tuned in isolation: what matters is
         * that a release is clearly softer than its press and a crossing is
         * clearly lighter than both. None of these have been felt on Victor's
         * phone — an emulator has no actuator — so they are a considered
         * starting point and nothing more.
         */
        const val PRESS_SCALE = 1.0f
        const val RELEASE_SCALE = 0.45f
        const val CROSS_SCALE = 0.35f
        const val LIFT_SCALE = 0.4f
        const val LAND_SCALE = 0.6f
        const val REFUSAL_SCALE = 0.55f

        /** Press to release. Long enough to be two events, short enough to be one thing. */
        const val GAP_MS = 45

        /** Knock to knock. Deliberately longer, so it cannot be heard as one click. */
        const val REFUSAL_GAP_MS = 90

        val TOUCH: VibrationAttributes? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_TOUCH)
                    .build()
            } else {
                null
            }

        val LEGACY_TOUCH: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
