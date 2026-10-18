package app.rawboy.color

import kotlin.math.hypot

/**
 * Temperature/tint <-> chromaticity conversion and the Bradford re-balancing
 * matrix used by the shader. Matrices are row-major DoubleArray(9).
 */
object WhiteBalance {
    /** Down to 1000 K so infrared photos (very red raws) can be neutralised. */
    const val MIN_KELVIN = 1000.0
    const val MAX_KELVIN = 12000.0
    /** The Tint slider's range is -MAX_TINT..MAX_TINT (wider than Lightroom's 100, for infrared photos). */
    const val MAX_TINT = 250.0

    // Valid range of the Kim et al. Planckian locus approximation.
    private const val LOCUS_MIN_K = 1667.0
    private const val LOCUS_MAX_K = 25000.0
    // Below Kim's range, Krystek's approximation (valid from 1000 K) takes over.
    private const val LOCUS_LOW_K = 1000.0

    /**
     * Duv per tint unit. Tint follows Lightroom: it names the illuminant's offset from the locus,
     * positive = green (above it). So + Tint on the slider (assuming greener light) makes the
     * image more magenta, and a greenish light reads as a positive as-shot tint.
     */
    private const val TINT_SCALE = 0.00025

    private val REC2020_TO_XYZ = doubleArrayOf(
        0.6369580483, 0.1446169036, 0.1688809752,
        0.2627002120, 0.6779980715, 0.0593017165,
        0.0000000000, 0.0280726930, 1.0609850577,
    )
    private val XYZ_TO_REC2020 = doubleArrayOf(
        1.7166511880, -0.3556707838, -0.2533662814,
        -0.6666843518, 1.6164812366, 0.0157685458,
        0.0176398574, -0.0427706133, 0.9421031212,
    )
    private val BRADFORD = doubleArrayOf(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    )
    private val BRADFORD_INV = Mat3.inverse(BRADFORD)

    /**
     * Planckian locus chromaticity: Kim et al. 2002 from 1667 K; below, down to 1000 K, Krystek 1985 shifted to meet
     * Kim's at 1667 K (so the locus stays continuous and nothing above 1667 K changes).
     */
    fun planckianXY(kelvin: Double): DoubleArray {
        val t = kelvin.coerceIn(LOCUS_LOW_K, LOCUS_MAX_K)
        if (t >= LOCUS_MIN_K) return kimXY(t)
        val k = krystekUV(t)
        val k0 = krystekUV(LOCUS_MIN_K)
        val kim = kimXY(LOCUS_MIN_K).let { xyToUV(it[0], it[1]) }
        return uvToXY(k[0] - k0[0] + kim[0], k[1] - k0[1] + kim[1])
    }

    /** Krystek's rational approximation of the Planckian locus in CIE 1960 uv (1000 K..15000 K). */
    private fun krystekUV(t: Double): DoubleArray {
        val t2 = t * t
        return doubleArrayOf(
            (0.860117757 + 1.54118254e-4 * t + 1.28641212e-7 * t2) / (1.0 + 8.42420235e-4 * t + 7.08145163e-7 * t2),
            (0.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t2) / (1.0 - 2.89741816e-5 * t + 1.61456053e-7 * t2),
        )
    }

    private fun kimXY(t: Double): DoubleArray {
        val t2 = t * t
        val t3 = t2 * t
        val x = if (t <= 4000.0) {
            -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390
        }
        val x2 = x * x
        val x3 = x2 * x
        val y = when {
            t <= 2222.0 -> -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683
            t <= 4000.0 -> -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483
        }
        return doubleArrayOf(x, y)
    }

    fun xyFromTempTint(kelvin: Double, tint: Double): DoubleArray {
        val mired = 1e6 / kelvin.coerceIn(LOCUS_LOW_K, LOCUS_MAX_K)
        val uv = locusUV(mired)
        val n = locusNormal(mired)
        val duv = tint * TINT_SCALE
        return uvToXY(uv[0] + n[0] * duv, uv[1] + n[1] * duv)
    }

    /** Returns (kelvin, tint) for a white chromaticity. */
    fun tempTintFromXY(x: Double, y: Double): DoubleArray {
        val p = xyToUV(x, y)
        val minMired = 1e6 / LOCUS_MAX_K
        val maxMired = 1e6 / LOCUS_LOW_K
        fun dist(m: Double): Double {
            val uv = locusUV(m)
            return hypot(p[0] - uv[0], p[1] - uv[1])
        }
        // Coarse scan, then golden-section refinement around the best sample.
        var best = minMired
        var m = minMired
        while (m <= maxMired) {
            if (dist(m) < dist(best)) best = m
            m += 2.0
        }
        var lo = (best - 2.0).coerceAtLeast(minMired)
        var hi = (best + 2.0).coerceAtMost(maxMired)
        val g = 0.6180339887498949
        repeat(40) {
            val a = hi - g * (hi - lo)
            val b = lo + g * (hi - lo)
            if (dist(a) < dist(b)) hi = b else lo = a
        }
        val mired = (lo + hi) / 2
        val uv = locusUV(mired)
        val n = locusNormal(mired)
        val duv = (p[0] - uv[0]) * n[0] + (p[1] - uv[1]) * n[1]
        return doubleArrayOf(1e6 / mired, duv / TINT_SCALE)
    }

    /**
     * Rec.2020 -> Rec.2020 matrix that re-balances an image that was balanced
     * for [asShotXY] so that [targetXY] becomes neutral instead.
     */
    fun rebalanceMatrix(asShotXY: DoubleArray, targetXY: DoubleArray): DoubleArray {
        val lmsAsShot = Mat3.apply(BRADFORD, xyToXYZ(asShotXY))
        val lmsTarget = Mat3.apply(BRADFORD, xyToXYZ(targetXY))
        val scale = Mat3.diagonal(
            lmsAsShot[0] / lmsTarget[0],
            lmsAsShot[1] / lmsTarget[1],
            lmsAsShot[2] / lmsTarget[2],
        )
        val cat = Mat3.multiply(BRADFORD_INV, Mat3.multiply(scale, BRADFORD))
        return Mat3.multiply(XYZ_TO_REC2020, Mat3.multiply(cat, REC2020_TO_XYZ))
    }

    /**
     * The white balance picker: temperature and tint (clamped to the sliders' ranges) that render [rgb] neutral grey.
     * [rgb] is a patch of the decoded image (linear Rec.2020, balanced for the as-shot white [asShotKelvin],
     * [asShotTint]), before the shader's white balance. Null when no white makes it grey (black, or a saturated colour).
     *
     * Solves [rebalanceMatrix] (asShot, target) x rgb = k (1, 1, 1): in Bradford LMS the matrix is a diagonal scale
     * asShot / target, so target = asShot x lms(rgb) / lms(1, 1, 1) per cone.
     */
    fun neutralTempTint(asShotKelvin: Double, asShotTint: Double, rgb: DoubleArray): DoubleArray? {
        val sample = Mat3.apply(BRADFORD, Mat3.apply(REC2020_TO_XYZ, rgb))
        val grey = Mat3.apply(BRADFORD, Mat3.apply(REC2020_TO_XYZ, doubleArrayOf(1.0, 1.0, 1.0)))
        val asShot = Mat3.apply(BRADFORD, xyToXYZ(xyFromTempTint(asShotKelvin, asShotTint)))
        val lms = DoubleArray(3) { asShot[it] * sample[it] / grey[it] }
        if (lms.any { !(it > 0.0) }) return null
        val xyz = Mat3.apply(BRADFORD_INV, lms)
        val sum = xyz[0] + xyz[1] + xyz[2]
        if (!(xyz[1] > 0.0) || !(sum > 0.0)) return null
        val tt = tempTintFromXY(xyz[0] / sum, xyz[1] / sum)
        return doubleArrayOf(tt[0].coerceIn(MIN_KELVIN, MAX_KELVIN), tt[1].coerceIn(-MAX_TINT, MAX_TINT))
    }

    private fun locusUV(mired: Double): DoubleArray {
        val xy = planckianXY(1e6 / mired)
        return xyToUV(xy[0], xy[1])
    }

    /** Unit normal of the locus pointing above it (towards green). */
    private fun locusNormal(mired: Double): DoubleArray {
        val a = locusUV(mired - 0.5)
        val b = locusUV(mired + 0.5)
        val du = b[0] - a[0]
        val dv = b[1] - a[1]
        val len = hypot(du, dv)
        return doubleArrayOf(-dv / len, du / len)
    }

    private fun xyToXYZ(xy: DoubleArray) =
        doubleArrayOf(xy[0] / xy[1], 1.0, (1.0 - xy[0] - xy[1]) / xy[1])

    // CIE 1960 UCS
    private fun xyToUV(x: Double, y: Double): DoubleArray {
        val d = -2.0 * x + 12.0 * y + 3.0
        return doubleArrayOf(4.0 * x / d, 6.0 * y / d)
    }

    private fun uvToXY(u: Double, v: Double): DoubleArray {
        val d = 2.0 * u - 8.0 * v + 4.0
        return doubleArrayOf(3.0 * u / d, 2.0 * v / d)
    }
}
