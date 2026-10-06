package dev.glasslab.glass

import kotlin.math.ceil
import kotlin.math.min

/** Pixel values shared by the capture bounds and the refraction shader. */
internal data class GlassParameters(
    val blurPx: Float,
    val refractionPx: Float,
    val tintAlpha: Float,
    val cornerPx: Float,
    val paddingPx: Int,
)

/** A finite rectangle used by ROI calculations and scene clipping. */
internal data class GlassRoi(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

internal fun expandedGlassRoi(width: Float, height: Float, paddingPx: Int): GlassRoi {
    val safeWidth = width.finiteOr(0f).coerceAtLeast(0f)
    val safeHeight = height.finiteOr(0f).coerceAtLeast(0f)
    val padding = paddingPx.coerceAtLeast(0).toFloat()
    return GlassRoi(-padding, -padding, safeWidth + padding, safeHeight + padding)
}

/**
 * Public sliders and restored settings are not trusted to contain finite values. The padding
 * keeps the blur kernel and the displaced sample inside the captured patch.
 */
internal fun resolveGlassParameters(
    width: Float,
    height: Float,
    density: Float,
    blurDp: Float,
    refractionDp: Float,
    tintAlpha: Float,
    cornerDp: Float,
    clear: Boolean,
): GlassParameters {
    val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    val blur = blurDp.finiteOr(4f).coerceIn(0f, 48f) * safeDensity
    val refraction = refractionDp.finiteOr(8f).coerceIn(0f, 32f) * safeDensity
    val radiusLimit = min(width.finiteOr(0f), height.finiteOr(0f)).coerceAtLeast(0f) / 2f
    return GlassParameters(
        blurPx = blur,
        refractionPx = refraction,
        tintAlpha = tintAlpha.finiteOr(0.22f).coerceIn(0f, 1f) * if (clear) 0.36f else 1f,
        cornerPx = (cornerDp.finiteOr(28f).coerceAtLeast(0f) * safeDensity)
            .coerceAtMost(radiusLimit),
        paddingPx = ceil(3f * blur + refraction + 2f * safeDensity).toInt(),
    )
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
