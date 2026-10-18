package app.rawboy.auto.looks

/**
 * Looks and film stocks removed as near-duplicates (2026-10-17, median dE2000 of their renders against the kept one
 * 0.4-3), each mapped to the closest one kept, so presets, saved picks and settings that name them still work.
 */
object RetiredLooks {
    /** spektrafilm stock ids ([app.rawboy.film.FilmStocks], [FilmLooks]). */
    private val STOCKS = mapOf("ultramax400" to "portra800", "c200" to "xtra400", "portra160" to "portra400")

    /** [Looks] ids. */
    private val LOOKS = mapOf("ultramax400" to "portra800", "portra160" to "portra400")

    /** Bundled LUT ids, as [Look.lutId] and LutRepository name them. */
    private val LUTS = mapOf(
        "asset:luts/Kodak UltraMax 400.cube" to "asset:luts/Kodak Portra 800.cube",
        "asset:luts/Kodak Portra 160.cube" to "asset:luts/Kodak Portra 400.cube",
    )

    fun stock(id: String): String = STOCKS[id] ?: id
    fun look(id: String): String = LOOKS[id] ?: id
    fun lut(id: String): String = LUTS[id] ?: id
}
