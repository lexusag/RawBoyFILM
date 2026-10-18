import Foundation

/**
 * Picks a look for a photo from what's in it. Monochrome looks are never suggested:
 * black and white is a choice the user makes.
 */
public enum LookSuggester {
    /** Key below this (about 4 EV under a typical daylight scene) counts as low light. */
    private static let lowKey: Float = 0.008

    /** A face at least this share of the frame makes it a portrait. */
    private static let portraitFaceArea: Float = 0.015

    /**
     * Gold 200 (+200 K, a warm LUT) only for scenes shot in light warmer than this: in cool daylight a few warm
     * things in the frame (sand, brick, pinkish snow) made it the pick, and snow came out yellow.
     */
    private static let warmLightKelvin: Float = 5500

    /** As-shot white below this is tungsten light: Vision3 500T, the tungsten-balanced stock, for film looks. */
    private static let tungstenKelvin: Float = 3800

    /** The film stock ([FilmLooks]) for a photo: the stock of the look [suggest] picks, tungsten scenes on 500T. */
    public static func suggestFilm(_ stats: SceneStats, asShotKelvin: Float) -> Look {
        if stats.faceArea <= portraitFaceArea && asShotKelvin < tungstenKelvin { return FilmLooks.byId("vision500t") }
        return FilmLooks.byId(suggest(stats, asShotKelvin: asShotKelvin).id)
    }

    /** [asShotKelvin]: the photo's as-shot white balance temperature. */
    public static func suggest(_ stats: SceneStats, asShotKelvin: Float) -> Look {
        let lowLight = stats.iso >= 3200 || stats.key < lowKey
        if stats.faceArea > portraitFaceArea {
            if lowLight { return Looks.portra800 }
            return Looks.portra400
        }
        if lowLight { return Looks.portra800 }
        if stats.chroma > 0.25 && stats.greenBlue > 0.3 { return Looks.ektar100 }
        if stats.warm > 0.35 && stats.warm > stats.greenBlue && asShotKelvin < warmLightKelvin { return Looks.gold200 }
        return Looks.portra400
    }
}
