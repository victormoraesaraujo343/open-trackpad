package org.opentrackpad.client

import java.util.ArrayDeque

/**
 * Holds snapshots waiting to be written to the socket, and decides what to throw
 * away when they arrive faster than they can be sent.
 *
 * The rule that matters: a frame which changed *which fingers exist* may never
 * be dropped or reordered, because the host would then believe a finger is down
 * that has lifted. Plain movement is fair game, since every frame is a complete
 * snapshot and a newer one supersedes an older one entirely.
 *
 * Not thread-safe on its own; callers hold the lock. Kept apart from
 * [HostConnection] so this reasoning can be tested without a socket.
 */
class FrameQueue(
    /**
     * Movement starts being coalesced once this many frames are waiting.
     *
     * Below it every sample is kept, including the historical ones Android
     * batches, because pointer fidelity is the point of the project. Above it
     * the sender is not keeping up and stale positions are worthless.
     */
    private val coalesceAbove: Int = 8,
    /** A hard ceiling, so a wedged socket cannot exhaust memory. */
    private val maxPending: Int = 128,
) {
    private val pending = ArrayDeque<TouchFrame>()

    val size: Int get() = pending.size

    fun add(frame: TouchFrame) {
        val last = pending.peekLast()
        if (!frame.critical && last != null && !last.critical && pending.size >= coalesceAbove) {
            // One stale position replaces another.
            pending.removeLast()
            pending.addLast(frame)
            return
        }

        if (pending.size >= maxPending) {
            dropOldestMovement()
        }
        pending.addLast(frame)
    }

    fun poll(): TouchFrame? = pending.pollFirst()

    fun clear() = pending.clear()

    /**
     * Makes room without losing meaning: the oldest movement goes first, and
     * only if every frame is critical does the front of the queue give way.
     */
    private fun dropOldestMovement() {
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            if (!iterator.next().critical) {
                iterator.remove()
                return
            }
        }
        pending.pollFirst()
    }
}
