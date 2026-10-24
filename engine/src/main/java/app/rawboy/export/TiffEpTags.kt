package app.rawboy.export

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The shooting tags a TIFF/EP-style raw keeps in IFD0 instead of an Exif IFD, as Android's DngCreator (RawBoy Camera)
 * writes them. ExifInterface only reads these tags from an Exif IFD, so without this a JPEG developed from such a DNG
 * lost its exposure, aperture, ISO, focal length and date. Rationals stay as written ([num] to [den]); missing ones are null.
 */
data class TiffEpTags(
    val exposureTime: Pair<Long, Long>? = null,
    val fNumber: Pair<Long, Long>? = null,
    val focalLength: Pair<Long, Long>? = null,
    /** Signed. */
    val exposureBias: Pair<Long, Long>? = null,
    val iso: Int? = null,
    val dateTimeOriginal: String? = null,
) {
    /** Fills [m]'s unknown fields from these tags. */
    fun fill(m: ExifMetadata): ExifMetadata {
        fun value(r: Pair<Long, Long>?) = r?.takeIf { it.second != 0L }?.let { it.first.toDouble() / it.second } ?: Double.NaN
        fun or(known: Double, r: Pair<Long, Long>?) = if (known.isFinite()) known else value(r)
        return m.copy(
            exposureTime = or(m.exposureTime, exposureTime),
            fNumber = or(m.fNumber, fNumber),
            focalLength = or(m.focalLength, focalLength),
            exposureBias = or(m.exposureBias, exposureBias),
            iso = if (m.iso.isFinite()) m.iso else iso?.toDouble() ?: Double.NaN,
            dateTimeOriginal = m.dateTimeOriginal.ifBlank { dateTimeOriginal.orEmpty() },
        )
    }

    companion object {
        /**
         * Reads IFD0 of a TIFF-structured file; [read] returns [length] bytes at [offset] (fewer or null past the end).
         * Null when the file isn't a TIFF.
         */
        fun read(read: (offset: Long, length: Int) -> ByteArray?): TiffEpTags? {
            val header = read(0, 8)?.takeIf { it.size == 8 } ?: return null
            val order = when {
                header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
                header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
                else -> return null
            }
            fun buf(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(order)
            val head = buf(header)
            if (head.getShort(2).toInt() != 42) return null
            val ifd = head.getInt(4).toLong() and 0xffffffffL
            val count = read(ifd, 2)?.takeIf { it.size == 2 }?.let { buf(it).getShort(0).toInt() and 0xffff } ?: return null
            val entries = read(ifd + 2, count * 12)?.let(::buf) ?: return null
            var tags = TiffEpTags()
            for (i in 0 until minOf(count, entries.capacity() / 12)) {
                val e = i * 12
                val tag = entries.getShort(e).toInt() and 0xffff
                val type = entries.getShort(e + 2).toInt()
                val n = entries.getInt(e + 4)
                fun valueBytes(size: Int): ByteBuffer? =
                    if (size <= 4) buf(entries.array().copyOfRange(e + 8, e + 12))
                    else read(entries.getInt(e + 8).toLong() and 0xffffffffL, size)?.takeIf { it.size == size }?.let(::buf)
                fun rational(): Pair<Long, Long>? {
                    if (n < 1 || (type != RATIONAL && type != SRATIONAL)) return null
                    val v = valueBytes(8) ?: return null
                    return if (type == RATIONAL) (v.getInt(0).toLong() and 0xffffffffL) to (v.getInt(4).toLong() and 0xffffffffL)
                    else v.getInt(0).toLong() to v.getInt(4).toLong()
                }
                when (tag) {
                    EXPOSURE_TIME -> tags = tags.copy(exposureTime = rational())
                    F_NUMBER -> tags = tags.copy(fNumber = rational())
                    FOCAL_LENGTH -> tags = tags.copy(focalLength = rational())
                    EXPOSURE_BIAS -> tags = tags.copy(exposureBias = rational())
                    ISO -> if (n >= 1) tags = tags.copy(
                        iso = when (type) {
                            SHORT -> valueBytes(2)?.getShort(0)?.toInt()?.and(0xffff)
                            LONG -> valueBytes(4)?.getInt(0)
                            else -> null
                        },
                    )
                    DATE_TIME_ORIGINAL -> if (type == ASCII && n in 1..64) {
                        tags = tags.copy(dateTimeOriginal = valueBytes(n)?.array()?.let { String(it, Charsets.US_ASCII).substringBefore('\u0000').trim() })
                    }
                }
            }
            return tags
        }

        private const val ASCII = 2
        private const val SHORT = 3
        private const val LONG = 4
        private const val RATIONAL = 5
        private const val SRATIONAL = 10

        private const val EXPOSURE_TIME = 0x829a
        private const val F_NUMBER = 0x829d
        private const val ISO = 0x8827
        private const val DATE_TIME_ORIGINAL = 0x9003
        private const val EXPOSURE_BIAS = 0x9204
        private const val FOCAL_LENGTH = 0x920a
    }
}
