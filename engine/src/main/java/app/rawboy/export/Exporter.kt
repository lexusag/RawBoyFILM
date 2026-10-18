package app.rawboy.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import app.rawboy.gl.OffscreenDeveloper
import app.rawboy.gl.PixelRect
import app.rawboy.gl.ShaderParams
import app.rawboy.raw.RawImage
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A GL context for exports, kept between exports made on one thread (the camera develops shot after
 * shot): saves creating the context and compiling the develop shader each time. Create, use and close
 * it on the same thread.
 */
class ExportGpu(private val lowPriority: Boolean = false) : AutoCloseable {
    private var developer: OffscreenDeveloper? = null

    internal fun developer(imageWidth: Int, imageHeight: Int): OffscreenDeveloper =
        (developer ?: OffscreenDeveloper(imageWidth, imageHeight, lowPriority).also { developer = it })
            .also { it.setImageSize(imageWidth, imageHeight) }

    override fun close() {
        developer?.close()
        developer = null
    }
}

/**
 * Develops the full-resolution image on the GPU in tiles and writes JPEG or TIFF
 * to Pictures/<folder>/ (see [OutputNaming]). Blocking: call from a background thread and don't
 * suspend inside, because the EGL context is bound to the calling thread.
 */
class Exporter(private val context: Context) {
    private var gpu: ExportGpu? = null

    /**
     * Returns the display name of the written file. [gpu] reuses a GL context across exports made on
     * the same thread (otherwise each export creates and compiles its own).
     */
    fun export(
        image: RawImage,
        source: Uri?,
        sourceName: String,
        params: ShaderParams,
        settings: ExportSettings,
        naming: OutputNaming = OutputNaming(),
        gpu: ExportGpu? = null,
        onProgress: (Float) -> Unit,
    ): String {
        this.gpu = gpu
        val resolver = context.contentResolver
        val (uri, name) = MediaStoreOutput.create(resolver, MediaStoreOutput.baseNameOf(sourceName), settings.format, naming)
        val size = BandDownscaler.outputSize(params.lens.outputWidth, params.lens.outputHeight, settings.maxLongSide)
        try {
            when (settings.format) {
                ExportFormat.JPEG -> if (settings.jpegEncoder == JpegEncoder.JPEGLI) {
                    writeJpegli(image, uri, params, size, settings.jpegQuality, onProgress)
                } else {
                    writeJpeg(image, uri, params, size, settings.jpegQuality, onProgress)
                }
                ExportFormat.TIFF -> writeTiff(image, uri, params, size, settings.tiffBits, source?.let(::readExif), onProgress)
            }
            val t = System.nanoTime()
            if (settings.format == ExportFormat.JPEG && source != null) copyExif(source, uri)
            MediaStoreOutput.publish(resolver, uri)
            Log.d(TAG, "EXIF + publish ${(System.nanoTime() - t) / 1_000_000} ms")
            return name
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    private fun writeJpeg(image: RawImage, uri: Uri, params: ShaderParams, size: Pair<Int, Int>, quality: Int, onProgress: (Float) -> Unit) {
        val w = size.first
        val bitmap = Bitmap.createBitmap(w, size.second, Bitmap.Config.ARGB_8888)
        try {
            var argb = IntArray(0)
            renderOutput(image, params, size, onProgress) { y, rows, band ->
                val n = w * rows
                if (argb.size < n) argb = IntArray(n)
                for (i in 0 until n) {
                    argb[i] = (0xff shl 24) or
                        (to8(band[i * 3]) shl 16) or (to8(band[i * 3 + 1]) shl 8) or to8(band[i * 3 + 2])
                }
                bitmap.setPixels(argb, 0, w, 0, y, w, rows)
            }
            context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw IOException("JPEG encoding failed")
            } ?: throw IOException("Cannot write $uri")
        } finally {
            bitmap.recycle()
        }
    }

    /** jpegli streams each band as it is developed, at full 16-bit precision, so no full-size bitmap is held. */
    private fun writeJpegli(image: RawImage, uri: Uri, params: ShaderParams, size: Pair<Int, Int>, quality: Int, onProgress: (Float) -> Unit) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "w") ?: throw IOException("Cannot write $uri")
        pfd.use {
            val handle = JpegNative.begin(it.fd, size.first, size.second, quality)
            try {
                renderOutput(image, params, size, onProgress) { _, rows, band -> JpegNative.writeRows(handle, band, rows) }
            } catch (t: Throwable) {
                JpegNative.abort(handle)
                throw t
            }
            JpegNative.finish(handle)
        }
    }

    private fun writeTiff(
        image: RawImage,
        uri: Uri,
        params: ShaderParams,
        size: Pair<Int, Int>,
        bits: Int,
        exif: ExifMetadata?,
        onProgress: (Float) -> Unit,
    ) {
        // Read-write: the header goes in last, in front of the compressed strips.
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("Cannot write $uri")
        pfd.use {
            // The stream doesn't own the descriptor; closing pfd closes it.
            val writer = TiffWriter(FileOutputStream(it.fileDescriptor).channel, size.first, size.second, bits, exif)
            renderOutput(image, params, size, onProgress) { _, rows, band -> writer.writeRows(band, rows) }
            writer.finish()
        }
    }

    /**
     * Renders the image at [size] (the full output size, or smaller: then the full-resolution bands are
     * area-downscaled as they stream, and the grain is sized to the smaller output). Same callback as [renderBands].
     */
    private fun renderOutput(
        image: RawImage,
        params: ShaderParams,
        size: Pair<Int, Int>,
        onProgress: (Float) -> Unit,
        consume: (y: Int, rows: Int, band: ShortArray) -> Unit,
    ) {
        val w = params.lens.outputWidth
        val h = params.lens.outputHeight
        if (size.first == w && size.second == h) return renderBands(image, params, 1f, onProgress, consume)
        var encodeNs = 0L
        var scaleNs = 0L
        BandDownscaler(w, h, size.first, size.second, TILE_HEIGHT) { y, rows, band ->
            val t = System.nanoTime()
            consume(y, rows, band)
            encodeNs += System.nanoTime() - t
        }.use { scaler ->
            // A grain cell at least one output pixel, as a full-size render of the smaller image would have.
            val minGrain = maxOf(w, h).toFloat() / maxOf(size.first, size.second)
            renderBands(image, params, minGrain, onProgress) { _, rows, band ->
                val t = System.nanoTime()
                scaler.push(rows, band)
                scaleNs += System.nanoTime() - t
            }
            val t = System.nanoTime()
            scaler.finish()
            scaleNs += System.nanoTime() - t
        }
        Log.d(TAG, "export to ${size.first}x${size.second}: downscale ${(scaleNs - encodeNs) / 1_000_000} ms, encode ${encodeNs / 1_000_000} ms")
    }

    /**
     * Renders the image top to bottom in bands of [TILE_HEIGHT] rows. [minGrainPx]: smallest grain cell
     * in full-resolution pixels (see [renderOutput]).
     * [consume] receives (firstRow, rowCount, RGB samples as unsigned 16-bit in a ShortArray).
     */
    private fun renderBands(
        image: RawImage,
        params: ShaderParams,
        minGrainPx: Float,
        onProgress: (Float) -> Unit,
        consume: (y: Int, rows: Int, band: ShortArray) -> Unit,
    ) {
        // Output size: rotation by 90 degrees swaps width and height.
        val w = params.lens.outputWidth
        val h = params.lens.outputHeight
        val tilesX = (w + TILE_WIDTH - 1) / TILE_WIDTH
        val tilesY = (h + TILE_HEIGHT - 1) / TILE_HEIGHT
        var input = ByteBuffer.allocateDirect((TILE_WIDTH + 2 * HALO) * (TILE_HEIGHT + 2 * HALO) * RawImage.BYTES_PER_PIXEL)
            .order(ByteOrder.nativeOrder())
        val output = ByteBuffer.allocateDirect(TILE_WIDTH * TILE_HEIGHT * 16).order(ByteOrder.nativeOrder())
        val band = ShortArray(w * TILE_HEIGHT * 3)
        var done = 0

        // The shader's image size is the source image's (display orientation), not the output's:
        // whole-image textures (high-pass base, bloom, skin mask) are sampled in source pixels.
        val shared = gpu?.developer(image.width, image.height)
        val developer = shared ?: OffscreenDeveloper(image.width, image.height)
        developer.minGrainPx = minGrainPx
        var renderNs = 0L
        var consumeNs = 0L
        try {
            for (ty in 0 until tilesY) {
                val y = ty * TILE_HEIGHT
                val rows = minOf(TILE_HEIGHT, h - y)
                for (tx in 0 until tilesX) {
                    val x = tx * TILE_WIDTH
                    val cols = minOf(TILE_WIDTH, w - x)
                    // Pad the tile so the denoise filter sees real neighbours at tile edges.
                    // Source pixels this tile needs: lens geometry plus the denoise neighbourhood.
                    val out = PixelRect(x, y, cols, rows)
                    val src = params.lens.sourceBounds(out, params.spatialMargin)
                    val bytes = src.width * src.height * RawImage.BYTES_PER_PIXEL
                    if (input.capacity() < bytes) input = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
                    val t0 = System.nanoTime()
                    image.copyTile(src.x, src.y, src.width, src.height, input)
                    developer.render(params, input, src, out, output)
                    renderNs += System.nanoTime() - t0
                    BandNative.packTile(output, developer.bytesPerSample, cols, rows, band, w, x)
                    done++
                    onProgress(done.toFloat() / (tilesX * tilesY))
                }
                val t1 = System.nanoTime()
                consume(y, rows, band)
                consumeNs += System.nanoTime() - t1
            }
        } finally {
            if (shared == null) developer.close()
        }
        Log.d(TAG, "export ${w}x$h: develop ${renderNs / 1_000_000} ms, encode/write ${consumeNs / 1_000_000} ms")
    }

    /** The source's EXIF for TIFFs, which ExifInterface can't write: TiffWriter puts it in the header. Best effort. */
    private fun readExif(source: Uri): ExifMetadata? = runCatching {
        context.contentResolver.openFileDescriptor(source, "r")?.use { pfd -> ExifMetadata.from(ExifInterface(pfd.fileDescriptor)) }
    }.getOrNull()

    private fun copyExif(source: Uri, target: Uri) {
        runCatching {
            val input = context.contentResolver.openFileDescriptor(source, "r")?.use { pfd ->
                ExifInterface(pfd.fileDescriptor).let { exif -> EXIF_TAGS.associateWith { exif.getAttribute(it) } }
            } ?: return
            context.contentResolver.openFileDescriptor(target, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                input.forEach { (tag, value) -> if (value != null) exif.setAttribute(tag, value) }
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "RawBoy")
                exif.setAttribute(ExifInterface.TAG_COLOR_SPACE, ExifInterface.COLOR_SPACE_S_RGB.toString())
                exif.saveAttributes()
            }
        } // Metadata is best effort; the image is already written.
    }

    private companion object {
        const val TAG = "RawBoy"
        const val TILE_WIDTH = 1024
        const val TILE_HEIGHT = 512
        const val HALO = 16 // >= the largest denoise radius (9 px)

        fun to8(v: Short): Int = ((v.toInt() and 0xffff) * 255 + 32767) / 65535

        val EXIF_TAGS = listOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
        )
    }
}
