import Foundation

/**
 * Mask layers (Capture One style; mirrored in web's Masks.ts and Android's Masks.kt): each layer is a mask with its
 * own adjustments, blended over the global develop. Positions are fractions of the decoded image (display
 * orientation, before crop and lens), so a mask stays on the same scene content when the photo is cropped,
 * straightened, rotated or lens-corrected.
 *
 * - linear: full effect on the (x0, y0) side, fading to none at (x1, y1). curve -100..100 bends the
 *   lines into arcs: + pulls their ends towards the full-effect side (a dome over a horizon), 0 = straight.
 * - radial: centre (cx, cy), radii rx/ry as fractions of the image's long side (circles stay round),
 *   angle in degrees (clockwise), feather 0..100 = the outer part of the radius over which the effect fades.
 * - brush: no gradient; the mask is only what is painted.
 *
 * Any layer can also be painted on: strokes add to its mask (brush) or take away from it (erase, also from the
 * gradient), in order. Strokes belong to one photo's content, so presets never keep them (see forPreset).
 *
 * Adjustments use the global sliders' units (exposure EV -5..5, others -100..100). Temperature and tint are offsets
 * from the global white balance (+ = warmer / more magenta, as the global sliders). All 0 = no change.
 */
public enum MaskShape: Equatable, Sendable {
    case linear(x0: Float, y0: Float, x1: Float, y1: Float, curve: Float = 0)
    case radial(cx: Float, cy: Float, rx: Float, ry: Float, angle: Float, feather: Float)
    case brush

    public var isBrush: Bool { if case .brush = self { true } else { false } }
}

/** One brush or erase stroke. Positions are image fractions, like gradient positions. */
public struct BrushStroke: Equatable, Sendable {
    public var id: String
    public var erase: Bool
    /** Auto mask: paint only on colours like the one under the brush centre. */
    public var auto: Bool
    /** Fraction of the image's long side. */
    public var radius: Float
    /** 0..100 */
    public var hardness: Float
    /** 1..100: how much one pass adds. */
    public var flow: Float
    /** Flat x, y pairs. */
    public var points: [Float]

    public init(id: String, erase: Bool, auto: Bool, radius: Float, hardness: Float, flow: Float, points: [Float]) {
        self.id = id
        self.erase = erase
        self.auto = auto
        self.radius = radius
        self.hardness = hardness
        self.flow = flow
        self.points = points
    }
}

public struct LayerAdjust: Equatable, Sendable {
    public var exposure: Float = 0
    public var contrast: Float = 0
    public var highlights: Float = 0
    public var shadows: Float = 0
    public var temperature: Float = 0
    public var tint: Float = 0
    public var saturation: Float = 0
    public var clarity: Float = 0

    public init(exposure: Float = 0, contrast: Float = 0, highlights: Float = 0, shadows: Float = 0,
                temperature: Float = 0, tint: Float = 0, saturation: Float = 0, clarity: Float = 0) {
        self.exposure = exposure
        self.contrast = contrast
        self.highlights = highlights
        self.shadows = shadows
        self.temperature = temperature
        self.tint = tint
        self.saturation = saturation
        self.clarity = clarity
    }

    public var isZero: Bool { self == LayerAdjust() }
}

public struct MaskLayer: Equatable, Sendable {
    public var id: String
    public var name: String
    public var enabled: Bool = true
    /** 0..100 */
    public var opacity: Float = 100
    public var invert: Bool = false
    public var shape: MaskShape
    public var adjust = LayerAdjust()
    public var strokes: [BrushStroke] = []

    public init(id: String, name: String, enabled: Bool = true, opacity: Float = 100, invert: Bool = false,
                shape: MaskShape, adjust: LayerAdjust = LayerAdjust(), strokes: [BrushStroke] = []) {
        self.id = id
        self.name = name
        self.enabled = enabled
        self.opacity = opacity
        self.invert = invert
        self.shape = shape
        self.adjust = adjust
        self.strokes = strokes
    }

    /** True when the layer can't change the image (off, transparent or no adjustments). */
    public var isIdentity: Bool { !enabled || opacity <= 0 || adjust.isZero }

    /** A gradient, or at least one stroke (an empty brush layer has nothing to mask with). */
    public var hasMask: Bool { !shape.isBrush || !strokes.isEmpty }
}

public enum Masks {
    /** Uniform arrays in the shader are this long. */
    public static let maxLayers = 4

    /**
     * Linear curvature: at curve 100 the lines' ends, half the image's long side from the middle, are pulled this
     * many long sides along the gradient. Shared by the shader packing and the overlay so both bend alike.
     */
    public static let curveScale: Float = 0.5

    /** Brush radius limits, fractions of the long side (the upper one bounds the cost of one dab). */
    public static let minBrushRadius: Float = 0.002
    public static let maxBrushRadius: Float = 0.15

    /** How far (px) a linear mask's lines are pulled along the gradient at perpendicular distance [s] (px). */
    public static func curveOffset(curve: Float, s: Float, long: Float) -> Float {
        curve / 100 * curveScale * 4 * s * s / long
    }

    public static func newId() -> String { UUID().uuidString.lowercased() }

    public static func name(_ shape: MaskShape) -> String {
        switch shape {
        case .linear: "Linear"
        case .radial: "Radial"
        case .brush: "Brush"
        }
    }

    /** A graduated filter for the sky: full at the top, gone by the middle. */
    public static func newLinear(name: String = "Linear") -> MaskLayer {
        MaskLayer(id: newId(), name: name, shape: .linear(x0: 0.5, y0: 0.15, x1: 0.5, y1: 0.5))
    }

    /** A centred ellipse with a soft edge. */
    public static func newRadial(name: String = "Radial") -> MaskLayer {
        MaskLayer(id: newId(), name: name, shape: .radial(cx: 0.5, cy: 0.5, rx: 0.25, ry: 0.18, angle: 0, feather: 50))
    }

    /** Nothing until painted. */
    public static func newBrush(name: String = "Brush") -> MaskLayer {
        MaskLayer(id: newId(), name: name, shape: .brush)
    }

    /** The layers as a preset keeps them: gradients without strokes; brush-only layers are dropped. */
    public static func forPreset(_ layers: [MaskLayer]) -> [MaskLayer] {
        layers.filter { !$0.shape.isBrush }.map { var l = $0; l.strokes = []; return l }
    }

    // Preset files (the same JSON as web's Masks.ts writes). Strokes are never written or read.

    public static func toJson(_ layers: [MaskLayer]) -> [[String: Any]] { forPreset(layers).map(layerJson) }

    private static func layerJson(_ l: MaskLayer) -> [String: Any] {
        let shape: [String: Any]
        switch l.shape {
        case let .linear(x0, y0, x1, y1, curve):
            shape = ["kind": "linear", "x0": num(x0), "y0": num(y0), "x1": num(x1), "y1": num(y1), "curve": num(curve)]
        case let .radial(cx, cy, rx, ry, angle, feather):
            shape = ["kind": "radial", "cx": num(cx), "cy": num(cy), "rx": num(rx), "ry": num(ry),
                     "angle": num(angle), "feather": num(feather)]
        case .brush:
            shape = ["kind": "brush"]
        }
        let a = l.adjust
        return [
            "id": l.id, "name": l.name, "enabled": l.enabled, "opacity": num(l.opacity), "invert": l.invert,
            "shape": shape,
            "adjust": [
                "exposure": num(a.exposure), "contrast": num(a.contrast), "highlights": num(a.highlights),
                "shadows": num(a.shadows), "temperature": num(a.temperature), "tint": num(a.tint),
                "saturation": num(a.saturation), "clarity": num(a.clarity),
            ],
            "strokes": [Any](),
        ]
    }

    /** Floats via their shortest decimal, so 0.1 is written as 0.1. */
    static func num(_ f: Float) -> Double { Double("\(f)") ?? Double(f) }

    /** Layers from untrusted JSON (a preset file), each with a fresh id: invalid ones are dropped, ranges clamped. */
    public static func parseLayers(_ v: Any?) -> [MaskLayer] {
        guard let a = v as? [Any] else { return [] }
        return Array(a.compactMap(parseLayer).prefix(maxLayers))
    }

    private static func parseLayer(_ v: Any) -> MaskLayer? {
        guard let o = v as? [String: Any], let shape = parseShape(o["shape"] as? [String: Any]) else { return nil }
        let a = o["adjust"] as? [String: Any] ?? [:]
        func adj(_ k: String, _ range: Float) -> Float { num(a, k, -range, range) ?? 0 }
        let rawName = (o["name"] as? String ?? "").trimmingCharacters(in: .whitespaces)
        return MaskLayer(
            id: newId(),
            name: rawName.isEmpty ? name(shape) : rawName,
            enabled: o["enabled"] as? Bool ?? true,
            opacity: num(o, "opacity", 0, 100) ?? 100,
            invert: o["invert"] as? Bool == true,
            shape: shape,
            adjust: LayerAdjust(
                exposure: adj("exposure", 5), contrast: adj("contrast", 100), highlights: adj("highlights", 100),
                shadows: adj("shadows", 100), temperature: adj("temperature", 100), tint: adj("tint", 100),
                saturation: adj("saturation", 100), clarity: adj("clarity", 100)),
            strokes: [])  // strokes never come from presets
    }

    private static func parseShape(_ s: [String: Any]?) -> MaskShape? {
        guard let s else { return nil }
        func f(_ k: String, _ lo: Float = -1, _ hi: Float = 2) -> Float? { num(s, k, lo, hi) }
        switch s["kind"] as? String {
        case "linear":
            guard let x0 = f("x0"), let y0 = f("y0"), let x1 = f("x1"), let y1 = f("y1") else { return nil }
            return .linear(x0: x0, y0: y0, x1: x1, y1: y1, curve: f("curve", -100, 100) ?? 0)  // older files: straight
        case "radial":
            guard let cx = f("cx"), let cy = f("cy"), let rx = f("rx", 0.005, 3), let ry = f("ry", 0.005, 3),
                  let angle = f("angle", -360, 360), let feather = f("feather", 0, 100) else { return nil }
            return .radial(cx: cx, cy: cy, rx: rx, ry: ry, angle: angle, feather: feather)
        default:
            return nil
        }
    }

    private static func num(_ o: [String: Any], _ k: String, _ lo: Float, _ hi: Float) -> Float? {
        // JSONSerialization gives NSNumber; Bool is an NSNumber too, so reject it explicitly.
        guard let n = o[k] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
        let f = n.floatValue
        return f.isFinite ? f.clamped(lo, hi) : nil
    }
}
