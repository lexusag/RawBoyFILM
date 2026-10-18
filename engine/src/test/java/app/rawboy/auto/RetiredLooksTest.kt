package app.rawboy.auto

import app.rawboy.auto.looks.FilmLooks
import app.rawboy.auto.looks.Looks
import app.rawboy.auto.looks.RetiredLooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Removed looks and stocks resolve to kept ones, so saved picks, settings and presets still work. */
class RetiredLooksTest {
    @Test
    fun removedLooksMapToTheClosestKeptOne() {
        assertEquals(Looks.PORTRA_800, Looks.byId("ultramax400"))
        assertEquals(Looks.PORTRA_400, Looks.byId("portra160"))
        assertEquals("portra800", FilmLooks.byId("ultramax400").id)
        assertEquals("xtra400", FilmLooks.byId("c200").id)
        assertEquals("portra400", FilmLooks.byId("portra160").id)
    }

    @Test
    fun removedLutsMapToBundledOnes() {
        for (old in listOf(
            "asset:luts/Kodak UltraMax 400.cube", "asset:luts/Kodak Portra 160.cube", "asset:luts/Kodak Portra 400.cube",
        )) {
            val kept = RetiredLooks.lut(old)
            assertTrue(old, kept != old && File("..", kept.removePrefix("asset:")).isFile)
        }
    }
}
