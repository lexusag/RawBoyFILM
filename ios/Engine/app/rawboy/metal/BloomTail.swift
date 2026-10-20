import Foundation

/**
 * Bloom's wide tail, scattered once per photo on its small (~64 px) copy: a round power law (alpha 2.2, knee
 * 0.6 texel) out to 7 texels (~10% of the long edge), its weights falling to zero at the rim. The develop shader
 * samples the result as a B-spline, so the glow is smooth: summed per pixel from a bilinear copy, the coarse
 * texels showed as bands and squares. The viewfinder runs the same kernel on the GPU (`tailScatterFragment`).
 * Port of BloomTail.kt.
 */
public enum BloomTail {
    public static let radius = 6

    /** Tap offsets and their weights, summing to 1. */
    private static let taps: (dx: [Int], dy: [Int], weight: [Float]) = {
        var dx: [Int] = [], dy: [Int] = [], ws: [Double] = []
        let rim2 = Double((radius + 1) * (radius + 1))
        for j in -radius...radius {
            for i in -radius...radius {
                let r2 = Double(i * i + j * j)
                let rim = 1 - r2 / rim2
                if rim <= 0 { continue }
                dx.append(i)
                dy.append(j)
                ws.append(pow(1 + r2.squareRoot() / 0.6, -2.2) * rim * rim)
            }
        }
        let sum = ws.reduce(0, +)
        return (dx, dy, ws.map { Float($0 / sum) })
    }()

    /** `img` (half-float RGB) scattered by the tail kernel, edges clamped. */
    public static func scatter(_ img: PreviewImage) -> PreviewImage {
        let w = img.width, h = img.height
        let rgb = img.pixels.map { Float(Float16(bitPattern: $0)) }
        var out = [UInt16](repeating: 0, count: w * h * 3)
        let (dx, dy, weight) = taps
        for y in 0..<h {
            for x in 0..<w {
                var r: Float = 0, g: Float = 0, b: Float = 0
                for k in 0..<weight.count {
                    let o = (min(h - 1, max(0, y + dy[k])) * w + min(w - 1, max(0, x + dx[k]))) * 3
                    r += weight[k] * rgb[o]
                    g += weight[k] * rgb[o + 1]
                    b += weight[k] * rgb[o + 2]
                }
                let o = (y * w + x) * 3
                out[o] = Float16(r).bitPattern
                out[o + 1] = Float16(g).bitPattern
                out[o + 2] = Float16(b).bitPattern
            }
        }
        return PreviewImage(width: w, height: h, pixels: out, fullWidth: img.fullWidth, fullHeight: img.fullHeight)
    }
}
