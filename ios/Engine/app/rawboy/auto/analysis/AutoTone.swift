import Foundation

/**
 * The automatic base develop: exposure and tone from SceneStats, set through the
 * same sliders RawBoy Pro has, so the pipeline stays one and the same. White balance
 * stays as shot (a fixed 6% warming was dropped: the camera's own is the better start);
 * a look may warm or cool it afterwards.
 */
public enum AutoTone {
    /** RawBoy lifts exposure by this before AgX (ShaderParams.baseExposureEv). */
    private static let baseExposureEv: Float = 0.7

    /**
     * Where the scene's key (geometric mean, decoded units: 1.0 = sensor clip) should land. Cameras
     * meter normal scenes to about 0.06 (Olympus, Fuji, Canon, Pixel raws); film looks read better
     * a little darker than that (owner's preference, measured on test raws: about -0.6 EV).
     */
    private static let targetKey: Float = 0.043

    /** Where the brightest 2% should sit, in EV below sensor clip, before exposure is raised. */
    private static let targetP98: Float = -1.3

    /** Skin is metered brighter than the scene key (about a stop, like an incident reading on a face). */
    private static let targetSkin: Float = 0.09

    /**
     * Faces start to steer exposure at the first share of the frame and get their full weight at the
     * second. A ramp, not a step: tiny faces are often misdetections (a cat's fur counted as skin) or
     * figures in a landscape, but a person a few metres away should still count a little.
     */
    private static let faceAreaFrom: Float = 0.003
    private static let faceAreaFull: Float = 0.02

    /** How much the skin decides when a face is large enough. */
    private static let skinWeight: Float = 0.6

    /**
     * Phones that store their own auto exposure in the DNG's BaselineExposure (HDR+, Smart HDR): the raw is exposed
     * to protect highlights and the tag says how much to lift it. Their metering knows the scene (snow, night,
     * backlight) far better than ours: on the owner's darktable edits, keeping it was 0.95 EV RMS off,
     * re-metering 1.78. Other cameras' BaselineExposure (e.g. Adobe DNG Converter's) is a fixed per-model offset.
     */
    private static let cameraMeteredMakes: Set<String> = ["google", "apple", "samsung"]

    /** A fine film grain every photo gets, even without a look (looks add their own on top; 10 read too noisy). */
    private static let baseGrain: Float = 5

    /** Contrast every photo starts from: RawBoy's AgX render alone looks flat under the film LUTs. */
    private static let baseContrast: Float = 15

    /** Saturation every photo starts from (owner's preference: the plain AgX render under the looks read too muted; 40 too much). */
    private static let baseSaturation: Float = 30

    /** Scene-linear value AgX maps to display white (0.18 * 2^4.03). */
    private static let displayWhite: Float = 2.95

    /**
     * Measured noise (NoiseLevel, std in the shader's sqrt domain) -> Denoise and Color denoise slider values, times
     * how far the develop's exposure lifts it (see AutoTone.kt).
     */
    private static let lumaDenoisePerStd: Float = 2000
    private static let colorDenoisePerStd: Float = 2600

    /**
     * [noise]: the photo's measured noise; when measured it sets Denoise, Color denoise and sharpening, else they come
     * from the ISO table. [noiseIsoScale] multiplies the ISO used for that table: its points were measured on
     * camera raws, and a small phone sensor is about as noisy as those at a few times its ISO.
     * A display source (JPEG, HEIC: DevelopParams.displaySource) is already exposed, toned, denoised and sharpened
     * by the camera, and its neutral render is the file itself: it gets only the base grain.
     */
    public static func develop(_ stats: SceneStats, asShot: DevelopParams, noiseIsoScale: Float = 1,
                               cameraMetered: Bool = false, noise: NoiseLevel = .none) -> DevelopParams {
        if asShot.displaySource {
            var p = asShot
            p.grain = baseGrain
            return p
        }
        let exposure = exposure(stats, cameraMetered: cameraMetered)
        let gainEv = exposure + baseExposureEv

        // Highlights: recover when a noticeable part of the frame lands in AgX's top stop.
        let nearWhite = stats.fractionAbove(log2f(displayWhite / 2) - gainEv)
        let highlights = -(nearWhite * 400).clamped(0, 60)

        // Shadows: lift when the darkest 5% sit far below grey after exposure.
        let shadowDepth = log2f(0.18) - (stats.p5 + gainEv)  // EV below scene grey after exposure
        let shadows = ((shadowDepth - 5) * 12).clamped(0, 40)

        // Contrast: a punchier base, a little less for very wide scenes, more for flat ones.
        let contrast = (baseContrast + (9 - stats.dynamicRange) * 4).clamped(0, 30)

        let (denoise, colorDenoise, sharpening) = noise.measured
            ? measuredNoiseSettings(noise, exposure: exposure) : noiseSettings(stats.iso * noiseIsoScale)
        var p = asShot
        p.exposure = Float((exposure * 20).roundToInt()) / 20  // 0.05 EV steps, as the slider shows
        p.highlights = Float(highlights.roundToInt())
        p.shadows = Float(shadows.roundToInt())
        p.contrast = Float(contrast.roundToInt())
        p.saturation = baseSaturation
        p.denoise = denoise
        p.colorDenoise = colorDenoise
        p.sharpening = sharpening
        p.grain = baseGrain
        return p
    }

    /**
     * EV that brings the key to its target, but never brighter than what puts the scene's
     * highlights near the top: so dark interiors and night scenes stay dark, while a frame the
     * camera underexposed (highlights far below clip) is lifted. [cameraMetered]: the camera's own
     * exposure is kept instead (see cameraMetered(make:baselineExposure:)). With a face, mostly the skin decides.
     */
    public static func exposure(_ stats: SceneStats, cameraMetered: Bool = false) -> Float {
        var ev = cameraMetered ? 0 : min(log2f(targetKey / stats.key), targetP98 - stats.p98)
        if stats.skinLuminance > 0 {
            let t = ((stats.faceArea - faceAreaFrom) / (faceAreaFull - faceAreaFrom)).clamped(0, 1)
            let w = skinWeight * t * t * (3 - 2 * t)
            let bySkin = log2f(targetSkin / stats.skinLuminance)
            ev = w * bySkin + (1 - w) * ev
        }
        return ev.clamped(-2, 2)
    }

    /**
     * [current] with the light, colour and detail of the auto develop [auto] (RawBoy Pro's Auto button): exposure,
     * tone, white balance, saturation, denoise and sharpening, on AgX (what AutoTone is tuned for). The LUT, grain,
     * retouch, lens corrections, geometry, masks and the rest of the user's edit stay as they are.
     */
    public static func applyTo(_ current: DevelopParams, _ auto: DevelopParams) -> DevelopParams {
        var p = current
        p.toneMapper = auto.toneMapper
        p.exposure = auto.exposure
        p.contrast = auto.contrast
        p.highlights = auto.highlights
        p.shadows = auto.shadows
        p.whites = auto.whites
        p.blacks = auto.blacks
        p.temperature = auto.temperature
        p.tint = auto.tint
        p.saturation = auto.saturation
        p.denoise = auto.denoise
        p.colorDenoise = auto.colorDenoise
        p.sharpening = auto.sharpening
        return p
    }

    /** Whether a raw from [make] with this [baselineExposure] (EV, NaN if none) carries the camera's own exposure. */
    public static func cameraMetered(make: String, baselineExposure: Float) -> Bool {
        !baselineExposure.isNaN && cameraMeteredMakes.contains(make.lowercased())
    }

    /** (denoise, colour denoise, sharpening) for the measured [noise] at auto [exposure] (EV). */
    public static func measuredNoiseSettings(_ noise: NoiseLevel, exposure: Float) -> (Float, Float, Float) {
        let shown = exp2f((exposure + baseExposureEv) / 2)
        let luma = Float((lumaDenoisePerStd * noise.luma * shown).roundToInt().clamped(0, 100))
        let color = Float((colorDenoisePerStd * noise.chroma * shown).roundToInt().clamped(0, 100))
        let sharpening: Float
        if luma <= 20 { sharpening = 15 } else if luma <= 45 { sharpening = 15 - (luma - 20) / 25 * 5 }
        else if luma <= 65 { sharpening = 10 - (luma - 45) / 20 * 5 } else { sharpening = 5 }
        return (luma, color, Float(sharpening.roundToInt()))
    }

    /** (denoise, colour denoise = nil: follows denoise, sharpening) by ISO, interpolated between measured points; ISO 0 = unknown. */
    private static func noiseSettings(_ iso: Float) -> (Float, Float?, Float) {
        if iso <= 0 { return (0, nil, 15) }
        let points: [[Float]] = [[400, 0, 15], [1600, 20, 15], [6400, 45, 10], [25600, 65, 5]]
        if iso <= points.first![0] { return (points.first![1], nil, points.first![2]) }
        if iso >= points.last![0] { return (points.last![1], nil, points.last![2]) }
        let i = points.lastIndex { $0[0] <= iso }!
        let a = points[i]
        let b = points[min(i + 1, points.count - 1)]
        // Interpolate in stops, as noise grows per stop.
        let t = (log2f(iso / a[0]) / log2f(b[0] / a[0])).clamped(0, 1)
        return (Float((a[1] + (b[1] - a[1]) * t).roundToInt()), nil, Float((a[2] + (b[2] - a[2]) * t).roundToInt()))
    }
}

/**
 * A photo's measured noise (RawImage.measureNoise, native estimateNoise): the std of luma and of colour noise in the
 * develop shader's denoise domain (sqrt of the decoded linear values), 0 = not measured.
 */
public struct NoiseLevel: Equatable, Sendable {
    public var luma: Float
    public var chroma: Float

    public init(luma: Float, chroma: Float) {
        self.luma = luma
        self.chroma = chroma
    }

    public var measured: Bool { luma > 0 }

    public static let none = NoiseLevel(luma: 0, chroma: 0)
}
