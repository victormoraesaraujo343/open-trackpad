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
 *
 * It was a closed set of **one** for longer than it should have been, and that
 * is worth leaving written down. The protocol had `BUTTON` and `RECORD` from
 * the day version 4 landed, the host executed both, and three drawn screens
 * rested on them — right click, the recorder, and the waiting screen that
 * follows it. None of them could be built, because the client had no way to say
 * anything but a chord, and nobody noticed that a sealed interface with a single
 * member was a question rather than a design. A missing verb here does not look
 * like a missing feature; it looks like nothing at all.
 */
sealed interface Action {
    fun encode(sequence: Long): String

    /**
     * Whether version 3 has never heard of this.
     *
     * The client drops one version down when a host answers in an older
     * language, and a version 3 host does not merely ignore what came after it
     * — it treats the message as proof the client is not what it claims and
     * **closes the connection**. So this is not politeness either; sending a
     * button to an old host takes the trackpad away.
     *
     * Chords are the exception: version 3 carried those already.
     */
    val afterVersionThree: Boolean

    /**
     * Presses a chord and lets it go: `ctrl+c`, `alt+tab`, `super`.
     *
     * The chord is passed through as written and validated by the host, which
     * owns the list of key names. Duplicating that list here would give two
     * places to keep in step and no extra safety — a wrong name is refused
     * either way, and refused where it matters.
     */
    data class KeyChord(val chord: String) : Action {
        override val afterVersionThree get() = false
        override fun encode(sequence: Long) = "ACTION $sequence KEY $chord"
    }

    /**
     * Clicks a mouse button once: pressed and released, with nothing in between.
     *
     * There is no held button and no drag, by the host's decision and for a good
     * reason — a drag needs the pointer moving while the button is down, which
     * is touch's job on the other path, and a stuck mouse button is worse than a
     * stuck modifier because it makes the desktop unusable *and* unfixable:
     * nothing can be clicked to escape it.
     *
     * These exist because tap-to-click is a setting. Somebody who turns it off
     * has told their system that taps should not click, and a touchpad honouring
     * that is correct rather than broken — but until there was a button they had
     * no click at all and nothing said so.
     */
    data class Click(val button: Button) : Action {
        override val afterVersionThree get() = true
        override fun encode(sequence: Long) = "ACTION $sequence BUTTON ${button.wire}"
    }

    /**
     * The three names the host will take, and no way to say a fourth.
     *
     * Not gated the way chords are, and the asymmetry is chosen: a chord can be
     * any of a hundred and thirty key names combined into anything a desktop can
     * do, so it is limited to what somebody recorded. Three fixed buttons can do
     * nothing a mouse cannot already do, so the closed set is the whole
     * protection.
     */
    enum class Button(val wire: String) {
        LEFT("left"),
        RIGHT("right"),
        MIDDLE("middle"),
    }

    /**
     * Asks the computer to open the shortcut recorder.
     *
     * Carries nothing — not a name, not a chord, not a hint. The host rejects
     * `RECORD` with any argument at all, which is how a verb that spawns a
     * program stays a verb that spawns exactly one program.
     *
     * What comes back is not a reply to this. The recorder is a window on the
     * computer; the phone finds out it worked when the shortcuts domain sends a
     * new entry, which may be a minute later or never if the person changes
     * their mind. So nothing here waits for anything.
     */
    data object Record : Action {
        override val afterVersionThree get() = true
        override fun encode(sequence: Long) = "ACTION $sequence RECORD"
    }
}
