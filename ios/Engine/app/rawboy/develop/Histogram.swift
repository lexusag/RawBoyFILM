import CoreGraphics
import Foundation

/**
 * RGB histogram of a developed 8-bit image (Pro's histogram, Camera's live one), [bins] bins per channel, with the
 * share of clipped pixels: [highlights] have a channel at 255, [shadows] every channel at 1 or below. The shader's
 * clipping warning (`clipWarning` in develop.glsl) marks the same pixels. Port of develop/Histogram.kt.
 */
public struct Histogram: Sendable {
    public static let bins = 64

    public let red: [Int]
    public let green: [Int]
    public let blue: [Int]
    /** Fraction 0..1 of pixels with a channel at 255. */
    public let highlights: Float
    /** Fraction 0..1 of pixels with every channel at 1 or below. */
    public let shadows: Float
    /** Largest bin, ignoring the two end bins (a clipped spike there would flatten everything else). */
    public let peak: Int

    /** From 8-bit pixels with [bytesPerPixel] bytes each, red, green, blue first. */
    public init(pixels: UnsafeRawBufferPointer, width: Int, height: Int, bytesPerRow: Int, bytesPerPixel: Int = 4) {
        var r = [Int](repeating: 0, count: Self.bins)
        var g = r
        var b = r
        var high = 0
        var low = 0
        for y in 0..<height {
            for x in 0..<width {
                let o = y * bytesPerRow + x * bytesPerPixel
                let pr = Int(pixels[o]), pg = Int(pixels[o + 1]), pb = Int(pixels[o + 2])
                r[pr * Self.bins / 256] += 1
                g[pg * Self.bins / 256] += 1
                b[pb * Self.bins / 256] += 1
                let m = max(pr, pg, pb)
                if m == 255 { high += 1 } else if m <= 1 { low += 1 }
            }
        }
        let n = Float(max(1, width * height))
        red = r
        green = g
        blue = b
        highlights = Float(high) / n
        shadows = Float(low) / n
        let inner = { (a: [Int]) in a[1..<(Self.bins - 1)].max() ?? 0 }
        peak = max(1, inner(r), inner(g), inner(b))
    }

    /** From an 8-bit RGB(X) image, as ThumbnailRenderer makes them; nil for other layouts. */
    public init?(image: CGImage) {
        guard image.bitsPerComponent == 8, image.bitsPerPixel == 32 || image.bitsPerPixel == 24,
              let data = image.dataProvider?.data else { return nil }
        let bytes = data as Data
        self = bytes.withUnsafeBytes {
            Histogram(pixels: $0, width: image.width, height: image.height, bytesPerRow: image.bytesPerRow,
                      bytesPerPixel: image.bitsPerPixel / 8)
        }
    }
}
