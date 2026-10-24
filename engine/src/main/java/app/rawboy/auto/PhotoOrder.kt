package app.rawboy.auto

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How Auto's grid (and RawBoy Browser on the web) orders photos: by when they were shot, or by file name. Remembered. */
enum class PhotoSort { DATE, NAME }

/** Photo order for [PhotoSort] (ports: `PhotoOrder.swift`, `PhotoOrder.ts`). */
object PhotoOrder {
    /** EXIF's date format, "YYYY:MM:DD HH:MM:SS", which sorts as text. */
    private val EXIF_DATE = Regex("""^\d{4}:\d\d:\d\d \d\d:\d\d:\d\d""")
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /** [text] as an EXIF date if it is one (cameras without a set clock write blanks or zeros), else null. */
    fun validDate(text: String?): String? {
        val t = text?.trim() ?: return null
        return if (EXIF_DATE.containsMatchIn(t) && !t.startsWith("0000")) t.substring(0, 19) else null
    }

    /** A file's modified time [millis] as an EXIF date in local time: the date of photos with no capture date. */
    fun fileDate(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String? =
        if (millis <= 0) null else FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** File names as people read them: case-insensitive, runs of digits by value ("IMG_9" before "IMG_10"). */
    fun compareNames(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val ei = digitsEnd(a, i)
                val ej = digitsEnd(b, j)
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                val c = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j).takeIf { it != 0 } ?: a.compareTo(b)
    }

    private fun digitsEnd(s: String, from: Int): Int {
        var k = from
        while (k < s.length && s[k].isDigit()) k++
        return k
    }

    /**
     * Orders items by [sort]: [PhotoSort.DATE] by [date] (oldest first; photos without one after the rest, by name),
     * then name; [PhotoSort.NAME] by [name], then date.
     */
    fun <T> comparator(sort: PhotoSort, date: (T) -> String?, name: (T) -> String): Comparator<T> {
        val byName = Comparator<T> { a, b -> compareNames(name(a), name(b)) }
        val byDate = Comparator<T> { a, b ->
            val da = date(a)
            val db = date(b)
            when {
                da == db -> 0
                da == null -> 1
                db == null -> -1
                else -> da.compareTo(db)
            }
        }
        return if (sort == PhotoSort.DATE) byDate.then(byName) else byName.then(byDate)
    }
}
