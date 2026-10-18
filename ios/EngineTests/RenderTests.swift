import ImageIO
import UniformTypeIdentifiers
import XCTest
@testable import RawBoyEngine

/**
 * Develops the `.raw/` test files through the Metal pipeline (the shader generated from <repo>/shaders) and writes
 * PNGs to ios/build/test-output for measuring against the web/Android renders of the same files.
 */
final class RenderTests: XCTestCase {
    private static let iosDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
    private static let rawDir = iosDir.deletingLastPathComponent().appendingPathComponent(".raw")
    static let outDir = iosDir.appendingPathComponent("build/test-output")

    static func asShotDefaults(_ image: RawImage) -> DevelopParams {
        let white = WhiteBalance.tempTintFromXY(image.asShotWhite[0], image.asShotWhite[1])
        var p = DevelopParams()
        p.temperature = Float(white[0])
        p.tint = Float(white[1])
        return p
    }

    static func writePng(_ image: CGImage, _ name: String) throws -> URL {
        try FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)
        let url = outDir.appendingPathComponent(name)
        guard let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)
        else { throw MetalError(message: "cannot write \(url.path)") }
        CGImageDestinationAddImage(dest, image, nil)
        guard CGImageDestinationFinalize(dest) else { throw MetalError(message: "cannot write \(url.path)") }
        return url
    }

    func testThumbnails() async throws {
        let file = Self.rawDir.appendingPathComponent("IMG_0805.CR2")
        try XCTSkipUnless(FileManager.default.fileExists(atPath: file.path), "no test file")
        let image = try RawImage.open(url: file)
        defer { image.close() }
        let preview = image.createPreview(maxDimension: 2048)
        let base = image.createPreview(maxDimension: 512)
        let bloom = BloomSources(halo: image.createPreview(maxDimension: 512, boostHighlights: true),
                                 tail: image.createPreview(maxDimension: 128, boostHighlights: true))
        let defaults = Self.asShotDefaults(image)
        let renderer = ThumbnailRenderer()

        func render(_ p: DevelopParams, lut: LutData? = nil, _ name: String) async throws {
            let lens = LensTransform(info: image.lens, params: p)
            let params = ShaderParams.from(p, asShot: defaults, lut: lut, lens: lens, base: base, bloomSources: bloom,
                                           skinMask: nil)
            let cg = try await renderer.render(params, preview: preview, maxDimension: 1024)
            print("wrote", try Self.writePng(cg, name).path, cg.width, "x", cg.height)
        }

        try await render(defaults, "IMG_0805-defaults.png")
        var look = defaults
        look.lut = "asset:luts/Kodak Portra 400.cube"
        look.lutAmount = 80
        look.highpassContrast = 30
        look.bloom = 40
        look.grain = 30
        look.rotation = 1
        let lut = try LutLoader.load(look.lut!)
        XCTAssertEqual(lut.size, 33 > 64 ? 64 : lut.size)
        try await render(look, lut: lut, "IMG_0805-portra.png")
    }
}
