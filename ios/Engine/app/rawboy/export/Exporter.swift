import CoreGraphics
import Foundation
import ImageIO
import os
import RawBoyJpeg

/**
 * A GPU developer for exports, kept between exports made one after another (the camera develops shot after
 * shot): saves allocating render targets each time. Not thread-safe: use it from one queue.
 */
public final class ExportGpu: @unchecked Sendable {
    private var developer: OffscreenDeveloper?

    public init() {}

    func developer(_ imageWidth: Int, _ imageHeight: Int) -> OffscreenDeveloper {
        let d = developer ?? OffscreenDeveloper(imageWidth: imageWidth, imageHeight: imageHeight)
        developer = d
        d.setImageSize(imageWidth, imageHeight)
        return d
    }
}

/** Where exports go and how they are named: <original>-<suffix>.<ext>, in the Photos album [folder]. */
public struct OutputNaming: Sendable {
    public var folder: String = "RawBoy"
    public var suffix: String = "rawboy"

    public init(folder: String = "RawBoy", suffix: String = "rawboy") {
        self.folder = folder
        self.suffix = suffix
    }

    public func fileName(_ baseName: String, _ ext: String) -> String {
        suffix.isEmpty ? "\(baseName).\(ext)" : "\(baseName)-\(suffix).\(ext)"
    }

    /** Strips the extension and characters file systems won't accept. */
    public static func baseNameOf(_ displayName: String) -> String {
        let stem = (displayName as NSString).deletingPathExtension
        let base = stem.trimmingCharacters(in: .whitespaces).isEmpty ? displayName : stem
        let cleaned = base.replacingOccurrences(of: "[/\\\\:*?\"<>|]", with: "_", options: .regularExpression)
        return cleaned.trimmingCharacters(in: .whitespaces).isEmpty ? "image" : cleaned
    }
}

/**
 * Develops the full-resolution image on the GPU in tiles and writes JPEG or TIFF to a file (the app then adds it
 * to Photos, see PhotoLibraryOutput). Blocking: call from a background queue.
 */
public final class Exporter {
    private static let log = Logger(subsystem: "app.rawboy", category: "RawBoy")
    private static let tileWidth = 1024
    private static let tileHeight = 512

    private var gpu: ExportGpu?

    public init() {}

    /**
     * Writes [image] developed with [params] into [directory] as "<source>-<suffix>.<ext>" and returns the file.
     * [gpu] reuses a developer across exports made one after another.
     */
    public func export(_ image: RawImage, sourceName: String, params: ShaderParams, settings: ExportSettings,
                       naming: OutputNaming = OutputNaming(), directory: URL = FileManager.default.temporaryDirectory,
                       gpu: ExportGpu? = nil, onProgress: (Float) -> Void = { _ in }) throws -> URL {
        self.gpu = gpu
        let name = naming.fileName(OutputNaming.baseNameOf(sourceName), settings.format.fileExtension)
        let url = directory.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: url)
        let size = BandDownscaler.outputSize(params.lens.outputWidth, params.lens.outputHeight,
                                             maxLongSide: settings.maxLongSide)
        do {
            switch settings.format {
            case .jpeg:
                if settings.jpegEncoder == .jpegli {
                    try writeJpegli(image, url, params, size, settings.jpegQuality, onProgress)
                } else {
                    try writeSystemJpeg(image, url, params, size, settings.jpegQuality, onProgress)
                }
                ExifWriter.addExif(to: url, metadata: image.metadata)
            case .tiff:
                try writeTiff(image, url, params, size, settings.tiffBits, onProgress)
            }
            return url
        } catch {
            try? FileManager.default.removeItem(at: url)
            throw error
        }
    }

    /** ImageIO's JPEG: 8-bit input, so the whole image is held as 8-bit RGBA. */
    private func writeSystemJpeg(_ image: RawImage, _ url: URL, _ params: ShaderParams, _ size: (Int, Int),
                                 _ quality: Int, _ onProgress: (Float) -> Void) throws {
        let (w, h) = size
        var rgba = [UInt8](repeating: 255, count: w * h * 4)
        try renderOutput(image, params, size, onProgress) { y, rows, band in
            rgba.withUnsafeMutableBufferPointer { dst in
                for i in 0..<(w * rows) {
                    let o = (y * w + i) * 4
                    dst[o] = Self.to8(band[i * 3])
                    dst[o + 1] = Self.to8(band[i * 3 + 1])
                    dst[o + 2] = Self.to8(band[i * 3 + 2])
                }
            }
        }
        let cg = try ThumbnailRenderer.cgImage(rgba, w, h)
        guard let dest = CGImageDestinationCreateWithURL(url as CFURL, "public.jpeg" as CFString, 1, nil) else {
            throw MetalError(message: "Cannot write \(url.lastPathComponent)")
        }
        CGImageDestinationAddImage(dest, cg, [kCGImageDestinationLossyCompressionQuality: Double(quality) / 100] as CFDictionary)
        if !CGImageDestinationFinalize(dest) { throw MetalError(message: "JPEG encoding failed") }
    }

    /** jpegli streams each band as it is developed, at full 16-bit precision, so no full-size image is held. */
    private func writeJpegli(_ image: RawImage, _ url: URL, _ params: ShaderParams, _ size: (Int, Int), _ quality: Int,
                             _ onProgress: (Float) -> Void) throws {
        var error = [CChar](repeating: 0, count: 256)
        guard let encoder = rb_jpeg_begin(url.path, Int32(size.0), Int32(size.1), Int32(quality), &error, error.count)
        else { throw MetalError(message: String(cString: error)) }
        do {
            try renderOutput(image, params, size, onProgress) { _, rows, band in
                if !rb_jpeg_write_rows(encoder, band, Int32(rows), &error, error.count) {
                    throw MetalError(message: String(cString: error))
                }
            }
        } catch {
            rb_jpeg_abort(encoder)
            throw error
        }
        if !rb_jpeg_finish(encoder, &error, error.count) { throw MetalError(message: String(cString: error)) }
    }

    private func writeTiff(_ image: RawImage, _ url: URL, _ params: ShaderParams, _ size: (Int, Int), _ bits: Int,
                           _ onProgress: (Float) -> Void) throws {
        FileManager.default.createFile(atPath: url.path, contents: nil)
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        let writer = try TiffWriter(handle, width: size.0, height: size.1, bitsPerSample: bits,
                                   metadata: image.metadata)
        try renderOutput(image, params, size, onProgress) { _, rows, band in try writer.writeRows(band, rows: rows) }
        try writer.finish()
    }

    /**
     * Renders the image at [size] (the full output size, or smaller: then the full-resolution bands are
     * area-downscaled as they stream, and the grain is sized to the smaller output). Same callback as renderBands.
     */
    private func renderOutput(_ image: RawImage, _ params: ShaderParams, _ size: (Int, Int),
                              _ onProgress: (Float) -> Void,
                              _ consume: @escaping (Int, Int, UnsafePointer<UInt16>) throws -> Void) throws {
        let w = params.lens.outputWidth
        let h = params.lens.outputHeight
        if size.0 == w && size.1 == h { return try renderBands(image, params, 1, onProgress, consume) }
        let scaler = BandDownscaler(inWidth: w, inHeight: h, outWidth: size.0, outHeight: size.1,
                                    maxRowsPerPush: Self.tileHeight, emit: consume)
        // A grain cell at least one output pixel, as a full-size render of the smaller image would have.
        let minGrain = Float(max(w, h)) / Float(max(size.0, size.1))
        try renderBands(image, params, minGrain, onProgress) { _, rows, band in try scaler.push(rows, band) }
        try scaler.finish()
    }

    /**
     * Renders the image top to bottom in bands of tileHeight rows. [minGrainPx]: smallest grain cell
     * in full-resolution pixels (see renderOutput).
     * [consume] receives (firstRow, rowCount, RGB samples as unsigned 16-bit).
     */
    private func renderBands(_ image: RawImage, _ params: ShaderParams, _ minGrainPx: Float,
                             _ onProgress: (Float) -> Void,
                             _ consume: (Int, Int, UnsafePointer<UInt16>) throws -> Void) throws {
        // Output size: rotation by 90 degrees swaps width and height.
        let w = params.lens.outputWidth
        let h = params.lens.outputHeight
        let tilesX = (w + Self.tileWidth - 1) / Self.tileWidth
        let tilesY = (h + Self.tileHeight - 1) / Self.tileHeight
        var input = [UInt16]()
        var output = [UInt16](repeating: 0, count: Self.tileWidth * Self.tileHeight * 4)
        var band = [UInt16](repeating: 0, count: w * Self.tileHeight * 3)
        var done = 0

        // The shader's image size is the source image's (display orientation), not the output's:
        // whole-image textures (high-pass base, bloom, skin mask) are sampled in source pixels.
        let developer = gpu?.developer(image.width, image.height)
            ?? OffscreenDeveloper(imageWidth: image.width, imageHeight: image.height)
        developer.minGrainPx = minGrainPx
        let start = Date()
        var renderTime = 0.0
        for ty in 0..<tilesY {
            let y = ty * Self.tileHeight
            let rows = min(Self.tileHeight, h - y)
            for tx in 0..<tilesX {
                let x = tx * Self.tileWidth
                let cols = min(Self.tileWidth, w - x)
                // Source pixels this tile needs: lens geometry plus the spatial effects' neighbourhood.
                let out = PixelRect(x: x, y: y, width: cols, height: rows)
                let src = params.lens.sourceBounds(out, margin: params.spatialMargin)
                let count = src.width * src.height * 3
                if input.count < count { input = [UInt16](repeating: 0, count: count) }
                let t0 = Date()
                try input.withUnsafeMutableBufferPointer { inBuf in
                    image.copyTile(x: src.x, y: src.y, w: src.width, h: src.height, out: inBuf.baseAddress!)
                    try output.withUnsafeMutableBufferPointer { outBuf in
                        try developer.render(params, input: inBuf.baseAddress!, inRect: src, out: out,
                                             output: outBuf.baseAddress!)
                    }
                }
                renderTime += Date().timeIntervalSince(t0)
                output.withUnsafeBytes { tile in
                    band.withUnsafeMutableBufferPointer {
                        rb_pack_tile(tile.baseAddress, 2, Int32(cols), Int32(rows), $0.baseAddress, Int32(w), Int32(x))
                    }
                }
                done += 1
                onProgress(Float(done) / Float(tilesX * tilesY))
            }
            try band.withUnsafeBufferPointer { try consume(y, rows, $0.baseAddress!) }
        }
        let total = Date().timeIntervalSince(start)
        Self.log.debug("export \(w)x\(h): develop \(Int(renderTime * 1000)) ms, encode/write \(Int((total - renderTime) * 1000)) ms")
    }

    static func to8(_ v: UInt16) -> UInt8 { UInt8((Int(v) * 255 + 32767) / 65535) }
}
