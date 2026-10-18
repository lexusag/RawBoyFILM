package app.rawboy.auto

import app.rawboy.auto.analysis.MeterArea
import org.junit.Assert.assertEquals
import org.junit.Test

/** Camera's metering boxes (the same cases as iOS's MeterAreaTests). */
class MeterAreaTest {
    @Test
    fun centreIsTheMiddleHalf() {
        assertEquals(listOf(128, 384, 96, 288), MeterArea.Centre(0.5f).box(512, 384).toList())
    }

    @Test
    fun spotIsASquareOfTheShortSideAroundTheTap() {
        // 16% of 384 = 61 px, centred on (256, 96).
        assertEquals(listOf(226, 287, 66, 127), MeterArea.Spot(0.5f, 0.25f).box(512, 384).toList())
    }

    @Test
    fun spotNearAnEdgeMovesInsideAtFullSize() {
        assertEquals(listOf(0, 61, 323, 384), MeterArea.Spot(0f, 1f).box(512, 384).toList())
        assertEquals(listOf(451, 512, 0, 61), MeterArea.Spot(1.2f, -0.1f).box(512, 384).toList())
    }

    @Test
    fun spotTurnsWithTheDevice() {
        val tap = MeterArea.Spot(0.2f, 0.1f) // near the viewfinder's top left
        assertEquals(tap, tap.turned(0))
        // Turned clockwise (left side up): the viewfinder's top is the level photo's right.
        assertSpot(0.9f, 0.2f, tap.turned(90))
        assertSpot(0.8f, 0.9f, tap.turned(180))
        assertSpot(0.1f, 0.8f, tap.turned(270))
    }

    private fun assertSpot(x: Float, y: Float, spot: MeterArea.Spot) {
        assertEquals(x, spot.x, 1e-6f)
        assertEquals(y, spot.y, 1e-6f)
    }
}
