import Foundation
import Metal

/** A full-resolution region (RGB half floats) used for the 100% view. */
public struct DetailTile: @unchecked Sendable {
    public let rect: PixelRect
    public let pixels: [UInt16]
    /**
     * The pixels as a texture, made where the tile is loaded: converting ~10 MP to RGBA on the main thread stalled
     * panning at 100%.
     */
    public let texture: MTLTexture
}

public struct PixelRect: Equatable, Hashable, Sendable {
    public let x: Int
    public let y: Int
    public let width: Int
    public let height: Int

    public init(x: Int, y: Int, width: Int, height: Int) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }

    public var right: Int { x + width }
    public var bottom: Int { y + height }

    public func contains(_ o: PixelRect) -> Bool { o.x >= x && o.y >= y && o.right <= right && o.bottom <= bottom }
}

/**
 * Geometry of the 100% view: one full-resolution pixel per screen pixel,
 * centred on (cx, cy) in full-resolution coordinates.
 */
public enum ZoomMath {
    /** Margin kept around the visible region so denoise has real neighbours. */
    public static let halo = 16
    private static let minTile = 2048
    private static let tileSlack = 512

    /** Keeps the visible region inside the image. */
    public static func clampCenter(cx: Float, cy: Float, viewW: Int, viewH: Int, imgW: Int, imgH: Int) -> (Float, Float) {
        let halfW = Float(min(viewW, imgW)) / 2
        let halfH = Float(min(viewH, imgH)) / 2
        return (cx.clamped(halfW, Float(imgW) - halfW), cy.clamped(halfH, Float(imgH) - halfH))
    }

    /** The full-resolution region visible on screen. */
    public static func viewRect(cx: Float, cy: Float, viewW: Int, viewH: Int, imgW: Int, imgH: Int) -> PixelRect {
        let w = min(viewW, imgW)
        let h = min(viewH, imgH)
        let x = Int((cx - Float(w) / 2).rounded()).clamped(0, imgW - w)
        let y = Int((cy - Float(h) / 2).rounded()).clamped(0, imgH - h)
        return PixelRect(x: x, y: y, width: w, height: h)
    }

    /**
     * Where the whole output image sits in the 100% view (one image pixel per view pixel), as [left, top, width,
     * height] in view pixels: the visible region is centred like DevelopRenderer.drawZoomed's quad.
     */
    public static func zoomedRect(cx: Float, cy: Float, viewW: Int, viewH: Int, imgW: Int, imgH: Int) -> [Float] {
        let v = viewRect(cx: cx, cy: cy, viewW: viewW, viewH: viewH, imgW: imgW, imgH: imgH)
        return [Float(viewW - v.width) / 2 - Float(v.x), Float(viewH - v.height) / 2 - Float(v.y), Float(imgW), Float(imgH)]
    }

    /** A tile around [needed] (source pixels), large enough that panning rarely reloads. */
    public static func tileAround(_ needed: PixelRect, imgW: Int, imgH: Int) -> PixelRect {
        let w = min(imgW, max(minTile, needed.width + tileSlack))
        let h = min(imgH, max(minTile, needed.height + tileSlack))
        let x = (needed.x + needed.width / 2 - w / 2).clamped(0, imgW - w)
        let y = (needed.y + needed.height / 2 - h / 2).clamped(0, imgH - h)
        return PixelRect(x: x, y: y, width: w, height: h)
    }

    /**
     * Where the photo sits in the fit view: aspect-fit inside the view minus a [frame]
     * on every side, centred. Returns (left, top, width, height) in view pixels.
     */
    public static func fitRect(viewW: Int, viewH: Int, imgW: Int, imgH: Int, frame: Int) -> [Float] {
        let availW = Float(max(1, viewW - 2 * frame))
        let availH = Float(max(1, viewH - 2 * frame))
        let scale = min(availW / Float(imgW), availH / Float(imgH))
        let w = Float(imgW) * scale
        let h = Float(imgH) * scale
        return [(Float(viewW) - w) / 2, (Float(viewH) - h) / 2, w, h]
    }

    /** Maps a point in the fit view to full-resolution coordinates, or nil if outside the photo. */
    public static func fitToImage(px: Float, py: Float, viewW: Int, viewH: Int, imgW: Int, imgH: Int,
                                  frame: Int) -> (Float, Float)? {
        let r = fitRect(viewW: viewW, viewH: viewH, imgW: imgW, imgH: imgH, frame: frame)
        let x = (px - r[0]) / r[2] * Float(imgW)
        let y = (py - r[1]) / r[3] * Float(imgH)
        return (0...Float(imgW)).contains(x) && (0...Float(imgH)).contains(y) ? (x, y) : nil
    }
}
