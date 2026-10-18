package app.rawboy.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** LZW TIFF round trips through a decoder with libtiff's code-width rule, as RawBoy Web's tiff.test.ts. */
class TiffWriterTest {
    private fun lzwDecode(data: ByteArray, from: Int, length: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val table = ArrayList<ByteArray>()
        fun reset() { table.clear(); for (i in 0 until 256) table.add(byteArrayOf(i.toByte())); table.add(ByteArray(0)); table.add(ByteArray(0)) }
        reset()
        var width = 9
        var pos = from * 8L
        val end = (from + length) * 8L
        var old: ByteArray? = null
        while (true) {
            check(pos + width <= end) { "ran out of data before EOI" }
            var code = 0
            repeat(width) { code = (code shl 1) or ((data[(pos shr 3).toInt()].toInt() shr (7 - (pos and 7).toInt())) and 1); pos++ }
            if (code == 257) break
            if (code == 256) { reset(); width = 9; old = null; continue }
            val entry = when {
                code < table.size -> table[code]
                code == table.size && old != null -> old + old[0]
                else -> error("bad code $code")
            }
            out.write(entry)
            old?.let { table.add(it + entry[0]) }
            old = entry
            if (table.size >= (1 shl width) - 1 && width < 12) width++
        }
        return out.toByteArray()
    }

    @Test
    fun lzwRoundTrips() {
        var seed = 1
        val rnd = { seed = seed * 1103515245 + 12345; (seed ushr 24).toByte() }
        val inputs = listOf(
            ByteArray(0), byteArrayOf(7), ByteArray(200_000) { rnd() }, ByteArray(300_000) { 3 },
            ByteArray(300_000) { ((it * 7) % 13).toByte() },
        ) + (1 until 600 step 37).map { n -> ByteArray(n * 17) { (it % n).toByte() } }
        val lzw = LzwEncoder()
        for (src in inputs) {
            val n = lzw.encode(src, src.size)
            assertArrayEquals(src, lzwDecode(lzw.output, 0, n))
        }
    }

    @Test
    fun stripsDecodeToTheInput() {
        val w = 700
        val h = 333
        val img = ShortArray(w * h * 3) { (((it * 977) xor (it shr 5)) and 0xffff).toShort() }
        for (bits in listOf(8, 16)) {
            val file = File.createTempFile("rawboy", ".tif")
            try {
                RandomAccessFile(file, "rw").channel.use { ch ->
                    val writer = TiffWriter(ch, w, h, bits)
                    for (y in 0 until h step 100) {
                        val rows = minOf(100, h - y)
                        writer.writeRows(img.copyOfRange(y * w * 3, (y + rows) * w * 3), rows)
                    }
                    writer.finish()
                }
                val buf = file.readBytes()
                val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                val ifd = b.getInt(4)
                val tags = HashMap<Int, IntArray>()
                for (i in 0 until b.getShort(ifd).toInt()) {
                    val p = ifd + 2 + i * 12
                    val type = b.getShort(p + 2).toInt()
                    val count = b.getInt(p + 4)
                    val size = if (type == 3) 2 else 4
                    val at = if (count * size > 4) b.getInt(p + 8) else p + 8
                    tags[b.getShort(p).toInt()] = IntArray(count) {
                        if (size == 2) b.getShort(at + it * 2).toInt() and 0xffff else b.getInt(at + it * 4)
                    }
                }
                assertEquals(5, tags[259]!![0])
                assertEquals(2, tags[317]!![0])
                val offsets = tags[273]!!
                val counts = tags[279]!!
                val bytes = offsets.indices.map { lzwDecode(buf, offsets[it], counts[it]) }.reduce { a, c -> a + c }
                val bps = bits / 8
                val got = IntArray(w * h * 3)
                for (y in 0 until h) {
                    val prev = IntArray(3)
                    for (i in 0 until w * 3) {
                        val at = (y * w * 3 + i) * bps
                        val d = if (bps == 2) (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8) else bytes[at].toInt() and 0xff
                        prev[i % 3] = (d + prev[i % 3]) and (if (bits == 16) 0xffff else 0xff)
                        got[y * w * 3 + i] = prev[i % 3]
                    }
                }
                val want = IntArray(img.size) {
                    val v = img[it].toInt() and 0xffff
                    if (bits == 16) v else (v * 255 + 32767) / 65535
                }
                assertArrayEquals(want, got)
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun carriesTheExifOfThePhoto() {
        val meta = ExifMetadata(
            make = "FUJIFILM", model = "GFX 50R", lensModel = "GF110mmF2 R LM WR", dateTimeOriginal = "2026:04:27 14:03:09",
            exposureTime = 1 / 250.0, fNumber = 2.8, focalLength = 110.0, iso = 1000.0,
            gps = ExifMetadata.Gps(doubleArrayOf(48.0, 8.0, 12.5), "N", doubleArrayOf(24.0, 30.0, 1.25), "E", 1840.0, false),
        )
        val file = File.createTempFile("rawboy", ".tif")
        try {
            RandomAccessFile(file, "rw").channel.use { ch ->
                val writer = TiffWriter(ch, 64, 40, 16, meta)
                writer.writeRows(ShortArray(64 * 40 * 3) { 30000.toShort() }, 40)
                writer.finish()
            }
            val buf = file.readBytes()
            val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            val ifd = b.getInt(4)
            val tags = HashMap<Int, Pair<Int, Int>>() // tag -> (count, value or offset)
            var last = -1
            for (i in 0 until b.getShort(ifd).toInt()) {
                val p = ifd + 2 + i * 12
                val tag = b.getShort(p).toInt() and 0xffff
                assert(tag > last) // sorted
                last = tag
                tags[tag] = b.getInt(p + 4) to b.getInt(p + 8)
            }
            fun text(tag: Int): String {
                val (count, at) = tags[tag]!!
                return String(buf, at, count - 1, Charsets.US_ASCII)
            }
            assertEquals("FUJIFILM", text(271))
            assertEquals("GFX 50R", text(272))
            assertEquals("RawBoy", text(305))
            assert(tags[34665]!!.second > 0) // Exif IFD
            assert(tags[34853]!!.second > 0) // GPS IFD
            val (_, offset) = tags[273]!!
            val (_, count) = tags[279]!!
            assertEquals(buf.size, offset + count) // the one strip ends the file
        } finally {
            file.delete()
        }
    }
}
