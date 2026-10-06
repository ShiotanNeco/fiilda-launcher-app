package com.fiilda.launcher

import androidx.compose.ui.graphics.Color
import dev.glasslab.glass.GlassVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassAppearanceTest {
    @Test
    fun defaultsCreateTheExistingClearMaterial() {
        val style = launcherGlassStyle(GlassAppearance())

        assertEquals(4f, style.blurRadius.value, 0f)
        assertEquals(8f, style.refraction.value, 0f)
        assertEquals(Color.White, style.tint)
        assertEquals(0f, style.tintAlpha, 0f)
        assertEquals(GlassVariant.Clear, style.variant)
        assertTrue(style.dark)
        assertFalse(style.reduceTransparency)
        assertFalse(style.highContrast)
    }

    @Test
    fun finiteValuesClampToRendererBoundsAndNonFiniteValuesUseDefaults() {
        val normalized = GlassAppearance(
            blurDp = Float.NaN,
            refractionDp = Float.POSITIVE_INFINITY,
            tintStrength = Float.NEGATIVE_INFINITY,
        ).normalized()
        assertEquals(4f, normalized.blurDp, 0f)
        assertEquals(8f, normalized.refractionDp, 0f)
        assertEquals(0f, normalized.tintStrength, 0f)

        val clamped = GlassAppearance(
            blurDp = -1f,
            refractionDp = 40f,
            tintStrength = 2f,
        ).normalized()
        assertEquals(0f, clamped.blurDp, 0f)
        assertEquals(32f, clamped.refractionDp, 0f)
        assertEquals(1f, clamped.tintStrength, 0f)
    }

    @Test
    fun zeroEffectsRemainZeroAndTintIsConvertedToStyleAlpha() {
        val style = launcherGlassStyle(
            appearance = GlassAppearance(
                blurDp = 0f,
                refractionDp = 0f,
                tintStrength = 0.37f,
            ),
        )

        assertEquals(0f, style.blurRadius.value, 0f)
        assertEquals(0f, style.refraction.value, 0f)
        assertEquals(0.37f, style.tintAlpha, 0f)
    }

    @Test
    fun accessibilityFlagsRemainEnabledRegardlessOfAppearanceValues() {
        val style = launcherGlassStyle(
            appearance = GlassAppearance(48f, 32f, 1f),
            reduceTransparency = true,
            highContrast = true,
        )

        assertTrue(style.reduceTransparency)
        assertTrue(style.highContrast)
        assertEquals(48f, style.blurRadius.value, 0f)
        assertEquals(32f, style.refraction.value, 0f)
        assertEquals(1f, style.tintAlpha, 0f)
    }
}
