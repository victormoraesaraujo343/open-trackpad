package org.opentrackpad.client

/**
 * How a rail slot is drawn. The names are the design's own states.
 *
 * Lime means "this one, now" and nothing else, so [ACTIVE] and [PRIMARY] are
 * the only styles that use it: the page you are on, the key you are holding,
 * the way out of the app.
 */
enum class SlotStyle {
    /** An ordinary button. */
    PLAIN,

    /** Latched, selected, or the page currently open. Lime outline. */
    ACTIVE,

    /** Filled lime. The Quick Ring, and only it, on the main screen. */
    PRIMARY,

    /** Present but not usable — the session is down. */
    DEAD,
}

/** What pressing a slot does. */
sealed interface SlotPress {

    /** Send a shortcut to the computer. */
    data class Send(val action: Action) : SlotPress

    /** Open the Quick Ring, the way in to everything below. */
    data object QuickRing : SlotPress

    /** Which set of shortcuts the rails are showing. */
    data object Profiles : SlotPress

    /** The settings screen. */
    data object Settings : SlotPress

    /** The screen for choosing which shortcuts sit where. */
    data object Editor : SlotPress

    /** The audio panel, on one of its pages. */
    data class Audio(val page: AudioPage) : SlotPress

    /** Out of whatever panel is open, back to the trackpad. */
    data object Close : SlotPress

    /** Reserved space that answers to nothing. */
    data object None : SlotPress
}

/**
 * One position on a rail: what it says, what it looks like, what it does.
 *
 * A slot with no content is `null` in the list rather than a variant of this,
 * because an empty slot has no label, icon or behaviour to describe — it is a
 * hole kept open on purpose.
 */
data class RailSlot(
    val label: String,
    val icon: String,
    val press: SlotPress,
    val style: SlotStyle = SlotStyle.PLAIN,
)

/**
 * Turns what the user has chosen into the two rails on screen.
 *
 * The rule this encodes, from the design: **a rail is always five slots**. The
 * first four change with context; the fifth always means "everything else about
 * this". Fewer items leave a slot empty rather than letting the others grow,
 * because the surface is used without looking and a button that moves is a
 * button that gets pressed by mistake.
 */
object Rails {

    /** Every rail, everywhere in the app, has exactly this many slots. */
    const val SLOTS = 5

    /**
     * The rail with the Quick Ring on it: four shortcuts and the way out.
     *
     * Which side it takes is the handedness setting; the rail itself does not
     * know or care.
     */
    fun shortcuts(profile: Profile): List<RailSlot?> = rail(
        first = profile.rail.map { it?.let(::slotFor) },
        last = RailSlot(
            label = "Quick",
            icon = RailIcons.path("quick"),
            press = SlotPress.QuickRing,
            style = SlotStyle.PRIMARY,
        ),
    )

    /**
     * The rail opposite the Quick Ring: five more shortcuts.
     *
     * All five, with nothing held back. When the recently-used applications
     * arrive they replace this rail whole — four windows and an "All" in slot
     * five — rather than moving into a space kept for them, so there is nothing
     * to reserve and no reason to leave a hole in the meantime.
     *
     * It has no fifth-slot button of its own because it has no "everything
     * else" to lead to yet. The Quick Ring on the other rail is the way in to
     * anything the interface has no room for, and there is only one of those.
     */
    fun overflow(profile: Profile): List<RailSlot?> =
        List(SLOTS) { index -> profile.ring.getOrNull(index)?.let(::slotFor) }

    /**
     * The rail a panel takes over while it is open: the way out, then its pages.
     *
     * Slot one is Close and slot five is the panel's own settings, which is the
     * same meaning those two slots carry everywhere — out, and everything else
     * about this. That the audio panel has exactly four pages and a way out is
     * what lets it fit a rail without bending the rule.
     */
    fun audioPages(current: AudioPage): List<RailSlot?> = listOf(
        RailSlot("Close", RailIcons.path("back"), SlotPress.Close),
        page(AudioPage.OUTPUT, "Output", "vol", current),
        page(AudioPage.INPUT, "Input", "mic", current),
        page(AudioPage.APPS, "Apps", "app", current),
        page(AudioPage.SETTINGS, "Settings", "gear", current),
    )

    private fun page(page: AudioPage, label: String, icon: String, current: AudioPage) = RailSlot(
        label = label,
        icon = RailIcons.path(icon),
        press = SlotPress.Audio(page),
        // Lime means "this one, now", which on a rail of pages is the page you
        // are looking at and nothing else.
        style = if (page == current) SlotStyle.ACTIVE else SlotStyle.PLAIN,
    )

    private fun slotFor(slot: Slot) = RailSlot(
        label = slot.label,
        icon = RailIcons.path(RailIcons.forAction(slot.action)),
        press = SlotPress.Send(slot.action),
    )

    /**
     * Builds the five slots: [first] fills the top four, [last] is always the
     * bottom one.
     *
     * The fifth slot is placed rather than appended, and that is the whole
     * point. Appending would let a profile with two shortcuts slide the Quick
     * Ring up to the third position — the button that means "everything else"
     * would live somewhere different on every profile, which is exactly what a
     * surface used without looking cannot have. Anything past the fourth
     * shortcut is refused the room instead.
     */
    private fun rail(first: List<RailSlot?>, last: RailSlot?): List<RailSlot?> =
        List(SLOTS) { index -> if (index == SLOTS - 1) last else first.getOrNull(index) }
}

/**
 * The same rail with everything that needs the computer greyed out.
 *
 * Drawn, not hidden: the app is still here, it just cannot do some of this
 * until the session comes back, and a rail that vanished and returned would
 * move every button on the way. A dead slot refuses the press itself rather
 * than trusting the caller to check.
 *
 * **Only what actually needs the host.** A shortcut has nowhere to go without
 * a session and is greyed; the Quick Ring is the way in to settings, profiles
 * and the app itself, and greying that out locks somebody out of the whole
 * interface for as long as the cable is unplugged — including out of the screen
 * that would tell them what is wrong.
 *
 * This deadened the ring as well until Victor found the fifth slot doing
 * nothing. That turned out to be a stale build, but this would have produced
 * exactly the same symptom the moment the session was anything short of
 * connected, and the wrong explanation would have been sitting ready.
 */
fun List<RailSlot?>.deadened(): List<RailSlot?> = map { slot ->
    when (slot?.press) {
        is SlotPress.Send -> slot.copy(press = SlotPress.None, style = SlotStyle.DEAD)
        else -> slot
    }
}
