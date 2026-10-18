import Foundation

/**
 * Slider values as the user sees them.
 *
 * - toneMapper: the display transform (AgX by default; Pro only, Auto and Camera stay on AgX)
 * - exposure: EV, -5..+5
 * - contrast, saturation, highlights, shadows, whites, blacks, tint: -100..+100
 * - temperature: Kelvin; the as-shot value is the default
 * - denoise, grain, sharpening: 0..100, 0 = off; denoise is luminance noise (and colour noise while colorDenoise is nil)
 * - grainSize: 0..100, 25 = the default ~1/3200 of the long edge, each 25 doubles it (0 = half, 100 = 8x);
 *   grainRoughness: 0..100 clumping (0 = even grain); grainColor: 0..100 per-channel colour in the grain (0 = mono)
 * - colorDenoise: 0..100 colour (chroma) noise, Pro's own slider; nil = follows denoise (Auto, Camera, older edits)
 * - localContrast: -100..100 edge-aware mid-frequency detail (texture a few pixels across), 0 = off
 * - highpassContrast: -100..100 local (clarity-style) contrast, 0 = off
 * - bloom: 0..100 diffusion-filter glow (spektrafilm-style), 0 = off
 * - skinRetouch: 0..100 smoothing of skin (found by a segmentation model), 0 = off
 * - blemishRemoval: 0..100 automatic spot healing on skin; higher heals fainter spots, 0 = off
 * - lut: LutEntry id or nil for "No LUT"; lutAmount: 0..100 strength
 * - film: spektrafilm stock id (FilmStocks) or nil for none (RawBoy Film only); filmAmount: 0..100 blend of the film
 *   render over the AgX one; filmGrain, halation: 0..200, 100 = as spektrafilm simulates the stock
 * - lensProfile: apply the lens corrections embedded in the file (if any)
 * - lensDistortion: -100..100, + removes barrel distortion; auto crop/scale follows
 * - vignetteAmount: -100..100, + brightens corners; vignetteMidpoint: 0..100 (higher = only the corners)
 * - curves: point curves (RGB, then red, green, blue) on the final display-encoded image (see ToneCurves)
 * - RGB primaries (after darktable): redHue/greenHue/blueHue in degrees (-30..30),
 *   red/green/bluePurity -100..100 (0 = unchanged), tintHue degrees (-180..180), tintPurity 0..100
 * - rotation: clockwise quarter turns; straighten: degrees -45..45, + turns clockwise (auto crop).
 * - flipHorizontal/flipVertical: mirror the photo (display orientation, before rotation) left-right / top-bottom.
 * - cropLeft/Top/Right/Bottom: 0..1 edges of the user crop within the rotated + straightened frame
 *   (0, 0, 1, 1 = no crop). See CropMath.
 *   Rotation, flips, straighten and crop are per photo: they reset when another file is opened.
 *
 * Whites/Blacks follow Lightroom: Blacks < 0 raises the black point (deeper
 * blacks), Blacks > 0 lifts/fades them; Whites > 0 lowers the white point
 * (brighter whites), Whites < 0 dims them.
 */
public struct DevelopParams: Equatable, Sendable {
    public var toneMapper: ToneMapper = .agx
    public var exposure: Float = 0
    public var contrast: Float = 0
    public var highlights: Float = 0
    public var shadows: Float = 0
    public var whites: Float = 0
    public var blacks: Float = 0
    public var temperature: Float = 5500
    public var tint: Float = 0
    public var saturation: Float = 0
    public var redHue: Float = 0
    public var redPurity: Float = 0
    public var greenHue: Float = 0
    public var greenPurity: Float = 0
    public var blueHue: Float = 0
    public var bluePurity: Float = 0
    public var tintHue: Float = 0
    public var tintPurity: Float = 0
    public var denoise: Float = 0
    public var colorDenoise: Float? = nil
    public var grain: Float = 0
    public var grainSize: Float = 25
    public var grainRoughness: Float = 0
    public var grainColor: Float = 15
    public var sharpening: Float = 0
    public var localContrast: Float = 0
    public var highpassContrast: Float = 0
    public var bloom: Float = 0
    public var skinRetouch: Float = 0
    public var blemishRemoval: Float = 0
    public var lut: String? = nil
    public var lutAmount: Float = 100
    public var film: String? = nil
    public var filmAmount: Float = 100
    public var filmGrain: Float = 100
    public var halation: Float = 100
    public var curves = ToneCurves.none
    public var lensProfile: Bool = true
    public var lensDistortion: Float = 0
    public var vignetteAmount: Float = 0
    public var vignetteMidpoint: Float = 50
    public var rotation: Int = 0
    public var flipHorizontal: Bool = false
    public var flipVertical: Bool = false
    public var straighten: Float = 0
    public var cropLeft: Float = 0
    public var cropTop: Float = 0
    public var cropRight: Float = 1
    public var cropBottom: Float = 1
    /** Up to Masks.maxLayers mask layers with local adjustments (see Masks.swift); per photo, like the crop. */
    public var masks: [MaskLayer] = []
    /**
     * The photo is display-referred (JPEG, HEIC: see display_inverse.cpp), rendered with AgX per channel. Set only on
     * a photo's as-shot defaults, from the decoder; never a slider or preset value.
     */
    public var displaySource = false

    public init() {}

    /** The colour denoise in effect: colorDenoise, or denoise while it isn't set. */
    public var effectiveColorDenoise: Float { colorDenoise ?? denoise }
}

/**
 * Scene -> display tone mapping. All three share AgX's log range, middle grey and slope there, so the
 * sliders mean the same; they differ in toe/shoulder and in colour (see shaders/develop.glsl).
 * [shaderId] is the `uToneMapper` value; [key] is stored in presets.
 */
public enum ToneMapper: CaseIterable, Sendable {
    case agx, filmic, sigmoid

    public var shaderId: Int {
        switch self {
        case .agx: 0
        case .filmic: 1
        case .sigmoid: 2
        }
    }

    public var key: String {
        switch self {
        case .agx: "agx"
        case .filmic: "filmic"
        case .sigmoid: "sigmoid"
        }
    }

    public var label: String {
        switch self {
        case .agx: "AgX"
        case .filmic: "Filmic"
        case .sigmoid: "Sigmoid"
        }
    }

    public static func fromKey(_ key: String?) -> ToneMapper? { allCases.first { $0.key == key } }
}
