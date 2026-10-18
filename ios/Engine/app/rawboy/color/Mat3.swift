import Foundation

/** Minimal row-major 3x3 matrix helpers. */
public enum Mat3 {
    public static func multiply(_ a: [Double], _ b: [Double]) -> [Double] {
        var r = [Double](repeating: 0, count: 9)
        for i in 0..<3 {
            for j in 0..<3 {
                var s = 0.0
                for k in 0..<3 { s += a[i * 3 + k] * b[k * 3 + j] }
                r[i * 3 + j] = s
            }
        }
        return r
    }

    public static func apply(_ m: [Double], _ v: [Double]) -> [Double] {
        [
            m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
            m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
            m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
        ]
    }

    public static func diagonal(_ a: Double, _ b: Double, _ c: Double) -> [Double] { [a, 0, 0, 0, b, 0, 0, 0, c] }

    public static func inverse(_ m: [Double]) -> [Double] {
        let det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])
        precondition(det != 0, "singular matrix")
        let i = 1.0 / det
        return [
            (m[4] * m[8] - m[5] * m[7]) * i, (m[2] * m[7] - m[1] * m[8]) * i, (m[1] * m[5] - m[2] * m[4]) * i,
            (m[5] * m[6] - m[3] * m[8]) * i, (m[0] * m[8] - m[2] * m[6]) * i, (m[2] * m[3] - m[0] * m[5]) * i,
            (m[3] * m[7] - m[4] * m[6]) * i, (m[1] * m[6] - m[0] * m[7]) * i, (m[0] * m[4] - m[1] * m[3]) * i,
        ]
    }

    /** Row-major double -> column-major float, as the shader's mat3 uniforms expect. */
    public static func toGlColumnMajor(_ m: [Double]) -> [Float] {
        (0..<9).map { idx in Float(m[(idx % 3) * 3 + idx / 3]) }
    }
}
