package org.opentrackpad.client

/**
 * Something the control surface asks the computer to do.
 *
 * A closed set on purpose, mirroring the host: there is no action that runs a
 * command, and there never will be. A control surface that can press anything,
 * or run anything, is a remote shell with buttons on it.
 *
 * Which shortcut sits on which button is not decided here. This is only the
 * vocabulary a button may draw from.
 */
sealed interface Action {
    fun encode(sequence: Long): String

    /**
     * Presses a chord and lets it go: `ctrl+c`, `alt+tab`, `super`.
     *
     * The chord is passed through as written and validated by the host, which
     * owns the list of key names. Duplicating that list here would give two
     * places to keep in step and no extra safety — a wrong name is refused
     * either way, and refused where it matters.
     */
    data class KeyChord(val chord: String) : Action {
        override fun encode(sequence: Long) = "ACTION $sequence KEY $chord"
    }
}
