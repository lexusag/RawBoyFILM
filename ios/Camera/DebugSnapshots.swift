#if DEBUG
import Foundation
import ImageIO
#if FILM_EDITION
import RawBoyFilmEngine
#else
import RawBoyEngine
#endif
import UniformTypeIdentifiers

/**
 * Debug builds: for each shot, a 1024 px copy of the photo and the viewfinder's 512 px frame at the shutter (linear,
 * shown with a plain sRGB curve) in Documents/debug, to compare orientation and exposure off the phone:
 * `xcrun devicectl device copy from --device <id> --domain-type appDataContainer --domain-identifier
 * app.rawboy.camera --source Documents/debug --destination <dir>`.
 */
enum DebugSnapshots {
    static func save(_ name: String, jpeg: URL, viewfinder: PreviewImage?, dng: Data) {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("debug")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? dng.write(to: dir.appendingPathComponent("\(name).dng"))
        if let src = CGImageSourceCreateWithURL(jpeg as CFURL, nil),
           let small = CGImageSourceCreateThumbnailAtIndex(src, 0, [
               kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 1024,
               kCGImageSourceCreateThumbnailWithTransform: true,
           ] as CFDictionary) {
            write(small, dir.appendingPathComponent("\(name)-photo.jpg"), UTType.jpeg)
        }
        guard let v = viewfinder else { return }
        var rgba = [UInt8](repeating: 255, count: v.width * v.height * 4)
        for i in 0..<(v.width * v.height) {
            for c in 0..<3 {
                let x = max(0, Float(Float16(bitPattern: v.pixels[i * 3 + c])))
                let e = x <= 0.0031308 ? x * 12.92 : 1.055 * powf(x, 1 / 2.4) - 0.055
                rgba[i * 4 + c] = UInt8(min(max(e, 0), 1) * 255)
            }
        }
        if let provider = CGDataProvider(data: Data(rgba) as CFData),
           let img = CGImage(width: v.width, height: v.height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: v.width * 4,
                             space: CGColorSpace(name: CGColorSpace.sRGB)!,
                             bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                             provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent) {
            write(img, dir.appendingPathComponent("\(name)-viewfinder.png"), UTType.png)
        }
    }

    private static func write(_ image: CGImage, _ url: URL, _ type: UTType) {
        guard let d = CGImageDestinationCreateWithURL(url as CFURL, type.identifier as CFString, 1, nil) else { return }
        CGImageDestinationAddImage(d, image, nil)
        CGImageDestinationFinalize(d)
    }
}
#endif
