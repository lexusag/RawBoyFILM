import Foundation

/**
 * Temperature/tint <-> chromaticity conversion and the Bradford re-balancing
 * matrix used by the shader. Matrices are row-major [Double] of 9.
 */
public enum WhiteBalance {
    /** Down to 1000 K so infrared photos (very red raws) can be neutralised. */
    public static let minKelvin = 1000.0
    public static let maxKelvin = 12000.0
    /** The Tint slider's range is -maxTint...maxTint (wider than Lightroom's 100, for infrared photos). */
    public static let maxTint = 250.0

    // Valid range of the Kim et al. Planckian locus approximation.
    private static let locusMinK = 1667.0
    private static let locusMaxK = 25000.0
    // Below Kim's range, Krystek's approximation (valid from 1000 K) takes over.
    private static let locusLowK = 1000.0

    /**
     * Duv per tint unit. Tint follows Lightroom: it names the illuminant's offset from the locus,
     * positive = green (above it). So + Tint on the slider (assuming greener light) makes the
     * image more magenta, and a greenish light reads as a positive as-shot tint.
     */
    private static let tintScale = 0.00025

    private static let rec2020ToXyz: [Double] = [
        0.6369580483, 0.1446169036, 0.1688809752,
        0.2627002120, 0.6779980715, 0.0593017165,
        0.0000000000, 0.0280726930, 1.0609850577,
    ]
    private static let xyzToRec2020: [Double] = [
        1.7166511880, -0.3556707838, -0.2533662814,
        -0.6666843518, 1.6164812366, 0.0157685458,
        0.0176398574, -0.0427706133, 0.9421031212,
    ]
    private static let bradford: [Double] = [
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    ]
    private static let bradfordInv = Mat3.inverse(bradford)

    /**
     * Planckian locus chromaticity: Kim et al. 2002 from 1667 K; below, down to 1000 K, Krystek 1985 shifted to meet
     * Kim's at 1667 K (so the locus stays continuous and nothing above 1667 K changes).
     */
    public static func planckianXY(_ kelvin: Double) -> [Double] {
        let t = kelvin.clamped(locusLowK, locusMaxK)
        if t >= locusMinK { return kimXY(t) }
        let k = krystekUV(t)
        let k0 = krystekUV(locusMinK)
        let kimXy = kimXY(locusMinK)
        let kim = xyToUV(kimXy[0], kimXy[1])
        return uvToXY(k[0] - k0[0] + kim[0], k[1] - k0[1] + kim[1])
    }

    /** Krystek's rational approximation of the Planckian locus in CIE 1960 uv (1000 K..15000 K). */
    private static func krystekUV(_ t: Double) -> [Double] {
        let t2 = t * t
        return [
            (0.860117757 + 1.54118254e-4 * t + 1.28641212e-7 * t2) / (1 + 8.42420235e-4 * t + 7.08145163e-7 * t2),
            (0.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t2) / (1 - 2.89741816e-5 * t + 1.61456053e-7 * t2),
        ]
    }

    private static func kimXY(_ t: Double) -> [Double] {
        let t2 = t * t
        let t3 = t2 * t
        let x = t <= 4000.0
            ? -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910
            : -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390
        let x2 = x * x
        let x3 = x2 * x
        let y: Double
        if t <= 2222.0 {
            y = -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683
        } else if t <= 4000.0 {
            y = -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867
        } else {
            y = 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483
        }
        return [x, y]
    }

    public static func xyFromTempTint(_ kelvin: Double, _ tint: Double) -> [Double] {
        let mired = 1e6 / kelvin.clamped(locusLowK, locusMaxK)
        let uv = locusUV(mired)
        let n = locusNormal(mired)
        let duv = tint * tintScale
        return uvToXY(uv[0] + n[0] * duv, uv[1] + n[1] * duv)
    }

    /** Returns (kelvin, tint) for a white chromaticity. */
    public static func tempTintFromXY(_ x: Double, _ y: Double) -> [Double] {
        let p = xyToUV(x, y)
        let minMired = 1e6 / locusMaxK
        let maxMired = 1e6 / locusLowK
        func dist(_ m: Double) -> Double {
            let uv = locusUV(m)
            return hypot(p[0] - uv[0], p[1] - uv[1])
        }
        // Coarse scan, then golden-section refinement around the best sample.
        var best = minMired
        var m = minMired
        while m <= maxMired {
            if dist(m) < dist(best) { best = m }
            m += 2.0
        }
        var lo = max(best - 2.0, minMired)
        var hi = min(best + 2.0, maxMired)
        let g = 0.6180339887498949
        for _ in 0..<40 {
            let a = hi - g * (hi - lo)
            let b = lo + g * (hi - lo)
            if dist(a) < dist(b) { hi = b } else { lo = a }
        }
        let mired = (lo + hi) / 2
        let uv = locusUV(mired)
        let n = locusNormal(mired)
        let duv = (p[0] - uv[0]) * n[0] + (p[1] - uv[1]) * n[1]
        return [1e6 / mired, duv / tintScale]
    }

    /**
     * Rec.2020 -> Rec.2020 matrix that re-balances an image that was balanced
     * for [asShotXY] so that [targetXY] becomes neutral instead.
     */
    public static func rebalanceMatrix(asShotXY: [Double], targetXY: [Double]) -> [Double] {
        let lmsAsShot = Mat3.apply(bradford, xyToXYZ(asShotXY))
        let lmsTarget = Mat3.apply(bradford, xyToXYZ(targetXY))
        let scale = Mat3.diagonal(
            lmsAsShot[0] / lmsTarget[0],
            lmsAsShot[1] / lmsTarget[1],
            lmsAsShot[2] / lmsTarget[2])
        let cat = Mat3.multiply(bradfordInv, Mat3.multiply(scale, bradford))
        return Mat3.multiply(xyzToRec2020, Mat3.multiply(cat, rec2020ToXyz))
    }

    /**
     * The white balance picker: temperature and tint (clamped to the sliders' ranges) that render [rgb] neutral grey.
     * [rgb] is a patch of the decoded image (linear Rec.2020, balanced for the as-shot white), before the shader's
     * white balance. Nil when no white makes it grey (black, or a saturated colour).
     *
     * Solves rebalanceMatrix(asShot, target) x rgb = k (1, 1, 1): in Bradford LMS the matrix is a diagonal scale
     * asShot / target, so target = asShot x lms(rgb) / lms(1, 1, 1) per cone.
     */
    public static func neutralTempTint(asShotKelvin: Double, asShotTint: Double, rgb: [Double]) -> [Double]? {
        let sample = Mat3.apply(bradford, Mat3.apply(rec2020ToXyz, rgb))
        let grey = Mat3.apply(bradford, Mat3.apply(rec2020ToXyz, [1, 1, 1]))
        let asShot = Mat3.apply(bradford, xyToXYZ(xyFromTempTint(asShotKelvin, asShotTint)))
        let lms = (0..<3).map { asShot[$0] * sample[$0] / grey[$0] }
        guard lms.allSatisfy({ $0 > 0 }) else { return nil }
        let xyz = Mat3.apply(bradfordInv, lms)
        let sum = xyz[0] + xyz[1] + xyz[2]
        guard xyz[1] > 0, sum > 0 else { return nil }
        let tt = tempTintFromXY(xyz[0] / sum, xyz[1] / sum)
        return [min(max(tt[0], minKelvin), maxKelvin), min(max(tt[1], -maxTint), maxTint)]
    }

    private static func locusUV(_ mired: Double) -> [Double] {
        let xy = planckianXY(1e6 / mired)
        return xyToUV(xy[0], xy[1])
    }

    /** Unit normal of the locus pointing above it (towards green). */
    private static func locusNormal(_ mired: Double) -> [Double] {
        let a = locusUV(mired - 0.5)
        let b = locusUV(mired + 0.5)
        let du = b[0] - a[0]
        let dv = b[1] - a[1]
        let len = hypot(du, dv)
        return [-dv / len, du / len]
    }

    private static func xyToXYZ(_ xy: [Double]) -> [Double] { [xy[0] / xy[1], 1.0, (1.0 - xy[0] - xy[1]) / xy[1]] }

    // CIE 1960 UCS
    private static func xyToUV(_ x: Double, _ y: Double) -> [Double] {
        let d = -2.0 * x + 12.0 * y + 3.0
        return [4.0 * x / d, 6.0 * y / d]
    }

    private static func uvToXY(_ u: Double, _ v: Double) -> [Double] {
        let d = 2.0 * u - 8.0 * v + 4.0
        return [3.0 * u / d, 2.0 * v / d]
    }
}
