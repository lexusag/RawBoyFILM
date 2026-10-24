import CoreGraphics
import Foundation
import ImageIO
#if FILM_EDITION
import RawBoyNativeFilm  // RawBoy Film: the same C API, built without the Adobe DNG SDK
#else
import RawBoyNative
#endif

/** Thrown when a raw file can't be read or decoded. */
public struct RawImageError: LocalizedError {
    public let message: String
    public init(message: String) { self.message = message }
    public var errorDescription: String? { message }
}

/**
 * A decoded raw file held in native memory as linear Rec.2020 half floats,
 * white balanced for the as-shot illuminant and oriented for display.
 * (Kotlin's RawNative is the JNI surface; here the C API in rawboy_native.h is called directly.)
 */
public final class RawImage: @unchecked Sendable {
    private var handle: OpaquePointer?
    private let lock = NSLock()

    public let width: Int
    public let height: Int
    public let description: String

    /** Camera and exposure essentials for the editor's info line. */
    public let shotInfo: ShotInfo

    /** ISO speed from the file's metadata, 0 if unknown. */
    public let iso: Float

    /** DNG BaselineExposure in EV, already applied to the pixels; NaN if the file has none. */
    public let baselineExposure: Float

    /** Pixel value of blown highlights (clipped in every channel), 0 if unknown (see DevelopParams.clipLevel). */
    public let clipLevel: Float

    /** Camera maker from the file's metadata, empty if unknown. */
    public let make: String

    /** A JPEG/HEIC, not a raw: rendered with `uDisplaySource` (see DevelopParams.displaySource). */
    public let displayReferred: Bool

    /** As-shot white chromaticity, CIE 1931 xy. */
    public let asShotWhite: [Double]

    /** Lens name and embedded lens corrections. */
    public let lens: LensInfo

    /** The photo's EXIF essentials, for exports (Android copies EXIF from the file instead). */
    public let metadata: PhotoMetadata

    private init(handle: OpaquePointer) {
        self.handle = handle
        width = Int(rb_width(handle))
        height = Int(rb_height(handle))
        let camera = String(cString: rb_camera(handle))
        description = "\(camera) · \(width)×\(height) · \(String(cString: rb_decoder(handle)))"
        iso = rb_iso(handle)
        baselineExposure = rb_baseline_exposure(handle)
        clipLevel = rb_clip_level(handle)
        var m = RBMetadata()
        rb_metadata(handle, &m)
        metadata = PhotoMetadata(m)
        make = metadata.make
        displayReferred = rb_display_referred(handle)
        shotInfo = ShotInfo(
            camera: camera, exposureTime: m.exposureTime, fNumber: m.fNumber, focalLength: m.focalLength,
            focalLength35mm: m.focalLength35mm, iso: Double(iso))
        var white = [Double](repeating: 0, count: 2)
        rb_as_shot_white(handle, &white)
        asShotWhite = white
        var info = [Double](repeating: 0, count: 41)
        rb_lens_info(handle, &info)
        var count = 0
        let gains = rb_lens_shading(handle, &count)
        let shading = gains.map { Array(UnsafeBufferPointer(start: $0, count: count)) } ?? []
        lens = LensInfo.fromNative(
            name: String(cString: rb_lens_name(handle)), width: width, height: height, v: info, shadingGains: shading)
    }

    deinit { close() }

    /** Downscaled copy; [boostHighlights] makes it a bloom source (see rawboy::downsample). */
    public func createPreview(maxDimension: Int, boostHighlights: Bool = false) -> PreviewImage {
        let scale = min(1.0, Double(maxDimension) / Double(max(width, height)))
        let w = max(1, Int((Double(width) * scale).rounded()))
        let h = max(1, Int((Double(height) * scale).rounded()))
        var pixels = [UInt16](repeating: 0, count: w * h * 3)
        withHandle { rb_downsample($0, Int32(w), Int32(h), &pixels, boostHighlights) }
        return PreviewImage(width: w, height: h, pixels: pixels, fullWidth: width, fullHeight: height)
    }

    public func createTile(_ rect: PixelRect) -> DetailTile {
        var pixels = [UInt16](repeating: 0, count: rect.width * rect.height * 3)
        copyTile(x: rect.x, y: rect.y, w: rect.width, h: rect.height, out: &pixels)
        let texture = pixels.withUnsafeBufferPointer {
            DevelopTextures.halfRgbTexture(rect.width, rect.height, $0.baseAddress!)
        }
        return DetailTile(rect: rect, pixels: pixels, texture: texture)
    }

    /** Copies a full-resolution region as tightly packed RGB half floats. */
    public func copyTile(x: Int, y: Int, w: Int, h: Int, out: UnsafeMutablePointer<UInt16>) {
        let ok = withHandle { rb_copy_tile($0, Int32(x), Int32(y), Int32(w), Int32(h), out) }
        precondition(ok, "invalid tile")
    }

    /** Measures the photo's noise on the full-resolution pixels (~30 ms); NoiseLevel.none for JPEG/HEIC. */
    public func measureNoise() -> NoiseLevel {
        var v: [Float] = [0, 0]
        withHandle { rb_noise_level($0, &v) }
        return NoiseLevel(luma: v[0], chroma: v[1])
    }

    /** Blemish candidates on [mask]: (x, y, radius, score) per spot, most obvious first. */
    public func detectBlemishes(_ mask: SkinMask) -> [Float] {
        withHandle { handle in
            var count = 0
            guard let spots = rb_detect_blemishes(
                handle, mask.data, Int32(mask.width), Int32(mask.height), mask.faceDiameterPx, &count)
            else { return [] }
            defer { rb_free_floats(spots) }
            return Array(UnsafeBufferPointer(start: spots, count: count))
        }
    }

    /** Replaces any earlier healing with healing of exactly [spots]. Previews must be rebuilt afterwards. */
    public func healBlemishes(_ spots: [Float]) {
        withHandle { rb_heal_blemishes($0, spots, spots.count) }
    }

    public func close() {
        lock.lock()
        defer { lock.unlock() }
        if let handle {
            rb_close(handle)
            self.handle = nil
        }
    }

    private func withHandle<T>(_ body: (OpaquePointer) -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        guard let handle else { preconditionFailure("image closed") }
        return body(handle)
    }

    public static let bytesPerPixel = 6  // RGB, half float

    /** True when the Adobe DNG SDK (DNG 1.7 / JPEG XL) is built in. */
    public static var hasDngSdk: Bool { rb_has_dng_sdk() }

    /**
     * [extraBlack]: black to take off beyond the file's black level, as a fraction of the white range (LibRaw
     * path only). RawBoy Camera uses it to match the phone's own rendering.
     */
    public static func open(url: URL, extraBlack: Float = 0) throws -> RawImage {
        // Memory-mapped: phone raws are up to ~130 MB and the decoder only reads them.
        let data: Data
        do {
            data = try Data(contentsOf: url, options: .alwaysMapped)
        } catch {
            throw RawImageError(message: "Cannot open \(url.lastPathComponent): \(error.localizedDescription)")
        }
        return try open(data: data, extraBlack: extraBlack)
    }

    public static func open(data: Data, extraBlack: Float = 0) throws -> RawImage {
        if isHeif(data) || isPng(data) { return try openWithImageIO(data: data) }
        var error = [CChar](repeating: 0, count: 512)
        let handle = data.withUnsafeBytes { bytes in
            rb_open(bytes.bindMemory(to: UInt8.self).baseAddress, bytes.count, extraBlack, &error, error.count)
        }
        guard let handle else {
            throw RawImageError(message: String(cString: error))
        }
        return RawImage(handle: handle)
    }

    private static let heifBrands: Set<String> = ["heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1"]

    /** An ISO base media file with a HEIF brand ("ftyp" box first). */
    static func isHeif(_ data: Data) -> Bool {
        guard data.count >= 12 else { return false }
        let head = data.prefix(12)
        let ftyp = String(decoding: head.dropFirst(4).prefix(4), as: UTF8.self)
        let brand = String(decoding: head.dropFirst(8).prefix(4), as: UTF8.self)
        return ftyp == "ftyp" && heifBrands.contains(brand)
    }

    /** The PNG signature. */
    static func isPng(_ data: Data) -> Bool {
        data.prefix(8).elementsEqual([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
    }

    /**
     * HEIC and PNG through ImageIO (no HEVC decoder of our own), drawn into half-float extended linear sRGB (colour
     * managed). SDR only: with iOS 18's kCGImageSourceDecodeToHDR, an iPhone 16 HEIC with a gain map drew as all black
     * (Simulator).
     */
    private static func openWithImageIO(data: Data) throws -> RawImage {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else {
            throw RawImageError(message: "Cannot decode file: not an image")
        }
        guard let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
            throw RawImageError(message: "Cannot decode file: ImageIO could not read it")
        }
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        let orientation = (properties?[kCGImagePropertyOrientation] as? NSNumber)?.int32Value ?? 1
        let w = image.width, h = image.height
        let rowBytes = w * 8
        var pixels = [UInt16](repeating: 0, count: w * h * 4)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let space = CGColorSpace(name: CGColorSpace.extendedLinearSRGB),
                  let context = CGContext(
                    data: buffer.baseAddress, width: w, height: h, bitsPerComponent: 16, bytesPerRow: rowBytes,
                    space: space,
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.floatComponents.rawValue
                        | CGBitmapInfo.byteOrder16Little.rawValue)
            else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard drawn else { throw RawImageError(message: "Cannot decode file: no float bitmap context") }
        var error = [CChar](repeating: 0, count: 512)
        let handle = pixels.withUnsafeBufferPointer { px in
            data.withUnsafeBytes { file in
                rb_open_pixels(px.baseAddress, Int32(w), Int32(h), rowBytes, orientation,
                               file.bindMemory(to: UInt8.self).baseAddress, file.count, &error, error.count)
            }
        }
        guard let handle else { throw RawImageError(message: String(cString: error)) }
        return RawImage(handle: handle)
    }
}

/** The photo's EXIF essentials (rawboy::PhotoMetadata); unknown numbers are NaN (iso 0), unknown strings empty. */
public struct PhotoMetadata: Sendable {
    public struct Gps: Sendable {
        public let latitude: [Double], longitude: [Double]  // degrees, minutes, seconds
        public let latitudeRef: String, longitudeRef: String
        public let altitude: Double  // metres, NaN if unknown
        public let belowSeaLevel: Bool
    }

    public let make, model, lensMake, lensModel, artist, copyright: String
    public let dateTimeOriginal: String  // "YYYY:MM:DD HH:MM:SS"
    public let subSecTimeOriginal, offsetTimeOriginal: String
    public let exposureTime, fNumber, exposureBias, focalLength, focalLength35mm: Double
    public let iso: Float
    public let gps: Gps?

    init(_ m: RBMetadata) {
        make = String(cString: m.make)
        model = String(cString: m.model)
        lensMake = String(cString: m.lensMake)
        lensModel = String(cString: m.lensModel)
        artist = String(cString: m.artist)
        copyright = String(cString: m.copyright)
        dateTimeOriginal = String(cString: m.dateTimeOriginal)
        subSecTimeOriginal = String(cString: m.subSecTimeOriginal)
        offsetTimeOriginal = String(cString: m.offsetTimeOriginal)
        exposureTime = m.exposureTime
        fNumber = m.fNumber
        exposureBias = m.exposureBias
        focalLength = m.focalLength
        focalLength35mm = m.focalLength35mm
        iso = m.iso
        if m.hasGps {
            gps = Gps(
                latitude: [m.gpsLatitude.0, m.gpsLatitude.1, m.gpsLatitude.2],
                longitude: [m.gpsLongitude.0, m.gpsLongitude.1, m.gpsLongitude.2],
                latitudeRef: String(UnicodeScalar(UInt8(bitPattern: m.gpsLatitudeRef))),
                longitudeRef: String(UnicodeScalar(UInt8(bitPattern: m.gpsLongitudeRef))),
                altitude: m.gpsAltitude, belowSeaLevel: m.gpsBelowSeaLevel)
        } else {
            gps = nil
        }
    }
}
