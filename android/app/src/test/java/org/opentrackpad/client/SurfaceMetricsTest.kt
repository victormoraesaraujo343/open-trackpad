package org.opentrackpad.client

import android.util.DisplayMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceMetricsTest {

    private fun metrics(xdpi: Float, ydpi: Float, densityDpi: Int = 420) =
        DisplayMetrics().apply {
            this.xdpi = xdpi
            this.ydpi = ydpi
            this.densityDpi = densityDpi
        }

    @Test
    fun `a real phone measures close to its actual size`() {
        // The figures a Nothing Phone reports: 2412x1080 at about 395 dpi,
        // which is 155 mm by 69 mm in landscape.
        val surface = SurfaceMetrics.measure(metrics(397.565f, 392.723f), 2412, 1080)

        assertEquals(155, surface.widthMicrometres / 1000)
        assertEquals(69, surface.heightMicrometres / 1000)
    }

    @Test
    fun `pixels are carried through untouched`() {
        val surface = SurfaceMetrics.measure(metrics(400f, 400f), 2412, 1080)
        assertEquals(2412, surface.widthPixels)
        assertEquals(1080, surface.heightPixels)
    }

    @Test
    fun `a device reporting no dpi falls back to its density bucket`() {
        val surface = SurfaceMetrics.measure(metrics(0f, 0f, densityDpi = 420), 2412, 1080)
        // 2412 px at 420 dpi is about 146 mm: coarse, but the right order.
        assertEquals(145, surface.widthMicrometres / 1000)
    }

    @Test
    fun `one broken axis does not poison the measurement`() {
        val surface = SurfaceMetrics.measure(metrics(0f, 400f), 2412, 1080)
        assertEquals(153, surface.widthMicrometres / 1000)
    }

    @Test
    fun `absurd dpi never produces an absurd surface`() {
        for (dpi in listOf(0f, 1f, 1_000_000f, Float.NaN)) {
            val surface = SurfaceMetrics.measure(metrics(dpi, dpi, densityDpi = 0), 2412, 1080)
            val millimetres = surface.widthMicrometres / 1000
            assertTrue("$dpi dpi gave $millimetres mm", millimetres in 50..400)
        }
    }

    @Test
    fun `a surface is never zero sized`() {
        val surface = SurfaceMetrics.measure(metrics(400f, 400f), 1, 1)
        assertTrue(surface.widthMicrometres >= 1)
        assertTrue(surface.heightMicrometres >= 1)
    }
}
