package app.rawboy.export

import android.content.Context

enum class ExportFormat(val extension: String, val mimeType: String) {
    JPEG("jpg", "image/jpeg"),
    TIFF("tif", "image/tiff"),
}

/**
 * JPEG encoders. SYSTEM is Android's (libjpeg-turbo via Bitmap.compress, 4:2:0 chroma, 8-bit input).
 * JPEGLI is Google's jpegli: 4:4:4, 16-bit input, adaptive quantisation; slower, better per byte.
 */
enum class JpegEncoder { SYSTEM, JPEGLI }

data class ExportSettings(
    val format: ExportFormat = ExportFormat.JPEG,
    val jpegQuality: Int = 92,   // 1..100
    val tiffBits: Int = 16,      // 8 or 16
    val jpegEncoder: JpegEncoder = JpegEncoder.SYSTEM,
    /** Long side of the saved image in pixels, area-downscaled from full resolution; null = full size. */
    val maxLongSide: Int? = null,
)

object ExportSettingsStore {
    private const val PREFS = "export"

    fun load(context: Context): ExportSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val defaults = ExportSettings()
        return ExportSettings(
            format = runCatching { ExportFormat.valueOf(p.getString("format", null)!!) }.getOrDefault(defaults.format),
            jpegQuality = p.getInt("jpegQuality", defaults.jpegQuality).coerceIn(1, 100),
            tiffBits = p.getInt("tiffBits", defaults.tiffBits).let { if (it == 8) 8 else 16 },
        )
    }

    fun save(context: Context, settings: ExportSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("format", settings.format.name)
            .putInt("jpegQuality", settings.jpegQuality)
            .putInt("tiffBits", settings.tiffBits)
            .apply()
    }
}
