package org.opentrackpad.client

/**
 * One thing the editor's library offers, ready to be dragged onto a slot.
 *
 * Not the same list as the host's. The host's library is what somebody's
 * desktop can be told to do; this is that **plus what the phone itself can do**,
 * which today is the three mouse buttons. They are not shortcuts, they were
 * never recorded, and they cannot be renamed or deleted — the protocol is
 * explicit that three fixed button names need no gating because they can do
 * nothing a mouse cannot already do.
 *
 * A type of its own rather than bending [LibraryEntry], because [LibraryEntry]
 * is the host's wire shape and every field on it is something the host said. A
 * mouse button has no id the host would recognise, no origin, and no chord.
 * Squeezing it in there would make that type a half-truth, and the next person
 * to read it would have no way to tell which fields were real.
 */
data class Offering(
    /**
     * What the drag carries.
     *
     * Host entries keep the host's id; the built-ins take negative ones, which
     * the host will never issue, so the two can share a list and a lookup
     * without either having to know about the other.
     */
    val id: Int,
    val name: String,

    /** The line under the name: a chord, or what a button is. */
    val detail: String,

    /** The host's bucket, or null for a recorded shortcut. Meaningless for built-ins. */
    val group: ShortcutGroup?,

    val action: Action,

    /** Recorded here by somebody, which earns the dot. */
    val mine: Boolean,

    /** The phone's own, which is neither grouped nor recorded. */
    val builtIn: Boolean,
) {
    fun asSlot() = Slot(name, action)

    companion object {
        fun of(entry: LibraryEntry) = Offering(
            id = entry.id,
            name = entry.name,
            detail = entry.chord,
            group = if (entry.origin == Origin.RECORDED) null else entry.group,
            action = entry.action,
            mine = entry.origin == Origin.RECORDED,
            builtIn = false,
        )

        /**
         * The three the phone can do without asking anybody.
         *
         * Right first, because it is the one somebody comes looking for. Left
         * is here for completeness and is genuinely useful to exactly one
         * person: somebody who turned tap-to-click off and now has no click at
         * all, which is the situation buttons exist for.
         *
         * Version 3 hosts cannot take these. They are still offered, because
         * the alternative is a library that changes shape when a cable is
         * replugged into an older machine, and because the connection drops
         * what an old host cannot hear rather than sending it.
         */
        fun buttons(name: (Action.Button) -> String, detail: String): List<Offering> =
            listOf(Action.Button.RIGHT, Action.Button.MIDDLE, Action.Button.LEFT)
                .mapIndexed { index, button ->
                    Offering(
                        id = BUILT_IN_FIRST_ID - index,
                        name = name(button),
                        detail = detail,
                        group = null,
                        action = Action.Click(button),
                        mine = false,
                        builtIn = true,
                    )
                }

        /** Ids below this are the phone's own. The host issues none of them. */
        const val BUILT_IN_FIRST_ID = -1
    }
}
