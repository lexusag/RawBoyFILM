package app.rawboy.auto.looks

import app.rawboy.auto.analysis.SceneStats

/**
 * Picks a look for a photo from what's in it. Monochrome looks are never suggested:
 * black and white is a choice the user makes.
 */
object LookSuggester {
    /** Key below this (about 4 EV under a typical daylight scene) counts as low light. */
    private const val LOW_KEY = 0.008f

    /** A face at least this share of the frame makes it a portrait. */
    private const val PORTRAIT_FACE_AREA = 0.015f

    /**
     * Gold 200 (+200 K, a warm LUT) only for scenes shot in light warmer than this: in cool daylight a few warm
     * things in the frame (sand, brick, pinkish snow) made it the pick, and snow came out yellow.
     */
    private const val WARM_LIGHT_KELVIN = 5500f

    /** As-shot white below this is tungsten light: Vision3 500T, the tungsten-balanced stock, for film looks. */
    private const val TUNGSTEN_KELVIN = 3800f

    /** The film stock ([FilmLooks]) for a photo: the stock of the look [suggest] picks, tungsten scenes on 500T. */
    fun suggestFilm(stats: SceneStats, asShotKelvin: Float): Look =
        if (stats.faceArea <= PORTRAIT_FACE_AREA && asShotKelvin < TUNGSTEN_KELVIN) FilmLooks.byId("vision500t")
        else FilmLooks.byId(suggest(stats, asShotKelvin).id)

    /** [asShotKelvin]: the photo's as-shot white balance temperature. */
    fun suggest(stats: SceneStats, asShotKelvin: Float): Look {
        val lowLight = stats.iso >= 3200f || stats.key < LOW_KEY
        return when {
            stats.faceArea > PORTRAIT_FACE_AREA -> when {
                lowLight -> Looks.PORTRA_800
                else -> Looks.PORTRA_400
            }
            lowLight -> Looks.PORTRA_800
            stats.chroma > 0.25f && stats.greenBlue > 0.3f -> Looks.EKTAR_100
            stats.warm > 0.35f && stats.warm > stats.greenBlue && asShotKelvin < WARM_LIGHT_KELVIN -> Looks.GOLD_200
            else -> Looks.PORTRA_400
        }
    }
}
