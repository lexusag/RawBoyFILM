package app.rawboy.auto.looks

import app.rawboy.auto.analysis.Warmth
import app.rawboy.develop.DevelopParams
import app.rawboy.develop.Preset
import app.rawboy.develop.Presets
import kotlin.math.pow

/**
 * The user's controls on top of the auto develop and the look; all 0 = as suggested. [brightness] -100..100 shifts
 * exposure; [warmth] -100..100 warms or cools; [tint] -50..50 (+ = magenta), [contrast] -50..50, [saturation]
 * -100..100, [grain] -50..50 and [denoise] -100..100 are added to the slider values the auto develop and the look arrive at,
 * except negative [saturation], which moves the saturation they arrive at towards -100 (-100 = black and white).
 * [rotation] is clockwise quarter turns (0..3) on top of the photo's own orientation.
 */
data class Adjustments(
    val brightness: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val grain: Float = 0f,
    val denoise: Float = 0f,
    val rotation: Int = 0,
) {
    companion object {
        val NONE = Adjustments()
    }
}

/** Combines the auto develop with a look and the user's [Adjustments]. */
object LookApplier {
    const val DEFAULT_STRENGTH = 100f

    /** Brightness ±100 = ±3 EV on top of auto exposure. */
    private const val BRIGHTNESS_EV = 3f

    /** Warmth ±100 = temperature x2 / ÷2 on top of the auto white balance (tint follows, see [Warmth]). */
    private const val WARMTH_RANGE = 2f

    /**
     * [strength] 0..100 scales the LUT and every adjustment of [look] (0 = the auto
     * develop alone); [adjust] comes on top, at full strength.
     */
    fun apply(
        base: DevelopParams,
        look: Look,
        strength: Float,
        adjust: Adjustments,
        hasFace: Boolean,
    ): DevelopParams {
        val k = strength / 100f
        fun add(value: Float, delta: Float, min: Float = -100f, max: Float = 100f) = (value + delta * k).coerceIn(min, max)
        val developed = base.copy(
            exposure = (base.exposure + look.exposure * k + adjust.brightness / 100f * BRIGHTNESS_EV).coerceIn(-5f, 5f),
            contrast = (base.contrast + look.contrast * k + adjust.contrast).coerceIn(-100f, 100f),
            saturation = saturation(base.saturation + look.saturation * k, adjust.saturation),
            denoise = (base.denoise + adjust.denoise).coerceIn(0f, 100f),
            // A measured colour denoise (AutoTone) moves with Denoise too; null keeps following it.
            colorDenoise = base.colorDenoise?.let { (it + adjust.denoise).coerceIn(0f, 100f) },
            highlights = add(base.highlights, look.highlights),
            blacks = add(base.blacks, look.blacks),
            whites = add(base.whites, look.whites),
            grain = (base.grain + look.grain * k + adjust.grain).coerceIn(0f, 100f),
            bloom = add(base.bloom, look.bloom, 0f),
            highpassContrast = add(base.highpassContrast, look.highpass),
            vignetteAmount = add(base.vignetteAmount, look.vignette),
            temperature = (base.temperature + look.warmth * k).coerceIn(Warmth.MIN_KELVIN, Warmth.MAX_KELVIN),
            skinRetouch = if (hasFace) add(base.skinRetouch, look.portraitRetouch, 0f) else base.skinRetouch,
            lut = if (k > 0f) look.lutId else null,
            lutAmount = look.lutAmount * k,
            // A film look renders the stock in place of AgX; Strength blends it with the AgX render.
            film = if (k > 0f) look.film else null,
            filmAmount = 100f * k,
            // A film look's grain is the stock's (AgX grain isn't rendered): Grain -50..50 scales it 0..2x.
            filmGrain = (look.filmGrain * (1f + adjust.grain / 50f)).coerceIn(0f, 200f),
            halation = look.halation,
            rotation = Math.floorMod(base.rotation + adjust.rotation, 4),
        )
        val warmed = Warmth.shift(developed, WARMTH_RANGE.pow(adjust.warmth / 100f))
        return if (adjust.tint == 0f) warmed else warmed.copy(tint = (warmed.tint + adjust.tint).coerceIn(-100f, 100f))
    }

    /**
     * A RawBoy preset in place of a look: [base] (the auto develop) with every field the preset holds, exactly as RawBoy
     * applies it (tone mapper, film, LUT, colour, detail, retouch, lens corrections, gradient masks...), then [adjust]
     * on top as with a look. Fields the preset leaves out keep the auto develop's values; there is no Strength.
     * Denoise, Color denoise and sharpening always come from the auto develop, measured on this photo (its ISO and
     * exposure), never from the photo the preset was made on; the Denoise adjustment still works on top.
     */
    fun applyPreset(base: DevelopParams, preset: Preset, adjust: Adjustments): DevelopParams {
        val p = Presets.apply(preset, base)
        val developed = p.copy(
            exposure = (p.exposure + adjust.brightness / 100f * BRIGHTNESS_EV).coerceIn(-5f, 5f),
            contrast = (p.contrast + adjust.contrast).coerceIn(-100f, 100f),
            saturation = saturation(p.saturation, adjust.saturation),
            denoise = (base.denoise + adjust.denoise).coerceIn(0f, 100f),
            colorDenoise = base.colorDenoise?.let { (it + adjust.denoise).coerceIn(0f, 100f) },
            sharpening = base.sharpening,
            // With a film stock its grain is the one rendered, so Grain scales that one, as in Film mode.
            grain = if (p.film != null) p.grain else (p.grain + adjust.grain).coerceIn(0f, 100f),
            filmGrain = if (p.film != null) (p.filmGrain * (1f + adjust.grain / 50f)).coerceIn(0f, 200f) else p.filmGrain,
            rotation = Math.floorMod(p.rotation + adjust.rotation, 4),
        )
        val warmed = Warmth.shift(developed, WARMTH_RANGE.pow(adjust.warmth / 100f))
        return if (adjust.tint == 0f) warmed else warmed.copy(tint = (warmed.tint + adjust.tint).coerceIn(-100f, 100f))
    }

    /**
     * A photo's whole develop: [preset] (a RawBoy preset, see [applyPreset]) or else [look] (see [apply]), then its lens
     * preset ([Preset.isLensPreset], or null), which changes only the lens corrections: the profile switch, distortion,
     * vignette radius and vignetting. A look's own vignette stays on top of the lens preset's vignetting; a RawBoy
     * preset's Lens group gives way to the lens preset.
     */
    fun develop(
        base: DevelopParams,
        look: Look,
        strength: Float,
        adjust: Adjustments,
        hasFace: Boolean,
        preset: Preset?,
        lensPreset: Preset?,
    ): DevelopParams {
        val developed = if (preset != null) applyPreset(base, preset, adjust) else apply(base, look, strength, adjust, hasFace)
        if (lensPreset == null) return developed
        val lens = Presets.apply(lensPreset, base)
        val lookVignette = if (preset != null) 0f else developed.vignetteAmount - base.vignetteAmount
        return developed.copy(
            lensProfile = lens.lensProfile,
            lensDistortion = lens.lensDistortion,
            vignetteAmount = (lens.vignetteAmount + lookVignette).coerceIn(-100f, 100f),
            vignetteMidpoint = lens.vignetteMidpoint,
        )
    }

    /**
     * Positive [adjust] adds to the auto + look saturation; negative scales it towards -100, so the slider's bottom is
     * black and white whatever the auto develop and the look added (an offset left ~30% of the colour).
     */
    private fun saturation(combined: Float, adjust: Float): Float {
        val c = combined.coerceIn(-100f, 100f)
        return if (adjust >= 0f) (c + adjust).coerceIn(-100f, 100f) else c + (-100f - c) * (-adjust / 100f).coerceAtMost(1f)
    }
}
