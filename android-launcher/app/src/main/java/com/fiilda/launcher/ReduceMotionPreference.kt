package com.fiilda.launcher

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

/** Stable preference key for the launcher-wide "reduce animation effects" setting. */
internal const val ReduceMotionPreferenceKey = "reduce_motion"
internal const val DefaultReduceMotion = false

internal fun readReduceMotion(context: Context): Boolean = runCatching {
    context.getSharedPreferences(LauncherThemePreferencesName, Context.MODE_PRIVATE)
        .getBoolean(ReduceMotionPreferenceKey, DefaultReduceMotion)
}.getOrDefault(DefaultReduceMotion)

/** Uses commit so callers can keep Compose state unchanged when the write fails. */
internal fun saveReduceMotion(context: Context, reduce: Boolean): Boolean = runCatching {
    context.getSharedPreferences(LauncherThemePreferencesName, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(ReduceMotionPreferenceKey, reduce)
        .commit()
}.getOrDefault(false)

internal val LocalReduceMotion = staticCompositionLocalOf { DefaultReduceMotion }
internal val LocalReduceMotionChanger = staticCompositionLocalOf<(Boolean) -> Boolean> {
    { false }
}
