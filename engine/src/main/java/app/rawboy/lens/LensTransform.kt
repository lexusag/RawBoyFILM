package app.rawboy.lens

import app.rawboy.color.Mat3
import app.rawboy.develop.DevelopParams
import app.rawboy.develop.crop
import app.rawboy.gl.PixelRect
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Lens geometry for one image and one set of slider values: where each output
 * pixel samples the decoded image, and the gains to apply there.
 *
 * Output pixel p (full resolution, after rotation and crop) maps to its source by:
 *   0. add the user crop's origin (the crop only picks a window of the finished frame), then undo
 *      rotation (90 degree turns + straighten), with auto crop for the straighten angle, and the flips
 *   1. auto scale around the image centre (crop to valid pixels, like Lightroom's Constrain Crop)
 *   2. manual distortion (radial, around the image centre)
 *   3. embedded DNG WarpRectilinear, in sensor coordinates, per colour plane
 *
 * Lens correction always works on the whole frame: the auto scale, distortion, warp and vignettes use the
 * full image and sensor, never the user crop, so a cropped pixel equals the uncropped render's pixel.
 *
 * The GLSL in Shaders.kt mirrors this exactly; Kotlin uses it to size tiles and the auto scale.
 * [knownZoom] reuses the auto scale of a transform that differs only in crop (it doesn't depend on it).
 */
class LensTransform(val info: LensInfo, params: DevelopParams, knownZoom: Double? = null) {
    val width = info.width
    val height = info.height
    val centerX = width / 2.0
    val centerY = height / 2.0
    val halfDiagonal = hypot(width / 2.0, height / 2.0)

    /** Clockwise quarter turns, 0..3. */
    val quarterTurns = ((params.rotation % 4) + 4) % 4
    /** The photo is mirrored left-right / top-bottom (display orientation, before the turns). */
    val flipHorizontal = params.flipHorizontal
    val flipVertical = params.flipVertical
    /** The rotated + straightened frame, before the user crop. */
    val frameWidth = if (quarterTurns % 2 == 1) height else width
    val frameHeight = if (quarterTurns % 2 == 1) width else height

    /** User crop in frame pixels; the whole frame when there is none. */
    val cropRect: PixelRect = cropPixels(params)
    val outputWidth = cropRect.width
    val outputHeight = cropRect.height

    private val straightenRadians = Math.toRadians(params.straighten.toDouble().coerceIn(-45.0, 45.0))

    /** Output pixel -> image pixel (display orientation), row-major 3x3 affine. */
    val outToImage: DoubleArray = rotationMatrix()

    private val useProfile = params.lensProfile && info.hasProfile

    /** k in src_r = r * (1 - k r^2); +100 on the slider removes strong barrel distortion. */
    val manualK = params.lensDistortion / 100.0 * 0.25

    val warp: LensInfo.Warp? = info.warp.takeIf { useProfile }
    val vignette: LensInfo.RadialVignette? = info.vignette.takeIf { useProfile }

    /** The decode applied the shading gain maps; with the profile off, take the luminance part back out. */
    val undoShading: Boolean = !params.lensProfile && info.shading != null

    val manualVignette = params.vignetteAmount / 100.0
    val manualVignetteMidpoint = params.vignetteMidpoint / 100.0

    /** Oriented pixel -> sensor pixel (row-major 3x3 affine), and back. */
    val toSensor: DoubleArray = orientationMatrix(info.orientation, info.sensorWidth.toDouble(), info.sensorHeight.toDouble())
    val fromSensor: DoubleArray = Mat3.inverse(toSensor)

    val warpCenterX = (warp?.centerX ?: 0.5) * info.sensorWidth
    val warpCenterY = (warp?.centerY ?: 0.5) * info.sensorHeight
    val warpNorm = maxCornerDistance(warpCenterX, warpCenterY)
    val vignetteCenterX = (vignette?.centerX ?: 0.5) * info.sensorWidth
    val vignetteCenterY = (vignette?.centerY ?: 0.5) * info.sensorHeight
    val vignetteNorm = maxCornerDistance(vignetteCenterX, vignetteCenterY)

    /** Planes with distinct warp coefficients (3 = lateral chromatic aberration correction). */
    val warpPlanes = warp?.planes ?: 1

    val isGeometric: Boolean get() = warp != null || manualK != 0.0

    /** Output scale >= the value at which every output pixel has real image data. */
    val zoom: Double = knownZoom ?: if (isGeometric) autoZoom() else 1.0

    /** Source position (image, display orientation) of output pixel (x, y) for colour plane 0..2. */
    fun source(x: Double, y: Double, plane: Int = 1): DoubleArray {
        val m = outToImage
        return lensSource(m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5], plane, zoom)
    }

    /**
     * Output pixel showing source position (x, y) (image, display orientation): the inverse of [source], for drawing
     * image-space shapes (mask gradients) over the preview. Newton steps from the lens-free guess, keeping the best
     * estimate: far outside the photo (overlay handles) the distortion polynomial can fold.
     */
    fun output(x: Double, y: Double): DoubleArray {
        val inv = Mat3.inverse(outToImage)
        var ox = inv[0] * x + inv[1] * y + inv[2]
        var oy = inv[3] * x + inv[4] * y + inv[5]
        val h = 0.5
        var best = doubleArrayOf(ox, oy)
        var bestErr = Double.POSITIVE_INFINITY
        for (i in 0 until 6) {
            val s = source(ox, oy)
            val ex = s[0] - x
            val ey = s[1] - y
            val err = kotlin.math.abs(ex) + kotlin.math.abs(ey)
            if (!(err < bestErr)) break
            best = doubleArrayOf(ox, oy)
            bestErr = err
            if (err < 0.02) break
            val sx = source(ox + h, oy)
            val sy = source(ox, oy + h)
            val a = (sx[0] - s[0]) / h
            val b = (sy[0] - s[0]) / h
            val c = (sx[1] - s[1]) / h
            val d = (sy[1] - s[1]) / h
            val det = a * d - b * c
            if (kotlin.math.abs(det) < 1e-9) break
            ox -= (d * ex - b * ey) / det
            oy -= (a * ey - c * ex) / det
        }
        return best
    }

    /** Steps 1-3 for an image position (x, y). */
    private fun lensSource(x: Double, y: Double, plane: Int, zoomOverride: Double): DoubleArray {
        // 1-2: scale and manual radial distortion around the image centre.
        var dx = (x - centerX) / zoomOverride
        var dy = (y - centerY) / zoomOverride
        val rn2 = (dx * dx + dy * dy) / (halfDiagonal * halfDiagonal)
        val f = 1.0 - manualK * rn2
        dx *= f
        dy *= f
        val qx = centerX + dx
        val qy = centerY + dy
        val w = warp ?: return doubleArrayOf(qx, qy)

        // 3: embedded warp in sensor space.
        val sx = toSensor[0] * qx + toSensor[1] * qy + toSensor[2]
        val sy = toSensor[3] * qx + toSensor[4] * qy + toSensor[5]
        val c = w.coefficients
        val o = (if (w.planes == 3) plane else 0) * 6
        val ux = (sx - warpCenterX) / warpNorm
        val uy = (sy - warpCenterY) / warpNorm
        val r2 = ux * ux + uy * uy
        val radial = c[o] + r2 * (c[o + 1] + r2 * (c[o + 2] + r2 * c[o + 3]))
        val tx = c[o + 4] * 2 * ux * uy + c[o + 5] * (r2 + 2 * ux * ux)
        val ty = c[o + 4] * (r2 + 2 * uy * uy) + c[o + 5] * 2 * ux * uy
        val srcX = warpCenterX + warpNorm * (ux * radial + tx)
        val srcY = warpCenterY + warpNorm * (uy * radial + ty)
        return doubleArrayOf(
            fromSensor[0] * srcX + fromSensor[1] * srcY + fromSensor[2],
            fromSensor[3] * srcX + fromSensor[4] * srcY + fromSensor[5],
        )
    }

    /** Source pixels needed to render [out], plus [margin], clamped to the image. */
    fun sourceBounds(out: PixelRect, margin: Int): PixelRect {
        if (!isGeometric && zoom == 1.0 && quarterTurns == 0 && !flipHorizontal && !flipVertical && straightenRadians == 0.0 && cropRect.x == 0 && cropRect.y == 0) {
            return out.expand(margin, width, height)
        }
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        val steps = 24
        for (plane in planesToCheck()) {
            for (i in 0..steps) {
                val t = i.toDouble() / steps
                val xs = out.x + t * out.width
                val ys = out.y + t * out.height
                for ((px, py) in listOf(
                    xs to out.y.toDouble(), xs to out.bottom.toDouble(),
                    out.x.toDouble() to ys, out.right.toDouble() to ys,
                )) {
                    val s = source(px, py, plane)
                    minX = min(minX, s[0]); maxX = max(maxX, s[0])
                    minY = min(minY, s[1]); maxY = max(maxY, s[1])
                }
            }
        }
        val x0 = (floor(minX).toInt() - margin).coerceIn(0, width - 1)
        val y0 = (floor(minY).toInt() - margin).coerceIn(0, height - 1)
        val x1 = (kotlin.math.ceil(maxX).toInt() + margin).coerceIn(x0 + 1, width)
        val y1 = (kotlin.math.ceil(maxY).toInt() + margin).coerceIn(y0 + 1, height)
        return PixelRect(x0, y0, x1 - x0, y1 - y0)
    }

    private fun planesToCheck() = if (warpPlanes == 3) listOf(0, 1, 2) else listOf(1)

    private fun autoZoom(): Double {
        fun inside(z: Double): Boolean {
            val steps = 48
            for (plane in planesToCheck()) {
                for (i in 0..steps) {
                    val t = i.toDouble() / steps
                    for ((px, py) in listOf(
                        t * width to 0.0, t * width to height.toDouble(),
                        0.0 to t * height, width.toDouble() to t * height,
                    )) {
                        val s = lensSource(px, py, plane, z)
                        if (s[0] < -0.5 || s[1] < -0.5 || s[0] > width + 0.5 || s[1] > height + 0.5) return false
                    }
                }
            }
            return true
        }
        var lo = 0.7
        var hi = 1.6
        if (!inside(hi)) return hi
        if (inside(lo)) return lo
        repeat(30) {
            val mid = (lo + hi) / 2
            if (inside(mid)) hi = mid else lo = mid
        }
        return hi
    }

    /** Crop edges rounded to frame pixels, at least 16 px and inside the frame. */
    private fun cropPixels(params: DevelopParams): PixelRect {
        val c = params.crop
        if (c.isFull) return PixelRect(0, 0, frameWidth, frameHeight)
        fun edges(a: Float, b: Float, size: Int): Pair<Int, Int> {
            val minSize = min(16, size)
            val lo = Math.round(a.coerceIn(0f, 1f) * size).coerceIn(0, size - minSize)
            val hi = Math.round(b.coerceIn(0f, 1f) * size).coerceIn(lo + minSize, size)
            return lo to hi
        }
        val (x0, x1) = edges(c.left, c.right, frameWidth)
        val (y0, y1) = edges(c.top, c.bottom, frameHeight)
        return PixelRect(x0, y0, x1 - x0, y1 - y0)
    }

    /**
     * image = c_img + F R(-90k) R(-theta) (out + crop - c_frame) / s, in y-down coordinates where
     * positive angles turn the picture clockwise; F mirrors the image about its centre (the flips). s crops the straightened frame to the
     * largest same-aspect rectangle inside the image; it depends on the frame, not the user crop.
     */
    private fun rotationMatrix(): DoubleArray {
        val t = straightenRadians
        val c = kotlin.math.cos(t)
        val sn = kotlin.math.sin(t)
        val ow = frameWidth.toDouble()
        val oh = frameHeight.toDouble()
        val s = c + max(ow / oh, oh / ow) * kotlin.math.abs(sn)
        // R(-theta) = [[c, s], [-s, c]]
        val r = doubleArrayOf(c / s, sn / s, -sn / s, c / s)
        // R(-90 k): k=1 [[0,1],[-1,0]], k=2 [[-1,0],[0,-1]], k=3 [[0,-1],[1,0]]
        val q = when (quarterTurns) {
            1 -> doubleArrayOf(0.0, 1.0, -1.0, 0.0)
            2 -> doubleArrayOf(-1.0, 0.0, 0.0, -1.0)
            3 -> doubleArrayOf(0.0, -1.0, 1.0, 0.0)
            else -> doubleArrayOf(1.0, 0.0, 0.0, 1.0)
        }
        val fx = if (flipHorizontal) -1.0 else 1.0
        val fy = if (flipVertical) -1.0 else 1.0
        val a = fx * (q[0] * r[0] + q[1] * r[2])
        val b = fx * (q[0] * r[1] + q[1] * r[3])
        val d = fy * (q[2] * r[0] + q[3] * r[2])
        val e = fy * (q[2] * r[1] + q[3] * r[3])
        // Frame centre in output (cropped) pixels.
        val cox = ow / 2 - cropRect.x
        val coy = oh / 2 - cropRect.y
        return doubleArrayOf(
            a, b, centerX - (a * cox + b * coy),
            d, e, centerY - (d * cox + e * coy),
            0.0, 0.0, 1.0,
        )
    }

    private fun maxCornerDistance(cx: Double, cy: Double): Double {
        val w = info.sensorWidth.toDouble()
        val h = info.sensorHeight.toDouble()
        return maxOf(hypot(cx, cy), hypot(w - cx, cy), hypot(cx, h - cy), hypot(w - cx, h - cy))
    }

    companion object {
        /** Affine (continuous pixel coordinates) from display orientation to sensor orientation. */
        fun orientationMatrix(o: Int, w: Double, h: Double): DoubleArray = when (o) {
            2 -> doubleArrayOf(-1.0, 0.0, w, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            3 -> doubleArrayOf(-1.0, 0.0, w, 0.0, -1.0, h, 0.0, 0.0, 1.0)
            4 -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, h, 0.0, 0.0, 1.0)
            5 -> doubleArrayOf(0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
            6 -> doubleArrayOf(0.0, 1.0, 0.0, -1.0, 0.0, h, 0.0, 0.0, 1.0)
            7 -> doubleArrayOf(0.0, -1.0, w, -1.0, 0.0, h, 0.0, 0.0, 1.0)
            8 -> doubleArrayOf(0.0, -1.0, w, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
            else -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        }
    }
}

private fun PixelRect.expand(margin: Int, maxW: Int, maxH: Int): PixelRect {
    val x0 = (x - margin).coerceAtLeast(0)
    val y0 = (y - margin).coerceAtLeast(0)
    val x1 = (right + margin).coerceAtMost(maxW)
    val y1 = (bottom + margin).coerceAtMost(maxH)
    return PixelRect(x0, y0, x1 - x0, y1 - y0)
}
