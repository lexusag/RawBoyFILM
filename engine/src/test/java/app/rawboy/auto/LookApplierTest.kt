package app.rawboy.auto

import app.rawboy.auto.looks.Adjustments
import app.rawboy.auto.looks.LookApplier
import app.rawboy.auto.looks.Looks
import app.rawboy.develop.DevelopParams
import app.rawboy.develop.PresetGroup
import app.rawboy.develop.Presets
import org.junit.Assert.assertEquals
import org.junit.Test

/** Auto's rotation (the same case as web's engine.test.ts) and RawBoy presets in place of a look. */
class LookApplierTest {
    @Test
    fun rotationAddsQuarterTurnsWrappingAtFour() {
        fun rotation(base: Int, adjust: Int, strength: Float = 100f) =
            LookApplier.apply(DevelopParams(rotation = base), Looks.NATURAL, strength, Adjustments(rotation = adjust), hasFace = false).rotation
        assertEquals(1, rotation(1, 0))
        assertEquals(0, rotation(1, 3))
        assertEquals(3, rotation(0, 3, strength = 0f))
    }

    @Test
    fun presetOverridesItsGroupsAndAdjustmentsStillApply() {
        val edited = DevelopParams(
            exposure = 0.7f, contrast = 20f, lut = "asset:luts/x.cube", lutAmount = 60f, lensProfile = false,
            lensDistortion = 25f, vignetteAmount = 30f, sharpening = 40f, temperature = 4000f,
        )
        val preset = Presets.capture("Street", edited, setOf(PresetGroup.LIGHT, PresetGroup.LUT, PresetGroup.LENS, PresetGroup.DETAIL))
        val base = DevelopParams(exposure = 1.2f, temperature = 5600f, denoise = 20f)
        val p = LookApplier.applyPreset(base, preset, Adjustments.NONE)
        assertEquals(0.7f, p.exposure, 1e-6f)
        assertEquals("asset:luts/x.cube", p.lut)
        assertEquals(false, p.lensProfile)
        assertEquals(25f, p.lensDistortion, 1e-6f)
        assertEquals(30f, p.vignetteAmount, 1e-6f)
        assertEquals(5600f, p.temperature, 1e-6f) // white balance wasn't in the preset
        val brighter = LookApplier.applyPreset(base, preset, Adjustments(brightness = 100f, grain = 10f))
        assertEquals(3.7f, brighter.exposure, 1e-5f)
        assertEquals(edited.grain + 10f, brighter.grain, 1e-6f)
    }

    @Test
    fun noiseSettingsComeFromTheAutoDevelopNotThePreset() {
        // The preset was made on a clean ISO 100 shot; this photo is ISO 6400, so Auto measured far more noise.
        val edited = DevelopParams(denoise = 5f, colorDenoise = 8f, sharpening = 40f, localContrast = 30f)
        val preset = Presets.capture("Night", edited, setOf(PresetGroup.DETAIL))
        val base = DevelopParams(denoise = 60f, colorDenoise = 75f, sharpening = 6f)
        val p = LookApplier.applyPreset(base, preset, Adjustments.NONE)
        assertEquals(60f, p.denoise, 1e-6f)
        assertEquals(75f, p.colorDenoise!!, 1e-6f)
        assertEquals(6f, p.sharpening, 1e-6f)
        assertEquals(30f, p.localContrast, 1e-6f) // the rest of Detail still comes from the preset
        val less = LookApplier.applyPreset(base, preset, Adjustments(denoise = -20f))
        assertEquals(40f, less.denoise, 1e-6f)
        assertEquals(55f, less.colorDenoise!!, 1e-6f)
    }
}
