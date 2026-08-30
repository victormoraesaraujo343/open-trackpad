package org.opentrackpad.client

import android.content.ClipData
import android.text.Editable
import android.util.TypedValue
import android.text.TextWatcher
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/**
 * The profile editor: both rails and the library they are filled from, on one
 * screen.
 *
 * One screen rather than a picker, because picking from a modal hides the thing
 * being changed at the moment of change — you would be choosing a shortcut
 * without being able to see the slot it is going into or what it displaces.
 *
 * Edits are a draft until Save. Everything else in this app writes through
 * immediately, and this is the exception on purpose: filling a rail is several
 * moves that only make sense together, and a half-rearranged rail is a worse
 * thing to walk away from than an unsaved one.
 */
class EditorPanel(private val root: View) {

    /** Keep this profile. */
    var onSave: ((Profile) -> Unit)? = null

    /** Leave, keeping nothing. */
    var onDismiss: (() -> Unit)? = null

    /** Make a copy of what is being edited, which needs a name first. */
    var onDuplicate: ((Profile) -> Unit)? = null

    /** Ask the computer to open its recorder, so there is something new to place. */
    var onRecord: (() -> Unit)? = null

    /** How this feels under a finger. Set by the activity. */
    var haptics: Haptics? = null

    private val inflater = LayoutInflater.from(root.context)
    private val shortcutRail: LinearLayout = root.findViewById(R.id.editor_rail_shortcuts)
    private val overflowRail: LinearLayout = root.findViewById(R.id.editor_rail_overflow)
    private val library: LinearLayout = root.findViewById(R.id.editor_library)
    private val groups: LinearLayout = root.findViewById(R.id.editor_groups)
    private val search: EditText = root.findViewById(R.id.editor_search)
    private val nameLabel: TextView = root.findViewById(R.id.editor_name)

    /** The profile being edited, as it stands. Never the saved one until Save. */
    private var draft: Profile = Profile("", emptyList())
    private var original: Profile = draft
    /** Everything draggable: the host's shortcuts and the phone's own buttons. */
    private var offerings: List<Offering> = emptyList()

    private var bucket: Bucket = Bucket.All

    private val artboard = Artboard.measure(
        root.resources.displayMetrics,
        root.resources.displayMetrics.widthPixels,
        root.resources.configuration.fontScale,
    )

    /**
     * Which chip along the top is chosen.
     *
     * Three shapes rather than a nullable group, because null already means
     * something here — a shortcut somebody recorded, which is its own bucket.
     * A second meaning for null is how the Quick Ring lost its contents.
     */
    private sealed interface Bucket {
        data object All : Bucket
        data object Mouse : Bucket
        data class Desktop(val group: ShortcutGroup?) : Bucket
    }

    init {
        root.findViewById<View>(R.id.editor_back).setOnClickListener { onDismiss?.invoke() }
        root.findViewById<View>(R.id.editor_save).setOnClickListener { onSave?.invoke(draft) }
        root.findViewById<View>(R.id.editor_duplicate).setOnClickListener {
            // The draft rather than the saved profile: somebody who has just
            // rearranged a rail and then duplicates means the thing in front of
            // them, not the one they started from.
            onDuplicate?.invoke(draft)
        }
        root.findViewById<View>(R.id.editor_reset).setOnClickListener {
            draft = original
            drawRails()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(text: Editable?) = drawLibrary()
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
    }

    /**
     * Whether making a new shortcut is possible at all right now.
     *
     * Drawn only when it is. The chip is the one control here that needs a live
     * computer — everything else on this screen rearranges what already exists,
     * and works with the cable out. A dashed outline that did nothing when
     * pressed would be worse than no outline, because there would be no way to
     * tell it from a shortcut that failed.
     */
    private var canRecord = false

    fun show(profile: Profile, entries: List<LibraryEntry>, canRecord: Boolean) {
        this.canRecord = canRecord
        draft = profile
        original = profile
        // The buttons first: they are three fixed things, and burying them under
        // however many shortcuts a desktop happens to have would make the one
        // feature somebody arrives looking for the hardest to find.
        offerings = Offering.buttons(
            name = {
                root.context.getString(
                    when (it) {
                        Action.Button.RIGHT -> R.string.button_right
                        Action.Button.MIDDLE -> R.string.button_middle
                        Action.Button.LEFT -> R.string.button_left
                    }
                )
            },
            detail = root.context.getString(R.string.button_chord),
        ) + entries.map(Offering::of)
        nameLabel.text = profile.name
        // What the search will actually look through, which includes the phone's
        // own buttons. It counted the host's entries alone and said "Search 0
        // shortcuts" over a list of three.
        search.hint = root.context.getString(R.string.editor_search, offerings.size)
        bucket = Bucket.All
        drawRails()
        drawGroups()
        drawLibrary()
    }

    // -- the two rails --------------------------------------------------------

    private fun drawRails() {
        fill(shortcutRail, R.string.editor_rail_shortcuts, 0)
        fill(overflowRail, R.string.editor_rail_overflow, Profile.RAIL_SLOTS)
    }

    /**
     * Draws one rail: a heading and five slots.
     *
     * [from] is where this rail starts in the profile's one list. The rails are
     * two windows onto the same ordered list rather than two lists, which is
     * why moving a shortcut between them is only a change of position.
     */
    private fun fill(into: LinearLayout, heading: Int, from: Int) {
        into.removeAllViews()
        val title = TextView(root.context).apply {
            text = root.context.getString(heading)
            setTextColor(root.context.getColor(R.color.muted))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, artboard.text(RAIL_HEADING))
            typeface = ResourcesCompat.getFont(root.context, R.font.inter_medium)
        }
        into.addView(title)

        for (offset in 0 until Rails.SLOTS) {
            val index = from + offset
            val slot = inflater.inflate(R.layout.row_editor_slot, into, false)
            // The fifth slot of the shortcut rail is the Quick Ring, which is
            // not a shortcut and is not the person's to replace. It shows a
            // lock rather than being absent, so the rail still reads as five.
            val locked = from == 0 && offset == Rails.SLOTS - 1
            val entry = if (locked) null else draft.shortcuts.getOrNull(index)

            slot.findViewById<TextView>(R.id.slot_label).apply {
                text = when {
                    locked -> root.context.getString(R.string.editor_quick)
                    entry != null -> entry.label
                    else -> root.context.getString(R.string.editor_empty)
                }
                setTextColor(
                    root.context.getColor(
                        when {
                            locked -> R.color.muted
                            entry != null -> R.color.secondary
                            else -> R.color.faint
                        }
                    )
                )
            }
            slot.findViewById<ImageView>(R.id.slot_lock).visibility =
                if (locked) View.VISIBLE else View.GONE

            val params = slot.layoutParams as LinearLayout.LayoutParams
            params.height = 0
            params.weight = 1f
            params.topMargin = (6 * root.resources.displayMetrics.density).toInt()
            slot.layoutParams = params

            if (!locked) {
                slot.setOnDragListener { view, event -> onDrag(view, event, index) }
                // Tapping a filled slot empties it. There is nowhere else for
                // "remove" to live on a screen whose only other gesture is a
                // drag, and an empty slot is a real state rather than an error.
                slot.setOnClickListener {
                    if (draft.shortcuts.getOrNull(index) != null) {
                        // The same shape as a drop landing, because the same
                        // thing happened to the rail: something is now
                        // somewhere it was not.
                        haptics?.land()
                        draft = draft.clear(index)
                        drawRails()
                    }
                }
            }
            into.addView(slot)
        }
    }

    /**
     * A slot taking a drop.
     *
     * The dragged shortcut **replaces** what was in that position, and nothing
     * else moves. Inserting and pushing the rest down would be the friendlier
     * instinct and is the wrong one here: it would shift every button below the
     * drop, which is the shuffling the whole positional model exists to
     * prevent. A rail is used without looking, so a drop must change exactly
     * one slot.
     *
     * What makes that safe is Reset, which is the reason edits are a draft.
     */
    private fun onDrag(view: View, event: DragEvent, index: Int): Boolean = when (event.action) {
        DragEvent.ACTION_DRAG_STARTED -> true

        DragEvent.ACTION_DRAG_ENTERED -> {
            view.isActivated = true
            true
        }

        DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
            view.isActivated = false
            true
        }

        DragEvent.ACTION_DROP -> {
            view.isActivated = false
            val id = event.clipData?.getItemAt(0)?.text?.toString()?.toIntOrNull()
            val dropped = offerings.firstOrNull { it.id == id }
            if (dropped != null) {
                haptics?.land()
                draft = draft.put(index, dropped.asSlot())
                drawRails()
            } else {
                // Dropped on a slot and nothing arrived — the thing being
                // dragged is gone from the library, because the host sent a new
                // one mid-gesture. A refusal rather than silence: the finger did
                // something and nothing happened, which without an answer looks
                // like the app missing the gesture.
                haptics?.refused()
            }
            dropped != null
        }

        else -> true
    }

    // -- the library ----------------------------------------------------------

    private fun drawGroups() {
        groups.removeAllViews()
        addGroupChip(Bucket.All, root.context.getString(R.string.editor_all))
        addGroupChip(Bucket.Mouse, root.context.getString(R.string.group_mouse))
        val present = offerings.filterNot { it.builtIn }.map { it.group }.distinct()
        for (candidate in present) {
            addGroupChip(Bucket.Desktop(candidate), nameOf(candidate))
        }
    }

    private fun addGroupChip(which: Bucket, label: String) {
        val chip = TextView(root.context).apply {
            text = label
            setPadding(
                artboard.size(CHIP_H),
                artboard.size(CHIP_V),
                artboard.size(CHIP_H),
                artboard.size(CHIP_V),
            )
            setTextSize(TypedValue.COMPLEX_UNIT_PX, artboard.text(CHIP_TEXT))
            setBackgroundResource(R.drawable.group_chip)
            isActivated = which == bucket
            setTextColor(context.getColor(if (isActivated) R.color.ink else R.color.muted))
            setOnClickListener {
                bucket = which
                drawGroups()
                drawLibrary()
            }
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.marginEnd = artboard.size(CHIP_GAP)
        groups.addView(chip, params)
    }

    private fun drawLibrary() {
        library.removeAllViews()
        val query = search.text.toString().trim().lowercase()
        val shown = offerings.filter { offering ->
            val inBucket = when (val where = bucket) {
                Bucket.All -> true
                Bucket.Mouse -> offering.builtIn
                is Bucket.Desktop -> !offering.builtIn && offering.group == where.group
            }
            // Searched by name and by detail, because somebody looking for the
            // key combination they already know is at least as likely as
            // somebody looking for the word.
            val matches = query.isEmpty() ||
                offering.name.lowercase().contains(query) ||
                offering.detail.lowercase().contains(query)
            inBucket && matches
        }

        for (offering in shown) addLibraryChip(offering)

        // Last, and only where a new shortcut would actually arrive. The chip
        // makes a shortcut rather than being one, which is why the artboard
        // draws it dashed and unfilled, and why it is not draggable: there is
        // nothing yet to drag.
        if (canRecord && query.isEmpty() && bucket != Bucket.Mouse) addRecordChip()
    }

    private fun addLibraryChip(offering: Offering) {
        val chip = inflater.inflate(R.layout.row_library_chip, library, false)
        chip.findViewById<TextView>(R.id.chip_name).text = offering.name
        chip.findViewById<TextView>(R.id.chip_chord).text = offering.detail
        chip.findViewById<View>(R.id.chip_mine).visibility =
            if (offering.mine) View.VISIBLE else View.INVISIBLE

        chip.setOnLongClickListener { view ->
            // The id rather than the whole shortcut: a drag can outlive the
            // list it started from if the host sends a new one mid-gesture,
            // and looking the id up at the drop is how the dropped thing
            // stays the thing that still exists.
            val data = ClipData.newPlainText("shortcut", offering.id.toString())
            haptics?.lift()
            view.startDragAndDrop(data, View.DragShadowBuilder(view), null, 0)
            true
        }
        stack(chip)
    }

    private fun addRecordChip() {
        val chip = TextView(root.context).apply {
            text = context.getString(R.string.editor_new)
            gravity = android.view.Gravity.CENTER
            setPadding(artboard.size(CHIP_H), artboard.size(NEW_V), artboard.size(CHIP_H), artboard.size(NEW_V))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, artboard.text(NEW_TEXT))
            setTextColor(context.getColor(R.color.muted))
            setBackgroundResource(R.drawable.new_shortcut_chip)
            typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
            setOnClickListener {
                haptics?.press()
                haptics?.release()
                onRecord?.invoke()
            }
        }
        stack(chip)
    }

    /** Adds [chip] to the library with the gap the drawing puts between them. */
    private fun stack(chip: View) {
        chip.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = if (library.childCount == 0) 0 else artboard.size(CHIP_STACK)
        }
        library.addView(chip)
    }

    private companion object {
        // Artboard units, like everything else on the phone.
        const val CHIP_H = 9f
        const val CHIP_V = 3f
        const val CHIP_GAP = 4f
        const val CHIP_STACK = 5f
        const val CHIP_TEXT = Artboard.MIN_READABLE_UNITS
        const val NEW_V = 7f
        const val NEW_TEXT = Artboard.MIN_READABLE_UNITS
        const val RAIL_HEADING = Artboard.MIN_READABLE_UNITS
    }

    private fun nameOf(group: ShortcutGroup?): String = root.context.getString(
        when (group) {
            ShortcutGroup.WINDOWS -> R.string.group_windows
            ShortcutGroup.DESKTOP -> R.string.group_desktop
            ShortcutGroup.SCREENSHOT -> R.string.group_screenshot
            ShortcutGroup.SOUND -> R.string.group_sound
            ShortcutGroup.MEDIA -> R.string.group_media
            ShortcutGroup.SESSION -> R.string.group_session
            ShortcutGroup.POWER -> R.string.group_power
            ShortcutGroup.KEYBOARD -> R.string.group_keyboard
            ShortcutGroup.ACCESSIBILITY -> R.string.group_accessibility
            ShortcutGroup.TEXT -> R.string.group_text
            ShortcutGroup.BROWSER -> R.string.group_browser
            ShortcutGroup.TERMINAL -> R.string.group_terminal
            ShortcutGroup.OTHER -> R.string.group_other
            null -> R.string.group_mine
        }
    )
}


