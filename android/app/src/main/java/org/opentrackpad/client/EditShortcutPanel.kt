package org.opentrackpad.client

import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/**
 * Rename a shortcut somebody recorded, record it again, or delete it.
 *
 * The screen that was drawn and never built, which is why `Library.rename` and
 * `Library.delete` sat tested with no caller for as long as they did: there was
 * no way to rename or delete anything from the phone at all.
 *
 * Only reachable from a chip with the lime dot, and the gate is the host's
 * rather than ours. A convention is ours and rewritten on upgrade; an import
 * returns to the offer the moment it is deleted, so a button removing one would
 * not mean what the person pressing it expects. Only a recorded shortcut is
 * theirs, and [Origin] says which is which.
 *
 * ## Why it lists the rails
 *
 * Deleting a shortcut empties every slot holding it, and those slots are on
 * screens the person is not looking at. A rail that silently loses a button is
 * the kind of thing found a day later by pressing where something used to be.
 * So the screen says where it is in use before anybody deletes it, and says
 * what will be left behind.
 */
class EditShortcutPanel(private val root: View) {

    /** Save the name. Null name means nothing changed. */
    var onRename: ((LibraryEntry, String) -> Unit)? = null

    /** Ask the computer to record over this one. */
    var onRecordAgain: ((LibraryEntry) -> Unit)? = null

    var onDelete: ((LibraryEntry) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    /** Somebody is still here — typing produces no touches. */
    var onTyping: (() -> Unit)? = null

    var haptics: Haptics? = null

    private val name: EditText = root.findViewById(R.id.edit_name)
    private val keys: LinearLayout = root.findViewById(R.id.edit_keys)
    private val used: LinearLayout = root.findViewById(R.id.edit_used)
    private val usedNote: TextView = root.findViewById(R.id.edit_used_note)
    private val usedEmpty: TextView = root.findViewById(R.id.edit_used_empty)

    private val artboard = Artboard.measure(
        root.resources.displayMetrics,
        root.resources.displayMetrics.widthPixels,
        root.resources.configuration.fontScale,
    )
    private val palette = Palette.of(root.context)

    private var editing: LibraryEntry? = null

    init {
        root.findViewById<View>(R.id.edit_back).setOnClickListener { onDismiss?.invoke() }
        root.findViewById<View>(R.id.edit_save_button).setOnClickListener { save() }
        root.findViewById<View>(R.id.edit_record_again).setOnClickListener {
            haptics?.press()
            haptics?.release()
            editing?.let { onRecordAgain?.invoke(it) }
        }
        root.findViewById<View>(R.id.edit_delete).setOnClickListener {
            haptics?.land()
            editing?.let { onDelete?.invoke(it) }
        }
        root.findViewById<ImageView>(R.id.edit_delete_icon).setImageDrawable(
            RailIcons.drawable(
                root.context,
                RailIcons.path("bin"),
                root.context.getColor(R.color.warn),
                artboard.px(GLYPH),
            )
        )
        name.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(text: Editable?) = Unit.also { onTyping?.invoke() }
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
    }

    /** Opens on [entry], showing every rail it is currently on across [profiles]. */
    fun show(entry: LibraryEntry, profiles: List<Profile>) {
        editing = entry
        name.setText(entry.name)
        name.setSelection(entry.name.length)
        drawKeys(entry.chord)
        drawUsed(entry, profiles)
    }

    private fun save() {
        val entry = editing ?: return
        val wanted = name.text.toString().trim()
        if (wanted.isEmpty() || wanted == entry.name) {
            onDismiss?.invoke()
            return
        }
        haptics?.press()
        haptics?.release()
        onRename?.invoke(entry, wanted)
    }

    /**
     * The chord as caps with pluses between them.
     *
     * Drawn rather than written out as text because a combination is a thing
     * you press, and `ctrl+shift+p` as a sentence is something you read. The
     * cap is what makes the difference, and the heavier bottom edge is the only
     * part of it that matters — it is what turns a rectangle into a top
     * surface.
     */
    private fun drawKeys(chord: String) {
        keys.removeAllViews()
        val parts = chord.split('+').filter { it.isNotBlank() }
        for ((index, key) in parts.withIndex()) {
            if (index > 0) keys.addView(plus())
            keys.addView(cap(key))
        }
    }

    private fun plus() = TextView(root.context).apply {
        text = "+"
        setTextSize(TypedValue.COMPLEX_UNIT_PX, artboard.text(KEY_TEXT))
        setTextColor(palette.muted)
        val gap = artboard.size(KEY_GAP)
        setPadding(gap, 0, gap, 0)
    }

    private fun cap(key: String) = TextView(root.context).apply {
        text = key
        gravity = android.view.Gravity.CENTER
        minWidth = artboard.size(CAP_MIN_WIDTH)
        setPadding(
            artboard.size(CAP_H),
            artboard.size(CAP_V),
            artboard.size(CAP_H),
            artboard.size(CAP_V),
        )
        setBackgroundResource(R.drawable.keycap)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, artboard.text(KEY_TEXT))
        setTextColor(palette.ink)
        typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
    }

    /**
     * Every place this shortcut sits, named by profile and slot.
     *
     * Matched on the action rather than on the name, because the name is the
     * thing being changed and a rename would otherwise appear to move a
     * shortcut off the rails it is on.
     */
    private fun drawUsed(entry: LibraryEntry, profiles: List<Profile>) {
        used.removeAllViews()
        var found = 0
        for (profile in profiles) {
            val slots = profile.shortcuts
                .mapIndexedNotNull { index, slot ->
                    if (slot?.action == entry.action) index else null
                }
            if (slots.isEmpty()) continue
            found += slots.size
            used.addView(
                usedRow(
                    profile.name,
                    slots.joinToString(", ") {
                        root.context.getString(R.string.edit_slot, it + 1)
                    },
                )
            )
        }
        // The note explains what deleting will empty, so with nothing to empty
        // it is explaining a consequence that cannot happen. The empty state
        // takes the column instead, at the top where the rails would be.
        usedEmpty.visibility = if (found == 0) View.VISIBLE else View.GONE
        usedNote.visibility = if (found == 0) View.GONE else View.VISIBLE
    }

    private fun usedRow(profile: String, where: String): View {
        val row = LinearLayout(root.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.chip)
            setPadding(
                artboard.size(ROW_H),
                artboard.size(ROW_V),
                artboard.size(ROW_H),
                artboard.size(ROW_V),
            )
        }
        row.addView(
            TextView(root.context).apply {
                text = profile
                setTextSize(TypedValue.COMPLEX_UNIT_PX, artboard.text(ROW_TEXT))
                setTextColor(palette.secondary)
                typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
            }
        )
        row.addView(
            TextView(root.context).apply {
                text = where
                gravity = android.view.Gravity.END
                maxLines = 1
                setTextSize(TypedValue.COMPLEX_UNIT_PX, artboard.text(ROW_WHERE))
                setTextColor(palette.muted)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = if (used.childCount == 0) 0 else artboard.size(ROW_GAP)
        }
        return row
    }

    private companion object {
        // Artboard units, from EditShortcut.dc.html, with the type floor applied.
        const val GLYPH = 14f
        const val KEY_TEXT = 12f
        const val KEY_GAP = 4f
        const val CAP_MIN_WIDTH = 38f
        const val CAP_H = 11f
        const val CAP_V = 6f
        const val ROW_TEXT = 13f
        const val ROW_WHERE = 12f
        const val ROW_H = 9f
        const val ROW_V = 7f
        const val ROW_GAP = 9f
    }
}
