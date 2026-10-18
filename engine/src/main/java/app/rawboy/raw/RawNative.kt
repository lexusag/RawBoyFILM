package app.rawboy.raw

import java.nio.ByteBuffer

/** JNI surface of librawboy (see app/src/main/cpp/jni_bridge.cpp). */
internal object RawNative {
    init {
        System.loadLibrary("rawboy")
    }

    @JvmStatic external fun open(fd: Int, extraBlack: Float): Long
    @JvmStatic external fun openBitmap(bitmap: android.graphics.Bitmap, fd: Int): Long
    @JvmStatic external fun displayReferred(handle: Long): Boolean
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun width(handle: Long): Int
    @JvmStatic external fun height(handle: Long): Int
    @JvmStatic external fun asShotWhite(handle: Long): DoubleArray
    @JvmStatic external fun describe(handle: Long): String
    @JvmStatic external fun shotInfo(handle: Long): DoubleArray
    @JvmStatic external fun camera(handle: Long): String
    @JvmStatic external fun iso(handle: Long): Float
    @JvmStatic external fun baselineExposure(handle: Long): Float
    @JvmStatic external fun make(handle: Long): String
    @JvmStatic external fun lensInfo(handle: Long): DoubleArray
    @JvmStatic external fun lensShading(handle: Long): FloatArray
    @JvmStatic external fun lensName(handle: Long): String
    @JvmStatic external fun detectBlemishes(handle: Long, mask: java.nio.FloatBuffer, maskWidth: Int, maskHeight: Int, faceDiameter: Float): FloatArray
    @JvmStatic external fun healBlemishes(handle: Long, spots: FloatArray)
    @JvmStatic external fun downsample(handle: Long, dstWidth: Int, dstHeight: Int, out: ByteBuffer, boostHighlights: Boolean)
    @JvmStatic external fun copyTile(handle: Long, x: Int, y: Int, width: Int, height: Int, out: ByteBuffer)
    @JvmStatic external fun noiseLevel(handle: Long): FloatArray
}
