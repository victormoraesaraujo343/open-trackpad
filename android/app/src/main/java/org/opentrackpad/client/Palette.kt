package org.opentrackpad.client

import android.content.Context
import androidx.annotation.AttrRes

/**
 * What colour a thing is, asked once and answered from the theme.
 *
 * Everything that draws goes through this. Before it, twelve colours lived in
 * `colors.xml` and forty-eight more were spelled as hex inside six custom
 * views — including eight that existed nowhere else, which is precisely what
 * the note at the top of `colors.xml` forbids and stayed true anyway, because
 * nothing could be grepped for.
 *
 * Read from **theme attributes** rather than from `@color` directly, and that is
 * the whole point: a colour resource can vary by night mode and by nothing else,
 * while an attribute can be pointed somewhere different by a theme. Victor has
 * approved a second visual identity that ships as a theme rather than a second
 * app, so the difference between "a data change" and "an edit everywhere" is
 * exactly this indirection.
 *
 * Resolved once per view rather than per draw. `obtainStyledAttributes` is a
 * real lookup and `onDraw` runs at the display's refresh rate.
 *
 * ## What this is not
 *
 * It is not a skin. The second identity has chamfered keycaps, cast shadows and
 * a lit LED in the corner of a pressed key — depth, not hue. Repainting this
 * palette would produce the same flat drawing in different colours, which would
 * be neither identity. What a skin selects is a description of a *surface* at
 * rest, lit and pressed; this is the layer underneath that, and it is worth
 * having whether or not the skin ever ships.
 */
class Palette private constructor(
    val ground: Int,
    val panel: Int,
    val inset: Int,
    val hairline: Int,
    val ink: Int,
    val body: Int,
    val secondary: Int,
    val muted: Int,
    val faint: Int,
    val lime: Int,
    val amber: Int,
    val scrim: Int,
    val limeBright: Int,
    val limeDim: Int,
    val amberDim: Int,
    val raised: Int,
    val raisedEdge: Int,
    val stroke: Int,
    val dots: Int,
    val veil: Int,
) {
    companion object {
        fun of(context: Context) = Palette(
            ground = context.themed(R.attr.otpGround),
            panel = context.themed(R.attr.otpPanel),
            inset = context.themed(R.attr.otpInset),
            hairline = context.themed(R.attr.otpHairline),
            ink = context.themed(R.attr.otpInk),
            body = context.themed(R.attr.otpBody),
            secondary = context.themed(R.attr.otpSecondary),
            muted = context.themed(R.attr.otpMuted),
            faint = context.themed(R.attr.otpFaint),
            lime = context.themed(R.attr.otpLime),
            amber = context.themed(R.attr.otpAmber),
            scrim = context.themed(R.attr.otpScrim),
            limeBright = context.themed(R.attr.otpLimeBright),
            limeDim = context.themed(R.attr.otpLimeDim),
            amberDim = context.themed(R.attr.otpAmberDim),
            raised = context.themed(R.attr.otpRaised),
            raisedEdge = context.themed(R.attr.otpRaisedEdge),
            stroke = context.themed(R.attr.otpStroke),
            dots = context.themed(R.attr.otpDots),
            veil = context.themed(R.attr.otpVeil),
        )

        /**
         * One attribute, resolved against this context's theme.
         *
         * Throws rather than falling back to a default. A theme that forgets a
         * colour should fail at the first screen rather than draw one thing
         * black and everything else correctly — an app half in one identity and
         * half in another is harder to diagnose than one that does not start.
         */
        private fun Context.themed(@AttrRes attribute: Int): Int {
            val values = obtainStyledAttributes(intArrayOf(attribute))
            try {
                require(values.hasValue(0)) {
                    "the theme does not answer for attribute ${resources.getResourceName(attribute)}"
                }
                return values.getColor(0, 0)
            } finally {
                values.recycle()
            }
        }
    }
}
