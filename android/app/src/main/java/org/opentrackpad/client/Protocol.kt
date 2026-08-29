package org.opentrackpad.client

/**
 * The OTP/1 wire format. See docs/PROTOCOL.md.
 *
 * Encoding lives here, apart from the socket and the touch surface, so it can be
 * read and tested on its own.
 */
object Protocol {
    const val VERSION = "OTP/2"

    /** The host rejects anything above this, and so should we. */
    const val MAX_CONTACTS = 10

    /** Pressure is sent on a fixed scale so the host never has to guess. */
    const val MAX_PRESSURE = 1024

    /**
     * The opening line of a session.
     *
     * [widthMicrometres] and [heightMicrometres] are the real physical size of
     * the touch surface. The host cannot guess it — every phone is a different
     * size — and libinput reasons about touchpads in millimetres, so getting it
     * right is what makes pointer speed and gesture distances feel the same on
     * any device.
     */
    fun hello(
        width: Int,
        height: Int,
        widthMicrometres: Int,
        heightMicrometres: Int,
    ): String =
        "HELLO $VERSION $width $height $MAX_CONTACTS " +
            "$widthMicrometres $heightMicrometres"
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
