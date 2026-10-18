import XCTest
@testable import RawBoyEngine

/** Pro's flips (the same cases as FlipTest.kt): a mirror of the picture as shown, crop and straighten included. */
final class FlipTests: XCTestCase {
    private let info = LensInfo.none(width: 6000, height: 4000)

    private func assertClose(_ a: [Double], _ b: [Double], _ accuracy: Double, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(a.count, b.count, file: file, line: line)
        for (x, y) in zip(a, b) { XCTAssertEqual(x, y, accuracy: accuracy, file: file, line: line) }
    }

    func testFlipsMirrorThePictureAsShown() {
        for rotation in 0...3 {
            for vertical in [false, true] {
                var before = DevelopParams()
                before.lensDistortion = 40
                before.straighten = 7.5
                before.rotation = rotation
                before = before.withCrop(CropRect(0.1, 0.2, 0.5, 0.9))
                let a = LensTransform(info: info, params: before)
                let b = LensTransform(info: info, params: CropMath.flip(before, vertical: vertical))
                XCTAssertEqual([a.outputWidth, a.outputHeight], [b.outputWidth, b.outputHeight])
                // Crop edges round to whole pixels, so allow a pixel's worth.
                for (x, y) in [(0.0, 0.0), (Double(a.outputWidth), 0.0), (137.5, 401.25)] {
                    let m = vertical ? (x, Double(a.outputHeight) - y) : (Double(a.outputWidth) - x, y)
                    assertClose(b.source(m.0, m.1), a.source(x, y), 1)
                }
                // Twice is no flip.
                let twice = CropMath.flip(CropMath.flip(before, vertical: vertical), vertical: vertical)
                assertClose(LensTransform(info: info, params: twice).outToImage, a.outToImage, 1e-9)
            }
        }
    }

    func testASidewaysTurnSwapsTheAxis() {
        let plain = LensTransform(info: info, params: CropMath.flip(DevelopParams(), vertical: false))
        XCTAssertTrue(plain.flipHorizontal)
        assertClose(plain.source(100, 50), [5900, 50], 1e-6)
        var turned = DevelopParams()
        turned.rotation = 1
        XCTAssertTrue(CropMath.flip(turned, vertical: false).flipVertical)
    }
}
