package app.rawboy.develop

/**
 * RGB histogram of a developed 8-bit image (Pro's histogram, Camera's live one), [BINS] bins per channel, with the
 * share of clipped pixels: [highlights] have a channel at 255, [shadows] every channel at 1 or below. The shader's
 * clipping warning (`clipWarning` in develop.glsl) marks the same pixels.
 */
class Histogram(
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
    /** Fraction 0..1 of pixels with a channel at 255. */
    val highlights: Float,
    /** Fraction 0..1 of pixels with every channel at 1 or below. */
    val shadows: Float,
) {
    /** Largest bin, ignoring the two end bins (a clipped spike there would flatten everything else). */
    val peak: Int = maxOf(1, maxOf(inner(red), inner(green), inner(blue)))

    companion object {
        const val BINS = 64

        /** From ARGB pixels (Android `Bitmap.getPixels`). */
        fun fromArgb(pixels: IntArray): Histogram {
            val r = IntArray(BINS)
            val g = IntArray(BINS)
            val b = IntArray(BINS)
            var high = 0
            var low = 0
            for (p in pixels) {
                val pr = (p shr 16) and 0xff
                val pg = (p shr 8) and 0xff
                val pb = p and 0xff
                r[pr * BINS / 256]++
                g[pg * BINS / 256]++
                b[pb * BINS / 256]++
                val m = maxOf(pr, pg, pb)
                if (m == 255) high++ else if (m <= 1) low++
            }
            val n = maxOf(1, pixels.size).toFloat()
            return Histogram(r, g, b, high / n, low / n)
        }

        private fun inner(bins: IntArray): Int {
            var m = 0
            for (i in 1 until bins.size - 1) m = maxOf(m, bins[i])
            return m
        }
    }
}
