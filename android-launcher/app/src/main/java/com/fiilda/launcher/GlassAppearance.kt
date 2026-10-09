package com.fiilda.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.glasslab.glass.GlassStyle
import dev.glasslab.glass.GlassVariant

/** User-controlled optical parameters shared by both glass themes. */
internal data class GlassAppearance(
    val blurDp: Float = 4f,
    val refractionDp: Float = 8f,
    val tintStrength: Float = 0f,
) {
    fun normalized(): GlassAppearance = GlassAppearance(
        blurDp = blurDp.finiteOr(4f).coerceIn(0f, 48f),
        refractionDp = refractionDp.finiteOr(8f).coerceIn(0f, 32f),
        tintStrength = tintStrength.finiteOr(0f).coerceIn(0f, 1f),
    )
}

/**
 * Creates the one launcher-wide clear material from persisted appearance values.
 * Accessibility flags remain explicit inputs so they always win over optical preferences.
 */
internal fun launcherGlassStyle(
    appearance: GlassAppearance,
    reduceTransparency: Boolean = false,
    highContrast: Boolean = false,
    darkGlass: Boolean = false,
): GlassStyle {
    val normalized = appearance.normalized()
    return GlassStyle(
        variant = GlassVariant.Clear,
        blurRadius = normalized.blurDp.dp,
        refraction = normalized.refractionDp.dp,
        tint = if (darkGlass) Color.Black else Color.White,
        // A fixed black base keeps Dark Glass distinct even when the shared tint slider is zero.
        baseTint = if (darkGlass) Color.Black.copy(alpha = 0.40f) else Color.Transparent,
        tintAlpha = normalized.tintStrength,
        dark = true,
        reduceTransparency = reduceTransparency,
        highContrast = highContrast,
    )
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
