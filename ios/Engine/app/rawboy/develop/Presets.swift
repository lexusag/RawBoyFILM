import Foundation

/**
 * Groups of sliders a preset can hold. Geometry (rotation, flips, straighten, crop) belongs to one photo and is
 * never part of a preset. [key] is the name in preset files (shared with Android's Presets.kt and web's Presets.ts).
 */
public enum PresetGroup: String, CaseIterable, Sendable {
    case light, whiteBalance, curves, color, detail, retouch, lut, lens, masks

    public var key: String { rawValue }

    public var label: String {
        switch self {
        case .light: "Light"
        case .whiteBalance: "White balance"
        case .curves: "Curves"
        case .color: "Color"
        case .detail: "Detail"
        case .retouch: "Retouch"
        case .lut: "LUT"
        case .lens: "Lens"
        case .masks: "Masks (gradients only)"
        }
    }

    public var fields: [String] {
        switch self {
        // The film fields belong to the tone mapper: Film is its fourth choice (film != nil).
        case .light: ["toneMapper", "film", "filmAmount", "filmGrain", "halation",
                      "exposure", "contrast", "highlights", "shadows", "whites", "blacks"]
        case .whiteBalance: ["temperature", "tint"]
        case .curves: ["curves"]
        case .color: ["saturation", "redHue", "redPurity", "greenHue", "greenPurity", "blueHue", "bluePurity", "tintHue",
                      "tintPurity"]
        case .detail: ["denoise", "colorDenoise", "sharpening", "localContrast", "highpassContrast", "grain", "bloom"]
        case .retouch: ["skinRetouch", "blemishRemoval"]
        case .lut: ["lut", "lutAmount"]
        case .lens: ["lensProfile", "lensDistortion", "vignetteAmount", "vignetteMidpoint"]
        case .masks: ["masks"]
        }
    }

    public static func ofKey(_ key: String) -> PresetGroup? { PresetGroup(rawValue: key) }

    /** What the save dialog ticks first: white balance is usually specific to one light, masks to one composition. */
    public static let defaultGroups: Set<PresetGroup> = Set(allCases).subtracting([.whiteBalance, .masks])
}

/** A stored field value: a number, an optional string (LUT id, tone mapper), a switch, mask layers or curves. */
public enum PresetValue: Equatable, Sendable {
    case number(Float)
    case text(String?)
    case flag(Bool)
    case layers([MaskLayer])
    case curves(ToneCurves)
}

/**
 * A named set of slider values. [values] holds only the fields of [groups] (by DevelopParams property name);
 * applying it overrides those and leaves everything else as it is. [id] is local to one device.
 */
public struct Preset: Identifiable, Equatable, Sendable {
    public let id: String
    public var name: String
    public let groups: Set<PresetGroup>
    public let values: [String: PresetValue]

    public init(id: String, name: String, groups: Set<PresetGroup>, values: [String: PresetValue]) {
        self.id = id
        self.name = name
        self.groups = groups
        self.values = values
    }
}

/** Capturing, applying and the JSON file format of Presets (mirrored in Android's Presets.kt and web's Presets.ts). */
public enum Presets {
    public static let format = "rawboy-preset"
    public static let version = 1

    public static func capture(_ name: String, _ params: DevelopParams, _ groups: Set<PresetGroup>) -> Preset {
        var values: [String: PresetValue] = [:]
        for g in PresetGroup.allCases where groups.contains(g) {
            for f in g.fields { values[f] = get(params, f) }
        }
        return Preset(id: newId(), name: name.trimmingCharacters(in: .whitespaces), groups: groups, values: values)
    }

    /** [params] with the preset's stored fields; fields it doesn't hold (or doesn't know) stay. */
    public static func apply(_ preset: Preset, _ params: DevelopParams) -> DevelopParams {
        preset.values.reduce(params) { set($0, $1.key, $1.value) }
    }

    public static func newId() -> String { UUID().uuidString.lowercased() }

    static func toJson(_ p: Preset) -> [String: Any] {
        var values: [String: Any] = [:]
        for (k, v) in p.values {
            switch v {
            // Floats via their shortest decimal, so 0.1 is written as 0.1, not 0.10000000149011612.
            case let .number(f): values[k] = Masks.num(f)
            case let .text(s): values[k] = s ?? NSNull()
            case let .flag(b): values[k] = b
            case let .layers(l): values[k] = Masks.toJson(l)
            case let .curves(c): values[k] = c.toJson()
            }
        }
        return [
            "format": format, "version": version, "name": p.name,
            "groups": PresetGroup.allCases.filter { p.groups.contains($0) }.map(\.key),
            "values": values,
        ]
    }

    /** One preset as an object, several as an array (what "Export all" writes). */
    public static func encode(_ presets: [Preset]) -> String {
        let object: Any = presets.count == 1 ? toJson(presets[0]) : presets.map(toJson)
        let data = (try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }

    public struct DecodeError: LocalizedError {
        public let message: String
        public var errorDescription: String? { message }
    }

    /**
     * Presets in [text] (an object or an array of them), each with a fresh id. Unknown groups and
     * fields are skipped, so files from newer versions still load. Throws on text that isn't a preset file.
     */
    public static func decode(_ text: String) throws -> [Preset] {
        guard let json = try? JSONSerialization.jsonObject(with: Data(text.utf8)) else {
            throw DecodeError(message: "Not a RawBoy preset")
        }
        let objects: [Any] = (json as? [Any]) ?? [json]
        return try objects.map { o in
            guard let o = o as? [String: Any] else { throw DecodeError(message: "Not a RawBoy preset") }
            return try fromJson(o)
        }
    }

    private static func fromJson(_ o: [String: Any]) throws -> Preset {
        guard o["format"] as? String == format else { throw DecodeError(message: "Not a RawBoy preset") }
        let groups = Set(((o["groups"] as? [Any]) ?? []).compactMap { ($0 as? String).flatMap(PresetGroup.ofKey) })
        let v = o["values"] as? [String: Any] ?? [:]
        var values: [String: PresetValue] = [:]
        for f in groups.flatMap(\.fields) {
            guard let raw = v[f] else { continue }
            if f == "masks" {
                values[f] = .layers(Masks.parseLayers(raw))
            } else if f == "curves" {
                if let c = ToneCurves.parse(raw) { values[f] = .curves(c) }
            } else if raw is NSNull {
                values[f] = .text(nil)
            } else if let n = raw as? NSNumber {
                values[f] = CFGetTypeID(n) == CFBooleanGetTypeID() ? .flag(n.boolValue) : .number(n.floatValue)
            } else if let s = raw as? String {
                values[f] = .text(s)
            }
        }
        // Presets from before the separate colour denoise had one denoise for both: colour follows it.
        if values["denoise"] != nil, groups.contains(where: { $0.fields.contains("colorDenoise") }), v["colorDenoise"] == nil {
            values["colorDenoise"] = .text(nil)
        }
        let name = (o["name"] as? String ?? "").trimmingCharacters(in: .whitespaces)
        return Preset(id: newId(), name: name.isEmpty ? "Preset" : name, groups: groups, values: values)
    }

    private static let numberFields: [String: WritableKeyPath<DevelopParams, Float>] = [
        "exposure": \.exposure, "contrast": \.contrast, "highlights": \.highlights, "shadows": \.shadows,
        "whites": \.whites, "blacks": \.blacks, "temperature": \.temperature, "tint": \.tint,
        "saturation": \.saturation, "redHue": \.redHue, "redPurity": \.redPurity, "greenHue": \.greenHue,
        "greenPurity": \.greenPurity, "blueHue": \.blueHue, "bluePurity": \.bluePurity, "tintHue": \.tintHue,
        "tintPurity": \.tintPurity, "denoise": \.denoise, "sharpening": \.sharpening, "localContrast": \.localContrast,
        "highpassContrast": \.highpassContrast, "grain": \.grain, "bloom": \.bloom, "skinRetouch": \.skinRetouch,
        "blemishRemoval": \.blemishRemoval, "lutAmount": \.lutAmount, "lensDistortion": \.lensDistortion,
        "vignetteAmount": \.vignetteAmount, "vignetteMidpoint": \.vignetteMidpoint,
        "filmAmount": \.filmAmount, "filmGrain": \.filmGrain, "halation": \.halation,
    ]

    private static func get(_ p: DevelopParams, _ field: String) -> PresetValue {
        switch field {
        case "toneMapper": .text(p.toneMapper.key)
        case "lut": .text(p.lut)
        case "film": .text(p.film)
        case "lensProfile": .flag(p.lensProfile)
        case "colorDenoise": p.colorDenoise.map { PresetValue.number($0) } ?? .text(nil)
        case "masks": .layers(Masks.forPreset(p.masks))  // strokes stay with the photo
        case "curves": .curves(p.curves)
        default: .number(p[keyPath: numberFields[field]!])
        }
    }

    /** A value of the wrong type (a hand-edited file) leaves the field unchanged. */
    private static func set(_ p: DevelopParams, _ field: String, _ value: PresetValue) -> DevelopParams {
        var out = p
        switch (field, value) {
        case let ("lut", .text(s)): out.lut = s.map(RetiredLooks.lut)
        case let ("film", .text(s)): out.film = s.map(RetiredLooks.stock)
        case let ("lensProfile", .flag(b)): out.lensProfile = b
        case let ("colorDenoise", .number(n)): if n.isFinite { out.colorDenoise = n }
        case ("colorDenoise", .text(.none)): out.colorDenoise = nil
        case let ("curves", .curves(c)): out.curves = c
        case let ("toneMapper", .text(s)): if let t = ToneMapper.fromKey(s) { out.toneMapper = t }
        case let ("masks", .layers(layers)):
            // Fresh ids, so two photos never share a layer id.
            out.masks = layers.prefix(Masks.maxLayers).map { var l = $0; l.id = Masks.newId(); return l }
        case let (f, .number(n)): if n.isFinite, let kp = numberFields[f] { out[keyPath: kp] = n }
        default: break
        }
        return out
    }
}
