package app.rawboy.auto.analysis

import app.rawboy.develop.DevelopParams
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The automatic base develop: exposure and tone from [SceneStats], set through the
 * same sliders RawBoy Pro has, so the pipeline stays one and the same. White balance
 * stays as shot (a fixed 6% warming was dropped: the camera's own is the better start);
 * a look may warm or cool it afterwards.
 */
object AutoTone {
    /** RawBoy lifts exposure by this before AgX (ShaderParams.BASE_EXPOSURE_EV). */
    private const val BASE_EXPOSURE_EV = 0.7f

    /**
     * Where the scene's key (geometric mean, decoded units: 1.0 = sensor clip) should land. Cameras
     * meter normal scenes to about 0.06 (Olympus, Fuji, Canon, Pixel raws); film looks read better
     * a little darker than that (owner's preference, measured on test raws: about -0.6 EV).
     */
    private const val TARGET_KEY = 0.043f

    /** Where the brightest 2% should sit, in EV below sensor clip, before exposure is raised. */
    private const val TARGET_P98 = -1.3f

    /** Skin is metered brighter than the scene key (about a stop, like an incident reading on a face). */
    private const val TARGET_SKIN = 0.09f

    /**
     * Faces start to steer exposure at the first share of the frame and get their full weight at the
     * second. A ramp, not a step: tiny faces are often misdetections (a cat's fur counted as skin) or
     * figures in a landscape, but a person a few metres away should still count a little.
     */
    private const val FACE_AREA_FROM = 0.003f
    private const val FACE_AREA_FULL = 0.02f

    /** How much the skin decides when a face is large enough. */
    private const val SKIN_WEIGHT = 0.6f

    /**
     * Phones that store their own auto exposure in the DNG's BaselineExposure (HDR+, Smart HDR): the raw is exposed
     * to protect highlights and the tag says how much to lift it. Their metering knows the scene (snow, night,
     * backlight) far better than ours: on the owner's darktable edits, keeping it was 0.95 EV RMS off,
     * re-metering 1.78. Other cameras' BaselineExposure (e.g. Adobe DNG Converter's) is a fixed per-model offset.
     */
    private val CAMERA_METERED_MAKES = setOf("google", "apple", "samsung")

    /** A fine film grain every photo gets, even without a look (looks add their own on top; 10 read too noisy). */
    private const val BASE_GRAIN = 5f

    /** Contrast every photo starts from: RawBoy's AgX render alone looks flat under the film LUTs. */
    private const val BASE_CONTRAST = 15f

    /** Saturation every photo starts from (owner's preference: the plain AgX render under the looks read too muted; 40 too much). */
    private const val BASE_SATURATION = 30f

    /**
     * Measured noise ([NoiseLevel], std in the shader's sqrt domain) -> Denoise and Color denoise slider values. The
     * shader's denoise at slider s assumes a std of s / 100 * 0.03; these put it at about 6x (luma) and 8x (colour)
     * the measured std, times how far the develop's exposure lifts it (noise shows by about the square root of the
     * gain after the display encoding). Fitted by eye on 100% crops of 26 raws from 9 cameras (Canon G9 at ISO 400
     * needed ~55/90, GFX 50R at 6400 ~30/40, clean Pixel shots ~5).
     */
    private const val LUMA_DENOISE_PER_STD = 2000f
    private const val COLOR_DENOISE_PER_STD = 2600f

    /**
     * The measured auto Denoise (luma) is this much of what the fit above gives: the full amount read too smooth (owner,
     * 2026-10-18). Colour denoise and sharpening stay where the full amount puts them. The ISO table (Camera, unmeasured
     * photos) is unchanged.
     */
    private const val LUMA_DENOISE_SCALE = 0.8f

    /** Scene-linear value AgX maps to display white (0.18 * 2^4.03). */
    private const val DISPLAY_WHITE = 2.95f

    /**
     * [noise]: the photo's measured noise; when measured it sets Denoise, Color denoise and sharpening, else they come
     * from the ISO table. [noiseIsoScale] multiplies the ISO used for that table: its points were measured on
     * camera raws, and a small phone sensor is about as noisy as those at a few times its ISO.
     * A display source (JPEG, HEIC: [DevelopParams.displaySource]) is already exposed, toned, denoised and sharpened
     * by the camera, and its neutral render is the file itself: it gets only the base grain.
     */
    fun develop(
        stats: SceneStats,
        asShot: DevelopParams,
        noiseIsoScale: Float = 1f,
        cameraMetered: Boolean = false,
        noise: NoiseLevel = NoiseLevel.NONE,
    ): DevelopParams {
        if (asShot.displaySource) return asShot.copy(grain = BASE_GRAIN)
        val exposure = exposure(stats, cameraMetered)
        val gainEv = exposure + BASE_EXPOSURE_EV

        // Highlights: recover when a noticeable part of the frame lands in AgX's top stop.
        val nearWhite = stats.fractionAbove(log2(DISPLAY_WHITE / 2f) - gainEv)
        val highlights = -(nearWhite * 400f).coerceIn(0f, 60f)

        // Shadows: lift when the darkest 5% sit far below grey after exposure.
        val shadowDepth = log2(0.18f) - (stats.p5 + gainEv) // EV below scene grey after exposure
        val shadows = ((shadowDepth - 5f) * 12f).coerceIn(0f, 40f)

        // Contrast: a punchier base, a little less for very wide scenes, more for flat ones.
        val contrast = (BASE_CONTRAST + (9f - stats.dynamicRange) * 4f).coerceIn(0f, 30f)

        val (denoise, colorDenoise, sharpening) =
            if (noise.measured) measuredNoiseSettings(noise, exposure) else noiseSettings(stats.iso * noiseIsoScale)
        return asShot.copy(
            exposure = (exposure * 20f).roundToInt() / 20f, // 0.05 EV steps, as the slider shows
            highlights = highlights.roundToInt().toFloat(),
            shadows = shadows.roundToInt().toFloat(),
            contrast = contrast.roundToInt().toFloat(),
            saturation = BASE_SATURATION,
            denoise = denoise,
            colorDenoise = colorDenoise,
            sharpening = sharpening,
            grain = BASE_GRAIN,
        )
    }

    /**
     * EV that brings the key to its target, but never brighter than what puts the scene's
     * highlights near the top: so dark interiors and night scenes stay dark, while a frame the
     * camera underexposed (highlights far below clip) is lifted. [cameraMetered]: the camera's own
     * exposure is kept instead (see [cameraMetered]). With a face, mostly the skin decides.
     */
    fun exposure(stats: SceneStats, cameraMetered: Boolean = false): Float {
        var ev = if (cameraMetered) 0f else min(log2(TARGET_KEY / stats.key), TARGET_P98 - stats.p98)
        if (stats.skinLuminance > 0f) {
            val t = ((stats.faceArea - FACE_AREA_FROM) / (FACE_AREA_FULL - FACE_AREA_FROM)).coerceIn(0f, 1f)
            val w = SKIN_WEIGHT * t * t * (3f - 2f * t)
            val bySkin = log2(TARGET_SKIN / stats.skinLuminance)
            ev = w * bySkin + (1f - w) * ev
        }
        return ev.coerceIn(-2f, 2f)
    }

    /**
     * [current] with the light, colour and detail of the auto develop [auto] (RawBoy Pro's Auto button): exposure,
     * tone, white balance, saturation, denoise and sharpening, on AgX (what AutoTone is tuned for). The LUT, grain,
     * retouch, lens corrections, geometry, masks and the rest of the user's edit stay as they are.
     */
    fun applyTo(current: DevelopParams, auto: DevelopParams): DevelopParams = current.copy(
        toneMapper = auto.toneMapper,
        exposure = auto.exposure,
        contrast = auto.contrast,
        highlights = auto.highlights,
        shadows = auto.shadows,
        whites = auto.whites,
        blacks = auto.blacks,
        temperature = auto.temperature,
        tint = auto.tint,
        saturation = auto.saturation,
        denoise = auto.denoise,
        colorDenoise = auto.colorDenoise,
        sharpening = auto.sharpening,
    )

    /** Whether a raw from [make] with this [baselineExposure] (EV, NaN if none) carries the camera's own exposure. */
    fun cameraMetered(make: String, baselineExposure: Float): Boolean =
        !baselineExposure.isNaN() && make.lowercase() in CAMERA_METERED_MAKES

    /**
     * (denoise, colour denoise, sharpening) for the measured [noise] at auto [exposure] (EV); sharpening eases off as
     * denoise grows, as in the ISO table.
     */
    fun measuredNoiseSettings(noise: NoiseLevel, exposure: Float): Triple<Float, Float, Float> {
        val shown = exp2((exposure + BASE_EXPOSURE_EV) / 2f)
        val full = (LUMA_DENOISE_PER_STD * noise.luma * shown).roundToInt().coerceIn(0, 100).toFloat()
        val color = (COLOR_DENOISE_PER_STD * noise.chroma * shown).roundToInt().coerceIn(0, 100).toFloat()
        val sharpening = when {
            full <= 20f -> 15f
            full <= 45f -> 15f - (full - 20f) / 25f * 5f
            full <= 65f -> 10f - (full - 45f) / 20f * 5f
            else -> 5f
        }.roundToInt().toFloat()
        return Triple((full * LUMA_DENOISE_SCALE).roundToInt().toFloat(), color, sharpening)
    }

    /** (denoise, colour denoise = null: follows denoise, sharpening) by ISO, interpolated between measured points; ISO 0 = unknown. */
    private fun noiseSettings(iso: Float): Triple<Float, Float?, Float> {
        if (iso <= 0f) return Triple(0f, null, 15f)
        val points = arrayOf(
            floatArrayOf(400f, 0f, 15f),
            floatArrayOf(1600f, 20f, 15f),
            floatArrayOf(6400f, 45f, 10f),
            floatArrayOf(25600f, 65f, 5f),
        )
        if (iso <= points.first()[0]) return Triple(points.first()[1], null, points.first()[2])
        if (iso >= points.last()[0]) return Triple(points.last()[1], null, points.last()[2])
        val i = points.indexOfLast { it[0] <= iso }
        val a = points[i]
        val b = points[min(i + 1, points.lastIndex)]
        // Interpolate in stops, as noise grows per stop.
        val t = (log2(iso / a[0]) / log2(b[0] / a[0])).coerceIn(0f, 1f)
        return Triple((a[1] + (b[1] - a[1]) * t).roundToInt().toFloat(), null, (a[2] + (b[2] - a[2]) * t).roundToInt().toFloat())
    }

    private fun exp2(v: Float): Float = Math.pow(2.0, v.toDouble()).toFloat()

    private fun log2(v: Float): Float = (ln(v.toDouble()) / ln(2.0)).toFloat()
}
