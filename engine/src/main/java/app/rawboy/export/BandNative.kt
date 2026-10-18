package app.rawboy.export

import java.nio.ByteBuffer

/** JNI surface of the export's per-pixel work (librawboyjpeg, see engine/src/main/cpp/band_ops.cpp). */
internal object BandNative {
    init {
        System.loadLibrary("rawboyjpeg")
    }

    @JvmStatic external fun createDownscaler(inWidth: Int, inHeight: Int, outWidth: Int, outHeight: Int): Long
    @JvmStatic external fun maxOutputRows(handle: Long, inputRows: Int): Int
    @JvmStatic external fun push(handle: Long, input: ShortArray, rows: Int, output: ShortArray): Int
    @JvmStatic external fun finish(handle: Long, output: ShortArray): Int
    @JvmStatic external fun destroy(handle: Long)

    /**
     * Tile of RGBA samples (direct [tile], [cols] x [rows], [bytesPerSample] 2 or 4) -> RGB 16-bit [band]
     * ([bandWidth] pixels wide) at column [x].
     */
    @JvmStatic external fun packTile(tile: ByteBuffer, bytesPerSample: Int, cols: Int, rows: Int, band: ShortArray, bandWidth: Int, x: Int)
}
