package app.rawboy.raw

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Camera and exposure essentials of a photo (from the decoder's metadata), for the editor's info line.
 * NaN (or 0 for ISO) = unknown.
 */
data class ShotInfo(
    val camera: String,
    val exposureTime: Double,
    val fNumber: Double,
    val focalLength: Double,
    val focalLength35mm: Double,
    val iso: Double,
) {
    /** e.g. "GFX 50R · ISO 6400 · 1/60 s · f/2.8 · 63 mm (50 mm eq.)"; unknown values are left out. */
    fun line(): String = listOfNotNull(
        camera.trim().takeIf { it.isNotEmpty() },
        iso.takeIf { it > 0 }?.let { "ISO ${it.roundToInt()}" },
        exposureTime.takeIf { it.isFinite() && it > 0 }?.let(::formatShutter),
        fNumber.takeIf { it.isFinite() && it > 0 }?.let { "f/${trim(it)}" },
        focalLength.takeIf { it.isFinite() && it > 0 }?.let { f ->
            val eq = focalLength35mm.takeIf { it.isFinite() && it > 0 && abs(it - f) >= 1 }
            "${f.roundToInt()} mm" + (eq?.let { " (${it.roundToInt()} mm eq.)" } ?: "")
        },
    ).joinToString(" · ")

    companion object {
        /** 1/60 s below a second (rounded to a whole denominator), 2.5 s / 30 s above. */
        fun formatShutter(t: Double): String =
            if (t >= 0.95) "${trim(t)} s" else "1/${(1 / t).roundToInt()} s"

        /** One decimal, without a trailing ".0". */
        private fun trim(v: Double): String {
            val s = String.format(Locale.ROOT, "%.1f", v)
            return if (s.endsWith(".0")) s.dropLast(2) else s
        }
    }
}
