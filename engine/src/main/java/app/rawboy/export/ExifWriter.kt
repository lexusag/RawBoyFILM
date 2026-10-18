package app.rawboy.export

import androidx.exifinterface.media.ExifInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * The EXIF fields RawBoy keeps in exports (the tags [Exporter] copies into JPEGs with ExifInterface), read from the
 * source file. Unknown numbers are NaN, unknown text is empty. Same fields as the web's and iOS's PhotoMetadata.
 */
data class ExifMetadata(
    val make: String = "",
    val model: String = "",
    val lensMake: String = "",
    val lensModel: String = "",
    val artist: String = "",
    val copyright: String = "",
    val dateTimeOriginal: String = "",
    val subSecTimeOriginal: String = "",
    val offsetTimeOriginal: String = "",
    val exposureTime: Double = Double.NaN,
    val fNumber: Double = Double.NaN,
    val exposureBias: Double = Double.NaN,
    val focalLength: Double = Double.NaN,
    val focalLength35mm: Double = Double.NaN,
    val iso: Double = Double.NaN,
    val gps: Gps? = null,
) {
    /** [latitude]/[longitude] as degrees, minutes, seconds; [altitude] in metres (NaN if unknown). */
    data class Gps(
        val latitude: DoubleArray,
        val latitudeRef: String,
        val longitude: DoubleArray,
        val longitudeRef: String,
        val altitude: Double,
        val belowSeaLevel: Boolean,
    )

    companion object {
        fun from(exif: ExifInterface): ExifMetadata {
            fun text(tag: String) = exif.getAttribute(tag).orEmpty()
            fun number(tag: String) = exif.getAttributeDouble(tag, Double.NaN)
            fun dms(degrees: Double): DoubleArray {
                val a = abs(degrees)
                val d = floor(a)
                val m = floor((a - d) * 60)
                return doubleArrayOf(d, m, ((a - d) * 60 - m) * 60)
            }
            val gps = exif.latLong?.let { (lat, lon) ->
                val alt = exif.getAltitude(Double.NaN)
                Gps(dms(lat), if (lat < 0) "S" else "N", dms(lon), if (lon < 0) "W" else "E", abs(alt), alt < 0)
            }
            return ExifMetadata(
                make = text(ExifInterface.TAG_MAKE),
                model = text(ExifInterface.TAG_MODEL),
                lensMake = text(ExifInterface.TAG_LENS_MAKE),
                lensModel = text(ExifInterface.TAG_LENS_MODEL),
                artist = text(ExifInterface.TAG_ARTIST),
                copyright = text(ExifInterface.TAG_COPYRIGHT),
                dateTimeOriginal = text(ExifInterface.TAG_DATETIME_ORIGINAL),
                subSecTimeOriginal = text(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
                offsetTimeOriginal = text(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                exposureTime = number(ExifInterface.TAG_EXPOSURE_TIME),
                fNumber = number(ExifInterface.TAG_F_NUMBER),
                exposureBias = number(ExifInterface.TAG_EXPOSURE_BIAS_VALUE),
                focalLength = number(ExifInterface.TAG_FOCAL_LENGTH),
                focalLength35mm = number(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM),
                iso = number(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
                gps = gps,
            )
        }
    }
}

/**
 * Lays out the IFDs of exported TIFFs: a port of the web build's ExifWriter.ts (`tiffIfds`). IFD0 holds the image's
 * own tags plus Make, Model, Artist and Copyright; the Exif and GPS IFDs follow, little endian.
 */
internal object ExifWriter {
    const val ASCII: Short = 2
    const val SHORT: Short = 3
    const val LONG: Short = 4
    const val RATIONAL: Short = 5
    private const val SRATIONAL: Short = 10
    private const val UNDEFINED: Short = 7

    private fun typeSize(type: Short) = when (type) {
        SHORT -> 2
        LONG -> 4
        RATIONAL, SRATIONAL -> 8
        else -> 1
    }

    /** One IFD entry; [bytes] is the value, little endian. */
    class Entry(val tag: Int, val type: Short, val count: Int, var bytes: ByteArray)

    private fun le(size: Int, fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

    fun ascii(tag: Int, s: String): Entry {
        val bytes = (s + "\u0000").toByteArray(Charsets.UTF_8)
        return Entry(tag, ASCII, bytes.size, bytes)
    }
    fun short(tag: Int, n: Int) = Entry(tag, SHORT, 1, le(2) { putShort(n.toShort()) })
    fun shorts(tag: Int, values: IntArray) = Entry(tag, SHORT, values.size, le(2 * values.size) { values.forEach { putShort(it.toShort()) } })
    fun long(tag: Int, n: Int) = Entry(tag, LONG, 1, le(4) { putInt(n) })
    fun longs(tag: Int, values: IntArray) = Entry(tag, LONG, values.size, le(4 * values.size) { values.forEach { putInt(it) } })
    private fun undefined(tag: Int, bytes: ByteArray) = Entry(tag, UNDEFINED, bytes.size, bytes)

    /** Unsigned rationals (numerator, denominator). */
    fun rationals(tag: Int, vararg values: Pair<Long, Long>) =
        Entry(tag, RATIONAL, values.size, le(8 * values.size) { values.forEach { putInt(it.first.toInt()); putInt(it.second.toInt()) } })
    private fun srational(tag: Int, n: Int, d: Int) = Entry(tag, SRATIONAL, 1, le(8) { putInt(n); putInt(d) })

    /** A positive number as a rational with [den] as denominator (rounded), clamped to 32 bits. */
    private fun rational(x: Double, den: Long): Pair<Long, Long> = min(0xffffffffL, (x * den).roundToLong()) to den

    /** Exposure time as photographers write it: 1/250 exactly, longer times in tenths of a second. */
    private fun exposureRational(t: Double): Pair<Long, Long> {
        if (t < 1) {
            val inv = 1 / t
            if (abs(inv - Math.round(inv)) < 0.01 * inv) return 1L to Math.round(inv)
            return rational(t, 1_000_000)
        }
        return rational(t, 10)
    }

    private fun known(x: Double) = x.isFinite() && x > 0

    // Tags (TIFF 6 / EXIF 2.32 / GPS).
    private const val MAKE = 0x010f
    private const val MODEL = 0x0110
    private const val ORIENTATION = 0x0112
    private const val SOFTWARE = 0x0131
    private const val ARTIST = 0x013b
    private const val COPYRIGHT = 0x8298
    private const val EXIF_IFD = 0x8769
    private const val GPS_IFD = 0x8825

    private fun dms(v: DoubleArray) = arrayOf(rational(v[0], 1), rational(v[1], 1), rational(v[2], 10000))

    private fun ifds(m: ExifMetadata): Triple<List<Entry>, List<Entry>, List<Entry>> {
        val ifd0 = ArrayList<Entry>()
        if (m.make.isNotBlank()) ifd0 += ascii(MAKE, m.make.trim())
        if (m.model.isNotBlank()) ifd0 += ascii(MODEL, m.model.trim())
        ifd0 += short(ORIENTATION, 1) // the exported pixels are already upright
        ifd0 += ascii(SOFTWARE, "RawBoy")
        if (m.artist.isNotBlank()) ifd0 += ascii(ARTIST, m.artist.trim())
        if (m.copyright.isNotBlank()) ifd0 += ascii(COPYRIGHT, m.copyright.trim())

        val exif = ArrayList<Entry>()
        if (known(m.exposureTime)) exif += rationals(0x829a, exposureRational(m.exposureTime))
        if (known(m.fNumber)) exif += rationals(0x829d, rational(m.fNumber, 10))
        if (known(m.iso)) exif += short(0x8827, min(65535L, Math.round(m.iso)).toInt())
        exif += undefined(0x9000, "0232".toByteArray(Charsets.US_ASCII))
        if (Regex("""^\d{4}:\d\d:\d\d \d\d:\d\d:\d\d$""").matches(m.dateTimeOriginal)) exif += ascii(0x9003, m.dateTimeOriginal)
        if (Regex("""^[+-]\d\d:\d\d$""").matches(m.offsetTimeOriginal)) exif += ascii(0x9011, m.offsetTimeOriginal)
        if (m.exposureBias.isFinite()) exif += srational(0x9204, Math.round(m.exposureBias * 100).toInt(), 100)
        if (known(m.focalLength)) exif += rationals(0x920a, rational(m.focalLength, 100))
        if (Regex("""^\d+$""").matches(m.subSecTimeOriginal)) exif += ascii(0x9291, m.subSecTimeOriginal)
        exif += short(0xa001, 1) // sRGB
        if (known(m.focalLength35mm)) exif += short(0xa405, min(65535L, Math.round(m.focalLength35mm)).toInt())
        if (m.lensMake.isNotBlank()) exif += ascii(0xa433, m.lensMake.trim())
        if (m.lensModel.isNotBlank()) exif += ascii(0xa434, m.lensModel.trim())

        val gps = ArrayList<Entry>()
        m.gps?.let { g ->
            gps += undefined(0, byteArrayOf(2, 3, 0, 0))
            gps += ascii(1, g.latitudeRef)
            gps += rationals(2, *dms(g.latitude))
            gps += ascii(3, g.longitudeRef)
            gps += rationals(4, *dms(g.longitude))
            if (g.altitude.isFinite()) {
                gps += undefined(5, byteArrayOf(if (g.belowSeaLevel) 1 else 0))
                gps += rationals(6, rational(g.altitude, 100))
            }
        }
        return Triple(ifd0, exif, gps)
    }

    private fun valueSize(e: Entry) = typeSize(e.type) * e.count

    /** Bytes an IFD takes: count, entries, next-IFD offset, then values that don't fit in 4 bytes (word aligned). */
    private fun ifdSize(entries: List<Entry>) =
        2 + entries.size * 12 + 4 + entries.sumOf { if (valueSize(it) > 4) (valueSize(it) + 1) and 1.inv() else 0 }

    /** Writes [entries] (sorted by tag) at [offset] of the TIFF block that starts at [base] in [b]. */
    private fun writeIfd(b: ByteBuffer, base: Int, offset: Int, entries: List<Entry>) {
        val sorted = entries.sortedBy { it.tag }
        var p = base + offset
        var data = offset + 2 + sorted.size * 12 + 4
        b.putShort(p, sorted.size.toShort())
        p += 2
        for (e in sorted) {
            b.putShort(p, e.tag.toShort())
            b.putShort(p + 2, e.type)
            b.putInt(p + 4, e.count)
            if (valueSize(e) <= 4) {
                b.position(p + 8); b.put(e.bytes)
            } else {
                b.putInt(p + 8, data)
                b.position(base + data); b.put(e.bytes)
                data += (valueSize(e) + 1) and 1.inv()
            }
            p += 12
        }
        b.putInt(p, 0) // no next IFD
    }

    /**
     * A TIFF block's layout: header, IFD0 with [own] entries plus [m]'s (own tags win), then the Exif and GPS IFDs.
     * [size] doesn't depend on the entries' values, only on their types and counts.
     */
    class TiffIfds(val size: Int, val write: (b: ByteBuffer, base: Int) -> Unit)

    fun tiffIfds(m: ExifMetadata?, own: List<Entry>): TiffIfds {
        val (meta, exif, gps) = m?.let(::ifds) ?: Triple(emptyList(), emptyList(), emptyList())
        val ifd0 = ArrayList(own)
        meta.filterTo(ifd0) { e -> own.none { it.tag == e.tag } }
        // Pointers to the sub-IFDs are LONG entries in IFD0; their values are set once offsets are known.
        val exifPointer = if (exif.isNotEmpty()) long(EXIF_IFD, 0).also { ifd0 += it } else null
        val gpsPointer = if (gps.isNotEmpty()) long(GPS_IFD, 0).also { ifd0 += it } else null
        val exifOffset = 8 + ifdSize(ifd0)
        val gpsOffset = exifOffset + if (exif.isNotEmpty()) ifdSize(exif) else 0
        exifPointer?.bytes = long(EXIF_IFD, exifOffset).bytes
        gpsPointer?.bytes = long(GPS_IFD, gpsOffset).bytes
        val size = gpsOffset + if (gps.isNotEmpty()) ifdSize(gps) else 0
        return TiffIfds(size) { b, base ->
            b.order(ByteOrder.LITTLE_ENDIAN)
            b.put(base, 'I'.code.toByte()).put(base + 1, 'I'.code.toByte()) // little endian
            b.putShort(base + 2, 42)
            b.putInt(base + 4, 8)
            writeIfd(b, base, 8, ifd0)
            if (exif.isNotEmpty()) writeIfd(b, base, exifOffset, exif)
            if (gps.isNotEmpty()) writeIfd(b, base, gpsOffset, gps)
        }
    }
}
