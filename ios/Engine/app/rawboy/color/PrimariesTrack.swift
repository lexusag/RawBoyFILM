import Foundation

/** The RGB primaries sliders, for [PrimariesTrack]. */
public enum PrimariesSlider: Sendable {
    case redHue, redPurity, greenHue, greenPurity, blueHue, bluePurity, tintHue, tintPurity
}

/**
 * Track colours for the RGB primaries sliders (port of color/PrimariesTrack.kt): what a sample patch turns into at
 * each slider position, through the same [Primaries.fromParams] the develop uses, with the other primaries sliders
 * at their current values. Red, green and blue take a fairly saturated colour of their own hue as their sample (a
 * pure sRGB primary would clip, so more purity wouldn't show), the tint sliders neutral grey. Brightness is
 * normalised (max channel = 1) so only hue and purity show.
 *
 * Exception: the Tint hue track is drawn at full Tint purity, since at 0 it would be plain grey.
 */
public enum PrimariesTrack {
    // Linear sRGB <-> Rec.2020 (row-major).
    private static let srgbToRec2020: [Double] = [
        0.6274040, 0.3292820, 0.0433136,
        0.0690970, 0.9195400, 0.0113612,
        0.0163916, 0.0880132, 0.8955950,
    ]
    private static let rec2020ToSrgb: [Double] = [
        1.6604910, -0.5876411, -0.0728499,
        -0.1245505, 1.1328999, -0.0083494,
        -0.0181508, -0.1005789, 1.1187297,
    ]

    /** Samples in linear Rec.2020: (1, 0.18, 0.18) in linear sRGB and its permutations; grey. */
    private static let sampleOther = 0.18
    private static let red = Mat3.apply(srgbToRec2020, [1, sampleOther, sampleOther])
    private static let green = Mat3.apply(srgbToRec2020, [sampleOther, 1, sampleOther])
    private static let blue = Mat3.apply(srgbToRec2020, [sampleOther, sampleOther, 1])
    private static let grey: [Double] = [1, 1, 1]

    /** [steps] sRGB-encoded colours (0..1) across [range], left to right. */
    public static func colors(_ p: DevelopParams, _ slider: PrimariesSlider, _ range: ClosedRange<Float>,
                              steps: Int = 13) -> [[Double]] {
        let sample: [Double]
        switch slider {
        case .redHue, .redPurity: sample = red
        case .greenHue, .greenPurity: sample = green
        case .blueHue, .bluePurity: sample = blue
        case .tintHue, .tintPurity: sample = grey
        }
        return (0..<steps).map { i in
            let v = range.lowerBound + (range.upperBound - range.lowerBound) * Float(i) / Float(steps - 1)
            let m = Primaries.fromParams(withValue(p, slider, v))
            return encode(Mat3.apply(rec2020ToSrgb, Mat3.apply(m, sample)))
        }
    }

    private static func withValue(_ p: DevelopParams, _ slider: PrimariesSlider, _ v: Float) -> DevelopParams {
        var q = p
        switch slider {
        case .redHue: q.redHue = v
        case .redPurity: q.redPurity = v
        case .greenHue: q.greenHue = v
        case .greenPurity: q.greenPurity = v
        case .blueHue: q.blueHue = v
        case .bluePurity: q.bluePurity = v
        case .tintHue: q.tintHue = v; q.tintPurity = 100
        case .tintPurity: q.tintPurity = v
        }
        return q
    }

    /** Linear sRGB -> normalised, clipped, sRGB-encoded. */
    private static func encode(_ rgb: [Double]) -> [Double] {
        let peak = max(rgb[0], rgb[1], rgb[2], 1e-6)
        return rgb.map { v in
            let c = min(1, max(0, v / peak))
            return c <= 0.0031308 ? 12.92 * c : 1.055 * pow(c, 1 / 2.4) - 0.055
        }
    }
}
