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

    /** Bundled LUT ids, as [Look.lutId] and LutRepository name them; also the files renamed to drop trademarked names. */
    private val LUTS = mapOf(
        "asset:luts/Kodak UltraMax 400.cube" to "asset:luts/Yellow Portrait 800.cube",
        "asset:luts/Kodak Portra 160.cube" to "asset:luts/Yellow Portrait 400.cube",
        "asset:luts/Kodak Portra 400.cube" to "asset:luts/Yellow Portrait 400.cube",
        "asset:luts/Kodak Portra 800.cube" to "asset:luts/Yellow Portrait 800.cube",
        "asset:luts/Kodak Ektar 100.cube" to "asset:luts/Yellow Vivid 100.cube",
        "asset:luts/Kodak Gold 200.cube" to "asset:luts/Yellow Warm 200.cube",
        "asset:luts/Fuji Superia X-Tra 400.cube" to "asset:luts/Green Everyday 400.cube",
        "asset:luts/Fuji Pro 400H.cube" to "asset:luts/Green Pastel 400.cube",
        "asset:luts/Kodak Vision3 500T.cube" to "asset:luts/Yellow Cine 500T.cube",
        "asset:luts/Fuji Velvia 50.cube" to "asset:luts/Green Vivid Slide 50.cube",
        "asset:luts/Kodak Tri-X 400.cube" to "asset:luts/Yellow Mono 400.cube",
        "asset:luts/Kodak Tri-X 400 Push.cube" to "asset:luts/Yellow Mono Push.cube",
        "asset:luts/Kodak Tri-X 400 Hard.cube" to "asset:luts/Yellow Mono Hard.cube",
        "asset:luts/Kodak Double-X 5222.cube" to "asset:luts/Yellow Cine Mono.cube",
        "asset:luts/Kodak Double-X 5222 Soft.cube" to "asset:luts/Yellow Cine Mono Soft.cube",
    )

    fun stock(id: String): String = STOCKS[id] ?: id
    fun look(id: String): String = LOOKS[id] ?: id
    fun lut(id: String): String = LUTS[id] ?: id
}
