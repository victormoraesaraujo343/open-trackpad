package org.opentrackpad.client

/**
 * The shortcut library and the import offer.
 *
 * Two domains rather than one, and they are genuinely different things: the
 * library is what the phone may put on a rail today, the offer is what the
 * computer found lying around and is proposing. Mixing them would mean a
 * candidate could be dragged onto a rail before anybody agreed to it.
 *
 * Written against the format `open-trackpad-dc` published. Nothing here talks
 * to a socket or draws anything.
 */

/**
 * Where a shortcut came from, which decides what may be done to it.
 *
 * Not inferred from the shape of a name — the host says, and only the host
 * knows. The three answers carry different permissions and the differences are
 * deliberate rather than an oversight:
 */
enum class Origin(val wire: String) {
    /** Ours, from the seed table. Rewritten on upgrade, so neither renamed nor deleted. */
    CONVENTION("convention"),

    /**
     * Found on this desktop and accepted.
     *
     * Renamable, because a machine offering "Toggle Present Windows (Current
     * desktop)" is accurate and hopeless under a 15.5mm button. **Not
     * deletable**: a deleted import simply returns to the offer next time, so
     * the button would not mean what the person pressing it expects.
     */
    IMPORTED("imported"),

    /** Recorded here by somebody pressing keys. Theirs, so theirs to rename or delete. */
    RECORDED("recorded"),
    ;

    val renamable: Boolean get() = this != CONVENTION
    val deletable: Boolean get() = this == RECORDED

    companion object {
        fun of(wire: String): Origin? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * What a shortcut is for, as a fixed vocabulary both desktops map into.
 *
 * Fixed rather than free text so a KDE machine and a GNOME machine cannot name
 * the same bucket differently. The mapping from `kwin` and `plasmashell` to
 * these lives on the host, which already knows which desktop it is.
 */
enum class ShortcutGroup(val wire: String) {
    WINDOWS("windows"),
    DESKTOP("desktop"),
    SCREENSHOT("screenshot"),
    SOUND("sound"),
    MEDIA("media"),
    SESSION("session"),
    POWER("power"),
    KEYBOARD("keyboard"),
    ACCESSIBILITY("accessibility"),
    TEXT("text"),
    BROWSER("browser"),
    TERMINAL("terminal"),
    OTHER("other"),
    ;

    companion object {
        /** The host's "no group", which a recorded shortcut always sends. */
        const val NONE = "-"

        fun of(wire: String): ShortcutGroup? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One shortcut the phone may put on a rail.
 *
 * [group] is null for anything recorded here: the person never said what theirs
 * is for, and guessing it from a chord would be worse than silence. Those are
 * gathered under "Mine" by origin instead.
 */
data class LibraryEntry(
    val id: Int,
    val chord: String,
    val origin: Origin,
    val group: ShortcutGroup?,
    val name: String,
) {
    /** The action this becomes on a rail. */
    val action: Action get() = Action.KeyChord(chord)
}

/** One shortcut the computer found and is offering. */
data class Candidate(
    val id: Int,
    val chord: String,
    val group: ShortcutGroup?,
    val recommended: Boolean,
    val name: String,
)

/** Something the host said about one of these two domains. */
sealed interface LibraryMessage {
    data class Snapshot(val domain: String, val generation: Long, val count: Int) : LibraryMessage
    data class Shortcut(val generation: Long, val entry: LibraryEntry, val changed: Boolean) :
        LibraryMessage

    data class Offer(val generation: Long, val candidate: Candidate) : LibraryMessage
    data class Removed(val domain: String, val generation: Long, val id: Int) : LibraryMessage
    data class Unavailable(val domain: String, val reason: String) : LibraryMessage
}

object Library {

    /** What the phone asks for in the handshake. */
    const val SHORTCUTS = "shortcuts"
    const val IMPORT = "import"

    /** The kind field each domain uses in an entry. */
    private const val SHORTCUT = "shortcut"
    private const val CANDIDATE = "candidate"

    /**
     * Reads one line, or null if it is not ours.
     *
     * Unknown verbs and domains are ignored rather than treated as errors, for
     * the same reason as everywhere else: the phone has to be allowed to be
     * older than the computer it is plugged into.
     */
    fun parse(line: String): LibraryMessage? {
        val parts = line.trim().split(' ')
        if (parts.size < 2) return null
        val domain = parts.getOrNull(1)
        if (domain != SHORTCUTS && domain != IMPORT) return null
        return when (parts[0]) {
            "SNAPSHOT" -> snapshot(parts)
            "ENTRY", "CHANGED" -> entry(parts, changed = parts[0] == "CHANGED")
            "REMOVED" -> removed(parts)
            "UNAVAILABLE" -> if (parts.size == 3) {
                LibraryMessage.Unavailable(parts[1], parts[2])
            } else {
                null
            }

            else -> null
        }
    }

    private fun snapshot(parts: List<String>): LibraryMessage? {
        if (parts.size != 4) return null
        val generation = parts[2].toLongOrNull() ?: return null
        val count = parts[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return LibraryMessage.Snapshot(parts[1], generation, count)
    }

    /**
     * `ENTRY shortcuts <gen> shortcut <id> <chord> <origin> <group> <name>`
     * `ENTRY import <gen> candidate <id> <chord> <group> <recommended> <name>`
     */
    private fun entry(parts: List<String>, changed: Boolean): LibraryMessage? {
        if (parts.size != 9) return null
        val generation = parts[2].toLongOrNull() ?: return null
        val id = parts[4].toIntOrNull() ?: return null
        val chord = parts[5].takeIf { it.isNotBlank() } ?: return null

        return when {
            parts[1] == SHORTCUTS && parts[3] == SHORTCUT -> {
                val origin = Origin.of(parts[6]) ?: return null
                val group = group(parts[7]) ?: return null
                // A recorded shortcut has no group and must not claim one: the
                // person never said what theirs is for.
                if (origin == Origin.RECORDED && group.value != null) return null
                val name = Wire.decode(parts[8]) ?: return null
                LibraryMessage.Shortcut(
                    generation,
                    LibraryEntry(id, chord, origin, group.value, name),
                    changed,
                )
            }

            parts[1] == IMPORT && parts[3] == CANDIDATE -> {
                val group = group(parts[6]) ?: return null
                val recommended = when (parts[7]) {
                    "0" -> false
                    "1" -> true
                    else -> return null
                }
                val name = Wire.decode(parts[8]) ?: return null
                LibraryMessage.Offer(
                    generation,
                    Candidate(id, chord, group.value, recommended, name),
                )
            }

            else -> null
        }
    }

    /** A group, wrapped so that "absent" and "unreadable" are different answers. */
    private data class Group(val value: ShortcutGroup?)

    private fun group(wire: String): Group? = when (wire) {
        ShortcutGroup.NONE -> Group(null)
        else -> ShortcutGroup.of(wire)?.let(::Group)
    }

    private fun removed(parts: List<String>): LibraryMessage? {
        // `REMOVED <domain> <generation> <kind> <id>`
        if (parts.size != 5) return null
        val generation = parts[2].toLongOrNull() ?: return null
        val expected = if (parts[1] == SHORTCUTS) SHORTCUT else CANDIDATE
        if (parts[3] != expected) return null
        val id = parts[4].toIntOrNull() ?: return null
        return LibraryMessage.Removed(parts[1], generation, id)
    }

    // -- what the phone asks for ---------------------------------------------

    /**
     * `REQUEST <seq> shortcuts RENAME <id> <name>`
     *
     * Refused here for anything the host would refuse anyway, so a button that
     * cannot work is never wired to a request that cannot succeed.
     */
    fun rename(sequence: Long, entry: LibraryEntry, name: String): String? {
        if (!entry.origin.renamable) return null
        if (name.isBlank()) return null
        return "REQUEST $sequence $SHORTCUTS RENAME ${entry.id} ${Wire.encode(name)}"
    }

    /** `REQUEST <seq> shortcuts DELETE <id>` — recorded shortcuts only. */
    fun delete(sequence: Long, entry: LibraryEntry): String? {
        if (!entry.origin.deletable) return null
        return "REQUEST $sequence $SHORTCUTS DELETE ${entry.id}"
    }

    /**
     * `REQUEST <seq> import ACCEPT <generation> <id>,<id>,...`
     *
     * All or nothing, and it carries the generation the offer was made in. A
     * set naming anything the host no longer knows is refused whole, because a
     * half-applied set leaves somebody looking at a screen that half agrees
     * with their machine.
     *
     * Accepting nothing is not a request. It is the same as not pressing the
     * button, and sending it would ask the host to do nothing at all.
     */
    fun accept(sequence: Long, generation: Long, ids: Collection<Int>): String? {
        if (ids.isEmpty()) return null
        return "REQUEST $sequence $IMPORT ACCEPT $generation ${ids.joinToString(",")}"
    }

    /** `REQUEST <seq> <domain> REFRESH` — the whole picture again. */
    fun refresh(sequence: Long, domain: String): String = "REQUEST $sequence $domain REFRESH"
}
