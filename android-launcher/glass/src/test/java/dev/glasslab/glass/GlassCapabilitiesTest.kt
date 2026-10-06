package dev.glasslab.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassCapabilitiesTest {
    @Test
    fun routesFrameworkEffectsByApiLevel() {
        assertEquals(GlassCapability.TINT_ONLY, glassCapabilityFor(29))
        assertEquals(GlassCapability.TINT_ONLY, glassCapabilityFor(30))
        assertEquals(GlassCapability.BLUR, glassCapabilityFor(31))
        assertEquals(GlassCapability.BLUR, glassCapabilityFor(32))
        assertEquals(GlassCapability.BLUR_AND_REFRACTION, glassCapabilityFor(33))
        assertEquals(GlassCapability.BLUR_AND_REFRACTION, glassCapabilityFor(36))
    }
}
