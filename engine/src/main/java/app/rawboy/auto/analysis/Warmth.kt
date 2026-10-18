package app.rawboy.auto.analysis

import app.rawboy.develop.DevelopParams

/**
 * Warms or cools a white balance so it still looks natural: the temperature moves by a
 * factor in Kelvin, and tint follows. Warming along the daylight locus alone tends to leave
 * a green/teal cast (camera greens, film LUTs), so each mired of warming adds a little
 * magenta; cooling adds a little green.
 *
 * Tint follows Lightroom: + makes the image more magenta.
 */
object Warmth {
    /** Tint units of magenta per mired of warming. */
    private const val MAGENTA_PER_MIRED = 0.25f

    /**
     * Temperature limits. Wider than Pro's slider (2000..12000 K): Warmth on top of the default can
     * put daylight well above 12000 K, and the white balance model (Planckian locus) is valid to 25000 K.
     */
    const val MIN_KELVIN = 2000f
    const val MAX_KELVIN = 25000f

    /** [p] with its temperature multiplied by [factor] (> 1 = warmer) and the matching tint. */
    fun shift(p: DevelopParams, factor: Float): DevelopParams {
        if (factor == 1f) return p
        val kelvin = (p.temperature * factor).coerceIn(MIN_KELVIN, MAX_KELVIN)
        val warmingMired = 1e6f / p.temperature - 1e6f / kelvin
        return p.copy(
            temperature = kelvin,
            tint = (p.tint + warmingMired * MAGENTA_PER_MIRED).coerceIn(-100f, 100f),
        )
    }
}
