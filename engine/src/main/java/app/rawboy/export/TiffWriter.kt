package app.rawboy.export

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Writes an LZW-compressed RGB TIFF (8 or 16 bits per sample, little endian, horizontal differencing
 * predictor). Rows are compressed strip by strip as they arrive, after a gap for the header; the header,
 * which needs the compressed strip sizes, is written into that gap by [finish]. With [metadata] the header
 * carries the photo's EXIF (the tags JPEG exports keep, in IFD0 and its Exif and GPS IFDs).
 */
class TiffWriter(
    private val out: FileChannel,
    private val width: Int,
    private val height: Int,
    private val bitsPerSample: Int,
    private val metadata: ExifMetadata? = null,
) {
    private val bytesPerRow = width.toLong() * 3 * bitsPerSample / 8
    private val rowsPerStrip = (STRIP_TARGET_BYTES / bytesPerRow).toInt().coerceIn(1, height)
    private val stripCount = (height + rowsPerStrip - 1) / rowsPerStrip
    private val stripSizes = IntArray(stripCount)
    private var stripsWritten = 0
    private var rowsWritten = 0
    private val strip: ByteArray
    private var stripFill = 0
    private val lzw = LzwEncoder()

    /** Header size: the IFDs and their values; the strips follow. Doesn't depend on the strip sizes. */
    private val dataOffset: Int

    init {
        require(bitsPerSample == 8 || bitsPerSample == 16)
        require(bytesPerRow * height < Int.MAX_VALUE) { "Image too large for classic TIFF" }
        strip = ByteArray((rowsPerStrip * bytesPerRow).toInt())
        dataOffset = layout(0).size
        out.position(dataOffset.toLong())
    }

    private fun layout(dataOffset: Int): ExifWriter.TiffIfds {
        val offsets = IntArray(stripCount)
        var offset = dataOffset
        for (i in 0 until stripCount) { offsets[i] = offset; offset += stripSizes[i] }
        val own = listOf(
            ExifWriter.long(256, width),                                // ImageWidth
            ExifWriter.long(257, height),                               // ImageLength
            ExifWriter.shorts(258, IntArray(3) { bitsPerSample }),      // BitsPerSample
            ExifWriter.short(259, 5),                                   // Compression: LZW
            ExifWriter.short(262, 2),                                   // Photometric: RGB
            ExifWriter.longs(273, offsets),                             // StripOffsets
            ExifWriter.short(274, 1),                                   // Orientation: top-left
            ExifWriter.short(277, 3),                                   // SamplesPerPixel
            ExifWriter.long(278, rowsPerStrip),                         // RowsPerStrip
            ExifWriter.longs(279, stripSizes),                          // StripByteCounts
            ExifWriter.rationals(282, 300L to 1L),                      // XResolution
            ExifWriter.rationals(283, 300L to 1L),                      // YResolution
            ExifWriter.short(284, 1),                                   // PlanarConfiguration: chunky
            ExifWriter.short(296, 2),                                   // ResolutionUnit: inch
            ExifWriter.ascii(305, "RawBoy"),                            // Software
            ExifWriter.short(317, 2),                                   // Predictor: horizontal differencing
        )
        return ExifWriter.tiffIfds(metadata, own)
    }

    private fun header(): ByteBuffer {
        val tiff = layout(dataOffset)
        check(tiff.size == dataOffset)
        val b = ByteBuffer.allocate(dataOffset).order(ByteOrder.LITTLE_ENDIAN)
        tiff.write(b, 0)
        b.position(0)
        return b
    }

    /**
     * Appends [rows] rows of interleaved RGB unsigned 16-bit samples from [band], converted to the file's
     * depth and differenced from the pixel to the left (the predictor: neighbours are similar, so LZW
     * finds more repeats).
     */
    fun writeRows(band: ShortArray, rows: Int) {
        val n = width * 3
        val s = strip
        for (r in 0 until rows) {
            val row = r * n
            var p = stripFill
            if (bitsPerSample == 16) {
                for (i in 0 until n) {
                    // Only the low 16 bits are written, so the samples' sign extension cancels out.
                    val d = band[row + i].toInt() - (if (i >= 3) band[row + i - 3].toInt() else 0)
                    s[p++] = d.toByte()
                    s[p++] = (d shr 8).toByte()
                }
            } else {
                var pr = 0; var pg = 0; var pb = 0
                for (i in 0 until n step 3) {
                    val r8 = to8(band[row + i]); val g8 = to8(band[row + i + 1]); val b8 = to8(band[row + i + 2])
                    s[p++] = (r8 - pr).toByte(); s[p++] = (g8 - pg).toByte(); s[p++] = (b8 - pb).toByte()
                    pr = r8; pg = g8; pb = b8
                }
            }
            stripFill = p
            rowsWritten++
            if (stripFill == s.size || rowsWritten == height) flushStrip()
        }
    }

    private fun flushStrip() {
        val length = lzw.encode(strip, stripFill)
        val data = ByteBuffer.wrap(lzw.output, 0, length)
        while (data.hasRemaining()) out.write(data)
        stripSizes[stripsWritten++] = length
        stripFill = 0
    }

    fun finish() {
        check(rowsWritten == height) { "TIFF size mismatch: wrote $rowsWritten rows" }
        out.position(0)
        val h = header()
        while (h.hasRemaining()) out.write(h)
        out.force(false)
    }

    private companion object {
        const val STRIP_TARGET_BYTES = 256L * 1024

        fun to8(v: Short): Int = ((v.toInt() and 0xffff) * 255 + 32767) / 65535
    }
}

/**
 * TIFF's LZW (MSB-first codes of 9 to 12 bits, "early change" as libtiff writes it), one strip at a time.
 * The string table is a hash of (prefix code, next byte) → code.
 */
internal class LzwEncoder {
    private val keys = IntArray(1 shl HASH_BITS)
    private val codes = ShortArray(1 shl HASH_BITS)
    /** The last [encode]'s result (its first bytes). */
    var output = ByteArray(0)
        private set
    private var pos = 0
    private var bits = 0
    private var bitCount = 0

    /** Compresses the first [length] bytes of [src] into [output]; returns the compressed length. */
    fun encode(src: ByteArray, length: Int): Int {
        // Worst case: one 12-bit code per input byte, plus clear codes.
        val bound = length + (length shr 1) + (length shr 10) + 16
        if (output.size < bound) output = ByteArray(bound)
        pos = 0
        bits = 0
        bitCount = 0
        var width = 9
        var next = FIRST_CODE
        keys.fill(-1)
        put(CLEAR, width)
        if (length == 0) {
            put(EOI, width)
            return flush()
        }
        val mask = (1 shl HASH_BITS) - 1
        var w = src[0].toInt() and 0xff
        for (i in 1 until length) {
            val k = src[i].toInt() and 0xff
            val key = (w shl 8) or k
            var h = (key * HASH_MULTIPLIER) ushr (32 - HASH_BITS)
            var found = -1
            while (keys[h] != -1) {
                if (keys[h] == key) { found = codes[h].toInt(); break }
                h = (h + 1) and mask
            }
            if (found >= 0) {
                w = found
                continue
            }
            put(w, width)
            keys[h] = key
            codes[h] = next++.toShort()
            if (next == TABLE_FULL) {
                put(CLEAR, width)
                keys.fill(-1)
                next = FIRST_CODE
                width = 9
            } else if (next > (1 shl width) - 1) {
                width++
            }
            w = k
        }
        put(w, width)
        // The decoder adds one more entry on reading that code, which may widen the EOI code.
        next++
        if (next == TABLE_FULL) {
            put(CLEAR, width)
            width = 9
        } else if (next > (1 shl width) - 1) {
            width++
        }
        put(EOI, width)
        return flush()
    }

    private fun put(code: Int, width: Int) {
        bits = (bits shl width) or code
        bitCount += width
        while (bitCount >= 8) {
            bitCount -= 8
            output[pos++] = (bits ushr bitCount).toByte()
        }
        bits = bits and ((1 shl bitCount) - 1)
    }

    private fun flush(): Int {
        if (bitCount > 0) output[pos++] = (bits shl (8 - bitCount)).toByte()
        return pos
    }

    private companion object {
        const val CLEAR = 256
        const val EOI = 257
        const val FIRST_CODE = 258
        /** libtiff resets the table when the next free code would be 4094. */
        const val TABLE_FULL = 4094
        const val HASH_BITS = 13
        const val HASH_MULTIPLIER = -0x61c8864f // 0x9e3779b1
    }
}
