package org.opentrackpad.client

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView

/**
 * Naming a copied profile.
 *
 * The only place in the app that asks anybody to type, which is why it uses the
 * phone's own keyboard. A keyboard of our own would be a large thing existing
 * for one field, and a drawn one would end up sitting underneath the real one.
 *
 * The field arrives filled in and selected, so somebody who does not care about
 * the name taps Save and is done. Naming a copy is a step on the way to
 * something else, not a decision worth stopping for.
 */
class NamePanel(private val root: View) {

    /** Keep the copy under this name. */
    var onSave: ((String) -> Unit)? = null

    /** Leave without making one. */
    var onDismiss: (() -> Unit)? = null

    /** Whether a name is free. Asked at the moment of saving, not before. */
    var isTaken: (String) -> Boolean = { false }

    /**
     * Somebody is still here.
     *
     * Typing produces no touch events — the keyboard has them — so without
     * this the return-to-trackpad wait would count somebody composing a name as
     * having walked away, and take the screen out from under them mid-word.
     */
    var onTyping: (() -> Unit)? = null

    private val field: EditText = root.findViewById(R.id.name_field)
    private val origin: TextView = root.findViewById(R.id.name_origin)
    private val note: TextView = root.findViewById(R.id.name_note)
    private val defaultNote = note.text

    init {
        root.findViewById<View>(R.id.name_back).setOnClickListener { close() }
        root.findViewById<View>(R.id.name_save).setOnClickListener { save() }
        // Done on the keyboard is the same as Save. Somebody who has just
        // finished typing should not have to find a button.
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(text: android.text.Editable?) {
                onTyping?.invoke()
            }

            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) save()
            true
        }
    }

    /**
     * Opens on a copy of [from], suggesting a name that is already free.
     *
     * Selected rather than merely filled in: the first keystroke should replace
     * the suggestion rather than land in the middle of it.
     */
    fun show(from: Profile, suggestion: String) {
        origin.text = root.context.getString(
            R.string.name_origin,
            from.name,
            from.shortcuts.count { it != null },
        )
        note.text = defaultNote
        field.setText(suggestion)
        field.setSelection(0, suggestion.length)
        field.requestFocus()
        field.post {
            val keyboard = root.context
                .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun save() {
        val name = field.text.toString().trim()
        // Checked here rather than as the person types. A message that appears
        // while somebody is halfway through typing a name they have not
        // finished is telling them off for being mid-word.
        when {
            name.isEmpty() -> complain(R.string.name_blank)
            isTaken(name) -> complain(R.string.name_taken)
            else -> {
                hideKeyboard()
                onSave?.invoke(name)
            }
        }
    }

    private fun complain(message: Int) {
        note.text = root.context.getString(message)
        note.setTextColor(root.context.getColor(R.color.amber))
    }

    private fun close() {
        hideKeyboard()
        onDismiss?.invoke()
    }

    /** Puts the keyboard away with the screen, rather than leaving it up. */
    fun hideKeyboard() {
        val keyboard = root.context
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus()
    }

    companion object {
        /**
         * A name for a copy of [profile] that nothing else is using.
         *
         * "Desktop copy", then "Desktop copy 2". Somebody who duplicates twice
         * should not be stopped by a collision with their own last copy.
         */
        fun suggest(context: Context, profile: Profile, existing: List<String>): String = suggest(
            existing = existing,
            first = context.getString(R.string.name_copy, profile.name),
            numbered = { number ->
                context.getString(R.string.name_copy_numbered, profile.name, number)
            },
        )

        /**
         * The same, without Android in the way, so the counting can be tested.
         *
         * Compared without regard to case, because two profiles differing only
         * in capitals are two profiles nobody can tell apart on a rail.
         */
        fun suggest(existing: List<String>, first: String, numbered: (Int) -> String): String {
            val taken = existing.map { it.lowercase() }.toSet()
            if (first.lowercase() !in taken) return first
            var number = 2
            while (numbered(number).lowercase() in taken) number += 1
            return numbered(number)
        }
    }
}
