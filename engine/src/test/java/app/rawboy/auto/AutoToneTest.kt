package app.rawboy.auto

import app.rawboy.auto.analysis.AutoTone
import app.rawboy.auto.analysis.NoiseLevel
import app.rawboy.develop.DevelopParams
import app.rawboy.develop.ToneMapper
import org.junit.Assert.assertEquals
import org.junit.Test

/** RawBoy Pro's Auto button (the same case as web's engine.test.ts). */
class AutoToneTest {
    @Test
    fun applyToTakesLightAndColourAndKeepsTheRest() {
        val current = DevelopParams(
            toneMapper = ToneMapper.FILMIC, exposure = 1f, lut = "portra", lutAmount = 60f, grain = 30f, rotation = 1, skinRetouch = 40f,
        )
        val auto = DevelopParams(exposure = -0.35f, contrast = 20f, saturation = 30f, temperature = 4800f, denoise = 20f, sharpening = 15f, grain = 5f)
        val p = AutoTone.applyTo(current, auto)
        assertEquals(listOf<Any>(ToneMapper.AGX, -0.35f, 20f, 30f, 4800f, 20f), listOf<Any>(p.toneMapper, p.exposure, p.contrast, p.saturation, p.temperature, p.denoise))
        assertEquals(listOf<Any?>("portra", 60f, 30f, 1, 40f), listOf<Any?>(p.lut, p.lutAmount, p.grain, p.rotation, p.skinRetouch))
    }

    @Test
    fun measuredNoiseSetsBothDenoisesAndEasesSharpening() {
        // Canon G9 at ISO 400, lifted 2 EV: heavy noise; colour more than luma.
        assertEquals(Triple(55f, 93f, 8f), AutoTone.measuredNoiseSettings(NoiseLevel(0.0107f, 0.0141f), 2f))
        // A clean Pixel shot at the phone's own exposure: a touch of denoise, full sharpening.
        assertEquals(Triple(6f, 6f, 15f), AutoTone.measuredNoiseSettings(NoiseLevel(0.0023f, 0.0019f), 0f))
        // Very noisy: capped at 100.
        assertEquals(100f, AutoTone.measuredNoiseSettings(NoiseLevel(0.05f, 0.05f), 0f).second)
    }
}
