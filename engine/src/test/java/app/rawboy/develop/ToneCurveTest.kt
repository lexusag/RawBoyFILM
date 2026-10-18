package app.rawboy.develop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Point curves (Curves in Pro), as RawBoy Web's engine.test.ts "curves". */
class ToneCurveTest {
    private val s = listOf(CurvePoint(0f, 0f), CurvePoint(0.25f, 0.18f), CurvePoint(0.75f, 0.82f), CurvePoint(1f, 1f))

    @Test
    fun passesThroughItsPointsAndStaysMonotone() {
        for (p in s) assertEquals(p.y, ToneCurves.evaluate(s, p.x), 1e-6f)
        val v = ToneCurves.sample(s, 101)
        for (i in 1 until v.size) assertTrue(v[i] >= v[i - 1])
        // Same values as engine.test.ts, so the ports agree.
        assertEquals(0.06528f, ToneCurves.evaluate(s, 0.1f), 1e-5f)
        assertEquals(0.5f, ToneCurves.evaluate(s, 0.5f), 1e-6f)
        assertEquals(0.93472f, ToneCurves.evaluate(s, 0.9f), 1e-5f)
        val ends = listOf(CurvePoint(0.1f, 0.2f), CurvePoint(0.9f, 0.7f))
        assertEquals(0.2f, ToneCurves.evaluate(ends, 0.05f), 1e-6f)
        assertEquals(0.7f, ToneCurves.evaluate(ends, 0.95f), 1e-6f)
    }

    @Test
    fun tableIsOffAtTheIdentityAndAppliesChannelsAfterRgb() {
        assertNull(ToneCurves.NONE.table())
        val t = ToneCurves(
            rgb = listOf(CurvePoint(0f, 0.2f), CurvePoint(1f, 1f)),
            red = listOf(CurvePoint(0f, 1f), CurvePoint(1f, 0f)),
        ).table()!!
        assertEquals(ToneCurves.SIZE * 3, t.size)
        assertEquals(0.8f, t[0], 1e-6f) // red: inverted after the RGB lift
        assertEquals(0.2f, t[1], 1e-6f)
        assertEquals(0f, t[(ToneCurves.SIZE - 1) * 3], 1e-6f)
        assertEquals(1f, t[(ToneCurves.SIZE - 1) * 3 + 2], 1e-6f)
    }

    @Test
    fun addsMovesAndRemovesPointsWithinTheirNeighbours() {
        val (points, index) = ToneCurves.add(ToneCurves.IDENTITY, 0.5f, 0.6f)!!
        assertEquals(1, index)
        assertEquals(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.6f), CurvePoint(1f, 1f)), points)
        assertNull(ToneCurves.add(points, 0.51f, 0.3f)) // too close
        assertEquals(CurvePoint(1f - ToneCurves.MIN_GAP, 0f), ToneCurves.move(points, 1, 1.5f, -1f)[1])
        assertEquals(CurvePoint(0.5f - ToneCurves.MIN_GAP, 0.1f), ToneCurves.move(points, 0, 0.7f, 0.1f)[0])
        assertEquals(ToneCurves.IDENTITY, ToneCurves.remove(points, 1))
        assertEquals(points, ToneCurves.remove(points, 0))
    }

    @Test
    fun roundTripsThroughPresetsInTheirOwnGroup() {
        val curves = ToneCurves(rgb = s, blue = listOf(CurvePoint(0f, 0.1f), CurvePoint(1f, 0.9f)))
        val preset = Presets.capture("Curve", DevelopParams(curves = curves, exposure = 1f), setOf(PresetGroup.CURVES))
        assertEquals(mapOf("curves" to curves), preset.values)
        val decoded = Presets.decode(Presets.encode(listOf(preset))).single()
        assertEquals(setOf(PresetGroup.CURVES), decoded.groups)
        assertEquals(DevelopParams(curves = curves, exposure = 2f), Presets.apply(decoded, DevelopParams(exposure = 2f)))
        val parsed = ToneCurves.parse(
            org.json.JSONObject("""{"rgb":[[0,0],[0.5,2],[1,1]],"red":[[0.5,0],[0.2,1]],"green":"x"}"""),
        )!!
        assertEquals(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 1f), CurvePoint(1f, 1f)), parsed.rgb)
        assertEquals(ToneCurves.IDENTITY, parsed.red) // not sorted
        assertEquals(ToneCurves.IDENTITY, parsed.green)
        assertNull(ToneCurves.parse(org.json.JSONArray("[1,2]")))
    }
}
