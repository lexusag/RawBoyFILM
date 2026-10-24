package app.rawboy.auto

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream

/**
 * When a photo was shot, for ordering Auto's batch ([PhotoOrder]): EXIF's DateTimeOriginal (else when it was digitised
 * or last written), read from the file's metadata without decoding it; the file's modified date when it has none.
 * Ports: `CaptureDate.swift` (ImageIO), the web's `engine/browse/CaptureDate.ts`.
 */
object CaptureDate {
    /** How much of a CR3's start [cr3Date] reads: its metadata boxes come first, ahead of the previews. */
    private const val CR3_HEAD = 256 shl 10

    /** [uri]'s capture date, else its file date, as an EXIF date; null when neither is known. Blocking: call off the main thread. */
    fun read(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        return runCatching { fromExif(resolver, uri) }.getOrNull()
            ?: runCatching { resolver.openInputStream(uri)?.use { cr3Date(readHead(it)) } }.getOrNull()
            ?: runCatching { fileDate(resolver, uri) }.getOrNull()
    }

    /** Up to [CR3_HEAD] bytes from the start of [input] (InputStream.readNBytes needs API 33). */
    private fun readHead(input: InputStream): ByteArray {
        val buf = ByteArray(CR3_HEAD)
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return buf.copyOf(n)
    }

    /** ExifInterface reads the raws it knows (DNG, CR2, NEF, ARW, RAF, ORF, RW2, PEF, SRW) and JPEG, HEIC, PNG; not CR3. */
    private fun fromExif(resolver: ContentResolver, uri: Uri): String? {
        // A file descriptor lets it seek to the metadata; a plain stream would be read from the start.
        val exif = resolver.openFileDescriptor(uri, "r")?.use { ExifInterface(it.fileDescriptor) }
            ?: resolver.openInputStream(uri)?.use { ExifInterface(it) }
            ?: return null
        return PhotoOrder.validDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            ?: PhotoOrder.validDate(exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED))
            ?: PhotoOrder.validDate(exif.getAttribute(ExifInterface.TAG_DATETIME))
    }

    private fun fileDate(resolver: ContentResolver, uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path?.let { PhotoOrder.fileDate(File(it).lastModified()) }
        val millis = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
        return millis?.let { PhotoOrder.fileDate(it) }
    }

    private const val CANON_UUID = "85c0b687820f11e08111f4ce462b6a48"

    /**
     * The capture date in the start of a Canon CR3 ([head]): moov's Canon box holds CMT1 (IFD0: DateTime) and CMT2 (the
     * EXIF IFD: DateTimeOriginal), each a little TIFF. Null for anything else.
     */
    fun cr3Date(head: ByteArray): String? {
        if (head.size < 12 || String(head, 4, 4, Charsets.ISO_8859_1) != "ftyp") return null
        val moov = boxes(head, 0, head.size).firstOrNull { it.type == "moov" } ?: return null
        val canon = boxes(head, moov.start, moov.end).firstOrNull { it.type == "uuid" && it.uuid == CANON_UUID } ?: return null
        val inner = boxes(head, canon.start, canon.end)
        inner.firstOrNull { it.type == "CMT2" }?.let { b ->
            (tiffDate(head, b.start, 0x9003) ?: tiffDate(head, b.start, 0x9004))?.let { return it }
        }
        return inner.firstOrNull { it.type == "CMT1" }?.let { tiffDate(head, it.start, 0x0132) }
    }

    private class Box(val type: String, val start: Int, val end: Int, val uuid: String)

    /** The boxes of an ISO base media file between [from] and [to] in [b] (those reaching past [b] are cut short). */
    private fun boxes(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var at = from
        while (at + 8 <= to && out.size < 256) {
            var size = u32(b, at, false)
            val type = String(b, at + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                if (at + 16 > b.size) break
                size = (u32(b, at + 8, false) shl 32) or u32(b, at + 12, false)
                header = 16
            } else if (size == 0L) {
                size = (to - at).toLong()
            }
            if (size < header) break
            var uuid = ""
            if (type == "uuid") {
                if (at + header + 16 > b.size) break
                uuid = (0 until 16).joinToString("") { "%02x".format(b[at + header + it]) }
                header += 16
            }
            val end = minOf(at.toLong() + size, to.toLong()).toInt()
            out += Box(type, at + header, end, uuid)
            at = end
        }
        return out
    }

    /** ASCII tag [tag] of the first IFD of the TIFF at [base] in [b], as a valid EXIF date; else null. */
    private fun tiffDate(b: ByteArray, base: Int, tag: Int): String? {
        if (base + 8 > b.size) return null
        val le = b[base] == 'I'.code.toByte()
        if (!le && b[base] != 'M'.code.toByte()) return null
        val ifd = base + u32(b, base + 4, le).toInt()
        if (ifd < base || ifd + 2 > b.size) return null
        val n = u16(b, ifd, le)
        for (i in 0 until n) {
            val p = ifd + 2 + i * 12
            if (p + 12 > b.size) return null
            if (u16(b, p, le) != tag || u16(b, p + 2, le) != 2) continue
            val count = u32(b, p + 4, le).toInt()
            val at = if (count <= 4) p + 8 else base + u32(b, p + 8, le).toInt()
            if (count < 19 || at < 0 || at + 19 > b.size) return null
            return PhotoOrder.validDate(String(b, at, 19, Charsets.ISO_8859_1))
        }
        return null
    }

    private fun u16(b: ByteArray, at: Int, le: Boolean): Int {
        val x = b[at].toInt() and 0xff
        val y = b[at + 1].toInt() and 0xff
        return if (le) x or (y shl 8) else (x shl 8) or y
    }

    private fun u32(b: ByteArray, at: Int, le: Boolean): Long {
        val first = u16(b, at, le).toLong()
        val second = u16(b, at + 2, le).toLong()
        return if (le) first or (second shl 16) else (first shl 16) or second
    }
}
