package app.rawboy.color

import app.rawboy.develop.DevelopParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class PrimariesTrackTest {
    private val defaults = DevelopParams()

    private fun saturation(c: FloatArray) = max(c[0], max(c[1], c[2])) - min(c[0], min(c[1], c[2]))

    @Test
    fun untouchedRedIsTheSample() {
        // Linear sRGB (1, 0.18, 0.18), encoded.
        val mid = PrimariesTrack.colors(defaults, PrimariesSlider.RED_HUE, -15f..15f, steps = 3)[1]
        assertEquals(1f, mid[0], 1e-3f)
        assertEquals(0.4613f, mid[1], 1e-3f)
        assertEquals(0.4613f, mid[2], 1e-3f)
    }

    @Test
    fun redHueTurnsTowardsYellow() {
        val c = PrimariesTrack.colors(defaults, PrimariesSlider.RED_HUE, -15f..15f)
        assertTrue(c.last()[1] > c.first()[1] + 0.1f)
        assertTrue(c.first()[2] > c.last()[2])
    }

    @Test
    fun purityRaisesSaturation() {
        for (slider in listOf(PrimariesSlider.RED_PURITY, PrimariesSlider.GREEN_PURITY, PrimariesSlider.BLUE_PURITY)) {
            val c = PrimariesTrack.colors(defaults, slider, -100f..100f)
            assertTrue("$slider", saturation(c.last()) > saturation(c.first()) + 0.05f)
        }
    }

    @Test
    fun tintTracks() {
        val purity = PrimariesTrack.colors(defaults, PrimariesSlider.TINT_PURITY, 0f..100f)
        assertTrue(saturation(purity.first()) < 1e-3f)
        assertTrue(saturation(purity.last()) > 0.05f)
        // Tint hue 0 points at red; ±180 at cyan.
        val hue = PrimariesTrack.colors(defaults, PrimariesSlider.TINT_HUE, -180f..180f, steps = 5)
        assertTrue(hue[2][0] > hue[2][1] && hue[2][0] > hue[2][2])
        assertTrue(hue[0][0] < hue[0][1] && hue[0][0] < hue[0][2])
    }
}
