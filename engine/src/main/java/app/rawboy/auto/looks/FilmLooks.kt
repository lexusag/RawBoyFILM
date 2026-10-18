package app.rawboy.auto.looks

/**
 * Looks on spektrafilm stocks (assets/luts/spektrafilm, see tools/spektrafilm_luts/generate.py; ids match its
 * STOCKS): RawBoy Film's looks and Auto's Film mode. The stock replaces AgX and the LUT: negative, print and scan
 * (slides: scan only), with grain and halation (RawBoy's own in the standard apps, spektrafilm's in RawBoy Film).
 * Looks add only skin retouch on the portrait films; the auto develop's exposure, white balance and denoise stay.
 */
object FilmLooks {
    private fun stock(id: String, name: String, portraitRetouch: Float = 0f) =
        Look(id, name, lutFile = null, film = id, portraitRetouch = portraitRetouch)

    val PORTRA_400 = stock("portra400", "Portra 400", portraitRetouch = 20f)

    val ALL = listOf(
        Looks.NATURAL,
        PORTRA_400,
        stock("portra800", "Portra 800", portraitRetouch = 20f),
        stock("ektar100", "Ektar 100"),
        stock("gold200", "Gold 200"),
        stock("fuji400h", "Fuji 400H", portraitRetouch = 20f),
        stock("xtra400", "X-Tra 400"),
        stock("vision250d", "Vision3 250D"),
        stock("vision500t", "Vision3 500T"),
        stock("velvia100", "Velvia 100"),
        stock("provia100", "Provia 100F"),
        stock("ektachrome100", "Ektachrome 100"),
        stock("kodachrome64", "Kodachrome 64"),
    )

    fun byId(id: String): Look = RetiredLooks.stock(id).let { key -> ALL.firstOrNull { it.id == key } } ?: PORTRA_400
}
