import Foundation

/**
 * The user's controls on top of the auto develop and the look; all 0 = as suggested. [brightness] -100..100 shifts
 * exposure; [warmth] -100..100 warms or cools; [tint] -50..50 (+ = magenta), [contrast] -50..50, [saturation]
 * -100..100, [grain] -50..50 and [denoise] -100..100 are added to the slider values the auto develop and the look
 * arrive at, except negative [saturation], which moves the saturation they arrive at towards -100 (-100 = black and
 * white). [rotation] is clockwise quarter turns (0..3) on top of the photo's own orientation.
 */
public struct Adjustments: Equatable, Sendable {
    public var brightness: Float = 0
    public var warmth: Float = 0
    public var tint: Float = 0
    public var contrast: Float = 0
    public var saturation: Float = 0
    public var grain: Float = 0
    public var denoise: Float = 0
    public var rotation: Int = 0

    public init(brightness: Float = 0, warmth: Float = 0, tint: Float = 0, contrast: Float = 0, saturation: Float = 0,
                grain: Float = 0, denoise: Float = 0, rotation: Int = 0) {
        self.brightness = brightness
        self.warmth = warmth
        self.tint = tint
        self.contrast = contrast
        self.saturation = saturation
        self.grain = grain
        self.denoise = denoise
        self.rotation = rotation
    }

    public static let none = Adjustments()
}

/** Combines the auto develop with a look and the user's Adjustments. */
public enum LookApplier {
    public static let defaultStrength: Float = 100

    /** Brightness ±100 = ±3 EV on top of auto exposure. */
    private static let brightnessEv: Float = 3

    /** Warmth ±100 = temperature x2 / ÷2 on top of the auto white balance (tint follows, see Warmth). */
    private static let warmthRange: Float = 2

    /**
     * [strength] 0..100 scales the LUT and every adjustment of [look] (0 = the auto
     * develop alone); [adjust] comes on top, at full strength.
     */
    public static func apply(_ base: DevelopParams, look: Look, strength: Float, adjust: Adjustments,
                             hasFace: Bool) -> DevelopParams {
        let k = strength / 100
        func add(_ value: Float, _ delta: Float, _ lo: Float = -100, _ hi: Float = 100) -> Float {
            (value + delta * k).clamped(lo, hi)
        }
        var d = base
        d.exposure = (base.exposure + look.exposure * k + adjust.brightness / 100 * brightnessEv).clamped(-5, 5)
        d.contrast = (base.contrast + look.contrast * k + adjust.contrast).clamped(-100, 100)
        d.saturation = saturation(base.saturation + look.saturation * k, adjust.saturation)
        d.denoise = (base.denoise + adjust.denoise).clamped(0, 100)
        // A measured colour denoise (AutoTone) moves with Denoise too; nil keeps following it.
        d.colorDenoise = base.colorDenoise.map { ($0 + adjust.denoise).clamped(0, 100) }
        d.highlights = add(base.highlights, look.highlights)
        d.blacks = add(base.blacks, look.blacks)
        d.whites = add(base.whites, look.whites)
        d.grain = (base.grain + look.grain * k + adjust.grain).clamped(0, 100)
        d.bloom = add(base.bloom, look.bloom, 0)
        d.highpassContrast = add(base.highpassContrast, look.highpass)
        d.vignetteAmount = add(base.vignetteAmount, look.vignette)
        d.temperature = (base.temperature + look.warmth * k).clamped(Warmth.minKelvin, Warmth.maxKelvin)
        d.skinRetouch = hasFace ? add(base.skinRetouch, look.portraitRetouch, 0) : base.skinRetouch
        d.lut = k > 0 ? look.lutId : nil
        d.lutAmount = look.lutAmount * k
        // A film look renders the stock in place of AgX; Strength blends it with the AgX render.
        d.film = k > 0 ? look.film : nil
        d.filmAmount = 100 * k
        // A film look's grain is the stock's (AgX grain isn't rendered): Grain -50..50 scales it 0..2x.
        d.filmGrain = (look.filmGrain * (1 + adjust.grain / 50)).clamped(0, 200)
        d.halation = look.halation
        d.rotation = ((base.rotation + adjust.rotation) % 4 + 4) % 4
        var warmed = Warmth.shift(d, powf(warmthRange, adjust.warmth / 100))
        if adjust.tint != 0 { warmed.tint = (warmed.tint + adjust.tint).clamped(-100, 100) }
        return warmed
    }

    /**
     * A RawBoy preset in place of a look: [base] (the auto develop) with every field the preset holds, exactly as RawBoy
     * applies it (tone mapper, film, LUT, colour, detail, retouch, lens corrections, gradient masks...), then [adjust]
     * on top as with a look. Fields the preset leaves out keep the auto develop's values; there is no Strength.
     * Denoise, Color denoise and sharpening always come from the auto develop, measured on this photo (its ISO and
     * exposure), never from the photo the preset was made on; the Denoise adjustment still works on top.
     */
    public static func applyPreset(_ base: DevelopParams, preset: Preset, adjust: Adjustments) -> DevelopParams {
        let p = Presets.apply(preset, base)
        var d = p
        d.exposure = (p.exposure + adjust.brightness / 100 * brightnessEv).clamped(-5, 5)
        d.contrast = (p.contrast + adjust.contrast).clamped(-100, 100)
        d.saturation = saturation(p.saturation, adjust.saturation)
        d.denoise = (base.denoise + adjust.denoise).clamped(0, 100)
        d.colorDenoise = base.colorDenoise.map { ($0 + adjust.denoise).clamped(0, 100) }
        d.sharpening = base.sharpening
        // With a film stock its grain is the one rendered, so Grain scales that one, as in Film mode.
        if p.film != nil {
            d.filmGrain = (p.filmGrain * (1 + adjust.grain / 50)).clamped(0, 200)
        } else {
            d.grain = (p.grain + adjust.grain).clamped(0, 100)
        }
        d.rotation = ((p.rotation + adjust.rotation) % 4 + 4) % 4
        var warmed = Warmth.shift(d, powf(warmthRange, adjust.warmth / 100))
        if adjust.tint != 0 { warmed.tint = (warmed.tint + adjust.tint).clamped(-100, 100) }
        return warmed
    }

    /**
     * A photo's whole develop: [preset] (a RawBoy preset, see [applyPreset]) or else [look] (see [apply]), then its lens
     * preset ([Preset.isLensPreset], or nil), which changes only the lens corrections: the profile switch, distortion,
     * vignette radius and vignetting. A look's own vignette stays on top of the lens preset's vignetting; a RawBoy
     * preset's Lens group gives way to the lens preset.
     */
    public static func develop(
        _ base: DevelopParams, look: Look, strength: Float, adjust: Adjustments, hasFace: Bool,
        preset: Preset?, lensPreset: Preset?
    ) -> DevelopParams {
        let developed = preset.map { applyPreset(base, preset: $0, adjust: adjust) }
            ?? apply(base, look: look, strength: strength, adjust: adjust, hasFace: hasFace)
        guard let lensPreset else { return developed }
        let lens = Presets.apply(lensPreset, base)
        let lookVignette: Float = preset != nil ? 0 : developed.vignetteAmount - base.vignetteAmount
        var d = developed
        d.lensProfile = lens.lensProfile
        d.lensDistortion = lens.lensDistortion
        d.vignetteAmount = (lens.vignetteAmount + lookVignette).clamped(-100, 100)
        d.vignetteMidpoint = lens.vignetteMidpoint
        return d
    }

    /**
     * Positive [adjust] adds to the auto + look saturation; negative scales it towards -100, so the slider's bottom is
     * black and white whatever the auto develop and the look added (an offset left ~30% of the colour).
     */
    private static func saturation(_ combined: Float, _ adjust: Float) -> Float {
        let c = combined.clamped(-100, 100)
        return adjust >= 0 ? (c + adjust).clamped(-100, 100) : c + (-100 - c) * min(-adjust / 100, 1)
    }
}
