import CoreGraphics
import Foundation

/**
 * Develops small whole-photo images (grid and look-strip thumbnails) off screen,
 * from a photo's downscaled preview, so no full-resolution data is needed.
 * Renders run one at a time on a private queue.
 */
public final class ThumbnailRenderer: @unchecked Sendable {
    private let queue = DispatchQueue(label: "RawBoy-thumbnails", qos: .userInitiated)
    private var developer: OffscreenDeveloper?

    public init() {}

    /**
     * Develops [preview] with [params] into an image whose long edge is at most [maxDimension]
     * (and no larger than the preview, since upscaling adds nothing).
     */
    public func render(_ params: ShaderParams, preview: PreviewImage, maxDimension: Int) async throws -> CGImage {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                continuation.resume(with: Result { try self.renderNow(params, preview, maxDimension) })
            }
        }
    }

    private func renderNow(_ params: ShaderParams, _ preview: PreviewImage, _ maxDimension: Int) throws -> CGImage {
        let outW = params.lens.outputWidth
        let outH = params.lens.outputHeight
        let longEdge = min(maxDimension, max(preview.width, preview.height))
        let scale = Double(longEdge) / Double(max(outW, outH))
        let w = max(1, (Double(outW) * scale).roundToInt())
        let h = max(1, (Double(outH) * scale).roundToInt())

        let dev = developer ?? OffscreenDeveloper(imageWidth: preview.fullWidth, imageHeight: preview.fullHeight)
        developer = dev
        dev.setImageSize(preview.fullWidth, preview.fullHeight)
        var samples = [UInt16](repeating: 0, count: w * h * 4)
        try samples.withUnsafeMutableBufferPointer {
            try dev.renderPreview(params, preview: preview, width: w, height: h, output: $0.baseAddress!)
        }
        var rgba = [UInt8](repeating: 255, count: w * h * 4)
        for i in 0..<(w * h) {
            for c in 0..<3 { rgba[i * 4 + c] = UInt8((Int(samples[i * 4 + c]) * 255 + 32767) / 65535) }
        }
        return try Self.cgImage(rgba, w, h)
    }

    /** 8-bit RGBA (alpha ignored) as an sRGB CGImage. */
    static func cgImage(_ rgba: [UInt8], _ w: Int, _ h: Int) throws -> CGImage {
        guard let provider = CGDataProvider(data: Data(rgba) as CFData),
              let image = CGImage(
                width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: w * 4,
                space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
        else { throw MetalError(message: "cannot make a \(w)x\(h) image") }
        return image
    }
}
