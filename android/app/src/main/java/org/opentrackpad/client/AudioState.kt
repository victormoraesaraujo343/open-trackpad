package org.opentrackpad.client

/**
 * What the phone currently believes about sound.
 *
 * Assembles the host's messages into one picture and says when that picture
 * cannot be trusted. No socket, no views: this is the reasoning, so it can be
 * tested against a script rather than a sound card.
 *
 * The generation is the whole trick. Every snapshot raises it, and every update
 * carries the generation of the picture it belongs to, so three situations that
 * would otherwise look identical are told apart:
 *
 * - an update for the picture we hold — apply it
 * - an update for a picture we have already replaced — drop it, it is stale
 * - an update for a picture we never saw — we have missed a snapshot, and the
 *   only honest recovery is to ask for the whole thing again
 */
class AudioState {

    /** Whether the host granted the domain at all. */
    var granted: Boolean = false
        private set

    /** Why there is nothing to show, if there is nothing to show. */
    var outage: AudioOutage? = null
        private set

    /** The picture, in the order the host sent it. */
    var entities: List<AudioEntity> = emptyList()
        private set

    /** Which picture [entities] belongs to. */
    var generation: Long = -1
        private set

    /** True between a `SNAPSHOT` and its last `ENTRY`. */
    val settling: Boolean get() = pending > 0

    private var pending = 0
    private var building = mutableListOf<AudioEntity>()

    /** The host agreed to serve this domain, or did not. */
    fun grant(granted: Boolean) {
        this.granted = granted
        if (!granted) reset()
    }

    /** Everything is forgotten when a session ends: none of it survives a socket. */
    fun reset() {
        outage = null
        entities = emptyList()
        generation = -1
        pending = 0
        building = mutableListOf()
    }

    /**
     * Takes one message.
     *
     * Returns true when the caller should ask for a fresh snapshot: the only
     * case is an update from a picture we never saw, which means a `SNAPSHOT`
     * went missing and nothing we hold can be relied on.
     */
    fun apply(message: AudioMessage): Boolean {
        when (message) {
            is AudioMessage.Snapshot -> {
                // A snapshot replaces everything, including a half-built one.
                // An interrupted picture is not worth keeping half of.
                outage = null
                generation = message.generation
                pending = message.count
                building = ArrayList(message.count)
                if (pending == 0) entities = emptyList()
            }

            is AudioMessage.Entry -> {
                // Entries only ever belong to the snapshot being built. One
                // from anywhere else is noise, and one arriving when nothing is
                // being built means the SNAPSHOT that opened it never came.
                if (message.generation != generation) return message.generation > generation
                if (pending <= 0) return true
                building.add(message.entity)
                pending -= 1
                if (pending == 0) entities = building.toList()
            }

            is AudioMessage.Changed -> {
                if (message.generation < generation) return false
                if (message.generation > generation) return true
                replace(message.entity)
            }

            is AudioMessage.Removed -> {
                if (message.generation < generation) return false
                if (message.generation > generation) return true
                entities = entities.filterNot {
                    it.kind == message.kind && it.id == message.id
                }
            }

            is AudioMessage.Unavailable -> {
                // The panel empties rather than freezing on values that are no
                // longer true. The host keeps looking and will send a fresh
                // snapshot if sound comes back.
                outage = message.reason
                entities = emptyList()
                generation = -1
                pending = 0
            }

            is AudioMessage.Refused -> Unit
        }
        return false
    }

    /**
     * Puts one entity in, in place if it is already known.
     *
     * A `CHANGED` for something absent is an appearance rather than an error,
     * which the protocol says in as many words: it is "one entity that has
     * appeared or is no longer what it was".
     */
    private fun replace(entity: AudioEntity) {
        val at = entities.indexOfFirst { it.key == entity.key }
        entities = if (at < 0) entities + entity else entities.toMutableList().also {
            it[at] = entity
        }
    }

    /** Everything of one kind, in the order the host gave it. */
    fun of(kind: AudioKind): List<AudioEntity> = entities.filter { it.kind == kind }

    /** The device the machine is using, if it has said. */
    fun defaultOf(kind: AudioKind): AudioEntity? = of(kind).firstOrNull { it.isDefault }
}
