package app.rawboy.raw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import app.rawboy.auto.analysis.NoiseLevel
import app.rawboy.gl.DetailTile
import app.rawboy.lens.LensInfo
import app.rawboy.retouch.SkinMask
import app.rawboy.gl.PixelRect
import app.rawboy.gl.PreviewImage
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A decoded raw file held in native memory as linear Rec.2020 half floats,
 * white balanced for the as-shot illuminant and oriented for display.
 * JPEGs and HEICs are taken back to scene light by the decoder ([displayReferred]).
 */
class RawImage private constructor(private var handle: Long) : AutoCloseable {

    val width: Int = RawNative.width(handle)
    val height: Int = RawNative.height(handle)
    val description: String = RawNative.describe(handle)

    /** Camera and exposure essentials for the editor's info line. */
    val shotInfo: ShotInfo = RawNative.shotInfo(handle).let {
        ShotInfo(RawNative.camera(handle), it[0], it[1], it[2], it[3], it[4])
    }

    /** ISO speed from the file's metadata, 0 if unknown. */
    val iso: Float = RawNative.iso(handle)

    /** DNG BaselineExposure in EV, already applied to the pixels; NaN if the file has none. */
    val baselineExposure: Float = RawNative.baselineExposure(handle)

    /** Camera maker from the file's metadata, empty if unknown. */
    val make: String = RawNative.make(handle)

    /** A JPEG/HEIC, not a raw: rendered with `uDisplaySource` (see DevelopParams.displaySource). */
    val displayReferred: Boolean = RawNative.displayReferred(handle)

    /** As-shot white chromaticity, CIE 1931 xy. */
    val asShotWhite: DoubleArray = RawNative.asShotWhite(handle)

    /** Lens name and embedded lens corrections. */
    val lens: LensInfo = LensInfo.fromNative(
        RawNative.lensName(handle), width, height, RawNative.lensInfo(handle), RawNative.lensShading(handle),
    )

    /** Downscaled copy; [boostHighlights] makes it a bloom source (see RawNative.downsample). */
    fun createPreview(maxDimension: Int, boostHighlights: Boolean = false): PreviewImage {
        val scale = minOf(1.0, maxDimension.toDouble() / max(width, height))
        val w = max(1, (width * scale).roundToInt())
        val h = max(1, (height * scale).roundToInt())
        val pixels = ByteBuffer.allocateDirect(w * h * BYTES_PER_PIXEL).order(ByteOrder.nativeOrder())
        synchronized(this) {
            check(handle != 0L) { "image closed" }
            RawNative.downsample(handle, w, h, pixels, boostHighlights)
        }
        return PreviewImage(w, h, pixels, width, height)
    }

    fun createTile(rect: PixelRect): DetailTile {
        val pixels = ByteBuffer.allocateDirect(rect.width * rect.height * BYTES_PER_PIXEL)
            .order(ByteOrder.nativeOrder())
        copyTile(rect.x, rect.y, rect.width, rect.height, pixels)
        return DetailTile(rect, pixels)
    }

    /** Copies a full-resolution region as tightly packed RGB half floats. */
    fun copyTile(x: Int, y: Int, w: Int, h: Int, out: ByteBuffer) = synchronized(this) {
        check(handle != 0L) { "image closed" }
        RawNative.copyTile(handle, x, y, w, h, out)
    }

    /** Measures the photo's noise on the full-resolution pixels (~30 ms); [NoiseLevel.NONE] for JPEG/HEIC. */
    fun measureNoise(): NoiseLevel = synchronized(this) {
        check(handle != 0L) { "image closed" }
        RawNative.noiseLevel(handle).let { NoiseLevel(it[0], it[1]) }
    }

    /** Blemish candidates on [mask]: (x, y, radius, score) per spot, most obvious first. */
    fun detectBlemishes(mask: SkinMask): FloatArray = synchronized(this) {
        check(handle != 0L) { "image closed" }
        RawNative.detectBlemishes(handle, mask.data, mask.width, mask.height, mask.faceDiameterPx)
    }

    /** Replaces any earlier healing with healing of exactly [spots]. Previews must be rebuilt afterwards. */
    fun healBlemishes(spots: FloatArray) = synchronized(this) {
        check(handle != 0L) { "image closed" }
        RawNative.healBlemishes(handle, spots)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            RawNative.close(handle)
            handle = 0L
        }
    }

    companion object {
        const val BYTES_PER_PIXEL = 6 // RGB, half float

        /**
         * [extraBlack]: black to take off beyond the file's black level, as a fraction of the white range (LibRaw
         * path only). RawBoy Camera uses it to match the phone's own rendering, see CameraViewModel.EXTRA_BLACK.
         */
        fun open(context: Context, uri: Uri, extraBlack: Float = 0f): RawImage {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Cannot open $uri")
            val handle = pfd.use {
                if (platformDecoded(it.fileDescriptor)) openWithImageDecoder(context, uri, it.fd)
                else RawNative.open(it.fd, extraBlack)
            }
            return RawImage(handle)
        }

        private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1")

        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        /** HEIC (an ISO base media file with a HEIF brand) or PNG: decoded by the platform, not by our native code. */
        private fun platformDecoded(fd: java.io.FileDescriptor): Boolean {
            val head = ByteArray(12)
            val n = try {
                android.system.Os.pread(fd, head, 0, head.size, 0)
            } catch (e: android.system.ErrnoException) {
                0
            }
            if (n < 12) return false
            val heif = String(head, 4, 4, Charsets.US_ASCII) == "ftyp" &&
                String(head, 8, 4, Charsets.US_ASCII) in HEIF_BRANDS
            return heif || head.copyOf(8).contentEquals(PNG_SIGNATURE)
        }

        /**
         * HEIC and PNG through the platform decoder (no HEVC decoder of our own), as linear extended sRGB half floats,
         * colour managed and already upright.
         */
        private fun openWithImageDecoder(context: Context, uri: Uri, fd: Int): Long {
            val linear = ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
            val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.setTargetColorSpace(linear)
            }
            // Normally already RGBA_F16 in the linear space; otherwise drawing converts it.
            val bitmap = if (decoded.config == Bitmap.Config.RGBA_F16 && decoded.colorSpace == linear) decoded else {
                Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.RGBA_F16, true, linear).also {
                    Canvas(it).drawBitmap(decoded, 0f, 0f, null)
                    decoded.recycle()
                }
            }
            try {
                return RawNative.openBitmap(bitmap, fd)
            } finally {
                bitmap.recycle()
            }
        }
    }
}
