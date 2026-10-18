package app.rawboy.gl

import android.util.Half
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * The viewfinder's small whole-image copy for analysis, made from [ViewfinderRenderer]'s readback
 * (PhotoSession.BASE_MAX_DIMENSION). The renderer makes its own base and bloom textures on the GPU.
 */
class ViewfinderBase(val base: PreviewImage) {
    companion object {
        /** [rgba]: [width] x [height] linear floats, row 0 = top; full* = the raw's upright size. */
        fun from(width: Int, height: Int, rgba: FloatArray, fullWidth: Int, fullHeight: Int): ViewfinderBase {
            val rgb = FloatArray(width * height * 3)
            for (i in 0 until width * height) {
                rgb[i * 3] = max(0f, rgba[i * 4])
                rgb[i * 3 + 1] = max(0f, rgba[i * 4 + 1])
                rgb[i * 3 + 2] = max(0f, rgba[i * 4 + 2])
            }
            return ViewfinderBase(PreviewImage(width, height, toHalf(rgb), fullWidth, fullHeight))
        }

        private fun toHalf(rgb: FloatArray): ByteBuffer {
            val out = ByteBuffer.allocateDirect(rgb.size * 2).order(ByteOrder.nativeOrder())
            val shorts = out.asShortBuffer()
            for (v in rgb) shorts.put(Half.toHalf(v))
            return out
        }
    }
}
