package app.rawboy.develop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presets' groups (the Film tone mapper in Light, local contrast and the two denoises in Detail), as RawBoy Web's Presets.ts. */
class PresetsTest {
    @Test
    fun lightGroupCarriesTheFilm() {
        val film = DevelopParams(film = "ektar100", filmAmount = 70f, filmGrain = 150f, halation = 50f)
        val preset = Presets.decode(Presets.encode(listOf(Presets.capture("Ektar", film, setOf(PresetGroup.LIGHT))))).single()
        val applied = Presets.apply(preset, DevelopParams())
        assertEquals("ektar100", applied.film)
        assertEquals(70f, applied.filmAmount, 0f)
        assertEquals(150f, applied.filmGrain, 0f)
        assertEquals(50f, applied.halation, 0f)
        // A Light preset saved without film turns it off.
        assertNull(Presets.apply(Presets.capture("AgX", DevelopParams(), setOf(PresetGroup.LIGHT)), film).film)
    }

    @Test
    fun presetsFromBeforeFilmLeaveItAlone() {
        val old = """{"format":"rawboy-preset","version":1,"name":"Old","groups":["light"],"values":{"toneMapper":"agx","exposure":1}}"""
        val applied = Presets.apply(Presets.decode(old).single(), DevelopParams(film = "portra400"))
        assertEquals("portra400", applied.film)
        assertEquals(1f, applied.exposure, 0f)
    }

    @Test
    fun detailGroupCarriesLocalContrast() {
        val edited = DevelopParams(localContrast = 35f)
        val preset = Presets.decode(Presets.encode(listOf(Presets.capture("T", edited, setOf(PresetGroup.DETAIL))))).single()
        assertEquals(35f, Presets.apply(preset, DevelopParams()).localContrast, 0f)
    }

    @Test
    fun detailGroupCarriesBothDenoises() {
        val split = DevelopParams(denoise = 30f, colorDenoise = 60f)
        val preset = Presets.decode(Presets.encode(listOf(Presets.capture("Split", split, setOf(PresetGroup.DETAIL))))).single()
        val applied = Presets.apply(preset, DevelopParams(colorDenoise = 5f))
        assertEquals(30f, applied.denoise, 0f)
        assertEquals(60f, applied.effectiveColorDenoise, 0f)
    }

    @Test
    fun presetsFromBeforeColorDenoiseSetBothFromTheOneDenoise() {
        val old = """{"format":"rawboy-preset","version":1,"name":"Old","groups":["detail"],"values":{"denoise":40}}"""
        val applied = Presets.apply(Presets.decode(old).single(), DevelopParams(colorDenoise = 10f))
        assertEquals(40f, applied.denoise, 0f)
        assertNull(applied.colorDenoise)
        assertEquals(40f, applied.effectiveColorDenoise, 0f)
    }

    @Test
    fun presetsWithRemovedLooksGetTheClosestKeptOne() {
        val old = """{"format":"rawboy-preset","version":1,"name":"Old","groups":["light","lut"],""" +
            """"values":{"film":"c200","lut":"asset:luts/Kodak UltraMax 400.cube"}}"""
        val applied = Presets.apply(Presets.decode(old).single(), DevelopParams())
        assertEquals("xtra400", applied.film)
        assertEquals("asset:luts/Yellow Portrait 800.cube", applied.lut)
    }

    @Test
    fun lensPresetsRoundTripAndMatchTheirLens() {
        val lens = Presets.captureLens(DevelopParams(lensDistortion = -12f, vignetteAmount = 40f), "XF23mmF2 R WR")
        assertTrue(lens.isLensPreset)
        assertEquals("XF23mmF2 R WR", lens.name)
        val decoded = Presets.decode(Presets.encode(listOf(lens))).single()
        assertEquals("XF23mmF2 R WR", decoded.lens)
        assertTrue(decoded.isLensPreset)
        assertEquals(setOf("lensProfile", "lensDistortion", "vignetteAmount", "vignetteMidpoint"), decoded.values.keys)
        val look = Presets.capture("Look", DevelopParams(), setOf(PresetGroup.LIGHT, PresetGroup.LENS), "XF23mmF2 R WR")
        assertFalse(look.isLensPreset)
        assertEquals(decoded, Presets.lensPresetFor(listOf(look, decoded), " xf23mmf2 r wr"))
        assertNull(Presets.lensPresetFor(listOf(look, decoded), "XF35mmF2 R WR"))
        // A lens-only preset naming no lens (a manual lens, or saved before the lens was recorded) is only picked by hand.
        val manual = Presets.decode("""{"format":"rawboy-preset","version":1,"name":"Helios 44-2","groups":["lens"],"values":{"lensDistortion":5}}""").single()
        assertNull(manual.lens)
        assertNull(Presets.lensPresetFor(listOf(manual), "XF35mmF2 R WR"))
        assertNull(Presets.lensPresetFor(listOf(manual), ""))
        assertEquals(decoded, Presets.lensPresetFor(listOf(manual, decoded), "XF23mmF2 R WR"))
    }

    @Test
    fun lensPresetsArePickedByLensByIdOrNotAtAll() {
        val auto = Presets.captureLens(DevelopParams(lensDistortion = 3f), "XF23mmF2 R WR")
        val manual = Presets.captureLens(DevelopParams(lensDistortion = -8f), "", "Helios 44-2")
        assertEquals("Helios 44-2", manual.name)
        assertNull(manual.lens)
        val list = listOf(auto, manual)
        assertEquals(auto, Presets.chosenLensPreset(list, null, "XF23mmF2 R WR"))
        assertNull(Presets.chosenLensPreset(list, null, ""))
        assertEquals(manual, Presets.chosenLensPreset(list, manual.id, "XF23mmF2 R WR"))
        assertNull(Presets.chosenLensPreset(list, Presets.NO_LENS_PRESET, "XF23mmF2 R WR"))
        assertNull(Presets.chosenLensPreset(listOf(auto), manual.id, ""))
    }

    @Test
    fun savingALensPresetAgainReplacesTheOneForThatLens() {
        val first = Presets.captureLens(DevelopParams(lensDistortion = 5f), "50mm")
        val other = Presets.captureLens(DevelopParams(lensDistortion = 9f), "85mm")
        val again = Presets.captureLens(DevelopParams(lensDistortion = 7f), "50mm")
        val list = Presets.putLensPreset(listOf(first, other), again)
        assertEquals(listOf(first.id, other.id), list.map { it.id })
        assertEquals(7f, list[0].values["lensDistortion"])
    }

    @Test
    fun namedManualLensPresetsLiveSideBySide() {
        val helios = Presets.captureLens(DevelopParams(lensDistortion = 5f), "", "Helios 44-2")
        val jupiter = Presets.captureLens(DevelopParams(lensDistortion = 2f), "", "Jupiter-9")
        val list = listOf(helios, jupiter).fold(emptyList<Preset>(), Presets::putLensPreset)
        assertEquals(2, list.size)
        val again = Presets.captureLens(DevelopParams(lensDistortion = 7f), "", "helios 44-2 ")
        val updated = Presets.putLensPreset(list, again)
        assertEquals(listOf(helios.id, jupiter.id), updated.map { it.id })
        assertEquals(7f, updated[0].values["lensDistortion"])
        // One photo, one recorded lens, two names: two presets.
        val a = Presets.captureLens(DevelopParams(lensDistortion = 1f), "----", "Helios 44-2")
        val b = Presets.captureLens(DevelopParams(lensDistortion = 2f), "----", "Jupiter-9")
        assertEquals(2, Presets.putLensPreset(listOf(a), b).size)
    }
}
