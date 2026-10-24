package app.rawboy.gl

import android.opengl.GLES30
import android.util.Log
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Multi-scale denoise pre-pass: renders a denoised copy of the image texture that the develop shader reads in place
 * of the image, with `uDenoisePre` 1 so it only adds its fine chroma stage. Ports: WaveletDenoise.ts/.swift.
 *
 * Method (the published a trous wavelet shrinkage, our own implementation; shaders/denoise_*.frag): in the
 * sqrt(x + c) domain split into luma L = mean and ratio chroma (c - L) / (L + k); decompose with the B3-spline a trous
 * transform (5-tap kernel at strides 1, 2, 4, ...); shrink every band against that band's noise; add the bands back.
 * Band noise follows a profile measured on flat patches of camera raws (GFX 50R, A7 III, ISO 1000-6400): demosaicing
 * correlates the noise, so the coarse bands hold 1.5x (luma) to 3x (chroma) what white noise would. The bilateral
 * alone only reached the finest band and left that coarse noise as blotchy mottle on flat surfaces once the exposure
 * went up. Fine chroma (full-res bands 0-1) is left to the develop shader's ratio-chroma bilateral: shrunk here, the
 * dim channels' structure under strongly coloured light (what a mono LUT shows there) was wiped out.
 *
 * Scales are in full-resolution pixels: on a downscaled texture (pixel scale p < 1) texture band j is full-res band
 * j + log2(1/p), and fewer levels cover the same reach. Needs float render targets (GL_EXT_color_buffer_float);
 * without them [supported] is false and the develop shader denoises alone. The camera viewfinder doesn't use it
 * (per frame it would cost too much); its shots do, through OffscreenDeveloper.
 *
 * [outputFilter]: filtering of the result texture, as the input texture's (the develop shader samples it the same way).
 */
internal class WaveletDenoise(private val quad: FullscreenQuad, private val outputFilter: Int) {
    private val split = GlUtil.createProgram(ShaderSources.DENOISE_VERT, ShaderSources.DENOISE_SPLIT_FRAG)
    private val level = GlUtil.createProgram(ShaderSources.DENOISE_VERT, ShaderSources.DENOISE_LEVEL_FRAG)
    private val shrink = GlUtil.createProgram(ShaderSources.DENOISE_VERT, ShaderSources.DENOISE_SHRINK_FRAG)
    private val merge = GlUtil.createProgram(ShaderSources.DENOISE_VERT, ShaderSources.DENOISE_MERGE_FRAG)
    private val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
    private var low = IntArray(0)
    private var acc = IntArray(0)
    private var detail = 0
    private var out = 0
    private var width = 0
    private var height = 0

    /** What [out] holds, so an unchanged image and strength aren't denoised again on every slider move. */
    private var key: List<Any>? = null

    val supported: Boolean = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty().contains("GL_EXT_color_buffer_float")
        .also { if (!it) Log.w("RawBoy", "wavelet denoise off: no GL_EXT_color_buffer_float (shader denoise only)") }

    /** Whether [apply] runs for these strengths (0..1 sliders, as ShaderParams.denoise / colorDenoise). */
    fun active(denoise: Float, colorDenoise: Float): Boolean = supported && (denoise > 0f || colorDenoise > 0f)

    private fun ensure(w: Int, h: Int) {
        if (w == width && h == height && out != 0) return
        releaseTextures()
        fun make(format: Int, filter: Int): Int {
            val t = GlUtil.createTexture(filter)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, format, w, h)
            return t
        }
        low = intArrayOf(make(GLES30.GL_RGBA32F, GLES30.GL_NEAREST), make(GLES30.GL_RGBA32F, GLES30.GL_NEAREST))
        acc = intArrayOf(make(GLES30.GL_RGBA32F, GLES30.GL_NEAREST), make(GLES30.GL_RGBA32F, GLES30.GL_NEAREST))
        detail = make(GLES30.GL_RGBA32F, GLES30.GL_NEAREST)
        out = make(GLES30.GL_RGBA16F, outputFilter)
        width = w
        height = h
        key = null
    }

    /**
     * Denoises [src] (linear RGB, [w] x [h] texels, [pixelScale] texels per full-resolution pixel) and returns the
     * texture holding the result. [denoise]/[colorDenoise]: the 0..1 sliders. [srcKey] identifies the texture's
     * contents for caching. Leaves framebuffer 0 bound and unit 0 active; the caller sets its viewport and framebuffer.
     */
    fun apply(src: Int, srcKey: Any, w: Int, h: Int, pixelScale: Float, denoise: Float, colorDenoise: Float): Int {
        ensure(w, h)
        val newKey = listOf(srcKey, w, h, pixelScale, denoise, colorDenoise)
        if (newKey == key) return out
        // The sliders' sigma, as the develop shader's (0.03 at full strength, ~ISO 6400 on a typical sensor).
        val sigmaL = denoise * 0.03f
        val sigmaC = colorDenoise * 0.03f
        val offset = offset(sigmaL, sigmaC)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDisable(GLES30.GL_BLEND)

        GLES30.glUseProgram(split)
        bindTexture(UNIT_A, src)
        uniform1i(split, "uSrc", UNIT_A)
        uniform1f(split, "uK", RATIO_K)
        uniform1f(split, "uOffset", offset)
        target(low[0])
        quad.draw()

        var cur = 0
        var a = 0
        levels(pixelScale, sigmaL, sigmaC).forEachIndexed { j, lv ->
            GLES30.glUseProgram(level)
            uniform1i(level, "uLow", UNIT_A)
            uniform1i(level, "uStride", lv.stride)
            bindTexture(UNIT_A, low[cur])
            target(low[1 - cur], detail)
            quad.draw()
            cur = 1 - cur

            GLES30.glUseProgram(shrink)
            uniform1i(shrink, "uDetail", UNIT_A)
            uniform1i(shrink, "uAcc", UNIT_B)
            // Energy window at about the band's own spacing.
            uniform1i(shrink, "uStride", max(1, lv.stride shr 1))
            uniform1i(shrink, "uFirst", if (j == 0) 1 else 0)
            GLES30.glUniform3f(GLES30.glGetUniformLocation(shrink, "uThr"), lv.lumaThreshold, lv.chromaThreshold, RATIO_K)
            uniform1f(shrink, "uOffset", offset)
            uniform1f(shrink, "uKeep", lv.lumaKeep)
            uniform1f(shrink, "uKeepC", lv.chromaKeep)
            bindTexture(UNIT_A, detail)
            bindTexture(UNIT_B, acc[a])
            target(acc[1 - a])
            quad.draw()
            a = 1 - a
            bindTexture(UNIT_B, 0)
            bindTexture(UNIT_A, 0)
        }

        GLES30.glUseProgram(merge)
        bindTexture(UNIT_A, low[cur])
        bindTexture(UNIT_B, acc[a])
        uniform1i(merge, "uLow", UNIT_A)
        uniform1i(merge, "uAcc", UNIT_B)
        uniform1f(merge, "uK", RATIO_K)
        uniform1f(merge, "uOffset", offset)
        target(out)
        quad.draw()

        // Unbind the pass textures so none stays attached and sampled at once later.
        bindTexture(UNIT_B, 0)
        bindTexture(UNIT_A, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        key = newKey
        return out
    }

    private fun bindTexture(unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    }

    private fun uniform1i(program: Int, name: String, v: Int) =
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), v)

    private fun uniform1f(program: Int, name: String, v: Float) =
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, name), v)

    private fun target(t0: Int, t1: Int = 0) {
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t0, 0)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT1, GLES30.GL_TEXTURE_2D, t1, 0)
        val buffers = if (t1 != 0) intArrayOf(GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1) else intArrayOf(GLES30.GL_COLOR_ATTACHMENT0)
        GLES30.glDrawBuffers(buffers.size, buffers, 0)
    }

    private fun releaseTextures() {
        val all = (low.toList() + acc.toList() + listOf(detail, out)).filter { it != 0 }.toIntArray()
        if (all.isNotEmpty()) GLES30.glDeleteTextures(all.size, all, 0)
        low = IntArray(0)
        acc = IntArray(0)
        detail = 0
        out = 0
    }

    fun release() {
        releaseTextures()
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        for (p in intArrayOf(split, level, shrink, merge)) GLES30.glDeleteProgram(p)
    }

    /** One level's uniforms. */
    data class Level(
        val stride: Int,
        val lumaThreshold: Float,
        val chromaThreshold: Float,
        val lumaKeep: Float,
        val chromaKeep: Float,
    )

    companion object {
        /** Luma noise per band relative to band 0, measured on flat raw patches (sqrt domain). */
        private val LUMA_PROFILE = floatArrayOf(1.0f, 0.32f, 0.14f, 0.065f, 0.03f, 0.015f)

        /** Chroma noise per band, relative to luma band 0. */
        private val CHROMA_PROFILE = floatArrayOf(0.76f, 0.48f, 0.28f, 0.14f, 0.06f, 0.03f)

        /**
         * Threshold per band in noise sigmas: firm everywhere, so flat areas lose each band's noise consistently (a
         * threshold near 1 sigma kills some patches and not others, and the leftover read as mottle again)...
         */
        private val LUMA_K = floatArrayOf(1.3f, 1.4f, 1.8f, 2.2f, 2.5f, 2.5f)
        private val CHROMA_K = floatArrayOf(1.5f, 2.0f, 2.5f, 3.0f, 3.0f, 3.0f)

        /**
         * ...then a constant fraction of each band's original is added back, about the same at every scale, so what
         * is left looks like the camera's own noise at a lower level (a cleaner sensor), not plastic. The bilateral
         * kept 39% of the finest band but 83-88% of the coarse ones (measured on a flat wall): its leftover was all
         * low frequency, the blotchy mottle. Picked by the owner in the web's comparison (0.3 / 0.25 / 0.2 x 1.75).
         */
        private val LUMA_KEEP = floatArrayOf(0.525f, 0.525f, 0.4375f, 0.35f, 0.35f, 0.35f)

        /** Coarse chroma kept: invisible as colour, but grain for a mono LUT under coloured light. */
        private const val CHROMA_KEEP = 0.175f

        /** Full-res bands below this are the develop shader's fine chroma stage, not shrunk here. */
        private const val FINE_CHROMA_BANDS = 1.5f

        /** Full-resolution levels: reach 2 * (1 + 2 + 4 + 8 + 16) = 62 px ([MARGIN]). */
        private const val LEVELS = 5

        /** Ratio chroma offset in the sqrt domain: keeps the ratio finite where luma is near black. */
        private const val RATIO_K = 0.02f

        /**
         * The transform is sqrt(x + c), not sqrt(x): near black read noise dominates, which sqrt(x) blows up (it is
         * steepest at 0). A yellow wall at ISO 1000 (blue mean 0.002, 1% clipped at zero) turned into deep dark dots
         * once the rest was smooth; c >= 0.003 removed most, 0.01 all. c follows the slider's noise level,
         * c = (OFFSET_K sigma)^2: 0.003 for that wall (sigma 0.0045), 0.0002 at base ISO, where read noise doesn't
         * matter. The thresholds are scaled by the transform's flatter slope near black (denoise_shrink.frag). (A
         * per-image fit of variance = a x + b was tried: on demosaiced pixels it underestimated b and was unstable.)
         */
        private const val OFFSET_K = 12f

        /** Source pixels the pre-pass reads around a pixel (5 levels of a 5-tap kernel, strides 1..16). */
        const val MARGIN = 64

        private const val UNIT_A = 10
        private const val UNIT_B = 11

        /** c of the sqrt(x + c) transform for the sliders' sigmas. */
        fun offset(sigmaL: Float, sigmaC: Float): Float = (OFFSET_K * max(sigmaL, sigmaC * 0.5f)).pow(2)

        /** Value of a per-band table at fractional band b (log-linear between entries, halving past the end). */
        private fun profileAt(table: FloatArray, b: Float): Float {
            val last = table.size - 1
            if (b >= last) return table[last] * 0.5f.pow(b - last)
            val i = floor(b).toInt()
            val f = b - i
            return exp(ln(table[i]) * (1 - f) + ln(table[i + 1]) * f)
        }

        private fun linearAt(table: FloatArray, b: Float): Float {
            val i = min(floor(b).toInt(), table.size - 1)
            val j = min(i + 1, table.size - 1)
            val f = min(1f, b - i)
            return table[i] * (1 - f) + table[j] * f
        }

        /**
         * The levels for a texture of [pixelScale] texels per full-res pixel; [sigmaL]/[sigmaC]: the sliders' noise
         * std in the sqrt domain (full resolution).
         */
        fun levels(pixelScale: Float, sigmaL: Float, sigmaC: Float): List<Level> {
            val shift = max(0f, log2(1f / pixelScale))
            val count = (LEVELS - shift).roundToInt().coerceIn(1, LEVELS)
            return (0 until count).map { j ->
                val band = j + shift // the full-resolution band this texture band stands for
                val fineChroma = band < FINE_CHROMA_BANDS
                Level(
                    stride = 1 shl j,
                    lumaThreshold = sigmaL * profileAt(LUMA_PROFILE, band) * linearAt(LUMA_K, band),
                    chromaThreshold = if (fineChroma) 0f else sigmaC * profileAt(CHROMA_PROFILE, band) * linearAt(CHROMA_K, band),
                    lumaKeep = linearAt(LUMA_KEEP, band),
                    chromaKeep = if (fineChroma) 1f else CHROMA_KEEP,
                )
            }
        }
    }
}
