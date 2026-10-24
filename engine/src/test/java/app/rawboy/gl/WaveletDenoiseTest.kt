package app.rawboy.gl

import org.junit.Assert.assertEquals
import org.junit.Test

class WaveletDenoiseTest {
    @Test
    fun fullResolutionLevelsLeaveFineChromaToTheShader() {
        val full = WaveletDenoise.levels(1f, 0.01f, 0.01f)
        assertEquals(listOf(1, 2, 4, 8, 16), full.map { it.stride })
        assertEquals(listOf(false, false, true, true, true), full.map { it.chromaThreshold > 0f })
    }

    @Test
    fun aQuarterSizePreviewStartsAtFullResolutionBandTwo() {
        val full = WaveletDenoise.levels(1f, 0.01f, 0.01f)
        val quarter = WaveletDenoise.levels(0.25f, 0.01f, 0.01f)
        assertEquals(3, quarter.size)
        assertEquals(full[2].lumaThreshold, quarter[0].lumaThreshold, 1e-6f)
        assertEquals(full[2].chromaThreshold, quarter[0].chromaThreshold, 1e-6f)
    }
}
