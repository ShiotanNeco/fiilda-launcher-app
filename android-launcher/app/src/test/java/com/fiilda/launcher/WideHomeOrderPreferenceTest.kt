package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WideHomeOrderPreferenceTest {
    @Test
    fun missingPreferenceKeepsExistingSharedOrderBehavior() {
        assertEquals(false, DefaultSeparateWideHomeOrder)
        assertFalse(separateWideHomeOrderFromStoredValue(null))
    }

    @Test
    fun storedPreferenceRoundTripsWithoutChangingItsValue() {
        assertTrue(separateWideHomeOrderFromStoredValue(true))
        assertFalse(separateWideHomeOrderFromStoredValue(false))
    }

    @Test
    fun preferenceKeyIsStable() {
        assertEquals("separate_wide_home_order", SeparateWideHomeOrderPreferenceKey)
        assertEquals("fiilda_preferences", LauncherThemePreferencesName)
    }
}
