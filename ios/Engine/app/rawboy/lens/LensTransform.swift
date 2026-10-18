import Foundation

/**
 * Lens geometry for one image and one set of slider values: where each output
 * pixel samples the decoded image, and the gains to apply there.
 *
 * Output pixel p (full resolution, after rotation and crop) maps to its source by:
 *   0. add the user crop's origin (the crop only picks a window of the finished frame), then undo
 *      rotation (90 degree turns + straighten), with auto crop for the straighten angle, and the flips
 *   1. auto scale around the image centre (crop to valid pixels, like Lightroom's Constrain Crop)
 *   2. manual distortion (radial, around the image centre)
 *   3. embedded DNG WarpRectilinear, in sensor coordinates, per colour plane
 *
 * Lens correction always works on the whole frame: the auto scale, distortion, warp and vignettes use the
 * full image and sensor, never the user crop, so a cropped pixel equals the uncropped render's pixel.
 *
 * The shader (shaders/develop.glsl) mirrors this exactly; Swift uses it to size tiles and the auto scale.
 * [knownZoom] reuses the auto scale of a transform that differs only in crop (it doesn't depend on it).
 */
public final class LensTransform: @unchecked Sendable {
    public let info: LensInfo
    public let width: Int
    public let height: Int
    public let centerX: Double
    public let centerY: Double
    public let halfDiagonal: Double

    /** Clockwise quarter turns, 0..3. */
    public let quarterTurns: Int
    /** The photo is mirrored left-right / top-bottom (display orientation, before the turns). */
    public let flipHorizontal: Bool
    public let flipVertical: Bool
    /** The rotated + straightened frame, before the user crop. */
    public let frameWidth: Int
    public let frameHeight: Int

    /** User crop in frame pixels; the whole frame when there is none. */
    public private(set) var cropRect = PixelRect(x: 0, y: 0, width: 1, height: 1)
    public var outputWidth: Int { cropRect.width }
    public var outputHeight: Int { cropRect.height }

    private let straightenRadians: Double

    /** Output pixel -> image pixel (display orientation), row-major 3x3 affine. */
    public private(set) var outToImage: [Double] = []

    private let useProfile: Bool

    /** k in src_r = r * (1 - k r^2); +100 on the slider removes strong barrel distortion. */
    public let manualK: Double

    public let warp: LensInfo.Warp?
    public let vignette: LensInfo.RadialVignette?

    /** The decode applied the shading gain maps; with the profile off, take the luminance part back out. */
    public let undoShading: Bool

    public let manualVignette: Double
    public let manualVignetteMidpoint: Double

    /** Oriented pixel -> sensor pixel (row-major 3x3 affine), and back. */
    public let toSensor: [Double]
    public let fromSensor: [Double]

    public let warpCenterX: Double
    public let warpCenterY: Double
    public private(set) var warpNorm: Double = 1
    public let vignetteCenterX: Double
    public let vignetteCenterY: Double
    public private(set) var vignetteNorm: Double = 1

    /** Planes with distinct warp coefficients (3 = lateral chromatic aberration correction). */
    public let warpPlanes: Int

    public var isGeometric: Bool { warp != nil || manualK != 0 }

    /** Output scale >= the value at which every output pixel has real image data. */
    public private(set) var zoom: Double = 1

    public init(info: LensInfo, params: DevelopParams, knownZoom: Double? = nil) {
        self.info = info
        width = info.width
        height = info.height
        centerX = Double(width) / 2
        centerY = Double(height) / 2
        halfDiagonal = hypot(Double(width) / 2, Double(height) / 2)
        quarterTurns = ((params.rotation % 4) + 4) % 4
        flipHorizontal = params.flipHorizontal
        flipVertical = params.flipVertical
        frameWidth = quarterTurns % 2 == 1 ? height : width
        frameHeight = quarterTurns % 2 == 1 ? width : height
        straightenRadians = Double(params.straighten).clamped(-45, 45) * .pi / 180
        useProfile = params.lensProfile && info.hasProfile
        manualK = Double(params.lensDistortion) / 100 * 0.25
        warp = useProfile ? info.warp : nil
        vignette = useProfile ? info.vignette : nil
        undoShading = !params.lensProfile && info.shading != nil
        manualVignette = Double(params.vignetteAmount) / 100
        manualVignetteMidpoint = Double(params.vignetteMidpoint) / 100
        toSensor = Self.orientationMatrix(info.orientation, Double(info.sensorWidth), Double(info.sensorHeight))
        fromSensor = Mat3.inverse(toSensor)
        warpCenterX = (warp?.centerX ?? 0.5) * Double(info.sensorWidth)
        warpCenterY = (warp?.centerY ?? 0.5) * Double(info.sensorHeight)
        vignetteCenterX = (vignette?.centerX ?? 0.5) * Double(info.sensorWidth)
        vignetteCenterY = (vignette?.centerY ?? 0.5) * Double(info.sensorHeight)
        warpPlanes = warp?.planes ?? 1
        cropRect = cropPixels(params)
        outToImage = rotationMatrix()
        warpNorm = maxCornerDistance(warpCenterX, warpCenterY)
        vignetteNorm = maxCornerDistance(vignetteCenterX, vignetteCenterY)
        zoom = knownZoom ?? (isGeometric ? autoZoom() : 1.0)
    }

    /** Source position (image, display orientation) of output pixel (x, y) for colour plane 0..2. */
    public func source(_ x: Double, _ y: Double, plane: Int = 1) -> [Double] {
        let m = outToImage
        return lensSource(m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5], plane, zoom)
    }

    /**
     * Output pixel showing source position (x, y) (image, display orientation): the inverse of [source], for drawing
     * image-space shapes (mask gradients) over the preview. Newton steps from the lens-free guess, keeping the best
     * estimate: far outside the photo (overlay handles) the distortion polynomial can fold.
     */
    public func output(_ x: Double, _ y: Double) -> [Double] {
        let inv = Mat3.inverse(outToImage)
        var ox = inv[0] * x + inv[1] * y + inv[2]
        var oy = inv[3] * x + inv[4] * y + inv[5]
        let h = 0.5
        var best = [ox, oy]
        var bestErr = Double.infinity
        for _ in 0..<6 {
            let s = source(ox, oy)
            let ex = s[0] - x
            let ey = s[1] - y
            let err = abs(ex) + abs(ey)
            if !(err < bestErr) { break }
            best = [ox, oy]
            bestErr = err
            if err < 0.02 { break }
            let sx = source(ox + h, oy)
            let sy = source(ox, oy + h)
            let a = (sx[0] - s[0]) / h
            let b = (sy[0] - s[0]) / h
            let c = (sx[1] - s[1]) / h
            let d = (sy[1] - s[1]) / h
            let det = a * d - b * c
            if abs(det) < 1e-9 { break }
            ox -= (d * ex - b * ey) / det
            oy -= (a * ey - c * ex) / det
        }
        return best
    }

    /** Steps 1-3 for an image position (x, y). */
    private func lensSource(_ x: Double, _ y: Double, _ plane: Int, _ zoomOverride: Double) -> [Double] {
        // 1-2: scale and manual radial distortion around the image centre.
        var dx = (x - centerX) / zoomOverride
        var dy = (y - centerY) / zoomOverride
        let rn2 = (dx * dx + dy * dy) / (halfDiagonal * halfDiagonal)
        let f = 1.0 - manualK * rn2
        dx *= f
        dy *= f
        let qx = centerX + dx
        let qy = centerY + dy
        guard let w = warp else { return [qx, qy] }

        // 3: embedded warp in sensor space.
        let sx = toSensor[0] * qx + toSensor[1] * qy + toSensor[2]
        let sy = toSensor[3] * qx + toSensor[4] * qy + toSensor[5]
        let c = w.coefficients
        let o = (w.planes == 3 ? plane : 0) * 6
        let ux = (sx - warpCenterX) / warpNorm
        let uy = (sy - warpCenterY) / warpNorm
        let r2 = ux * ux + uy * uy
        let radial = c[o] + r2 * (c[o + 1] + r2 * (c[o + 2] + r2 * c[o + 3]))
        let tx = c[o + 4] * 2 * ux * uy + c[o + 5] * (r2 + 2 * ux * ux)
        let ty = c[o + 4] * (r2 + 2 * uy * uy) + c[o + 5] * 2 * ux * uy
        let srcX = warpCenterX + warpNorm * (ux * radial + tx)
        let srcY = warpCenterY + warpNorm * (uy * radial + ty)
        return [
            fromSensor[0] * srcX + fromSensor[1] * srcY + fromSensor[2],
            fromSensor[3] * srcX + fromSensor[4] * srcY + fromSensor[5],
        ]
    }

    /** Source pixels needed to render [out], plus [margin], clamped to the image. */
    public func sourceBounds(_ out: PixelRect, margin: Int) -> PixelRect {
        if !isGeometric && zoom == 1.0 && quarterTurns == 0 && !flipHorizontal && !flipVertical && straightenRadians == 0 && cropRect.x == 0 && cropRect.y == 0 {
            return out.expand(margin, maxW: width, maxH: height)
        }
        var minX = Double.greatestFiniteMagnitude
        var minY = Double.greatestFiniteMagnitude
        var maxX = -Double.greatestFiniteMagnitude
        var maxY = -Double.greatestFiniteMagnitude
        let steps = 24
        for plane in planesToCheck() {
            for i in 0...steps {
                let t = Double(i) / Double(steps)
                let xs = Double(out.x) + t * Double(out.width)
                let ys = Double(out.y) + t * Double(out.height)
                for (px, py) in [(xs, Double(out.y)), (xs, Double(out.bottom)), (Double(out.x), ys), (Double(out.right), ys)] {
                    let s = source(px, py, plane: plane)
                    minX = min(minX, s[0]); maxX = max(maxX, s[0])
                    minY = min(minY, s[1]); maxY = max(maxY, s[1])
                }
            }
        }
        let x0 = (Int(floor(minX)) - margin).clamped(0, width - 1)
        let y0 = (Int(floor(minY)) - margin).clamped(0, height - 1)
        let x1 = (Int(ceil(maxX)) + margin).clamped(x0 + 1, width)
        let y1 = (Int(ceil(maxY)) + margin).clamped(y0 + 1, height)
        return PixelRect(x: x0, y: y0, width: x1 - x0, height: y1 - y0)
    }

    private func planesToCheck() -> [Int] { warpPlanes == 3 ? [0, 1, 2] : [1] }

    private func autoZoom() -> Double {
        func inside(_ z: Double) -> Bool {
            let steps = 48
            let w = Double(width)
            let h = Double(height)
            for plane in planesToCheck() {
                for i in 0...steps {
                    let t = Double(i) / Double(steps)
                    for (px, py) in [(t * w, 0.0), (t * w, h), (0.0, t * h), (w, t * h)] {
                        let s = lensSource(px, py, plane, z)
                        if s[0] < -0.5 || s[1] < -0.5 || s[0] > w + 0.5 || s[1] > h + 0.5 { return false }
                    }
                }
            }
            return true
        }
        var lo = 0.7
        var hi = 1.6
        if !inside(hi) { return hi }
        if inside(lo) { return lo }
        for _ in 0..<30 {
            let mid = (lo + hi) / 2
            if inside(mid) { hi = mid } else { lo = mid }
        }
        return hi
    }

    /** Crop edges rounded to frame pixels, at least 16 px and inside the frame. */
    private func cropPixels(_ params: DevelopParams) -> PixelRect {
        let c = params.crop
        if c.isFull { return PixelRect(x: 0, y: 0, width: frameWidth, height: frameHeight) }
        func edges(_ a: Float, _ b: Float, _ size: Int) -> (Int, Int) {
            let minSize = min(16, size)
            let lo = (a.clamped(0, 1) * Float(size)).roundToInt().clamped(0, size - minSize)
            let hi = (b.clamped(0, 1) * Float(size)).roundToInt().clamped(lo + minSize, size)
            return (lo, hi)
        }
        let (x0, x1) = edges(c.left, c.right, frameWidth)
        let (y0, y1) = edges(c.top, c.bottom, frameHeight)
        return PixelRect(x: x0, y: y0, width: x1 - x0, height: y1 - y0)
    }

    /**
     * image = c_img + F R(-90k) R(-theta) (out + crop - c_frame) / s, in y-down coordinates where
     * positive angles turn the picture clockwise; F mirrors the image about its centre (the flips). s crops the straightened frame to the
     * largest same-aspect rectangle inside the image; it depends on the frame, not the user crop.
     */
    private func rotationMatrix() -> [Double] {
        let t = straightenRadians
        let c = cos(t)
        let sn = sin(t)
        let ow = Double(frameWidth)
        let oh = Double(frameHeight)
        let s = c + max(ow / oh, oh / ow) * abs(sn)
        // R(-theta) = [[c, s], [-s, c]]
        let r = [c / s, sn / s, -sn / s, c / s]
        // R(-90 k): k=1 [[0,1],[-1,0]], k=2 [[-1,0],[0,-1]], k=3 [[0,-1],[1,0]]
        let q: [Double]
        switch quarterTurns {
        case 1: q = [0, 1, -1, 0]
        case 2: q = [-1, 0, 0, -1]
        case 3: q = [0, -1, 1, 0]
        default: q = [1, 0, 0, 1]
        }
        let fx: Double = flipHorizontal ? -1 : 1
        let fy: Double = flipVertical ? -1 : 1
        let a = fx * (q[0] * r[0] + q[1] * r[2])
        let b = fx * (q[0] * r[1] + q[1] * r[3])
        let d = fy * (q[2] * r[0] + q[3] * r[2])
        let e = fy * (q[2] * r[1] + q[3] * r[3])
        // Frame centre in output (cropped) pixels.
        let cox = ow / 2 - Double(cropRect.x)
        let coy = oh / 2 - Double(cropRect.y)
        return [
            a, b, centerX - (a * cox + b * coy),
            d, e, centerY - (d * cox + e * coy),
            0, 0, 1,
        ]
    }

    private func maxCornerDistance(_ cx: Double, _ cy: Double) -> Double {
        let w = Double(info.sensorWidth)
        let h = Double(info.sensorHeight)
        return max(hypot(cx, cy), hypot(w - cx, cy), hypot(cx, h - cy), hypot(w - cx, h - cy))
    }

    /** Affine (continuous pixel coordinates) from display orientation to sensor orientation. */
    public static func orientationMatrix(_ o: Int, _ w: Double, _ h: Double) -> [Double] {
        switch o {
        case 2: [-1, 0, w, 0, 1, 0, 0, 0, 1]
        case 3: [-1, 0, w, 0, -1, h, 0, 0, 1]
        case 4: [1, 0, 0, 0, -1, h, 0, 0, 1]
        case 5: [0, 1, 0, 1, 0, 0, 0, 0, 1]
        case 6: [0, 1, 0, -1, 0, h, 0, 0, 1]
        case 7: [0, -1, w, -1, 0, h, 0, 0, 1]
        case 8: [0, -1, w, 1, 0, 0, 0, 0, 1]
        default: [1, 0, 0, 0, 1, 0, 0, 0, 1]
        }
    }
}

private extension PixelRect {
    func expand(_ margin: Int, maxW: Int, maxH: Int) -> PixelRect {
        let x0 = max(x - margin, 0)
        let y0 = max(y - margin, 0)
        let x1 = min(right + margin, maxW)
        let y1 = min(bottom + margin, maxH)
        return PixelRect(x: x0, y: y0, width: x1 - x0, height: y1 - y0)
    }
}
