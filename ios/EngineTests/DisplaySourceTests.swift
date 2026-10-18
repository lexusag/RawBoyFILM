import ImageIO
import XCTest
@testable import RawBoyEngine

/**
 * JPEG and HEIC input (display sources, see display_inverse.cpp): decodes the files in `.raw/display/` (git-ignored),
 * checks the flag, size and orientation against ImageIO, and writes the defaults render (no edits) next to ImageIO's
 * own decode into ios/build/test-output, `<name>-rawboy.png` and `<name>-imageio.png`, for measuring with PIL: they
 * should match.
 */
final class DisplaySourceTests: XCTestCase {
    private static let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().appendingPathComponent(".raw/display")

    func testDisplaySources() async throws {
        let files = (try? FileManager.default.contentsOfDirectory(at: Self.dir, includingPropertiesForKeys: nil))?
            .filter { ["jpg", "jpeg", "heic", "heif"].contains($0.pathExtension.lowercased()) }
            .sorted { $0.lastPathComponent < $1.lastPathComponent } ?? []
        try XCTSkipIf(files.isEmpty, "no test files in \(Self.dir.path)")
        let renderer = ThumbnailRenderer()
        for file in files {
            let name = file.deletingPathExtension().lastPathComponent
            let start = Date()
            let image = try RawImage.open(url: file)
            defer { image.close() }
            print(file.lastPathComponent, "\(Int(Date().timeIntervalSince(start) * 1000)) ms", image.description, "|",
                  image.shotInfo.line())
            XCTAssertTrue(image.displayReferred, name)

            // ImageIO's upright decode, the reference.
            let source = try XCTUnwrap(CGImageSourceCreateWithURL(file as CFURL, nil))
            let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                            kCGImageSourceCreateThumbnailWithTransform: true,
                                            kCGImageSourceThumbnailMaxPixelSize: 1024]
            let reference = try XCTUnwrap(CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary))
            XCTAssertEqual(image.width > image.height, reference.width > reference.height, "\(name): orientation")
            _ = try RenderTests.writePng(reference, "\(name)-imageio.png")

            let defaults = PhotoSession.asShotDefaults(image)
            XCTAssertTrue(defaults.displaySource)
            let preview = image.createPreview(maxDimension: 2048)
            let params = ShaderParams.from(defaults, asShot: defaults, lut: nil,
                                           lens: LensTransform(info: image.lens, params: defaults),
                                           base: image.createPreview(maxDimension: 512), bloomSources: nil,
                                           skinMask: nil)
            let cg = try await renderer.render(params, preview: preview, maxDimension: 1024)
            print("wrote", try RenderTests.writePng(cg, "\(name)-rawboy.png").path)
        }
    }
}
