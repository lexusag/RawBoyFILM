package app.rawboy.develop

import app.rawboy.gl.ShaderParams
import app.rawboy.gl.ZoomMath
import app.rawboy.lens.LensInfo
import app.rawboy.lens.LensTransform
import app.rawboy.raw.ShotInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mask layers and brush rasters (the same cases as web's engine.test.ts). */
class MasksTest {
    private val w = 200
    private val h = 100
    private fun noGuide() = BrushGuide(FloatArray(w * h), FloatArray(w * h), FloatArray(w * h))

    private fun stroke(id: String, points: List<Float>, erase: Boolean = false, auto: Boolean = false, radius: Float = 0.05f, hardness: Float = 50f, flow: Float = 60f) =
        BrushStroke(id, erase, auto, radius, hardness, flow, points)

    private fun pixels(r: BrushRaster) = (0 until w * h).map { r.valueAt(it) }

    private val a = stroke("a", listOf(0.1f, 0.5f, 0.3f, 0.5f, 0.5f, 0.6f, 0.7f, 0.4f))
    private val b = stroke("b", listOf(0.2f, 0.2f, 0.8f, 0.8f), erase = true, radius = 0.03f)
    private val c = stroke("c", listOf(0.5f, 0.2f, 0.5f, 0.8f), flow = 100f)

    @Test
    fun rasterIsTheSameDrawnIncrementallyOrAllAtOnce() {
        val whole = BrushRaster(w, h, ::noGuide).apply { sync(listOf(a, b, c)) }
        val step = BrushRaster(w, h, ::noGuide)
        for (n in 2..a.points.size step 2) step.sync(listOf(a.copy(points = a.points.take(n))))
        step.sync(listOf(a, b.copy(points = b.points.take(2))))
        step.sync(listOf(a, b))
        step.sync(listOf(a, b, c))
        assertEquals(pixels(whole), pixels(step))
        // Undo: back to a and b, redrawn from scratch.
        val ab = BrushRaster(w, h, ::noGuide).apply { sync(listOf(a, b)) }
        step.sync(listOf(a, b))
        assertEquals(pixels(ab), pixels(step))
    }

    @Test
    fun paintAndEraseApplyInOrderAndTrackChanges() {
        val r = BrushRaster(w, h, ::noGuide)
        val k = 50 * w + 100
        val p = stroke("p", listOf(0.5f, 0.5f), flow = 100f, hardness = 100f)
        r.sync(listOf(p))
        assertEquals(255 to 0, r.valueAt(k))
        val v = r.version
        r.sync(listOf(p, stroke("e", listOf(0.5f, 0.5f), erase = true, flow = 100f, hardness = 100f)))
        assertEquals(0 to 255, r.valueAt(k))
        val pending = r.pendingRect(v)!!
        assertTrue(pending.width < w)
        assertNull(r.pendingRect(r.version))
        assertEquals(w, r.pendingRect(v - 5)!!.width) // too far back: everything
    }

    @Test
    fun autoMaskStaysOnTheColourUnderTheBrush() {
        val guide = noGuide()
        for (y in 0 until h) for (x in 100 until w) guide.lum[y * w + x] = 3f // 3 EV brighter on the right
        val r = BrushRaster(w, h) { guide }
        r.sync(listOf(stroke("x", listOf(0.45f, 0.5f), auto = true, flow = 100f, hardness = 100f, radius = 0.1f)))
        assertTrue(r.valueAt(50 * w + 92).first > 200)
        assertEquals(0, r.valueAt(50 * w + 108).first)
    }

    @Test
    fun packsLayersSlotsAndCurvature() {
        val raster = BrushRaster(w, h, ::noGuide)
        val painted = Masks.newBrush().copy(adjust = LayerAdjust(exposure = 1f), strokes = listOf(a))
        val sky = Masks.newLinear().copy(adjust = LayerAdjust(exposure = -1f), strokes = listOf(b))
        val emptyBrush = Masks.newBrush().copy(adjust = LayerAdjust(exposure = 1f))
        val u = ShaderParams.packLayers(listOf(emptyBrush, sky, painted), 4000, 3000, 5500f, 0f, true) { raster }
        assertEquals(2, u.count) // the empty brush layer has no mask
        assertEquals(2f, u.shape[4]) // brush kind
        assertEquals(listOf(raster, raster, null, null), u.brush)
        // Linear p0 in pixels, then the direction over its squared length (1050 px straight down).
        assertEquals(2000f, u.geom[0], 1e-3f)
        assertEquals(450f, u.geom[1], 1e-3f)
        assertEquals(1f / 1050, u.geom[3], 1e-7f)
        val bent = Masks.newLinear().let { it.copy(shape = (it.shape as MaskShape.Linear).copy(curve = 40f), adjust = LayerAdjust(exposure = 1f)) }
        val ub = ShaderParams.packLayers(listOf(bent), 4000, 3000, 5500f, 0f, true)
        // The overlay's centre line (along = len/2 - offset, across = s) is where the shader's t = 0.5.
        val len = 1050f
        val across = 1200f
        val along = len / 2 - Masks.curveOffset(40f, across, 4000f)
        val px = 2000f - across // perp = (-dy, dx) / len = (-1, 0)
        val py = 450f + along
        val g = ub.geom
        val dx = px - g[0]
        val dy = py - g[1]
        val t = dx * g[2] + dy * g[3]
        val n = kotlin.math.hypot(g[2], g[3])
        val s = (dx * -g[3] + dy * g[2]) / n
        assertEquals(0.5f, t + ub.shape[1] * s * s, 1e-4f)
    }

    @Test
    fun presetsKeepGradientsOnly() {
        val sky = Masks.newLinear().copy(adjust = LayerAdjust(exposure = -1f), strokes = listOf(b))
        val painted = Masks.newBrush().copy(strokes = listOf(a))
        val p = Presets.capture("M", DevelopParams(masks = listOf(sky, painted)), setOf(PresetGroup.MASKS))
        val decoded = Presets.decode(Presets.encode(listOf(p))).single()
        val out = Presets.apply(decoded, DevelopParams(exposure = 1f))
        assertEquals(1f, out.exposure)
        assertEquals(1, out.masks.size)
        assertTrue(out.masks[0].shape is MaskShape.Linear)
        assertTrue(out.masks[0].strokes.isEmpty())
        assertEquals(-1f, out.masks[0].adjust.exposure)
        assertNotEquals(sky.id, out.masks[0].id)
        assertTrue(PresetGroup.MASKS !in PresetGroup.DEFAULT)
        // A web file from before curvature, with a broken layer and an out-of-range one.
        val web = """{"format":"rawboy-preset","version":1,"name":"W","groups":["masks"],"values":{"masks":[
            {"shape":{"kind":"linear","x0":0.5,"y0":0,"x1":0.5,"y1":0.5}},
            {"shape":{"kind":"blob"}},
            {"shape":{"kind":"radial","cx":0.5,"cy":0.5,"rx":0.2,"ry":0.2,"angle":0,"feather":500},"opacity":300,"adjust":{"exposure":99}}
        ]}}"""
        val layers = Presets.apply(Presets.decode(web).single(), DevelopParams()).masks
        assertEquals(2, layers.size)
        assertEquals(0f, (layers[0].shape as MaskShape.Linear).curve)
        assertEquals(100f, layers[1].opacity)
        assertEquals(5f, layers[1].adjust.exposure)
        assertEquals(100f, (layers[1].shape as MaskShape.Radial).feather)
    }

    @Test
    fun lensOutputInvertsSource() {
        val lens = LensTransform(LensInfo.none(4000, 3000), DevelopParams(straighten = 7f, lensDistortion = 40f, rotation = 1))
        for ((x, y) in listOf(100.0 to 200.0, 1500.0 to 900.0, 2900.0 to 3800.0)) {
            val s = lens.source(x, y)
            val o = lens.output(s[0], s[1])
            assertEquals(x, o[0], 1e-2)
            assertEquals(y, o[1], 1e-2)
        }
    }

    @Test
    fun zoomedRectAgreesWithViewRect() {
        val z = ZoomMath.zoomedRect(1500f, 1000f, 800, 600, 4000, 3000)
        val v = ZoomMath.viewRect(1500f, 1000f, 800, 600, 4000, 3000)
        assertEquals(0f, z[0] + v.x, 0f)
        assertEquals(0f, z[1] + v.y, 0f)
        assertEquals(4000f, z[2], 0f)
    }

    @Test
    fun shotInfoLine() {
        assertEquals(
            "GFX 50R · ISO 6400 · 1/60 s · f/2.8 · 63 mm (50 mm eq.)",
            ShotInfo("GFX 50R", 1 / 60.0, 2.8, 63.0, 50.0, 6400.0).line(),
        )
        assertEquals("ISO 100 · 2.5 s · f/8", ShotInfo("", 2.5, 8.0, Double.NaN, Double.NaN, 100.0).line())
    }

    @Test
    fun presetsKeepTheToneMapper() {
        val p = Presets.capture("F", DevelopParams(toneMapper = ToneMapper.FILMIC), setOf(PresetGroup.LIGHT))
        val decoded = Presets.decode(Presets.encode(listOf(p))).single()
        assertEquals("filmic", decoded.values["toneMapper"])
        assertEquals(ToneMapper.FILMIC, Presets.apply(decoded, DevelopParams()).toneMapper)
        val bad = decoded.copy(values = mapOf("toneMapper" to "aces"))
        assertEquals(ToneMapper.SIGMOID, Presets.apply(bad, DevelopParams(toneMapper = ToneMapper.SIGMOID)).toneMapper)
    }
}
