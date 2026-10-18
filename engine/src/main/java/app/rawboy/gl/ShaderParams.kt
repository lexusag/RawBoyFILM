package app.rawboy.gl

import app.rawboy.color.Mat3
import app.rawboy.color.Primaries
import app.rawboy.color.WhiteBalance
import app.rawboy.develop.BrushRaster
import app.rawboy.develop.DevelopParams
import app.rawboy.develop.MaskLayer
import app.rawboy.develop.MaskShape
import app.rawboy.develop.Masks
import app.rawboy.film.FilmData
import app.rawboy.lens.LensTransform
import app.rawboy.lut.LutData
import app.rawboy.retouch.SkinMask
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Uniform values derived from [DevelopParams]; immutable so it can cross threads. */
class ShaderParams(
    val whiteBalance: FloatArray, // column-major 3x3
    val exposure: Float,
    val contrast: Float,
    val saturation: Float,
    val shadows: Float,
    val highlights: Float,
    val blacks: Float,
    val whites: Float,
    /** Luminance denoise, 0..1. */
    val denoise: Float,
    /** 0..1: the coarse luminance denoise stage, for the slider's top third (see [WIDE_DENOISE_FROM]). */
    val denoiseWide: Float,
    /** Colour (chroma) denoise and its coarse stage, as [denoise] and [denoiseWide]. */
    val colorDenoise: Float,
    val colorDenoiseWide: Float,
    val grain: Float,
    /** 0..1 clumping and 0..1 per-channel colour of the grain (`uGrainRoughness`, `uGrainColor`). */
    val grainRoughness: Float,
    val grainColor: Float,
    /** Loaded LUT matching DevelopParams.lut, or null for none (always null while a film stock is on). */
    val lut: LutData?,
    val lutAmount: Float,
    val lens: LensTransform,
    val sharpening: Float,
    /** -1..1 edge-aware mid-frequency detail (`uLocalContrast`). */
    val localContrast: Float,
    val highpassContrast: Float,
    /** Small (about 512 px) copy of the whole image, the large-radius blur for high-pass contrast. */
    val base: PreviewImage?,
    val bloom: Float,
    /** Highlight-boosted copies of the whole image: ~512 px (halo) and ~128 px (long tail). */
    val bloomHalo: PreviewImage?,
    val bloomTail: PreviewImage?,
    val skinRetouch: Float,
    val skinMask: SkinMask?,
    /** Multiplies the grain cell size (1 = film-scaled default, ~1/3200 of the long edge). */
    val grainSizeScale: Float = 1f,
    /** 1 = purity-limited saturation and soft gamut compression (no hard colour clipping), 0 = off. */
    val gamutSoft: Float = 1f,
    /** `uToneMapper`: [app.rawboy.develop.ToneMapper.shaderId]. */
    val toneMapper: Int = 0,
    /** `uDisplaySource`: 1 for a JPEG/HEIC (DevelopParams.displaySource of the as-shot defaults), else 0. */
    val displaySource: Float = 0f,
    /** Mask layers packed for the shader (see [packLayers]). */
    val layers: LayerUniforms = LayerUniforms.NONE,
    /** spektrafilm film stock (shaders/film/film.glsl) matching DevelopParams.film, or null for none. */
    val film: FilmData? = null,
    /** 0..1 blend of the film render over AgX; 0 = off. */
    val filmAmount: Float = 0f,
    /** Film grain and halation/scatter amounts, 1 = as spektrafilm simulates the stock. */
    val filmGrain: Float = 1f,
    val halation: Float = 1f,
    /** `uCurve`: DevelopParams.curves as [app.rawboy.develop.ToneCurves.table], or null when they are all the identity. */
    val curve: FloatArray? = null,
) {
    /**
     * Extra source pixels needed around an output region for the spatial effects
     * (denoise, sharpening, skin smoothing), so tiles join seamlessly.
     */
    val spatialMargin: Int
        get() {
            val retouch = if (skinRetouch > 0f && skinMask?.hasSkin == true) (skinMask.sigmaPx * 2.4f).toInt() + 4 else 0
            val wide = if (denoiseWide > 0f || colorDenoiseWide > 0f) WIDE_DENOISE_MARGIN else 0
            return maxOf(ZoomMath.HALO, retouch, wide)
        }

    companion object {
        /**
         * AgX puts display white ~6.5 EV above middle grey, but a sensor clips only
         * ~2.5 EV above it. This base lift (as darktable does for AgX/filmic) puts
         * clipped highlights near display white at Exposure 0.
         */
        private const val BASE_EXPOSURE_EV = 0.7f

        /** Denoise above this adds the coarse stage, fully at 100 (high-ISO noise the fine stage can't reach). */
        const val WIDE_DENOISE_FROM = 65f

        /** Source pixels the coarse stage reads around a pixel (chroma radius 18 px, plus bilinear). */
        const val WIDE_DENOISE_MARGIN = 20

        /** Grain Size slider -> cell size factor: 1 at 25, doubling every 25 (0.5..8). */
        fun grainSizeFactor(size: Float): Float = 2f.pow((size - 25f) / 25f)

        fun wideDenoise(denoise: Float): Float = ((denoise - WIDE_DENOISE_FROM) / (100f - WIDE_DENOISE_FROM)).coerceIn(0f, 1f)

        /** [asShot] holds the defaults the image was decoded with (its as-shot temperature/tint). */
        fun from(
            p: DevelopParams,
            asShot: DevelopParams,
            lut: LutData?,
            lens: LensTransform,
            base: PreviewImage?,
            bloomSources: BloomSources?,
            skinMask: SkinMask?,
            /** Grain cell size factor (see [grainSizeScale]) and amplitude factor, for apps that size grain differently. */
            grainSizeScale: Float = 1f,
            grainStrength: Float = 1f,
            /** Mask layer whose mask is shown in red (Masks tool), or null. */
            maskView: String? = null,
            /** Each layer's painted mask (the session's cache); none by default (Auto, Camera). */
            brushFor: (MaskLayer) -> BrushRaster? = { null },
            /** Loaded spektrafilm stock for DevelopParams.film (Camera). */
            film: FilmData? = null,
        ): ShaderParams {
            val filmUsed = film?.takeIf { it.stock.id == p.film }
            val sourceWhite = WhiteBalance.xyFromTempTint(asShot.temperature.toDouble(), asShot.tint.toDouble())
            val targetWhite = WhiteBalance.xyFromTempTint(p.temperature.toDouble(), p.tint.toDouble())
            return ShaderParams(
                // White balance, then the RGB primaries adjustment; both are 3x3 in linear Rec.2020.
                whiteBalance = Mat3.toGlColumnMajor(
                    Mat3.multiply(Primaries.fromParams(p), WhiteBalance.rebalanceMatrix(sourceWhite, targetWhite)),
                ),
                exposure = 2f.pow(p.exposure + BASE_EXPOSURE_EV),
                contrast = 2f.pow(p.contrast / 100f * 0.7f),
                saturation = 1f + p.saturation / 100f,
                shadows = p.shadows / 100f,
                highlights = p.highlights / 100f,
                blacks = p.blacks / 100f,
                whites = p.whites / 100f,
                denoise = p.denoise / 100f,
                denoiseWide = wideDenoise(p.denoise),
                colorDenoise = p.effectiveColorDenoise / 100f,
                colorDenoiseWide = wideDenoise(p.effectiveColorDenoise),
                grain = p.grain / 100f * grainStrength,
                grainRoughness = p.grainRoughness / 100f,
                grainColor = p.grainColor / 100f,
                // Film replaces the user LUT (Pro keeps the choice for when Film is turned off).
                lut = lut?.takeIf { it.id == p.lut && p.film == null },
                lutAmount = p.lutAmount / 100f,
                lens = lens,
                sharpening = p.sharpening / 100f,
                localContrast = p.localContrast / 100f,
                highpassContrast = p.highpassContrast / 100f,
                base = base,
                bloom = p.bloom / 100f * 0.35f, // up to 35% of the light scattered
                bloomHalo = bloomSources?.halo,
                bloomTail = bloomSources?.tail,
                skinRetouch = p.skinRetouch / 100f,
                skinMask = skinMask,
                grainSizeScale = grainSizeScale * grainSizeFactor(p.grainSize),
                toneMapper = p.toneMapper.shaderId,
                displaySource = if (asShot.displaySource) 1f else 0f,
                layers = packLayers(
                    p.masks, lens.width, lens.height, p.temperature, p.tint, base != null, maskView, brushFor,
                ),
                film = filmUsed,
                filmAmount = if (filmUsed != null) (p.filmAmount / 100f).coerceIn(0f, 1f) else 0f,
                filmGrain = p.filmGrain / 100f,
                halation = p.halation / 100f,
                curve = p.curves.table(),
            )
        }

        /** Full layer temperature offset (100) moves the white this many mireds; + = warmer, as the global slider. */
        private const val LAYER_MIRED_RANGE = 100.0

        /**
         * Packs the layers that change the image (plus [viewId], the layer whose mask is shown, even without
         * adjustments) into the shader's uniform arrays. Positions go from image fractions to full-resolution image
         * pixels. [clarityReady]: the base copy the clarity blur needs is loaded (as for the global slider).
         * Mirrors web's ShaderParams.packLayers.
         */
        fun packLayers(
            masks: List<MaskLayer>,
            imageWidth: Int,
            imageHeight: Int,
            kelvin: Float,
            tint: Float,
            clarityReady: Boolean,
            viewId: String? = null,
            brushFor: (MaskLayer) -> BrushRaster? = { null },
        ): LayerUniforms {
            val used = masks.filter { it.hasMask && (!it.isIdentity || (it.id == viewId && it.enabled)) }.take(Masks.MAX_LAYERS)
            if (used.isEmpty()) return LayerUniforms.NONE
            val n = Masks.MAX_LAYERS
            val geom = FloatArray(n * 4)
            val shape = FloatArray(n * 4)
            val tone = FloatArray(n * 4)
            val color = FloatArray(n * 4)
            val wbs = FloatArray(n * 9)
            val brush = arrayOfNulls<BrushRaster>(n)
            var view = 0
            val target = WhiteBalance.xyFromTempTint(kelvin.toDouble(), tint.toDouble())
            val w = imageWidth.toFloat()
            val h = imageHeight.toFloat()
            val long = max(w, h)
            used.forEachIndexed { i, l ->
                val o = i * 4
                when (val s = l.shape) {
                    is MaskShape.Linear -> {
                        val x0 = s.x0 * w
                        val y0 = s.y0 * h
                        val dx = s.x1 * w - x0
                        val dy = s.y1 * h - y0
                        val len2 = max(dx * dx + dy * dy, 1f)
                        floatArrayOf(x0, y0, dx / len2, dy / len2).copyInto(geom, o)
                        // Curvature: t grows by curveOffset(s) / length, with s the distance across the gradient.
                        floatArrayOf(0f, s.curve / 100f * Masks.CURVE_SCALE * 4f / (long * sqrt(len2)), 0f, 0f).copyInto(shape, o)
                    }
                    is MaskShape.Radial -> {
                        val a = Math.toRadians(s.angle.toDouble())
                        floatArrayOf(s.cx * w, s.cy * h, 1f / max(s.rx * long, 1f), 1f / max(s.ry * long, 1f)).copyInto(geom, o)
                        floatArrayOf(1f, cos(a).toFloat(), sin(a).toFloat(), (s.feather / 100f).coerceIn(0.01f, 1f)).copyInto(shape, o)
                    }
                    MaskShape.Brush -> floatArrayOf(2f, 0f, 0f, 0f).copyInto(shape, o) // only the painted mask
                }
                val a = l.adjust
                floatArrayOf(a.exposure, a.contrast / 100f * 0.7f, a.highlights / 100f, a.shadows / 100f).copyInto(tone, o)
                floatArrayOf(
                    1f + a.saturation / 100f, if (clarityReady) a.clarity / 100f else 0f, l.opacity / 100f, if (l.invert) 1f else 0f,
                ).copyInto(color, o)
                val mired = 1e6 / kelvin - a.temperature / 100.0 * LAYER_MIRED_RANGE
                val layerWhite = WhiteBalance.xyFromTempTint(1e6 / max(mired, 1.0), (tint + a.tint).toDouble())
                val wb = if (a.temperature == 0f && a.tint == 0f) Mat3.diagonal(1.0, 1.0, 1.0) else WhiteBalance.rebalanceMatrix(target, layerWhite)
                Mat3.toGlColumnMajor(wb).copyInto(wbs, i * 9)
                if (l.id == viewId) view = i + 1
                if (l.strokes.isNotEmpty()) brush[i] = brushFor(l)
            }
            return LayerUniforms(used.size, geom, shape, tone, color, wbs, view, brush.toList())
        }
    }
}

/** The two highlight-boosted, downscaled images bloom scatters light from. */
class BloomSources(val halo: PreviewImage, val tail: PreviewImage)

/** Mask layers packed for the shader's uniform arrays (see shaders/develop.glsl, layerMask). */
class LayerUniforms(
    val count: Int,
    val geom: FloatArray,
    val shape: FloatArray,
    val tone: FloatArray,
    val color: FloatArray,
    /** Masks.MAX_LAYERS column-major 3x3 matrices. */
    val whiteBalance: FloatArray,
    /** 0 = off, else the shown layer's packed index + 1. */
    val view: Int,
    /** Painted mask of each packed layer (null = no strokes); slot i of the brush atlas. */
    val brush: List<BrushRaster?>,
) {
    companion object {
        val NONE = LayerUniforms(
            0, FloatArray(16), FloatArray(16), FloatArray(16), FloatArray(16), FloatArray(36), 0, List(4) { null },
        )
    }
}
