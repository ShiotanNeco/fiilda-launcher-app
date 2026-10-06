package com.fiilda.launcher

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb

/** Stable preference key for the launcher-wide notification-count badge setting. */
internal const val ShowNotificationBadgesPreferenceKey = "show_notification_badges"
internal const val DefaultShowNotificationBadges = true

/** Existing installs have no record yet, so notification-count badges remain visible by default. */
internal fun showNotificationBadgesFromStoredValue(storedValue: Boolean?): Boolean =
    storedValue ?: DefaultShowNotificationBadges

/**
 * Decides whether a count badge belongs on a tile. Live notification tiles own their count and
 * rows, so this setting only controls the small overlay used by ordinary app tiles.
 */
internal fun shouldRenderNotificationBadge(
    showNotificationBadges: Boolean,
    notificationCount: Int,
    isLiveTile: Boolean,
): Boolean = showNotificationBadges && notificationCount > 0 && !isLiveTile

/**
 * Returns the base sp value used by the badge number. Compose applies the system font scale to
 * this value, so divide by larger scales to keep the visual number inside the fixed square. The
 * normal-scale values intentionally remain the compact 9sp/8sp sizes used by the badge.
 */
internal fun notificationBadgeTextSizeSp(
    label: String,
    fontScale: Float,
): Float {
    val normalSize = if (label.length > 2) 8f else 9f
    val safeFontScale = fontScale
        .takeIf { it.isFinite() && it > 0f }
        ?.coerceAtLeast(1f)
        ?: 1f
    return normalSize / safeFontScale
}

internal fun readShowNotificationBadges(context: Context): Boolean = runCatching {
    val preferences = context.getSharedPreferences(
        LauncherThemePreferencesName,
        Context.MODE_PRIVATE,
    )
    showNotificationBadgesFromStoredValue(
        preferences.getBoolean(
            ShowNotificationBadgesPreferenceKey,
            DefaultShowNotificationBadges,
        ),
    )
}.getOrDefault(DefaultShowNotificationBadges)

/** Uses commit so the Compose state changes only after the preference is durably written. */
internal fun saveShowNotificationBadges(context: Context, show: Boolean): Boolean = runCatching {
    context.getSharedPreferences(LauncherThemePreferencesName, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(ShowNotificationBadgesPreferenceKey, show)
        .commit()
}.getOrDefault(false)

internal val LocalShowNotificationBadges = staticCompositionLocalOf {
    DefaultShowNotificationBadges
}
internal val LocalShowNotificationBadgesChanger = staticCompositionLocalOf<(Boolean) -> Boolean> {
    { false }
}

/** Colors for the compact square badge, derived from the app tile instead of a global accent. */
internal data class NotificationBadgeColors(
    val background: Color,
    val border: Color,
    val content: Color,
)

/**
 * Keeps the badge subdued while preserving a readable number on both light and dark tile colors.
 * The fill and border use the tile's content color with reduced alpha. If that tint would reduce
 * text contrast, black or white is selected for the number as the accessible fallback.
 */
internal fun notificationBadgeColors(
    tileSurface: Color,
    tileContentColor: Color,
): NotificationBadgeColors {
    val surface = tileSurface.copy(alpha = 1f)
    val content = tileContentColor.copy(alpha = 1f)
    val background = content.copy(alpha = NotificationBadgeBackgroundAlpha)
    val renderedBackground = background.compositeOver(surface)
    val contentContrast = contrastRatioArgb(
        renderedBackground.toArgb(),
        content.toArgb(),
    )
    val whiteContrast = contrastRatioArgb(renderedBackground.toArgb(), Color.White.toArgb())
    val blackContrast = contrastRatioArgb(renderedBackground.toArgb(), Color.Black.toArgb())
    val readableContent = when {
        contentContrast >= NotificationBadgeMinimumTextContrast -> content
        whiteContrast >= blackContrast -> Color.White
        else -> Color.Black
    }
    return NotificationBadgeColors(
        background = background,
        border = content.copy(alpha = NotificationBadgeBorderAlpha),
        content = readableContent,
    )
}

private const val NotificationBadgeBackgroundAlpha = 0.18f
private const val NotificationBadgeBorderAlpha = 0.62f
private const val NotificationBadgeMinimumTextContrast = 4.5f
