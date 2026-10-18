package app.rawboy.color

/** Minimal row-major 3x3 matrix helpers. */
object Mat3 {
    fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += a[i * 3 + k] * b[k * 3 + j]
            r[i * 3 + j] = s
        }
        return r
    }

    fun apply(m: DoubleArray, v: DoubleArray) = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    fun diagonal(a: Double, b: Double, c: Double) = doubleArrayOf(a, 0.0, 0.0, 0.0, b, 0.0, 0.0, 0.0, c)

    fun inverse(m: DoubleArray): DoubleArray {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])
        require(det != 0.0) { "singular matrix" }
        val i = 1.0 / det
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) * i, (m[2] * m[7] - m[1] * m[8]) * i, (m[1] * m[5] - m[2] * m[4]) * i,
            (m[5] * m[6] - m[3] * m[8]) * i, (m[0] * m[8] - m[2] * m[6]) * i, (m[2] * m[3] - m[0] * m[5]) * i,
            (m[3] * m[7] - m[4] * m[6]) * i, (m[1] * m[6] - m[0] * m[7]) * i, (m[0] * m[4] - m[1] * m[3]) * i,
        )
    }

    /** Row-major double -> column-major float, as glUniformMatrix3fv expects. */
    fun toGlColumnMajor(m: DoubleArray) = FloatArray(9) { idx ->
        val col = idx / 3
        val row = idx % 3
        m[row * 3 + col].toFloat()
    }
}
