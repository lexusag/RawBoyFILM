import Foundation

/** One curve point: [x] input, [y] output, both 0..1 of the display-encoded (sRGB) value. */
public struct CurvePoint: Equatable, Sendable {
    public var x: Float
    public var y: Float

    public init(_ x: Float, _ y: Float) {
        self.x = x
        self.y = y
    }
}

/** Which curve the Curves editor shows; [key] is the name in preset files. */
public enum CurveChannel: String, CaseIterable, Sendable {
    case rgb, red, green, blue

    public var key: String { rawValue }

    public var label: String {
        switch self {
        case .rgb: "RGB"
        case .red: "Red"
        case .green: "Green"
        case .blue: "Blue"
        }
    }
}

/**
 * Point curves on the final display-encoded (sRGB) image, as in Lightroom (port of develop/ToneCurve.kt).
 * The [rgb] curve applies to all three channels first, then each channel's own curve. Points are sorted by x;
 * the curve between them is a monotone cubic (Fritsch-Carlson), so it never overshoots its points, and it is
 * flat beyond the first and last point.
 */
public struct ToneCurves: Equatable, Sendable {
    public var rgb: [CurvePoint] = ToneCurves.identity
    public var red: [CurvePoint] = ToneCurves.identity
    public var green: [CurvePoint] = ToneCurves.identity
    public var blue: [CurvePoint] = ToneCurves.identity

    public init() {}

    public init(rgb: [CurvePoint], red: [CurvePoint], green: [CurvePoint], blue: [CurvePoint]) {
        self.rgb = rgb
        self.red = red
        self.green = green
        self.blue = blue
    }

    public static let identity = [CurvePoint(0, 0), CurvePoint(1, 1)]
    public static let none = ToneCurves()

    /** Samples per channel in the shader's `uCurve` table (CURVE_SIZE in develop.glsl). */
    public static let size = 33
    public static let maxPoints = 16
    /** Closest two points may sit along the input axis. */
    public static let minGap: Float = 0.02

    public subscript(channel: CurveChannel) -> [CurvePoint] {
        get {
            switch channel {
            case .rgb: rgb
            case .red: red
            case .green: green
            case .blue: blue
            }
        }
        set {
            switch channel {
            case .rgb: rgb = newValue
            case .red: red = newValue
            case .green: green = newValue
            case .blue: blue = newValue
            }
        }
    }

    public var isIdentity: Bool { CurveChannel.allCases.allSatisfy { Self.isIdentity(self[$0]) } }

    /** Edited positions are kept to 1/1000, so preset files stay readable. */
    private static func snap(_ v: Float) -> Float { (v * 1000).rounded() / 1000 }

    /** Every point on the diagonal: the curve is the straight identity line. */
    public static func isIdentity(_ points: [CurvePoint]) -> Bool { points.allSatisfy { abs($0.x - $0.y) < 1e-6 } }

    /**
     * The shader's `uCurve` table: [size] rgb triples, each channel's curve after the RGB one, or nil when every
     * curve is the identity (`uCurveOn` 0).
     */
    public func table() -> [Float]? {
        if isIdentity { return nil }
        let mm = Self.tangents(rgb), mr = Self.tangents(red), mg = Self.tangents(green), mb = Self.tangents(blue)
        var out = [Float](repeating: 0, count: Self.size * 3)
        for i in 0..<Self.size {
            let v = Self.evaluate(rgb, Float(i) / Float(Self.size - 1), mm)
            out[i * 3] = Self.evaluate(red, v, mr)
            out[i * 3 + 1] = Self.evaluate(green, v, mg)
            out[i * 3 + 2] = Self.evaluate(blue, v, mb)
        }
        return out
    }

    /** Fritsch-Carlson tangents: zero at local extrema, limited so each segment stays monotone. */
    static func tangents(_ p: [CurvePoint]) -> [Float] {
        let n = p.count
        let d = (0..<(n - 1)).map { (p[$0 + 1].y - p[$0].y) / (p[$0 + 1].x - p[$0].x) }
        if n == 2 { return [d[0], d[0]] }
        var m = [Float](repeating: 0, count: n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for i in 1..<(n - 1) { m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2 }
        for i in 0..<(n - 1) {
            if d[i] == 0 {
                m[i] = 0
                m[i + 1] = 0
                continue
            }
            let a = m[i] / d[i], b = m[i + 1] / d[i]
            let s = a * a + b * b
            if s > 9 {
                let t = 3 / s.squareRoot()
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
        return m
    }

    /** The curve through [points] at input [x] (0..1), clamped to 0..1. */
    public static func evaluate(_ points: [CurvePoint], _ x: Float, _ tangents: [Float]? = nil) -> Float {
        let n = points.count
        if x <= points[0].x { return min(max(points[0].y, 0), 1) }
        if x >= points[n - 1].x { return min(max(points[n - 1].y, 0), 1) }
        let m = tangents ?? Self.tangents(points)
        var i = 0
        while x > points[i + 1].x { i += 1 }
        let x0 = points[i].x, y0 = points[i].y, x1 = points[i + 1].x, y1 = points[i + 1].y
        let h = x1 - x0
        let t = (x - x0) / h
        let t2 = t * t, t3 = t * t * t
        let y = (2 * t3 - 3 * t2 + 1) * y0 + (t3 - 2 * t2 + t) * h * m[i] + (-2 * t3 + 3 * t2) * y1
            + (t3 - t2) * h * m[i + 1]
        return min(max(y, 0), 1)
    }

    /** A channel's curve sampled at [count] evenly spaced inputs (for drawing it). */
    public static func sample(_ points: [CurvePoint], count: Int) -> [Float] {
        let m = tangents(points)
        return (0..<count).map { evaluate(points, Float($0) / Float(count - 1), m) }
    }

    /** Index of the point within [radius] of ([x], [y]), the closest one, or nil. */
    public static func nearest(_ points: [CurvePoint], _ x: Float, _ y: Float, radius: Float) -> Int? {
        var best: Int?
        var bestD = radius * radius
        for (i, p) in points.enumerated() {
            let d = (p.x - x) * (p.x - x) + (p.y - y) * (p.y - y)
            if d <= bestD {
                best = i
                bestD = d
            }
        }
        return best
    }

    /** [points] with a new point at ([x], [y]) and its index, or nil when full or too close to another point. */
    public static func add(_ points: [CurvePoint], _ x: Float, _ y: Float) -> (points: [CurvePoint], index: Int)? {
        if points.count >= maxPoints { return nil }
        let px = snap(min(max(x, 0), 1))
        if points.contains(where: { abs($0.x - px) < minGap }) { return nil }
        // Outside the first and last point: move those instead.
        guard let index = points.firstIndex(where: { $0.x > px }), index > 0 else { return nil }
        var out = points
        out.insert(CurvePoint(px, snap(min(max(y, 0), 1))), at: index)
        return (out, index)
    }

    /** [points] with point [i] moved to ([x], [y]), kept between its neighbours. */
    public static func move(_ points: [CurvePoint], _ i: Int, _ x: Float, _ y: Float) -> [CurvePoint] {
        let lo = i > 0 ? points[i - 1].x + minGap : 0
        let hi = i < points.count - 1 ? points[i + 1].x - minGap : 1
        var out = points
        out[i] = CurvePoint(min(max(snap(x), lo), hi), snap(min(max(y, 0), 1)))
        return out
    }

    /** [points] without point [i]; the first and last point stay (they can only be moved). */
    public static func remove(_ points: [CurvePoint], _ i: Int) -> [CurvePoint] {
        if i <= 0 || i >= points.count - 1 { return points }
        var out = points
        out.remove(at: i)
        return out
    }

    /** For preset files: each channel a list of [x, y] pairs. */
    public func toJson() -> [String: Any] {
        var o: [String: Any] = [:]
        for c in CurveChannel.allCases { o[c.key] = self[c].map { [Masks.num($0.x), Masks.num($0.y)] } }
        return o
    }

    /**
     * Curves from a preset file: each channel a list of [x, y] pairs. Channels that are missing or malformed are
     * the identity; nil when [value] isn't an object at all.
     */
    public static func parse(_ value: Any) -> ToneCurves? {
        guard let o = value as? [String: Any] else { return nil }
        func channel(_ c: CurveChannel) -> [CurvePoint] {
            guard let a = o[c.key] as? [Any], a.count >= 2, a.count <= maxPoints else { return identity }
            var points: [CurvePoint] = []
            for p in a {
                guard let p = p as? [Any], p.count == 2,
                      let x = (p[0] as? NSNumber)?.floatValue, let y = (p[1] as? NSNumber)?.floatValue,
                      x.isFinite, y.isFinite else { return identity }
                let cx = min(max(x, 0), 1)
                if let last = points.last, cx <= last.x { return identity }
                points.append(CurvePoint(cx, min(max(y, 0), 1)))
            }
            return points
        }
        return ToneCurves(rgb: channel(.rgb), red: channel(.red), green: channel(.green), blue: channel(.blue))
    }
}
