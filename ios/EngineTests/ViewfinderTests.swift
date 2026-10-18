import CoreVideo
import ImageIO
import MetalKit
import XCTest
@testable import RawBoyEngine

/**
 * The viewfinder's GPU side without a camera: a JPEG export stands in for a camera frame (BGRA, sRGB), goes through
 * pass 1 (linearise), the small copies and the 512 px readback, which must undo the sRGB curve: its mean luminance
 * ≈ scale × the JPEG's linear luminance.
 */
final class ViewfinderTests: XCTestCase {
    func testLinearizeAndReadback() throws {
        let jpeg = RenderTests.outDir.appendingPathComponent("IMG_0805-ios-small.jpg")
        try XCTSkipUnless(FileManager.default.fileExists(atPath: jpeg.path), "run ExportTests first")
        let src = CGImageSourceCreateWithURL(jpeg as CFURL, nil)!
        let image = CGImageSourceCreateImageAtIndex(src, 0, nil)!
        let w = image.width
        let h = image.height
        var buffer: CVPixelBuffer?
        CVPixelBufferCreate(nil, w, h, kCVPixelFormatType_32BGRA,
                            [kCVPixelBufferMetalCompatibilityKey: true, kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary,
                            &buffer)
        let pb = try XCTUnwrap(buffer)
        CVPixelBufferLockBaseAddress(pb, [])
        let ctx = CGContext(data: CVPixelBufferGetBaseAddress(pb), width: w, height: h, bitsPerComponent: 8,
                            bytesPerRow: CVPixelBufferGetBytesPerRow(pb), space: CGColorSpace(name: CGColorSpace.sRGB)!,
                            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)!
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        // The JPEG's own mean linear luminance (sRGB primaries), from the same pixels.
        let bytes = CVPixelBufferGetBaseAddress(pb)!.assumingMemoryBound(to: UInt8.self)
        let stride = CVPixelBufferGetBytesPerRow(pb)
        func lin(_ v: UInt8) -> Double {
            let c = Double(v) / 255
            return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        var expected = 0.0
        for y in 0..<h {
            for x in 0..<w {
                let p = bytes + y * stride + x * 4  // B, G, R, A
                expected += 0.2126 * lin(p[2]) + 0.7152 * lin(p[1]) + 0.0722 * lin(p[0])
            }
        }
        expected /= Double(w * h)
        CVPixelBufferUnlockBaseAddress(pb, [])

        let scale: Float = 0.35
        let renderer = ViewfinderRenderer()
        renderer.frame = ViewfinderRenderer.Frame(fullWidth: 4032, fullHeight: 3024, scale: scale)
        let got = expectation(description: "base")
        var base: PreviewImage?
        renderer.onBase = { b in
            base = b
            got.fulfill()
        }
        let view = MTKView(frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        renderer.attach(view)
        view.isPaused = true
        renderer.enqueue(pb)
        renderer.draw(in: view)
        wait(for: [got], timeout: 10)

        let b = try XCTUnwrap(base)
        XCTAssertEqual(max(b.width, b.height), 512)
        var mean = 0.0
        for i in 0..<(b.width * b.height) {
            let r = Double(Float(Float16(bitPattern: b.pixels[i * 3])))
            let g = Double(Float(Float16(bitPattern: b.pixels[i * 3 + 1])))
            let bl = Double(Float(Float16(bitPattern: b.pixels[i * 3 + 2])))
            mean += 0.2627 * r + 0.6780 * g + 0.0593 * bl  // Rec.2020 luminance: the same light as above
        }
        mean /= Double(b.width * b.height)
        print("viewfinder base \(b.width)x\(b.height): mean luminance \(mean), expected \(expected * Double(scale))")
        XCTAssertEqual(mean, expected * Double(scale), accuracy: expected * Double(scale) * 0.02)
    }
}
