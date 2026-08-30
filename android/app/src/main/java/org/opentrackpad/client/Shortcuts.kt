package org.opentrackpad.client

/**
 * One button: what it says, and what it does.
 */
data class Slot(val label: String, val action: Action)

/**
 * A named set of shortcuts, ordered.
 *
 * One list rather than one per rail. The first [RAIL_SLOTS] are the shortcut
 * rail; everything after them is the rail opposite. Moving a shortcut between
 * the two is therefore a change of position and nothing else, and the two views
 * can never disagree about what exists.
 *
 * The Quick Ring is not in this list and never was, though the names here said
 * it was until the ring was pinned down. It holds destinations — settings,
 * profiles, the audio panel — and no shortcuts at all.
 */
data class Profile(val name: String, val shortcuts: List<Slot?>) {

    /**
     * A position with nothing in it.
     *
     * The list is positions rather than contents, and that is the whole point:
     * emptying the second slot must not pull the third one up into it. **A
     * button that moves is a button pressed by mistake**, and a surface used
     * without looking cannot have its arrangement rearrange itself as a side
     * effect of removing something else.
     */
    val rail: List<Slot?> get() = shortcuts.take(RAIL_SLOTS)

    /**
     * Everything after the shortcut rail: the rail opposite it.
     *
     * This was called `ring`, and the name cost an evening. The rail opposite
     * read it as "not on the shortcut rail" and the Quick Ring read it as "not
     * on either rail" — one property, two meanings, forty lines apart, and the
     * arithmetic that followed was correct in both places. What Victor saw was
     * a ring with three items in it, which is what nine shortcuts minus four
     * minus five comes to.
     *
     * Nothing past the tenth shortcut is reachable today. That is honest and it
     * is a limit worth knowing about: it used to be hidden behind a ring that
     * was never going to show them.
     */
    val overflow: List<Slot?> get() = shortcuts.drop(RAIL_SLOTS)

    /** The same profile with [index] emptied, every other position untouched. */
    fun clear(index: Int): Profile {
        if (index !in shortcuts.indices) return this
        return copy(shortcuts = shortcuts.toMutableList().also { it[index] = null })
    }

    /** The same profile with [slot] at [index], growing the list if it must. */
    fun put(index: Int, slot: Slot): Profile {
        if (index < 0) return this
        val out = shortcuts.toMutableList()
        while (out.size <= index) out.add(null)
        out[index] = slot
        return copy(shortcuts = out)
    }

    /**
     * Moves the shortcut at [from] to [to].
     *
     * **Nothing calls this, and that is correct rather than unfinished.** It was
     * written for promoting a shortcut out of the Quick Ring onto the rail, back
     * when the ring was believed to hold shortcuts. It does not: it holds
     * destinations, so there is nothing in it to promote. Rearranging happens by
     * dragging in the editor, where both rails are visible at once and you can
     * see what a move displaces.
     *
     * Kept because it is the correct operation on this list if a second way to
     * rearrange ever arrives, and tested so that it still is. Do not wire it to
     * the ring.
     *
     * Out-of-range positions are ignored rather than throwing: any caller would
     * be driven by dragging, and a drag that ends somewhere unexpected should do
     * nothing.
     */
    fun reorder(from: Int, to: Int): Profile {
        if (from !in shortcuts.indices || to !in shortcuts.indices || from == to) return this
        val moved = shortcuts.toMutableList()
        moved.add(to, moved.removeAt(from))
        return copy(shortcuts = moved)
    }

    companion object {
        /**
         * How many shortcuts sit on the rail itself.
         *
         * The fifth slot is the Quick Ring, which is not a shortcut: it is the
         * way in to everything the interface has no other room for.
         */
        const val RAIL_SLOTS = 4
    }
}

/** Which side of the screen a rail sits on. */
enum class Side {
    LEFT,
    RIGHT,
    ;

    fun opposite() = if (this == LEFT) RIGHT else LEFT
}

/**
 * Everything the user has chosen.
 *
 * [shortcutSide] decides which rail goes where; the recent-applications rail
 * takes the other side, whenever that arrives.
 */
data class Settings(
    val activeProfile: String,
    val shortcutSide: Side = Side.RIGHT,
    val keepScreenAwake: Boolean = true,
    val haptics: Boolean = true,
    val fadeWhenIdle: Boolean = true,

    /**
     * How long a panel may sit open before the trackpad comes back.
     *
     * From the design: *a panel left open stops being a trackpad*. Settings and
     * the profile menu cover the surface, and somebody who opens one and then
     * reaches for the pad should find the pad rather than the panel they forgot
     * about. Zero means never.
     */
    val returnToPadSeconds: Int = 30,

    /**
     * Whether the audio panel offers levels past 100%.
     *
     * Off by default and deliberately so: it distorts, and at high volume it
     * can damage speakers and ears. Somebody who wants it can say so, and the
     * fader turns amber past the mark so they can see where they are.
     */
    val audioBoost: Boolean = false,

    /** Which page the audio panel shows first, every time. */
    val audioOpensOn: AudioPage = AudioPage.OUTPUT,

    /**
     * Whether the Apps page lists streams that are stopped.
     *
     * Off, it shows what is making sound now. The flag behind it is the sound
     * daemon's own corked state, which is certain about silence and only
     * hopeful about the opposite — an application can hold a stream open
     * writing nothing — so this hides what is obviously idle rather than
     * claiming the rest is definitely audible.
     */
    val audioShowIdle: Boolean = false,
) {
    val applicationsSide: Side get() = shortcutSide.opposite()

    companion object {
        /** What the design offers, and the only values a stored file may hold. */
        val RETURN_CHOICES = listOf(15, 30, 60, 0)

        /** Longer than this and it is not a timeout, it is "never" spelled oddly. */
        const val MAX_RETURN_SECONDS = 3600
    }
}

/**
 * What ships before anyone has chosen anything.
 *
 * Nine each: four on the shortcut rail beside the Quick Ring, and five on the
 * rail opposite. Which shortcuts belong on which profile is a product question
 * rather than a technical one, and nothing else in the code assumes these
 * particular ones.
 *
 * **Nothing destructive ships on a rail.** `alt+f4` was here and is not any
 * more. Shortcuts fire the moment a finger lands, so a default that closes the
 * window under an accidental press is the wrong thing to hand somebody who has
 * not chosen it yet. It is a perfectly good shortcut for a person who
 * deliberately puts it there, which is a different thing.
 */
object DefaultProfiles {
    private fun key(label: String, chord: String) = Slot(label, Action.KeyChord(chord))

    val desktop = Profile(
        name = "Desktop",
        shortcuts = listOf(
            key("Switch", "alt+tab"),
            key("Overview", "super"),
            key("Copy", "ctrl+c"),
            key("Paste", "ctrl+v"),
            key("Cut", "ctrl+x"),
            key("Undo", "ctrl+z"),
            key("Redo", "ctrl+shift+z"),
            key("Esc", "escape"),
            key("Screenshot", "print"),
        ),
    )

    val browser = Profile(
        name = "Browser",
        shortcuts = listOf(
            key("New tab", "ctrl+t"),
            key("Close tab", "ctrl+w"),
            key("Reload", "ctrl+r"),
            key("Find", "ctrl+f"),
            key("Address", "ctrl+l"),
            key("Copy", "ctrl+c"),
            key("Paste", "ctrl+v"),
            key("Full screen", "f11"),
            key("Back", "alt+left"),
        ),
    )

    val media = Profile(
        name = "Media",
        shortcuts = listOf(
            key("Play", "playpause"),
            key("Vol +", "volumeup"),
            key("Vol −", "volumedown"),
            key("Mute", "mute"),
            key("Next", "nexttrack"),
            key("Previous", "previoustrack"),
            key("Full screen", "f11"),
            key("Esc", "escape"),
            key("Mic mute", "micmute"),
        ),
    )

    val all = listOf(desktop, browser, media)

    val settings = Settings(activeProfile = desktop.name)
}
