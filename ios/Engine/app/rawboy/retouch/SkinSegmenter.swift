import CoreGraphics
import Foundation
import MediaPipeTasksVision
import os
import UIKit

/**
 * Where the skin is, as a 0..1 map over the whole image (display orientation),
 * plus the smoothing scale for the faces in it.
 */
public struct SkinMask: @unchecked Sendable {
    public let width: Int
    public let height: Int
    public let data: [Float]
    /** Edge-aware smoothing sigma in full-resolution pixels, scaled to the face size. */
    public let sigmaPx: Float
    public let hasSkin: Bool
    /** Estimated face diameter in full-resolution pixels (0 if no face). */
    public let faceDiameterPx: Float

    public init(width: Int, height: Int, data: [Float], sigmaPx: Float, hasSkin: Bool, faceDiameterPx: Float) {
        self.width = width
        self.height = height
        self.data = data
        self.sigmaPx = sigmaPx
        self.hasSkin = hasSkin
        self.faceDiameterPx = faceDiameterPx
    }
}

public struct SkinSegmenterError: LocalizedError {
    public let message: String
    public var errorDescription: String? { message }
}

/**
 * Finds skin with MediaPipe's selfie multiclass segmentation model (Apache 2.0):
 * categories 0 background, 1 hair, 2 body-skin, 3 face-skin, 4 clothes, 5 others.
 * The same models as Android and web (bundled from engine/src/main/assets/models), via MediaPipe Tasks for iOS
 * (ios/mediapipe/fetch.sh).
 */
public enum SkinSegmenter {
    private static let logger = Logger(subsystem: "app.rawboy", category: "RawBoy")
    private static let model = "selfie_multiclass_256x256"
    private static let landmarkModel = "face_landmarker"

    // MediaPipe face mesh landmark indices (face_mesh_connections) for the features
    // that must never be smoothed or "healed": they are dark/pink shapes inside skin.
    private static let rightEye = [33, 7, 163, 144, 145, 153, 154, 155, 133, 246, 161, 160, 159, 158, 157, 173]
    private static let leftEye = [263, 249, 390, 373, 374, 380, 381, 382, 362, 466, 388, 387, 386, 385, 384, 398]
    private static let rightBrow = [46, 53, 52, 65, 55, 70, 63, 105, 66, 107]
    private static let leftBrow = [276, 283, 282, 295, 285, 300, 293, 334, 296, 336]
    private static let lips = [61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185]
    private static let nostrils = [64, 98, 97, 2, 326, 327, 294, 240, 460, 75, 305, 79, 309, 166, 392]

    /** Feature outlines and how far to grow them (eye corners and tear ducts sit just outside the lid line). */
    private static let features: [([Int], Float)] = [
        (rightEye, 1.6), (leftEye, 1.6), (rightBrow, 1.25), (leftBrow, 1.25), (lips, 1.2), (nostrils, 1.35),
    ]
    private static let bodySkin = 2
    private static let faceSkin = 3

    /**
     * Both models stay loaded after first use (loading them took a few hundred ms per photo; together
     * they hold ~25 MB). MediaPipe tasks aren't thread-safe, so [segment] runs one call at a time.
     */
    private static let lock = NSLock()
    nonisolated(unsafe) private static var segmenter: ImageSegmenter?
    nonisolated(unsafe) private static var landmarker: FaceLandmarker?

    private static func modelPath(_ name: String, _ ext: String) throws -> String {
        guard let path = LutRepository.resourceBundle.path(forResource: name, ofType: ext) else {
            throw SkinSegmenterError(message: "model \(name).\(ext) missing")
        }
        return path
    }

    private static func getSegmenter() throws -> ImageSegmenter {
        if let segmenter { return segmenter }
        let options = ImageSegmenterOptions()
        options.baseOptions.modelAssetPath = try modelPath(model, "tflite")
        options.runningMode = .image
        options.shouldOutputConfidenceMasks = true
        options.shouldOutputCategoryMask = false
        let s = try ImageSegmenter(options: options)
        segmenter = s
        return s
    }

    private static func getLandmarker() throws -> FaceLandmarker {
        if let landmarker { return landmarker }
        let options = FaceLandmarkerOptions()
        options.baseOptions.modelAssetPath = try modelPath(landmarkModel, "task")
        options.runningMode = .image
        options.numFaces = 4
        options.minFaceDetectionConfidence = 0.3
        let l = try FaceLandmarker(options: options)
        landmarker = l
        return l
    }

    /** [image]: the small linear Rec.2020 copy of the photo; [fullWidth]/[fullHeight]: full-resolution size. */
    public static func segment(_ image: PreviewImage, fullWidth: Int, fullHeight: Int) throws -> SkinMask {
        lock.lock()
        defer { lock.unlock() }
        let start = Date()
        let w = image.width
        let h = image.height
        let (cgImage, gray) = try toDisplayImage(image)
        let mpImage = try MPImage(uiImage: UIImage(cgImage: cgImage))

        var skin = [Float](repeating: 0, count: w * h)
        var faceArea = 0.0
        var skinArea = 0.0
        let result = try getSegmenter().segment(image: mpImage)
        guard let masks = result.confidenceMasks, masks.count > faceSkin else { return empty(w, h) }
        func read(_ index: Int) -> [Float] {
            let mask = masks[index]
            let out = Array(UnsafeBufferPointer(start: mask.float32Data, count: mask.width * mask.height))
            return mask.width == w && mask.height == h ? out : resize(out, mask.width, mask.height, w, h)
        }
        let face = read(faceSkin)
        let body = read(bodySkin)
        for i in skin.indices {
            skin[i] = min(max(face[i] + body[i], 0), 1)
            // Count only confident face pixels: summed, the model's faint confidence everywhere
            // else adds up to a "face" of about 2% of every frame.
            if face[i] > 0.5 { faceArea += 1 }
            skinArea += Double(skin[i])
        }
        let tSeg = Date()

        // Snap the low-resolution mask to edges in the photo (hairline, brows, jaw).
        var refined = guidedFilter(gray, skin, w, h, radius: 3, eps: 1e-3)
        let tGuided = Date()

        // Cut eyes, brows, lips and nostrils out of the mask: the segmentation model
        // labels them face skin, but they must not be smoothed or healed.
        excludeFacialFeatures(mpImage, &refined, w, h)
        logger.debug("skin: segmentation \(Int(tSeg.timeIntervalSince(start) * 1000)) ms, guided filter \(Int(tGuided.timeIntervalSince(tSeg) * 1000)) ms, landmarks \(Int(Date().timeIntervalSince(tGuided) * 1000)) ms")

        // Smoothing scale from the face size: ~1.2% of the face diameter.
        let scale = Double(fullWidth) / Double(w)
        let longEdge = Double(max(fullWidth, fullHeight))
        let faceDiameter = faceArea > 4 ? 2 * (faceArea / .pi).squareRoot() * scale : 0
        let sigma = faceArea > 4 ? min(max(0.012 * faceDiameter, 3), 40) : min(max(0.002 * longEdge, 3), 40)
        return SkinMask(width: w, height: h, data: refined, sigmaPx: Float(sigma),
                        hasSkin: skinArea > 0.002 * Double(w * h), faceDiameterPx: Float(faceDiameter))
    }

    /**
     * Zeroes the mask inside each facial feature (polygon grown around its centre),
     * with a soft edge. Faces the landmarker can't find are left as they are.
     */
    private static func excludeFacialFeatures(_ image: MPImage, _ mask: inout [Float], _ w: Int, _ h: Int) {
        guard let faces = try? getLandmarker().detect(image: image).faceLandmarks else { return }
        logger.debug("Face landmarker: \(faces.count) face(s); protecting eyes, brows, lips, nostrils")
        if faces.isEmpty { return }
        var keep = [Float](repeating: 1, count: w * h)
        for face in faces {
            for (indices, grow) in features {
                var xs = indices.map { face[$0].x * Float(w) }
                var ys = indices.map { face[$0].y * Float(h) }
                let cx = xs.reduce(0, +) / Float(xs.count)
                let cy = ys.reduce(0, +) / Float(ys.count)
                for i in xs.indices {
                    xs[i] = cx + (xs[i] - cx) * grow
                    ys[i] = cy + (ys[i] - cy) * grow
                }
                fillConvexHull(xs, ys, &keep, w, h)
            }
        }
        // Soften the cut so smoothing fades out instead of stopping at a hard line.
        let soft = Filters.box(keep, w, h, 1)
        for i in mask.indices { mask[i] *= soft[i] }
    }

    /** Sets [keep] to 0 inside the convex hull of the points. */
    private static func fillConvexHull(_ xs: [Float], _ ys: [Float], _ keep: inout [Float], _ w: Int, _ h: Int) {
        // Monotone chain hull.
        let pts = xs.indices.sorted { xs[$0] != xs[$1] ? xs[$0] < xs[$1] : ys[$0] < ys[$1] }
        func cross(_ o: Int, _ a: Int, _ b: Int) -> Float { (xs[a] - xs[o]) * (ys[b] - ys[o]) - (ys[a] - ys[o]) * (xs[b] - xs[o]) }
        var hull: [Int] = []
        for p in pts {
            while hull.count >= 2 && cross(hull[hull.count - 2], hull[hull.count - 1], p) <= 0 { hull.removeLast() }
            hull.append(p)
        }
        let lower = hull.count + 1
        for p in pts.reversed() {
            while hull.count >= lower && cross(hull[hull.count - 2], hull[hull.count - 1], p) <= 0 { hull.removeLast() }
            hull.append(p)
        }
        hull.removeLast()
        if hull.count < 3 { return }
        let minX = Int(hull.map { xs[$0] }.min()!).clamped(0, w - 1)
        let maxX = Int(hull.map { xs[$0] }.max()!).clamped(0, w - 1)
        let minY = Int(hull.map { ys[$0] }.min()!).clamped(0, h - 1)
        let maxY = Int(hull.map { ys[$0] }.max()!).clamped(0, h - 1)
        for y in minY...maxY {
            for x in minX...maxX {
                let px = Float(x) + 0.5
                let py = Float(y) + 0.5
                // Inside a convex polygon = on the same side of every edge (either winding).
                var pos = false
                var neg = false
                for k in hull.indices {
                    let a = hull[k]
                    let b = hull[(k + 1) % hull.count]
                    let c = (xs[b] - xs[a]) * (py - ys[a]) - (ys[b] - ys[a]) * (px - xs[a])
                    if c > 0 { pos = true } else if c < 0 { neg = true }
                }
                if !(pos && neg) { keep[y * w + x] = 0 }
            }
        }
    }

    private static func empty(_ w: Int, _ h: Int) -> SkinMask {
        SkinMask(width: w, height: h, data: [Float](repeating: 0, count: w * h), sigmaPx: 3, hasSkin: false, faceDiameterPx: 0)
    }

    /** Linear Rec.2020 half floats -> an ordinary-looking sRGB image for the model, plus its luminance. */
    private static func toDisplayImage(_ image: PreviewImage) throws -> (CGImage, [Float]) {
        let n = image.width * image.height
        let halves = image.pixels
        var rgb = [Float](repeating: 0, count: n * 3)
        var logSum = 0.0
        for i in 0..<n {
            let r = Float(Float16(bitPattern: halves[i * 3]))
            let g = Float(Float16(bitPattern: halves[i * 3 + 1]))
            let b = Float(Float16(bitPattern: halves[i * 3 + 2]))
            // Rec.2020 -> Rec.709/sRGB primaries
            rgb[i * 3] = 1.6605 * r - 0.5876 * g - 0.0728 * b
            rgb[i * 3 + 1] = -0.1246 * r + 1.1329 * g - 0.0083 * b
            rgb[i * 3 + 2] = -0.0182 * r - 0.1006 * g + 1.1187 * b
            logSum += log(max(0.2627 * Double(r) + 0.6780 * Double(g) + 0.0593 * Double(b), 1e-5))
        }
        // Auto exposure: put the log-average at middle grey, then a soft shoulder.
        let k = Float(0.18 / exp(logSum / Double(n)))
        let weights: [Float] = [0.2126, 0.7152, 0.0722]
        var pixels = [UInt8](repeating: 255, count: n * 4)
        var gray = [Float](repeating: 0, count: n)
        for i in 0..<n {
            var y: Float = 0
            for ch in 0..<3 {
                let v = max(rgb[i * 3 + ch], 0) * k
                let t = (v / (1 + v)) * 1.6
                let e = t <= 0.0031308 ? t * 12.92 : 1.055 * powf(t, 1 / 2.4) - 0.055
                let e8 = Int(min(max(e, 0), 1) * 255 + 0.5)
                y += Float(e8) * weights[ch]
                pixels[i * 4 + ch] = UInt8(e8)
            }
            gray[i] = y / 255
        }
        guard let provider = CGDataProvider(data: Data(pixels) as CFData),
              let cg = CGImage(width: image.width, height: image.height, bitsPerComponent: 8, bitsPerPixel: 32,
                               bytesPerRow: image.width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                               bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                               provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
        else { throw SkinSegmenterError(message: "cannot make the model's input image") }
        return (cg, gray)
    }

    private static func resize(_ src: [Float], _ sw: Int, _ sh: Int, _ dw: Int, _ dh: Int) -> [Float] {
        var out = [Float](repeating: 0, count: dw * dh)
        for y in 0..<dh {
            let fy = min(max((Float(y) + 0.5) * Float(sh) / Float(dh) - 0.5, 0), Float(sh - 1))
            let y0 = max(min(Int(fy), sh - 2), 0)
            let ty = fy - Float(y0)
            for x in 0..<dw {
                let fx = min(max((Float(x) + 0.5) * Float(sw) / Float(dw) - 0.5, 0), Float(sw - 1))
                let x0 = max(min(Int(fx), sw - 2), 0)
                let tx = fx - Float(x0)
                let y1 = min(y0 + 1, sh - 1)
                let x1 = min(x0 + 1, sw - 1)
                let top = src[y0 * sw + x0] * (1 - tx) + src[y0 * sw + x1] * tx
                let bottom = src[y1 * sw + x0] * (1 - tx) + src[y1 * sw + x1] * tx
                out[y * dw + x] = top * (1 - ty) + bottom * ty
            }
        }
        return out
    }

    /** Guided filter (He et al.): edge-preserving refinement of [p] using guide [g]. */
    private static func guidedFilter(_ g: [Float], _ p: [Float], _ w: Int, _ h: Int, radius: Int, eps: Float) -> [Float] {
        let meanG = Filters.box(g, w, h, radius)
        let meanP = Filters.box(p, w, h, radius)
        let corrGP = Filters.box(zip(g, p).map { $0 * $1 }, w, h, radius)
        let corrGG = Filters.box(g.map { $0 * $0 }, w, h, radius)
        var a = [Float](repeating: 0, count: g.count)
        var b = [Float](repeating: 0, count: g.count)
        for i in g.indices {
            let variance = corrGG[i] - meanG[i] * meanG[i]
            a[i] = (corrGP[i] - meanG[i] * meanP[i]) / (variance + eps)
            b[i] = meanP[i] - a[i] * meanG[i]
        }
        let meanA = Filters.box(a, w, h, radius)
        let meanB = Filters.box(b, w, h, radius)
        return g.indices.map { min(max(meanA[$0] * g[$0] + meanB[$0], 0), 1) }
    }
}
