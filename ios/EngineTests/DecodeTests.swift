import XCTest
@testable import RawBoyEngine

/**
 * Decodes the repo's `.raw/` test files (git-ignored; the Simulator reads the Mac's file system) and prints the
 * same line as web/scripts/decode-check.mjs, so iOS and web decodes of the same C++ can be compared.
 */
final class DecodeTests: XCTestCase {
    private static let rawDir = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent(".raw")

    func testDecodesTestFiles() throws {
        let files = (try? FileManager.default.contentsOfDirectory(at: Self.rawDir, includingPropertiesForKeys: nil))?
            .filter { !$0.lastPathComponent.hasPrefix(".") && !$0.hasDirectoryPath }  // display/: DisplaySourceTests
            .sorted { $0.lastPathComponent < $1.lastPathComponent } ?? []
        try XCTSkipIf(files.isEmpty, "no test files in \(Self.rawDir.path)")
        print("DNG SDK:", RawImage.hasDngSdk)
        for file in files {
            let start = Date()
            let image = try RawImage.open(url: file)
            let ms = Int(Date().timeIntervalSince(start) * 1000)
            let base = image.createPreview(maxDimension: 512)
            var mean = [Double](repeating: 0, count: 3)
            for (i, h) in base.pixels.enumerated() { mean[i % 3] += Double(Float(Float16(bitPattern: h))) }
            let n = Double(base.pixels.count / 3)
            let lens = image.lens
            print(file.lastPathComponent, "\(ms) ms", image.description, "| iso", image.iso,
                  "| lens", "\"\(lens.lensName)\"",
                  "| white", image.asShotWhite.map { String(format: "%.4f", $0) }.joined(separator: ","),
                  "| orientation", lens.orientation, "sensor", lens.sensorWidth, "x", lens.sensorHeight,
                  "warp", lens.warp != nil ? 1 : 0, "vig", lens.vignette != nil ? 1 : 0,
                  "shading", lens.shading != nil ? 1 : 0, "gainMaps", lens.gainMapsApplied,
                  "| mean", mean.map { String(format: "%.4f", $0 / n) }.joined(separator: ","),
                  "|", image.shotInfo.line())
            XCTAssertGreaterThan(image.width, 0)
            XCTAssertTrue(mean.allSatisfy { $0.isFinite && $0 > 0 }, file.lastPathComponent)
            image.close()
        }
    }
}
