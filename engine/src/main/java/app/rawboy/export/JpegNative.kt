package app.rawboy.export

/** JNI surface of the jpegli encoder (librawboyjpeg, see engine/src/main/cpp/jpeg_jpegli.cpp). */
internal object JpegNative {
    init {
        System.loadLibrary("rawboyjpeg")
    }

    @JvmStatic external fun begin(fd: Int, width: Int, height: Int, quality: Int): Long
    @JvmStatic external fun writeRows(handle: Long, samples: ShortArray, rows: Int)
    @JvmStatic external fun finish(handle: Long)
    @JvmStatic external fun abort(handle: Long)
}
