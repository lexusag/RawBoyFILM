package app.rawboy.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import java.nio.ByteBuffer

/**
 * Runs the develop shader on full-resolution tiles in a private EGL context.
 * Must be created, used and closed on the same thread.
 *
 * Output is read back as RGBA 16-bit values from an RGBA16UI target, as uint16 or uint32 ([bytesPerSample]).
 */
internal class OffscreenDeveloper(
    /** Source image size (display orientation); [setImageSize] changes it when the developer is reused. */
    private var imageWidth: Int,
    private var imageHeight: Int,
    /** Ask the GPU to schedule this context behind others (the camera's viewfinder), where supported. */
    lowPriority: Boolean = false,
) : AutoCloseable {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val surface: EGLSurface
    private val program: DevelopProgram
    private val quad: FullscreenQuad
    private val lutTexture: LutTexture
    private val filmTextures: FilmTextures
    private val shadingTexture: ShadingTexture
    private val baseTexture: ImageTexture
    private val bloomHaloTexture: ImageTexture
    private val bloomTailTexture: ImageTexture
    private val maskTexture: MaskTexture
    private val brushTexture: BrushTexture
    private val inputTexture: Int
    private val outputTexture: Int
    private val framebuffer: Int
    private val denoiser: WaveletDenoise
    private var uploads = 0
    private var denoisedInput = false

    init {
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No GLES3 pbuffer config"
        }
        val config = configs[0]!!
        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()
        val priority = if (lowPriority && "EGL_IMG_context_priority" in extensions) {
            intArrayOf(EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_LOW_IMG)
        } else {
            intArrayOf()
        }
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, *priority, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        surface = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }

        program = DevelopProgram(forExport = true)
        quad = FullscreenQuad()
        lutTexture = LutTexture()
        filmTextures = FilmTextures()
        shadingTexture = ShadingTexture()
        baseTexture = ImageTexture(ImageTexture.BASE_UNIT)
        bloomHaloTexture = ImageTexture(ImageTexture.BLOOM_HALO_UNIT)
        bloomTailTexture = ImageTexture(ImageTexture.BLOOM_TAIL_UNIT)
        maskTexture = MaskTexture()
        brushTexture = BrushTexture()
        inputTexture = GlUtil.createTexture(GLES30.GL_NEAREST)
        outputTexture = GlUtil.createTexture(GLES30.GL_NEAREST)
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        denoiser = WaveletDenoise(quad, GLES30.GL_NEAREST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DITHER)
    }

    /**
     * Read back 16-bit samples (8 bytes per pixel) when the driver offers that for the RGBA16UI target (Pixel 9
     * Pro does: half the readback time); else 32-bit, the format GLES always allows. Known after the first draw.
     */
    private var shortSamples: Boolean? = null

    /** Bytes per sample of [render]/[renderPreview] output: 2 or 4. Valid after the first render. */
    val bytesPerSample: Int get() = if (shortSamples == true) 2 else 4

    private fun readsShorts(): Boolean {
        val f = IntArray(2)
        GLES30.glGetIntegerv(GLES30.GL_IMPLEMENTATION_COLOR_READ_FORMAT, f, 0)
        GLES30.glGetIntegerv(GLES30.GL_IMPLEMENTATION_COLOR_READ_TYPE, f, 1)
        return f[0] == GLES30.GL_RGBA_INTEGER && f[1] == GLES30.GL_UNSIGNED_SHORT
    }

    /** Smallest grain cell for [render] (see SampleGeometry.minGrainPx). */
    var minGrainPx = 1f

    fun setImageSize(width: Int, height: Int) {
        imageWidth = width
        imageHeight = height
    }

    /**
     * Develops the full-resolution region [out] and reads it back.
     *
     * [input] holds RGB half floats for [inRect], which must contain every source
     * pixel [out] needs (lens geometry + denoise neighbourhood), so tiles match
     * seamlessly. [output] receives out.width*out.height*4 values in 0..65535, [bytesPerSample] bytes each.
     */
    fun render(params: ShaderParams, input: ByteBuffer, inRect: PixelRect, out: PixelRect, output: ByteBuffer) {
        bindInputs(params)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
        GlUtil.uploadHalfRgb(inRect.width, inRect.height, input)
        val geometry = SampleGeometry(inRect.width, inRect.height, inRect, out, imageWidth, imageHeight, minGrainPx)
        draw(params, geometry, out.width, out.height, output, denoised(params, inRect.width, inRect.height, 1f))
    }

    /**
     * Develops the whole photo from its downscaled [preview] into a [width] x [height]
     * image (thumbnails), the way the on-screen fit view does: spatial effects are
     * scaled by the preview's footprint. [output] receives width*height*4 values in 0..65535, [bytesPerSample] bytes each.
     */
    fun renderPreview(params: ShaderParams, preview: PreviewImage, width: Int, height: Int, output: ByteBuffer) {
        bindInputs(params)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
        GlUtil.uploadHalfRgb(preview.width, preview.height, preview.pixels)
        val input = PixelRect(0, 0, preview.fullWidth, preview.fullHeight)
        val out = PixelRect(0, 0, params.lens.outputWidth, params.lens.outputHeight)
        val geometry = SampleGeometry(preview.width, preview.height, input, out, preview.fullWidth, preview.fullHeight)
        draw(params, geometry, width, height, output, denoised(params, preview.width, preview.height, preview.width.toFloat() / preview.fullWidth))
    }

    private fun bindInputs(params: ShaderParams) {
        lutTexture.bind(params.lut)
        filmTextures.bind(params.film)
        shadingTexture.bind(params.lens.info.shading)
        baseTexture.bind(params.base)
        bloomHaloTexture.bind(params.bloomHalo)
        bloomTailTexture.bind(params.bloomTail)
        maskTexture.bind(params.skinMask)
        brushTexture.bind(params.layers.brush)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /** The input texture, or its copy through the wavelet denoise pre-pass when denoise is on (see WaveletDenoise). */
    private fun denoised(p: ShaderParams, w: Int, h: Int, pixelScale: Float): Int {
        uploads++ // every render uploads new pixels
        denoisedInput = denoiser.active(p.denoise, p.colorDenoise)
        if (!denoisedInput) return inputTexture
        return denoiser.apply(inputTexture, uploads, w, h, pixelScale, p.denoise, p.colorDenoise)
    }

    /** Draws [input] on unit 0 into a [width] x [height] target and reads it back. */
    private fun draw(params: ShaderParams, geometry: SampleGeometry, width: Int, height: Int, output: ByteBuffer, input: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outputTexture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16UI, width, height, 0,
            GLES30.GL_RGBA_INTEGER, GLES30.GL_UNSIGNED_SHORT, null,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, outputTexture, 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Framebuffer incomplete: 0x${status.toString(16)}" }

        GLES30.glViewport(0, 0, width, height)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, input)
        program.bind(params, geometry, 1f, 1f, flipY = false) // framebuffer row 0 = output row 0
        program.setDenoisePre(denoisedInput)
        quad.draw()

        if (shortSamples == null) shortSamples = readsShorts()
        output.position(0)
        GLES30.glReadPixels(
            0, 0, width, height, GLES30.GL_RGBA_INTEGER,
            if (shortSamples == true) GLES30.GL_UNSIGNED_SHORT else GLES30.GL_UNSIGNED_INT, output,
        )
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "GL error 0x${error.toString(16)} during export" }
    }

    override fun close() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES30.glDeleteTextures(2, intArrayOf(inputTexture, outputTexture), 0)
        denoiser.release()
        quad.release()
        lutTexture.release()
        filmTextures.release()
        shadingTexture.release()
        baseTexture.release()
        bloomHaloTexture.release()
        bloomTailTexture.release()
        maskTexture.release()
        brushTexture.release()
        program.release()
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        // No eglTerminate: the display is shared with the on-screen GLSurfaceView.
    }

    private companion object {
        // EGL_IMG_context_priority
        const val EGL_CONTEXT_PRIORITY_LEVEL_IMG = 0x3100
        const val EGL_CONTEXT_PRIORITY_LOW_IMG = 0x3103
    }
}
