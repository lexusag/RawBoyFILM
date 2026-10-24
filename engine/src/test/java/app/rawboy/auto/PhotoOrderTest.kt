package app.rawboy.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset

/** Auto's batch order: by shoot date (undated photos last, by name) or by name, and the CR3 date reader. */
class PhotoOrderTest {
    private data class P(val name: String, val date: String?)

    private val photos = listOf(
        P("IMG_10.CR3", "2026:10:01 12:00:00"),
        P("img_9.CR3", "2026:10:01 12:00:05"),
        P("DSCF0001.RAF", null),
        P("IMG_2.CR3", "2026:09:30 08:00:00"),
        P("A.dng", null),
    )

    @Test
    fun byDateThenUndatedByName() {
        val order = photos.sortedWith(PhotoOrder.comparator(PhotoSort.DATE, P::date, P::name)).map { it.name }
        assertEquals(listOf("IMG_2.CR3", "IMG_10.CR3", "img_9.CR3", "A.dng", "DSCF0001.RAF"), order)
    }

    @Test
    fun byNameWithNumbersByValue() {
        val order = photos.sortedWith(PhotoOrder.comparator(PhotoSort.NAME, P::date, P::name)).map { it.name }
        assertEquals(listOf("A.dng", "DSCF0001.RAF", "IMG_2.CR3", "img_9.CR3", "IMG_10.CR3"), order)
    }

    @Test
    fun validDates() {
        assertEquals("2026:10:01 12:00:00", PhotoOrder.validDate("2026:10:01 12:00:00\u0000"))
        assertNull(PhotoOrder.validDate("0000:00:00 00:00:00"))
        assertNull(PhotoOrder.validDate("    :  :     :  :  "))
        assertNull(PhotoOrder.validDate(null))
        assertEquals("2026:10:21 16:10:59", PhotoOrder.fileDate(1792599059000, ZoneOffset.UTC))
        assertNull(PhotoOrder.fileDate(0))
    }

    @Test
    fun readsCr3CaptureDate() {
        assertEquals("2026:08:14 19:03:27", CaptureDate.cr3Date(cr3(exifDate = "2026:08:14 19:03:27", ifd0Date = "2026:08:15 10:00:00")))
        assertEquals("2026:08:15 10:00:00", CaptureDate.cr3Date(cr3(exifDate = null, ifd0Date = "2026:08:15 10:00:00")))
        assertNull(CaptureDate.cr3Date(ByteArray(64)))
    }

    private fun box(type: String, payload: ByteArray, uuid: String? = null): ByteArray {
        val u = uuid?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray() ?: ByteArray(0)
        val out = ByteArrayOutputStream()
        val size = 8 + u.size + payload.size
        out.write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(u)
        out.write(payload)
        return out.toByteArray()
    }

    /** A little-endian TIFF whose first IFD has one ASCII tag. */
    private fun tiff(tag: Int, text: String): ByteArray {
        val value = (text + "\u0000").toByteArray(Charsets.ISO_8859_1)
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
        fun le32(v: Int) = le16(v) + le16(v ushr 16)
        return "II".toByteArray() + le16(42) + le32(8) + le16(1) +
            le16(tag) + le16(2) + le32(value.size) + le32(8 + 2 + 12 + 4) + le32(0) + value
    }

    private fun cr3(exifDate: String?, ifd0Date: String): ByteArray {
        val canon = box("CMT1", tiff(0x0132, ifd0Date)) + (exifDate?.let { box("CMT2", tiff(0x9003, it)) } ?: ByteArray(0))
        val moov = box("moov", box("uuid", canon, "85c0b687820f11e08111f4ce462b6a48"))
        return box("ftyp", "crx ".toByteArray()) + moov + ByteArray(100)
    }
}
