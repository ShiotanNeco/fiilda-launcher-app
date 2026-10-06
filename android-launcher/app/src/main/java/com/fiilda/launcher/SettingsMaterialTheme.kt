package com.fiilda.launcher

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The settings surface has its own Material 3 boundary so launcher themes cannot leak their
 * palettes into controls intended to follow Android system appearance.
 */
@Composable
internal fun SettingsMaterialTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    val colorScheme = remember(context, systemDarkTheme) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (systemDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (systemDarkTheme) darkColorScheme() else lightColorScheme()
        }
    }
    val activity = context as? ComponentActivity
    // This value is supplied by the parent FiiLDA theme. It is intentionally used only for
    // restoration; the settings surface always follows the system light/dark mode above.
    val parentPaletteIsLight = LocalLauncherPalette.current.isLight

    DisposableEffect(activity, parentPaletteIsLight, systemDarkTheme) {
        onDispose {
            restoreLauncherSystemBars(activity, parentPaletteIsLight)
        }
    }
    // FiiLDATheme also owns the same window, so reapply after every settings recomposition (for
    // example, when a launcher theme is selected) to keep the nested surface authoritative.
    SideEffect {
        applySettingsSystemBars(activity, systemDarkTheme)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = SettingsShapes,
        content = content,
    )
}

private val SettingsShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun applySettingsSystemBars(
    activity: ComponentActivity?,
    systemDarkTheme: Boolean,
) {
    activity?.window?.let { window ->
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !systemDarkTheme
            isAppearanceLightNavigationBars = !systemDarkTheme
        }
    }
}

private fun restoreLauncherSystemBars(
    activity: ComponentActivity?,
    parentPaletteIsLight: Boolean,
) {
    activity?.window?.let { window ->
        // LauncherThemeSurface uses transparent edge-to-edge bars for every palette. Restoring
        // the icon appearance from the parent palette also handles a theme changed in Settings.
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = parentPaletteIsLight
            isAppearanceLightNavigationBars = parentPaletteIsLight
        }
    }
}
