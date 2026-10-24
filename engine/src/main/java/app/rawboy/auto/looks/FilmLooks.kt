package app.rawboy.auto.looks

/**
 * Looks on spektrafilm stocks (assets/luts/spektrafilm, see tools/spektrafilm_luts/generate.py; ids match its
 * STOCKS): RawBoy Film's looks and Auto's Film mode. The stock replaces AgX and the LUT: negative, print and scan
 * (slides: scan only), with grain and halation (RawBoy's own in the standard apps, spektrafilm's in RawBoy Film).
 * Looks add only skin retouch on the portrait films; the auto develop's exposure, white balance and denoise stay.
 */
object FilmLooks {
    /** Auto's film grain, a little finer than the stock's own (100). */
    const val FILM_GRAIN = 80f

    private fun stock(id: String, name: String, portraitRetouch: Float = 0f) =
        Look(id, name, lutFile = null, film = id, portraitRetouch = portraitRetouch, filmGrain = FILM_GRAIN)

    val PORTRA_400 = stock("portra400", "Yellow Portrait 400", portraitRetouch = 20f)

    val ALL = listOf(
        Looks.NATURAL,
        PORTRA_400,
        stock("portra800", "Yellow Portrait 800", portraitRetouch = 20f),
        stock("ektar100", "Yellow Vivid 100"),
        stock("gold200", "Yellow Warm 200"),
        stock("fuji400h", "Green Pastel 400", portraitRetouch = 20f),
        stock("xtra400", "Green Everyday 400"),
        stock("vision250d", "Yellow Cine 250D"),
        stock("vision500t", "Yellow Cine 500T"),
        stock("velvia100", "Green Vivid Slide 100"),
        stock("provia100", "Green Slide 100"),
        stock("ektachrome100", "Yellow Slide 100"),
        stock("kodachrome64", "Yellow Classic 64"),
    )

    fun byId(id: String): Look = RetiredLooks.stock(id).let { key -> ALL.firstOrNull { it.id == key } } ?: PORTRA_400
}
