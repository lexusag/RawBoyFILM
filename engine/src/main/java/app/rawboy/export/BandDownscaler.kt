package app.rawboy.export

import kotlin.math.roundToInt

/**
 * Shrinks a developed image while it streams in, band by band, by exact area averaging (each output
 * pixel is the coverage-weighted mean of the input pixels under it): the right filter for downscaling,
 * no ringing, and the result doesn't depend on where the bands split. Only two output rows of state are
 * kept, so memory stays small at any size. The work is native (`band_ops.cpp`).
 *
 * Samples are interleaved RGB, unsigned 16-bit in shorts, as [Exporter] renders them. Feed input rows top
 * to bottom with [push] (at most [maxRowsPerPush] per call), then call [finish]; finished output rows go to
 * [emit] (first output row, row count, samples; the array is reused). Close it afterwards.
 */
internal class BandDownscaler(
    inWidth: Int,
    inHeight: Int,
    val outWidth: Int,
    val outHeight: Int,
    maxRowsPerPush: Int,
    private val emit: (y: Int, rows: Int, band: ShortArray) -> Unit,
) : AutoCloseable {
    init {
        require(outWidth in 1..inWidth && outHeight in 1..inHeight) { "only downscaling" }
    }

    private var handle = BandNative.createDownscaler(inWidth, inHeight, outWidth, outHeight)
    private val out = ShortArray(BandNative.maxOutputRows(handle, maxRowsPerPush) * outWidth * 3)
    private var outY = 0

    /** Adds [rows] input rows from [band] (interleaved RGB, input width pixels per row). */
    fun push(rows: Int, band: ShortArray) = emitRows(BandNative.push(handle, band, rows, out))

    /** Emits the remaining rows; call once after the last [push]. */
    fun finish() = emitRows(BandNative.finish(handle, out))

    private fun emitRows(rows: Int) {
        if (rows == 0) return
        emit(outY, rows, out)
        outY += rows
    }

    override fun close() {
        if (handle != 0L) BandNative.destroy(handle)
        handle = 0L
    }

    companion object {
        /** Output size for a [width] x [height] image limited to [maxLongSide] (null or larger = unchanged). */
        fun outputSize(width: Int, height: Int, maxLongSide: Int?): Pair<Int, Int> {
            val long = maxOf(width, height)
            if (maxLongSide == null || maxLongSide <= 0 || long <= maxLongSide) return width to height
            val scale = maxLongSide.toDouble() / long
            return maxOf(1, (width * scale).roundToInt()).coerceAtMost(width) to
                maxOf(1, (height * scale).roundToInt()).coerceAtMost(height)
        }
    }
}
