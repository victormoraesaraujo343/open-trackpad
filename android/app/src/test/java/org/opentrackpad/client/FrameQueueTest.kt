package org.opentrackpad.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameQueueTest {

    private fun move(x: Int) = TouchFrame(
        eventTimeNanos = x.toLong(),
        contacts = listOf(Contact(id = 0, x = x, y = 0, pressure = 600, major = 12)),
        critical = false,
    )

    private fun lift(at: Long) = TouchFrame.empty(at)

    private fun press(id: Int) = TouchFrame(
        eventTimeNanos = id.toLong(),
        contacts = listOf(Contact(id = id, x = 0, y = 0, pressure = 600, major = 12)),
        critical = true,
    )

    private fun drain(queue: FrameQueue): List<TouchFrame> =
        generateSequence { queue.poll() }.toList()

    @Test
    fun `frames come back in the order they went in`() {
        val queue = FrameQueue()
        val frames = listOf(press(0), move(1), move(2), lift(3))
        frames.forEach(queue::add)
        assertEquals(frames, drain(queue))
    }

    @Test
    fun `movement is kept intact below the coalescing threshold`() {
        val queue = FrameQueue(coalesceAbove = 8)
        repeat(8) { queue.add(move(it)) }
        // Fidelity matters more than throughput until the sender falls behind.
        assertEquals(8, queue.size)
    }

    @Test
    fun `movement above the threshold collapses onto the newest sample`() {
        val queue = FrameQueue(coalesceAbove = 4)
        repeat(20) { queue.add(move(it)) }

        assertEquals(4, queue.size)
        val drained = drain(queue)
        assertEquals(
            "the surviving frame must be the latest position, not an old one",
            19,
            drained.last().contacts.single().x,
        )
    }

    @Test
    fun `a lift is never coalesced away`() {
        val queue = FrameQueue(coalesceAbove = 2)
        repeat(30) { queue.add(move(it)) }
        queue.add(lift(99))
        repeat(30) { queue.add(move(100 + it)) }

        val drained = drain(queue)
        assertEquals(
            "the frame that released the finger must survive",
            1,
            drained.count { it.contacts.isEmpty() },
        )
    }

    @Test
    fun `consecutive presses are all delivered`() {
        val queue = FrameQueue(coalesceAbove = 1)
        val presses = listOf(press(0), press(1), press(2), press(3))
        presses.forEach(queue::add)
        assertEquals(presses, drain(queue))
    }

    @Test
    fun `a stalled sender cannot grow the queue without bound`() {
        val queue = FrameQueue(coalesceAbove = 4, maxPending = 16)
        repeat(10_000) { queue.add(move(it)) }
        assertTrue("queue grew to ${queue.size}", queue.size <= 16)
    }

    @Test
    fun `critical frames survive even when the queue is full of them`() {
        val queue = FrameQueue(coalesceAbove = 4, maxPending = 8)
        repeat(100) { queue.add(press(it % 10)) }
        assertEquals(8, queue.size)
        assertTrue(
            "only critical frames were added, so only those can remain",
            drain(queue).all { it.critical },
        )
    }

    @Test
    fun `an empty queue polls to null rather than blocking or throwing`() {
        assertNull(FrameQueue().poll())
    }

    @Test
    fun `clearing discards everything`() {
        val queue = FrameQueue()
        repeat(5) { queue.add(move(it)) }
        queue.clear()
        assertEquals(0, queue.size)
    }
}
