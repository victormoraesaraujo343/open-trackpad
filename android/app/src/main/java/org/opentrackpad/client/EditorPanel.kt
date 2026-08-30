package org.opentrackpad.client

import android.content.ClipData
import android.text.Editable
import android.text.TextWatcher
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

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
    private var entries: List<LibraryEntry> = emptyList()
    private var group: ShortcutGroup? = null
    private var anyGroup = true

    init {
        root.findViewById<View>(R.id.editor_back).setOnClickListener { onDismiss?.invoke() }
        root.findViewById<View>(R.id.editor_save).setOnClickListener { onSave?.invoke(draft) }
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

    fun show(profile: Profile, entries: List<LibraryEntry>) {
        draft = profile
        original = profile
        this.entries = entries
        nameLabel.text = profile.name
        search.hint = root.context.getString(R.string.editor_search, entries.size)
        anyGroup = true
        group = null
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
            textSize = 10f
            typeface = androidx.core.content.res.ResourcesCompat
                .getFont(root.context, R.font.inter_medium)
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
            val entry = entries.firstOrNull { it.id == id }
            if (entry != null) {
                draft = draft.put(index, entry.asSlot())
                drawRails()
            }
            entry != null
        }

        else -> true
    }

    // -- the library ----------------------------------------------------------

    private fun drawGroups() {
        groups.removeAllViews()
        val present = entries.map { if (it.origin == Origin.RECORDED) null else it.group }
            .distinct()
        addGroupChip(null, all = true)
        for (candidate in present) addGroupChip(candidate, all = false)
    }

    private fun addGroupChip(which: ShortcutGroup?, all: Boolean) {
        val chip = TextView(root.context).apply {
            text = if (all) context.getString(R.string.editor_all) else nameOf(which)
            setPadding(
                (9 * resources.displayMetrics.density).toInt(),
                (3 * resources.displayMetrics.density).toInt(),
                (9 * resources.displayMetrics.density).toInt(),
                (3 * resources.displayMetrics.density).toInt(),
            )
            textSize = 10f
            setBackgroundResource(R.drawable.group_chip)
            isActivated = if (all) anyGroup else !anyGroup && group == which
            setTextColor(context.getColor(if (isActivated) R.color.ink else R.color.muted))
            setOnClickListener {
                anyGroup = all
                group = which
                drawGroups()
                drawLibrary()
            }
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.marginEnd = (4 * root.resources.displayMetrics.density).toInt()
        groups.addView(chip, params)
    }

    private fun drawLibrary() {
        library.removeAllViews()
        val query = search.text.toString().trim().lowercase()
        val shown = entries.filter { entry ->
            val itsGroup = if (entry.origin == Origin.RECORDED) null else entry.group
            val inGroup = anyGroup || itsGroup == group
            // Searched by name and by chord, because somebody looking for the
            // key combination they already know is at least as likely as
            // somebody looking for the word.
            val matches = query.isEmpty() ||
                entry.name.lowercase().contains(query) ||
                entry.chord.lowercase().contains(query)
            inGroup && matches
        }

        for (entry in shown) {
            val chip = inflater.inflate(R.layout.row_library_chip, library, false)
            chip.findViewById<TextView>(R.id.chip_name).text = entry.name
            chip.findViewById<TextView>(R.id.chip_chord).text = entry.chord
            chip.findViewById<View>(R.id.chip_mine).visibility =
                if (entry.origin == Origin.RECORDED) View.VISIBLE else View.INVISIBLE

            chip.setOnLongClickListener { view ->
                // The id rather than the whole shortcut: a drag can outlive the
                // list it started from if the host sends a new one mid-gesture,
                // and looking the id up at the drop is how the dropped thing
                // stays the thing that still exists.
                val data = ClipData.newPlainText("shortcut", entry.id.toString())
                view.startDragAndDrop(data, View.DragShadowBuilder(view), null, 0)
                true
            }
            (chip.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
                if (library.childCount == 0) 0
                else (5 * root.resources.displayMetrics.density).toInt()
            library.addView(chip)
        }
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

/** The rail entry a library shortcut becomes. */
private fun LibraryEntry.asSlot() = Slot(name, action)
