package app.rawboy.develop

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * User crop: edges as fractions (0..1) of the rotated + straightened frame, i.e. the whole picture the
 * Crop & rotate tool shows. The crop is the last geometry step and never changes lens correction or the
 * straighten auto crop, which always work on the full frame.
 */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isFull: Boolean get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f
}

val DevelopParams.crop: CropRect get() = CropRect(cropLeft, cropTop, cropRight, cropBottom)

fun DevelopParams.withCrop(r: CropRect): DevelopParams =
    copy(cropLeft = r.left, cropTop = r.top, cropRight = r.right, cropBottom = r.bottom)

/** The same edit on the whole frame: what the Crop & rotate tool shows while it is open. */
fun DevelopParams.withoutCrop(): DevelopParams = withCrop(CropMath.FULL)

/** Crop aspect ratios offered by the tool, as long side : short side (orientation is chosen separately). */
enum class CropAspect(private val long: Int, private val short: Int) {
    FREE(0, 0),
    ORIGINAL(0, 0),
    SQUARE(1, 1),
    R5_4(5, 4),
    R4_3(4, 3),
    R3_2(3, 2),
    R16_9(16, 9),
    ;

    fun label(portrait: Boolean): String = when (this) {
        FREE -> "Free"
        ORIGINAL -> "Original"
        SQUARE -> "1:1"
        else -> if (portrait) "$short:$long" else "$long:$short"
    }

    /** Width / height in pixels, or null for free. */
    fun ratio(frameWidth: Int, frameHeight: Int, portrait: Boolean): Double? = when (this) {
        FREE -> null
        ORIGINAL -> {
            val a = frameWidth.toDouble() / frameHeight
            if (portrait) min(a, 1 / a) else max(a, 1 / a)
        }
        else -> if (portrait) short.toDouble() / long else long.toDouble() / short
    }
}

/** Pure crop geometry shared by the Pro UI (and mirrored in web's CropMath.ts). */
object CropMath {
    val FULL = CropRect(0f, 0f, 1f, 1f)

    /** Smallest crop side in frame pixels. */
    const val MIN_PIXELS = 64

    enum class Handle { MOVE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /** The crop after turning the picture by [quarterTurns] clockwise quarter turns. */
    fun rotate(r: CropRect, quarterTurns: Int): CropRect {
        var c = r
        repeat(((quarterTurns % 4) + 4) % 4) {
            // Clockwise: frame point (x, y) moves to (1 - y, x).
            c = CropRect(1f - c.bottom, c.left, 1f - c.top, c.right)
        }
        return c
    }

    /**
     * [p] with the picture as shown mirrored left-right ([vertical] false) or top-bottom. The flips are stored on the
     * unturned photo, so a sideways turn swaps the axis; the straighten angle and the crop mirror with the picture.
     */
    fun flip(p: DevelopParams, vertical: Boolean): DevelopParams {
        val f = if (vertical != (p.rotation % 2 != 0)) p.copy(flipVertical = !p.flipVertical) else p.copy(flipHorizontal = !p.flipHorizontal)
        val r = p.crop
        val crop = if (vertical) r.copy(top = 1f - r.bottom, bottom = 1f - r.top) else r.copy(left = 1f - r.right, right = 1f - r.left)
        return f.copy(straighten = if (p.straighten == 0f) 0f else -p.straighten).withCrop(crop)
    }

    /**
     * [r] reshaped to [ratio] (width / height in pixels) with about the same area, around the same
     * centre, inside the frame. Null ratio = free: [r] unchanged.
     */
    fun withAspect(r: CropRect, ratio: Double?, frameWidth: Int, frameHeight: Int): CropRect {
        if (ratio == null) return r
        val fw = frameWidth.toDouble()
        val fh = frameHeight.toDouble()
        val area = r.width * fw * r.height * fh
        var w = sqrt(area * ratio)
        var h = w / ratio
        val fit = min(1.0, min(fw / w, fh / h))
        w *= fit
        h *= fit
        val cx = ((r.left + r.right) / 2 * fw).coerceIn(w / 2, fw - w / 2)
        val cy = ((r.top + r.bottom) / 2 * fh).coerceIn(h / 2, fh - h / 2)
        return fromPixels(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, fw, fh)
    }

    /**
     * [start] after dragging [handle] by ([dx], [dy]) in frame fractions, kept inside the frame, at least
     * [MIN_PIXELS] wide and high, and at [ratio] (width / height in pixels) when not null.
     */
    fun drag(
        start: CropRect, handle: Handle, dx: Float, dy: Float,
        ratio: Double?, frameWidth: Int, frameHeight: Int,
    ): CropRect {
        val fw = frameWidth.toDouble()
        val fh = frameHeight.toDouble()
        val minW = min(MIN_PIXELS.toDouble(), fw)
        val minH = min(MIN_PIXELS.toDouble(), fh)
        val l = start.left * fw
        val t = start.top * fh
        val r = start.right * fw
        val b = start.bottom * fh
        val mx = dx * fw
        val my = dy * fh

        if (handle == Handle.MOVE) {
            val ox = mx.coerceIn(-l, fw - r)
            val oy = my.coerceIn(-t, fh - b)
            return fromPixels(l + ox, t + oy, r + ox, b + oy, fw, fh)
        }

        val movesLeft = handle == Handle.LEFT || handle == Handle.TOP_LEFT || handle == Handle.BOTTOM_LEFT
        val movesRight = handle == Handle.RIGHT || handle == Handle.TOP_RIGHT || handle == Handle.BOTTOM_RIGHT
        val movesTop = handle == Handle.TOP || handle == Handle.TOP_LEFT || handle == Handle.TOP_RIGHT
        val movesBottom = handle == Handle.BOTTOM || handle == Handle.BOTTOM_LEFT || handle == Handle.BOTTOM_RIGHT

        if (ratio == null) {
            val nl = if (movesLeft) (l + mx).coerceIn(0.0, r - minW) else l
            val nr = if (movesRight) (r + mx).coerceIn(l + minW, fw) else r
            val nt = if (movesTop) (t + my).coerceIn(0.0, b - minH) else t
            val nb = if (movesBottom) (b + my).coerceIn(t + minH, fh) else b
            return fromPixels(nl, nt, nr, nb, fw, fh)
        }

        val horizontal = movesLeft || movesRight
        val vertical = movesTop || movesBottom
        // Anchor: the opposite edge (or corner); a single edge keeps the other axis centred.
        val ax = if (movesLeft) r else if (movesRight) l else (l + r) / 2
        val ay = if (movesTop) b else if (movesBottom) t else (t + b) / 2
        val wantW = if (horizontal) abs((if (movesLeft) l + mx else r + mx) - ax) else 0.0
        val wantH = if (vertical) abs((if (movesTop) t + my else b + my) - ay) else 0.0
        var w = max(wantW, wantH * ratio)
        // Room around the anchor, per axis (both halves when that axis stays centred).
        val roomW = when {
            movesLeft -> ax
            movesRight -> fw - ax
            else -> 2 * min(ax, fw - ax)
        }
        val roomH = when {
            movesTop -> ay
            movesBottom -> fh - ay
            else -> 2 * min(ay, fh - ay)
        }
        w = min(w, min(roomW, roomH * ratio))
        w = max(w, max(minW, minH * ratio)).coerceAtMost(min(roomW, roomH * ratio))
        val h = w / ratio
        val nl = when {
            movesLeft -> ax - w
            movesRight -> ax
            else -> ax - w / 2
        }
        val nt = when {
            movesTop -> ay - h
            movesBottom -> ay
            else -> ay - h / 2
        }
        return fromPixels(nl, nt, nl + w, nt + h, fw, fh)
    }

    /**
     * The handle under frame point ([x], [y]) (fractions), with grab distances [tolX], [tolY] in fractions:
     * corners first, then edges, then inside = move. Null outside the crop.
     */
    fun handleAt(r: CropRect, x: Float, y: Float, tolX: Float, tolY: Float): Handle? {
        val nearL = abs(x - r.left) <= tolX
        val nearR = abs(x - r.right) <= tolX
        val nearT = abs(y - r.top) <= tolY
        val nearB = abs(y - r.bottom) <= tolY
        val inX = x >= r.left - tolX && x <= r.right + tolX
        val inY = y >= r.top - tolY && y <= r.bottom + tolY
        if (!inX || !inY) return null
        return when {
            nearT && nearL -> Handle.TOP_LEFT
            nearT && nearR -> Handle.TOP_RIGHT
            nearB && nearL -> Handle.BOTTOM_LEFT
            nearB && nearR -> Handle.BOTTOM_RIGHT
            nearL -> Handle.LEFT
            nearR -> Handle.RIGHT
            nearT -> Handle.TOP
            nearB -> Handle.BOTTOM
            else -> Handle.MOVE
        }
    }

    private fun fromPixels(l: Double, t: Double, r: Double, b: Double, fw: Double, fh: Double) = CropRect(
        (l / fw).toFloat().coerceIn(0f, 1f), (t / fh).toFloat().coerceIn(0f, 1f),
        (r / fw).toFloat().coerceIn(0f, 1f), (b / fh).toFloat().coerceIn(0f, 1f),
    )
}
