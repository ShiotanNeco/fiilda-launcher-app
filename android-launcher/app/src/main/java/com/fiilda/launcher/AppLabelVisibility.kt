package com.fiilda.launcher

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

/** Stable preference key for the launcher-wide app-name visibility setting. */
internal const val ShowAppLabelsPreferenceKey = "show_app_labels"
internal const val DefaultShowAppLabels = true

/**
 * Keeps the missing-value behavior explicit and independently testable. Existing installs have no
 * record yet, so app names remain visible unless the user has deliberately disabled them.
 */
internal fun showAppLabelsFromStoredValue(storedValue: Boolean?): Boolean =
    storedValue ?: DefaultShowAppLabels

/** Returns whether a label should be drawn; shortcut labels are intentionally unaffected. */
internal fun shouldRenderAppLabel(showAppLabels: Boolean, isAppLabel: Boolean = true): Boolean =
    !isAppLabel || showAppLabels

internal fun readShowAppLabels(context: Context): Boolean = runCatching {
    val preferences = context.getSharedPreferences(
        LauncherThemePreferencesName,
        Context.MODE_PRIVATE,
    )
    showAppLabelsFromStoredValue(
        preferences.getBoolean(ShowAppLabelsPreferenceKey, DefaultShowAppLabels),
    )
}.getOrDefault(DefaultShowAppLabels)

/** Uses commit so callers can keep Compose state unchanged when the write fails. */
internal fun saveShowAppLabels(context: Context, show: Boolean): Boolean = runCatching {
    context.getSharedPreferences(LauncherThemePreferencesName, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(ShowAppLabelsPreferenceKey, show)
        .commit()
}.getOrDefault(false)

internal val LocalShowAppLabels = staticCompositionLocalOf { DefaultShowAppLabels }
internal val LocalShowAppLabelsChanger = staticCompositionLocalOf<(Boolean) -> Boolean> {
    { false }
}
