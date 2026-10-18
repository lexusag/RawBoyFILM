package app.rawboy.lut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

/** Parses .cube and HaldCLUT .png files into [LutData], resampled to at most 64^3. */
object LutLoader {
    /** 64^3 is plenty for smooth film looks and keeps the GPU texture at ~3 MB. */
    private const val MAX_SIZE = 64

    /**
     * Synchronized: a HaldCLUT parse briefly needs 12-64 MB of Java heap (direct buffers live
     * there on ART), so parallel loads of several LUTs can run the app out of memory.
     */
    @Synchronized
    fun load(context: Context, id: String): LutData =
        LutRepository.open(context, id).buffered().use { stream ->
            // Sniff the format: document ids from some providers carry no extension.
            stream.mark(8)
            val magic = ByteArray(4)
            val read = stream.read(magic)
            stream.reset()
            val isPng = read == 4 && magic[0] == 0x89.toByte() && magic[1] == 'P'.code.toByte() &&
                magic[2] == 'N'.code.toByte() && magic[3] == 'G'.code.toByte()
            if (isPng) parseHald(id, stream) else parseCube(id, stream)
        }

    /** Adobe/Resolve .cube: LUT_3D_SIZE N, then N^3 "r g b" lines with red varying fastest. */
    private fun parseCube(id: String, stream: InputStream): LutData {
        var size = 0
        var values: FloatArray? = null
        var count = 0
        stream.bufferedReader().forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#') return@forEachLine
            when {
                line[0].isDigit() || line[0] == '-' || line[0] == '.' -> {
                    val v = values ?: throw IOException("LUT data before LUT_3D_SIZE")
                    val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
                    if (parts.size < 3 || count + 3 > v.size) throw IOException("Malformed .cube data")
                    v[count++] = parts[0].toFloat()
                    v[count++] = parts[1].toFloat()
                    v[count++] = parts[2].toFloat()
                }
                line.startsWith("LUT_3D_SIZE") -> {
                    size = line.substringAfter("LUT_3D_SIZE").trim().toInt()
                    if (size !in 2..256) throw IOException("Unsupported LUT size $size")
                    values = FloatArray(size * size * size * 3)
                }
                line.startsWith("LUT_1D_SIZE") -> throw IOException("1D .cube LUTs are not supported")
                // TITLE, DOMAIN_MIN/MAX (assumed 0..1) and other keywords are ignored.
            }
        }
        val v = values ?: throw IOException("Missing LUT_3D_SIZE")
        if (count != v.size) throw IOException("Expected ${v.size / 3} entries, found ${count / 3}")
        return finish(id, size, v)
    }

    /**
     * HaldCLUT: a square image of level^3 pixels per side holding a level^2 cube.
     * Pixel index = r + g*N + b*N^2, row-major.
     */
    private fun parseHald(id: String, stream: InputStream): LutData {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeStream(stream, null, options) ?: throw IOException("Cannot decode HaldCLUT")
        try {
            val w = bitmap.width
            val level = Math.cbrt(w.toDouble()).roundToInt()
            if (bitmap.height != w || level * level * level != w) {
                throw IOException("Not a HaldCLUT image (${w}x${bitmap.height})")
            }
            val n = level * level
            // Keep pixels in native memory: a 256^3 HaldCLUT as floats would need ~200 MB of heap.
            // ARGB_8888 is stored as R, G, B, A bytes.
            val pixels = ByteBuffer.allocateDirect(w * w * 4)
            bitmap.copyPixelsToBuffer(pixels)
            bitmap.recycle()
            return finish(id, n) { i, c -> (pixels.get(i * 4 + c).toInt() and 0xff) / 255f }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun finish(id: String, size: Int, values: FloatArray): LutData =
        finish(id, size) { i, c -> values[i * 3 + c] }

    /** [sample] (node index, channel) reads the source cube, red fastest. */
    private fun finish(id: String, size: Int, sample: (Int, Int) -> Float): LutData {
        val outSize = min(size, MAX_SIZE)
        val out = if (outSize == size) {
            FloatArray(size * size * size * 3) { sample(it / 3, it % 3) }
        } else {
            resample(sample, size, outSize)
        }
        val buffer = ByteBuffer.allocateDirect(out.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(out).position(0)
        return LutData(id, outSize, buffer)
    }

    /** Trilinear resampling of a size^3 cube onto an outSize^3 grid spanning the same 0..1 domain. */
    private fun resample(sample: (Int, Int) -> Float, size: Int, outSize: Int): FloatArray {
        val out = FloatArray(outSize * outSize * outSize * 3)
        val scale = (size - 1).toFloat() / (outSize - 1)
        fun idx(r: Int, g: Int, b: Int) = (b * size + g) * size + r
        var o = 0
        for (b in 0 until outSize) {
            val fb = b * scale
            val b0 = min(fb.toInt(), size - 2)
            val tb = fb - b0
            for (g in 0 until outSize) {
                val fg = g * scale
                val g0 = min(fg.toInt(), size - 2)
                val tg = fg - g0
                for (r in 0 until outSize) {
                    val fr = r * scale
                    val r0 = min(fr.toInt(), size - 2)
                    val tr = fr - r0
                    for (c in 0 until 3) {
                        fun at(dr: Int, dg: Int, db: Int) = sample(idx(r0 + dr, g0 + dg, b0 + db), c)
                        val c00 = at(0, 0, 0) + (at(1, 0, 0) - at(0, 0, 0)) * tr
                        val c10 = at(0, 1, 0) + (at(1, 1, 0) - at(0, 1, 0)) * tr
                        val c01 = at(0, 0, 1) + (at(1, 0, 1) - at(0, 0, 1)) * tr
                        val c11 = at(0, 1, 1) + (at(1, 1, 1) - at(0, 1, 1)) * tr
                        val c0 = c00 + (c10 - c00) * tg
                        val c1 = c01 + (c11 - c01) * tg
                        out[o++] = c0 + (c1 - c0) * tb
                    }
                }
            }
        }
        return out
    }
}
