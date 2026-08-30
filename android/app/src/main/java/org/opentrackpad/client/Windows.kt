package org.opentrackpad.client

/**
 * The desktop's recently used windows, and switching to one.
 *
 * The third domain, in the same shape as the other two: whole snapshots in,
 * requests out, nothing acknowledged. What is different is what the ordering
 * means — this list is **most recently used first**, and that ordering is the
 * only reason the rail exists.
 *
 * ## The ordering is the part that lies
 *
 * A list of windows that looks plausible and never reorders is what this domain
 * invites, and it is a failure that works for an hour: the rail is right the
 * first time somebody looks and wrong every time after. dc found exactly that
 * in the obvious source — it gives identifiers, titles and activation, and
 * activating a window leaves the list byte-identical.
 *
 * So there is nothing here that sorts, dedupes or remembers. The host says the
 * order and the rail draws it, because any cleverness on this side would be a
 * second thing that could be plausibly wrong, and it would hide the first.
 *
 * ## Titles
 *
 * The most attacker-influenced strings in this product. An application name
 * comes from an application; a title comes from whatever a web page decided to
 * call itself. Percent-encoded like every other name, and [Wire.decode] refuses
 * one carrying a newline — so a title cannot become a second line — but nothing
 * downstream may trust one for its length either. The rail truncates and never
 * lets a title decide a size.
 */
data class WindowEntry(
    /**
     * The host's own number, never reused.
     *
     * Not the compositor's identifier, which is a UUID and deliberately never
     * crosses the wire: a stale rail button must not be able to switch to
     * whatever window inherited an identifier.
     */
    val id: Int,

    /** The resource class: `firefox`, `org.kde.dolphin`. What tells four browsers apart. */
    val application: String,

    /** Whatever the window calls itself. Untrusted; see the note above. */
    val title: String,
) {
    /**
     * What a rail slot says.
     *
     * The application, not the title, and this is the one design decision in
     * this file. A title is longer, more specific and completely under a
     * stranger's control; an application name is short, stable, and the thing
     * somebody is actually looking for when they reach for the phone without
     * looking. "Firefox" beats forty characters of page title truncated to
     * eight.
     *
     * The last dotted part, because a resource class is `firefox` on one
     * desktop and `org.kde.dolphin` on another. Seen on Victor's machine, where
     * the rail is fifteen millimetres wide: the full string truncates to
     * "org.kde.d…", which identifies nothing and is worse than no button at
     * all. "dolphin" fits and is the word somebody is looking for.
     *
     * Only the prefix goes. A long single word — `systemsettings` — still
     * truncates, and shortening that would mean inventing names for other
     * people's applications rather than shortening one.
     */
    val label: String get() = application.substringAfterLast('.')
}

sealed interface WindowMessage {
    data class Snapshot(val generation: Long, val count: Int) : WindowMessage
    data class Window(val generation: Long, val entry: WindowEntry) : WindowMessage
    data class Removed(val generation: Long, val id: Int) : WindowMessage
    data class Unavailable(val reason: String) : WindowMessage
}

object Windows {

    /**
     * What the phone asks for in the handshake.
     *
     * **Not always granted, unlike the other three.** It appears in the
     * `WELCOME` only on a desktop the host can actually ask. Where it is absent
     * the rail is absent too — not empty, absent, the same rule audio follows
     * when there is no sound daemon.
     */
    const val CAPABILITY = "windows"

    /** The kind field this domain uses. */
    private const val WINDOW = "window"

    /** How many the rail can show beside its fifth slot. */
    const val ON_THE_RAIL = 4

    /**
     * Reads one line, or null if it is not ours.
     *
     * Unknown verbs are ignored rather than refused, for the same reason as
     * everywhere else: the phone has to be allowed to be older than the
     * computer it is plugged into.
     */
    fun parse(line: String): WindowMessage? {
        val parts = line.trim().split(' ')
        if (parts.size < 2 || parts[1] != CAPABILITY) return null
        return when (parts[0]) {
            "SNAPSHOT" -> {
                if (parts.size != 4) return null
                val generation = parts[2].toLongOrNull() ?: return null
                val count = parts[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                WindowMessage.Snapshot(generation, count)
            }

            "ENTRY", "CHANGED" -> {
                if (parts.size != 7 || parts[3] != WINDOW) return null
                val generation = parts[2].toLongOrNull() ?: return null
                val id = parts[4].toIntOrNull() ?: return null
                // The application is a resource class and arrives as written;
                // the title is percent-encoded like every other name.
                val application = parts[5].takeIf { it.isNotBlank() } ?: return null
                val title = Wire.decode(parts[6]) ?: return null
                WindowMessage.Window(generation, WindowEntry(id, application, title))
            }

            "REMOVED" -> {
                if (parts.size != 4) return null
                val generation = parts[2].toLongOrNull() ?: return null
                val id = parts[3].toIntOrNull() ?: return null
                WindowMessage.Removed(generation, id)
            }

            "UNAVAILABLE" -> if (parts.size == 3) {
                WindowMessage.Unavailable(parts[2])
            } else {
                null
            }

            else -> null
        }
    }

    /** `REQUEST <sequence> windows ACTIVATE <id>` — the only thing this domain takes. */
    fun activate(sequence: Long, id: Int) = "REQUEST $sequence $CAPABILITY ACTIVATE $id"

    fun refresh(sequence: Long) = "REQUEST $sequence $CAPABILITY REFRESH"
}
