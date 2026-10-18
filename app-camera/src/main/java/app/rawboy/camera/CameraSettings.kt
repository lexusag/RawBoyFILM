package app.rawboy.camera

import android.content.Context
import app.rawboy.auto.looks.LookApplier
import app.rawboy.camera.capture.CameraController
import app.rawboy.camera.capture.FlashGuides
import app.rawboy.camera.capture.FlashMode

/** The look controls, camera choice and output settings. */
data class CameraSettings(
    val lookId: String = Edition.defaultLookId,
    val strength: Float = LookApplier.DEFAULT_STRENGTH,
    val brightness: Float = 0f,
    val warmth: Float = 0f,
    /** JPEG quality, [MIN_QUALITY]..100. */
    val jpegQuality: Int = 95,
    /** Keep the raw as DNG in Pictures/<Edition.FOLDER>/RAW. */
    val keepDng: Boolean = true,
    /** Save photos at [SMALL_LONG_SIDE] px on the long side instead of full size. */
    val smallExport: Boolean = true,
    /**
     * Keep the camera (facing, lens), look and sliders between launches. Off: each launch starts on the back
     * W lens with the default look and sliders. Output settings and calibration are always kept.
     */
    val rememberLast: Boolean = false,
    /** A live RGB histogram in a corner of the viewfinder. */
    val showHistogram: Boolean = false,
    /** Each shot (without flash) takes [BURST_FRAMES] raws and merges them into one: less noise. */
    val mergeBurst: Boolean = false,
    /**
     * Per camera ([lensKey]): gain from the linearised preview to raw units (1.0 = sensor clip), learned
     * from each shot (raw key / viewfinder key), so the viewfinder's auto exposure matches the final.
     */
    val previewScales: Map<String, Float> = emptyMap(),
    /**
     * Per camera ([lensKey]): the flash's guide (still gain at 1 m, see CameraController.flashExposure), learned from
     * each flash shot, so flash raws come out neither clipped nor dark.
     */
    val flashGuides: Map<String, Float> = emptyMap(),
    /** Selfie camera instead of the back cameras. */
    val front: Boolean = false,
    /** Lens label within the current side: UW, W, T, T2… */
    val lens: String = "W",
    /** Flash for the next shots (used only on lenses with a flash). */
    val flash: FlashMode = FlashMode.OFF,
) {
    /** The lens's key for per-lens values: the label, prefixed with "F" on the front side. */
    val lensKey: String get() = lensKey(front, lens)

    val previewScale: Float get() = previewScales[lensKey] ?: 1f

    /** The current lens's flash guides; the one for shots without a focus distance is kept under "<lens>?". */
    val flashGuide: FlashGuides get() = FlashGuides(
        flashGuides[lensKey] ?: CameraController.DEFAULT_FLASH_GUIDE,
        flashGuides[flashGuideKey(lensKey, distanceKnown = false)] ?: CameraController.DEFAULT_FLASH_GAIN_NO_DISTANCE,
    )

    /** The long side to save at, or null for full size. */
    val maxLongSide: Int? get() = if (smallExport) SMALL_LONG_SIDE else null

    /** Raws per shot: [BURST_FRAMES] with [mergeBurst], else 1. */
    val burstFrames: Int get() = if (mergeBurst) BURST_FRAMES else 1

    companion object {
        const val MIN_QUALITY = 70
        const val SMALL_LONG_SIDE = 3000
        /** Raws merged per shot with [mergeBurst]: noise about 1/sqrt of it where nothing moves (~2.8x less). */
        const val BURST_FRAMES = 8

        /** Range of the preview calibration; the Pixel's lenses need 0.5 (selfie) to ~9 (T3). */
        const val MIN_SCALE = 1f / 32
        const val MAX_SCALE = 32f

        fun lensKey(front: Boolean, lens: String) = if (front) "F$lens" else lens

        fun flashGuideKey(lensKey: String, distanceKnown: Boolean) = if (distanceKnown) lensKey else "$lensKey?"
    }
}

object CameraSettingsStore {
    private const val PREFS = "camera"

    /**
     * Calibration storage. "previewScales3": learned with the explicit sRGB preview curve and the still-exposure
     * prediction (CameraController.stillGain); earlier values were learned without them and are dropped.
     */
    private const val SCALES_KEY = "previewScales3"
    private const val FLASH_GUIDES_KEY = "flashGuides"

    fun load(context: Context): CameraSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = CameraSettings()
        val always = CameraSettings(
            jpegQuality = p.getInt("jpegQuality", d.jpegQuality).coerceIn(CameraSettings.MIN_QUALITY, 100),
            keepDng = p.getBoolean("keepDng", d.keepDng),
            smallExport = p.getBoolean("smallExport", d.smallExport),
            rememberLast = p.getBoolean("rememberLast", d.rememberLast),
            showHistogram = p.getBoolean("showHistogram", d.showHistogram),
            mergeBurst = p.getBoolean("mergeBurst", d.mergeBurst),
            previewScales = parseScales(p.getString(SCALES_KEY, null)),
            flashGuides = parseScales(p.getString(FLASH_GUIDES_KEY, null), min = 1e-2f, max = 1e4f),
            flash = runCatching { FlashMode.valueOf(p.getString("flash", null)!!) }.getOrDefault(d.flash),
        )
        if (!always.rememberLast) return always
        return always.copy(
            lookId = p.getString("look", d.lookId) ?: d.lookId,
            strength = p.getFloat("strength", d.strength).coerceIn(0f, 100f),
            brightness = p.getFloat("brightness", d.brightness).coerceIn(-100f, 100f),
            warmth = p.getFloat("warmth", d.warmth).coerceIn(-100f, 100f),
            front = p.getBoolean("front", d.front),
            lens = p.getString("lens", d.lens) ?: d.lens,
        )
    }

    /** Saves everything, so switching [CameraSettings.rememberLast] on later starts from the current state. */
    fun save(context: Context, s: CameraSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("look", s.lookId)
            .putFloat("strength", s.strength)
            .putFloat("brightness", s.brightness)
            .putFloat("warmth", s.warmth)
            .putInt("jpegQuality", s.jpegQuality)
            .putBoolean("keepDng", s.keepDng)
            .putBoolean("smallExport", s.smallExport)
            .putBoolean("rememberLast", s.rememberLast)
            .putBoolean("showHistogram", s.showHistogram)
            .putBoolean("mergeBurst", s.mergeBurst)
            .putBoolean("front", s.front)
            .putString("lens", s.lens)
            .putString("flash", s.flash.name)
            .putString(SCALES_KEY, s.previewScales.entries.joinToString(";") { "${it.key}=${it.value}" })
            .putString(FLASH_GUIDES_KEY, s.flashGuides.entries.joinToString(";") { "${it.key}=${it.value}" })
            .apply()
    }

    /** "W=1.1;T=0.95;FW=1.0" -> map; malformed entries are skipped. */
    private fun parseScales(text: String?, min: Float = CameraSettings.MIN_SCALE, max: Float = CameraSettings.MAX_SCALE): Map<String, Float> =
        text.orEmpty().split(';').mapNotNull { entry ->
            val (key, value) = entry.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            value.toFloatOrNull()?.takeIf { it > 0f }?.let { key to it.coerceIn(min, max) }
        }.toMap()
}
