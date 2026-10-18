package app.rawboy.gl

import android.graphics.Bitmap
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Develops small whole-photo images (grid and look-strip thumbnails) off screen,
 * from a photo's downscaled preview, so no full-resolution data is needed.
 *
 * The EGL context belongs to one private thread; every render runs there.
 */
class ThumbnailRenderer : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "RawBoy-thumbnails") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var developer: OffscreenDeveloper? = null

    /**
     * Develops [preview] with [params] into a bitmap whose long edge is at most [maxDimension]
     * (and no larger than the preview, since upscaling adds nothing).
     */
    suspend fun render(params: ShaderParams, preview: PreviewImage, maxDimension: Int): Bitmap = withContext(dispatcher) {
        val outW = params.lens.outputWidth
        val outH = params.lens.outputHeight
        val longEdge = minOf(maxDimension, max(preview.width, preview.height))
        val scale = longEdge.toDouble() / max(outW, outH)
        val w = max(1, (outW * scale).roundToInt())
        val h = max(1, (outH * scale).roundToInt())

        val dev = developer ?: OffscreenDeveloper(preview.fullWidth, preview.fullHeight).also { developer = it }
        val output = ByteBuffer.allocateDirect(w * h * 16).order(ByteOrder.nativeOrder())
        dev.renderPreview(params, preview, w, h, output)

        val samples = IntArray(w * h * 4)
        output.position(0)
        if (dev.bytesPerSample == 2) {
            val shorts = output.asShortBuffer()
            for (i in samples.indices) samples[i] = shorts.get(i).toInt() and 0xffff
        } else {
            output.asIntBuffer().get(samples)
        }
        val argb = IntArray(w * h) { i ->
            (0xff shl 24) or (to8(samples[i * 4]) shl 16) or (to8(samples[i * 4 + 1]) shl 8) or to8(samples[i * 4 + 2])
        }
        Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Releases the GL context on its own thread, after any queued renders. */
    override fun close() {
        executor.execute {
            developer?.close()
            developer = null
        }
        executor.shutdown()
    }

    private fun to8(v: Int): Int = (v * 255 + 32767) / 65535
}
