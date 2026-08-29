package org.opentrackpad.client

/**
 * The audio domain: what the host tells the phone about sound, and what the
 * phone may ask it to change.
 *
 * The wire format is `docs/PROTOCOL.md`, and this file is only its Kotlin
 * shape. Nothing here talks to a socket or draws anything, so all of it can be
 * tested on a desk.
 */

/**
 * The audio panel's pages.
 *
 * Four, which with the way out is exactly the five slots a rail always has.
 * That is not a coincidence: while a panel is open the rail opposite the Quick
 * Ring becomes its pages, so the way out of the app never moves.
 */
enum class AudioPage(val wire: String) {
    OUTPUT("output"),
    INPUT("input"),
    APPS("apps"),
    SETTINGS("settings"),
    ;

    /** Which entities this page shows, or null for the page about the panel. */
    val kind: AudioKind?
        get() = when (this) {
            OUTPUT -> AudioKind.OUTPUT
            INPUT -> AudioKind.INPUT
            APPS -> AudioKind.STREAM
            SETTINGS -> null
        }

    companion object {
        fun of(wire: String): AudioPage? = entries.firstOrNull { it.wire == wire }

        /** The pages a person may choose to open on: the ones showing sound. */
        val openable = listOf(OUTPUT, INPUT, APPS)
    }
}

/** What an entity is. The three are numbered independently by the sound daemon. */
enum class AudioKind(val wire: String) {
    OUTPUT("output"),
    INPUT("input"),
    STREAM("stream"),
    ;

    companion object {
        fun of(wire: String): AudioKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One thing that makes or takes sound.
 *
 * [volume] is per mille, because a fader the height of a phone screen has room
 * for far more than a hundred steps. A thousand is what reads as 100%.
 *
 * Not capped on the way in. A device left at 130% by another tool arrives as
 * 1300, and telling the phone the machine is quieter than it is actually
 * playing would be the wrong half of that decision. The host stops at 1500
 * because the client has no way to draw more.
 */
data class AudioEntity(
    val kind: AudioKind,
    val id: Int,
    val volume: Int,
    val muted: Boolean,
    val isDefault: Boolean,

    /** For a stream, the output it plays through. Null for a device. */
    val target: Int?,
    val name: String,
) {
    /** What a person would call this level. */
    val percent: Int get() = Math.round(volume / 10f)

    /** Whether this is past the reference level, and so drawn in amber. */
    val boosted: Boolean get() = volume > Audio.REFERENCE

    /** How far up its own scale the fader sits, from 0 to 1. */
    val fraction: Float get() = (volume.toFloat() / Audio.CEILING).coerceIn(0f, 1f)

    /** Which entity this is, across all three kinds. */
    val key: Pair<AudioKind, Int> get() = kind to id
}

/** Why the panel has nothing to show. */
enum class AudioOutage(val wire: String) {
    /** The host has no way to talk to a sound daemon. */
    NO_TOOL("no-tool"),

    /** There is no sound daemon running. */
    NO_DAEMON("no-daemon"),

    /** It was working and stopped. */
    LOST("lost"),
    ;

    companion object {
        fun of(wire: String): AudioOutage? = entries.firstOrNull { it.wire == wire }
    }
}

/** Why a request that was legal could not be done. */
enum class AudioRefusal(val wire: String) {
    UNKNOWN_ID("unknown-id"),
    WRONG_KIND("wrong-kind"),
    UNAVAILABLE("unavailable"),
    BACKEND_FAILED("backend-failed"),
    TOO_FAST("too-fast"),
    ;

    companion object {
        fun of(wire: String): AudioRefusal? = entries.firstOrNull { it.wire == wire }
    }
}

/** One thing the host said. */
sealed interface AudioMessage {
    /** A complete picture is coming: exactly [count] entries of this [generation]. */
    data class Snapshot(val generation: Long, val count: Int) : AudioMessage

    /** One entry of the picture [Snapshot] opened. */
    data class Entry(val generation: Long, val entity: AudioEntity) : AudioMessage

    /** One entity has appeared or is no longer what it was. */
    data class Changed(val generation: Long, val entity: AudioEntity) : AudioMessage

    /** One entity is gone. */
    data class Removed(val generation: Long, val kind: AudioKind, val id: Int) : AudioMessage

    /** The domain cannot be served. */
    data class Unavailable(val reason: AudioOutage) : AudioMessage

    /** A request of ours was refused. [sequence] is our own numbering. */
    data class Refused(val sequence: Long, val reason: AudioRefusal) : AudioMessage
}

object Audio {

    /** What this client asks for in the handshake. */
    const val CAPABILITY = "audio"

    /** The domain these messages belong to. */
    const val DOMAIN = "audio"

    /**
     * The level that reads as 100%.
     *
     * Two numbers rather than one, and they are deliberately not equal. They
     * were a single constant while they happened to be, which is how a ceiling
     * silently becomes a reference and caps everything back at 100%.
     */
    const val REFERENCE = 1000

    /** The highest level that may be asked for, and the top of the drawn scale. */
    const val CEILING = 1500

    /**
     * Reads one line from the host, or null if it is not ours to read.
     *
     * Unknown verbs and unknown domains are ignored rather than treated as
     * errors. A later host will send messages this version has never heard of,
     * and a client that fell over at the first one could never be older than
     * its host.
     */
    fun parse(line: String): AudioMessage? {
        val parts = line.trim().split(' ')
        if (parts.size < 2) return null
        return when (parts[0]) {
            "SNAPSHOT" -> parseSnapshot(parts)
            "ENTRY" -> parseEntity(parts)?.let { (generation, entity) ->
                AudioMessage.Entry(generation, entity)
            }

            "CHANGED" -> parseEntity(parts)?.let { (generation, entity) ->
                AudioMessage.Changed(generation, entity)
            }

            "REMOVED" -> parseRemoved(parts)
            "UNAVAILABLE" -> parseUnavailable(parts)
            "REFUSED" -> parseRefused(parts)
            else -> null
        }
    }

    private fun parseSnapshot(parts: List<String>): AudioMessage? {
        if (parts.size != 4 || parts[1] != DOMAIN) return null
        val generation = parts[2].toLongOrNull() ?: return null
        val count = parts[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return AudioMessage.Snapshot(generation, count)
    }

    /** `<verb> audio <generation> <kind> <id> <volume> <muted> <default> <target> <name>` */
    private fun parseEntity(parts: List<String>): Pair<Long, AudioEntity>? {
        if (parts.size != 10 || parts[1] != DOMAIN) return null
        val generation = parts[2].toLongOrNull() ?: return null
        val kind = AudioKind.of(parts[3]) ?: return null
        val id = parts[4].toIntOrNull() ?: return null
        val volume = parts[5].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val muted = parts[6].asFlag() ?: return null
        val isDefault = parts[7].asFlag() ?: return null
        // A device has no target and says so with a dash; a stream names the
        // output it plays through. A device claiming a target, or a stream
        // claiming to be the default, is a host that is confused about what it
        // is describing, so neither is quietly accepted.
        val target = when {
            parts[8] == "-" -> null
            else -> parts[8].toIntOrNull() ?: return null
        }
        if (kind == AudioKind.STREAM && isDefault) return null
        if (kind != AudioKind.STREAM && target != null) return null
        val name = decode(parts[9]) ?: return null
        return generation to AudioEntity(kind, id, volume, muted, isDefault, target, name)
    }

    private fun parseRemoved(parts: List<String>): AudioMessage? {
        if (parts.size != 5 || parts[1] != DOMAIN) return null
        val generation = parts[2].toLongOrNull() ?: return null
        val kind = AudioKind.of(parts[3]) ?: return null
        val id = parts[4].toIntOrNull() ?: return null
        return AudioMessage.Removed(generation, kind, id)
    }

    private fun parseUnavailable(parts: List<String>): AudioMessage? {
        if (parts.size != 3 || parts[1] != DOMAIN) return null
        return AudioOutage.of(parts[2])?.let(AudioMessage::Unavailable)
    }

    private fun parseRefused(parts: List<String>): AudioMessage? {
        if (parts.size != 3) return null
        val sequence = parts[1].toLongOrNull() ?: return null
        return AudioRefusal.of(parts[2])?.let { AudioMessage.Refused(sequence, it) }
    }

    private fun String.asFlag(): Boolean? = when (this) {
        "0" -> false
        "1" -> true
        else -> null
    }

    /**
     * Undoes the host's percent-encoding.
     *
     * Names are free text the host does not author — a window title comes from
     * whatever page a browser has open — so everything outside printable ASCII,
     * and the space and percent themselves, arrive as `%XX` per UTF-8 byte.
     * Decoding is per byte and only then as UTF-8, because one character can be
     * several escapes and decoding them one at a time would produce mojibake.
     *
     * Returns null for an encoding that cannot be right, rather than guessing:
     * a name is the one field a person reads, and a wrong one is worse than a
     * refused line.
     */
    fun decode(encoded: String): String? {
        val bytes = ArrayList<Byte>(encoded.length)
        var index = 0
        while (index < encoded.length) {
            val character = encoded[index]
            if (character != '%') {
                // Anything the host should have escaped and did not is a
                // malformed line, not a name with a space in it.
                if (character.code !in 0x21..0x7E) return null
                bytes.add(character.code.toByte())
                index += 1
                continue
            }
            if (index + 2 >= encoded.length) return null
            val value = encoded.substring(index + 1, index + 3).toIntOrNull(16) ?: return null
            bytes.add(value.toByte())
            index += 3
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    // -- what the phone asks for ---------------------------------------------

    /** `REQUEST <sequence> audio VOLUME <kind> <id> <level>` */
    fun volume(sequence: Long, kind: AudioKind, id: Int, level: Int): String =
        "REQUEST $sequence $DOMAIN VOLUME ${kind.wire} $id ${level.coerceIn(0, CEILING)}"

    /** `REQUEST <sequence> audio MUTE <kind> <id> <0|1>` */
    fun mute(sequence: Long, kind: AudioKind, id: Int, muted: Boolean): String =
        "REQUEST $sequence $DOMAIN MUTE ${kind.wire} $id ${if (muted) 1 else 0}"

    /**
     * `REQUEST <sequence> audio DEFAULT <kind> <id>`
     *
     * Only a device can become the default. Asking it of a stream cannot mean
     * anything and is a protocol error, so it is refused here rather than sent.
     */
    fun makeDefault(sequence: Long, kind: AudioKind, id: Int): String? {
        if (kind == AudioKind.STREAM) return null
        return "REQUEST $sequence $DOMAIN DEFAULT ${kind.wire} $id"
    }

    /** `REQUEST <sequence> audio REFRESH` — the whole picture again, not a difference. */
    fun refresh(sequence: Long): String = "REQUEST $sequence $DOMAIN REFRESH"
}
