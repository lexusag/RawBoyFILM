import Foundation

/**
 * What the auto decisions need to know about a photo. Luminances are linear scene
 * light as decoded (1.0 = sensor clip, as-shot white balance); percentiles are log2.
 */
public struct SceneStats: Sendable {
    /** Centre-weighted geometric mean luminance. */
    public let key: Float
    public let p1: Float
    public let p5: Float
    public let p50: Float
    public let p98: Float
    public let p99: Float
    /** Fraction of pixels with any channel at sensor clip. */
    public let clipped: Float
    /** Mean saturation of the display-encoded image, 0..1. */
    public let chroma: Float
    /** Share of the frame that is clearly (saturation > 0.15) green/blue, and warm (red to yellow). */
    public let greenBlue: Float
    public let warm: Float
    /** Share of the frame covered by a face (0 = none found). */
    public let faceArea: Float
    /** Mean linear luminance of skin, 0 if there is none. */
    public let skinLuminance: Float
    public let iso: Float
    /** log2-luminance histogram, SceneAnalyzer.binsPerEv bins per EV from SceneAnalyzer.minLog. */
    let histogram: [Int]

    /** p99 - p1 in EV. */
    public var dynamicRange: Float { p99 - p1 }
    public var hasFace: Bool { faceArea > 0 }

    /** Fraction of pixels whose log2 luminance is at least [log2Luminance]. */
    public func fractionAbove(_ log2Luminance: Float) -> Float {
        let from = SceneAnalyzer.bin(log2Luminance)
        var count = 0
        var total = 0
        for (i, n) in histogram.enumerated() {
            total += n
            if i >= from { count += n }
        }
        return total > 0 ? Float(count) / Float(total) : 0
    }
}

/** Reads the 512 px whole-image copy on the CPU; cheap (~260k pixels). */
public enum SceneAnalyzer {
    public static let minLog: Float = -20
    public static let maxLog: Float = 1
    public static let binsPerEv = 16
    private static let bins = Int((maxLog - minLog) * Float(binsPerEv)) + 1

    static func bin(_ log2Luminance: Float) -> Int { Int((log2Luminance.clamped(minLog, maxLog) - minLog) * Float(binsPerEv)) }

    // Rec.2020 luminance weights.
    private static let kr: Float = 0.2627
    private static let kg: Float = 0.6780
    private static let kb: Float = 0.0593

    /**
     * [meter]: meter only that box (.centre, a tap's .spot): `key` and `p98` (which set exposure) come from it,
     * uniformly weighted; the other stats stay whole-frame. Nil: the centre-weighted whole frame.
     */
    public static func analyze(_ base: PreviewImage, skin: SkinMask?, iso: Float, meter: MeterArea? = nil) -> SceneStats {
        let w = base.width
        let h = base.height
        let px = base.pixels
        var histogram = [Int](repeating: 0, count: bins)
        var weightedLog = 0.0
        var weightSum = 0.0
        var clipped = 0
        var chromaSum = 0.0
        var greenBlue = 0
        var warm = 0
        var skinSum = 0.0
        var skinCount = 0
        // The metered box.
        var centreHistogram: [Int]? = meter != nil ? [Int](repeating: 0, count: bins) : nil
        var centreLog = 0.0
        var centreCount = 0
        let (cx0, cx1, cy0, cy1) = meter?.box(w, h) ?? (x0: 0, x1: w, y0: 0, y1: h)
        let cx = Float(w - 1) / 2
        let cy = Float(h - 1) / 2
        let useSkin = skin?.hasSkin == true

        for y in 0..<h {
            for x in 0..<w {
                let i = (y * w + x) * 3
                let r = max(Float(Float16(bitPattern: px[i])), 0)
                let g = max(Float(Float16(bitPattern: px[i + 1])), 0)
                let b = max(Float(Float16(bitPattern: px[i + 2])), 0)
                let lum = max(kr * r + kg * g + kb * b, 1e-6)
                let logLum = log2f(lum)
                histogram[bin(logLum)] += 1

                // Centre-weighted metering: the middle counts 2.5x the corners.
                let dx = (Float(x) - cx) / cx
                let dy = (Float(y) - cy) / cy
                let weight = 1.0 + 1.5 * Double(max(0, 1 - (dx * dx + dy * dy) / 2))
                weightedLog += weight * Double(logLum)
                weightSum += weight
                if centreHistogram != nil && (cx0..<cx1).contains(x) && (cy0..<cy1).contains(y) {
                    centreHistogram![bin(logLum)] += 1
                    centreLog += Double(logLum)
                    centreCount += 1
                }
                if max(r, max(g, b)) >= 0.98 { clipped += 1 }

                // Colour judged on a display-like encoding, where saturation means what it looks like.
                let er = powf(r, 1 / 2.2)
                let eg = powf(g, 1 / 2.2)
                let eb = powf(b, 1 / 2.2)
                let hi = max(er, max(eg, eb))
                let lo = min(er, min(eg, eb))
                let sat = hi > 1e-4 ? (hi - lo) / hi : 0
                chromaSum += Double(sat)
                if sat > 0.15 && hi > 0.1 {
                    let hue = hueDegrees(er, eg, eb)
                    if (75...260).contains(hue) {
                        greenBlue += 1
                    } else if (0...60).contains(hue) || (330...360).contains(hue) {
                        warm += 1
                    }
                }

                if useSkin, let skin {
                    let mx = min(skin.width - 1, x * skin.width / w)
                    let my = min(skin.height - 1, y * skin.height / h)
                    if skin.data[my * skin.width + mx] > 0.5 {
                        skinSum += Double(lum)
                        skinCount += 1
                    }
                }
            }
        }

        let n = w * h
        func percentile(_ p: Float, _ hist: [Int], _ count: Int) -> Float {
            let target = p * Float(count)
            var acc = 0
            for bin in 0..<bins {
                acc += hist[bin]
                if Float(acc) >= target { return minLog + (Float(bin) + 0.5) / Float(binsPerEv) }
            }
            return maxLog
        }
        let metered = centreHistogram != nil && centreCount > 0
        var faceArea: Float = 0
        if let skin, skin.faceDiameterPx > 0 {
            let radius = skin.faceDiameterPx / 2
            faceArea = (Float.pi * radius * radius / (Float(base.fullWidth) * Float(base.fullHeight))).clamped(0, 1)
        }
        return SceneStats(
            key: Float(pow(2.0, metered ? centreLog / Double(centreCount) : weightedLog / weightSum)),
            p1: percentile(0.01, histogram, n),
            p5: percentile(0.05, histogram, n),
            p50: percentile(0.50, histogram, n),
            p98: metered ? percentile(0.98, centreHistogram!, centreCount) : percentile(0.98, histogram, n),
            p99: percentile(0.99, histogram, n),
            clipped: Float(clipped) / Float(n),
            chroma: Float(chromaSum / Double(n)),
            greenBlue: Float(greenBlue) / Float(n),
            warm: Float(warm) / Float(n),
            faceArea: faceArea,
            skinLuminance: skinCount > 0 ? Float(skinSum / Double(skinCount)) : 0,
            iso: iso,
            histogram: histogram)
    }

    private static func hueDegrees(_ r: Float, _ g: Float, _ b: Float) -> Float {
        // Hue angle in an opponent space (like HSV hue, but smooth).
        let a = r - (g + b) / 2
        let bb = (g - b) * 3.0.squareRoot().float / 2
        let deg = Float(Double(atan2(bb, a)) * 180 / .pi)
        return deg < 0 ? deg + 360 : deg
    }
}

private extension Double {
    var float: Float { Float(self) }
}
