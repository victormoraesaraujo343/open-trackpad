package org.opentrackpad.client

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
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
        const val HINT_UNITS = 12f
        const val HINT_GAP_UNITS = 13f

        /** What the rails fade to while the ring is over them, from the drawing. */
        const val DIMMED_BEHIND_RING = 0.55f
    }

    private lateinit var surface: TouchSurfaceView
    private lateinit var connection: HostConnection
    private lateinit var screen: ScreenCare

    private lateinit var railStart: RailView
    private lateinit var railEnd: RailView
    private lateinit var ring: QuickRingView
    private lateinit var profileMenu: ProfileMenuView
    private lateinit var settingsPanel: View
    private lateinit var padHint: TextView

    private lateinit var audioPanel: View
    private lateinit var audioTitle: TextView
    private lateinit var audioHint: TextView
    private lateinit var audioEmpty: TextView
    private lateinit var audioFaders: AudioFadersView
    private lateinit var audioSettingsPage: View

    private lateinit var importPanel: View
    private lateinit var importScreen: ImportPanel
    private lateinit var editorPanel: View
    private lateinit var editorScreen: EditorPanel
    private lateinit var namePanel: View
    private lateinit var recordingPanel: View

    /**
     * One of these for the whole app.
     *
     * A single object rather than one per view, because the Haptics switch has
     * to mean the same thing everywhere and a view that kept its own copy would
     * be a view that could be missed when the switch moves.
     */
    private val haptics by lazy { Haptics(this) }
    private lateinit var nameScreen: NamePanel

    /** The profile being copied, while its name is being chosen. */
    private var copying: Profile? = null

    /** What the computer knows about shortcuts, and what it is offering. */
    private val library = LibraryState()

    /**
     * Whether the offer has been shown without being asked for.
     *
     * Once per session, on the first snapshot that has anything in it. Somebody
     * who skips it should not meet it again on the next reconnection, and it
     * stays reachable from settings for the rest of time.
     */
    private var offeredThisSession = false

    /** Runs the "back to the trackpad" wait. Nothing else uses it. */
    private val idle = Handler(Looper.getMainLooper())

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

    /** The last thing the connection said, so a panel opened later can show it. */
    private var lastState = ConnectionState.CONNECTING

    /** Which protocol version was actually agreed, once one has been. */
    private var speaking: String? = null

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
        ring = findViewById(R.id.quick_ring)
        padHint = findViewById(R.id.pad_hint)

        ring.onChoose = { press ->
            // The ring closes behind a choice. It is a way in to somewhere, not
            // a panel that stays open, and leaving it up over the pad after it
            // has been used would be the same trap as having no way out.
            show(Panel.NONE)
            onPress(press)
        }
        ring.onDismiss = { show(Panel.NONE) }

        profileMenu = findViewById(R.id.profile_menu)
        profileMenu.onChooseProfile = ::chooseProfile
        profileMenu.onChooseDestination = { press -> show(Panel.NONE); onPress(press) }
        profileMenu.onDismiss = { show(Panel.NONE) }

        audioPanel = findViewById(R.id.audio_panel)
        audioTitle = findViewById(R.id.audio_title)
        audioHint = findViewById(R.id.audio_hint)
        audioEmpty = findViewById(R.id.audio_empty)
        audioFaders = findViewById(R.id.audio_faders)
        audioSettingsPage = findViewById(R.id.audio_settings_page)
        bindAudio()

        importPanel = findViewById(R.id.import_panel)
        importScreen = ImportPanel(importPanel)
        importScreen.onDismiss = { show(Panel.NONE) }
        importScreen.onAccept = { generation, ids ->
            accepting = ++requestSequence
            ask(Library.accept(accepting, generation, ids))
        }
        // The screen closes when the host sends the picture that resulted,
        // which is the only acknowledgement the protocol has. Closing on the
        // request instead would leave somebody believing thirty shortcuts had
        // been added when the answer was a refusal.
        importScreen.onAccepted = { show(Panel.NONE) }

        editorPanel = findViewById(R.id.editor_panel)
        editorScreen = EditorPanel(editorPanel)
        editorScreen.onDismiss = { show(Panel.NONE) }
        editorScreen.onSave = { edited ->
            stored = stored.copy(
                profiles = stored.profiles.map { if (it.name == edited.name) edited else it }
            )
            ProfileStore.save(settingsFile, stored)
            layOutRails()
            show(Panel.NONE)
        }

        namePanel = findViewById(R.id.name_panel)
        nameScreen = NamePanel(namePanel)
        nameScreen.isTaken = { name -> stored.profiles.any { it.name.equals(name, true) } }
        nameScreen.onDismiss = { show(Panel.EDITOR) }
        nameScreen.onSave = ::saveCopy
        nameScreen.onTyping = ::waitBeforeReturning

        editorScreen.haptics = haptics
        editorScreen.onRecord = {
            // Sent and forgotten, which is the whole shape of this verb. The
            // recorder is a window on the computer and the shortcut it produces
            // arrives through the library like any other — so there is nothing
            // to hold open, nothing to time out, and nothing to cancel.
            connection.send(Action.Record)
            show(Panel.RECORDING)
        }

        recordingPanel = findViewById(R.id.recording_panel)
        findViewById<View>(R.id.recording_close).setOnClickListener { show(Panel.EDITOR) }

        editorScreen.onDuplicate = { draft ->
            copying = draft
            nameScreen.show(draft, NamePanel.suggest(this, draft, stored.profiles.map { it.name }))
            show(Panel.NAME)
        }

        settingsPanel = findViewById(R.id.settings_panel)
        findViewById<View>(R.id.settings_back).setOnClickListener { show(Panel.NONE) }
        findViewById<View>(R.id.settings_import).setOnClickListener { openImport() }
        bindSettings()

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

        connection = HostConnection(
            // Asked for every time. A host that cannot serve it answers with a
            // shorter list and the panel is simply absent, which is the whole
            // reason the handshake carries one.
            wanted = setOf(Audio.CAPABILITY, Library.SHORTCUTS, Library.IMPORT),
            onState = ::showState,
            onGranted = ::onGranted,
            onLine = ::onHostLine,
        )
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
        screen.protect(padHint)

        // A trackpad that goes to sleep under your fingers is useless, so this
        // is on by default — but it is the person's choice, and until now the
        // setting existed and was ignored. It does not have to stay bright
        // either; ScreenCare handles that half.
        applyKeepAwake(stored.settings.keepScreenAwake)
        screen.fadeWhenIdle = stored.settings.fadeWhenIdle
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
            resources.configuration.fontScale,
        )
        val edge = artboard.size(EDGE_UNITS)

        // Every screen built from XML declares its type in artboard units and
        // has them applied here, once. Nothing is sized by the system alone any
        // more; the font scale multiplies the physical size rather than
        // replacing it.
        Typography.apply(findViewById(R.id.root), artboard)
        haptics.reach(findViewById(R.id.root))

        findViewById<View>(R.id.frame).setPadding(edge, edge, edge, edge)
        for (rail in listOf(railStart, railEnd)) {
            rail.updateLayoutParams { width = artboard.size(Artboard.RAIL_UNITS) }
        }
        findViewById<View>(R.id.pad_holder).updateLayoutParams<MarginLayoutParams> {
            marginStart = edge
            marginEnd = edge
        }

        /*
         * The trouble card takes the middle of the screen, and only the middle.
         *
         * It used to take all of it: a `match_parent` overlay with
         * `clickable="true"`, which swallows every touch it covers. So for as
         * long as a cable was out, both rails were drawn, looked pressable, and
         * were not — including the Quick Ring, which is the only way to
         * settings, profiles and the editor. Somebody unplugged could not reach
         * any part of the app except the screen telling them they were
         * unplugged.
         *
         * That is the same mistake `deadened()` was corrected for, in a form
         * that greying could not have caused: there, a dead button; here, a
         * closed door over every button at once. Inset to the pad instead, which
         * is where the design puts it anyway — the rails were never what the
         * card was covering.
         */
        trouble.updateLayoutParams<MarginLayoutParams> {
            val rail = edge + artboard.size(Artboard.RAIL_UNITS)
            marginStart = rail
            marginEnd = rail
        }
        padHint.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, artboard.text(HINT_UNITS))
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
        val opposite =
            if (panel == Panel.AUDIO) Rails.audioPages(audioPage)
            else Rails.overflow(profile)
        val overflow = opposite.let { if (live || panel == Panel.AUDIO) it else it.deadened() }

        railStart.slots = if (onLeft) shortcuts else overflow
        railEnd.slots = if (onLeft) overflow else shortcuts
        railStart.contentDescription =
            getString(if (onLeft) R.string.rail_shortcuts else R.string.rail_overflow)
        railEnd.contentDescription =
            getString(if (onLeft) R.string.rail_overflow else R.string.rail_shortcuts)

        haptics.enabled = stored.settings.haptics
        railStart.haptics = haptics
        railEnd.haptics = haptics
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
            SlotPress.QuickRing -> show(if (panel == Panel.RING) Panel.NONE else Panel.RING)
            SlotPress.Profiles -> show(Panel.PROFILES)
            SlotPress.Settings -> show(Panel.SETTINGS)
            SlotPress.Editor -> show(Panel.EDITOR)
            SlotPress.Import -> openImport()
            is SlotPress.Audio -> openAudio(press.page)
            SlotPress.Close -> show(Panel.NONE)
            SlotPress.None -> Unit
        }
    }

    /** What is over the trackpad, if anything. Only ever one thing. */
    private enum class Panel {
        NONE, RING, PROFILES, SETTINGS, AUDIO, IMPORT, EDITOR, NAME,

        /**
         * The computer's recorder is open and this phone is telling somebody so.
         *
         * Not a wait. Nothing on this screen is listening for a reply, because
         * `RECORD` has none: what comes back is a new entry in the shortcuts
         * domain, minutes later or never, and it arrives whether this screen is
         * open or closed.
         */
        RECORDING,
    }

    private var panel = Panel.NONE

    /**
     * Puts one panel up and every other one away.
     *
     * One function rather than a show and a hide per panel, because the rule
     * that matters is that **only one thing is ever over the pad**. Two panels
     * open at once is not a state this interface has, and the only reliable way
     * to keep it that way is to have one place that decides.
     *
     * The ring and the profile menu only dim the rails behind them: the slot
     * that opened them has to still be there to close them. Settings takes the
     * whole screen, so there is nothing behind it to dim, and its own back
     * arrow is the way out.
     */
    private fun show(next: Panel) {
        if (panel == next) return
        panel = next

        if (next == Panel.RING) {
            ring.wedges = ringWedges()
            ring.side = stored.settings.shortcutSide
            ring.haptics = haptics
        }
        if (next == Panel.PROFILES) {
            profileMenu.rows = profileRows()
            profileMenu.side = stored.settings.shortcutSide
            profileMenu.haptics = haptics
        }
        if (next == Panel.SETTINGS) showSettings()
        if (next == Panel.IMPORT) importScreen.show(library)
        if (next == Panel.EDITOR) {
            editorScreen.show(activeProfile, library.entries, canRecord = canRecord())
        }

        ring.visibility = if (next == Panel.RING) View.VISIBLE else View.GONE
        profileMenu.visibility = if (next == Panel.PROFILES) View.VISIBLE else View.GONE
        settingsPanel.visibility = if (next == Panel.SETTINGS) View.VISIBLE else View.GONE
        audioPanel.visibility = if (next == Panel.AUDIO) View.VISIBLE else View.GONE
        importPanel.visibility = if (next == Panel.IMPORT) View.VISIBLE else View.GONE
        editorPanel.visibility = if (next == Panel.EDITOR) View.VISIBLE else View.GONE
        namePanel.visibility = if (next == Panel.NAME) View.VISIBLE else View.GONE
        recordingPanel.visibility = if (next == Panel.RECORDING) View.VISIBLE else View.GONE
        showTroubleIfOnThePad()
        // The keyboard belongs to one screen and must not outlive it.
        if (next != Panel.NAME) nameScreen.hideKeyboard()
        // The rail opposite the Quick Ring becomes the panel's pages while one
        // is open, and goes back to being shortcuts when it closes. The rail
        // with the ring on it never changes, so the way out never moves.
        layOutRails()

        val overThePad = next == Panel.RING || next == Panel.PROFILES
        val behind = if (overThePad) DIMMED_BEHIND_RING else 1f
        railStart.alpha = behind
        railEnd.alpha = behind
        padHint.alpha = behind

        waitBeforeReturning()
    }

    /**
     * Sends the trackpad back after a while, because a panel left open stops
     * being a trackpad.
     *
     * The design's own words. Somebody who opens settings, is interrupted, and
     * comes back to the desk should find the surface they plugged in for rather
     * than the screen they forgot about. Zero seconds means never, for anyone
     * who would rather it stayed where they left it.
     */
    private val returnToPad = Runnable { show(Panel.NONE) }

    private fun waitBeforeReturning() {
        idle.removeCallbacks(returnToPad)
        val after = stored.settings.returnToPadSeconds
        if (panel == Panel.NONE || after <= 0) return
        idle.postDelayed(returnToPad, after * 1000L)
    }

    /** Connects the audio panel's own controls and its faders, once. */
    private fun bindAudio() {
        audioFaders.onLevel = { entity, level ->
            ask(Audio.volume(++requestSequence, entity.kind, entity.id, level))
            // The fader follows the finger rather than waiting for the host to
            // agree. The CHANGED that comes back is the acknowledgement and
            // carries what the value actually became, which corrects this if
            // the machine had other ideas.
            showLevelNow(entity, level)
            waitBeforeReturning()
        }
        audioFaders.onMakeDefault = { entity ->
            ask(Audio.makeDefault(++requestSequence, entity.kind, entity.id))
            waitBeforeReturning()
        }
        audioFaders.onMute = { entity, muted ->
            ask(Audio.mute(++requestSequence, entity.kind, entity.id, muted))
            waitBeforeReturning()
        }
        findViewById<PillToggle>(R.id.audio_boost).onChange = { on ->
            update { it.copy(audioBoost = on) }
            audioFaders.allowBoost = on
        }
        findViewById<SegmentedView>(R.id.audio_opens_on).onChoose = { index ->
            update { it.copy(audioOpensOn = AudioPage.openable[index]) }
        }
        findViewById<PillToggle>(R.id.audio_show_idle).onChange = { on ->
            update { it.copy(audioShowIdle = on) }
            showAudio()
        }
    }

    /**
     * Moves one fader without waiting for the host.
     *
     * Only the drawing: the picture the client holds is still the host's, and
     * the next `CHANGED` replaces this. Without it a dragged fader would stick
     * where it was until the round trip finished, which reads as the app being
     * broken rather than the network being slow.
     */
    private fun showLevelNow(entity: AudioEntity, level: Int) {
        audioFaders.faders = audioFaders.faders.map {
            if (it.key == entity.key) it.copy(volume = level) else it
        }
    }

    // -- settings ------------------------------------------------------------

    /**
     * Connects the settings controls to what they change, once.
     *
     * Each one writes through immediately rather than waiting for a Save. There
     * is nothing here that needs to be applied as a set, and a settings screen
     * that can be abandoned half-applied is a settings screen somebody has to
     * remember to finish.
     */
    private fun bindSettings() {
        val handedness = findViewById<SegmentedView>(R.id.settings_handedness)
        handedness.options = listOf(
            getString(R.string.settings_hand_right),
            getString(R.string.settings_hand_left),
        )
        handedness.onChoose = { index ->
            update { it.copy(shortcutSide = if (index == 0) Side.RIGHT else Side.LEFT) }
            layOutRails()
        }

        val returnAfter = findViewById<SegmentedView>(R.id.settings_return)
        returnAfter.options = Settings.RETURN_CHOICES.map(::describeWait)
        returnAfter.onChoose = { index ->
            update { it.copy(returnToPadSeconds = Settings.RETURN_CHOICES[index]) }
            waitBeforeReturning()
        }

        findViewById<PillToggle>(R.id.settings_fade).onChange = { on ->
            update { it.copy(fadeWhenIdle = on) }
            screen.fadeWhenIdle = on
        }
        findViewById<PillToggle>(R.id.settings_haptics).onChange = { on ->
            update { it.copy(haptics = on) }
            // One object, one switch. Nothing has to be told again, which is
            // what stops a view being missed the next time one is added.
            haptics.enabled = on
        }
        findViewById<PillToggle>(R.id.settings_awake).onChange = { on ->
            update { it.copy(keepScreenAwake = on) }
            applyKeepAwake(on)
        }
    }

    /** "15 s", "1 min", "Never" — the wait, as the design words it. */
    private fun describeWait(seconds: Int): String = when {
        seconds <= 0 -> getString(R.string.settings_never)
        seconds % 60 == 0 && seconds == 60 -> getString(R.string.settings_minute)
        else -> getString(R.string.settings_seconds, seconds)
    }

    /** Puts the current settings on the controls, whenever the screen opens. */
    private fun showSettings() {
        val settings = stored.settings
        findViewById<SegmentedView>(R.id.settings_handedness).chosen =
            if (settings.shortcutSide == Side.RIGHT) 0 else 1
        findViewById<SegmentedView>(R.id.settings_return).chosen =
            Settings.RETURN_CHOICES.indexOf(settings.returnToPadSeconds).coerceAtLeast(0)
        findViewById<PillToggle>(R.id.settings_fade).checked = settings.fadeWhenIdle
        findViewById<PillToggle>(R.id.settings_haptics).checked = settings.haptics
        findViewById<PillToggle>(R.id.settings_awake).checked = settings.keepScreenAwake
        findViewById<TextView>(R.id.settings_active_profile).text = settings.activeProfile
        findViewById<TextView>(R.id.settings_version).text = getString(
            R.string.settings_version,
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
            speaking ?: getString(R.string.settings_not_speaking),
        )
        val waiting = library.candidates.size
        findViewById<TextView>(R.id.settings_import_count).apply {
            text = if (waiting == 0) getString(R.string.settings_import_none)
            else getString(R.string.settings_import_count, waiting)
            setTextColor(getColor(if (waiting == 0) R.color.muted else R.color.lime))
        }
        showConnectionIn(settingsPanel)
    }

    /** The connection card, which is the same fact the trouble card carries. */
    private fun showConnectionIn(panel: View) {
        val state = lastState
        panel.findViewById<TextView>(R.id.settings_status).setText(
            when (state) {
                ConnectionState.CONNECTED -> R.string.status_connected
                ConnectionState.LIMITED -> R.string.status_limited
                ConnectionState.BUSY -> R.string.status_busy
                ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> R.string.status_connecting
                else -> R.string.status_disconnected
            }
        )
        panel.findViewById<View>(R.id.settings_status_dot).backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                getColor(if (state.carriesActions) R.color.lime else R.color.amber)
            )
        panel.findViewById<TextView>(R.id.settings_speaking).text =
            speaking ?: getString(R.string.settings_not_speaking)
    }

    /** Changes one setting and writes the file, which is small enough to do here. */
    private fun update(change: (Settings) -> Settings) {
        stored = stored.copy(settings = change(stored.settings))
        ProfileStore.save(settingsFile, stored)
    }

    /**
     * Keeps the copy and starts editing it.
     *
     * The copy becomes the active profile, because duplicating is something
     * done on the way to changing it — leaving the original active would mean
     * every edit after this landed on a profile nobody is looking at.
     */
    private fun saveCopy(name: String) {
        val from = copying ?: return
        copying = null
        val copy = from.copy(name = name)
        stored = stored.copy(
            profiles = stored.profiles + copy,
            settings = stored.settings.copy(activeProfile = name),
        )
        ProfileStore.save(settingsFile, stored)
        layOutRails()
        editorScreen.show(copy, library.entries, canRecord = canRecord())
        show(Panel.EDITOR)
    }

    private fun chooseProfile(name: String) {
        update { it.copy(activeProfile = name) }
        layOutRails()
        show(Panel.NONE)
    }

    private fun applyKeepAwake(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** The rows of the profile menu: every profile, then the ways onward. */
    private fun profileRows(): List<ProfileMenuView.Row> =
        stored.profiles.map { profile ->
            ProfileMenuView.Row.Profile(
                name = profile.name,
                active = profile.name == stored.settings.activeProfile,
            )
        } + listOf(
            ProfileMenuView.Row.Destination(
                label = getString(R.string.profile_manage),
                press = SlotPress.Editor,
            ),
            ProfileMenuView.Row.Destination(
                label = getString(R.string.profile_settings),
                press = SlotPress.Settings,
            ),
        )

    /**
     * What the ring holds: the shortcuts that fit on neither rail.
     *
     * Eight positions, empty ones left empty. With the profiles that ship there
     * is nothing here — nine shortcuts fill both rails exactly — so the ring
     * opens onto eight holes, which is honest rather than useful and is a
     * question outstanding with the orchestrator. The roadmap says the ring is
     * the way in to settings, profiles and modes rather than a shortcut menu;
     * the artboard draws shortcuts in it. Those cannot both be right, and the
     * mechanism is the same either way.
     */
    /**
     * What the Quick Ring holds: destinations, and never shortcuts.
     *
     * From `docs/ROADMAP.md` — "the ring is therefore not a shortcut menu but
     * the app's only way in to anything at all". It reads as a shortcut menu,
     * which is how this was built as one and then quietly showed nothing: the
     * shortcuts it would have drawn were already on the rail opposite, so the
     * ring came out holding its three destinations and looking broken.
     *
     * The ring is built to fit them rather than the other way round: four
     * wedges with a computer attached, two without. Padding to a fixed eight
     * was tried first and looked like an app that had failed to load — six
     * empty wedges do not read as room for later.
     *
     * A destination therefore moves when the host grants a domain, which is the
     * one thing a fixed count bought. It is worth the trade here and would not
     * be on a rail: this list changes only when a cable is plugged in, a rail's
     * changes every time somebody edits a profile.
     */
    private fun ringWedges(): List<RailSlot?> {
        val destinations = buildList {
            // Only when the host said it could serve it. A wedge that opened a
            // panel with nothing behind it would be the "broken" that the
            // capability handshake exists to avoid — absent is the honest shape.
            if (audio.granted) {
                add(
                    RailSlot(
                        label = getString(R.string.audio_sound),
                        icon = RailIcons.path("vol"),
                        press = SlotPress.Audio(stored.settings.audioOpensOn),
                    )
                )
            }
            // Carries the count, because the number is the reason to press it.
            // Present whenever the host offers the domain, including at zero:
            // the screen that says there is nothing new to import is a better
            // answer than a wedge that comes and goes.
            if (library.offers) {
                val waiting = library.candidates.size
                add(
                    RailSlot(
                        label =
                            if (waiting > 0) getString(R.string.ring_import_count, waiting)
                            else getString(R.string.ring_import),
                        icon = RailIcons.path("app"),
                        press = SlotPress.Import,
                    )
                )
            }
            add(
                RailSlot(
                    label = getString(R.string.profile_heading),
                    icon = RailIcons.path("profiles"),
                    press = SlotPress.Profiles,
                )
            )
            add(
                RailSlot(
                    label = getString(R.string.profile_settings),
                    icon = RailIcons.path("gear"),
                    press = SlotPress.Settings,
                )
            )
        }
        return destinations
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

    /** What the phone believes about sound. Empty until a host grants it. */
    private val audio = AudioState()

    /** Which page the audio panel is on. */
    private var audioPage = AudioPage.OUTPUT

    private fun openAudio(page: AudioPage) {
        audioPage = page
        showAudio()
        show(Panel.AUDIO)
        // `show` returns early when the panel is already up, so the rails and
        // the body are refreshed here rather than relying on it.
        layOutRails()
        waitBeforeReturning()
    }

    /** Puts the current picture on whichever page is open. */
    private fun showAudio() {
        val onSettings = audioPage == AudioPage.SETTINGS
        audioTitle.setText(
            when (audioPage) {
                AudioPage.OUTPUT -> R.string.audio_output
                AudioPage.INPUT -> R.string.audio_input
                AudioPage.APPS -> R.string.audio_apps
                AudioPage.SETTINGS -> R.string.audio_settings_title
            }
        )
        audioHint.text = when (audioPage) {
            AudioPage.APPS -> getString(R.string.audio_hint_apps)
            AudioPage.SETTINGS -> ""
            else -> getString(R.string.audio_hint)
        }

        audioSettingsPage.visibility = if (onSettings) View.VISIBLE else View.GONE
        if (onSettings) {
            audioFaders.visibility = View.GONE
            audioEmpty.visibility = View.GONE
            findViewById<PillToggle>(R.id.audio_boost).checked = stored.settings.audioBoost
            findViewById<PillToggle>(R.id.audio_show_idle).checked = stored.settings.audioShowIdle
            findViewById<SegmentedView>(R.id.audio_opens_on).apply {
                options = AudioPage.openable.map { getString(it.title()) }
                chosen = AudioPage.openable.indexOf(stored.settings.audioOpensOn)
                    .coerceAtLeast(0)
            }
            return
        }

        val kind = audioPage.kind ?: return
        val shown = audio.of(kind).filter {
            // A stopped stream is hidden unless asked for. `paused` is certain
            // about silence and only hopeful about the opposite, so this hides
            // what is obviously idle rather than promising the rest is audible.
            stored.settings.audioShowIdle || it.paused != true
        }
        audioFaders.allowBoost = stored.settings.audioBoost
        audioFaders.haptics = haptics
        audioFaders.faders = shown
        audioFaders.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        audioEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        if (shown.isEmpty()) audioEmpty.setText(emptyReason(kind))
    }

    /** What to say when a page has nothing on it, which is never just "empty". */
    private fun emptyReason(kind: AudioKind): Int = when (audio.outage) {
        AudioOutage.NO_TOOL -> R.string.audio_no_tool
        AudioOutage.NO_DAEMON -> R.string.audio_no_daemon
        AudioOutage.LOST -> R.string.audio_lost
        null -> when (kind) {
            AudioKind.OUTPUT -> R.string.audio_none_outputs
            AudioKind.INPUT -> R.string.audio_none_inputs
            AudioKind.STREAM -> R.string.audio_none_apps
        }
    }

    private fun AudioPage.title(): Int = when (this) {
        AudioPage.OUTPUT -> R.string.audio_output
        AudioPage.INPUT -> R.string.audio_input
        AudioPage.APPS -> R.string.audio_apps
        AudioPage.SETTINGS -> R.string.audio_settings
    }

    private var requestSequence = 0L

    /** The sequence of an accept still waiting for an answer, or -1. */
    private var accepting = -1L

    private fun onGranted(granted: Set<String>) {
        audio.grant(Audio.CAPABILITY in granted)
        audio.reset()
        library.reset()
        library.grant(
            shortcuts = Library.SHORTCUTS in granted,
            imports = Library.IMPORT in granted,
        )
        offeredThisSession = false
        // A request from the previous session can never be answered now, and
        // leaving its number here would let a refusal from this one land on a
        // screen that asked nothing.
        accepting = -1
        if (audio.granted) ask(Audio.refresh(++requestSequence))
        if (Library.SHORTCUTS in granted) ask(Library.refresh(++requestSequence, Library.SHORTCUTS))
        // Asked for every time the offer screen opens as well as here: it is
        // re-runnable rather than first-run.
        if (Library.IMPORT in granted) ask(Library.refresh(++requestSequence, Library.IMPORT))
        if (panel == Panel.RING) ring.wedges = ringWedges()
        Log.i(TAG, "host granted: " + granted.ifEmpty { setOf("nothing") }.joinToString(","))
    }

    /**
     * One line from the host.
     *
     * Domains that are not ours are ignored rather than treated as errors: a
     * later host will send messages this version has never heard of, and the
     * client has to be able to be older than the computer it is plugged into.
     */
    private fun onHostLine(line: String) {
        // Refusals first, and by themselves. The line names a sequence rather
        // than a domain, so no domain can claim it and asking one to parse it
        // means the reasons only another domain can produce are dropped.
        Wire.refusal(line)?.let { refusal ->
            /*
             * The one place feedback carries information rather than
             * confirmation, so it gets its own shape and must never be mistaken
             * for a success.
             *
             * This is also the one place that fires on a reply rather than on
             * the touch, and that is not a contradiction of the rule — the rule
             * says a *confirmation* must not wait for the computer, because a
             * late tick stops reading as caused by the press. A refusal has
             * nothing to confirm and could not be known any earlier: the phone
             * finds out that a fader would not move when the computer says so.
             * Arriving late is what it is.
             */
            haptics.refused()
            if (refusal.sequence == accepting) {
                accepting = -1
                importScreen.refused(refusal.reason)
            } else {
                // Everything else springs a control back to whatever the host
                // says is true, which the picture already holds.
                Log.i(TAG, "refused " + refusal.sequence + ": " + refusal.reason)
            }
            return
        }
        Library.parse(line)?.let { message ->
            library.apply(message)?.let { domain ->
                ask(Library.refresh(++requestSequence, domain))
            }
            if (!library.settling) onLibraryChanged()
            return
        }
        val message = Audio.parse(line) ?: return
        // True means we hold a picture that was superseded by one we never saw,
        // so patching it would be guesswork. Ask for the whole thing instead.
        if (audio.apply(message)) ask(Audio.refresh(++requestSequence))
        // A picture that changed under an open panel has to reach the screen,
        // and one that changed under a closed one costs nothing to ignore.
        if (panel == Panel.AUDIO && !audio.settling) showAudio()
    }

    /**
     * The library or the offer changed.
     *
     * The offer arrives on its own on the first connection that has one, and is
     * reachable from settings afterwards — somebody who binds a new desktop
     * shortcut next month should be able to pick it up, and a one-shot offer
     * means the only chance to say yes is the moment they are least ready.
     */
    private fun onLibraryChanged() {
        if (panel == Panel.IMPORT) importScreen.show(library)
        if (!offeredThisSession && library.candidates.isNotEmpty() && panel == Panel.NONE) {
            offeredThisSession = true
            show(Panel.IMPORT)
        }
    }

    /**
     * Opens the import review, from settings or from the ring.
     *
     * Re-asks the host every time rather than trusting what it already had: the
     * offer is re-runnable, and what the computer has may have changed since.
     */
    /**
     * Whether asking the computer to record something could work right now.
     *
     * Both halves are needed and they fail differently: a version 3 host would
     * hang up over the message, and a host that never granted the shortcuts
     * domain would run the recorder and have nowhere to put the result.
     */
    private fun canRecord(): Boolean =
        library.lists && lastState == ConnectionState.CONNECTED

    private fun openImport() {
        ask(Library.refresh(++requestSequence, Library.IMPORT))
        show(Panel.IMPORT)
    }

    private fun ask(line: String?) {
        connection.send(HostConnection.Request(line ?: return))
    }

    private fun onFrame(frame: TouchFrame) {
        screen.onActivity()
        connection.send(frame)
    }

    override fun onResume() {
        super.onResume()
        screen.resume()
        waitBeforeReturning()
    }

    /**
     * Any touch anywhere puts off the return to the trackpad.
     *
     * The design says *a panel left open stops being a trackpad*, and "left"
     * is the word that matters: the wait is for a panel somebody walked away
     * from, not one they are using. Timing from when it opened closed the
     * import review out from under a reader partway down a list of
     * seventy-five, which is the opposite of what the setting is for.
     *
     * Only the down and the up, because a drag is hundreds of moves and
     * rescheduling on each is work for nothing.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> waitBeforeReturning()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onBackPressed() {
        // Back goes back one step, not all the way out. Naming a copy was
        // opened from the editor, so it returns there — collapsing straight to
        // the trackpad would throw away the draft behind it as well, which is
        // two things lost for one press.
        when (panel) {
            Panel.NONE -> @Suppress("DEPRECATION") super.onBackPressed()
            Panel.NAME, Panel.RECORDING -> show(Panel.EDITOR)
            else -> show(Panel.NONE)
        }
    }

    override fun onPause() {
        screen.pause()
        pulse?.cancel()
        idle.removeCallbacks(returnToPad)
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

        /*
         * Edge to edge is what the rails bought. A rail is buttons with
         * generous padding around their middles, so a camera cutout landing in
         * one costs nothing — which is why the control surface takes the whole
         * glass.
         *
         * A screen without rails has none of that. Its content runs to the
         * edge with nothing to absorb a punch-hole, and Victor found items on
         * the settings screens being cut by his. So those screens, and only
         * those, are inset to the cutout's safe area. Not the whole app, and
         * not by giving them fake rails to hide behind.
         */
        val railless = listOf(
            findViewById<View>(R.id.settings_panel),
            findViewById(R.id.import_panel),
            findViewById(R.id.editor_panel),
            findViewById(R.id.name_panel),
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            for (screen in railless) {
                // Added to each screen's own padding rather than replacing it,
                // the same mistake as the first time this was written.
                val edge = resources.getDimensionPixelSize(R.dimen.edge)
                screen.setPadding(
                    edge + cutout.left,
                    edge + cutout.top,
                    edge + cutout.right,
                    edge + cutout.bottom,
                )
            }
            insets
        }

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
        lastState = state
        if (state == ConnectionState.CONNECTED || state == ConnectionState.LIMITED) {
            speaking = detail
        } else if (state == ConnectionState.ERROR || state == ConnectionState.DISCONNECTED) {
            speaking = null
        }
        if (panel == Panel.SETTINGS) showConnectionIn(settingsPanel)
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
        troubleWanted = true
        showTroubleIfOnThePad()
        if (seeking) startPulse() else pulse?.cancel()
    }

    private fun hideTrouble() {
        troubleWanted = false
        trouble.visibility = View.GONE
        pulse?.cancel()
    }

    /**
     * Whether the session is in a state worth a card, regardless of what is on
     * screen right now.
     */
    private var troubleWanted = false

    /**
     * The card is about the pad, so it only shows when the pad is what you are
     * looking at.
     *
     * A panel is a place somebody went on purpose — settings, the ring, the
     * editor — and covering it with a notice about the trackpad would be
     * answering a question nobody asked while hiding the one they did. It is
     * also unavoidable rather than a preference: the ring is drawn inside the
     * pad's own view, so no ordering of siblings can put it above this.
     *
     * Nothing is lost by waiting. The card comes back the moment the pad does,
     * and the state it describes is still true.
     */
    private fun showTroubleIfOnThePad() {
        trouble.visibility =
            if (troubleWanted && panel == Panel.NONE) View.VISIBLE else View.GONE
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
