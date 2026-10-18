import Foundation

/**
 * Warms or cools a white balance so it still looks natural: the temperature moves by a
 * factor in Kelvin, and tint follows. Warming along the daylight locus alone tends to leave
 * a green/teal cast (camera greens, film LUTs), so each mired of warming adds a little
 * magenta; cooling adds a little green.
 *
 * Tint follows Lightroom: + makes the image more magenta.
 */
public enum Warmth {
    /** Tint units of magenta per mired of warming. */
    private static let magentaPerMired: Float = 0.25

    /**
     * Temperature limits. Wider than Pro's slider (2000..12000 K): Warmth on top of the default can
     * put daylight well above 12000 K, and the white balance model (Planckian locus) is valid to 25000 K.
     */
    public static let minKelvin: Float = 2000
    public static let maxKelvin: Float = 25000

    /** [p] with its temperature multiplied by [factor] (> 1 = warmer) and the matching tint. */
    public static func shift(_ p: DevelopParams, _ factor: Float) -> DevelopParams {
        if factor == 1 { return p }
        let kelvin = (p.temperature * factor).clamped(minKelvin, maxKelvin)
        let warmingMired = 1e6 / p.temperature - 1e6 / kelvin
        var out = p
        out.temperature = kelvin
        out.tint = (p.tint + warmingMired * magentaPerMired).clamped(-100, 100)
        return out
    }
}
