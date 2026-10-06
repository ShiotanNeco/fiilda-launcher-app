package dev.glasslab.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassParametersTest {
    @Test
    fun capturePaddingCoversBlurAndDisplacementAtMultipleDensities() {
        for (density in listOf(1f, 1.5f, 2.625f, 3.5f)) {
            val result = resolveGlassParameters(800f, 300f, density, 4f, 8f, .22f, 28f, false)
            assertTrue(result.paddingPx >= result.blurPx * 3f + result.refractionPx)
            assertTrue(
                result.paddingPx <
                    result.blurPx * 3f + result.refractionPx + density * 2f + 1f,
            )
        }
    }

    @Test
    fun restoredNonFiniteSettingsCannotReachTheShader() {
        val result = resolveGlassParameters(
            100f, 40f, Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, Float.NaN, Float.NaN, false,
        )
        assertEquals(4f, result.blurPx, 0f)
        assertEquals(8f, result.refractionPx, 0f)
        assertEquals(.22f, result.tintAlpha, 0f)
        assertEquals(20f, result.cornerPx, 0f)
        assertTrue(result.paddingPx > 0)
    }

    @Test
    fun smallSurfaceConstrainsTheLensRadiusAndEffectBudget() {
        val result = resolveGlassParameters(120f, 24f, 3f, 1000f, 1000f, 4f, 1000f, false)
        assertEquals(12f, result.cornerPx, 0f)
        assertEquals(144f, result.blurPx, 0f)
        assertEquals(96f, result.refractionPx, 0f)
        assertEquals(1f, result.tintAlpha, 0f)
    }

    @Test
    fun zeroEffectsAndClearVariantPreserveIntent() {
        val result = resolveGlassParameters(200f, 100f, 2f, 0f, -2f, .5f, 0f, true)
        assertEquals(0f, result.blurPx, 0f)
        assertEquals(0f, result.refractionPx, 0f)
        assertEquals(0f, result.cornerPx, 0f)
        assertEquals(.18f, result.tintAlpha, .0001f)
        assertEquals(4, result.paddingPx)
    }

    @Test
    fun roiExpansionIsFiniteAndSymmetric() {
        assertEquals(
            GlassRoi(-12f, -12f, 112f, 62f),
            expandedGlassRoi(100f, 50f, 12),
        )
        assertEquals(0f, expandedGlassRoi(Float.NaN, Float.POSITIVE_INFINITY, -3).width, 0f)
        assertEquals(0f, expandedGlassRoi(Float.NaN, Float.POSITIVE_INFINITY, -3).height, 0f)
    }
}
