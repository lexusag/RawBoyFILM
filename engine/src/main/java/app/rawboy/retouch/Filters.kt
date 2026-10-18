package app.rawboy.retouch

/** Small image filters shared by skin detection and brush masks (plain Kotlin, so JVM tests can use them). */
internal object Filters {
    /** Mean over a (2r+1)^2 window via an integral image, clamped at the borders. */
    fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val sat = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += src[y * w + x]
                sat[(y + 1) * (w + 1) + x + 1] = sat[y * (w + 1) + x + 1] + row
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - r)
            val y1 = minOf(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - r)
                val x1 = minOf(w, x + r + 1)
                val sum = sat[y1 * (w + 1) + x1] - sat[y0 * (w + 1) + x1] - sat[y1 * (w + 1) + x0] + sat[y0 * (w + 1) + x0]
                out[y * w + x] = (sum / ((y1 - y0) * (x1 - x0))).toFloat()
            }
        }
        return out
    }
}
