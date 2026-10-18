import XCTest
@testable import RawBoyEngine

/**
 * Auto's Film mode in the standard engine: the stocks and RawBoy's own formation parameters load, and a `.raw/` file
 * developed through shaders/film_form.glsl differs from AgX, has grain, and stays under the print's paper white.
 * Writes PNGs to ios/build/test-output.
 */
final class FilmFormationTests: XCTestCase {
    private static let rawDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().appendingPathComponent(".raw")

    func testStocksCarryTheirFormation() throws {
        let stocks = try FilmStocks.all()
        XCTAssertEqual(stocks.count, 12)
        for s in stocks {
            XCTAssertTrue(s.formation.grainRms.allSatisfy { $0 > 0 }, s.id)
            XCTAssertGreaterThan(s.formation.haloRadiusUm, 0, s.id)
        }
        for look in FilmLooks.all { if let id = look.film { XCTAssertNotNil(try FilmStocks.byId(id), id) } }
    }

    func testFilmRender() async throws {
        let file = Self.rawDir.appendingPathComponent("IMG_0805.CR2")
        try XCTSkipUnless(FileManager.default.fileExists(atPath: file.path), "no test file")
        let image = try RawImage.open(url: file)
        defer { image.close() }
        let preview = image.createPreview(maxDimension: 2048)
        let base = image.createPreview(maxDimension: 512)
        let defaults = RenderTests.asShotDefaults(image)
        let film = try FilmStocks.load(try XCTUnwrap(FilmStocks.byId("portra400")))
        let renderer = ThumbnailRenderer()

        func pixels(_ p: DevelopParams, _ name: String) async throws -> [UInt8] {
            let params = ShaderParams.from(p, asShot: defaults, lut: nil, lens: LensTransform(info: image.lens, params: p),
                                           base: base, bloomSources: nil, skinMask: nil, film: film)
            let cg = try await renderer.render(params, preview: preview, maxDimension: 1024)
            print("wrote", try RenderTests.writePng(cg, name).path)
            var px = [UInt8](repeating: 0, count: cg.width * cg.height * 4)
            let ctx = try XCTUnwrap(CGContext(data: &px, width: cg.width, height: cg.height, bitsPerComponent: 8,
                                              bytesPerRow: cg.width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
            return px
        }
        /** Mean absolute RGB difference in levels. */
        func diff(_ a: [UInt8], _ b: [UInt8]) -> Double {
            let rgb = (0..<a.count).filter { $0 % 4 != 3 }
            return rgb.reduce(0.0) { $0 + abs(Double(a[$1]) - Double(b[$1])) } / Double(rgb.count)
        }

        let agx = try await pixels(defaults, "IMG_0805-form-agx.png")
        var p = LookApplier.apply(defaults, look: FilmLooks.portra400, strength: 100, adjust: .none, hasFace: false)
        p.filmGrain = 0
        let clean = try await pixels(p, "IMG_0805-form-portra400-nograin.png")
        p.filmGrain = 100
        let grain = try await pixels(p, "IMG_0805-form-portra400.png")
        let brightest = (0..<clean.count).filter { $0 % 4 != 3 }.map { clean[$0] }.max() ?? 0
        print("film vs AgX \(diff(agx, clean)) levels, grain \(diff(clean, grain)) levels, brightest \(brightest)")
        XCTAssertGreaterThan(diff(agx, clean), 2, "the film render should differ from AgX")
        XCTAssertGreaterThan(diff(clean, grain), 0.3, "grain should change the image")
        XCTAssertLessThan(brightest, 240, "whites should stay at the print's paper white (~0.85)")
    }
}
