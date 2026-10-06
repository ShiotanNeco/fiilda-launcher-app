package com.fiilda.launcher

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

/** Stable preference key for keeping the wide Start-canvas order separate from the narrow pages. */
internal const val SeparateWideHomeOrderPreferenceKey = "separate_wide_home_order"
internal const val DefaultSeparateWideHomeOrder = false

/** Missing or unreadable preference values retain the existing shared-order behavior. */
internal fun separateWideHomeOrderFromStoredValue(storedValue: Boolean?): Boolean =
    storedValue ?: DefaultSeparateWideHomeOrder

internal fun readSeparateWideHomeOrder(context: Context): Boolean = runCatching {
    val preferences = context.getSharedPreferences(
        LauncherThemePreferencesName,
        Context.MODE_PRIVATE,
    )
    separateWideHomeOrderFromStoredValue(
        preferences.getBoolean(
            SeparateWideHomeOrderPreferenceKey,
            DefaultSeparateWideHomeOrder,
        ),
    )
}.getOrDefault(DefaultSeparateWideHomeOrder)

/** Uses commit so a failed setting write cannot be reflected by Compose state. */
internal fun saveSeparateWideHomeOrder(context: Context, separate: Boolean): Boolean = runCatching {
    context.getSharedPreferences(LauncherThemePreferencesName, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(SeparateWideHomeOrderPreferenceKey, separate)
        .commit()
}.getOrDefault(false)

internal val LocalSeparateWideHomeOrder = staticCompositionLocalOf {
    DefaultSeparateWideHomeOrder
}
internal val LocalSeparateWideHomeOrderChanger = staticCompositionLocalOf<(Boolean) -> Boolean> {
    { false }
}
