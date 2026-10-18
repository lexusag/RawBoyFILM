import XCTest
@testable import RawBoyEngine

/** Full-resolution tiled exports of the `.raw/` files into ios/build/test-output, for measuring with PIL. */
final class ExportTests: XCTestCase {
    private static let rawDir = RenderTests.outDir.deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().appendingPathComponent(".raw")

    private func export(_ file: String, _ settings: ExportSettings, suffix: String,
                        edit: (inout DevelopParams) -> Void = { _ in }) throws {
        let url = Self.rawDir.appendingPathComponent(file)
        try XCTSkipUnless(FileManager.default.fileExists(atPath: url.path), "no test file")
        let image = try RawImage.open(url: url)
        defer { image.close() }
        var p = RenderTests.asShotDefaults(image)
        edit(&p)
        let params = ShaderParams.from(p, asShot: RenderTests.asShotDefaults(image), lut: nil,
                                       lens: LensTransform(info: image.lens, params: p),
                                       base: image.createPreview(maxDimension: 512), bloomSources: nil, skinMask: nil)
        try FileManager.default.createDirectory(at: RenderTests.outDir, withIntermediateDirectories: true)
        let start = Date()
        let out = try Exporter().export(image, sourceName: file, params: params, settings: settings,
                                        naming: OutputNaming(suffix: suffix), directory: RenderTests.outDir)
        print("exported", out.path, String(format: "%.1f s", Date().timeIntervalSince(start)))
    }

    func testExports() throws {
        try export("IMG_0805.CR2", ExportSettings(format: .tiff, tiffBits: 16), suffix: "ios-defaults")
        try export("IMG_0805.CR2", ExportSettings(format: .jpeg, jpegQuality: 92, jpegEncoder: .jpegli),
                   suffix: "ios-jpegli")
        try export("IMG_0805.CR2", ExportSettings(format: .jpeg, jpegEncoder: .jpegli, maxLongSide: 1500),
                   suffix: "ios-small")
    }
}
