package org.opentrackpad.client

/**
 * The two things every domain does with a line: escape a name, and decide
 * whether an update still applies.
 *
 * Both were written for audio and both are needed identically by the shortcut
 * library and the import offer. Shared rather than copied, because copying the
 * generation rules is how two panels end up disagreeing about whether the
 * picture they hold is current.
 */
object Wire {

    /**
     * Percent-encodes a name for the wire.
     *
     * Everything outside printable ASCII, and the space and percent themselves,
     * becomes `%XX` per UTF-8 byte — so a name can never contain a separator or
     * a line ending. This matters in both directions: a name the phone sends
     * back in a rename is as much free text as one the host sends, and the host
     * closes the connection on a malformed line.
     *
     * An empty name is sent as `%20` rather than as nothing, so it cannot
     * vanish and shift every field after it.
     */
    fun encode(name: String): String {
        val out = StringBuilder(name.length)
        for (byte in name.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val printable = value in 0x21..0x7E && value != '%'.code
            if (printable) out.append(value.toChar()) else out.append("%%%02X".format(value))
        }
        return out.ifEmpty { StringBuilder("%20") }.toString()
    }

    /**
     * Undoes [encode].
     *
     * Per byte and only then as UTF-8: one character can be several escapes,
     * and decoding them one at a time turns an accent into two wrong ones.
     * Returns null for an encoding that cannot be right, rather than guessing —
     * a name is the one field a person reads and cannot check.
     */
    fun decode(encoded: String): String? {
        val bytes = ArrayList<Byte>(encoded.length)
        var index = 0
        while (index < encoded.length) {
            val character = encoded[index]
            if (character != '%') {
                // Anything the sender should have escaped and did not is a
                // malformed line, not a name with a space in it.
                if (character.code !in 0x21..0x7E) return null
                bytes.add(character.code.toByte())
                index += 1
                continue
            }
            if (index + 2 >= encoded.length) return null
            val value = encoded.substring(index + 1, index + 3).toIntOrNull(16) ?: return null
            // Escaped is not the same as allowed.
            //
            // The line above already refuses an unescaped control character as
            // malformed, and the reason was never framing — it was that a
            // control character is not part of anybody's name. Escaping one
            // gets it past the framing safely and leaves it in the string, and
            // a window title is the most attacker-influenced text in this
            // protocol: it is whatever a web page decided to call itself. A
            // newline that survived to a label would draw as a second line and
            // push everything under it out of place.
            //
            // Refused here rather than stripped at each place a name is drawn,
            // because there is one decoder and there are many labels.
            if (value in 0x00..0x1F || value == 0x7F) return null
            bytes.add(value.toByte())
            index += 3
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /**
     * Why a request that was legal could not be done.
     *
     * One line shared by every domain — `REFUSED <sequence> <reason>` — so it
     * is parsed here rather than by whichever panel happens to look first.
     * Reading it in the audio module meant `stale` and `full`, which only the
     * import offer can produce, were silently unparseable and the screen that
     * asked never heard the answer.
     *
     * The sequence is the only thing that says which request this answers,
     * because the line does not name a domain.
     */
    data class Refusal(val sequence: Long, val reason: String)

    fun refusal(line: String): Refusal? {
        val parts = line.trim().split(' ')
        if (parts.size != 3 || parts[0] != "REFUSED") return null
        val sequence = parts[1].toLongOrNull() ?: return null
        return Refusal(sequence, parts[2])
    }

    /** What an update naming a generation means for the picture being held. */
    enum class Verdict {
        /** It belongs to the picture we hold. */
        APPLY,

        /** It belongs to a picture we have already replaced. */
        STALE,

        /** It belongs to a picture we never saw: a snapshot went missing. */
        MISSED,
    }

    /**
     * Judges an update against the picture in hand.
     *
     * The whole reason a generation exists. Three situations that would
     * otherwise be indistinguishable: apply it, drop it, or admit that nothing
     * held can be relied on and ask for the lot again. Patching a picture that
     * was superseded by one never seen is guesswork dressed as recovery.
     */
    fun verdict(held: Long, named: Long): Verdict = when {
        named == held -> Verdict.APPLY
        named < held -> Verdict.STALE
        else -> Verdict.MISSED
    }
}
