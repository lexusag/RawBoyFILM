package app.rawboy.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The white balance picker (the same cases as web's engine.test.ts and iOS's WhitePickerTests). */
class WhitePickerTest {
    /** The shader's white balance (ShaderParams.from) at [kelvin]/[tint] applied to [rgb]. */
    private fun developed(rgb: DoubleArray, asShotK: Double, asShotTint: Double, kelvin: Double, tint: Double): DoubleArray {
        val m = WhiteBalance.rebalanceMatrix(
            WhiteBalance.xyFromTempTint(asShotK, asShotTint), WhiteBalance.xyFromTempTint(kelvin, tint),
        )
        return Mat3.apply(m, rgb)
    }

    private fun patch(r: Float, g: Float, b: Float, n: Int = 16) = FloatArray(n * 3) { floatArrayOf(r, g, b)[it % 3] }

    @Test
    fun aGreyPatchKeepsTheAsShotWhite() {
        val pick = WhitePicker.pick(patch(0.18f, 0.18f, 0.18f), 5200f, 8f) as WhitePicker.Pick.White
        assertEquals(5200f, pick.kelvin, 2f)
        assertEquals(8f, pick.tint, 0.2f)
    }

    @Test
    fun aTintedPatchDevelopsNeutral() {
        for (rgb in listOf(doubleArrayOf(0.20, 0.17, 0.12), doubleArrayOf(0.10, 0.12, 0.16), doubleArrayOf(0.15, 0.17, 0.15))) {
            val pick = WhitePicker.pick(patch(rgb[0].toFloat(), rgb[1].toFloat(), rgb[2].toFloat()), 5000f, 0f)
                as WhitePicker.Pick.White
            val out = developed(rgb, 5000.0, 0.0, pick.kelvin.toDouble(), pick.tint.toDouble())
            assertEquals(1.0, out[0] / out[1], 0.003)
            assertEquals(1.0, out[2] / out[1], 0.003)
        }
    }

    @Test
    fun warmerPatchMeansAWarmerLight() {
        val pick = WhitePicker.pick(patch(0.20f, 0.17f, 0.12f), 5000f, 0f) as WhitePicker.Pick.White
        assertTrue(pick.kelvin < 5000f)
    }

    @Test
    fun clippedPixelsAreLeftOut() {
        val rgb = patch(0.18f, 0.18f, 0.18f, 4) + patch(1f, 0.6f, 0.2f, 12)
        val pick = WhitePicker.pick(rgb, 5200f, 0f) as WhitePicker.Pick.White
        assertEquals(5200f, pick.kelvin, 2f)
        assertEquals(WhitePicker.Pick.Clipped, WhitePicker.pick(patch(1f, 1f, 1f), 5200f, 0f))
    }

    @Test
    fun infraredCastsDevelopNeutral() {
        for (rgb in listOf(doubleArrayOf(0.3, 0.1, 0.02), doubleArrayOf(0.3, 0.12, 0.2), doubleArrayOf(0.2, 0.1, 0.25))) {
            val pick = WhitePicker.pick(patch(rgb[0].toFloat(), rgb[1].toFloat(), rgb[2].toFloat()), 5000f, 0f)
                as WhitePicker.Pick.White
            val out = developed(rgb, 5000.0, 0.0, pick.kelvin.toDouble(), pick.tint.toDouble())
            assertEquals(1.0, out[0] / out[1], 0.003)
            assertEquals(1.0, out[2] / out[1], 0.003)
        }
    }

    @Test
    fun locusBelow1667KJoinsKimsAndRoundTrips() {
        val a = WhiteBalance.planckianXY(1666.9)
        val b = WhiteBalance.planckianXY(1667.1)
        assertTrue(kotlin.math.hypot(a[0] - b[0], a[1] - b[1]) < 1e-4)
        for (k in listOf(1000.0, 1200.0, 1500.0)) {
            val xy = WhiteBalance.xyFromTempTint(k, -120.0)
            val tt = WhiteBalance.tempTintFromXY(xy[0], xy[1])
            assertEquals(k, tt[0], 0.5)
            assertEquals(-120.0, tt[1], 0.01)
        }
    }

    @Test
    fun blackIsNotNeutral() {
        assertEquals(WhitePicker.Pick.NotNeutral, WhitePicker.pick(patch(0f, 0f, 0f), 5200f, 0f))
    }

    @Test
    fun resultStaysInTheSliderRanges() {
        val pick = WhitePicker.pick(patch(0.30f, 0.10f, 0.02f), 5000f, 0f)
        if (pick is WhitePicker.Pick.White) {
            assertTrue(pick.kelvin in WhiteBalance.MIN_KELVIN.toFloat()..WhiteBalance.MAX_KELVIN.toFloat())
            assertTrue(pick.tint in -WhiteBalance.MAX_TINT.toFloat()..WhiteBalance.MAX_TINT.toFloat())
        }
    }
}
