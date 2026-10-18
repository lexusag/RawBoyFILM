package app.rawboy.color

import app.rawboy.develop.DevelopParams
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * RGB primaries adjustment, after darktable's "rgb primaries" module (reimplemented
 * from its description; darktable is GPL): each Rec.2020 primary is rotated in hue
 * and pushed/pulled in purity around the white point in CIE xy, and the white point
 * itself can be tinted. The image's RGB values are then read as if they were in that
 * custom space and converted back to Rec.2020 - one 3x3 matrix, applied in
 * scene-linear light before tone mapping.
 */
object Primaries {
    // Rec.2020 primaries and D65 white, CIE xy.
    private val PRIMARIES = arrayOf(
        doubleArrayOf(0.708, 0.292),
        doubleArrayOf(0.170, 0.797),
        doubleArrayOf(0.131, 0.046),
    )
    private val WHITE = doubleArrayOf(0.3127, 0.3290)

    private val XYZ_TO_REC2020 = doubleArrayOf(
        1.7166511880, -0.3556707838, -0.2533662814,
        -0.6666843518, 1.6164812366, 0.0157685458,
        0.0176398574, -0.0427706133, 0.9421031212,
    )

    /** Slider values -> primaries matrix (identity when untouched). */
    fun fromParams(p: DevelopParams): DoubleArray {
        val hues = doubleArrayOf(p.redHue.toDouble(), p.greenHue.toDouble(), p.blueHue.toDouble()).map { Math.toRadians(it) }.toDoubleArray()
        // -100..100 -> purity x0.71 .. x1.41 (exponential, so +/- feel symmetric). Rec.2020's
        // primaries sit near the spectral locus, so small changes already move a lot.
        val purities = doubleArrayOf(p.redPurity.toDouble(), p.greenPurity.toDouble(), p.bluePurity.toDouble())
            .map { 2.0.pow(it / 200.0) }.toDoubleArray()
        val tintPurity = p.tintPurity / 100.0 * 0.1 // full slider = 10% towards the gamut edge
        if (isIdentity(hues, purities, tintPurity)) return Mat3.diagonal(1.0, 1.0, 1.0)
        return matrix(hues, purities, Math.toRadians(p.tintHue.toDouble()), tintPurity)
    }

    /**
     * @param hues rotation per primary (R, G, B) in radians; + turns red towards yellow,
     *   green towards cyan, blue towards magenta.
     * @param purities per primary, 1 = unchanged, <1 less pure, >1 more pure.
     * @param tintHue direction of the white-point tint (radians, 0 = towards red).
     * @param tintPurity 0 = neutral white.
     * @return row-major Rec.2020 -> Rec.2020 matrix (identity for the defaults).
     */
    fun matrix(hues: DoubleArray, purities: DoubleArray, tintHue: Double, tintPurity: Double): DoubleArray {
        val custom = Array(3) { rotateAndScale(it, purities[it], hues[it]) }
        val white = rotateAndScale(0, tintPurity, tintHue)
        return Mat3.multiply(XYZ_TO_REC2020, rgbToXyz(custom, white))
    }

    fun isIdentity(hues: DoubleArray, purities: DoubleArray, tintPurity: Double) =
        hues.all { it == 0.0 } && purities.all { it == 1.0 } && tintPurity == 0.0

    /**
     * Rotates primary [index] around the white point by [rotation] and places it at
     * [scale] x the distance to the edge of the Rec.2020 triangle in that direction
     * (so scale 1 with no rotation gives back the primary itself).
     */
    private fun rotateAndScale(index: Int, scale: Double, rotation: Double): DoubleArray {
        val p = PRIMARIES[index]
        val angle = atan2(p[1] - WHITE[1], p[0] - WHITE[0]) + rotation
        val dx = cos(angle)
        val dy = sin(angle)
        val edge = distanceToGamutEdge(dx, dy)
        return doubleArrayOf(WHITE[0] + scale * edge * dx, WHITE[1] + scale * edge * dy)
    }

    /** Distance from the white point along (dx, dy) to the Rec.2020 triangle's edge. */
    private fun distanceToGamutEdge(dx: Double, dy: Double): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until 3) {
            val a = PRIMARIES[i]
            val b = PRIMARIES[(i + 1) % 3]
            // Solve white + t*(dx,dy) = a + u*(b-a) for t >= 0, 0 <= u <= 1.
            val ex = b[0] - a[0]
            val ey = b[1] - a[1]
            val det = dx * (-ey) - dy * (-ex)
            if (det == 0.0) continue
            val wx = a[0] - WHITE[0]
            val wy = a[1] - WHITE[1]
            val t = (wx * (-ey) - wy * (-ex)) / det
            val u = (dx * wy - dy * wx) / det
            if (t >= 0 && u >= -1e-9 && u <= 1 + 1e-9 && t < best) best = t
        }
        return best
    }

    /** RGB -> XYZ for the given primaries and white (white maps to Y = 1). */
    private fun rgbToXyz(primaries: Array<DoubleArray>, white: DoubleArray): DoubleArray {
        fun xyz(xy: DoubleArray) = doubleArrayOf(xy[0] / xy[1], 1.0, (1 - xy[0] - xy[1]) / xy[1])
        val r = xyz(primaries[0])
        val g = xyz(primaries[1])
        val b = xyz(primaries[2])
        val p = doubleArrayOf(r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2])
        val s = Mat3.apply(Mat3.inverse(p), xyz(white))
        return Mat3.multiply(p, Mat3.diagonal(s[0], s[1], s[2]))
    }
}
