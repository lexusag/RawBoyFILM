package app.rawboy.retouch

import android.content.Context
import android.graphics.Bitmap
import android.util.Half
import android.util.Log
import app.rawboy.gl.PreviewImage
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Where the skin is, as a 0..1 map over the whole image (display orientation),
 * plus the smoothing scale for the faces in it.
 */
class SkinMask(
    val width: Int,
    val height: Int,
    val data: FloatBuffer,
    /** Edge-aware smoothing sigma in full-resolution pixels, scaled to the face size. */
    val sigmaPx: Float,
    val hasSkin: Boolean,
    /** Estimated face diameter in full-resolution pixels (0 if no face). */
    val faceDiameterPx: Float,
)

/**
 * Finds skin with MediaPipe's selfie multiclass segmentation model (Apache 2.0):
 * categories 0 background, 1 hair, 2 body-skin, 3 face-skin, 4 clothes, 5 others.
 */
object SkinSegmenter {
    private const val TAG = "RawBoy"
    private const val MODEL = "models/selfie_multiclass_256x256.tflite"
    private const val LANDMARK_MODEL = "models/face_landmarker.task"

    // MediaPipe face mesh landmark indices (face_mesh_connections) for the features
    // that must never be smoothed or "healed": they are dark/pink shapes inside skin.
    private val RIGHT_EYE = intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 246, 161, 160, 159, 158, 157, 173)
    private val LEFT_EYE = intArrayOf(263, 249, 390, 373, 374, 380, 381, 382, 362, 466, 388, 387, 386, 385, 384, 398)
    private val RIGHT_BROW = intArrayOf(46, 53, 52, 65, 55, 70, 63, 105, 66, 107)
    private val LEFT_BROW = intArrayOf(276, 283, 282, 295, 285, 300, 293, 334, 296, 336)
    private val LIPS = intArrayOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185)
    private val NOSTRILS = intArrayOf(64, 98, 97, 2, 326, 327, 294, 240, 460, 75, 305, 79, 309, 166, 392)

    /** Feature outlines and how far to grow them (eye corners and tear ducts sit just outside the lid line). */
    private val FEATURES = listOf(
        RIGHT_EYE to 1.6f, LEFT_EYE to 1.6f, RIGHT_BROW to 1.25f, LEFT_BROW to 1.25f, LIPS to 1.2f, NOSTRILS to 1.35f,
    )
    private const val BODY_SKIN = 2
    private const val FACE_SKIN = 3

    /**
     * Both models stay loaded after first use (loading them took a few hundred ms per photo; together
     * they hold ~25 MB). MediaPipe tasks aren't thread-safe, so [segment] runs one call at a time.
     */
    private var segmenter: ImageSegmenter? = null
    private var landmarker: FaceLandmarker? = null

    private fun segmenter(context: Context): ImageSegmenter = segmenter ?: ImageSegmenter.createFromOptions(
        context.applicationContext,
        ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.IMAGE)
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .build(),
    ).also { segmenter = it }

    private fun landmarker(context: Context): FaceLandmarker = landmarker ?: FaceLandmarker.createFromOptions(
        context.applicationContext,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(LANDMARK_MODEL).setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(4)
            .setMinFaceDetectionConfidence(0.3f)
            .build(),
    ).also { landmarker = it }

    /** [image]: the small linear Rec.2020 copy of the photo; [fullWidth]/[fullHeight]: full-resolution size. */
    @Synchronized
    fun segment(context: Context, image: PreviewImage, fullWidth: Int, fullHeight: Int): SkinMask {
        val tStart = System.nanoTime()
        val w = image.width
        val h = image.height
        val (bitmap, gray) = toDisplayBitmap(image)

        val skin = FloatArray(w * h)
        var faceArea = 0.0
        var skinArea = 0.0
        segmenter(context).let { segmenter ->
            val result = segmenter.segment(BitmapImageBuilder(bitmap).build())
            val masks = result.confidenceMasks().orElse(null) ?: return empty(w, h)
            fun read(index: Int): FloatArray {
                val mask = masks[index]
                val buffer = ByteBufferExtractor.extract(mask).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val out = FloatArray(mask.width * mask.height)
                buffer.position(0)
                buffer.get(out)
                return if (mask.width == w && mask.height == h) out else resize(out, mask.width, mask.height, w, h)
            }
            val face = read(FACE_SKIN)
            val body = read(BODY_SKIN)
            // The segmenter is kept, so free this result's native masks now.
            masks.forEach { it.close() }
            for (i in skin.indices) {
                skin[i] = (face[i] + body[i]).coerceIn(0f, 1f)
                // Count only confident face pixels: summed, the model's faint confidence everywhere
                // else adds up to a "face" of about 2% of every frame.
                if (face[i] > 0.5f) faceArea += 1.0
                skinArea += skin[i]
            }
        }

        val tSeg = System.nanoTime()
        // Snap the low-resolution mask to edges in the photo (hairline, brows, jaw).
        val refined = guidedFilter(gray, skin, w, h, radius = 3, eps = 1e-3f)
        val tGuided = System.nanoTime()

        // Cut eyes, brows, lips and nostrils out of the mask: the segmentation model
        // labels them face skin, but they must not be smoothed or healed.
        excludeFacialFeatures(context, bitmap, refined, w, h)
        bitmap.recycle()
        Log.d(TAG, "skin: segmentation ${(tSeg - tStart) / 1_000_000} ms, guided filter ${(tGuided - tSeg) / 1_000_000} ms, landmarks ${(System.nanoTime() - tGuided) / 1_000_000} ms")

        // Smoothing scale from the face size: ~1.2% of the face diameter.
        val scale = fullWidth.toDouble() / w
        val longEdge = max(fullWidth, fullHeight).toDouble()
        val faceDiameter = if (faceArea > 4) 2 * sqrt(faceArea / Math.PI) * scale else 0.0
        val sigma = if (faceArea > 4) {
            (0.012 * faceDiameter).coerceIn(3.0, 40.0)
        } else {
            (0.002 * longEdge).coerceIn(3.0, 40.0)
        }
        val buffer = ByteBuffer.allocateDirect(refined.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(refined).position(0)
        return SkinMask(w, h, buffer, sigma.toFloat(), hasSkin = skinArea > 0.002 * w * h, faceDiameter.toFloat())
    }

    /**
     * Zeroes the mask inside each facial feature (polygon grown around its centre),
     * with a soft edge. Faces the landmarker can't find are left as they are.
     */
    private fun excludeFacialFeatures(context: Context, image: Bitmap, mask: FloatArray, w: Int, h: Int) {
        val faces = landmarker(context).detect(BitmapImageBuilder(image).build()).faceLandmarks()
        Log.d(TAG, "Face landmarker: ${faces.size} face(s); protecting eyes, brows, lips, nostrils")
        if (faces.isEmpty()) return
        val keep = FloatArray(w * h) { 1f }
        for (face in faces) {
            for ((indices, grow) in FEATURES) {
                val xs = FloatArray(indices.size) { face[indices[it]].x() * w }
                val ys = FloatArray(indices.size) { face[indices[it]].y() * h }
                val cx = xs.average().toFloat()
                val cy = ys.average().toFloat()
                for (i in xs.indices) {
                    xs[i] = cx + (xs[i] - cx) * grow
                    ys[i] = cy + (ys[i] - cy) * grow
                }
                fillConvexHull(xs, ys, keep, w, h)
            }
        }
        // Soften the cut so smoothing fades out instead of stopping at a hard line.
        val soft = box(keep, w, h, 1)
        for (i in mask.indices) mask[i] *= soft[i]
    }

    /** Sets [keep] to 0 inside the convex hull of the points. */
    private fun fillConvexHull(xs: FloatArray, ys: FloatArray, keep: FloatArray, w: Int, h: Int) {
        // Monotone chain hull.
        val pts = xs.indices.sortedWith(compareBy({ xs[it] }, { ys[it] }))
        fun cross(o: Int, a: Int, b: Int) = (xs[a] - xs[o]) * (ys[b] - ys[o]) - (ys[a] - ys[o]) * (xs[b] - xs[o])
        val hull = ArrayList<Int>()
        for (p in pts) {
            while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0) hull.removeAt(hull.size - 1)
            hull.add(p)
        }
        val lower = hull.size + 1
        for (p in pts.reversed()) {
            while (hull.size >= lower && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0) hull.removeAt(hull.size - 1)
            hull.add(p)
        }
        hull.removeAt(hull.size - 1)
        if (hull.size < 3) return
        val minX = hull.minOf { xs[it] }.toInt().coerceIn(0, w - 1)
        val maxX = hull.maxOf { xs[it] }.toInt().coerceIn(0, w - 1)
        val minY = hull.minOf { ys[it] }.toInt().coerceIn(0, h - 1)
        val maxY = hull.maxOf { ys[it] }.toInt().coerceIn(0, h - 1)
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val px = x + 0.5f
                val py = y + 0.5f
                // Inside a convex polygon = on the same side of every edge (either winding).
                var pos = false
                var neg = false
                for (k in hull.indices) {
                    val a = hull[k]
                    val b = hull[(k + 1) % hull.size]
                    val c = (xs[b] - xs[a]) * (py - ys[a]) - (ys[b] - ys[a]) * (px - xs[a])
                    if (c > 0) pos = true else if (c < 0) neg = true
                }
                if (!(pos && neg)) keep[y * w + x] = 0f
            }
        }
    }

    private fun empty(w: Int, h: Int) = SkinMask(
        w, h, ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(), 3f, false, 0f,
    )

    /** Linear Rec.2020 half floats -> an ordinary-looking sRGB bitmap for the model, plus its luminance. */
    private fun toDisplayBitmap(image: PreviewImage): Pair<Bitmap, FloatArray> {
        val n = image.width * image.height
        val halves = image.pixels.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val rgb = FloatArray(n * 3)
        var logSum = 0.0
        for (i in 0 until n) {
            val r = Half.toFloat(halves.get(i * 3))
            val g = Half.toFloat(halves.get(i * 3 + 1))
            val b = Half.toFloat(halves.get(i * 3 + 2))
            // Rec.2020 -> Rec.709/sRGB primaries
            rgb[i * 3] = 1.6605f * r - 0.5876f * g - 0.0728f * b
            rgb[i * 3 + 1] = -0.1246f * r + 1.1329f * g - 0.0083f * b
            rgb[i * 3 + 2] = -0.0182f * r - 0.1006f * g + 1.1187f * b
            logSum += ln(max(0.2627 * r + 0.6780 * g + 0.0593 * b, 1e-5))
        }
        // Auto exposure: put the log-average at middle grey, then a soft shoulder.
        val k = (0.18 / exp(logSum / n)).toFloat()
        val pixels = IntArray(n)
        val gray = FloatArray(n)
        for (i in 0 until n) {
            var y = 0f
            val c = IntArray(3) { ch ->
                val v = max(rgb[i * 3 + ch], 0f) * k
                val t = (v / (1f + v)) * 1.6f
                val e = if (t <= 0.0031308f) t * 12.92f else 1.055f * t.toDouble().pow(1 / 2.4).toFloat() - 0.055f
                val e8 = (e.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                y += e8 * floatArrayOf(0.2126f, 0.7152f, 0.0722f)[ch]
                e8
            }
            pixels[i] = (0xff shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
            gray[i] = y / 255f
        }
        val bitmap = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        return bitmap to gray
    }

    private fun resize(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val out = FloatArray(dw * dh)
        for (y in 0 until dh) {
            val fy = ((y + 0.5f) * sh / dh - 0.5f).coerceIn(0f, sh - 1f)
            val y0 = fy.toInt().coerceAtMost(sh - 2).coerceAtLeast(0)
            val ty = fy - y0
            for (x in 0 until dw) {
                val fx = ((x + 0.5f) * sw / dw - 0.5f).coerceIn(0f, sw - 1f)
                val x0 = fx.toInt().coerceAtMost(sw - 2).coerceAtLeast(0)
                val tx = fx - x0
                val y1 = (y0 + 1).coerceAtMost(sh - 1)
                val x1 = (x0 + 1).coerceAtMost(sw - 1)
                val top = src[y0 * sw + x0] * (1 - tx) + src[y0 * sw + x1] * tx
                val bottom = src[y1 * sw + x0] * (1 - tx) + src[y1 * sw + x1] * tx
                out[y * dw + x] = top * (1 - ty) + bottom * ty
            }
        }
        return out
    }

    /** Guided filter (He et al.): edge-preserving refinement of [p] using guide [g]. */
    private fun guidedFilter(g: FloatArray, p: FloatArray, w: Int, h: Int, radius: Int, eps: Float): FloatArray {
        val meanG = box(g, w, h, radius)
        val meanP = box(p, w, h, radius)
        val corrGP = box(FloatArray(g.size) { g[it] * p[it] }, w, h, radius)
        val corrGG = box(FloatArray(g.size) { g[it] * g[it] }, w, h, radius)
        val a = FloatArray(g.size)
        val b = FloatArray(g.size)
        for (i in g.indices) {
            val variance = corrGG[i] - meanG[i] * meanG[i]
            a[i] = (corrGP[i] - meanG[i] * meanP[i]) / (variance + eps)
            b[i] = meanP[i] - a[i] * meanG[i]
        }
        val meanA = box(a, w, h, radius)
        val meanB = box(b, w, h, radius)
        return FloatArray(g.size) { (meanA[it] * g[it] + meanB[it]).coerceIn(0f, 1f) }
    }

    private fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray = Filters.box(src, w, h, r)
}
