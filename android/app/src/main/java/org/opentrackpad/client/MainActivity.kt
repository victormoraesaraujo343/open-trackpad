package org.opentrackpad.client

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import java.io.File

/**
 * The control surface: two rails of five, the trackpad between them.
 *
 * There is no header, no status bar and no settings button — every pixel that is
 * not a rail belongs to the touch surface — so anything the app needs to say
 * about the session takes over the middle of the screen instead.
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "OpenTrackpad"

        // Artboard units, from the screens. See [Artboard].

        /** Around everything, and between a rail and the pad. */
        const val EDGE_UNITS = 12f

        /** The line on the pad, and how far it sits above the bottom edge. */
        const val HINT_UNITS = 10f
        const val HINT_GAP_UNITS = 13f
    }

    private lateinit var surface: TouchSurfaceView
    private lateinit var connection: HostConnection
    private lateinit var screen: ScreenCare

    private lateinit var railStart: RailView
    private lateinit var railEnd: RailView

    private lateinit var trouble: View
    private lateinit var troubleIcon: ImageView
    private lateinit var troubleTitle: TextView
    private lateinit var troubleBody: TextView
    private lateinit var troubleNote: TextView
    private lateinit var troubleVersions: LinearLayout
    private lateinit var troublePhoneVersion: TextView
    private lateinit var troubleHostVersion: TextView
    private lateinit var troubleSeeking: View
    private var pulse: ObjectAnimator? = null

    private lateinit var stored: ProfileStore.Stored

    /** Where settings live. Per-install, so this app starts on the defaults. */
    private val settingsFile: File get() = File(filesDir, "settings.tsv")

    /** Whether a shortcut pressed now would actually reach the computer. */
    private var live = false

    /**
     * Set once the person has acknowledged an older computer.
     *
     * The session works; only the rails do not. Leaving the card up forever
     * would cover a trackpad that is otherwise fine.
     */
    private var mismatchDismissed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surface = findViewById(R.id.touch_surface)
        railStart = findViewById(R.id.rail_start)
        railEnd = findViewById(R.id.rail_end)

        trouble = findViewById(R.id.trouble)
        troubleIcon = findViewById(R.id.trouble_icon)
        troubleTitle = findViewById(R.id.trouble_title)
        troubleBody = findViewById(R.id.trouble_body)
        troubleNote = findViewById(R.id.trouble_note)
        troubleVersions = findViewById(R.id.trouble_versions)
        troublePhoneVersion = findViewById(R.id.trouble_phone_version)
        troubleHostVersion = findViewById(R.id.trouble_host_version)
        troubleSeeking = findViewById(R.id.trouble_seeking)

        // Small enough that reading it is not worth a thread, and everything
        // below depends on it: which shortcuts exist, and which hand holds them.
        stored = ProfileStore.load(settingsFile)

        connection = HostConnection(onState = ::showState)
        surface.onFrame = ::onFrame
        surface.onSurfaceSize = ::onSurfaceSize

        railStart.onPress = ::onPress
        railEnd.onPress = ::onPress

        trouble.setOnClickListener {
            // Only the mismatch card can be dismissed: it is the one problem
            // where carrying on is a real option.
            if (troubleVersions.isVisible()) {
                mismatchDismissed = true
                trouble.visibility = View.GONE
                pulse?.cancel()
            }
        }

        // The hint is the only thing on this screen that never moves and is not
        // the pad. The rails redraw as they are pressed and the pad may not be
        // shifted at all — sliding it would push its edge off the screen.
        screen = ScreenCare(window)
        screen.protect(findViewById(R.id.pad_hint))

        // A trackpad that goes to sleep under your fingers is useless. It does
        // not have to stay bright, though; ScreenCare handles that.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()
        takeTheWholeScreen()
        sizeToTheDrawing()
        layOutRails()
    }

    private fun View.isVisible() = visibility == View.VISIBLE

    /**
     * Puts the control surface on screen at the size it was drawn.
     *
     * The layout file carries the artboard's numbers as dp so the editor
     * preview is not nonsense, and every one of them is replaced here. dp is
     * relative to a display density the person can change; this is a
     * peripheral, and its buttons are a fixed size in the hand. [Artboard] has
     * the whole argument and the clamps.
     *
     * Only the control surface is treated this way — the rails, the pad, the
     * margins between them and the hint on the pad. The card that explains a
     * broken session is left in dp on purpose: that is text somebody reads,
     * not a target hit without looking, and it is the one place where
     * following the system's own sizing is the right answer.
     */
    private fun sizeToTheDrawing() {
        val artboard = Artboard.measure(
            resources.displayMetrics,
            resources.displayMetrics.widthPixels,
        )
        val edge = artboard.size(EDGE_UNITS)

        findViewById<View>(R.id.frame).setPadding(edge, edge, edge, edge)
        for (rail in listOf(railStart, railEnd)) {
            rail.updateLayoutParams { width = artboard.size(Artboard.RAIL_UNITS) }
        }
        findViewById<View>(R.id.pad_holder).updateLayoutParams<MarginLayoutParams> {
            marginStart = edge
            marginEnd = edge
        }
        findViewById<TextView>(R.id.pad_hint).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, artboard.px(HINT_UNITS))
            updateLayoutParams<MarginLayoutParams> { bottomMargin = artboard.size(HINT_GAP_UNITS) }
        }

        // Worth being able to read back from a report rather than inferred from
        // a screenshot: what the drawing actually measures on this glass.
        Log.i(
            TAG,
            "artboard: 1 unit = %.3f px, rail %.1f mm across %d px%s".format(
                artboard.pixelsPerUnit,
                artboard.millimetres(Artboard.RAIL_UNITS),
                artboard.size(Artboard.RAIL_UNITS),
                if (artboard.clamped) " (clamped)" else "",
            ),
        )
    }

    private val activeProfile: Profile
        get() = stored.profiles.firstOrNull { it.name == stored.settings.activeProfile }
            ?: stored.profiles.first()

    /**
     * Fills both rails and puts them on the sides the user chose.
     *
     * The rails do not know which side they are on. Handedness is only which
     * content goes to which of the two columns, so flipping it can never change
     * anything else about them — and the shortcut rail keeps its own order, so
     * a button is in the same place on the hand it moved to.
     */
    private fun layOutRails() {
        val profile = activeProfile
        val onLeft = stored.settings.shortcutSide == Side.LEFT

        val shortcuts = Rails.shortcuts(profile).let { if (live) it else it.deadened() }
        val overflow = Rails.overflow(profile).let { if (live) it else it.deadened() }

        railStart.slots = if (onLeft) shortcuts else overflow
        railEnd.slots = if (onLeft) overflow else shortcuts
        railStart.contentDescription =
            getString(if (onLeft) R.string.rail_shortcuts else R.string.rail_overflow)
        railEnd.contentDescription =
            getString(if (onLeft) R.string.rail_overflow else R.string.rail_shortcuts)

        railStart.hapticsEnabled = stored.settings.haptics
        railEnd.hapticsEnabled = stored.settings.haptics
    }

    /** Greys both rails out, or brings them back. */
    private fun showLive(connected: Boolean) {
        if (live == connected) return
        live = connected
        layOutRails()
    }

    private fun onPress(press: SlotPress) {
        when (press) {
            is SlotPress.Send -> connection.send(press.action)
            // Nothing yet. The ring is the next screen, not this one, and a
            // button that silently does nothing is better than one that guesses.
            SlotPress.QuickRing -> Unit
            SlotPress.None -> Unit
        }
    }

    private var started = false

    private fun onSurfaceSize(metrics: SurfaceMetrics) {
        if (started) {
            connection.surfaceResized(metrics)
        } else {
            started = true
            connection.start(metrics)
        }
    }

    private fun onFrame(frame: TouchFrame) {
        screen.onActivity()
        connection.send(frame)
    }

    override fun onResume() {
        super.onResume()
        screen.resume()
    }

    override fun onPause() {
        screen.pause()
        pulse?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        connection.stop()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // System bars come back after a system gesture or a dialog; put them
        // away again so they never steal touches from the surface.
        if (hasFocus) goImmersive()
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Takes the whole screen, and asks the system to stop taking bits of it
     * back.
     *
     * The layout is not inset for anything: not for the hidden system bars, and
     * not for the camera. A rail is buttons with generous padding around their
     * middles, and a notch across the outer few millimetres of one costs
     * nothing worth a strip of a 6.7-inch screen. The only margin is the 12dp
     * the design asks for.
     *
     * Drawing there is the easy half. The hard half is that the same edges
     * belong to Android's own gestures — a swipe in from the left or right is
     * Back before it is anything of ours, and the bottom strip is Home — and a
     * trackpad stroke that gets taken for a system gesture is a finger that
     * starts a drag and vanishes. So the whole window is claimed as an
     * exclusion rect, which is the most an app may ask for.
     *
     * It is not the most it may *get*. Android caps the claim at 200dp of each
     * side edge and honours nothing at all along the bottom, where Home always
     * wins. What survives that cap is measured rather than assumed; see the
     * report in the commit that turned this on.
     */
    private fun takeTheWholeScreen() {
        val root = findViewById<View>(R.id.root)
        // Consume nothing and inset nothing: the listener exists only so that
        // no ancestor quietly applies the insets on our behalf.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets -> insets }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            root.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val whole = listOf(Rect(0, 0, view.width, view.height))
                if (view.systemGestureExclusionRects != whole) {
                    view.systemGestureExclusionRects = whole
                }
            }
        }
    }

    /**
     * Says what is wrong with the session, or takes the card away.
     *
     * Every state that is not a working v4 session dims the rails, because a
     * shortcut that goes nowhere is worse than one that is visibly unavailable.
     */
    private fun showState(state: ConnectionState, detail: String?) {
        showLive(state.carriesActions)

        when (state) {
            ConnectionState.CONNECTED -> hideTrouble()

            ConnectionState.LIMITED ->
                if (mismatchDismissed) hideTrouble() else showMismatch()

            ConnectionState.INCOMPATIBLE -> showTrouble(
                icon = R.drawable.ic_warning,
                title = getString(R.string.incompatible_title),
                body = getString(R.string.incompatible_body),
                note = detail,
                seeking = true,
            )

            ConnectionState.BUSY -> showTrouble(
                icon = R.drawable.ic_warning,
                title = getString(R.string.busy_title),
                body = getString(R.string.busy_body),
                note = getString(R.string.busy_note),
                seeking = true,
            )

            // The very first attempt says nothing. It takes a moment and it
            // almost always works, and a card claiming the cable came out while
            // the app is still opening the socket would be a lie most times it
            // appeared.
            ConnectionState.CONNECTING -> hideTrouble()

            ConnectionState.ERROR,
            ConnectionState.DISCONNECTED,
            ConnectionState.RECONNECTING,
            -> showTrouble(
                icon = R.drawable.ic_unplugged,
                title = getString(R.string.unplugged_title),
                body = getString(R.string.unplugged_body),
                note = getString(R.string.unplugged_note),
                seeking = true,
            )
        }
    }

    private fun showMismatch() = showTrouble(
        icon = R.drawable.ic_warning,
        title = getString(R.string.mismatch_title),
        body = getString(R.string.mismatch_body),
        note = getString(R.string.mismatch_note),
        seeking = false,
        versions = Protocol.VERSION to Protocol.FALLBACK_VERSION,
    )

    private fun showTrouble(
        icon: Int,
        title: String,
        body: String,
        note: String?,
        seeking: Boolean,
        versions: Pair<String, String>? = null,
    ) {
        troubleIcon.setImageResource(icon)
        troubleTitle.text = title
        troubleBody.text = body
        troubleNote.text = note.orEmpty()
        troubleNote.visibility = if (note.isNullOrBlank()) View.GONE else View.VISIBLE

        if (versions == null) {
            troubleVersions.visibility = View.GONE
        } else {
            troubleVersions.visibility = View.VISIBLE
            // Just the number: "speaks OTP/4" is the wire talking, not a person.
            troublePhoneVersion.text =
                getString(R.string.mismatch_speaks, versions.first.substringAfter('/'))
            troubleHostVersion.text =
                getString(R.string.mismatch_speaks, versions.second.substringAfter('/'))
        }

        troubleSeeking.visibility = if (seeking) View.VISIBLE else View.GONE
        trouble.visibility = View.VISIBLE
        if (seeking) startPulse() else pulse?.cancel()
    }

    private fun hideTrouble() {
        trouble.visibility = View.GONE
        pulse?.cancel()
    }

    /** The dot that says the app is still looking rather than given up. */
    private fun startPulse() {
        if (pulse?.isRunning == true) return
        pulse = ObjectAnimator.ofFloat(findViewById(R.id.trouble_pulse), View.ALPHA, 1f, 0.35f)
            .apply {
                duration = 750
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
    }
}
