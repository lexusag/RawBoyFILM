import XCTest
@testable import RawBoyEngine

/** The white balance picker (the same cases as WhitePickerTest.kt and web's whitepicker.test.ts). */
final class WhitePickerTests: XCTestCase {
    /** The shader's white balance (ShaderParams.from) at kelvin/tint applied to rgb. */
    private func developed(_ rgb: [Double], _ asShotK: Double, _ asShotTint: Double, _ kelvin: Double, _ tint: Double) -> [Double] {
        let m = WhiteBalance.rebalanceMatrix(
            asShotXY: WhiteBalance.xyFromTempTint(asShotK, asShotTint), targetXY: WhiteBalance.xyFromTempTint(kelvin, tint))
        return Mat3.apply(m, rgb)
    }

    private func patch(_ r: Float, _ g: Float, _ b: Float, _ n: Int = 16) -> [Float] {
        (0..<(n * 3)).map { [r, g, b][$0 % 3] }
    }

    private func white(_ p: WhitePicker.Pick) -> (kelvin: Float, tint: Float) {
        guard case let .white(kelvin, tint) = p else {
            XCTFail("expected a white, got \(p)")
            return (0, 0)
        }
        return (kelvin, tint)
    }

    func testAGreyPatchKeepsTheAsShotWhite() {
        let p = white(WhitePicker.pick(patch(0.18, 0.18, 0.18), asShotKelvin: 5200, asShotTint: 8))
        XCTAssertEqual(p.kelvin, 5200, accuracy: 2)
        XCTAssertEqual(p.tint, 8, accuracy: 0.2)
    }

    func testATintedPatchDevelopsNeutral() {
        for rgb in [[0.20, 0.17, 0.12], [0.10, 0.12, 0.16], [0.15, 0.17, 0.15]] {
            let p = white(WhitePicker.pick(patch(Float(rgb[0]), Float(rgb[1]), Float(rgb[2])), asShotKelvin: 5000, asShotTint: 0))
            let out = developed(rgb, 5000, 0, Double(p.kelvin), Double(p.tint))
            XCTAssertEqual(out[0] / out[1], 1, accuracy: 0.003)
            XCTAssertEqual(out[2] / out[1], 1, accuracy: 0.003)
        }
    }

    func testAWarmerPatchMeansAWarmerLight() {
        XCTAssertLessThan(white(WhitePicker.pick(patch(0.20, 0.17, 0.12), asShotKelvin: 5000, asShotTint: 0)).kelvin, 5000)
    }

    func testClippedPixelsAreLeftOut() {
        let p = white(WhitePicker.pick(patch(0.18, 0.18, 0.18, 4) + patch(1, 0.6, 0.2, 12), asShotKelvin: 5200, asShotTint: 0))
        XCTAssertEqual(p.kelvin, 5200, accuracy: 2)
        XCTAssertEqual(WhitePicker.pick(patch(1, 1, 1), asShotKelvin: 5200, asShotTint: 0), .clipped)
    }

    func testInfraredCastsDevelopNeutral() {
        for rgb in [[0.3, 0.1, 0.02], [0.3, 0.12, 0.2], [0.2, 0.1, 0.25]] {
            let p = white(WhitePicker.pick(patch(Float(rgb[0]), Float(rgb[1]), Float(rgb[2])), asShotKelvin: 5000, asShotTint: 0))
            let out = developed(rgb, 5000, 0, Double(p.kelvin), Double(p.tint))
            XCTAssertEqual(out[0] / out[1], 1, accuracy: 0.003)
            XCTAssertEqual(out[2] / out[1], 1, accuracy: 0.003)
        }
    }

    func testLocusBelow1667KJoinsKimsAndRoundTrips() {
        let a = WhiteBalance.planckianXY(1666.9)
        let b = WhiteBalance.planckianXY(1667.1)
        XCTAssertLessThan(hypot(a[0] - b[0], a[1] - b[1]), 1e-4)
        for k in [1000.0, 1200.0, 1500.0] {
            let xy = WhiteBalance.xyFromTempTint(k, -120)
            let tt = WhiteBalance.tempTintFromXY(xy[0], xy[1])
            XCTAssertEqual(tt[0], k, accuracy: 0.5)
            XCTAssertEqual(tt[1], -120, accuracy: 0.01)
        }
    }

    func testBlackIsNotNeutral() {
        XCTAssertEqual(WhitePicker.pick(patch(0, 0, 0), asShotKelvin: 5200, asShotTint: 0), .notNeutral)
    }

    func testSamplesASmallSquareAroundTheTap() {
        let b = WhitePicker.box(3000, 2000, 6000, 4000)
        XCTAssertEqual([b.x0, b.x1, b.y0, b.y1], [2980, 3020, 1980, 2020])
        let c = WhitePicker.box(0, 0, 6000, 4000)
        XCTAssertEqual([c.x0, c.x1, c.y0, c.y1], [0, 40, 0, 40])
    }
}
