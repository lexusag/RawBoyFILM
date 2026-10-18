package app.rawboy.color

import app.rawboy.develop.DevelopParams
import kotlin.math.max
import kotlin.math.pow

/** The RGB primaries sliders, for [PrimariesTrack]. */
enum class PrimariesSlider { RED_HUE, RED_PURITY, GREEN_HUE, GREEN_PURITY, BLUE_HUE, BLUE_PURITY, TINT_HUE, TINT_PURITY }

/**
 * Track colours for the RGB primaries sliders: what a sample patch turns into at each slider position, through the
 * same [Primaries.fromParams] the develop uses, with the other primaries sliders at their current values. The red,
 * green and blue sliders take a fairly saturated colour of their own hue as their sample (a pure sRGB primary would
 * clip, so more purity wouldn't show), the tint sliders neutral grey. Brightness is normalised (max channel = 1) so
 * only hue and purity show.
 *
 * Exception: the Tint hue track is drawn at full Tint purity, since at 0 it would be plain grey.
 */
object PrimariesTrack {
    // Linear sRGB <-> Rec.2020 (row-major).
    private val SRGB_TO_REC2020 = doubleArrayOf(
        0.6274040, 0.3292820, 0.0433136,
        0.0690970, 0.9195400, 0.0113612,
        0.0163916, 0.0880132, 0.8955950,
    )
    private val REC2020_TO_SRGB = doubleArrayOf(
        1.6604910, -0.5876411, -0.0728499,
        -0.1245505, 1.1328999, -0.0083494,
        -0.0181508, -0.1005789, 1.1187297,
    )

    /** Samples in linear Rec.2020: (1, 0.18, 0.18) in linear sRGB and its permutations; grey. */
    private const val SAMPLE_OTHER = 0.18
    private val RED = Mat3.apply(SRGB_TO_REC2020, doubleArrayOf(1.0, SAMPLE_OTHER, SAMPLE_OTHER))
    private val GREEN = Mat3.apply(SRGB_TO_REC2020, doubleArrayOf(SAMPLE_OTHER, 1.0, SAMPLE_OTHER))
    private val BLUE = Mat3.apply(SRGB_TO_REC2020, doubleArrayOf(SAMPLE_OTHER, SAMPLE_OTHER, 1.0))
    private val GREY = doubleArrayOf(1.0, 1.0, 1.0)

    /** [steps] sRGB-encoded colours (0..1) across [range], left to right. */
    fun colors(p: DevelopParams, slider: PrimariesSlider, range: ClosedFloatingPointRange<Float>, steps: Int = 13): List<FloatArray> {
        val sample = when (slider) {
            PrimariesSlider.RED_HUE, PrimariesSlider.RED_PURITY -> RED
            PrimariesSlider.GREEN_HUE, PrimariesSlider.GREEN_PURITY -> GREEN
            PrimariesSlider.BLUE_HUE, PrimariesSlider.BLUE_PURITY -> BLUE
            PrimariesSlider.TINT_HUE, PrimariesSlider.TINT_PURITY -> GREY
        }
        return List(steps) { i ->
            val v = range.start + (range.endInclusive - range.start) * i / (steps - 1)
            val m = Primaries.fromParams(withValue(p, slider, v))
            encode(Mat3.apply(REC2020_TO_SRGB, Mat3.apply(m, sample)))
        }
    }

    private fun withValue(p: DevelopParams, slider: PrimariesSlider, v: Float) = when (slider) {
        PrimariesSlider.RED_HUE -> p.copy(redHue = v)
        PrimariesSlider.RED_PURITY -> p.copy(redPurity = v)
        PrimariesSlider.GREEN_HUE -> p.copy(greenHue = v)
        PrimariesSlider.GREEN_PURITY -> p.copy(greenPurity = v)
        PrimariesSlider.BLUE_HUE -> p.copy(blueHue = v)
        PrimariesSlider.BLUE_PURITY -> p.copy(bluePurity = v)
        PrimariesSlider.TINT_HUE -> p.copy(tintHue = v, tintPurity = 100f)
        PrimariesSlider.TINT_PURITY -> p.copy(tintPurity = v)
    }

    /** Linear sRGB -> normalised, clipped, sRGB-encoded. */
    private fun encode(rgb: DoubleArray): FloatArray {
        val peak = max(rgb[0], max(rgb[1], rgb[2])).coerceAtLeast(1e-6)
        return FloatArray(3) {
            val c = (rgb[it] / peak).coerceIn(0.0, 1.0)
            (if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055).toFloat()
        }
    }
}
