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
     * Taken exactly as sent, and there was briefly a rule here that dropped a
     * reverse-DNS prefix. It is gone, and the reason it went is worth more than
     * the rule was.
     *
     * It was written when this field carried a resource class, where
     * `org.kde.dolphin` truncates to "org.kde.d…" in fifteen millimetres and
     * identifies nothing. The host then changed the field to carry the name an
     * application gives itself — it asks the desktop entry the window belongs
     * to, and the entry states its own name, localised where it offers a
     * translation, with no inference at either step.
     *
     * dc said the stripping could no longer help and could no longer hurt. The
     * first half is true and the second is not: it runs on **every** window
     * now, named ones included, and a name is free to contain a dot.
     * "Node.js" would have become "js". The case it was built for stopped
     * arriving and the case it could damage started.
     *
     * It fired exactly twice on Victor's desktop across a whole session —
     * `org.kde.dolphin` and `dev.warp.WarpPreview`, both correctly — and then
     * the host began sending `Dolphin` and `WarpPreview` from the entry
     * directly. The two strings it existed for stopped arriving and nothing
     * replaced them but strings it could only damage.
     *
     * ## Do not put it back here. It belongs on the host, and not out of
     * politeness
     *
     * A window with no desktop entry still falls back to its class, and on a
     * KDE machine that class is often reverse-DNS. So the shortening is still
     * worth doing — just not from here, and the reason is **information rather
     * than ownership**.
     *
     * The host knows which of the two it is sending. By the time the string
     * reaches this field the two are indistinguishable, so any rule here has to
     * guess from the shape of the string, and shape is exactly what cannot
     * decide it: **a dot in a name that came from a desktop entry is part of
     * the name; a dot in a class that was fallen back to is a namespace.**
     * `Node.js` and `org.kde.dolphin` are the same shape and want opposite
     * treatment.
     *
     * On the host it is not a guess at all — it is a branch it is already
     * standing in. Queued there, and this is the note for whoever is tempted to
     * write it here again instead.
     */
    val label: String get() = withoutAside(application)
}

/**
 * A name with a trailing parenthetical removed: "Ship Studio (b22db3)" is
 * "Ship Studio".
 *
 * The one shortening that is safe, and it is safe because it removes an aside
 * rather than abbreviating a name. A parenthetical suffix is almost never the
 * identifying part — it is a build number, a profile, a channel — and the words
 * before it are what somebody is looking for.
 *
 * A name that is *only* a parenthetical keeps it, because then the aside is all
 * there is and removing it would leave nothing.
 */
private fun withoutAside(name: String): String {
    if (!name.endsWith(')')) return name
    val opened = name.lastIndexOf('(')
    if (opened <= 0) return name
    return name.substring(0, opened).trim().ifEmpty { name }
}

/**
 * Where a name divides, for the rail that has to fit it.
 *
 * Free of Android so it can be tested, because the rule has more edges than it
 * looks and every one of them is somebody's application coming out wrong.
 */
object WindowName {

    /**
     * The index a run-together name divides at, or null if it does not.
     *
     * **Only a lowercase followed by an uppercase.** A run of capitals is an
     * acronym and not two words: `VLC` and `KDE` must not be cut in half, and
     * the transition *into* one — the `C` of `VLCMedia` — is not a boundary
     * either. `VLCMediaPlayer` divides once, after `Media`.
     *
     * The boundary nearest the middle when there are several, since both lines
     * have the same width to work with and the balanced break is the one most
     * likely to let each fit.
     */
    fun boundary(name: String): Int? = (1 until name.length)
        .filter { name[it - 1].isLowerCase() && name[it].isUpperCase() }
        .minByOrNull { kotlin.math.abs(it - name.length / 2) }
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
                // Both are percent-encoded, and this decoded only the title
                // for a while. Every application on the machine it was tested
                // against happened to be one word — `firefox`, `steam`,
                // `systemsettings` — so nothing needed escaping and nothing
                // looked wrong. The host had always escaped this field; the
                // day it started carrying "System Settings" the rail would
                // have read "System%20Settings".
                //
                // A fixture that cannot tell two behaviours apart is not a
                // test of either.
                val application = Wire.decode(parts[5])?.takeIf { it.isNotBlank() } ?: return null
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
