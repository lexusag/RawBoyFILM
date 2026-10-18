import Foundation

/**
 * Looks and film stocks removed as near-duplicates (2026-10-17, median dE2000 of their renders against the kept one
 * 0.4-3), each mapped to the closest one kept, so presets, saved picks and settings that name them still work.
 * Mirrors Android's RetiredLooks.kt.
 */
public enum RetiredLooks {
    /** spektrafilm stock ids ([FilmStocks], [FilmLooks]). */
    private static let stocks = ["ultramax400": "portra800", "c200": "xtra400", "portra160": "portra400"]

    /** [Looks] ids. */
    private static let looks = ["ultramax400": "portra800", "portra160": "portra400"]

    /** Bundled LUT ids, as [Look.lutId] and LutRepository name them; also the files renamed to drop trademarked names. */
    private static let luts = [
        "asset:luts/Kodak UltraMax 400.cube": "asset:luts/Yellow Portrait 800.cube",
        "asset:luts/Kodak Portra 160.cube": "asset:luts/Yellow Portrait 400.cube",
        "asset:luts/Kodak Portra 400.cube": "asset:luts/Yellow Portrait 400.cube",
        "asset:luts/Kodak Portra 800.cube": "asset:luts/Yellow Portrait 800.cube",
        "asset:luts/Kodak Ektar 100.cube": "asset:luts/Yellow Vivid 100.cube",
        "asset:luts/Kodak Gold 200.cube": "asset:luts/Yellow Warm 200.cube",
        "asset:luts/Fuji Superia X-Tra 400.cube": "asset:luts/Green Everyday 400.cube",
        "asset:luts/Fuji Pro 400H.cube": "asset:luts/Green Pastel 400.cube",
        "asset:luts/Kodak Vision3 500T.cube": "asset:luts/Yellow Cine 500T.cube",
        "asset:luts/Fuji Velvia 50.cube": "asset:luts/Green Vivid Slide 50.cube",
        "asset:luts/Kodak Tri-X 400.cube": "asset:luts/Yellow Mono 400.cube",
        "asset:luts/Kodak Tri-X 400 Push.cube": "asset:luts/Yellow Mono Push.cube",
        "asset:luts/Kodak Tri-X 400 Hard.cube": "asset:luts/Yellow Mono Hard.cube",
        "asset:luts/Kodak Double-X 5222.cube": "asset:luts/Yellow Cine Mono.cube",
        "asset:luts/Kodak Double-X 5222 Soft.cube": "asset:luts/Yellow Cine Mono Soft.cube",
    ]

    public static func stock(_ id: String) -> String { stocks[id] ?? id }
    public static func look(_ id: String) -> String { looks[id] ?? id }
    public static func lut(_ id: String) -> String { luts[id] ?? id }
}
