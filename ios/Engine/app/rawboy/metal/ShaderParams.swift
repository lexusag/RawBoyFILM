import Foundation

/** Uniform values derived from DevelopParams; immutable so it can cross threads. */
public final class ShaderParams: @unchecked Sendable {
    public let whiteBalance: [Float]  // column-major 3x3
    public let exposure: Float
    public let contrast: Float
    public let saturation: Float
    public let shadows: Float
    public let highlights: Float
    public let blacks: Float
    public let whites: Float
    public let denoise: Float
    /** 0..1: the coarse denoise stage, for the slider's top third (see wideDenoiseFrom). */
    public let denoiseWide: Float
    /** Colour (chroma) denoise and its coarse stage, as denoise and denoiseWide. */
    public let colorDenoise: Float
    public let colorDenoiseWide: Float
    public let grain: Float
    /** 0..1 clumping and 0..1 per-channel colour of the grain (`uGrainRoughness`, `uGrainColor`). */
    public let grainRoughness: Float
    public let grainColor: Float
    /** Loaded LUT matching DevelopParams.lut, or nil for none (always nil while a film stock is on). */
    public let lut: LutData?
    public let lutAmount: Float
    public let lens: LensTransform
    public let sharpening: Float
    /** -1..1 edge-aware mid-frequency detail (`uLocalContrast`). */
    public let localContrast: Float
    public let highpassContrast: Float
    /** Small (about 512 px) copy of the whole image, the large-radius blur for high-pass contrast. */
    public let base: PreviewImage?
    public let bloom: Float
    /** Highlight-boosted copies of the whole image: ~512 px (halo) and ~128 px (long tail). */
    public let bloomHalo: PreviewImage?
    public let bloomTail: PreviewImage?
    public let skinRetouch: Float
    public let skinMask: SkinMask?
    /** Multiplies the grain cell size (1 = film-scaled default, ~1/3200 of the long edge). */
    public let grainSizeScale: Float
    /** 1 = purity-limited saturation and soft gamut compression (no hard colour clipping), 0 = off. */
    public let gamutSoft: Float
    /** `uToneMapper`: ToneMapper.shaderId. */
    public let toneMapper: Int
    /** `uDisplaySource`: 1 for a JPEG/HEIC (DevelopParams.displaySource of the as-shot defaults), else 0. */
    public let displaySource: Float
    /** Mask layers packed for the shader (see packLayers). */
    public let layers: LayerUniforms
    /** spektrafilm film stock (shaders/film/film.glsl) matching DevelopParams.film, or nil for none. */
    public let film: FilmData?
    /** 0..1 blend of the film render over AgX; 0 = off. */
    public let filmAmount: Float
    /** Film grain and halation/scatter amounts, 1 = as spektrafilm simulates the stock. */
    public let filmGrain: Float
    public let halation: Float
    /** `uCurve`: DevelopParams.curves as ToneCurves.table(), or nil when they are all the identity. */
    public let curve: [Float]?

    public init(whiteBalance: [Float], exposure: Float, contrast: Float, saturation: Float, shadows: Float,
                highlights: Float, blacks: Float, whites: Float, denoise: Float, denoiseWide: Float,
                colorDenoise: Float, colorDenoiseWide: Float, grain: Float, grainRoughness: Float = 0, grainColor: Float = 0.15,
                lut: LutData?, lutAmount: Float, lens: LensTransform, sharpening: Float, localContrast: Float = 0,
                highpassContrast: Float,
                base: PreviewImage?, bloom: Float, bloomHalo: PreviewImage?, bloomTail: PreviewImage?,
                skinRetouch: Float, skinMask: SkinMask?, grainSizeScale: Float = 1, gamutSoft: Float = 1,
                toneMapper: Int = 0, displaySource: Float = 0, layers: LayerUniforms = .none, film: FilmData? = nil, filmAmount: Float = 0,
                filmGrain: Float = 1, halation: Float = 1, curve: [Float]? = nil) {
        self.whiteBalance = whiteBalance
        self.exposure = exposure
        self.contrast = contrast
        self.saturation = saturation
        self.shadows = shadows
        self.highlights = highlights
        self.blacks = blacks
        self.whites = whites
        self.denoise = denoise
        self.denoiseWide = denoiseWide
        self.colorDenoise = colorDenoise
        self.colorDenoiseWide = colorDenoiseWide
        self.grain = grain
        self.grainRoughness = grainRoughness
        self.grainColor = grainColor
        self.lut = lut
        self.lutAmount = lutAmount
        self.lens = lens
        self.sharpening = sharpening
        self.localContrast = localContrast
        self.highpassContrast = highpassContrast
        self.base = base
        self.bloom = bloom
        self.bloomHalo = bloomHalo
        self.bloomTail = bloomTail
        self.skinRetouch = skinRetouch
        self.skinMask = skinMask
        self.grainSizeScale = grainSizeScale
        self.gamutSoft = gamutSoft
        self.toneMapper = toneMapper
        self.displaySource = displaySource
        self.layers = layers
        self.film = film
        self.filmAmount = filmAmount
        self.filmGrain = filmGrain
        self.halation = halation
        self.curve = curve
    }

    /**
     * Extra source pixels needed around an output region for the spatial effects
     * (denoise, sharpening, skin smoothing), so tiles join seamlessly.
     */
    public var spatialMargin: Int {
        var retouch = 0
        if skinRetouch > 0, let skinMask, skinMask.hasSkin { retouch = Int(skinMask.sigmaPx * 2.4) + 4 }
        let wide = denoiseWide > 0 || colorDenoiseWide > 0 ? Self.wideDenoiseMargin : 0
        return max(ZoomMath.halo, retouch, wide)
    }

    /**
     * AgX puts display white ~6.5 EV above middle grey, but a sensor clips only
     * ~2.5 EV above it. This base lift (as darktable does for AgX/filmic) puts
     * clipped highlights near display white at Exposure 0.
     */
    private static let baseExposureEv: Float = 0.7

    /** Denoise above this adds the coarse stage, fully at 100 (high-ISO noise the fine stage can't reach). */
    public static let wideDenoiseFrom: Float = 65

    /** Source pixels the coarse stage reads around a pixel (chroma radius 18 px, plus bilinear). */
    public static let wideDenoiseMargin = 20

    /** Grain Size slider -> cell size factor: 1 at 25, doubling every 25 (0.5..8). */
    public static func grainSizeFactor(_ size: Float) -> Float { powf(2, (size - 25) / 25) }

    public static func wideDenoise(_ denoise: Float) -> Float {
        ((denoise - wideDenoiseFrom) / (100 - wideDenoiseFrom)).clamped(0, 1)
    }

    /**
     * [asShot] holds the defaults the image was decoded with (its as-shot temperature/tint).
     * [grainSizeScale] / [grainStrength]: grain cell size and amplitude factors, for apps that size grain
     * differently. [maskView]: mask layer whose mask is shown in red (Masks tool), or nil. [brushFor]: each layer's
     * painted mask (the session's cache); none by default (Auto, Camera). [film]: the loaded spektrafilm stock for
     * DevelopParams.film (RawBoy Film).
     */
    public static func from(_ p: DevelopParams, asShot: DevelopParams, lut: LutData?, lens: LensTransform,
                            base: PreviewImage?, bloomSources: BloomSources?, skinMask: SkinMask?,
                            grainSizeScale: Float = 1, grainStrength: Float = 1, maskView: String? = nil,
                            brushFor: (MaskLayer) -> BrushRaster? = { _ in nil },
                            film: FilmData? = nil) -> ShaderParams {
        let filmUsed = film.flatMap { $0.stock.id == p.film ? $0 : nil }
        let sourceWhite = WhiteBalance.xyFromTempTint(Double(asShot.temperature), Double(asShot.tint))
        let targetWhite = WhiteBalance.xyFromTempTint(Double(p.temperature), Double(p.tint))
        return ShaderParams(
            // White balance, then the RGB primaries adjustment; both are 3x3 in linear Rec.2020.
            whiteBalance: Mat3.toGlColumnMajor(
                Mat3.multiply(Primaries.fromParams(p), WhiteBalance.rebalanceMatrix(asShotXY: sourceWhite, targetXY: targetWhite))),
            exposure: powf(2, p.exposure + baseExposureEv),
            contrast: powf(2, p.contrast / 100 * 0.7),
            saturation: 1 + p.saturation / 100,
            shadows: p.shadows / 100,
            highlights: p.highlights / 100,
            blacks: p.blacks / 100,
            whites: p.whites / 100,
            denoise: p.denoise / 100,
            denoiseWide: wideDenoise(p.denoise),
            colorDenoise: p.effectiveColorDenoise / 100,
            colorDenoiseWide: wideDenoise(p.effectiveColorDenoise),
            grain: p.grain / 100 * grainStrength,
            grainRoughness: p.grainRoughness / 100,
            grainColor: p.grainColor / 100,
            // Film replaces the user LUT (Pro keeps the choice for when Film is turned off).
            lut: lut?.id == p.lut && p.film == nil ? lut : nil,
            lutAmount: p.lutAmount / 100,
            lens: lens,
            sharpening: p.sharpening / 100,
            localContrast: p.localContrast / 100,
            highpassContrast: p.highpassContrast / 100,
            base: base,
            bloom: p.bloom / 100 * (0.35 + 0.4 * p.bloom / 100),  // up to 75% of the light scattered
            bloomHalo: bloomSources?.halo,
            bloomTail: bloomSources?.tail,
            skinRetouch: p.skinRetouch / 100,
            skinMask: skinMask,
            grainSizeScale: grainSizeScale * grainSizeFactor(p.grainSize),
            toneMapper: p.toneMapper.shaderId,
            displaySource: asShot.displaySource ? 1 : 0,
            layers: packLayers(p.masks, imageWidth: lens.width, imageHeight: lens.height, kelvin: p.temperature,
                               tint: p.tint, clarityReady: base != nil, viewId: maskView, brushFor: brushFor),
            film: filmUsed,
            filmAmount: filmUsed != nil ? (p.filmAmount / 100).clamped(0, 1) : 0,
            filmGrain: p.filmGrain / 100,
            halation: p.halation / 100,
            curve: p.curves.table())
    }

    /** Full layer temperature offset (100) moves the white this many mireds; + = warmer, as the global slider. */
    private static let layerMiredRange = 100.0

    /**
     * Packs the layers that change the image (plus [viewId], the layer whose mask is shown, even without
     * adjustments) into the shader's uniform arrays. Positions go from image fractions to full-resolution image
     * pixels. [clarityReady]: the base copy the clarity blur needs is loaded (as for the global slider).
     * Mirrors Android's and web's ShaderParams.packLayers.
     */
    public static func packLayers(_ masks: [MaskLayer], imageWidth: Int, imageHeight: Int, kelvin: Float, tint: Float,
                                  clarityReady: Bool, viewId: String? = nil,
                                  brushFor: (MaskLayer) -> BrushRaster? = { _ in nil }) -> LayerUniforms {
        let used = Array(masks.filter { $0.hasMask && (!$0.isIdentity || ($0.id == viewId && $0.enabled)) }
            .prefix(Masks.maxLayers))
        if used.isEmpty { return .none }
        let n = Masks.maxLayers
        var geom = [Float](repeating: 0, count: n * 4)
        var shape = [Float](repeating: 0, count: n * 4)
        var tone = [Float](repeating: 0, count: n * 4)
        var color = [Float](repeating: 0, count: n * 4)
        var wbs = [Float](repeating: 0, count: n * 9)
        var brush = [BrushRaster?](repeating: nil, count: n)
        var view = 0
        let target = WhiteBalance.xyFromTempTint(Double(kelvin), Double(tint))
        let w = Float(imageWidth)
        let h = Float(imageHeight)
        let long = max(w, h)
        func put(_ values: [Float], into array: inout [Float], at o: Int) {
            for (k, v) in values.enumerated() { array[o + k] = v }
        }
        for (i, l) in used.enumerated() {
            let o = i * 4
            switch l.shape {
            case let .linear(sx0, sy0, sx1, sy1, curve):
                let x0 = sx0 * w
                let y0 = sy0 * h
                let dx = sx1 * w - x0
                let dy = sy1 * h - y0
                let len2 = max(dx * dx + dy * dy, 1)
                put([x0, y0, dx / len2, dy / len2], into: &geom, at: o)
                // Curvature: t grows by curveOffset(s) / length, with s the distance across the gradient.
                put([0, curve / 100 * Masks.curveScale * 4 / (long * len2.squareRoot()), 0, 0], into: &shape, at: o)
            case let .radial(cx, cy, rx, ry, angle, feather):
                let a = Double(angle) * .pi / 180
                put([cx * w, cy * h, 1 / max(rx * long, 1), 1 / max(ry * long, 1)], into: &geom, at: o)
                put([1, Float(cos(a)), Float(sin(a)), (feather / 100).clamped(0.01, 1)], into: &shape, at: o)
            case .brush:
                put([2, 0, 0, 0], into: &shape, at: o)  // only the painted mask
            }
            let a = l.adjust
            put([a.exposure, a.contrast / 100 * 0.7, a.highlights / 100, a.shadows / 100], into: &tone, at: o)
            put([1 + a.saturation / 100, clarityReady ? a.clarity / 100 : 0, l.opacity / 100, l.invert ? 1 : 0],
                into: &color, at: o)
            let mired = 1e6 / Double(kelvin) - Double(a.temperature) / 100 * layerMiredRange
            let layerWhite = WhiteBalance.xyFromTempTint(1e6 / max(mired, 1), Double(tint + a.tint))
            let wb = a.temperature == 0 && a.tint == 0
                ? Mat3.diagonal(1, 1, 1)
                : WhiteBalance.rebalanceMatrix(asShotXY: target, targetXY: layerWhite)
            put(Mat3.toGlColumnMajor(wb), into: &wbs, at: i * 9)
            if l.id == viewId { view = i + 1 }
            if !l.strokes.isEmpty { brush[i] = brushFor(l) }
        }
        return LayerUniforms(count: used.count, geom: geom, shape: shape, tone: tone, color: color, whiteBalance: wbs,
                             view: view, brush: brush)
    }
}

/** The two highlight-boosted, downscaled images bloom scatters light from. */
public struct BloomSources: @unchecked Sendable {
    public let halo: PreviewImage
    public let tail: PreviewImage

    public init(halo: PreviewImage, tail: PreviewImage) {
        self.halo = halo
        self.tail = tail
    }
}

/** Mask layers packed for the shader's uniform arrays (see shaders/develop.glsl, layerMask). */
public struct LayerUniforms: @unchecked Sendable {
    public let count: Int
    public let geom: [Float]
    public let shape: [Float]
    public let tone: [Float]
    public let color: [Float]
    /** Masks.maxLayers column-major 3x3 matrices. */
    public let whiteBalance: [Float]
    /** 0 = off, else the shown layer's packed index + 1. */
    public let view: Int
    /** Painted mask of each packed layer (nil = no strokes); slot i of the brush atlas. */
    public let brush: [BrushRaster?]

    public static let none = LayerUniforms(
        count: 0, geom: [Float](repeating: 0, count: 16), shape: [Float](repeating: 0, count: 16),
        tone: [Float](repeating: 0, count: 16), color: [Float](repeating: 0, count: 16),
        whiteBalance: [Float](repeating: 0, count: 36), view: 0, brush: [nil, nil, nil, nil])
}
