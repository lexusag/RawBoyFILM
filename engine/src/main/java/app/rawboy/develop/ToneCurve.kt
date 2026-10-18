package app.rawboy.develop

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One curve point: [x] input, [y] output, both 0..1 of the display-encoded (sRGB) value. */
data class CurvePoint(val x: Float, val y: Float)

/** Which curve the Curves editor shows; [key] is the name in preset files. */
enum class CurveChannel(val key: String, val label: String) {
    RGB("rgb", "RGB"),
    RED("red", "Red"),
    GREEN("green", "Green"),
    BLUE("blue", "Blue"),
}

/**
 * Point curves on the final display-encoded (sRGB) image, as in Lightroom (web: ToneCurve.ts, iOS: ToneCurve.swift).
 * The [rgb] curve applies to all three channels first, then each channel's own curve. Points are sorted by x;
 * the curve between them is a monotone cubic (Fritsch-Carlson), so it never overshoots its points, and it is
 * flat beyond the first and last point.
 */
data class ToneCurves(
    val rgb: List<CurvePoint> = IDENTITY,
    val red: List<CurvePoint> = IDENTITY,
    val green: List<CurvePoint> = IDENTITY,
    val blue: List<CurvePoint> = IDENTITY,
) {
    operator fun get(channel: CurveChannel): List<CurvePoint> = when (channel) {
        CurveChannel.RGB -> rgb
        CurveChannel.RED -> red
        CurveChannel.GREEN -> green
        CurveChannel.BLUE -> blue
    }

    fun with(channel: CurveChannel, points: List<CurvePoint>): ToneCurves = when (channel) {
        CurveChannel.RGB -> copy(rgb = points)
        CurveChannel.RED -> copy(red = points)
        CurveChannel.GREEN -> copy(green = points)
        CurveChannel.BLUE -> copy(blue = points)
    }

    val isIdentity: Boolean get() = CurveChannel.entries.all { isIdentity(this[it]) }

    /**
     * The shader's `uCurve` table: [SIZE] rgb triples, each channel's curve after the RGB one, or null when every
     * curve is the identity (`uCurveOn` 0).
     */
    fun table(): FloatArray? {
        if (isIdentity) return null
        val mm = tangents(rgb)
        val mr = tangents(red)
        val mg = tangents(green)
        val mb = tangents(blue)
        val out = FloatArray(SIZE * 3)
        for (i in 0 until SIZE) {
            val v = evaluate(rgb, i / (SIZE - 1f), mm)
            out[i * 3] = evaluate(red, v, mr)
            out[i * 3 + 1] = evaluate(green, v, mg)
            out[i * 3 + 2] = evaluate(blue, v, mb)
        }
        return out
    }

    fun toJson(): JSONObject = JSONObject().apply {
        for (c in CurveChannel.entries) {
            // Floats via their shortest decimal, as Presets writes numbers.
            put(c.key, JSONArray(this@ToneCurves[c].map { JSONArray(listOf(it.x.toString().toDouble(), it.y.toString().toDouble())) }))
        }
    }

    companion object {
        val IDENTITY = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
        val NONE = ToneCurves()

        /** Samples per channel in the shader's `uCurve` table (CURVE_SIZE in develop.glsl). */
        const val SIZE = 33
        const val MAX_POINTS = 16

        /** Closest two points may sit along the input axis. */
        const val MIN_GAP = 0.02f

        /** Edited positions are kept to 1/1000, so preset files stay readable. */
        private fun snap(v: Float): Float = (v * 1000f).roundToInt() / 1000f

        /** Every point on the diagonal: the curve is the straight identity line. */
        fun isIdentity(points: List<CurvePoint>): Boolean = points.all { abs(it.x - it.y) < 1e-6f }

        /** Fritsch-Carlson tangents: zero at local extrema, limited so each segment stays monotone. */
        private fun tangents(p: List<CurvePoint>): FloatArray {
            val n = p.size
            val d = FloatArray(n - 1) { (p[it + 1].y - p[it].y) / (p[it + 1].x - p[it].x) }
            if (n == 2) return floatArrayOf(d[0], d[0])
            val m = FloatArray(n)
            m[0] = d[0]
            m[n - 1] = d[n - 2]
            for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
            for (i in 0 until n - 1) {
                if (d[i] == 0f) {
                    m[i] = 0f
                    m[i + 1] = 0f
                    continue
                }
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / sqrt(s)
                    m[i] = t * a * d[i]
                    m[i + 1] = t * b * d[i]
                }
            }
            return m
        }

        /** The curve through [points] at input [x] (0..1), clamped to 0..1. */
        fun evaluate(points: List<CurvePoint>, x: Float, m: FloatArray = tangents(points)): Float {
            val n = points.size
            if (x <= points[0].x) return points[0].y.coerceIn(0f, 1f)
            if (x >= points[n - 1].x) return points[n - 1].y.coerceIn(0f, 1f)
            var i = 0
            while (x > points[i + 1].x) i++
            val (x0, y0) = points[i]
            val (x1, y1) = points[i + 1]
            val h = x1 - x0
            val t = (x - x0) / h
            val t2 = t * t
            val t3 = t2 * t
            val y = (2 * t3 - 3 * t2 + 1) * y0 + (t3 - 2 * t2 + t) * h * m[i] + (-2 * t3 + 3 * t2) * y1 + (t3 - t2) * h * m[i + 1]
            return y.coerceIn(0f, 1f)
        }

        /** A channel's curve sampled at [count] evenly spaced inputs (for drawing it). */
        fun sample(points: List<CurvePoint>, count: Int): FloatArray {
            val m = tangents(points)
            return FloatArray(count) { evaluate(points, it / (count - 1f), m) }
        }

        /** Index of the point within [radius] of ([x], [y]), the closest one, or -1. */
        fun nearest(points: List<CurvePoint>, x: Float, y: Float, radius: Float): Int {
            var best = -1
            var bestD = radius * radius
            points.forEachIndexed { i, p ->
                val d = (p.x - x) * (p.x - x) + (p.y - y) * (p.y - y)
                if (d <= bestD) {
                    best = i
                    bestD = d
                }
            }
            return best
        }

        /** [points] with a new point at ([x], [y]) and its index, or null when full or too close to another point. */
        fun add(points: List<CurvePoint>, x: Float, y: Float): Pair<List<CurvePoint>, Int>? {
            if (points.size >= MAX_POINTS) return null
            val px = snap(x.coerceIn(0f, 1f))
            if (points.any { abs(it.x - px) < MIN_GAP }) return null
            val index = points.indexOfFirst { it.x > px }
            if (index <= 0) return null // outside the first and last point: move those instead
            return points.toMutableList().apply { add(index, CurvePoint(px, snap(y.coerceIn(0f, 1f)))) } to index
        }

        /** [points] with point [i] moved to ([x], [y]), kept between its neighbours. */
        fun move(points: List<CurvePoint>, i: Int, x: Float, y: Float): List<CurvePoint> {
            val lo = if (i > 0) points[i - 1].x + MIN_GAP else 0f
            val hi = if (i < points.size - 1) points[i + 1].x - MIN_GAP else 1f
            return points.toMutableList().apply { this[i] = CurvePoint(snap(x).coerceIn(lo, hi), snap(y.coerceIn(0f, 1f))) }
        }

        /** [points] without point [i]; the first and last point stay (they can only be moved). */
        fun remove(points: List<CurvePoint>, i: Int): List<CurvePoint> =
            if (i <= 0 || i >= points.size - 1) points else points.filterIndexed { k, _ -> k != i }

        /**
         * Curves from a preset file: each channel a list of [x, y] pairs. Channels that are missing or malformed
         * are the identity; null when [value] isn't an object at all.
         */
        fun parse(value: Any?): ToneCurves? {
            val o = value as? JSONObject ?: return null
            fun channel(c: CurveChannel): List<CurvePoint> {
                val a = o.optJSONArray(c.key) ?: return IDENTITY
                if (a.length() < 2 || a.length() > MAX_POINTS) return IDENTITY
                val points = ArrayList<CurvePoint>()
                for (i in 0 until a.length()) {
                    val p = a.optJSONArray(i) ?: return IDENTITY
                    if (p.length() != 2) return IDENTITY
                    val x = (p.opt(0) as? Number)?.toFloat()?.takeIf { it.isFinite() } ?: return IDENTITY
                    val y = (p.opt(1) as? Number)?.toFloat()?.takeIf { it.isFinite() } ?: return IDENTITY
                    val cx = x.coerceIn(0f, 1f)
                    if (points.isNotEmpty() && cx <= points.last().x) return IDENTITY
                    points += CurvePoint(cx, y.coerceIn(0f, 1f))
                }
                return points
            }
            return ToneCurves(channel(CurveChannel.RGB), channel(CurveChannel.RED), channel(CurveChannel.GREEN), channel(CurveChannel.BLUE))
        }
    }
}
