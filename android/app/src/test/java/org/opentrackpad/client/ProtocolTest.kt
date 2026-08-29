package org.opentrackpad.client

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolTest {

    @Test
    fun `the handshake matches the wire format`() {
        assertEquals(
            "HELLO OTP/3 2400 1080 10 156000 69000",
            Protocol.hello(2400, 1080, 156_000, 69_000),
        )
    }

    @Test
    fun `a frame lists every contact in order`() {
        val frame = TouchFrame(
            eventTimeNanos = 9_912_345_678,
            contacts = listOf(
                Contact(id = 0, x = 210, y = 780, pressure = 650, major = 11),
                Contact(id = 1, x = 810, y = 782, pressure = 620, major = 10),
            ),
            critical = false,
        )
        assertEquals(
            "FRAME 42 9912345678 2 0 210 780 650 11 1 810 782 620 10",
            frame.encode(sequence = 42),
        )
    }

    @Test
    fun `a lift is a frame with no contacts`() {
        assertEquals("FRAME 7 1000 0", TouchFrame.empty(1000).encode(sequence = 7))
    }
}
