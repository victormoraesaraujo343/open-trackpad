package org.opentrackpad.client

/**
 * The windows the computer last told us about.
 *
 * Same shape as [LibraryState] and [AudioState]: a snapshot names a generation
 * and a count, that many entries follow, and the picture swaps over when the
 * last one lands. An update from a generation never seen means a snapshot went
 * missing, and the only honest recovery is to ask again — patching would be
 * guesswork dressed as recovery.
 *
 * **The order arrives and is kept.** Nothing here sorts. The list is most
 * recently used first and that is the entire content of this domain; a sort on
 * this side would be a second opinion about the one thing the host is
 * authoritative on, and it would look right while being wrong.
 */
class WindowsState {

    /** Whether this desktop can answer at all. Absent is not empty. */
    var granted: Boolean = false
        private set

    /** Every window, most recently used first. */
    var windows: List<WindowEntry> = emptyList()
        private set

    private var generation = -1L
    private var pending = 0
    private var building = mutableListOf<WindowEntry>()

    /** What the far rail shows: the first four, in the order given. */
    val onTheRail: List<WindowEntry> get() = windows.take(Windows.ON_THE_RAIL)

    fun grant(granted: Boolean) {
        this.granted = granted
        if (!granted) reset()
    }

    fun reset() {
        windows = emptyList()
        generation = -1
        pending = 0
        building = mutableListOf()
    }

    /**
     * Takes one message. Returns true if the picture needs asking for again.
     */
    fun apply(message: WindowMessage): Boolean {
        when (message) {
            is WindowMessage.Snapshot -> {
                generation = message.generation
                pending = message.count
                building = ArrayList(message.count)
                if (message.count == 0) windows = emptyList()
            }

            is WindowMessage.Window -> {
                when (Wire.verdict(generation, message.generation)) {
                    Wire.Verdict.STALE -> return false
                    Wire.Verdict.MISSED -> return true
                    Wire.Verdict.APPLY -> Unit
                }
                if (pending <= 0) return true
                building.add(message.entry)
                pending -= 1
                if (pending == 0) windows = building.toList()
            }

            is WindowMessage.Removed -> {
                when (Wire.verdict(generation, message.generation)) {
                    Wire.Verdict.STALE -> return false
                    Wire.Verdict.MISSED -> return true
                    Wire.Verdict.APPLY -> Unit
                }
                windows = windows.filterNot { it.id == message.id }
            }

            // The host can serve the domain and still have nothing to say about
            // it — a desktop that stopped answering. Empty rather than stale:
            // an old list of windows is worse than none, because every button
            // on it switches to something that may be gone.
            is WindowMessage.Unavailable -> reset()
        }
        return false
    }
}
