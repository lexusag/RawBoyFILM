package app.rawboy.auto.analysis

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where [SceneAnalyzer] meters exposure, in fractions of the upright image (x right, y down). Camera meters
 * [Centre] (0.5) and, while focus is locked by a tap, a [Spot] there.
 */
sealed class MeterArea {
    /** Pixel box `[x0, x1) × [y0, y1)` in a [width] × [height] image: never empty, always inside it. */
    abstract fun box(width: Int, height: Int): IntArray

    /** The centred box [fraction] of the width and height (0.5 = middle half each way). */
    data class Centre(val fraction: Float) : MeterArea() {
        override fun box(width: Int, height: Int): IntArray {
            val x0 = ((1f - fraction) / 2f * width).toInt()
            val y0 = ((1f - fraction) / 2f * height).toInt()
            return intArrayOf(x0, max(x0 + 1, width - x0), y0, max(y0 + 1, height - y0))
        }
    }

    /**
     * A square [size] of the short side around ([x], [y]), moved inside the frame near an edge (so a tap in a corner
     * still meters a box of the same size). The same size as Camera2's tap region (`CameraController.setRegions`).
     */
    data class Spot(val x: Float, val y: Float, val size: Float = SPOT_SIZE) : MeterArea() {
        override fun box(width: Int, height: Int): IntArray {
            val side = (size * min(width, height)).roundToInt().coerceIn(1, min(width, height))
            val x0 = (x * width - side / 2f).roundToInt().coerceIn(0, width - side)
            val y0 = (y * height - side / 2f).roundToInt().coerceIn(0, height - side)
            return intArrayOf(x0, x0 + side, y0, y0 + side)
        }

        /**
         * The same spot in the photo taken with the device turned [deviceDegrees] clockwise from portrait: the photo is
         * level with the horizon, so the viewfinder's (portrait) point turns the other way.
         */
        fun turned(deviceDegrees: Int): Spot = when ((deviceDegrees % 360 + 360) % 360) {
            90 -> copy(x = 1f - y, y = x)
            180 -> copy(x = 1f - x, y = 1f - y)
            270 -> copy(x = y, y = 1f - x)
            else -> this
        }
    }

    companion object {
        /** Tap metering: 16% of the short side (about the size of the focus ring). */
        const val SPOT_SIZE = 0.16f
    }
}
