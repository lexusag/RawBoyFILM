package app.rawboy.develop

import app.rawboy.lens.LensInfo
import app.rawboy.lens.LensTransform
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pro's flips (the same cases as web's engine.test.ts): a mirror of the picture as shown, crop and straighten included. */
class FlipTest {
    private val info = LensInfo.none(6000, 4000)

    @Test
    fun flipsMirrorThePictureAsShown() {
        for (rotation in 0..3) {
            for (vertical in listOf(false, true)) {
                val before = DevelopParams(lensDistortion = 40f, straighten = 7.5f, rotation = rotation)
                    .withCrop(CropRect(0.1f, 0.2f, 0.5f, 0.9f))
                val a = LensTransform(info, before)
                val b = LensTransform(info, CropMath.flip(before, vertical))
                assertEquals(a.outputWidth, b.outputWidth)
                assertEquals(a.outputHeight, b.outputHeight)
                // Crop edges round to whole pixels, so allow a pixel's worth.
                for ((x, y) in listOf(0.0 to 0.0, a.outputWidth.toDouble() to 0.0, 137.5 to 401.25)) {
                    val mx = if (vertical) x else a.outputWidth - x
                    val my = if (vertical) a.outputHeight - y else y
                    assertArrayEquals(a.source(x, y), b.source(mx, my), 1.0)
                }
                // Twice is no flip.
                assertArrayEquals(a.outToImage, LensTransform(info, CropMath.flip(CropMath.flip(before, vertical), vertical)).outToImage, 1e-9)
            }
        }
    }

    @Test
    fun aSidewaysTurnSwapsTheAxis() {
        val plain = LensTransform(info, CropMath.flip(DevelopParams(), false))
        assertTrue(plain.flipHorizontal)
        assertArrayEquals(doubleArrayOf(5900.0, 50.0), plain.source(100.0, 50.0), 1e-6)
        assertTrue(CropMath.flip(DevelopParams(rotation = 1), false).flipVertical)
    }
}
