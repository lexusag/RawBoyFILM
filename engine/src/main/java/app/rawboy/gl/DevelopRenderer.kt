package app.rawboy.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders the developed image into a GLSurfaceView: either the whole downscaled
 * preview (aspect-fit) or, when zoomed, a 1:1 window into a full-resolution tile.
 */
class DevelopRenderer : GLSurfaceView.Renderer {
    private val lock = Any()
    private var image: PreviewImage? = null
    private var imageDirty = false
    private var detail: DetailTile? = null
    private var detailDirty = false

    @Volatile
    var params: ShaderParams? = null

    /** Frame around the photo in the fit view: width in pixels and its grey level (display-encoded). */
    @Volatile
    var frameWidth: Int = 0

    @Volatile
    var frameGrey: Float = FrameColor.GREY.level

    /** Extra empty space around the frame in the fit view, pixels (room for the mask tool's handles). */
    @Volatile
    var margin: Int = 0

    /** Paints clipped highlights red and crushed shadows blue (Pro's histogram, `clipWarning` in develop.glsl). */
    @Volatile
    var clipWarning: Boolean = false

    /** Centre of the 100% view in full-resolution pixels, or null for fit-to-view. */
    @Volatile
    var zoomCenter: Pair<Float, Float>? = null

    private var program: DevelopProgram? = null
    private var quad: FullscreenQuad? = null
    private var lutTexture: LutTexture? = null
    private var shadingTexture: ShadingTexture? = null
    private var baseTexture: ImageTexture? = null
    private var bloomHaloTexture: ImageTexture? = null
    private var bloomTailTexture: ImageTexture? = null
    private var maskTexture: MaskTexture? = null
    private var brushTexture: BrushTexture? = null
    private var filmTextures: FilmTextures? = null
    private var previewTexture = 0
    private var detailTexture = 0
    private var uploadedDetail: DetailTile? = null
    private var viewWidth = 1
    private var viewHeight = 1

    fun setImage(newImage: PreviewImage?) = synchronized(lock) {
        image = newImage
        imageDirty = true
    }

    fun setDetail(tile: DetailTile?) = synchronized(lock) {
        detail = tile
        detailDirty = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context means all GL objects are gone: rebuild and re-upload.
        program = DevelopProgram(forExport = false)
        quad = FullscreenQuad()
        lutTexture = LutTexture()
        shadingTexture = ShadingTexture()
        baseTexture = ImageTexture(ImageTexture.BASE_UNIT)
        bloomHaloTexture = ImageTexture(ImageTexture.BLOOM_HALO_UNIT)
        bloomTailTexture = ImageTexture(ImageTexture.BLOOM_TAIL_UNIT)
        maskTexture = MaskTexture()
        brushTexture = BrushTexture()
        filmTextures = FilmTextures()
        previewTexture = GlUtil.createTexture(GLES30.GL_LINEAR)
        detailTexture = GlUtil.createTexture(GLES30.GL_NEAREST) // 1:1, keep pixels crisp
        uploadedDetail = null
        synchronized(lock) {
            imageDirty = true
            detailDirty = true
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, viewWidth, viewHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val img: PreviewImage?
        val tile: DetailTile?
        val uploadImage: Boolean
        val uploadDetail: Boolean
        synchronized(lock) {
            img = image
            tile = detail
            uploadImage = imageDirty
            uploadDetail = detailDirty
            imageDirty = false
            detailDirty = false
        }
        if (uploadImage && img != null) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, previewTexture)
            GlUtil.uploadHalfRgb(img.width, img.height, img.pixels)
        }
        if (uploadDetail) {
            uploadedDetail = tile?.also {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, detailTexture)
                GlUtil.uploadHalfRgb(it.rect.width, it.rect.height, it.pixels)
            }
        }

        GLES30.glClearColor(0.07f, 0.07f, 0.07f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val p = params ?: return
        if (img == null) return

        lutTexture!!.bind(p.lut)
        shadingTexture!!.bind(p.lens.info.shading)
        baseTexture!!.bind(p.base)
        bloomHaloTexture!!.bind(p.bloomHalo)
        bloomTailTexture!!.bind(p.bloomTail)
        maskTexture!!.bind(p.skinMask)
        brushTexture!!.bind(p.layers.brush)
        filmTextures!!.bind(p.film)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val center = zoomCenter
        val detailTile = uploadedDetail
        if (center != null && detailTile != null) {
            drawZoomed(p, img, detailTile, center)
        } else {
            drawFit(p, img)
        }
        program!!.setClipWarning(clipWarning)
        quad!!.draw()
    }

    private fun drawFit(p: ShaderParams, img: PreviewImage) {
        val outW = p.lens.outputWidth
        val outH = p.lens.outputHeight
        val frame = frameWidth
        val r = ZoomMath.fitRect(viewWidth, viewHeight, outW, outH, frame + margin)

        // Frame: a band of neutral grey / white / black around the photo, for judging exposure.
        if (frame > 0) {
            val g = frameGrey
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            GLES30.glScissor(
                (r[0] - frame).toInt(), (viewHeight - (r[1] + r[3] + frame)).toInt(), // GL origin is bottom-left
                (r[2] + 2 * frame).toInt(), (r[3] + 2 * frame).toInt(),
            )
            GLES30.glClearColor(g, g, g, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        }

        val sx = r[2] / viewWidth
        val sy = r[3] / viewHeight
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, previewTexture)
        val input = PixelRect(0, 0, img.fullWidth, img.fullHeight)
        val output = PixelRect(0, 0, outW, outH)
        val geometry = SampleGeometry(img.width, img.height, input, output, img.fullWidth, img.fullHeight)
        program!!.bind(p, geometry, sx, sy, flipY = true)
    }

    private fun drawZoomed(p: ShaderParams, img: PreviewImage, tile: DetailTile, center: Pair<Float, Float>) {
        val t = tile.rect
        // Output = what's on screen; input = the loaded full-resolution tile. If the
        // lens moves pixels outside the tile while a new one loads, edges smear briefly.
        val view = ZoomMath.viewRect(
            center.first, center.second, viewWidth, viewHeight, p.lens.outputWidth, p.lens.outputHeight,
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, detailTexture)
        val geometry = SampleGeometry(t.width, t.height, t, view, img.fullWidth, img.fullHeight)
        program!!.bind(
            p, geometry,
            scaleX = view.width.toFloat() / viewWidth,
            scaleY = view.height.toFloat() / viewHeight,
            flipY = true,
        )
    }
}

/** Frame colours around the photo, cycled by tapping outside it. Levels are display-encoded (sRGB). */
enum class FrameColor(val level: Float) {
    /** Perceptual middle grey (~18% reflectance). */
    GREY(0.46f),
    WHITE(1f),
    BLACK(0f),
    ;

    fun next(): FrameColor = entries[(ordinal + 1) % entries.size]
}
