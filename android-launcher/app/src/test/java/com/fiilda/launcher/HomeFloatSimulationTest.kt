package com.fiilda.launcher

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFloatSimulationTest {
    private val config = HomeFloatConfig.forDensity(1f)
    private val frame = 1f / 60f
    private val anchor = Offset(200f, 0f)

    private fun simulationWithColumn(): HomeFloatSimulation = HomeFloatSimulation(config).apply {
        place("near", 200f, 60f)
        place("middle", 200f, 160f)
        place("far", 200f, 260f)
    }

    private fun HomeFloatSimulation.scroll(frames: Int) {
        repeat(frames) { step(frame, scrollDeltaPx = 25f, anchor = anchor, scrolling = true) }
    }

    @Test
    fun scrollingOpensGapsAwayFromTheTouchPoint() {
        val simulation = simulationWithColumn()
        simulation.scroll(frames = 30)

        val near = simulation.offsetOf("near").y
        val middle = simulation.offsetOf("middle").y
        val far = simulation.offsetOf("far").y
        assertTrue(near > 0f)
        assertTrue("gap near/middle opens", middle > near)
        assertTrue("gap middle/far opens", far > middle)
        assertTrue(far <= config.maxOffsetPx + 0.5f)
    }

    @Test
    fun tilesSettleBackToTheirCellsAfterScrolling() {
        val simulation = simulationWithColumn()
        simulation.scroll(frames = 30)

        var frames = 0
        while (simulation.step(frame, 0f, anchor, scrolling = false)) {
            frames++
            assertTrue("settles within five seconds", frames < 300)
        }

        assertEquals(Offset.Zero, simulation.offsetOf("near"))
        assertEquals(Offset.Zero, simulation.offsetOf("far"))
        assertFalse(simulation.step(frame, 0f, anchor, scrolling = false))
    }

    @Test
    fun pullingPastAnEdgeOpensGapsAndReleasingClosesThem() {
        val simulation = simulationWithColumn()
        repeat(30) {
            simulation.step(frame, scrollDeltaPx = 0f, anchor = anchor, scrolling = true, edgePullPx = 8f)
        }
        assertTrue(simulation.offsetOf("far").y > simulation.offsetOf("near").y)

        var frames = 0
        while (simulation.step(frame, 0f, anchor, scrolling = false)) {
            frames++
            assertTrue("settles after release", frames < 300)
        }
        assertEquals(Offset.Zero, simulation.offsetOf("far"))
    }

    @Test
    fun slidingSidewaysMakesTilesLagThenSettle() {
        val simulation = simulationWithColumn()
        // The surface moves left (negative x); tiles lag behind, to the right.
        repeat(20) {
            simulation.step(frame, 0f, anchor, scrolling = false, horizontalDeltaPx = -40f)
        }
        assertTrue(simulation.offsetOf("near").x > 0f)
        // The under-damped spring may overshoot its clamped target, but only by a bounce.
        assertTrue(simulation.offsetOf("far").x <= config.maxSwayPx * 1.3f)
        assertTrue("tiles sway by different amounts", simulation.offsetOf("near").x != simulation.offsetOf("far").x)

        var frames = 0
        while (simulation.step(frame, 0f, anchor, scrolling = false)) {
            frames++
            assertTrue("settles after the slide", frames < 300)
        }
        assertEquals(Offset.Zero, simulation.offsetOf("near"))
    }

    @Test
    fun surfaceBlurIsZeroWhenSettledAndFullOneWidthAway() {
        assertEquals(0f, surfaceMotionBlurFraction(0f))
        assertEquals(1f, surfaceMotionBlurFraction(-1f))
        assertEquals(1f, surfaceMotionBlurFraction(2.5f))
        assertTrue(surfaceMotionBlurFraction(0.5f) > 0.5f)
    }

    @Test
    fun theTileUnderTheFingerAnchorsTheSpreadAndMovesWithTheContent() {
        val state = HomeFloatState(config, centersMoveWithScroll = true)
        state.simulation.place("top", 200f, 60f)
        state.simulation.place("bottom", 200f, 400f)

        state.anchorAt(Offset(210f, 380f))
        assertEquals(Offset(200f, 400f), state.anchorOnBoard(scrollDeltaPx = 0f))

        // The lazy list scrolls the tile up, then disposes it; the anchor keeps following.
        state.simulation.place("bottom", 200f, 300f)
        assertEquals(Offset(200f, 300f), state.anchorOnBoard(scrollDeltaPx = -100f))
        state.simulation.remove("bottom")
        assertEquals(Offset(200f, 250f), state.anchorOnBoard(scrollDeltaPx = -50f))
    }

    @Test
    fun removedTilesHaveNoOffset() {
        val simulation = simulationWithColumn()
        simulation.scroll(frames = 10)
        simulation.remove("far")

        assertEquals(Offset.Zero, simulation.offsetOf("far"))
    }
}
