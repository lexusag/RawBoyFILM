import Foundation

/**
 * Auto mask guide: per-pixel features of the photo at the raster size (log2 luminance and two exposure-independent
 * chroma terms), lightly blurred so sensor noise doesn't break strokes up. Mirrors web's BrushRaster.ts.
 */
public final class BrushGuide: @unchecked Sendable {
    let lum: [Float]
    let red: [Float]
    let blue: [Float]

    init(lum: [Float], red: [Float], blue: [Float]) {
        self.lum = lum
        self.red = red
        self.blue = blue
    }

    /**
     * Linear offsets (1.0 = sensor clip): the luminance floor keeps deep-shadow noise from reading as big EV
     * steps, the chroma one keeps near-black noise from reading as strong colour.
     */
    private static let lumFloor: Float = 0.002
    private static let chromaEps: Float = 0.01
    /** Box blur radius of the guide (raster pixels), against sensor noise. */
    private static let guideBlur = 2

    public static func of(_ image: PreviewImage) -> BrushGuide {
        let n = image.width * image.height
        var lum = [Float](repeating: 0, count: n)
        var red = [Float](repeating: 0, count: n)
        var blue = [Float](repeating: 0, count: n)
        let px = image.pixels
        for i in 0..<n {
            let r = max(Float(Float16(bitPattern: px[i * 3])), 0)
            let g = max(Float(Float16(bitPattern: px[i * 3 + 1])), 0)
            let b = max(Float(Float16(bitPattern: px[i * 3 + 2])), 0)
            let y = 0.2627 * r + 0.678 * g + 0.0593 * b  // Rec.2020 luma
            lum[i] = log2(y + lumFloor)
            red[i] = log2((r + chromaEps) / (y + chromaEps))
            blue[i] = log2((b + chromaEps) / (y + chromaEps))
        }
        let w = image.width
        let h = image.height
        return BrushGuide(lum: Filters.box(lum, w, h, guideBlur), red: Filters.box(red, w, h, guideBlur),
                          blue: Filters.box(blue, w, h, guideBlur))
    }
}

/**
 * One layer's painted mask over the whole image (display orientation, row 0 = top), at the size of the preview copy.
 * Two channels, applied stroke by stroke in order:
 * - paint: what strokes added (the shader takes the max of this and the gradient);
 * - erase: how much of the layer's gradient strokes took away.
 * A brush dab of weight w: paint += (1 - paint) w, erase *= 1 - w; an erase dab the other way round.
 *
 * [sync] runs on the UI thread, [upload] on render threads (preview, export); both lock the raster, so an upload
 * never sees half a stroke. Mirrors web's BrushRaster.ts (same dabs, so masks match across apps).
 */
public final class BrushRaster: @unchecked Sendable {
    public let width: Int
    public let height: Int
    private let guide: () -> BrushGuide
    private let lock = NSRecursiveLock()

    private var paint: [Float]
    private var erase: [Float]
    /** RG8 copy for the GPU. */
    private var rg: [UInt8]

    public private(set) var version = 0
    private var history: [(Int, PixelRect)] = []
    /** Strokes drawn so far (by id) and how many points of the last one. */
    private var drawn: [String] = []
    private var drawnPoints = 0
    /** Walk state of the last stroke: last point (raster px) and distance since its last dab. */
    private var walkX: Float = 0
    private var walkY: Float = 0
    private var walkSince: Float = 0
    private var dirty: [Int]?  // x0, y0, x1, y1

    /** Dab spacing as a fraction of the radius. */
    private static let spacing: Float = 0.15
    /** Auto mask tolerances: luminance in EV, chroma in log2 ratios. */
    private static let sigmaL: Float = 0.6
    private static let sigmaC: Float = 0.25
    /** Changes remembered for partial texture uploads. */
    private static let historySize = 16

    public init(width: Int, height: Int, guide: @escaping () -> BrushGuide) {
        self.width = width
        self.height = height
        self.guide = guide
        paint = [Float](repeating: 0, count: width * height)
        erase = [Float](repeating: 0, count: width * height)
        rg = [UInt8](repeating: 0, count: width * height * 2)
    }

    /** Brings the raster up to [strokes]: only new dabs when the list grew, a full redraw otherwise. */
    public func sync(_ strokes: [BrushStroke]) {
        lock.lock()
        defer { lock.unlock() }
        let extends = strokes.count >= drawn.count &&
            drawn.indices.allSatisfy { strokes[$0].id == drawn[$0] } &&
            (drawn.isEmpty || strokes[drawn.count - 1].points.count >= drawnPoints)
        if !extends { clear() }
        let start = drawn.isEmpty ? 0 : drawn.count - 1
        for i in start..<max(start, strokes.count) {
            let s = strokes[i]
            let resume = i == drawn.count - 1 ? drawnPoints : 0
            drawStroke(s, from: resume)
            if i >= drawn.count { drawn.append(s.id) }
            drawnPoints = s.points.count
        }
        commit()
    }

    /** What changed after [uploaded]: a rectangle (the whole raster after a redraw or too far back), or nil. */
    public func pendingRect(_ uploaded: Int) -> PixelRect? {
        lock.lock()
        defer { lock.unlock() }
        if uploaded == version { return nil }
        let since = history.filter { $0.0 > uploaded }
        if since.isEmpty || since[0].0 != uploaded + 1 { return PixelRect(x: 0, y: 0, width: width, height: height) }
        var x0 = Int.max, y0 = Int.max, x1 = Int.min, y1 = Int.min
        for (_, r) in since {
            x0 = min(x0, r.x); y0 = min(y0, r.y)
            x1 = max(x1, r.x + r.width); y1 = max(y1, r.y + r.height)
        }
        return PixelRect(x: x0, y: y0, width: x1 - x0, height: y1 - y0)
    }

    /**
     * Hands the RG8 pixels and what changed since [uploaded] ([fresh]: nothing uploaded yet, send everything) to
     * [block], under the raster's lock. Returns the version now on the GPU.
     */
    public func upload(_ uploaded: Int, fresh: Bool, _ block: (UnsafeRawPointer, PixelRect) -> Void) -> Int {
        lock.lock()
        defer { lock.unlock() }
        let r: PixelRect
        if fresh {
            r = PixelRect(x: 0, y: 0, width: width, height: height)
        } else if let p = pendingRect(uploaded) {
            r = p
        } else {
            return version
        }
        rg.withUnsafeBytes { block($0.baseAddress!, r) }
        return version
    }

    /** Paint and erase at pixel [i], 0..255 (tests). */
    public func valueAt(_ i: Int) -> (Int, Int) {
        lock.lock()
        defer { lock.unlock() }
        return (Int(rg[i * 2]), Int(rg[i * 2 + 1]))
    }

    private func clear() {
        for i in paint.indices { paint[i] = 0; erase[i] = 0 }
        drawn.removeAll()
        drawnPoints = 0
        dirty = [0, 0, width, height]
    }

    /** Dabs of [s] from point index [from] (0 = from its start). */
    private func drawStroke(_ s: BrushStroke, from: Int) {
        let long = Float(max(width, height))
        let radius = max(0.75, min(s.radius, Masks.maxBrushRadius) * long)
        let spacing = max(1, radius * Self.spacing)
        let pts = s.points
        var i = from
        if i == 0 && pts.count >= 2 {
            let x = pts[0] * Float(width)
            let y = pts[1] * Float(height)
            dab(s, x, y, radius)
            walkX = x; walkY = y; walkSince = 0
            i = 2
        }
        while i + 1 < pts.count {
            let x = pts[i] * Float(width)
            let y = pts[i + 1] * Float(height)
            let len = hypot(x - walkX, y - walkY)
            var t = spacing - walkSince
            while t <= len {
                let f = t / len
                dab(s, walkX + (x - walkX) * f, walkY + (y - walkY) * f, radius)
                t += spacing
            }
            walkSince = len - (t - spacing)
            walkX = x; walkY = y
            i += 2
        }
    }

    private func dab(_ s: BrushStroke, _ cx: Float, _ cy: Float, _ radius: Float) {
        let w = width
        let h = height
        let x0 = max(0, Int(floor(cx - radius)))
        let y0 = max(0, Int(floor(cy - radius)))
        let x1 = min(w - 1, Int(ceil(cx + radius)))
        let y1 = min(h - 1, Int(ceil(cy + radius)))
        if x0 > x1 || y0 > y1 { return }
        let inner = radius * (s.hardness / 100) * 0.95
        let flow = (s.flow / 100).clamped(0.01, 1)
        let g = s.auto ? guide() : nil
        var cl: Float = 0, cr: Float = 0, cb: Float = 0
        if let g {
            // Colour under the centre, averaged over 3x3.
            var n: Float = 0
            let ix = Int(cx.rounded()).clamped(0, w - 1)
            let iy = Int(cy.rounded()).clamped(0, h - 1)
            for y in max(0, iy - 1)...min(h - 1, iy + 1) {
                for x in max(0, ix - 1)...min(w - 1, ix + 1) {
                    let k = y * w + x
                    cl += g.lum[k]; cr += g.red[k]; cb += g.blue[k]
                    n += 1
                }
            }
            cl /= n; cr /= n; cb /= n
        }
        for y in y0...y1 {
            let dy = Float(y) + 0.5 - cy
            for x in x0...x1 {
                let dx = Float(x) + 0.5 - cx
                let d = (dx * dx + dy * dy).squareRoot()
                if d >= radius { continue }
                var wt = flow
                if d > inner {
                    let t = (radius - d) / (radius - inner)
                    wt *= t * t * (3 - 2 * t)
                }
                let k = y * w + x
                if let g {
                    let dl = g.lum[k] - cl
                    let dr = g.red[k] - cr
                    let db = g.blue[k] - cb
                    wt *= exp(-(dl * dl / (Self.sigmaL * Self.sigmaL) + (dr * dr + db * db) / (Self.sigmaC * Self.sigmaC)))
                }
                if wt <= 0 { continue }
                if s.erase {
                    paint[k] *= 1 - wt
                    erase[k] += (1 - erase[k]) * wt
                } else {
                    paint[k] += (1 - paint[k]) * wt
                    erase[k] *= 1 - wt
                }
            }
        }
        if let r = dirty {
            dirty = [min(r[0], x0), min(r[1], y0), max(r[2], x1 + 1), max(r[3], y1 + 1)]
        } else {
            dirty = [x0, y0, x1 + 1, y1 + 1]
        }
    }

    /** Copies the changed area into [rg] and records it as a new version. */
    private func commit() {
        guard let r = dirty else { return }
        dirty = nil
        for y in r[1]..<r[3] {
            for x in r[0]..<r[2] {
                let k = y * width + x
                // Kotlin's roundToInt().toByte(): half up.
                rg[k * 2] = UInt8(truncatingIfNeeded: Int((paint[k] * 255 + 0.5).rounded(.down)))
                rg[k * 2 + 1] = UInt8(truncatingIfNeeded: Int((erase[k] * 255 + 0.5).rounded(.down)))
            }
        }
        version += 1
        // A full redraw can't be patched onto an older upload: forget the history so it uploads everything.
        if r[0] == 0 && r[1] == 0 && r[2] == width && r[3] == height { history.removeAll() }
        history.append((version, PixelRect(x: r[0], y: r[1], width: r[2] - r[0], height: r[3] - r[1])))
        if history.count > Self.historySize { history.removeFirst() }
    }
}
