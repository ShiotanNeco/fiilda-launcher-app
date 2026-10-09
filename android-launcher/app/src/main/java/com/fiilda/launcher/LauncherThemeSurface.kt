package com.fiilda.launcher

import android.content.SharedPreferences
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat

/** Palette accessors and the root Material/Classic/Metro theme boundary. */
// These composable getters preserve the existing FiiLDA* call sites while routing every launcher
// surface through the active palette. Changing the setting therefore recomposes the complete
// launcher, including dialogs and transient selection surfaces.
internal val FiiLDABlack: Color
    @Composable get() = LocalLauncherPalette.current.background
internal val FiiLDADeep: Color
    @Composable get() = LocalLauncherPalette.current.deep
internal val FiiLDASurface: Color
    @Composable get() = LocalLauncherPalette.current.surface
internal val FiiLDASelectedSurface: Color
    @Composable get() = LocalLauncherPalette.current.selectedSurface
internal val FiiLDAEnabledSurface: Color
    @Composable get() = LocalLauncherPalette.current.enabledSurface
internal val FiiLDAAccentSurface: Color
    @Composable get() = LocalLauncherPalette.current.accentSurface
internal val FiiLDAPhotoFrameSurface: Color
    @Composable get() = LocalLauncherPalette.current.photoFrameSurface
internal val FiiLDAPhotoPreviewInk: Color
    @Composable get() = LocalLauncherPalette.current.photoPreviewInk
internal val FiiLDALine: Color
    @Composable get() = LocalLauncherPalette.current.line
internal val FiiLDALineStrong: Color
    @Composable get() = LocalLauncherPalette.current.lineStrong
internal val FiiLDAInk: Color
    @Composable get() = LocalLauncherPalette.current.ink
internal val FiiLDAMuted: Color
    @Composable get() = LocalLauncherPalette.current.muted
internal val FiiLDAQuiet: Color
    @Composable get() = LocalLauncherPalette.current.quiet
internal val FiiLDACyan: Color
    @Composable get() = LocalLauncherPalette.current.accent

@Composable
internal fun FiiLDATheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var manualTheme by remember { mutableStateOf(readLauncherTheme(context)) }
    var systemThemeConfig by remember { mutableStateOf(readSystemThemeConfig(context)) }
    val systemDarkTheme = isSystemInDarkTheme()
    val launcherTheme = systemThemeConfig.resolve(manualTheme, systemDarkTheme)
    val themePreferences = remember(context) {
        context.getSharedPreferences(LauncherThemePreferencesName, android.content.Context.MODE_PRIVATE)
    }
    DisposableEffect(themePreferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
            if (key == LauncherThemePreferenceKey) {
                manualTheme = parseLauncherThemeToken(
                    preferences.getString(LauncherThemePreferenceKey, LauncherTheme.DEFAULT.token),
                )
            }
            if (key in SystemThemePreferenceKeys) {
                systemThemeConfig = readSystemThemeConfig(context)
            }
        }
        themePreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { themePreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DisposableEffect(context, systemThemeConfig.enabled) {
        ThemeRotationScheduler.update(context.applicationContext, readThemeRotationConfig(context))
        onDispose { }
    }
    // The wallpaper controller and Photo Picker registration live at the stable theme boundary,
    // rather than inside SettingsScreen. Closing/recreating settings therefore cannot unregister a
    // pending picker result, and changing themes retains the private wallpaper copy.
    val glassWallpaperController = rememberGlassWallpaperController(context)
    RegisterGlassWallpaperPicker(glassWallpaperController)
    var showAppLabels by remember { mutableStateOf(readShowAppLabels(context)) }
    var showNotificationBadges by remember { mutableStateOf(readShowNotificationBadges(context)) }
    var separateWideHomeOrder by remember { mutableStateOf(readSeparateWideHomeOrder(context)) }
    var reduceMotion by remember { mutableStateOf(readReduceMotion(context)) }
    val isMaterialTheme = launcherTheme == LauncherTheme.MATERIAL
    val materialColorScheme = if (isMaterialTheme) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (systemDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (systemDarkTheme) darkColorScheme() else lightColorScheme()
        }
    } else {
        null
    }
    val palette = if (materialColorScheme != null) {
        launcherPaletteFromMaterialColorScheme(
            colorScheme = materialColorScheme,
            isLight = !systemDarkTheme,
        )
    } else {
        launcherPaletteFor(launcherTheme)
    }
    val changeTheme: (LauncherTheme) -> Boolean = { requestedTheme ->
        if (requestedTheme == manualTheme) {
            true
        } else if (saveLauncherTheme(context, requestedTheme)) {
            manualTheme = requestedTheme
            true
        } else {
            false
        }
    }
    val changeSystemThemeConfig: (SystemThemeConfig) -> Boolean = { requestedConfig ->
        if (saveSystemThemeConfig(context, requestedConfig)) {
            manualTheme = readLauncherTheme(context)
            systemThemeConfig = requestedConfig
            true
        } else {
            false
        }
    }
    val changeAppLabels: (Boolean) -> Boolean = { requestedShowAppLabels ->
        if (requestedShowAppLabels == showAppLabels) {
            true
        } else if (saveShowAppLabels(context, requestedShowAppLabels)) {
            showAppLabels = requestedShowAppLabels
            true
        } else {
            false
        }
    }
    val changeNotificationBadges: (Boolean) -> Boolean = { requestedShowNotificationBadges ->
        if (requestedShowNotificationBadges == showNotificationBadges) {
            true
        } else if (saveShowNotificationBadges(context, requestedShowNotificationBadges)) {
            showNotificationBadges = requestedShowNotificationBadges
            true
        } else {
            false
        }
    }
    val changeReduceMotion: (Boolean) -> Boolean = { requestedReduceMotion ->
        if (requestedReduceMotion == reduceMotion) {
            true
        } else if (saveReduceMotion(context, requestedReduceMotion)) {
            reduceMotion = requestedReduceMotion
            true
        } else {
            false
        }
    }
    val changeSeparateWideHomeOrder: (Boolean) -> Boolean = { requestedSeparate ->
        if (requestedSeparate == separateWideHomeOrder) {
            true
        } else if (saveSeparateWideHomeOrder(context, requestedSeparate)) {
            separateWideHomeOrder = requestedSeparate
            true
        } else {
            false
        }
    }
    // Keep system bars legible as the setting changes. WindowCompat edge-to-edge remains enabled
    // by MainActivity, so transparent bars let each surface's palette continue underneath them.
    val activity = context as? ComponentActivity
    SideEffect {
        activity?.window?.let { window ->
            // The activity draws behind both system bars. Surface-specific inset handling keeps
            // interactive content safe while the active palette remains visible underneath them.
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = palette.isLight
                isAppearanceLightNavigationBars = palette.isLight
            }
        }
    }
    val colorScheme = materialColorScheme ?: if (launcherTheme == LauncherTheme.CLASSIC) {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.accentOn,
            secondary = palette.accent,
            onSecondary = palette.accentOn,
            background = palette.background,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.deep,
            onSurfaceVariant = palette.muted,
            outline = palette.line,
        )
    } else {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.accentOn,
            secondary = palette.accent,
            onSecondary = palette.accentOn,
            background = palette.background,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.deep,
            onSurfaceVariant = palette.muted,
            outline = palette.line,
        )
    }
    CompositionLocalProvider(
        LocalLauncherTheme provides launcherTheme,
        LocalLauncherPalette provides palette,
        LocalLauncherThemeChanger provides changeTheme,
        LocalSystemThemeConfig provides systemThemeConfig,
        LocalSystemThemeConfigChanger provides changeSystemThemeConfig,
        LocalGlassWallpaperController provides glassWallpaperController,
        LocalGlassReduceTransparency provides glassWallpaperController.state.reduceTransparency,
        LocalShowAppLabels provides showAppLabels,
        LocalShowAppLabelsChanger provides changeAppLabels,
        LocalShowNotificationBadges provides showNotificationBadges,
        LocalShowNotificationBadgesChanger provides changeNotificationBadges,
        LocalSeparateWideHomeOrder provides separateWideHomeOrder,
        LocalSeparateWideHomeOrderChanger provides changeSeparateWideHomeOrder,
        LocalReduceMotion provides reduceMotion,
        LocalReduceMotionChanger provides changeReduceMotion,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
