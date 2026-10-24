package app.rawboy.gl

import android.opengl.GLES30
import app.rawboy.develop.BrushRaster
import app.rawboy.develop.Masks
import app.rawboy.develop.ToneCurves
import app.rawboy.film.FilmData
import app.rawboy.color.Mat3
import app.rawboy.lens.LensInfo
import app.rawboy.lens.LensTransform
import app.rawboy.lut.LutData
import app.rawboy.retouch.SkinMask
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Linear Rec.2020 half-float RGB pixels, row 0 = top of the image. */
class PreviewImage(
    val width: Int,
    val height: Int,
    val pixels: ByteBuffer,
    /** Size of the full-resolution image this preview was made from. */
    val fullWidth: Int,
    val fullHeight: Int,
)

/**
 * Where the bound texture ([input], in full-resolution pixels) and the rendered
 * quad ([output]) sit in the full-resolution image. They differ when lens
 * correction moves pixels, and in tiled export. Spatial effects (denoise radius,
 * grain) are sized in full-resolution pixels.
 */
internal class SampleGeometry(
    val textureWidth: Int,
    val textureHeight: Int,
    val input: PixelRect,
    val output: PixelRect,
    /** Full-resolution image size, which sets the grain size. */
    val imageWidth: Int,
    val imageHeight: Int,
    /** Smallest grain cell in full-resolution pixels: more than 1 when the export is saved smaller. */
    val minGrainPx: Float = 1f,
) {
    /** Grain cell in full-resolution pixels: ~1/3200 of the long edge (fine-grain film). */
    val grainSize: Float get() = maxOf(minGrainPx, maxOf(imageWidth, imageHeight) / 3200f)
}

internal object GlUtil {
    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw IllegalStateException("Program link failed: $log")
        }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("Shader compile failed: $log")
        }
        return shader
    }

    fun createTexture(filter: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    /** Uploads tightly packed RGB half floats into the currently bound texture. */
    fun uploadHalfRgb(width: Int, height: Int, pixels: ByteBuffer) {
        pixels.position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB16F, width, height, 0,
            GLES30.GL_RGB, GLES30.GL_HALF_FLOAT, pixels,
        )
    }
}

/** Compiled develop shader plus uniform locations. */
internal class DevelopProgram(forExport: Boolean) {
    private val id = GlUtil.createProgram(Shaders.VERTEX, Shaders.fragment(forExport))
    private val uImage = GLES30.glGetUniformLocation(id, "uImage")
    private val uScale = GLES30.glGetUniformLocation(id, "uScale")
    private val uFlipY = GLES30.glGetUniformLocation(id, "uFlipY")
    private val uUvRect = GLES30.glGetUniformLocation(id, "uUvRect")
    private val uWhiteBalance = GLES30.glGetUniformLocation(id, "uWhiteBalance")
    private val uExposure = GLES30.glGetUniformLocation(id, "uExposure")
    private val uContrast = GLES30.glGetUniformLocation(id, "uContrast")
    private val uSaturation = GLES30.glGetUniformLocation(id, "uSaturation")
    private val uGamutSoft = GLES30.glGetUniformLocation(id, "uGamutSoft")
    private val uToneMapper = GLES30.glGetUniformLocation(id, "uToneMapper")
    private val uDisplaySource = GLES30.glGetUniformLocation(id, "uDisplaySource")
    private val uClipLevel = GLES30.glGetUniformLocation(id, "uClipLevel")
    private val uShadows = GLES30.glGetUniformLocation(id, "uShadows")
    private val uHighlights = GLES30.glGetUniformLocation(id, "uHighlights")
    private val uBlacks = GLES30.glGetUniformLocation(id, "uBlacks")
    private val uWhites = GLES30.glGetUniformLocation(id, "uWhites")
    private val uDenoise = GLES30.glGetUniformLocation(id, "uDenoise")
    private val uDenoiseWide = GLES30.glGetUniformLocation(id, "uDenoiseWide")
    private val uColorDenoise = GLES30.glGetUniformLocation(id, "uColorDenoise")
    private val uColorDenoiseWide = GLES30.glGetUniformLocation(id, "uColorDenoiseWide")
    private val uDenoisePre = GLES30.glGetUniformLocation(id, "uDenoisePre")
    private val uGrain = GLES30.glGetUniformLocation(id, "uGrain")
    private val uGrainSize = GLES30.glGetUniformLocation(id, "uGrainSize")
    private val uGrainRoughness = GLES30.glGetUniformLocation(id, "uGrainRoughness")
    private val uGrainColor = GLES30.glGetUniformLocation(id, "uGrainColor")
    private val uSharpen = GLES30.glGetUniformLocation(id, "uSharpen")
    private val uLocalContrast = GLES30.glGetUniformLocation(id, "uLocalContrast")
    private val uClarity = GLES30.glGetUniformLocation(id, "uClarity")
    private val uBase = GLES30.glGetUniformLocation(id, "uBase")
    private val uBaseTexel = GLES30.glGetUniformLocation(id, "uBaseTexel")
    private val uImageSize = GLES30.glGetUniformLocation(id, "uImageSize")
    private val uBloom = GLES30.glGetUniformLocation(id, "uBloom")
    private val uRetouch = GLES30.glGetUniformLocation(id, "uRetouch")
    private val uSkinMask = GLES30.glGetUniformLocation(id, "uSkinMask")
    private val uSkinSigma = GLES30.glGetUniformLocation(id, "uSkinSigma")
    private val uBloomHalo = GLES30.glGetUniformLocation(id, "uBloomHalo")
    private val uBloomTail = GLES30.glGetUniformLocation(id, "uBloomTail")
    private val uBloomHaloTexel = GLES30.glGetUniformLocation(id, "uBloomHaloTexel")
    private val uBloomTailTexel = GLES30.glGetUniformLocation(id, "uBloomTailTexel")
    private val uLut = GLES30.glGetUniformLocation(id, "uLut")
    private val uLutSize = GLES30.glGetUniformLocation(id, "uLutSize")
    private val uLutAmount = GLES30.glGetUniformLocation(id, "uLutAmount")
    private val uCurveOn = GLES30.glGetUniformLocation(id, "uCurveOn")
    private val uCurve = GLES30.glGetUniformLocation(id, "uCurve")
    private val uTexel = GLES30.glGetUniformLocation(id, "uTexel")
    private val uPixelScale = GLES30.glGetUniformLocation(id, "uPixelScale")
    private val uInOrigin = GLES30.glGetUniformLocation(id, "uInOrigin")
    private val uInSpan = GLES30.glGetUniformLocation(id, "uInSpan")
    private val uOutOrigin = GLES30.glGetUniformLocation(id, "uOutOrigin")
    private val uOutSpan = GLES30.glGetUniformLocation(id, "uOutSpan")
    private val uOutToImage = GLES30.glGetUniformLocation(id, "uOutToImage")
    private val uToSensor = GLES30.glGetUniformLocation(id, "uToSensor")
    private val uFromSensor = GLES30.glGetUniformLocation(id, "uFromSensor")
    private val uImageCenter = GLES30.glGetUniformLocation(id, "uImageCenter")
    private val uHalfDiagonal = GLES30.glGetUniformLocation(id, "uHalfDiagonal")
    private val uZoom = GLES30.glGetUniformLocation(id, "uZoom")
    private val uManualK = GLES30.glGetUniformLocation(id, "uManualK")
    private val uWarpOn = GLES30.glGetUniformLocation(id, "uWarpOn")
    private val uWarpPlanes = GLES30.glGetUniformLocation(id, "uWarpPlanes")
    private val uWarpK = GLES30.glGetUniformLocation(id, "uWarpK")
    private val uWarpCenter = GLES30.glGetUniformLocation(id, "uWarpCenter")
    private val uWarpNorm = GLES30.glGetUniformLocation(id, "uWarpNorm")
    private val uVigOn = GLES30.glGetUniformLocation(id, "uVigOn")
    private val uVigK = GLES30.glGetUniformLocation(id, "uVigK")
    private val uVigCenter = GLES30.glGetUniformLocation(id, "uVigCenter")
    private val uVigNorm = GLES30.glGetUniformLocation(id, "uVigNorm")
    private val uShadingUndo = GLES30.glGetUniformLocation(id, "uShadingUndo")
    private val uShading = GLES30.glGetUniformLocation(id, "uShading")
    private val uShadingGrid = GLES30.glGetUniformLocation(id, "uShadingGrid")
    private val uShadingPoints = GLES30.glGetUniformLocation(id, "uShadingPoints")
    private val uSensorSize = GLES30.glGetUniformLocation(id, "uSensorSize")
    private val uManualVig = GLES30.glGetUniformLocation(id, "uManualVig")
    private val uManualVigMid = GLES30.glGetUniformLocation(id, "uManualVigMid")
    private val uLayerCount = GLES30.glGetUniformLocation(id, "uLayerCount")
    private val uMaskGeom = GLES30.glGetUniformLocation(id, "uMaskGeom")
    private val uMaskShape = GLES30.glGetUniformLocation(id, "uMaskShape")
    private val uLayerTone = GLES30.glGetUniformLocation(id, "uLayerTone")
    private val uLayerColor = GLES30.glGetUniformLocation(id, "uLayerColor")
    private val uLayerWb = GLES30.glGetUniformLocation(id, "uLayerWb")
    private val uMaskView = GLES30.glGetUniformLocation(id, "uMaskView")
    private val uClipWarn = GLES30.glGetUniformLocation(id, "uClipWarn")
    private val uBrushMask = GLES30.glGetUniformLocation(id, "uBrushMask")
    private val uBrushSlot = GLES30.glGetUniformLocation(id, "uBrushSlot")
    private val uBrushTexel = GLES30.glGetUniformLocation(id, "uBrushTexel")
    private val uFilmAmount = GLES30.glGetUniformLocation(id, "uFilmAmount")
    private val uFilmLut = GLES30.glGetUniformLocation(id, "uFilmLut")
    private val uPrintLut = GLES30.glGetUniformLocation(id, "uPrintLut")
    private val uFilmLutSize = GLES30.glGetUniformLocation(id, "uFilmLutSize")
    private val uPrintLutSize = GLES30.glGetUniformLocation(id, "uPrintLutSize")
    private val uFilmDMin = GLES30.glGetUniformLocation(id, "uFilmDMin")
    private val uFilmDMax = GLES30.glGetUniformLocation(id, "uFilmDMax")
    private val uFilmUmPerPx = GLES30.glGetUniformLocation(id, "uFilmUmPerPx")
    private val uHalation = GLES30.glGetUniformLocation(id, "uHalation")
    private val uHalationSigmaUm = GLES30.glGetUniformLocation(id, "uHalationSigmaUm")
    private val uHalationDecay = GLES30.glGetUniformLocation(id, "uHalationDecay")
    private val uScatterTailUm = GLES30.glGetUniformLocation(id, "uScatterTailUm")
    private val uScatterWeight = GLES30.glGetUniformLocation(id, "uScatterWeight")
    private val uFilmGrain = GLES30.glGetUniformLocation(id, "uFilmGrain")
    private val uGrainParticleUm2 = GLES30.glGetUniformLocation(id, "uGrainParticleUm2")
    private val uGrainDMin = GLES30.glGetUniformLocation(id, "uGrainDMin")
    private val uGrainDMax = GLES30.glGetUniformLocation(id, "uGrainDMax")
    private val uGrainUniformity = GLES30.glGetUniformLocation(id, "uGrainUniformity")
    private val uGrainBlurPx = GLES30.glGetUniformLocation(id, "uGrainBlurPx")
    private val uFormGrainRms = GLES30.glGetUniformLocation(id, "uFormGrainRms")
    private val uFormHalation = GLES30.glGetUniformLocation(id, "uFormHalation")
    private val uFormHaloUm = GLES30.glGetUniformLocation(id, "uFormHaloUm")
    private val uFormScatter = GLES30.glGetUniformLocation(id, "uFormScatter")
    private val uFormScatterUm = GLES30.glGetUniformLocation(id, "uFormScatterUm")

    fun bind(
        p: ShaderParams,
        g: SampleGeometry,
        scaleX: Float,
        scaleY: Float,
        flipY: Boolean,
        uvRect: FloatArray = FULL_UV_RECT,
    ) {
        GLES30.glUseProgram(id)
        GLES30.glUniform1i(uImage, 0)
        GLES30.glUniform2f(uScale, scaleX, scaleY)
        GLES30.glUniform1f(uFlipY, if (flipY) 1f else 0f)
        GLES30.glUniform4f(uUvRect, uvRect[0], uvRect[1], uvRect[2], uvRect[3])
        GLES30.glUniformMatrix3fv(uWhiteBalance, 1, false, p.whiteBalance, 0)
        GLES30.glUniform1f(uExposure, p.exposure)
        GLES30.glUniform1f(uContrast, p.contrast)
        GLES30.glUniform1f(uSaturation, p.saturation)
        GLES30.glUniform1f(uGamutSoft, p.gamutSoft)
        GLES30.glUniform1i(uToneMapper, p.toneMapper)
        GLES30.glUniform1f(uDisplaySource, p.displaySource)
        GLES30.glUniform1f(uClipLevel, p.clipLevel)
        GLES30.glUniform1f(uShadows, p.shadows)
        GLES30.glUniform1f(uHighlights, p.highlights)
        GLES30.glUniform1f(uBlacks, p.blacks)
        GLES30.glUniform1f(uWhites, p.whites)
        GLES30.glUniform1f(uDenoise, p.denoise)
        GLES30.glUniform1f(uDenoiseWide, p.denoiseWide)
        GLES30.glUniform1f(uColorDenoise, p.colorDenoise)
        GLES30.glUniform1f(uColorDenoiseWide, p.colorDenoiseWide)
        GLES30.glUniform1f(uDenoisePre, 0f)
        GLES30.glUniform1f(uGrain, p.grain)
        GLES30.glUniform1f(uGrainSize, g.grainSize * p.grainSizeScale)
        GLES30.glUniform1f(uGrainRoughness, p.grainRoughness)
        GLES30.glUniform1f(uGrainColor, p.grainColor)
        GLES30.glUniform1f(uSharpen, p.sharpening)
        GLES30.glUniform1f(uLocalContrast, p.localContrast)
        GLES30.glUniform1f(uClarity, if (p.base != null) p.highpassContrast else 0f)
        GLES30.glUniform1i(uBase, ImageTexture.BASE_UNIT)
        val skin = p.skinMask
        GLES30.glUniform1f(uRetouch, if (skin?.hasSkin == true) p.skinRetouch else 0f)
        GLES30.glUniform1i(uSkinMask, MaskTexture.UNIT)
        GLES30.glUniform1f(uSkinSigma, skin?.sigmaPx ?: 3f)
        val bloomReady = p.bloomHalo != null && p.bloomTail != null
        GLES30.glUniform1f(uBloom, if (bloomReady) p.bloom else 0f)
        GLES30.glUniform1i(uBloomHalo, ImageTexture.BLOOM_HALO_UNIT)
        GLES30.glUniform1i(uBloomTail, ImageTexture.BLOOM_TAIL_UNIT)
        GLES30.glUniform2f(uBloomHaloTexel, 1f / (p.bloomHalo?.width ?: 1), 1f / (p.bloomHalo?.height ?: 1))
        GLES30.glUniform2f(uBloomTailTexel, 1f / (p.bloomTail?.width ?: 1), 1f / (p.bloomTail?.height ?: 1))
        GLES30.glUniform2f(uBaseTexel, 1f / (p.base?.width ?: 1), 1f / (p.base?.height ?: 1))
        GLES30.glUniform2f(uImageSize, g.imageWidth.toFloat(), g.imageHeight.toFloat())
        GLES30.glUniform1i(uLut, LutTexture.UNIT)
        GLES30.glUniform1f(uLutSize, (p.lut?.size ?: 2).toFloat())
        GLES30.glUniform1f(uLutAmount, if (p.lut != null) p.lutAmount else 0f)
        GLES30.glUniform1f(uCurveOn, if (p.curve != null) 1f else 0f)
        p.curve?.let { GLES30.glUniform3fv(uCurve, ToneCurves.SIZE, it, 0) }
        GLES30.glUniform2f(uTexel, 1f / g.textureWidth, 1f / g.textureHeight)
        GLES30.glUniform1f(uPixelScale, g.textureWidth.toFloat() / g.input.width)
        GLES30.glUniform2f(uInOrigin, g.input.x.toFloat(), g.input.y.toFloat())
        GLES30.glUniform2f(uInSpan, g.input.width.toFloat(), g.input.height.toFloat())
        GLES30.glUniform2f(uOutOrigin, g.output.x.toFloat(), g.output.y.toFloat())
        GLES30.glUniform2f(uOutSpan, g.output.width.toFloat(), g.output.height.toFloat())
        bindLens(p.lens)
        bindLayers(p.layers)
        bindFilm(p, g)
    }

    /** After [bind], with the program in use: the bound image went through the wavelet denoise pre-pass (WaveletDenoise). */
    fun setDenoisePre(on: Boolean) {
        GLES30.glUniform1f(uDenoisePre, if (on) 1f else 0f)
    }

    /** Preview only (the export shader has no warning): after [bind], with the program in use. */
    fun setClipWarning(on: Boolean) {
        GLES30.glUniform1f(uClipWarn, if (on) 1f else 0f)
    }

    /**
     * Film look: shaders/film_form.glsl (standard) or film/film.glsl (film edition); each program finds only its own
     * uniforms (the others' locations are -1, ignored). Everything 0 = off without one.
     */
    private fun bindFilm(p: ShaderParams, g: SampleGeometry) {
        GLES30.glUniform1i(uFilmLut, FilmTextures.FILM_UNIT)
        GLES30.glUniform1i(uPrintLut, FilmTextures.PRINT_UNIT)
        val f = p.film
        val amount = if (f != null) p.filmAmount else 0f
        GLES30.glUniform1f(uFilmAmount, amount)
        if (f == null) {
            GLES30.glUniform1f(uFilmUmPerPx, 0f)
            GLES30.glUniform3f(uHalation, 0f, 0f, 0f)
            GLES30.glUniform3f(uScatterWeight, 0f, 0f, 0f)
            GLES30.glUniform1f(uFilmGrain, 0f)
            GLES30.glUniform3f(uFormHalation, 0f, 0f, 0f)
            GLES30.glUniform3f(uFormScatter, 0f, 0f, 0f)
            return
        }
        val s = f.stock
        fun vec3(loc: Int, v: FloatArray, k: Float = 1f) = GLES30.glUniform3f(loc, v[0] * k, v[1] * k, v[2] * k)
        GLES30.glUniform1f(uFilmLutSize, f.film.size.toFloat())
        GLES30.glUniform1f(uPrintLutSize, f.print.size.toFloat())
        vec3(uFilmDMin, s.densityMin)
        vec3(uFilmDMax, s.densityMax)
        GLES30.glUniform1f(uFilmUmPerPx, FilmTextures.FRAME_WIDTH_UM / maxOf(g.imageWidth, g.imageHeight))
        // Halation and scatter happen before the film, so Strength fades them with the film render.
        val optics = p.halation * amount
        vec3(uHalation, s.halation.strength, optics)
        GLES30.glUniform1f(uHalationSigmaUm, s.halation.firstSigmaUm)
        GLES30.glUniform1f(uHalationDecay, s.halation.bounceDecay)
        vec3(uScatterTailUm, s.halation.scatterTailUm)
        vec3(uScatterWeight, s.halation.scatterTailWeight, optics.coerceAtMost(1f))
        GLES30.glUniform1f(uFilmGrain, p.filmGrain)
        vec3(uGrainParticleUm2, s.grain.particleScale, s.grain.particleAreaUm2)
        vec3(uGrainDMin, s.grain.densityMin)
        vec3(uGrainDMax, s.grain.densityMax)
        vec3(uGrainUniformity, s.grain.uniformity)
        GLES30.glUniform1f(uGrainBlurPx, s.grain.blurPx)
        val form = s.formation
        vec3(uFormGrainRms, form.grainRms)
        vec3(uFormHalation, form.halation, optics)
        GLES30.glUniform1f(uFormHaloUm, form.haloRadiusUm)
        vec3(uFormScatter, form.scatter, optics.coerceAtMost(1f))
        GLES30.glUniform1f(uFormScatterUm, form.scatterUm)
    }

    private fun bindLayers(l: LayerUniforms) {
        val n = Masks.MAX_LAYERS
        GLES30.glUniform1i(uLayerCount, l.count)
        GLES30.glUniform4fv(uMaskGeom, n, l.geom, 0)
        GLES30.glUniform4fv(uMaskShape, n, l.shape, 0)
        GLES30.glUniform4fv(uLayerTone, n, l.tone, 0)
        GLES30.glUniform4fv(uLayerColor, n, l.color, 0)
        GLES30.glUniformMatrix3fv(uLayerWb, n, false, l.whiteBalance, 0)
        GLES30.glUniform1i(uMaskView, l.view)
        // Painted masks: slot i of the 2x2 brush atlas (see BrushTexture); zero size = no strokes.
        GLES30.glUniform1i(uBrushMask, BrushTexture.UNIT)
        val slots = FloatArray(n * 4)
        var w = 1
        var h = 1
        l.brush.forEachIndexed { i, r ->
            if (r == null) return@forEachIndexed
            floatArrayOf((i % 2) * 0.5f, (i / 2) * 0.5f, 0.5f, 0.5f).copyInto(slots, i * 4)
            w = r.width
            h = r.height
        }
        GLES30.glUniform4fv(uBrushSlot, n, slots, 0)
        GLES30.glUniform2f(uBrushTexel, 1f / (2 * w), 1f / (2 * h))
    }

    /**
     * The viewfinder's whole-image copies: textures it renders from the current frame and binds to the base and
     * bloom units itself, instead of [ShaderParams.base] / [ShaderParams.bloomHalo] / [ShaderParams.bloomTail]
     * (copies made from a readback lag the live image; bloom then trails behind moving lights). Call after [bind].
     */
    fun bindLiveCopies(p: ShaderParams, baseWidth: Int, baseHeight: Int, tailWidth: Int, tailHeight: Int) {
        GLES30.glUniform1f(uClarity, p.highpassContrast)
        GLES30.glUniform1f(uBloom, p.bloom)
        GLES30.glUniform2f(uBaseTexel, 1f / baseWidth, 1f / baseHeight)
        GLES30.glUniform2f(uBloomHaloTexel, 1f / baseWidth, 1f / baseHeight)
        GLES30.glUniform2f(uBloomTailTexel, 1f / tailWidth, 1f / tailHeight)
    }

    private fun bindLens(l: LensTransform) {
        val info = l.info
        GLES30.glUniformMatrix3fv(uOutToImage, 1, false, Mat3.toGlColumnMajor(l.outToImage), 0)
        GLES30.glUniformMatrix3fv(uToSensor, 1, false, Mat3.toGlColumnMajor(l.toSensor), 0)
        GLES30.glUniformMatrix3fv(uFromSensor, 1, false, Mat3.toGlColumnMajor(l.fromSensor), 0)
        GLES30.glUniform2f(uImageCenter, l.centerX.toFloat(), l.centerY.toFloat())
        GLES30.glUniform1f(uHalfDiagonal, l.halfDiagonal.toFloat())
        GLES30.glUniform1f(uZoom, l.zoom.toFloat())
        GLES30.glUniform1f(uManualK, l.manualK.toFloat())
        val warp = l.warp
        GLES30.glUniform1f(uWarpOn, if (warp != null) 1f else 0f)
        GLES30.glUniform1f(uWarpPlanes, l.warpPlanes.toFloat())
        val k = FloatArray(18)
        warp?.coefficients?.forEachIndexed { i, v -> if (i < 18) k[i] = v.toFloat() }
        GLES30.glUniform1fv(uWarpK, 18, k, 0)
        GLES30.glUniform2f(uWarpCenter, l.warpCenterX.toFloat(), l.warpCenterY.toFloat())
        GLES30.glUniform1f(uWarpNorm, l.warpNorm.toFloat())
        val vig = l.vignette
        GLES30.glUniform1f(uVigOn, if (vig != null) 1f else 0f)
        GLES30.glUniform1fv(uVigK, 5, FloatArray(5) { (vig?.k?.getOrNull(it) ?: 0.0).toFloat() }, 0)
        GLES30.glUniform2f(uVigCenter, l.vignetteCenterX.toFloat(), l.vignetteCenterY.toFloat())
        GLES30.glUniform1f(uVigNorm, l.vignetteNorm.toFloat())
        val shading = info.shading
        GLES30.glUniform1f(uShadingUndo, if (l.undoShading) 1f else 0f)
        GLES30.glUniform1i(uShading, ShadingTexture.UNIT)
        GLES30.glUniform4f(
            uShadingGrid,
            (shading?.originV ?: 0.0).toFloat(), (shading?.originH ?: 0.0).toFloat(),
            (shading?.spacingV ?: 1.0).toFloat(), (shading?.spacingH ?: 1.0).toFloat(),
        )
        GLES30.glUniform2f(uShadingPoints, (shading?.pointsV ?: 1).toFloat(), (shading?.pointsH ?: 1).toFloat())
        GLES30.glUniform2f(uSensorSize, info.sensorWidth.toFloat(), info.sensorHeight.toFloat())
        GLES30.glUniform1f(uManualVig, l.manualVignette.toFloat())
        GLES30.glUniform1f(uManualVigMid, l.manualVignetteMidpoint.toFloat())
    }

    fun release() = GLES30.glDeleteProgram(id)

    private companion object {
        val FULL_UV_RECT = floatArrayOf(0f, 0f, 1f, 1f)
    }
}

/** A small whole-image copy (high-pass contrast, bloom) on its own unit; re-uploads when it changes. */
internal class ImageTexture(private val unit: Int) {
    private val id = GlUtil.createTexture(GLES30.GL_LINEAR)
    private var uploaded: PreviewImage? = null

    fun bind(base: PreviewImage?) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        if (base != null && base !== uploaded) {
            GlUtil.uploadHalfRgb(base.width, base.height, base.pixels)
            uploaded = base
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    fun release() = GLES30.glDeleteTextures(1, intArrayOf(id), 0)

    companion object {
        const val BASE_UNIT = 3
        const val BLOOM_HALO_UNIT = 4
        const val BLOOM_TAIL_UNIT = 5
    }
}

/** The skin mask (R16F) on its own unit; re-uploads when it changes. */
internal class MaskTexture {
    private val id = GlUtil.createTexture(GLES30.GL_LINEAR)
    private var uploaded: SkinMask? = null

    fun bind(mask: SkinMask?) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        if (mask != null && mask !== uploaded) {
            mask.data.position(0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F, mask.width, mask.height, 0,
                GLES30.GL_RED, GLES30.GL_FLOAT, mask.data,
            )
            uploaded = mask
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    fun release() = GLES30.glDeleteTextures(1, intArrayOf(id), 0)

    companion object {
        const val UNIT = 6
    }
}

/**
 * The layers' painted masks ([BrushRaster], RG8: paint, erase) in one 2x2 atlas on its own unit, one slot per packed
 * layer. A plain 2D texture rather than an array: a program that never binds it (the viewfinder) leaves the sampler
 * on unit 0 with uImage, and samplers of different types on one unit are an error. Uploads only what changed.
 */
internal class BrushTexture {
    private val id = GlUtil.createTexture(GLES30.GL_LINEAR)
    private var slotW = 0
    private var slotH = 0
    private val slotRaster = arrayOfNulls<BrushRaster>(4)
    private val slotVersion = IntArray(4)
    private val maxSize = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0]

    init {
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG8, 1, 1, 0, GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE,
            java.nio.ByteBuffer.allocateDirect(2),
        )
    }

    fun bind(brush: List<BrushRaster?>) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        val any = brush.firstOrNull { it != null }
        if (any != null && 2 * maxOf(any.width, any.height) <= maxSize) {
            if (any.width != slotW || any.height != slotH) {
                slotW = any.width
                slotH = any.height
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG8, 2 * slotW, 2 * slotH, 0, GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, null as java.nio.Buffer?,
                )
                slotRaster.fill(null)
            }
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            brush.forEachIndexed { i, r ->
                if (r == null || r.width != slotW || r.height != slotH) return@forEachIndexed
                slotVersion[i] = r.upload(slotVersion[i], slotRaster[i] !== r) { pixels, rect ->
                    GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, r.width)
                    GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, rect.x)
                    GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, rect.y)
                    pixels.position(0)
                    GLES30.glTexSubImage2D(
                        GLES30.GL_TEXTURE_2D, 0, (i % 2) * slotW + rect.x, (i / 2) * slotH + rect.y, rect.width, rect.height,
                        GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, pixels,
                    )
                }
                slotRaster[i] = r
            }
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    fun release() = GLES30.glDeleteTextures(1, intArrayOf(id), 0)

    companion object {
        const val UNIT = 7
    }
}

/** The lens shading gain map (R16F) on its own texture unit; re-uploads when the image changes. */
internal class ShadingTexture {
    private val id: Int
    private var uploaded: LensInfo.ShadingMap? = null

    init {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        id = ids[0]
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        // A 1x1 neutral map so the sampler is always complete.
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F, 1, 1, 0, GLES30.GL_RED, GLES30.GL_FLOAT,
            java.nio.FloatBuffer.wrap(floatArrayOf(1f)),
        )
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    fun bind(map: LensInfo.ShadingMap?) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        if (map != null && map !== uploaded) {
            map.gains.position(0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F, map.pointsH, map.pointsV, 0,
                GLES30.GL_RED, GLES30.GL_FLOAT, map.gains,
            )
            uploaded = map
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    fun release() = GLES30.glDeleteTextures(1, intArrayOf(id), 0)

    companion object {
        const val UNIT = 2
    }
}

/** The 3D LUT texture on its own texture unit; re-uploads only when the LUT changes. */
internal class LutTexture(private val unit: Int = UNIT) {
    private val id: Int
    private var uploaded: LutData? = null

    init {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        id = ids[0]
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, id)
        for (param in intArrayOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER)) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, param, GLES30.GL_LINEAR)
        }
        for (param in intArrayOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R)) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, param, GLES30.GL_CLAMP_TO_EDGE)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /** Binds [lut] to its unit, uploading it if it isn't the one already on the GPU. Leaves unit 0 active. */
    fun bind(lut: LutData?) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, id)
        if (lut != null && lut !== uploaded) {
            lut.data.position(0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, lut.size, lut.size, lut.size, 0,
                GLES30.GL_RGB, GLES30.GL_FLOAT, lut.data,
            )
            uploaded = lut
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    fun release() = GLES30.glDeleteTextures(1, intArrayOf(id), 0)

    companion object {
        const val UNIT = 1
    }
}

/** The film and print LUTs of a [FilmData] (shaders/film_form.glsl, film/film.glsl), each on its own unit. */
internal class FilmTextures {
    private val film = LutTexture(FILM_UNIT)
    private val print = LutTexture(PRINT_UNIT)

    fun bind(f: FilmData?) {
        film.bind(f?.film)
        print.bind(f?.print)
    }

    fun release() {
        film.release()
        print.release()
    }

    companion object {
        const val FILM_UNIT = 8
        const val PRINT_UNIT = 9

        /** spektrafilm's default format: a 35 mm frame, 36 mm on the long side. Sizes grain and halation. */
        const val FRAME_WIDTH_UM = 36000f
    }
}

internal class FullscreenQuad {
    private val vao: Int
    private val vbo: Int

    init {
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val data = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(vertices); position(0) }
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, data, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
    }

    fun draw() {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
    }
}
