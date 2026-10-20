package app.rawboy.gl

import android.util.Half
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Bloom's wide tail, scattered once per photo on its small (~64 px) copy: a round power law (alpha 2.2, knee
 * 0.6 texel) out to 7 texels (~10% of the long edge), its weights falling to zero at the rim. The develop shader
 * samples the result as a B-spline, so the glow is smooth: summed per pixel from a bilinear copy, the coarse
 * texels showed as bands and squares. The viewfinder runs the same kernel on the GPU
 * (ViewfinderRenderer's TAIL_SCATTER). Ports: BloomTail.ts, BloomTail.swift.
 */
object BloomTail {
    const val RADIUS = 6

    /** Tap offsets (dx, dy) and their weights, summing to 1. */
    private val dx: IntArray
    private val dy: IntArray
    private val weight: FloatArray

    init {
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        val ws = ArrayList<Double>()
        val rim2 = ((RADIUS + 1) * (RADIUS + 1)).toDouble()
        for (j in -RADIUS..RADIUS) {
            for (i in -RADIUS..RADIUS) {
                val r2 = (i * i + j * j).toDouble()
                val rim = 1.0 - r2 / rim2
                if (rim <= 0.0) continue
                xs += i
                ys += j
                ws += (1.0 + sqrt(r2) / 0.6).pow(-2.2) * rim * rim
            }
        }
        val sum = ws.sum()
        dx = xs.toIntArray()
        dy = ys.toIntArray()
        weight = FloatArray(ws.size) { (ws[it] / sum).toFloat() }
    }

    /** [img] (half-float RGB) scattered by the tail kernel, edges clamped. */
    fun scatter(img: PreviewImage): PreviewImage {
        val w = img.width
        val h = img.height
        val src = img.pixels.duplicate().apply { position(0) }.order(ByteOrder.nativeOrder()).asShortBuffer()
        val rgb = FloatArray(w * h * 3) { Half.toFloat(src.get(it)) }
        val out = ByteBuffer.allocateDirect(w * h * 6).order(ByteOrder.nativeOrder())
        val dst = out.asShortBuffer()
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in weight.indices) {
                    val o = ((y + dy[k]).coerceIn(0, h - 1) * w + (x + dx[k]).coerceIn(0, w - 1)) * 3
                    r += weight[k] * rgb[o]
                    g += weight[k] * rgb[o + 1]
                    b += weight[k] * rgb[o + 2]
                }
                dst.put(Half.toHalf(r))
                dst.put(Half.toHalf(g))
                dst.put(Half.toHalf(b))
            }
        }
        return PreviewImage(w, h, out, img.fullWidth, img.fullHeight)
    }
}
