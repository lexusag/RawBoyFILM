import Foundation

/**
 * User crop: edges as fractions (0..1) of the rotated + straightened frame, i.e. the whole picture the
 * Crop & rotate tool shows. The crop is the last geometry step and never changes lens correction or the
 * straighten auto crop, which always work on the full frame.
 */
public struct CropRect: Equatable, Sendable {
    public var left: Float
    public var top: Float
    public var right: Float
    public var bottom: Float

    public init(_ left: Float, _ top: Float, _ right: Float, _ bottom: Float) {
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
    }

    public var width: Float { right - left }
    public var height: Float { bottom - top }
    public var isFull: Bool { left <= 0 && top <= 0 && right >= 1 && bottom >= 1 }
}

public extension DevelopParams {
    var crop: CropRect { CropRect(cropLeft, cropTop, cropRight, cropBottom) }

    func withCrop(_ r: CropRect) -> DevelopParams {
        var p = self
        p.cropLeft = r.left
        p.cropTop = r.top
        p.cropRight = r.right
        p.cropBottom = r.bottom
        return p
    }

    /** The same edit on the whole frame: what the Crop & rotate tool shows while it is open. */
    func withoutCrop() -> DevelopParams { withCrop(CropMath.full) }
}

/** Crop aspect ratios offered by the tool, as long side : short side (orientation is chosen separately). */
public enum CropAspect: CaseIterable, Sendable {
    case free, original, square, r5x4, r4x3, r3x2, r16x9

    private var sides: (long: Int, short: Int) {
        switch self {
        case .free, .original: (0, 0)
        case .square: (1, 1)
        case .r5x4: (5, 4)
        case .r4x3: (4, 3)
        case .r3x2: (3, 2)
        case .r16x9: (16, 9)
        }
    }

    public func label(portrait: Bool) -> String {
        switch self {
        case .free: "Free"
        case .original: "Original"
        case .square: "1:1"
        default: portrait ? "\(sides.short):\(sides.long)" : "\(sides.long):\(sides.short)"
        }
    }

    /** Width / height in pixels, or nil for free. */
    public func ratio(frameWidth: Int, frameHeight: Int, portrait: Bool) -> Double? {
        switch self {
        case .free:
            return nil
        case .original:
            let a = Double(frameWidth) / Double(frameHeight)
            return portrait ? min(a, 1 / a) : max(a, 1 / a)
        default:
            return portrait ? Double(sides.short) / Double(sides.long) : Double(sides.long) / Double(sides.short)
        }
    }
}

/** Pure crop geometry shared by the Pro UI (mirrored in Android's CropMath.kt and web's CropMath.ts). */
public enum CropMath {
    public static let full = CropRect(0, 0, 1, 1)

    /** Smallest crop side in frame pixels. */
    public static let minPixels = 64

    public enum Handle: Sendable { case move, left, top, right, bottom, topLeft, topRight, bottomLeft, bottomRight }

    /** The crop after turning the picture by [quarterTurns] clockwise quarter turns. */
    public static func rotate(_ r: CropRect, quarterTurns: Int) -> CropRect {
        var c = r
        for _ in 0..<(((quarterTurns % 4) + 4) % 4) {
            // Clockwise: frame point (x, y) moves to (1 - y, x).
            c = CropRect(1 - c.bottom, c.left, 1 - c.top, c.right)
        }
        return c
    }

    /**
     * [p] with the picture as shown mirrored left-right ([vertical] false) or top-bottom. The flips are stored on the
     * unturned photo, so a sideways turn swaps the axis; the straighten angle and the crop mirror with the picture.
     */
    public static func flip(_ p: DevelopParams, vertical: Bool) -> DevelopParams {
        var f = p
        if vertical != (p.rotation % 2 != 0) { f.flipVertical.toggle() } else { f.flipHorizontal.toggle() }
        f.straighten = p.straighten == 0 ? 0 : -p.straighten
        let r = p.crop
        return f.withCrop(vertical ? CropRect(r.left, 1 - r.bottom, r.right, 1 - r.top) : CropRect(1 - r.right, r.top, 1 - r.left, r.bottom))
    }

    /**
     * [r] reshaped to [ratio] (width / height in pixels) with about the same area, around the same
     * centre, inside the frame. Nil ratio = free: [r] unchanged.
     */
    public static func withAspect(_ r: CropRect, ratio: Double?, frameWidth: Int, frameHeight: Int) -> CropRect {
        guard let ratio else { return r }
        let fw = Double(frameWidth)
        let fh = Double(frameHeight)
        let area = Double(r.width) * fw * Double(r.height) * fh
        var w = (area * ratio).squareRoot()
        var h = w / ratio
        let fit = min(1.0, min(fw / w, fh / h))
        w *= fit
        h *= fit
        let cx = (Double(r.left + r.right) / 2 * fw).clamped(w / 2, fw - w / 2)
        let cy = (Double(r.top + r.bottom) / 2 * fh).clamped(h / 2, fh - h / 2)
        return fromPixels(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, fw, fh)
    }

    /**
     * [start] after dragging [handle] by ([dx], [dy]) in frame fractions, kept inside the frame, at least
     * [minPixels] wide and high, and at [ratio] (width / height in pixels) when not nil.
     */
    public static func drag(_ start: CropRect, handle: Handle, dx: Float, dy: Float, ratio: Double?,
                            frameWidth: Int, frameHeight: Int) -> CropRect {
        let fw = Double(frameWidth)
        let fh = Double(frameHeight)
        let minW = min(Double(minPixels), fw)
        let minH = min(Double(minPixels), fh)
        let l = Double(start.left) * fw
        let t = Double(start.top) * fh
        let r = Double(start.right) * fw
        let b = Double(start.bottom) * fh
        let mx = Double(dx) * fw
        let my = Double(dy) * fh

        if handle == .move {
            let ox = mx.clamped(-l, fw - r)
            let oy = my.clamped(-t, fh - b)
            return fromPixels(l + ox, t + oy, r + ox, b + oy, fw, fh)
        }

        let movesLeft = handle == .left || handle == .topLeft || handle == .bottomLeft
        let movesRight = handle == .right || handle == .topRight || handle == .bottomRight
        let movesTop = handle == .top || handle == .topLeft || handle == .topRight
        let movesBottom = handle == .bottom || handle == .bottomLeft || handle == .bottomRight

        guard let ratio else {
            let nl = movesLeft ? (l + mx).clamped(0, r - minW) : l
            let nr = movesRight ? (r + mx).clamped(l + minW, fw) : r
            let nt = movesTop ? (t + my).clamped(0, b - minH) : t
            let nb = movesBottom ? (b + my).clamped(t + minH, fh) : b
            return fromPixels(nl, nt, nr, nb, fw, fh)
        }

        let horizontal = movesLeft || movesRight
        let vertical = movesTop || movesBottom
        // Anchor: the opposite edge (or corner); a single edge keeps the other axis centred.
        let ax = movesLeft ? r : movesRight ? l : (l + r) / 2
        let ay = movesTop ? b : movesBottom ? t : (t + b) / 2
        let wantW = horizontal ? abs((movesLeft ? l + mx : r + mx) - ax) : 0
        let wantH = vertical ? abs((movesTop ? t + my : b + my) - ay) : 0
        var w = max(wantW, wantH * ratio)
        // Room around the anchor, per axis (both halves when that axis stays centred).
        let roomW = movesLeft ? ax : movesRight ? fw - ax : 2 * min(ax, fw - ax)
        let roomH = movesTop ? ay : movesBottom ? fh - ay : 2 * min(ay, fh - ay)
        w = min(w, min(roomW, roomH * ratio))
        w = min(max(w, max(minW, minH * ratio)), min(roomW, roomH * ratio))
        let h = w / ratio
        let nl = movesLeft ? ax - w : movesRight ? ax : ax - w / 2
        let nt = movesTop ? ay - h : movesBottom ? ay : ay - h / 2
        return fromPixels(nl, nt, nl + w, nt + h, fw, fh)
    }

    /**
     * The handle under frame point ([x], [y]) (fractions), with grab distances [tolX], [tolY] in fractions:
     * corners first, then edges, then inside = move. Nil outside the crop.
     */
    public static func handleAt(_ r: CropRect, x: Float, y: Float, tolX: Float, tolY: Float) -> Handle? {
        let nearL = abs(x - r.left) <= tolX
        let nearR = abs(x - r.right) <= tolX
        let nearT = abs(y - r.top) <= tolY
        let nearB = abs(y - r.bottom) <= tolY
        let inX = x >= r.left - tolX && x <= r.right + tolX
        let inY = y >= r.top - tolY && y <= r.bottom + tolY
        if !inX || !inY { return nil }
        if nearT && nearL { return .topLeft }
        if nearT && nearR { return .topRight }
        if nearB && nearL { return .bottomLeft }
        if nearB && nearR { return .bottomRight }
        if nearL { return .left }
        if nearR { return .right }
        if nearT { return .top }
        if nearB { return .bottom }
        return .move
    }

    private static func fromPixels(_ l: Double, _ t: Double, _ r: Double, _ b: Double, _ fw: Double,
                                   _ fh: Double) -> CropRect {
        CropRect(Float(l / fw).clamped(0, 1), Float(t / fh).clamped(0, 1),
                 Float(r / fw).clamped(0, 1), Float(b / fh).clamped(0, 1))
    }
}
