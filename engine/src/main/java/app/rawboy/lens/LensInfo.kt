package app.rawboy.lens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Lens data for one decoded image. Corrections come from DNG opcodes and are in
 * sensor coordinates (before orientation); [orientation] maps between the two.
 */
class LensInfo(
    val lensName: String,
    /** Oriented (display) size of the decoded image. */
    val width: Int,
    val height: Int,
    val sensorWidth: Int,
    val sensorHeight: Int,
    /** TIFF orientation 1..8 that turned the sensor image into the displayed one. */
    val orientation: Int,
    val warp: Warp?,
    val vignette: RadialVignette?,
    val shading: ShadingMap?,
    /** Number of DNG GainMaps applied while decoding (colour + lens shading). */
    val gainMapsApplied: Int,
) {
    /** DNG WarpRectilinear: per plane kr0..kr3, kt0, kt1; centre relative to the sensor image. */
    class Warp(val planes: Int, val coefficients: DoubleArray, val centerX: Double, val centerY: Double)

    /** DNG FixVignetteRadial: gain = 1 + k0 r^2 + ... + k4 r^10. */
    class RadialVignette(val k: DoubleArray, val centerX: Double, val centerY: Double)

    /** Luminance part of the DNG gain maps (already applied), so it can be undone on the GPU. */
    class ShadingMap(
        val pointsV: Int,
        val pointsH: Int,
        val originV: Double,
        val originH: Double,
        val spacingV: Double,
        val spacingH: Double,
        val gains: FloatBuffer,
    )

    val hasProfile: Boolean get() = warp != null || vignette != null || shading != null

    /** One line for the Lens tab, e.g. "vignetting & colour shading, distortion". */
    val profileSummary: String?
        get() {
            val parts = buildList {
                if (shading != null || gainMapsApplied > 0) add("vignetting & colour shading")
                if (vignette != null) add("vignetting")
                if (warp != null) add(if (warp.planes == 3) "distortion & chromatic aberration" else "distortion")
            }.distinct()
            return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }

    companion object {
        /** Unpacks RawNative.lensInfo (layout documented in jni_bridge.cpp). */
        fun fromNative(name: String, width: Int, height: Int, v: DoubleArray, shadingGains: FloatArray): LensInfo {
            val warp = if (v[3] != 0.0) Warp(v[4].toInt(), v.copyOfRange(5, 23), v[23], v[24]) else null
            val vignette = if (v[25] != 0.0) RadialVignette(v.copyOfRange(26, 31), v[31], v[32]) else null
            val shading = if (v[33] != 0.0 && shadingGains.isNotEmpty()) {
                val buffer = ByteBuffer.allocateDirect(shadingGains.size * 4).order(ByteOrder.nativeOrder())
                    .asFloatBuffer().apply { put(shadingGains); position(0) }
                ShadingMap(v[34].toInt(), v[35].toInt(), v[36], v[37], v[38], v[39], buffer)
            } else {
                null
            }
            return LensInfo(
                lensName = name,
                width = width,
                height = height,
                sensorWidth = v[1].toInt(),
                sensorHeight = v[2].toInt(),
                orientation = v[0].toInt(),
                warp = warp,
                vignette = vignette,
                shading = shading,
                gainMapsApplied = v[40].toInt(),
            )
        }

        /** No image yet: identity geometry. */
        fun none(width: Int = 1, height: Int = 1) =
            LensInfo("", width, height, width, height, 1, null, null, null, 0)
    }
}
