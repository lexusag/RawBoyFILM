package app.rawboy.auto.analysis

import android.util.Half
import app.rawboy.gl.PreviewImage
import app.rawboy.retouch.SkinMask
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * What the auto decisions need to know about a photo. Luminances are linear scene
 * light as decoded (1.0 = sensor clip, as-shot white balance); percentiles are log2.
 */
class SceneStats(
    /** Centre-weighted geometric mean luminance. */
    val key: Float,
    val p1: Float,
    val p5: Float,
    val p50: Float,
    val p98: Float,
    val p99: Float,
    /** Fraction of pixels with any channel at sensor clip. */
    val clipped: Float,
    /** Mean saturation of the display-encoded image, 0..1. */
    val chroma: Float,
    /** Share of the frame that is clearly (saturation > 0.15) green/blue, and warm (red to yellow). */
    val greenBlue: Float,
    val warm: Float,
    /** Share of the frame covered by a face (0 = none found). */
    val faceArea: Float,
    /** Mean linear luminance of skin, 0 if there is none. */
    val skinLuminance: Float,
    val iso: Float,
    /** log2-luminance histogram, [SceneAnalyzer.BINS_PER_EV] bins per EV from [SceneAnalyzer.MIN_LOG]. */
    private val histogram: IntArray,
) {
    /** p99 - p1 in EV. */
    val dynamicRange: Float get() = p99 - p1
    val hasFace: Boolean get() = faceArea > 0f

    /** Fraction of pixels whose log2 luminance is at least [log2Luminance]. */
    fun fractionAbove(log2Luminance: Float): Float {
        val from = SceneAnalyzer.bin(log2Luminance)
        var count = 0L
        var total = 0L
        for (i in histogram.indices) {
            total += histogram[i]
            if (i >= from) count += histogram[i]
        }
        return if (total > 0) count.toFloat() / total else 0f
    }
}

/** Reads the 512 px whole-image copy on the CPU; cheap (~260k pixels). */
object SceneAnalyzer {
    const val MIN_LOG = -20f
    const val MAX_LOG = 1f
    const val BINS_PER_EV = 16
    private const val BINS = ((MAX_LOG - MIN_LOG) * BINS_PER_EV).toInt() + 1

    internal fun bin(log2Luminance: Float): Int = ((log2Luminance.coerceIn(MIN_LOG, MAX_LOG) - MIN_LOG) * BINS_PER_EV).toInt()

    // Rec.2020 luminance weights.
    private const val KR = 0.2627f
    private const val KG = 0.6780f
    private const val KB = 0.0593f

    /**
     * [meter]: meter only that box ([MeterArea.Centre], a tap's [MeterArea.Spot]): `key` and `p98` (which set
     * exposure) come from it, uniformly weighted; the other stats stay whole-frame. Null: the centre-weighted whole frame.
     */
    fun analyze(base: PreviewImage, skin: SkinMask?, iso: Float, meter: MeterArea? = null): SceneStats {
        val w = base.width
        val h = base.height
        val px = base.pixels.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val histogram = IntArray(BINS)
        var weightedLog = 0.0
        var weightSum = 0.0
        var clipped = 0
        var chromaSum = 0.0
        var greenBlue = 0
        var warm = 0
        var skinSum = 0.0
        var skinCount = 0
        // The metered box.
        val centreHistogram = if (meter != null) IntArray(BINS) else null
        var centreLog = 0.0
        var centreCount = 0
        val (cx0, cx1, cy0, cy1) = meter?.box(w, h) ?: intArrayOf(0, w, 0, h)
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 3
                val r = Half.toFloat(px.get(i)).coerceAtLeast(0f)
                val g = Half.toFloat(px.get(i + 1)).coerceAtLeast(0f)
                val b = Half.toFloat(px.get(i + 2)).coerceAtLeast(0f)
                val lum = max(KR * r + KG * g + KB * b, 1e-6f)
                val logLum = log2(lum)
                histogram[bin(logLum)]++

                // Centre-weighted metering: the middle counts 2.5x the corners.
                val dx = (x - cx) / cx
                val dy = (y - cy) / cy
                val weight = 1.0 + 1.5 * max(0f, 1f - (dx * dx + dy * dy) / 2f)
                weightedLog += weight * logLum
                weightSum += weight
                if (centreHistogram != null && x in cx0 until cx1 && y in cy0 until cy1) {
                    centreHistogram[bin(logLum)]++
                    centreLog += logLum
                    centreCount++
                }
                if (max(r, max(g, b)) >= 0.98f) clipped++

                // Colour judged on a display-like encoding, where saturation means what it looks like.
                val er = r.pow(1 / 2.2f)
                val eg = g.pow(1 / 2.2f)
                val eb = b.pow(1 / 2.2f)
                val hi = max(er, max(eg, eb))
                val lo = min(er, min(eg, eb))
                val sat = if (hi > 1e-4f) (hi - lo) / hi else 0f
                chromaSum += sat
                if (sat > 0.15f && hi > 0.1f) {
                    when (hueDegrees(er, eg, eb)) {
                        in 75f..260f -> greenBlue++
                        in 0f..60f, in 330f..360f -> warm++
                    }
                }

                if (skin != null && skin.hasSkin) {
                    val mx = min(skin.width - 1, x * skin.width / w)
                    val my = min(skin.height - 1, y * skin.height / h)
                    if (skin.data.get(my * skin.width + mx) > 0.5f) {
                        skinSum += lum
                        skinCount++
                    }
                }
            }
        }

        val n = w * h
        fun percentile(p: Float, hist: IntArray = histogram, count: Int = n): Float {
            val target = p * count
            var acc = 0
            for (bin in 0 until BINS) {
                acc += hist[bin]
                if (acc >= target) return MIN_LOG + (bin + 0.5f) / BINS_PER_EV
            }
            return MAX_LOG
        }
        val metered = centreHistogram != null && centreCount > 0
        val faceArea = if (skin != null && skin.faceDiameterPx > 0f) {
            val radius = skin.faceDiameterPx / 2f
            (PI.toFloat() * radius * radius / (base.fullWidth.toFloat() * base.fullHeight)).coerceIn(0f, 1f)
        } else {
            0f
        }
        return SceneStats(
            key = 2.0.pow(if (metered) centreLog / centreCount else weightedLog / weightSum).toFloat(),
            p1 = percentile(0.01f),
            p5 = percentile(0.05f),
            p50 = percentile(0.50f),
            p98 = if (metered) percentile(0.98f, centreHistogram!!, centreCount) else percentile(0.98f),
            p99 = percentile(0.99f),
            clipped = clipped.toFloat() / n,
            chroma = (chromaSum / n).toFloat(),
            greenBlue = greenBlue.toFloat() / n,
            warm = warm.toFloat() / n,
            faceArea = faceArea,
            skinLuminance = if (skinCount > 0) (skinSum / skinCount).toFloat() else 0f,
            iso = iso,
            histogram = histogram,
        )
    }

    private fun log2(v: Float): Float = (ln(v.toDouble()) / ln(2.0)).toFloat()

    private fun hueDegrees(r: Float, g: Float, b: Float): Float {
        // Hue angle in an opponent space (like HSV hue, but smooth).
        val a = r - (g + b) / 2f
        val bb = (g - b) * sqrt(3f) / 2f
        val deg = Math.toDegrees(atan2(bb, a).toDouble()).toFloat()
        return if (deg < 0f) deg + 360f else deg
    }
}
