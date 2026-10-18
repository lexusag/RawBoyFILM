import XCTest
@testable import RawBoyEngine

/** MediaPipe skin segmentation + face landmarks on the `.raw/` portraits; masks are written as PNGs for a look. */
final class SkinTests: XCTestCase {
    private static let rawDir = RenderTests.outDir.deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().appendingPathComponent(".raw")

    private func mask(_ file: String) throws -> SkinMask {
        let url = Self.rawDir.appendingPathComponent(file)
        try XCTSkipUnless(FileManager.default.fileExists(atPath: url.path), "no test file")
        let image = try RawImage.open(url: url)
        defer { image.close() }
        let base = image.createPreview(maxDimension: PhotoSession.baseMaxDimension)
        let start = Date()
        let m = try SkinSegmenter.segment(base, fullWidth: image.width, fullHeight: image.height)
        let skin = m.data.reduce(0, +) / Float(m.data.count)
        print(file, String(format: "%.0f ms", Date().timeIntervalSince(start) * 1000), "hasSkin", m.hasSkin,
              "face diameter", Int(m.faceDiameterPx), "sigma", m.sigmaPx, String(format: "mean skin %.3f", skin))
        let pixels = m.data.flatMap { v -> [UInt8] in let g = UInt8(min(max(v, 0), 1) * 255); return [g, g, g, 255] }
        _ = try RenderTests.writePng(ThumbnailRenderer.cgImage(pixels, m.width, m.height), "skin-\(file).png")
        return m
    }

    func testPortraits() throws {
        let portrait = try mask("retouch.RAF")
        XCTAssertTrue(portrait.hasSkin)
        XCTAssertGreaterThan(portrait.faceDiameterPx, 500)
        let selfie = try mask("P4270088-compressed.dng")
        XCTAssertTrue(selfie.hasSkin)
        let landscape = try mask("IMG_4239.DNG")
        XCTAssertEqual(landscape.faceDiameterPx, 0)
    }
}
