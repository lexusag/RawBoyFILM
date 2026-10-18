// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import XCTest
@testable import RawBoyFilmEngine

/**
 * RawBoyFilmEngine (RawBoy Film): the spektrafilm stocks parse, and a `.raw/` file developed through the film shader
 * (shaders/film/film.glsl) differs from AgX, keeps whites at the print's paper white and has grain. Writes PNGs to
 * ios/build/test-output for comparing with Android's RawBoy Film.
 */
final class FilmRenderTests: XCTestCase {
    private static let iosDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
    private static let rawDir = iosDir.deletingLastPathComponent().appendingPathComponent(".raw")
    private static let outDir = iosDir.appendingPathComponent("build/test-output")

    func testStocksParse() throws {
        let stocks = try FilmStocks.all()
        XCTAssertEqual(stocks.count, 12)
        XCTAssertEqual(Set(stocks.filter { $0.print == nil }.map(\.id)),
                       ["velvia100", "provia100", "ektachrome100", "kodachrome64"])
        for look in FilmLooks.all { if let id = look.film { XCTAssertNotNil(try FilmStocks.byId(id), id) } }
        let portra = try XCTUnwrap(FilmStocks.byId("portra400"))
        let data = try FilmStocks.load(portra)
        XCTAssertEqual(data.film.size, 33)
        XCTAssertEqual(data.print.size, 33)
    }

    func testLicencesAreTheFilmEditions() {
        XCTAssertTrue(LicensesView.notices.hasPrefix("RawBoy Film"))
        XCTAssertFalse(LicensesView.notices.contains("DNG technology"))
        XCTAssertFalse(RawImage.hasDngSdk)
    }

    func testFilmRender() async throws {
        let file = Self.rawDir.appendingPathComponent("IMG_0805.CR2")
        try XCTSkipUnless(FileManager.default.fileExists(atPath: file.path), "no test file")
        let image = try RawImage.open(url: file)
        defer { image.close() }
        let preview = image.createPreview(maxDimension: 2048)
        let base = image.createPreview(maxDimension: 512)
        let white = WhiteBalance.tempTintFromXY(image.asShotWhite[0], image.asShotWhite[1])
        var defaults = DevelopParams()
        defaults.temperature = Float(white[0])
        defaults.tint = Float(white[1])
        let film = try FilmStocks.load(try XCTUnwrap(FilmStocks.byId("portra400")))
        let renderer = ThumbnailRenderer()

        func render(_ p: DevelopParams, _ name: String) async throws -> [UInt8] {
            let params = ShaderParams.from(p, asShot: defaults, lut: nil, lens: LensTransform(info: image.lens, params: p),
                                           base: base, bloomSources: nil, skinMask: nil, film: film)
            let cg = try await renderer.render(params, preview: preview, maxDimension: 1024)
            try FileManager.default.createDirectory(at: Self.outDir, withIntermediateDirectories: true)
            let url = Self.outDir.appendingPathComponent(name)
            let dest = try XCTUnwrap(CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil))
            CGImageDestinationAddImage(dest, cg, nil)
            XCTAssertTrue(CGImageDestinationFinalize(dest))
            print("wrote", url.path)
            var px = [UInt8](repeating: 0, count: cg.width * cg.height * 4)
            let ctx = try XCTUnwrap(CGContext(data: &px, width: cg.width, height: cg.height, bitsPerComponent: 8,
                                              bytesPerRow: cg.width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
            return px
        }
        func meanAbsDiff(_ a: [UInt8], _ b: [UInt8]) -> Double {
            var sum = 0.0
            for i in stride(from: 0, to: a.count, by: 4) { for c in 0..<3 { sum += abs(Double(a[i + c]) - Double(b[i + c])) } }
            return sum / Double(a.count / 4 * 3)
        }

        let agx = try await render(defaults, "IMG_0805-film-agx.png")
        var p = defaults
        p.film = "portra400"
        p.filmAmount = 100
        p.filmGrain = 0
        let clean = try await render(p, "IMG_0805-film-portra400-nograin.png")
        p.filmGrain = 100
        let grain = try await render(p, "IMG_0805-film-portra400.png")

        let vsAgx = meanAbsDiff(agx, clean)
        let grainDiff = meanAbsDiff(clean, grain)
        let maxClean = stride(from: 0, to: clean.count, by: 4).map { max(clean[$0], clean[$0 + 1], clean[$0 + 2]) }.max() ?? 0
        print("film vs AgX \(vsAgx) levels, grain \(grainDiff) levels, brightest \(maxClean)")
        XCTAssertGreaterThan(vsAgx, 2, "the film render should differ from AgX")
        XCTAssertGreaterThan(grainDiff, 0.3, "grain should change the image")
        XCTAssertLessThan(maxClean, 240, "whites should stay at the print's paper white (~0.85)")
    }
}
