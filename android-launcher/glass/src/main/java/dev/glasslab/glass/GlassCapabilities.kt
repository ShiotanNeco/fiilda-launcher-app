package dev.glasslab.glass

import android.os.Build

/** The strongest optical path that can be used for a given Android API level. */
enum class GlassCapability {
    /** Backdrop replay, tint, edge, and sheen are available. */
    TINT_ONLY,

    /** RenderEffect blur is available, but AGSL RuntimeShader is not. */
    BLUR,

    /** RenderEffect blur and AGSL RuntimeShader refraction are available. */
    BLUR_AND_REFRACTION,
}

/**
 * Pure capability routing kept separate from [Build.VERSION] so compatibility behavior can be
 * tested without a device or an Android framework shadow.
 */
fun glassCapabilityFor(apiLevel: Int): GlassCapability = when {
    apiLevel >= 33 -> GlassCapability.BLUR_AND_REFRACTION
    apiLevel >= 31 -> GlassCapability.BLUR
    else -> GlassCapability.TINT_ONLY
}

/** Returns the strongest path available on the current device. */
fun currentGlassCapability(): GlassCapability = glassCapabilityFor(Build.VERSION.SDK_INT)
