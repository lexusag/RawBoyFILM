import XCTest
@testable import RawBoyEngine

/** The measured auto denoise (AutoTone.measuredNoiseSettings, native estimateNoise); same cases as AutoToneTest.kt. */
final class NoiseTests: XCTestCase {
    func testMeasuredNoiseSetsBothDenoisesAndEasesSharpening() {
        let g9 = AutoTone.measuredNoiseSettings(NoiseLevel(luma: 0.0107, chroma: 0.0141), exposure: 2)
        XCTAssertEqual([g9.0, g9.1, g9.2], [55, 93, 8])
        let pixel = AutoTone.measuredNoiseSettings(NoiseLevel(luma: 0.0023, chroma: 0.0019), exposure: 0)
        XCTAssertEqual([pixel.0, pixel.1, pixel.2], [6, 6, 15])
        XCTAssertEqual(AutoTone.measuredNoiseSettings(NoiseLevel(luma: 0.05, chroma: 0.05), exposure: 0).1, 100)
    }

    /** The same C++ as the web build: IMG_4914.CR2 (Canon G9, ISO 400) measured 0.0107 / 0.0141 there. */
    func testMeasuresTheNoisyCanon() throws {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent(".raw/IMG_4914.CR2")
        try XCTSkipUnless(FileManager.default.fileExists(atPath: file.path), "no \(file.path)")
        let image = try RawImage.open(url: file)
        defer { image.close() }
        let noise = image.measureNoise()
        XCTAssertEqual(noise.luma, 0.0107, accuracy: 0.0003)
        XCTAssertEqual(noise.chroma, 0.0141, accuracy: 0.0003)
    }
}
