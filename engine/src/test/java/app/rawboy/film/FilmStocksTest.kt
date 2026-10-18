package app.rawboy.film

import app.rawboy.auto.looks.Adjustments
import app.rawboy.auto.looks.FilmLooks
import app.rawboy.auto.looks.LookApplier
import app.rawboy.develop.DevelopParams
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The generated spektrafilm stocks (luts/spektrafilm) and the Camera looks built on them. */
class FilmStocksTest {
    // Unit tests run in the module directory.
    private val dir = File("../luts/spektrafilm")
    private val formation = JSONObject(dir.resolve("formation.json").readText())
    private val stocks = JSONObject(dir.resolve("stocks.json").readText()).getJSONArray("stocks")
        .let { a -> List(a.length()) { FilmStock.parse(a.getJSONObject(it), formation) } }

    @Test
    fun everyStockParsesWithItsLutsAndLicence() {
        assertTrue(stocks.isNotEmpty())
        for (s in stocks) {
            assertTrue(s.id, dir.resolve(s.filmLut).isFile && dir.resolve(s.printLut).isFile)
            assertEquals(3, s.densityMin.size)
            for (c in 0 until 3) assertTrue(s.id, s.densityMax[c] > s.densityMin[c])
            assertTrue(s.id, s.grain.particleAreaUm2 > 0f && s.grain.particleScale.all { it > 0f })
            assertTrue(s.id, s.halation.firstSigmaUm > 0f)
            // CC BY-SA: every cube keeps spektrafilm's attribution header.
            val header = dir.resolve(s.filmLut).useLines { it.take(20).joinToString("\n") }
            assertTrue(s.id, "Andrea Volpato" in header && "CC BY-SA 4.0" in header)
        }
        assertTrue(dir.resolve("SPEKTRAFILM_LICENSE.txt").isFile && dir.resolve("CHANGELOG.txt").isFile)
    }

    @Test
    fun slidesAreScannedNotPrinted() {
        val slides = stocks.filter { it.print == null }.map { it.id }.toSet()
        assertEquals(setOf("velvia100", "provia100", "ektachrome100", "kodachrome64"), slides)
    }

    @Test
    fun everyFilmLookHasAStock() {
        val ids = stocks.map { it.id }.toSet()
        for (look in FilmLooks.ALL) look.film?.let { assertTrue(it, it in ids) }
    }

    @Test
    fun strengthBlendsTheFilmAndZeroTurnsItOff() {
        val base = DevelopParams()
        val full = LookApplier.apply(base, FilmLooks.PORTRA_400, 100f, Adjustments.NONE, hasFace = false)
        assertEquals("portra400", full.film)
        assertEquals(100f, full.filmAmount, 0f)
        assertNull(full.lut)
        val half = LookApplier.apply(base, FilmLooks.PORTRA_400, 50f, Adjustments.NONE, hasFace = false)
        assertEquals(50f, half.filmAmount, 1e-4f)
        val off = LookApplier.apply(base, FilmLooks.PORTRA_400, 0f, Adjustments.NONE, hasFace = false)
        assertNull(off.film)
    }

    @Test
    fun everyStockHasItsOwnFormation() {
        for (s in stocks) {
            val f = s.formation
            assertTrue(s.id, f !== FilmStock.Formation.DEFAULT)
            assertTrue(s.id, f.grainRms.all { it in 0.002f..0.02f })
            assertTrue(s.id, f.halation.all { it in 0f..0.05f } && f.halation[0] >= f.halation[2])
            assertTrue(s.id, f.haloRadiusUm > 0f && f.scatterUm > 0f)
        }
        // The consumer stocks halate redder than Portra (fitted to RawBoy Film's renders).
        val byId = stocks.associateBy { it.id }
        assertTrue(byId.getValue("gold200").formation.halation[0] > byId.getValue("portra400").formation.halation[0])
    }

    @Test
    fun grainAdjustScalesTheFilmGrain() {
        val base = DevelopParams()
        fun grain(adjust: Float) =
            LookApplier.apply(base, FilmLooks.PORTRA_400, 100f, Adjustments(grain = adjust), hasFace = false).filmGrain
        assertEquals(100f, grain(0f), 0f)
        assertEquals(0f, grain(-50f), 0f)
        assertEquals(200f, grain(50f), 0f)
    }
}
