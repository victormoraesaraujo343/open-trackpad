package org.opentrackpad.client

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The screen dims on the trackpad and nowhere else.
 *
 * Victor found this by opening settings and reading: after half a minute the
 * screen went almost black under him. The setting says the trackpad surface
 * fades when nobody is touching it, and the mistake was treating "nobody has
 * touched anything" as the same thing everywhere. On the pad those are the same
 * fact. On a settings screen the person is reading, which produces no touches
 * at all and is the opposite of absent.
 */
class ScreenCareTest {

    @Test
    fun `the trackpad dims when it is what is on screen`() {
        assertEquals(true, ScreenCare.mayDim(running = true, wanted = true, onThePad = true))
    }

    @Test
    fun `nothing else ever dims, however long somebody reads it`() {
        // The defect, stated as the thing that must stay false.
        assertEquals(false, ScreenCare.mayDim(running = true, wanted = true, onThePad = false))
    }

    @Test
    fun `the setting still turns it off on the pad itself`() {
        assertEquals(false, ScreenCare.mayDim(running = true, wanted = false, onThePad = true))
    }

    @Test
    fun `a paused activity schedules nothing`() {
        assertEquals(false, ScreenCare.mayDim(running = false, wanted = true, onThePad = true))
    }
}
