package com.fiilda.launcher

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf

internal data class SystemThemeConfig(
    val enabled: Boolean = false,
    val lightTheme: LauncherTheme = LauncherTheme.CLASSIC,
    val darkTheme: LauncherTheme = LauncherTheme.DEFAULT,
) {
    fun resolve(manualTheme: LauncherTheme, isDark: Boolean): LauncherTheme =
        if (!enabled) manualTheme else if (isDark) darkTheme else lightTheme
}

internal const val SystemThemeEnabledKey = "system_theme_enabled"
internal const val SystemThemeLightKey = "system_theme_light"
internal const val SystemThemeDarkKey = "system_theme_dark"
internal val SystemThemePreferenceKeys = setOf(SystemThemeEnabledKey, SystemThemeLightKey, SystemThemeDarkKey)

internal fun readSystemThemeConfig(context: Context): SystemThemeConfig = runCatching {
    val preferences = context.getSharedPreferences(LauncherThemePreferencesName, Context.MODE_PRIVATE)
    SystemThemeConfig(
        enabled = preferences.getBoolean(SystemThemeEnabledKey, false),
        lightTheme = parseLauncherThemeToken(preferences.getString(SystemThemeLightKey, LauncherTheme.CLASSIC.token)),
        darkTheme = parseLauncherThemeToken(preferences.getString(SystemThemeDarkKey, LauncherTheme.DEFAULT.token)),
    )
}.getOrDefault(SystemThemeConfig())

internal fun currentSystemTheme(context: Context): LauncherTheme = readSystemThemeConfig(context).resolve(
    manualTheme = readLauncherTheme(context),
    isDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
)

internal fun saveSystemThemeConfig(context: Context, config: SystemThemeConfig): Boolean = runCatching {
    val previous = readSystemThemeConfig(context)
    val editor = context.getSharedPreferences(LauncherThemePreferencesName, Context.MODE_PRIVATE).edit()
        .putBoolean(SystemThemeEnabledKey, config.enabled)
        .putString(SystemThemeLightKey, config.lightTheme.token)
        .putString(SystemThemeDarkKey, config.darkTheme.token)
    // Persist both mode changes together so an already queued rotation cannot override system mode.
    if (config.enabled) editor.putBoolean(ThemeRotationEnabledKey, false)
    // Turning off system following keeps the theme currently on screen.
    if (previous.enabled && !config.enabled) {
        editor.putString(LauncherThemePreferenceKey, currentSystemTheme(context).token)
    }
    editor.commit()
}.getOrDefault(false)

internal val LocalSystemThemeConfig = staticCompositionLocalOf { SystemThemeConfig() }
internal val LocalSystemThemeConfigChanger = staticCompositionLocalOf<(SystemThemeConfig) -> Boolean> {
    { false }
}
