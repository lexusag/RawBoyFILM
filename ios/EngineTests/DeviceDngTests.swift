import XCTest
@testable import RawBoyEngine

/**
 * Raws pulled off a phone (ios/build/device-debug, from the Camera app's debug snapshots): decoded and written as
 * plain sRGB previews (no develop) to look at what the decoder makes of them.
 */
final class DeviceDngTests: XCTestCase {
    func testDeviceDngs() throws {
        let dir = RenderTests.outDir.deletingLastPathComponent().appendingPathComponent("device-debug")
        let files = ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).filter { $0.hasSuffix(".dng") }.sorted()
        try XCTSkipIf(files.isEmpty, "no device DNGs")
        for f in files {
            let image = try RawImage.open(url: dir.appendingPathComponent(f))
            defer { image.close() }
            let p = image.createPreview(maxDimension: 768)
            var mean = [Double](repeating: 0, count: 3)
            var rgba = [UInt8](repeating: 255, count: p.width * p.height * 4)
            for i in 0..<(p.width * p.height) {
                for c in 0..<3 {
                    let x = max(0, Float(Float16(bitPattern: p.pixels[i * 3 + c])))
                    mean[c] += Double(x)
                    let v = x * 3  // a rough fixed lift so dark raws are visible
                    let e = v <= 0.0031308 ? v * 12.92 : 1.055 * powf(v, 1 / 2.4) - 0.055
                    rgba[i * 4 + c] = UInt8(min(max(e, 0), 1) * 255)
                }
            }
            let n = Double(p.width * p.height)
            print("device", f, image.description, "white", image.asShotWhite, "iso", image.iso, "baseline", image.baselineExposure,
                  "mean", mean.map { String(format: "%.4f", $0 / n) })
            _ = try RenderTests.writePng(ThumbnailRenderer.cgImage(rgba, p.width, p.height), "device-\(f).png")
            // The 512 px linear copy the auto develop analyses, as float32 RGB after an Int32 width and height, to
            // compare with the viewfinder's copy at the shutter (Documents/debug "-viewfinder.png").
            let base = image.createPreview(maxDimension: PhotoSession.baseMaxDimension)
            var data = Data()
            withUnsafeBytes(of: Int32(base.width)) { data.append(contentsOf: $0) }
            withUnsafeBytes(of: Int32(base.height)) { data.append(contentsOf: $0) }
            let floats = base.pixels.map { Float(Float16(bitPattern: $0)) }
            floats.withUnsafeBytes { data.append(contentsOf: $0) }
            try data.write(to: RenderTests.outDir.appendingPathComponent("device-\(f).f32"))
        }
    }
}
