import Foundation

/** Small image filters shared by skin detection and brush masks. */
enum Filters {
    /** Mean over a (2r+1)^2 window via an integral image, clamped at the borders. */
    static func box(_ src: [Float], _ w: Int, _ h: Int, _ r: Int) -> [Float] {
        var sat = [Double](repeating: 0, count: (w + 1) * (h + 1))
        for y in 0..<h {
            var row = 0.0
            for x in 0..<w {
                row += Double(src[y * w + x])
                sat[(y + 1) * (w + 1) + x + 1] = sat[y * (w + 1) + x + 1] + row
            }
        }
        var out = [Float](repeating: 0, count: w * h)
        for y in 0..<h {
            let y0 = max(0, y - r)
            let y1 = min(h, y + r + 1)
            for x in 0..<w {
                let x0 = max(0, x - r)
                let x1 = min(w, x + r + 1)
                let sum = sat[y1 * (w + 1) + x1] - sat[y0 * (w + 1) + x1] - sat[y1 * (w + 1) + x0] + sat[y0 * (w + 1) + x0]
                out[y * w + x] = Float(sum / Double((y1 - y0) * (x1 - x0)))
            }
        }
        return out
    }
}
