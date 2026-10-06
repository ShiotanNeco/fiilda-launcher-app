package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLabelVisibilityTest {
    @Test
    fun missingPreferenceDefaultsToVisible() {
        assertEquals(DefaultShowAppLabels, showAppLabelsFromStoredValue(null))
    }

    @Test
    fun storedPreferenceIsPreserved() {
        assertTrue(showAppLabelsFromStoredValue(true))
        assertFalse(showAppLabelsFromStoredValue(false))
    }

    @Test
    fun renderingHidesOnlyAppLabels() {
        assertTrue(shouldRenderAppLabel(showAppLabels = true))
        assertFalse(shouldRenderAppLabel(showAppLabels = false))
        assertTrue(shouldRenderAppLabel(showAppLabels = false, isAppLabel = false))
    }

    @Test
    fun preferenceKeyIsStableAndUsesExistingPreferenceStore() {
        assertEquals("show_app_labels", ShowAppLabelsPreferenceKey)
        assertEquals("fiilda_preferences", LauncherThemePreferencesName)
    }
}
