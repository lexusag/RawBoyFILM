package app.rawboy.raw

import java.nio.ByteBuffer

/**
 * JNI surface of the burst merge (librawboy, see engine/src/main/cpp/burst_merge.h); RawBoy Camera's burst shots.
 * A worker thread merges each frame as soon as its exposure and the reference's levels are known, then frees it.
 */
object BurstNative {
    init {
        System.loadLibrary("rawboy")
    }

    /**
     * A merger for [width] x [height] Bayer frames. [black]: per 2x2 CFA position, row-major, until [setLevels];
     * frames wait for [setLevels] before they merge.
     */
    @JvmStatic external fun create(width: Int, height: Int, black: FloatArray, white: Float): Long

    /** Copies one RAW_SENSOR plane; returns its index (0 = the reference). */
    @JvmStatic external fun addFrame(handle: Long, plane: ByteBuffer, width: Int, height: Int, rowStride: Int): Int

    /** Frame [frame]'s exposure (time x ISO), once its result is in. */
    @JvmStatic external fun setGain(handle: Long, frame: Int, gain: Double)

    /**
     * The reference's black levels (null: keep [create]'s) and noise model: variance = scale x signal + offset in
     * units of the white-black range (0, 0 = estimate from the frames).
     */
    @JvmStatic external fun setLevels(handle: Long, black: FloatArray?, noiseScale: Float, noiseOffset: Float)

    /** Merges the frames still waiting; the returned direct buffer holds the 16-bit samples until [destroy]. */
    @JvmStatic external fun finish(handle: Long, width: Int, height: Int): ByteBuffer

    /** Mean tile weight of each frame after the reference, 0..1 (how much of it was used). */
    @JvmStatic external fun frameWeights(handle: Long): FloatArray

    /** Most frames that waited at once (copied, not yet merged). */
    @JvmStatic external fun maxQueued(handle: Long): Int

    @JvmStatic external fun destroy(handle: Long)
}
