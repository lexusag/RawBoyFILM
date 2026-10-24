package app.rawboy.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** IFD0 shooting tags as Android's DngCreator writes them (no Exif IFD). */
class TiffEpTagsTest {
    /** A little-endian TIFF whose IFD0 holds a Pixel 9 Pro DNG's shooting tags, values after the IFD. */
    private fun dngLike(order: ByteOrder = ByteOrder.LITTLE_ENDIAN): ByteArray {
        val b = ByteBuffer.allocate(256).order(order)
        b.put(if (order == ByteOrder.LITTLE_ENDIAN) "II".toByteArray() else "MM".toByteArray()).putShort(42).putInt(8)
        val entries = 6
        var data = 8 + 2 + entries * 12 + 4
        b.putShort(entries.toShort())
        fun entry(tag: Int, type: Int, count: Int, inline: (ByteBuffer) -> Unit) {
            b.putShort(tag.toShort()).putShort(type.toShort()).putInt(count)
            val p = b.position()
            inline(b)
            b.position(p + 4)
        }
        fun rational(tag: Int, type: Int, num: Int, den: Int) {
            entry(tag, type, 1) { it.putInt(data) }
            b.putInt(data, num).putInt(data + 4, den)
            data += 8
        }
        rational(0x829a, 5, 26040241, 1_000_000_000)
        rational(0x829d, 5, 42, 25)
        entry(0x8827, 3, 1) { it.putShort(335) }
        val date = "2026:10:22 17:11:48\u0000".toByteArray()
        entry(0x9003, 2, date.size) { it.putInt(data) }
        date.forEachIndexed { i, c -> b.put(data + i, c) }
        data += date.size
        rational(0x9204, 10, -33, 100)
        rational(0x920a, 5, 69, 10)
        b.putInt(0)
        return b.array().copyOf(data)
    }

    private fun reader(bytes: ByteArray) = { offset: Long, length: Int ->
        if (offset >= bytes.size) null else bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))
    }

    @Test
    fun readsDngCreatorTags() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val t = TiffEpTags.read(reader(dngLike(order)))!!
            assertEquals(26040241L to 1_000_000_000L, t.exposureTime)
            assertEquals(42L to 25L, t.fNumber)
            assertEquals(69L to 10L, t.focalLength)
            assertEquals(-33L to 100L, t.exposureBias)
            assertEquals(335, t.iso)
            assertEquals("2026:10:22 17:11:48", t.dateTimeOriginal)
        }
    }

    @Test
    fun fillsOnlyUnknownFields() {
        val t = TiffEpTags.read(reader(dngLike()))!!
        val m = t.fill(ExifMetadata(make = "Google", fNumber = 2.8))
        assertEquals(2.8, m.fNumber, 0.0)
        assertEquals(0.026040241, m.exposureTime, 1e-12)
        assertEquals(6.9, m.focalLength, 1e-12)
        assertEquals(-0.33, m.exposureBias, 1e-12)
        assertEquals(335.0, m.iso, 0.0)
        assertEquals("2026:10:22 17:11:48", m.dateTimeOriginal)
        assertEquals("Google", m.make)
    }

    @Test
    fun notATiff() {
        assertNull(TiffEpTags.read(reader(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0, 0, 0, 0, 0, 0))))
        assertNull(TiffEpTags.read(reader(ByteArray(4))))
    }
}
