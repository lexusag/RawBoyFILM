package app.rawboy.auto

import android.content.Context
import android.net.Uri
import android.util.Log
import app.rawboy.auto.analysis.AutoTone
import app.rawboy.auto.analysis.MeterArea
import app.rawboy.auto.analysis.NoiseLevel
import app.rawboy.auto.analysis.SceneAnalyzer
import app.rawboy.auto.analysis.SceneStats
import app.rawboy.develop.DevelopParams
import app.rawboy.gl.BloomSources
import app.rawboy.gl.PreviewImage
import app.rawboy.lens.LensInfo
import app.rawboy.raw.RawImage
import app.rawboy.retouch.SkinMask
import app.rawboy.retouch.SkinSegmenter
import app.rawboy.session.PhotoSession

/** Everything learned from a photo once; enough to render it small and to export it. */
class Analysis(
    val defaults: DevelopParams,
    val autoBase: DevelopParams,
    val stats: SceneStats,
    val lens: LensInfo,
    val base: PreviewImage,
    val bloom: BloomSources,
    val skin: SkinMask?,
)

/** The automatic develop of one raw, shared by RawBoy Auto and RawBoy Camera. */
object AutoAnalysis {
    /** Tag of the per-photo stats log, used for recalibrating the auto develop. */
    const val TAG = "RawBoyAuto"

    /**
     * Decodes [uri], keeps its small whole-image copies and works out the auto develop.
     * Blocking and memory hungry (a full-resolution decode): call from a background thread,
     * one at a time.
     */
    fun analyze(context: Context, uri: Uri): Analysis {
        // The raw is closed before segmentation, which needs only the 512 px copy.
        val (copies, info) = RawImage.open(context, uri).use { img -> copiesOf(img) to Info.of(img, auto = true) }
        return analyze(context, copies.first, copies.second, info, uri.toString())
    }

    /**
     * The same for an already decoded [image] (kept open, e.g. to export it next). Blocking.
     * [noiseIsoScale]: see [AutoTone.develop]. [fallbackIso] is used when the file has no ISO (e.g. from the capture).
     * [detectSkin] false skips skin detection (face metering, skin retouch): the camera saw no face.
     * [meter]: see [SceneAnalyzer.analyze].
     */
    fun analyze(
        context: Context,
        image: RawImage,
        label: String,
        noiseIsoScale: Float = 1f,
        fallbackIso: Float = 0f,
        detectSkin: Boolean = true,
        meter: MeterArea? = null,
    ): Analysis {
        val t0 = System.nanoTime()
        val (base, bloom) = copiesOf(image)
        Log.d(TAG, "small copies: ${(System.nanoTime() - t0) / 1_000_000} ms")
        val info = Info.of(image).let { if (it.iso > 0f) it else Info(it.lens, fallbackIso, it.defaults) }
        return analyze(context, base, bloom, info, label, noiseIsoScale, detectSkin, meter)
    }

    /**
     * [cameraMetered]: see [AutoTone.cameraMetered]; [noise]: the measured noise. Only Auto uses them (Camera meters
     * the scene live itself and keeps its ISO-based denoise, which has no time cost per shot).
     */
    private class Info(
        val lens: LensInfo,
        val iso: Float,
        val defaults: DevelopParams,
        val cameraMetered: Boolean = false,
        val noise: NoiseLevel = NoiseLevel.NONE,
    ) {
        companion object {
            fun of(img: RawImage, auto: Boolean = false) = Info(
                img.lens, img.iso, PhotoSession.asShotDefaults(img),
                auto && AutoTone.cameraMetered(img.make, img.baselineExposure),
                if (auto) img.measureNoise() else NoiseLevel.NONE,
            )
        }
    }

    /**
     * The auto develop alone, for a photo already open elsewhere (RawBoy Pro's Auto button): [base] is its 512 px copy,
     * [skin] its skin mask (null: no face metering), [noise] its measured noise. Fast; no decode or segmentation.
     */
    fun autoBase(
        base: PreviewImage,
        skin: SkinMask?,
        iso: Float,
        defaults: DevelopParams,
        cameraMetered: Boolean,
        noise: NoiseLevel = NoiseLevel.NONE,
    ): DevelopParams = develop(base, skin, iso, defaults, 1f, cameraMetered, null, noise).second

    private fun develop(
        base: PreviewImage,
        skin: SkinMask?,
        iso: Float,
        defaults: DevelopParams,
        noiseIsoScale: Float,
        cameraMetered: Boolean,
        meter: MeterArea?,
        noise: NoiseLevel = NoiseLevel.NONE,
    ): Pair<SceneStats, DevelopParams> {
        val stats = SceneAnalyzer.analyze(base, skin, iso, meter)
        return stats to AutoTone.develop(stats, defaults, noiseIsoScale, cameraMetered, noise)
    }

    private fun copiesOf(img: RawImage) = img.createPreview(PhotoSession.BASE_MAX_DIMENSION) to PhotoSession.bloomSourcesOf(img)

    private fun analyze(
        context: Context,
        base: PreviewImage,
        bloom: BloomSources,
        info: Info,
        label: String,
        noiseIsoScale: Float = 1f,
        detectSkin: Boolean = true,
        meter: MeterArea? = null,
    ): Analysis {
        val t0 = System.nanoTime()
        val skin = if (!detectSkin) null else try {
            SkinSegmenter.segment(context, base, base.fullWidth, base.fullHeight)
        } catch (e: Throwable) {
            null // MediaPipe can fail with Errors too; the photo is just treated as having no face
        }
        Log.d(TAG, "skin detection: ${(System.nanoTime() - t0) / 1_000_000} ms")
        val defaults = info.defaults
        val (stats, auto) = develop(base, skin, info.iso, defaults, noiseIsoScale, info.cameraMetered, meter, info.noise)
        Log.d(TAG, "analysis $label: key=${stats.key} p1=${stats.p1} p5=${stats.p5} p50=${stats.p50} p98=${stats.p98} " +
            "p99=${stats.p99} clipped=${stats.clipped} chroma=${stats.chroma} face=${stats.faceArea} " +
            "skin=${stats.skinLuminance} iso=${info.iso} cameraMetered=${info.cameraMetered} noise=${info.noise.luma}/${info.noise.chroma} " +
            "-> exposure=${auto.exposure} hl=${auto.highlights} denoise=${auto.denoise}/${auto.colorDenoise} " +
            "sh=${auto.shadows} contrast=${auto.contrast} wb=${defaults.temperature.toInt()}K/${defaults.tint.toInt()} -> ${auto.temperature.toInt()}K/${auto.tint.toInt()}")
        return Analysis(defaults, auto, stats, info.lens, base, bloom, skin)
    }
}
