package app.rawboy.color

import app.rawboy.auto.analysis.MeterArea

/**
 * Pro's white balance picker: the user taps something neutral grey and the temperature and tint are set so it renders
 * grey. Samples the decoded image (linear, as-shot balanced, before any develop step), averaged over a small square
 * so noise and texture don't decide the result.
 */
object WhitePicker {
    /** Side of the averaged square, as a fraction of the image's short side (~40 px on a 24 MP photo). */
    const val SPOT_SIZE = 0.01f

    /** Pixels with a channel at or above this (1.0 = sensor clip) are left out: blown highlights aren't neutral. */
    const val CLIP = 0.95f

    sealed interface Pick {
        data class White(val kelvin: Float, val tint: Float) : Pick
        /** Every pixel in the square is clipped. */
        data object Clipped : Pick
        /** No white balance makes the spot grey (black, or a strongly saturated colour). */
        data object NotNeutral : Pick
    }

    /** Full-resolution box `[x0, x1) × [y0, y1)` to sample around image pixel ([x], [y]), kept inside the image. */
    fun box(x: Float, y: Float, width: Int, height: Int): IntArray =
        MeterArea.Spot(x / width, y / height, SPOT_SIZE).box(width, height)

    /** [rgb]: the box's pixels (RGB triplets, linear Rec.2020 as decoded); as-shot white from the photo's defaults. */
    fun pick(rgb: FloatArray, asShotKelvin: Float, asShotTint: Float): Pick {
        val sum = DoubleArray(3)
        var n = 0
        for (i in 0 until rgb.size / 3) {
            val r = rgb[i * 3]
            val g = rgb[i * 3 + 1]
            val b = rgb[i * 3 + 2]
            if (r >= CLIP || g >= CLIP || b >= CLIP) continue
            sum[0] += r
            sum[1] += g
            sum[2] += b
            n++
        }
        if (n == 0) return Pick.Clipped
        val mean = DoubleArray(3) { sum[it] / n }
        val wb = WhiteBalance.neutralTempTint(asShotKelvin.toDouble(), asShotTint.toDouble(), mean) ?: return Pick.NotNeutral
        return Pick.White(wb[0].toFloat(), wb[1].toFloat())
    }
}
