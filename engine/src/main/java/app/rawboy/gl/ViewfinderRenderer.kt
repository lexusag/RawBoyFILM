package app.rawboy.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import app.rawboy.session.PhotoSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A live camera preview developed by the same shader as a raw: each camera frame is turned
 * back into scene-linear Rec.2020 (pass 1, into a half-float texture, upright), then developed
 * with [params] (pass 2) and drawn aspect-fit.
 *
 * The camera's ISP is asked for a known tone curve (sRGB or a plain gamma, see [Frame.gamma])
 * so the linearisation is exact up to a gain; [Frame.scale] brings the result to raw units
 * (1.0 = sensor clip). The whole-image effects (high-pass contrast, bloom) sample small copies
 * made on the GPU from every frame; every [baseIntervalMs] the 512 px one is also read back for
 * analysis, see [onBase].
 */
class ViewfinderRenderer(
    /** The camera's output texture; called on the GL thread whenever the GL context is (re)created. */
    private val onSurfaceTexture: (SurfaceTexture) -> Unit,
    /** Asks the view for a frame (GLSurfaceView.requestRender). */
    private val requestRender: () -> Unit,
    /**
     * A ~512 px linear RGBA float copy of the current frame (row 0 = top), on the GL thread;
     * hand it off quickly. The array is not reused.
     */
    private val onBase: (width: Int, height: Int, rgba: FloatArray) -> Unit,
) : GLSurfaceView.Renderer {

    /** How camera buffers map to the upright image. */
    data class Frame(
        val bufferWidth: Int,
        val bufferHeight: Int,
        /** Clockwise rotation that makes a buffer upright (the sensor orientation for a back camera). */
        val rotation: Int,
        /** Full-resolution (raw) size of the upright image, which sizes grain and denoise like the final. */
        val fullWidth: Int,
        val fullHeight: Int,
        /** Tone curve the ISP applied: <= 0 means the sRGB curve, otherwise a plain power. */
        val gamma: Float = 0f,
        /** Gain from linearised preview values to raw units (1.0 = sensor clip). */
        val scale: Float = 1f,
        /** Show a mirror image (front camera), like a selfie preview. */
        val mirror: Boolean = false,
        /**
         * Linear preview RGB -> Rec.2020, GL column-major, matching the raw decoder's colours (the ISP's own
         * colour matrix is more saturated); null = plain sRGB primaries.
         */
        val color: FloatArray? = null,
    ) {
        val uprightWidth get() = if (rotation % 180 == 0) bufferWidth else bufferHeight
        val uprightHeight get() = if (rotation % 180 == 0) bufferHeight else bufferWidth
    }

    @Volatile
    var frame: Frame? = null

    @Volatile
    var params: ShaderParams? = null

    @Volatile
    var baseIntervalMs: Long = 300

    private var surfaceTexture: SurfaceTexture? = null
    private var cameraTexture = 0
    private val texMatrix = FloatArray(16)

    private var linearize: LinearizeProgram? = null
    private var copy: PassProgram? = null
    private var boost: PassProgram? = null
    private var boxDown: PassProgram? = null
    private var tailScatter: PassProgram? = null
    private var develop: DevelopProgram? = null
    private var quad: FullscreenQuad? = null
    private var lutTexture: LutTexture? = null
    private var filmTextures: FilmTextures? = null
    private var shadingTexture: ShadingTexture? = null
    private var maskTexture: MaskTexture? = null

    private val linear = Target()
    private val small = Target()
    private val halo = Target()
    private val tailRaw = Target()
    private val tail = Target()
    private var lastBase = 0L
    private var viewWidth = 1
    private var viewHeight = 1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: every GL object (and the camera's texture) is gone.
        linear.reset()
        small.reset()
        halo.reset()
        tailRaw.reset()
        tail.reset()
        pending = null
        pixelBuffer = 0
        pixelBufferSize = 0
        val yuv = GLES30.glGetString(GLES30.GL_EXTENSIONS)?.contains("GL_EXT_YUV_target") == true
        Log.d("RawBoyViewfinder", "camera frames: " + if (yuv) "YUV, converted here" else "RGB from the driver")
        linearize = LinearizeProgram(yuv)
        copy = PassProgram(PassProgram.COPY, trilinear = true)
        boost = PassProgram(PassProgram.BOOST, trilinear = false)
        boxDown = PassProgram(PassProgram.BOX_DOWN, trilinear = false)
        tailScatter = PassProgram(PassProgram.TAIL_SCATTER, trilinear = false)
        develop = DevelopProgram(forExport = false)
        quad = FullscreenQuad()
        lutTexture = LutTexture()
        filmTextures = FilmTextures()
        shadingTexture = ShadingTexture()
        maskTexture = MaskTexture()

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        cameraTexture = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        surfaceTexture?.release()
        val st = SurfaceTexture(cameraTexture)
        st.setOnFrameAvailableListener { requestRender() }
        surfaceTexture = st
        GLES30.glDisable(GLES30.GL_BLEND)
        onSurfaceTexture(st)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewWidth, viewHeight)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val f = frame ?: return

        // Pass 1: camera frame -> upright linear Rec.2020 in raw units.
        val w = f.uprightWidth
        val h = f.uprightHeight
        linear.ensure(w, h, mipmapped = true)
        linear.bindForDrawing()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        linearize!!.draw(quad!!, texMatrix, f, dataSpaceOf(st))

        // The whole-image copies, from this frame: built from a readback instead, they lagged the image by
        // a few hundred ms and bright lights left a trailing glow when the camera moved.
        val now = SystemClock.uptimeMillis()
        val scale = BASE_MAX_DIMENSION.toFloat() / max(w, h)
        val sw = max(1, (w * scale).roundToInt())
        val sh = max(1, (h * scale).roundToInt())
        val tailScale = PhotoSession.BLOOM_TAIL_MAX_DIMENSION.toFloat() / max(sw, sh)
        val tw = max(1, (sw * tailScale).toInt())
        val th = max(1, (sh * tailScale).toInt())
        makeCopies(sw, sh, tw, th)

        // Every so often the 512 px copy is read back for the auto develop. The readback is asynchronous
        // (pixel buffer + fence), collected on a later frame, so the GL thread never waits for the GPU:
        // a blocking glReadPixels here dropped a viewfinder frame three times a second.
        collectBase()
        if (pending == null && now - lastBase >= baseIntervalMs) {
            lastBase = now
            startBase(sw, sh)
        }

        // Pass 2: develop to the screen, aspect-fit. The first params come from the first readback above.
        val p = params ?: return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewWidth, viewHeight)
        lutTexture!!.bind(p.lut)
        filmTextures!!.bind(p.film)
        shadingTexture!!.bind(p.lens.info.shading)
        bindUnit(ImageTexture.BASE_UNIT, small.texture)
        bindUnit(ImageTexture.BLOOM_HALO_UNIT, halo.texture)
        bindUnit(ImageTexture.BLOOM_TAIL_UNIT, tail.texture)
        maskTexture!!.bind(p.skinMask)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, linear.texture)
        val outW = p.lens.outputWidth
        val outH = p.lens.outputHeight
        val r = ZoomMath.fitRect(viewWidth, viewHeight, outW, outH, 0)
        val input = PixelRect(0, 0, f.fullWidth, f.fullHeight)
        val output = PixelRect(0, 0, outW, outH)
        val geometry = SampleGeometry(w, h, input, output, f.fullWidth, f.fullHeight)
        develop!!.bind(p, geometry, r[2] / viewWidth, r[3] / viewHeight, flipY = true)
        develop!!.bindLiveCopies(p, sw, sh, tw, th)
        quad!!.draw()
        stats.frame(SystemClock.uptimeMillis() - now)
    }

    /** A readback in flight: its pixel buffer, fence and size. */
    private class PendingRead(val buffer: Int, val fence: Long, val width: Int, val height: Int, val started: Long)

    private var pending: PendingRead? = null
    private var pixelBuffer = 0
    private var pixelBufferSize = 0

    /**
     * The copies a decoded raw gets from PhotoSession (base, bloomSourcesOf), from the linear frame: [small] the
     * ~512 px base (trilinear, from mipmaps), [halo] the same highlight-boosted, [tail] that box-filtered to ~64 px
     * and scattered by [BloomTail]'s kernel.
     */
    private fun makeCopies(sw: Int, sh: Int, tw: Int, th: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, linear.texture)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        small.ensure(sw, sh, mipmapped = false)
        small.bindForDrawing()
        copy!!.draw(quad!!, linear.texture, sw, sh)
        halo.ensure(sw, sh, mipmapped = false)
        halo.bindForDrawing()
        boost!!.draw(quad!!, small.texture, sw, sh)
        tailRaw.ensure(tw, th, mipmapped = false)
        tailRaw.bindForDrawing()
        boxDown!!.draw(quad!!, halo.texture, sw, sh)
        tail.ensure(tw, th, mipmapped = false)
        tail.bindForDrawing()
        tailScatter!!.draw(quad!!, tailRaw.texture, tw, th)
    }

    /** The current buffer's data space (android.hardware.DataSpace), or 0 = unknown before Android 13. */
    private fun dataSpaceOf(st: SurfaceTexture): Int =
        if (android.os.Build.VERSION.SDK_INT >= 33) st.dataSpace else 0

    private fun bindUnit(unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /** Starts reading back the ~512 px base copy ([makeCopies] drew it this frame). */
    private fun startBase(sw: Int, sh: Int) {
        small.bindForDrawing()
        val bytes = sw * sh * 16
        if (pixelBuffer == 0) {
            val ids = IntArray(1)
            GLES30.glGenBuffers(1, ids, 0)
            pixelBuffer = ids[0]
            pixelBufferSize = 0
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pixelBuffer)
        if (pixelBufferSize < bytes) {
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, bytes, null, GLES30.GL_STREAM_READ)
            pixelBufferSize = bytes
        }
        GLES30.glReadPixels(0, 0, sw, sh, GLES30.GL_RGBA, GLES30.GL_FLOAT, 0) // into the buffer, no wait
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        GLES30.glFlush()
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            GLES30.glDeleteSync(fence)
            return
        }
        pending = PendingRead(pixelBuffer, fence, sw, sh, SystemClock.uptimeMillis())
    }

    /** Hands over the readback once the GPU has finished it; returns at once otherwise. */
    private fun collectBase() {
        val read = pending ?: return
        val status = GLES30.glClientWaitSync(read.fence, 0, 0)
        if (status == GLES30.GL_TIMEOUT_EXPIRED) return
        GLES30.glDeleteSync(read.fence)
        pending = null
        if (status == GLES30.GL_WAIT_FAILED) return
        val n = read.width * read.height * 4
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, read.buffer)
        val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, n * 4, GLES30.GL_MAP_READ_BIT) as? ByteBuffer
        val rgba = mapped?.let {
            FloatArray(n).also { out -> it.order(ByteOrder.nativeOrder()).asFloatBuffer().get(out) }
        }
        GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        stats.readback(SystemClock.uptimeMillis() - read.started)
        if (rgba != null) onBase(read.width, read.height, rgba)
    }

    private val stats = FrameStats()

    /** Logs frame rate and GL-thread time every few seconds (tag RawBoyViewfinder), for tuning on the phone. */
    private class FrameStats {
        private var frames = 0
        private var cpuMs = 0L
        private var worstMs = 0L
        private var readbacks = 0
        private var readbackMs = 0L
        private var since = SystemClock.uptimeMillis()

        fun frame(ms: Long) {
            frames++
            cpuMs += ms
            worstMs = max(worstMs, ms)
            val now = SystemClock.uptimeMillis()
            if (now - since >= 5000) {
                Log.d(
                    "RawBoyViewfinder",
                    "%.1f fps, GL thread %.1f ms/frame (worst %d), readback latency %.0f ms".format(
                        frames * 1000f / (now - since), cpuMs.toFloat() / frames, worstMs,
                        if (readbacks > 0) readbackMs.toFloat() / readbacks else 0f,
                    ),
                )
                frames = 0
                cpuMs = 0
                worstMs = 0
                readbacks = 0
                readbackMs = 0
                since = now
            }
        }

        fun readback(ms: Long) {
            readbacks++
            readbackMs += ms
        }
    }

    /** Stops delivering frames; call when the camera closes. */
    fun release() {
        surfaceTexture?.setOnFrameAvailableListener(null)
    }

    /** A half-float colour target; row 0 = top of the image. */
    private class Target {
        var texture = 0
        private var framebuffer = 0
        private var width = 0
        private var height = 0

        fun reset() {
            texture = 0
            framebuffer = 0
            width = 0
            height = 0
        }

        fun ensure(w: Int, h: Int, mipmapped: Boolean) {
            if (texture != 0 && w == width && h == height) return
            if (texture == 0) {
                texture = GlUtil.createTexture(GLES30.GL_LINEAR)
                val ids = IntArray(1)
                GLES30.glGenFramebuffers(1, ids, 0)
                framebuffer = ids[0]
            }
            width = w
            height = h
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            // Storage for the whole mip chain, so the base readback can sample a box-filtered level.
            val levels = if (mipmapped) 32 - Integer.numberOfLeadingZeros(max(w, h)) else 1
            var lw = w
            var lh = h
            for (level in 0 until levels) {
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, level, GLES30.GL_RGBA16F, lw, lh, 0,
                    GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null,
                )
                lw = max(1, lw / 2)
                lh = max(1, lh / 2)
            }
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAX_LEVEL, levels - 1)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0,
            )
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Viewfinder framebuffer incomplete: 0x${status.toString(16)}" }
        }

        fun bindForDrawing() {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glViewport(0, 0, width, height)
        }
    }

    /** Camera frame (external OES texture) -> upright scene-linear Rec.2020. */
    private class LinearizeProgram(private val yuv: Boolean) {
        private val id = GlUtil.createProgram(VERTEX, if (yuv) YUV_FRAGMENT else FRAGMENT)
        private val uCamera = GLES30.glGetUniformLocation(id, "uCamera")
        private val uTexMatrix = GLES30.glGetUniformLocation(id, "uTexMatrix")
        private val uRotation = GLES30.glGetUniformLocation(id, "uRotation")
        private val uGamma = GLES30.glGetUniformLocation(id, "uGamma")
        private val uScale = GLES30.glGetUniformLocation(id, "uScale")
        private val uMirror = GLES30.glGetUniformLocation(id, "uMirror")
        private val uColor = GLES30.glGetUniformLocation(id, "uColor")
        private val uYuvToRgb = GLES30.glGetUniformLocation(id, "uYuvToRgb")
        private val uYuvOffset = GLES30.glGetUniformLocation(id, "uYuvOffset")
        private var yuvDataSpace = -1

        fun draw(quad: FullscreenQuad, texMatrix: FloatArray, f: Frame, dataSpace: Int) {
            GLES30.glUseProgram(id)
            GLES30.glUniform1i(uCamera, 0)
            GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
            // Camera2 usually rotates preview buffers to the display's natural orientation itself, through
            // the SurfaceTexture transform (Pixel phones do; the emulator doesn't). Rotate only when it hasn't:
            // a matrix that turns by 90 degrees has its scale on the off-diagonal (column-major [1], [4]).
            val matrixRotates = abs(texMatrix[1]) > abs(texMatrix[0])
            val turns = if (matrixRotates) 0 else ((f.rotation / 90) % 4 + 4) % 4
            GLES30.glUniform1i(uRotation, turns)
            // SurfaceTexture's matrix normally flips t only (determinant < 0); for a front camera Camera2 usually
            // adds the selfie mirror itself (determinant > 0). Mirror here only when it hasn't.
            val determinant = texMatrix[0] * texMatrix[5] - texMatrix[1] * texMatrix[4]
            GLES30.glUniform1f(uMirror, if (f.mirror && determinant < 0f) 1f else 0f)
            GLES30.glUniform1f(uGamma, f.gamma)
            GLES30.glUniform1f(uScale, f.scale)
            GLES30.glUniformMatrix3fv(uColor, 1, false, f.color ?: SRGB_TO_REC2020, 0)
            if (yuv) {
                val (matrix, offset) = yuvToRgb(dataSpace)
                GLES30.glUniformMatrix3fv(uYuvToRgb, 1, false, matrix, 0)
                GLES30.glUniform3fv(uYuvOffset, 1, offset, 0)
                if (dataSpace != yuvDataSpace) {
                    yuvDataSpace = dataSpace
                    Log.d("RawBoyViewfinder", "camera data space 0x${dataSpace.toString(16)}")
                }
            }
            quad.draw()
        }

        /**
         * Y'CbCr -> R'G'B' for a camera buffer: BT.601 (JFIF), the camera's encoding, full range unless the data
         * space says limited (BT.2020 only when it says so). The data space's standard can't be trusted: the
         * Pixel 9 Pro labels its preview sRGB (0x8810000), so the driver decoded with BT.709, but the ISP encodes
         * BT.601; fitted against the raw, BT.601 leaves no cross-channel error, BT.709 over-saturated reds ~20%.
         * Returns a GL column-major matrix and the offset to subtract first.
         */
        private fun yuvToRgb(dataSpace: Int): Pair<FloatArray, FloatArray> {
            val standard = (dataSpace shr 16) and 0x3f
            val range = (dataSpace shr 27) and 0x7
            val (kr, kb) = if (standard == 6 || standard == 7) 0.2627f to 0.0593f else 0.299f to 0.114f
            val kg = 1f - kr - kb
            val limited = range == 2
            val sy = if (limited) 255f / 219f else 1f
            val sc = if (limited) 255f / 224f else 1f
            // Columns: Y, Cb, Cr.
            val matrix = floatArrayOf(
                sy, sy, sy,
                0f, -2f * kb * (1f - kb) / kg * sc, 2f * (1f - kb) * sc,
                2f * (1f - kr) * sc, -2f * kr * (1f - kr) / kg * sc, 0f,
            )
            val offset = floatArrayOf(if (limited) 16f / 255f else 0f, 128f / 255f, 128f / 255f)
            return matrix to offset
        }

        companion object {
            /** [FRAGMENT] sampling the camera's Y'CbCr (GL_EXT_YUV_target) and converting it with [yuvToRgb]. */
            val YUV_FRAGMENT = FRAGMENT
                .replace(
                    "#extension GL_OES_EGL_image_external_essl3 : require",
                    "#extension GL_EXT_YUV_target : require",
                )
                .replace(
                    "uniform samplerExternalOES uCamera;",
                    "uniform __samplerExternal2DY2YEXT uCamera;\nuniform mat3 uYuvToRgb;\nuniform vec3 uYuvOffset;",
                )
                .replace(
                    "clamp(texture(uCamera, st).rgb, 0.0, 1.0)",
                    "clamp(uYuvToRgb * (texture(uCamera, st).rgb - uYuvOffset), 0.0, 1.0)",
                )
                .also { check(it.contains("uYuvToRgb *")) }

            /** Plain sRGB -> Rec.2020 primaries, GL column-major. */
            val SRGB_TO_REC2020 = floatArrayOf(
                0.6274040f, 0.0690970f, 0.0163916f,
                0.3292820f, 0.9195400f, 0.0880132f,
                0.0433136f, 0.0113612f, 0.8955950f,
            )

            // Output row 0 (window y = 0) is the top of the upright image, as for decoded raws.
            const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUp; // upright image coordinates, y = 0 at the top
void main() {
    vUp = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""
            const val FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uCamera;
uniform mat4 uTexMatrix;
uniform int uRotation; // clockwise quarter turns from buffer to upright
uniform float uGamma;  // <= 0: sRGB curve
uniform float uScale;
uniform float uMirror; // 1: show a mirror image
uniform mat3 uColor;   // linear preview RGB -> Rec.2020
in vec2 vUp;
out vec4 fragColor;

void main() {
    float u = mix(vUp.x, 1.0 - vUp.x, uMirror);
    float v = vUp.y;
    // Buffer position (y = 0 at the buffer's top) that lands at (u, v) after rotating clockwise.
    vec2 b;
    if (uRotation == 1) b = vec2(v, 1.0 - u);
    else if (uRotation == 2) b = vec2(1.0 - u, 1.0 - v);
    else if (uRotation == 3) b = vec2(1.0 - v, u);
    else b = vec2(u, v);
    // SurfaceTexture coordinates have t = 0 at the bottom.
    vec2 st = (uTexMatrix * vec4(b.x, 1.0 - b.y, 0.0, 1.0)).xy;
    vec3 c = clamp(texture(uCamera, st).rgb, 0.0, 1.0);
    vec3 lin = uGamma > 0.0
        ? pow(c, vec3(uGamma))
        : mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
    fragColor = vec4(uColor * lin * uScale, 1.0);
}
"""
        }
    }

    /** Draws one of [COPY], [BOOST], [BOX_DOWN] over the whole target (row 0 = top) from one source texture. */
    private class PassProgram(fragment: String, private val trilinear: Boolean) {
        private val id = GlUtil.createProgram(LinearizeProgram.VERTEX, fragment)
        private val uSource = GLES30.glGetUniformLocation(id, "uSource")
        private val uSourceTexel = GLES30.glGetUniformLocation(id, "uSourceTexel")

        fun draw(quad: FullscreenQuad, texture: Int, sourceWidth: Int, sourceHeight: Int) {
            GLES30.glUseProgram(id)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            if (trilinear) {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            }
            GLES30.glUniform1i(uSource, 0)
            GLES30.glUniform2f(uSourceTexel, 1f / sourceWidth, 1f / sourceHeight)
            quad.draw()
            // The develop pass samples level 0 only.
            if (trilinear) GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        }

        companion object {
            /** Trilinear from the source's mipmaps. */
            const val COPY = """#version 300 es
precision highp float;
uniform sampler2D uSource;
in vec2 vUp;
out vec4 fragColor;
void main() {
    fragColor = vec4(texture(uSource, vUp).rgb, 1.0);
}
"""
            /** Same-size copy with the shader's bloomBoost (and the native downsample's). */
            const val BOOST = """#version 300 es
precision highp float;
uniform sampler2D uSource;
in vec2 vUp;
out vec4 fragColor;
void main() {
    vec3 c = max(texture(uSource, vUp).rgb, 0.0);
    float t = clamp((max(c.r, max(c.g, c.b)) - 0.6) / 0.4, 0.0, 1.25);
    fragColor = vec4(c * (1.0 + 6.0 * t * t), 1.0);
}
"""
            /** 8x box downscale. */
            const val BOX_DOWN = """#version 300 es
precision highp float;
uniform sampler2D uSource;
uniform vec2 uSourceTexel;
in vec2 vUp;
out vec4 fragColor;
void main() {
    // 8x box: 4x4 bilinear taps, each the mean of 2x2 source texels.
    vec3 s = vec3(0.0);
    for (int j = 0; j < 4; ++j) {
        for (int i = 0; i < 4; ++i) {
            s += texture(uSource, vUp + vec2(float(2 * i - 3), float(2 * j - 3)) * uSourceTexel).rgb;
        }
    }
    fragColor = vec4(s * 0.0625, 1.0);
}
"""
            /** Same-size pass: BloomTail's kernel (keep the two in step), edges clamped by the sampler. */
            const val TAIL_SCATTER = """#version 300 es
precision highp float;
uniform sampler2D uSource;
uniform vec2 uSourceTexel;
in vec2 vUp;
out vec4 fragColor;
void main() {
    vec3 s = vec3(0.0);
    float ws = 0.0;
    for (int j = -6; j <= 6; ++j) {
        for (int i = -6; i <= 6; ++i) {
            float r2 = float(i * i + j * j);
            float rim = 1.0 - r2 / 49.0;
            if (rim <= 0.0) continue;
            float w = pow(1.0 + sqrt(r2) / 0.6, -2.2) * rim * rim;
            s += w * texture(uSource, vUp + vec2(i, j) * uSourceTexel).rgb;
            ws += w;
        }
    }
    fragColor = vec4(s / ws, 1.0);
}
"""
        }
    }

    companion object {
        const val BASE_MAX_DIMENSION = 512
    }
}
