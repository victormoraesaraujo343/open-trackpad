package org.opentrackpad.client

/**
 * The OTP/4 wire format. See docs/PROTOCOL.md.
 *
 * Encoding lives here, apart from the socket and the touch surface, so it can be
 * read and tested on its own.
 */
object Protocol {
    /**
     * Version 4 opened a channel in the other direction: the handshake carries
     * what the client wants to be told about, and the host answers `WELCOME`.
     *
     * The bump could not be avoided. Older hosts treat an unexpected field as
     * fatal and close the connection without a word, which is exactly how this
     * client learns it is talking to one.
     */
    const val VERSION = "OTP/4"

    /**
     * What to say to a computer that has not been updated.
     *
     * A mismatch closes the connection immediately with no reply, so meeting an
     * older host is learned at once rather than waited out. The protocol's
     * instruction is to reconnect one version down, which is version 3: the
     * trackpad and shortcuts still work, and nothing answers back. See
     * [welcomeIsOurs] for why the reply is only expected on version 4.
     */
    const val FALLBACK_VERSION = "OTP/3"

    /** What this client asks to be told about. Nothing yet: no panel is built. */
    const val NO_CAPABILITIES = "-"

    /** The host rejects anything above this, and so should we. */
    const val MAX_CONTACTS = 10

    /** Pressure is sent on a fixed scale so the host never has to guess. */
    const val MAX_PRESSURE = 1024

    /** No line may exceed this, in either direction. */
    const val MAX_LINE_BYTES = 4096

    /**
     * The opening line of a session.
     *
     * [widthMicrometres] and [heightMicrometres] are the real physical size of
     * the touch surface. The host cannot guess it — every phone is a different
     * size — and libinput reasons about touchpads in millimetres, so getting it
     * right is what makes pointer speed and gesture distances feel the same on
     * any device. It is the size of the *pad*, not of the screen: the rails are
     * not part of the touchpad and their width must not be counted in.
     *
     * [capabilities] is left out entirely for [FALLBACK_VERSION], because the
     * field does not exist before version 4 and an older host treats a trailing
     * one as fatal.
     */
    fun hello(
        width: Int,
        height: Int,
        widthMicrometres: Int,
        heightMicrometres: Int,
        version: String = VERSION,
        capabilities: String? = NO_CAPABILITIES,
    ): String = buildString {
        append("HELLO $version $width $height $MAX_CONTACTS ")
        append("$widthMicrometres $heightMicrometres")
        if (capabilities != null) append(" $capabilities")
    }

    /**
     * Whether a line is this host agreeing to talk to us.
     *
     * The reply is checked rather than assumed because it is the only thing
     * that distinguishes three situations a socket cannot tell apart: a host
     * that is ours, a host that is a different program listening on the same
     * port, and a host that is busy with another client and has not read our
     * handshake at all. The last one matters — the light version of this app
     * can be holding the session — and it shows up as no reply rather than a
     * wrong one.
     */
    fun welcomeIsOurs(line: String?): Boolean {
        val parts = line?.trim()?.split(' ') ?: return false
        return parts.size >= 2 && parts[0] == "WELCOME" && parts[1] == VERSION
    }

    /**
     * What the host actually agreed to serve.
     *
     * The answer is what was asked for kept to what this machine can do, so it
     * may be smaller than the request and never larger. A host with no sound
     * daemon answers `-` and the phone draws no panel — absent rather than
     * broken, which is the whole reason the reply carries a list at all.
     */
    fun welcomeCapabilities(line: String?): Set<String> {
        if (!welcomeIsOurs(line)) return emptySet()
        val granted = line!!.trim().split(' ').getOrNull(2) ?: return emptySet()
        if (granted == NO_CAPABILITIES) return emptySet()
        return granted.split(',').filter { it.isNotBlank() }.toSet()
    }

    /** The handshake's capability field, as the host expects to read it. */
    fun capabilities(wanted: Collection<String>): String =
        if (wanted.isEmpty()) NO_CAPABILITIES else wanted.joinToString(",")
}

/**
 * One finger at one instant.
 *
 * [id] is the Android pointer ID, not the pointer index: indices shuffle as
 * fingers come and go, IDs do not.
 */
data class Contact(
    val id: Int,
    val x: Int,
    val y: Int,
    val pressure: Int,
    val major: Int,
)

/**
 * A complete snapshot of every finger on the surface.
 *
 * Snapshots rather than deltas: a frame that never arrives costs nothing,
 * because the next one describes the whole state again.
 *
 * [critical] marks a frame that changed which fingers exist. Those must reach
 * the host in order and may never be dropped; plain movement may be coalesced
 * away under backpressure without breaking anything.
 */
data class TouchFrame(
    val eventTimeNanos: Long,
    val contacts: List<Contact>,
    val critical: Boolean,
) {
    fun encode(sequence: Long): String = buildString {
        append("FRAME ")
        append(sequence)
        append(' ')
        append(eventTimeNanos)
        append(' ')
        append(contacts.size)
        for (contact in contacts) {
            append(' ')
            append(contact.id)
            append(' ')
            append(contact.x)
            append(' ')
            append(contact.y)
            append(' ')
            append(contact.pressure)
            append(' ')
            append(contact.major)
        }
    }

    companion object {
        /** The frame that says "nothing is touching", used to end cleanly. */
        fun empty(eventTimeNanos: Long) =
            TouchFrame(eventTimeNanos, emptyList(), critical = true)
    }
}
