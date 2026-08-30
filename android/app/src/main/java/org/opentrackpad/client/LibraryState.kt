package org.opentrackpad.client

/**
 * What the phone believes the computer knows about shortcuts.
 *
 * Two pictures, not one, because the two domains are genuinely different
 * things: [entries] is what may be put on a rail today, [candidates] is what
 * the computer found lying around and is proposing. Keeping them apart is what
 * stops a candidate from being dragged onto a rail before anybody agreed to it.
 *
 * The generation arbitration is [Wire.verdict], shared with the audio panel so
 * the two cannot come to different conclusions about the same question.
 */
class LibraryState {

    var entries: List<LibraryEntry> = emptyList()
        private set

    var candidates: List<Candidate> = emptyList()
        private set

    /** Which offer [candidates] belongs to. `ACCEPT` has to carry this back. */
    var offerGeneration: Long = -1
        private set

    private var entryGeneration = -1L
    private var pendingEntries = 0
    private var pendingCandidates = 0
    private var buildingEntries = mutableListOf<LibraryEntry>()
    private var buildingCandidates = mutableListOf<Candidate>()

    fun reset() {
        entries = emptyList()
        candidates = emptyList()
        entryGeneration = -1
        offerGeneration = -1
        pendingEntries = 0
        pendingCandidates = 0
        buildingEntries = mutableListOf()
        buildingCandidates = mutableListOf()
    }

    /**
     * Takes one message. Returns the domain that needs asking again, or null.
     *
     * The only thing that asks for a refresh is an update from a picture never
     * seen, which means a snapshot went missing and nothing held can be
     * trusted. Patching it would be guesswork dressed as recovery.
     */
    fun apply(message: LibraryMessage): String? {
        when (message) {
            is LibraryMessage.Snapshot -> if (message.domain == Library.SHORTCUTS) {
                entryGeneration = message.generation
                pendingEntries = message.count
                buildingEntries = ArrayList(message.count)
                if (message.count == 0) entries = emptyList()
            } else {
                offerGeneration = message.generation
                pendingCandidates = message.count
                buildingCandidates = ArrayList(message.count)
                if (message.count == 0) candidates = emptyList()
            }

            is LibraryMessage.Shortcut -> {
                when (Wire.verdict(entryGeneration, message.generation)) {
                    Wire.Verdict.STALE -> return null
                    Wire.Verdict.MISSED -> return Library.SHORTCUTS
                    Wire.Verdict.APPLY -> Unit
                }
                if (message.changed) {
                    replace(message.entry)
                } else {
                    if (pendingEntries <= 0) return Library.SHORTCUTS
                    buildingEntries.add(message.entry)
                    pendingEntries -= 1
                    if (pendingEntries == 0) entries = buildingEntries.toList()
                }
            }

            is LibraryMessage.Offer -> {
                when (Wire.verdict(offerGeneration, message.generation)) {
                    Wire.Verdict.STALE -> return null
                    Wire.Verdict.MISSED -> return Library.IMPORT
                    Wire.Verdict.APPLY -> Unit
                }
                if (pendingCandidates <= 0) return Library.IMPORT
                buildingCandidates.add(message.candidate)
                pendingCandidates -= 1
                if (pendingCandidates == 0) candidates = buildingCandidates.toList()
            }

            is LibraryMessage.Removed -> {
                val held =
                    if (message.domain == Library.SHORTCUTS) entryGeneration else offerGeneration
                when (Wire.verdict(held, message.generation)) {
                    Wire.Verdict.STALE -> return null
                    Wire.Verdict.MISSED -> return message.domain
                    Wire.Verdict.APPLY -> Unit
                }
                if (message.domain == Library.SHORTCUTS) {
                    entries = entries.filterNot { it.id == message.id }
                } else {
                    candidates = candidates.filterNot { it.id == message.id }
                }
            }

            is LibraryMessage.Unavailable -> if (message.domain == Library.SHORTCUTS) {
                entries = emptyList()
                entryGeneration = -1
            } else {
                candidates = emptyList()
                offerGeneration = -1
            }
        }
        return null
    }

    private fun replace(entry: LibraryEntry) {
        val at = entries.indexOfFirst { it.id == entry.id }
        entries = if (at < 0) entries + entry else entries.toMutableList().also { it[at] = entry }
    }

    /** True between a snapshot and its last entry. */
    val settling: Boolean get() = pendingEntries > 0 || pendingCandidates > 0

    /**
     * The offer, gathered by what each shortcut controls.
     *
     * In the order the groups first appear rather than alphabetically or by the
     * enum: the host sends them grouped already, and reordering would put
     * "Accessibility" above "Windows" on a machine where forty-five of the
     * seventy-five are windows. Groups that do not appear are simply absent —
     * this machine has no screenshot shortcuts at all, and reserving a row for
     * one would be drawing an empty shelf.
     */
    fun offerByGroup(): List<Pair<ShortcutGroup?, List<Candidate>>> =
        candidates.groupBy { it.group }.toList()

    /** The library, gathered the same way, with everything recorded under its own head. */
    fun libraryByGroup(): List<Pair<ShortcutGroup?, List<LibraryEntry>>> =
        entries.groupBy { if (it.origin == Origin.RECORDED) null else it.group }.toList()
}
