package app.rawboy.develop

import android.util.Half
import app.rawboy.gl.PixelRect
import app.rawboy.gl.PreviewImage
import app.rawboy.retouch.Filters
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.ceil

/**
 * Auto mask guide: per-pixel features of the photo at the raster size (log2 luminance and two exposure-independent
 * chroma terms), lightly blurred so sensor noise doesn't break strokes up. Mirrors web's BrushRaster.ts.
 */
class BrushGuide(val lum: FloatArray, val red: FloatArray, val blue: FloatArray) {
    companion object {
        /**
         * Linear offsets (1.0 = sensor clip): the luminance floor keeps deep-shadow noise from reading as big EV
         * steps, the chroma one keeps near-black noise from reading as strong colour.
         */
        private const val LUM_FLOOR = 0.002f
        private const val CHROMA_EPS = 0.01f
        /** Box blur radius of the guide (raster pixels), against sensor noise. */
        private const val GUIDE_BLUR = 2
        private val LOG2 = ln(2.0).toFloat()

        private fun log2(v: Float) = ln(v) / LOG2

        fun of(image: PreviewImage): BrushGuide {
            val n = image.width * image.height
            val lum = FloatArray(n)
            val red = FloatArray(n)
            val blue = FloatArray(n)
            val px = image.pixels.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
            for (i in 0 until n) {
                val r = max(Half.toFloat(px.get(i * 3)), 0f)
                val g = max(Half.toFloat(px.get(i * 3 + 1)), 0f)
                val b = max(Half.toFloat(px.get(i * 3 + 2)), 0f)
                val y = 0.2627f * r + 0.678f * g + 0.0593f * b // Rec.2020 luma
                lum[i] = log2(y + LUM_FLOOR)
                red[i] = log2((r + CHROMA_EPS) / (y + CHROMA_EPS))
                blue[i] = log2((b + CHROMA_EPS) / (y + CHROMA_EPS))
            }
            val w = image.width
            val h = image.height
            return BrushGuide(Filters.box(lum, w, h, GUIDE_BLUR), Filters.box(red, w, h, GUIDE_BLUR), Filters.box(blue, w, h, GUIDE_BLUR))
        }
    }
}

/**
 * One layer's painted mask over the whole image (display orientation, row 0 = top), at the size of the preview copy.
 * Two channels, applied stroke by stroke in order:
 * - paint: what strokes added (the shader takes the max of this and the gradient);
 * - erase: how much of the layer's gradient strokes took away.
 * A brush dab of weight w: paint += (1 - paint) w, erase *= 1 - w; an erase dab the other way round.
 *
 * [sync] runs on the UI thread, [upload] on GL threads (preview, export); both lock the raster, so an upload never
 * sees half a stroke. Mirrors web's BrushRaster.ts (same dabs, so masks match across apps).
 */
class BrushRaster(val width: Int, val height: Int, private val guide: () -> BrushGuide) {
    private val paint = FloatArray(width * height)
    private val erase = FloatArray(width * height)
    /** RG8 copy for the GPU. */
    private val rg: ByteBuffer = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.nativeOrder())

    @Volatile var version = 0
        private set
    private val history = ArrayDeque<Pair<Int, PixelRect>>()
    /** Strokes drawn so far (by id) and how many points of the last one. */
    private val drawn = ArrayList<String>()
    private var drawnPoints = 0
    /** Walk state of the last stroke: last point (raster px) and distance since its last dab. */
    private var walkX = 0f
    private var walkY = 0f
    private var walkSince = 0f
    private var dirty: IntArray? = null // x0, y0, x1, y1

    /** Brings the raster up to [strokes]: only new dabs when the list grew, a full redraw otherwise. */
    @Synchronized
    fun sync(strokes: List<BrushStroke>) {
        val extends = strokes.size >= drawn.size &&
            drawn.indices.all { strokes[it].id == drawn[it] } &&
            (drawn.isEmpty() || strokes[drawn.size - 1].points.size >= drawnPoints)
        if (!extends) clear()
        val start = if (drawn.isEmpty()) 0 else drawn.size - 1
        for (i in start until strokes.size) {
            val s = strokes[i]
            val resume = if (i == drawn.size - 1) drawnPoints else 0
            drawStroke(s, resume)
            if (i >= drawn.size) drawn.add(s.id)
            drawnPoints = s.points.size
        }
        commit()
    }

    /** What changed after [uploaded]: a rectangle (the whole raster after a redraw or too far back), or null. */
    @Synchronized
    fun pendingRect(uploaded: Int): PixelRect? {
        if (uploaded == version) return null
        val since = history.filter { it.first > uploaded }
        if (since.isEmpty() || since[0].first != uploaded + 1) return PixelRect(0, 0, width, height)
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE
        var y1 = Int.MIN_VALUE
        for ((_, r) in since) {
            x0 = min(x0, r.x); y0 = min(y0, r.y)
            x1 = max(x1, r.x + r.width); y1 = max(y1, r.y + r.height)
        }
        return PixelRect(x0, y0, x1 - x0, y1 - y0)
    }

    /**
     * Hands the RG8 pixels and what changed since [uploaded] ([fresh]: nothing uploaded yet, send everything) to
     * [block], under the raster's lock. Returns the version now on the GPU.
     */
    @Synchronized
    fun upload(uploaded: Int, fresh: Boolean, block: (pixels: ByteBuffer, rect: PixelRect) -> Unit): Int {
        val r = if (fresh) PixelRect(0, 0, width, height) else pendingRect(uploaded) ?: return version
        block(rg, r)
        return version
    }

    /** Paint and erase at pixel [i], 0..255 (tests). */
    @Synchronized
    fun valueAt(i: Int): Pair<Int, Int> = (rg.get(i * 2).toInt() and 0xFF) to (rg.get(i * 2 + 1).toInt() and 0xFF)

    private fun clear() {
        paint.fill(0f)
        erase.fill(0f)
        drawn.clear()
        drawnPoints = 0
        dirty = intArrayOf(0, 0, width, height)
    }

    /** Dabs of [s] from point index [from] (0 = from its start). */
    private fun drawStroke(s: BrushStroke, from: Int) {
        val long = max(width, height)
        val radius = max(0.75f, min(s.radius, Masks.MAX_BRUSH_RADIUS) * long)
        val spacing = max(1f, radius * SPACING)
        val pts = s.points
        var i = from
        if (i == 0 && pts.size >= 2) {
            val x = pts[0] * width
            val y = pts[1] * height
            dab(s, x, y, radius)
            walkX = x; walkY = y; walkSince = 0f
            i = 2
        }
        while (i + 1 < pts.size) {
            val x = pts[i] * width
            val y = pts[i + 1] * height
            val len = hypot(x - walkX, y - walkY)
            var t = spacing - walkSince
            while (t <= len) {
                val f = t / len
                dab(s, walkX + (x - walkX) * f, walkY + (y - walkY) * f, radius)
                t += spacing
            }
            walkSince = len - (t - spacing)
            walkX = x; walkY = y
            i += 2
        }
    }

    private fun dab(s: BrushStroke, cx: Float, cy: Float, radius: Float) {
        val w = width
        val h = height
        val x0 = max(0, floor(cx - radius).toInt())
        val y0 = max(0, floor(cy - radius).toInt())
        val x1 = min(w - 1, ceil(cx + radius).toInt())
        val y1 = min(h - 1, ceil(cy + radius).toInt())
        if (x0 > x1 || y0 > y1) return
        val inner = radius * (s.hardness / 100f) * 0.95f
        val flow = (s.flow / 100f).coerceIn(0.01f, 1f)
        val g = if (s.auto) guide() else null
        var cl = 0f
        var cr = 0f
        var cb = 0f
        if (g != null) {
            // Colour under the centre, averaged over 3x3.
            var n = 0
            val ix = cx.roundToInt().coerceIn(0, w - 1)
            val iy = cy.roundToInt().coerceIn(0, h - 1)
            for (y in max(0, iy - 1)..min(h - 1, iy + 1)) {
                for (x in max(0, ix - 1)..min(w - 1, ix + 1)) {
                    val k = y * w + x
                    cl += g.lum[k]; cr += g.red[k]; cb += g.blue[k]
                    n++
                }
            }
            cl /= n; cr /= n; cb /= n
        }
        for (y in y0..y1) {
            val dy = y + 0.5f - cy
            for (x in x0..x1) {
                val dx = x + 0.5f - cx
                val d = sqrt(dx * dx + dy * dy)
                if (d >= radius) continue
                var wt = flow
                if (d > inner) {
                    val t = (radius - d) / (radius - inner)
                    wt *= t * t * (3 - 2 * t)
                }
                val k = y * w + x
                if (g != null) {
                    val dl = g.lum[k] - cl
                    val dr = g.red[k] - cr
                    val db = g.blue[k] - cb
                    wt *= exp(-(dl * dl / (SIGMA_L * SIGMA_L) + (dr * dr + db * db) / (SIGMA_C * SIGMA_C)))
                }
                if (wt <= 0f) continue
                if (s.erase) {
                    paint[k] *= 1 - wt
                    erase[k] += (1 - erase[k]) * wt
                } else {
                    paint[k] += (1 - paint[k]) * wt
                    erase[k] *= 1 - wt
                }
            }
        }
        val r = dirty
        dirty = if (r == null) intArrayOf(x0, y0, x1 + 1, y1 + 1) else intArrayOf(min(r[0], x0), min(r[1], y0), max(r[2], x1 + 1), max(r[3], y1 + 1))
    }

    /** Copies the changed area into [rg] and records it as a new version. */
    private fun commit() {
        val r = dirty ?: return
        dirty = null
        for (y in r[1] until r[3]) {
            for (x in r[0] until r[2]) {
                val k = y * width + x
                rg.put(k * 2, (paint[k] * 255f).roundToInt().toByte())
                rg.put(k * 2 + 1, (erase[k] * 255f).roundToInt().toByte())
            }
        }
        version++
        // A full redraw can't be patched onto an older upload: forget the history so it uploads everything.
        if (r[0] == 0 && r[1] == 0 && r[2] == width && r[3] == height) history.clear()
        history.addLast(version to PixelRect(r[0], r[1], r[2] - r[0], r[3] - r[1]))
        if (history.size > HISTORY) history.removeFirst()
    }

    private companion object {
        /** Dab spacing as a fraction of the radius. */
        const val SPACING = 0.15f
        /** Auto mask tolerances: luminance in EV, chroma in log2 ratios. */
        const val SIGMA_L = 0.6f
        const val SIGMA_C = 0.25f
        /** Changes remembered for partial texture uploads. */
        const val HISTORY = 16
    }
}
