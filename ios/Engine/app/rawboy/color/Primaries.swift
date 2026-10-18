import Foundation

/**
 * RGB primaries adjustment, after darktable's "rgb primaries" module (reimplemented
 * from its description; darktable is GPL): each Rec.2020 primary is rotated in hue
 * and pushed/pulled in purity around the white point in CIE xy, and the white point
 * itself can be tinted. The image's RGB values are then read as if they were in that
 * custom space and converted back to Rec.2020 - one 3x3 matrix, applied in
 * scene-linear light before tone mapping.
 */
public enum Primaries {
    // Rec.2020 primaries and D65 white, CIE xy.
    private static let primaries: [[Double]] = [[0.708, 0.292], [0.170, 0.797], [0.131, 0.046]]
    private static let white: [Double] = [0.3127, 0.3290]

    private static let xyzToRec2020: [Double] = [
        1.7166511880, -0.3556707838, -0.2533662814,
        -0.6666843518, 1.6164812366, 0.0157685458,
        0.0176398574, -0.0427706133, 0.9421031212,
    ]

    /** Slider values -> primaries matrix (identity when untouched). */
    public static func fromParams(_ p: DevelopParams) -> [Double] {
        let hues = [p.redHue, p.greenHue, p.blueHue].map { Double($0) * .pi / 180 }
        // -100..100 -> purity x0.71 .. x1.41 (exponential, so +/- feel symmetric). Rec.2020's
        // primaries sit near the spectral locus, so small changes already move a lot.
        let purities = [p.redPurity, p.greenPurity, p.bluePurity].map { pow(2.0, Double($0) / 200) }
        let tintPurity = Double(p.tintPurity) / 100 * 0.1  // full slider = 10% towards the gamut edge
        if isIdentity(hues: hues, purities: purities, tintPurity: tintPurity) { return Mat3.diagonal(1, 1, 1) }
        return matrix(hues: hues, purities: purities, tintHue: Double(p.tintHue) * .pi / 180, tintPurity: tintPurity)
    }

    /**
     * - hues: rotation per primary (R, G, B) in radians; + turns red towards yellow,
     *   green towards cyan, blue towards magenta.
     * - purities: per primary, 1 = unchanged, <1 less pure, >1 more pure.
     * - tintHue: direction of the white-point tint (radians, 0 = towards red).
     * - tintPurity: 0 = neutral white.
     * Returns the row-major Rec.2020 -> Rec.2020 matrix (identity for the defaults).
     */
    public static func matrix(hues: [Double], purities: [Double], tintHue: Double, tintPurity: Double) -> [Double] {
        let custom = (0..<3).map { rotateAndScale($0, scale: purities[$0], rotation: hues[$0]) }
        let w = rotateAndScale(0, scale: tintPurity, rotation: tintHue)
        return Mat3.multiply(xyzToRec2020, rgbToXyz(custom, w))
    }

    public static func isIdentity(hues: [Double], purities: [Double], tintPurity: Double) -> Bool {
        hues.allSatisfy { $0 == 0 } && purities.allSatisfy { $0 == 1 } && tintPurity == 0
    }

    /**
     * Rotates primary [index] around the white point by [rotation] and places it at
     * [scale] x the distance to the edge of the Rec.2020 triangle in that direction
     * (so scale 1 with no rotation gives back the primary itself).
     */
    private static func rotateAndScale(_ index: Int, scale: Double, rotation: Double) -> [Double] {
        let p = primaries[index]
        let angle = atan2(p[1] - white[1], p[0] - white[0]) + rotation
        let dx = cos(angle)
        let dy = sin(angle)
        let edge = distanceToGamutEdge(dx, dy)
        return [white[0] + scale * edge * dx, white[1] + scale * edge * dy]
    }

    /** Distance from the white point along (dx, dy) to the Rec.2020 triangle's edge. */
    private static func distanceToGamutEdge(_ dx: Double, _ dy: Double) -> Double {
        var best = Double.greatestFiniteMagnitude
        for i in 0..<3 {
            let a = primaries[i]
            let b = primaries[(i + 1) % 3]
            // Solve white + t*(dx,dy) = a + u*(b-a) for t >= 0, 0 <= u <= 1.
            let ex = b[0] - a[0]
            let ey = b[1] - a[1]
            let det = dx * (-ey) - dy * (-ex)
            if det == 0 { continue }
            let wx = a[0] - white[0]
            let wy = a[1] - white[1]
            let t = (wx * (-ey) - wy * (-ex)) / det
            let u = (dx * wy - dy * wx) / det
            if t >= 0 && u >= -1e-9 && u <= 1 + 1e-9 && t < best { best = t }
        }
        return best
    }

    /** RGB -> XYZ for the given primaries and white (white maps to Y = 1). */
    private static func rgbToXyz(_ primaries: [[Double]], _ white: [Double]) -> [Double] {
        func xyz(_ xy: [Double]) -> [Double] { [xy[0] / xy[1], 1.0, (1 - xy[0] - xy[1]) / xy[1]] }
        let r = xyz(primaries[0])
        let g = xyz(primaries[1])
        let b = xyz(primaries[2])
        let p = [r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2]]
        let s = Mat3.apply(Mat3.inverse(p), xyz(white))
        return Mat3.multiply(p, Mat3.diagonal(s[0], s[1], s[2]))
    }
}
