package com.fiilda.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Profiling builds only: `adb shell am broadcast -n com.fiilda.launcher/.ProfilingThemeReceiver
 * --es theme glass` switches the theme without run-as, which non-debuggable builds refuse.
 */
class ProfilingThemeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val theme = parseLauncherThemeToken(intent.getStringExtra("theme"))
        context.getSharedPreferences(LauncherThemePreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(LauncherThemePreferenceKey, theme.token)
            .commit()
        resultData = theme.token
    }
}
