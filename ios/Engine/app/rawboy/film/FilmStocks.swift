import Foundation

/**
 * A film stock simulated with spektrafilm (https://github.com/andreavolpato/spektrafilm, Andrea Volpato), as baked by
 * tools/spektrafilm_luts into luts/spektrafilm: a film LUT (Apple Log -> normalised film density) and a print LUT
 * (density -> sRGB), plus spektrafilm's grain and halation parameters for shaders/film/film.glsl (RawBoy Film) and
 * RawBoy's own ([formation]) for shaders/film_form.glsl (the standard apps). Mirrors Android's FilmStocks.kt.
 */
public final class FilmStock: @unchecked Sendable {
    public struct Grain {
        public let particleAreaUm2: Float
        public let particleScale: [Float]
        public let densityMin: [Float]
        /** Maximum density of each layer's characteristic curve. */
        public let densityMax: [Float]
        public let uniformity: [Float]
        public let blurPx: Float
    }

    public struct Halation {
        public let strength: [Float]
        public let firstSigmaUm: Float
        public let bounceDecay: Float
        public let scatterTailUm: [Float]
        public let scatterTailWeight: [Float]
    }

    /**
     * Parameters of shaders/film_form.glsl, from formation.json (RawBoy's data, not spektrafilm's): per film layer
     * (C, M, Y) RMS granularity at net density 1 through a 48 um aperture, per channel the fraction of light the base
     * returns and its disc radius, and the in-emulsion scatter.
     */
    public struct Formation {
        public let grainRms: [Float]
        public let halation: [Float]
        public let haloRadiusUm: Float
        public let scatter: [Float]
        public let scatterUm: Float

        static let none = Formation(grainRms: [0, 0, 0], halation: [0, 0, 0], haloRadiusUm: 0, scatter: [0, 0, 0], scatterUm: 0)

        /** The stock [id] in formation.json, its own values over the file's defaults. */
        static func parse(_ json: [String: Any], id: String) -> Formation {
            guard let d = json["defaults"] as? [String: Any], let s = (json["stocks"] as? [String: Any])?[id] as? [String: Any],
                  let rms = (s["rms"] as? NSNumber)?.floatValue else { return none }
            func nums(_ k: String) -> [Float] { ((s[k] ?? d[k]) as? [NSNumber])?.map(\.floatValue) ?? [0, 0, 0] }
            func num(_ k: String) -> Float { ((s[k] ?? d[k]) as? NSNumber)?.floatValue ?? 0 }
            let layers = (d["layerGrain"] as? [NSNumber])?.map(\.floatValue) ?? [1, 1, 1]
            return Formation(grainRms: layers.map { rms * $0 }, halation: nums("halation"), haloRadiusUm: num("haloRadiusUm"),
                             scatter: nums("scatter"), scatterUm: num("scatterUm"))
        }
    }

    public let id: String
    public let name: String
    /** spektrafilm profile names; [print] is nil for slides, which are scanned directly. */
    public let film: String
    public let print: String?
    public let filmLut: String
    public let printLut: String
    /** Density wire of the film LUT's output: D = code * (max - min) + min. */
    public let densityMin: [Float]
    public let densityMax: [Float]
    public let grain: Grain
    public let halation: Halation
    /** RawBoy's own grain, halation and scatter (shaders/film_form.glsl, the standard apps). */
    public let formation: Formation

    static let assetDir = "luts/spektrafilm"

    /** spektrafilm's default format: a 35 mm frame, 36 mm on the long side. Sizes grain and halation. */
    public static let frameWidthUm: Float = 36000

    public var filmLutId: String { "asset:\(Self.assetDir)/\(filmLut)" }
    public var printLutId: String { "asset:\(Self.assetDir)/\(printLut)" }

    init(json j: [String: Any], formation f: [String: Any]? = nil) throws {
        func str(_ k: String, _ o: [String: Any] = j) throws -> String {
            guard let v = o[k] as? String else { throw LutError(message: "stocks.json: no \(k)") }
            return v
        }
        func num(_ k: String, _ o: [String: Any]) throws -> Float {
            guard let v = o[k] as? NSNumber else { throw LutError(message: "stocks.json: no \(k)") }
            return v.floatValue
        }
        func nums(_ k: String, _ o: [String: Any] = j) throws -> [Float] {
            guard let v = o[k] as? [NSNumber] else { throw LutError(message: "stocks.json: no \(k)") }
            return v.map(\.floatValue)
        }
        guard let g = j["grain"] as? [String: Any], let h = j["halation"] as? [String: Any] else {
            throw LutError(message: "stocks.json: no grain/halation")
        }
        id = try str("id")
        name = try str("name")
        film = try str("film")
        print = j["print"] as? String
        filmLut = try str("filmLut")
        printLut = try str("printLut")
        densityMin = try nums("densityMin")
        densityMax = try nums("densityMaxWire")
        grain = Grain(particleAreaUm2: try num("particleAreaUm2", g), particleScale: try nums("particleScale", g),
                      densityMin: try nums("densityMin", g), densityMax: try nums("densityMax", g),
                      uniformity: try nums("uniformity", g), blurPx: try num("blurPx", g))
        // spektrafilm allows a sigma per channel; its stocks use one for all three.
        halation = Halation(strength: try nums("strength", h), firstSigmaUm: try nums("firstSigmaUm", h)[0],
                            bounceDecay: try num("bounceDecay", h), scatterTailUm: try nums("scatterTailUm", h),
                            scatterTailWeight: try nums("scatterTailWeight", h))
        let stockId = id
        formation = f.map { Formation.parse($0, id: stockId) } ?? .none
    }
}

/** A stock with both its LUTs loaded, ready for ShaderParams. */
public final class FilmData: @unchecked Sendable {
    public let stock: FilmStock
    public let film: LutData
    public let print: LutData

    public init(stock: FilmStock, film: LutData, print: LutData) {
        self.stock = stock
        self.film = film
        self.print = print
    }
}

/** The stocks in luts/spektrafilm/stocks.json (generated by tools/spektrafilm_luts/generate.py). */
public enum FilmStocks {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var cached: [FilmStock]?

    public static func all() throws -> [FilmStock] {
        lock.lock()
        defer { lock.unlock() }
        if let cached { return cached }
        func json(_ name: String) throws -> [String: Any]? {
            guard let url = LutRepository.resourceBundle.resourceURL?
                .appendingPathComponent(FilmStock.assetDir).appendingPathComponent(name) else {
                throw LutError(message: "No film stocks bundled")
            }
            return try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        }
        let formation = try json("formation.json")
        guard let list = try json("stocks.json")?["stocks"] as? [[String: Any]] else { throw LutError(message: "stocks.json: no stocks") }
        let stocks = try list.map { try FilmStock(json: $0, formation: formation) }
        cached = stocks
        return stocks
    }

    public static func byId(_ id: String) throws -> FilmStock? {
        let key = RetiredLooks.stock(id)
        return try all().first { $0.id == key }
    }

    /** Loads both LUTs (LutLoader is locked and slow; callers cache the result). */
    public static func load(_ stock: FilmStock) throws -> FilmData {
        FilmData(stock: stock, film: try LutLoader.load(stock.filmLutId), print: try LutLoader.load(stock.printLutId))
    }
}
