package com.fiilda.launcher

import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemThemeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences(LauncherThemePreferencesName, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        preferences.edit().clear().commit()
        RuntimeEnvironment.setQualifiers("+notnight")
    }

    @Test
    fun existingManualSelectionIsKeptUntilSystemFollowingIsEnabled() {
        assertTrue(saveLauncherTheme(context, LauncherTheme.GLASS))
        val config = readSystemThemeConfig(context)
        assertFalse(config.enabled)
        assertEquals(LauncherTheme.GLASS, config.resolve(readLauncherTheme(context), false))
        assertEquals(LauncherTheme.GLASS, config.resolve(readLauncherTheme(context), true))
    }

    @Test
    fun everyThemeCanBeSelectedForEitherModeIncludingIdenticalChoices() {
        LauncherTheme.values().forEach { light ->
            LauncherTheme.values().forEach { dark ->
                val config = SystemThemeConfig(true, light, dark)
                assertTrue(saveSystemThemeConfig(context, config))
                val restored = readSystemThemeConfig(context)
                assertEquals(config, restored)
                assertEquals(light, restored.resolve(LauncherTheme.MATERIAL, false))
                assertEquals(dark, restored.resolve(LauncherTheme.MATERIAL, true))
            }
        }
    }

    @Test
    fun systemChangesResolveWithoutOverwritingManualOrSavedModeChoices() {
        saveLauncherTheme(context, LauncherTheme.WINDOWS_8)
        val config = SystemThemeConfig(true, LauncherTheme.CLASSIC, LauncherTheme.GLASS)
        saveSystemThemeConfig(context, config)
        val before = preferences.all.toMap()
        assertEquals(LauncherTheme.CLASSIC, currentSystemTheme(context))
        RuntimeEnvironment.setQualifiers("+night")
        assertEquals(LauncherTheme.GLASS, currentSystemTheme(context))
        RuntimeEnvironment.setQualifiers("+notnight")
        assertEquals(LauncherTheme.CLASSIC, currentSystemTheme(context))
        assertEquals(before, preferences.all)
    }

    @Test
    fun disablingSystemFollowingKeepsTheDisplayedThemeAndRetainsBothChoices() {
        val config = SystemThemeConfig(true, LauncherTheme.CLASSIC, LauncherTheme.GLASS)
        saveSystemThemeConfig(context, config)
        RuntimeEnvironment.setQualifiers("+night")
        assertTrue(saveSystemThemeConfig(context, config.copy(enabled = false)))
        assertEquals(LauncherTheme.GLASS, readLauncherTheme(context))
        RuntimeEnvironment.setQualifiers("+notnight")
        assertEquals(LauncherTheme.GLASS, currentSystemTheme(context))
        assertEquals(config.copy(enabled = false), readSystemThemeConfig(context))
    }

    @Test
    fun enablingEitherAutomationDisablesTheOtherInTheSamePreferenceCommit() {
        val rotation = ThemeRotationConfig(true, ThemeRotationInterval.FIFTEEN_MINUTES.millis,
            setOf(LauncherTheme.CLASSIC, LauncherTheme.GLASS))
        saveThemeRotationConfig(context, rotation)
        var conflictingSnapshot = false
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (readThemeRotationConfig(context).enabled && readSystemThemeConfig(context).enabled) {
                conflictingSnapshot = true
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        try {
            val config = SystemThemeConfig(true, LauncherTheme.CLASSIC, LauncherTheme.GLASS)
            assertTrue(saveSystemThemeConfig(context, config))
            assertEquals(rotation.copy(enabled = false), readThemeRotationConfig(context))
            RuntimeEnvironment.setQualifiers("+night")
            assertTrue(saveThemeRotationConfig(context, rotation))
            assertFalse(readSystemThemeConfig(context).enabled)
            assertEquals(config.copy(enabled = false), readSystemThemeConfig(context))
            assertEquals(LauncherTheme.GLASS, readLauncherTheme(context))
            assertEquals(rotation, readThemeRotationConfig(context))
            assertFalse(conflictingSnapshot)
        } finally {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    @Test
    fun editingInactiveAutomationDoesNotDisableTheActiveOne() {
        val config = SystemThemeConfig(true, LauncherTheme.CLASSIC, LauncherTheme.MATERIAL)
        saveSystemThemeConfig(context, config)
        assertTrue(saveThemeRotationConfig(context, ThemeRotationConfig(intervalMillis = ThemeRotationInterval.ONE_DAY.millis)))
        assertEquals(config, readSystemThemeConfig(context))
        saveThemeRotationConfig(context, ThemeRotationConfig(enabled = true))
        assertTrue(saveSystemThemeConfig(context, config.copy(enabled = false, lightTheme = LauncherTheme.GLASS)))
        assertTrue(readThemeRotationConfig(context).enabled)
    }

    @Test
    fun queuedRotationCannotChangeThemesWhileFollowingSystemEvenWithConflictingFlags() {
        saveLauncherTheme(context, LauncherTheme.WINDOWS_8)
        saveSystemThemeConfig(context, SystemThemeConfig(enabled = true))
        preferences.edit().putBoolean(ThemeRotationEnabledKey, true).commit()
        val before = preferences.all.toMap()
        ThemeRotationReceiver().onReceive(context, Intent("com.fiilda.launcher.action.ROTATE_THEME"))
        assertEquals(before, preferences.all)
    }

    @Test
    fun normalRotationStillAdvancesAndPreservesLightDarkChoices() {
        val config = SystemThemeConfig(false, LauncherTheme.GLASS, LauncherTheme.MATERIAL)
        saveSystemThemeConfig(context, config)
        saveLauncherTheme(context, LauncherTheme.CLASSIC)
        saveThemeRotationConfig(context, ThemeRotationConfig(enabled = true,
            themes = setOf(LauncherTheme.CLASSIC, LauncherTheme.WINDOWS_8)))
        ThemeRotationReceiver().onReceive(context, Intent("com.fiilda.launcher.action.ROTATE_THEME"))
        assertEquals(LauncherTheme.WINDOWS_8, readLauncherTheme(context))
        assertEquals(config, readSystemThemeConfig(context))
    }
}
