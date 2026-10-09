package com.fiilda.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal data class ThemeRotationConfig(
    val enabled: Boolean = false,
    val intervalMillis: Long = ThemeRotationInterval.ONE_HOUR.millis,
    val themes: Set<LauncherTheme> = LauncherTheme.values().toSet(),
)

internal enum class ThemeRotationInterval(val millis: Long, private val jaLabel: String, private val enLabel: String) {
    FIFTEEN_MINUTES(15 * 60 * 1000L, "15分", "15 min"),
    THIRTY_MINUTES(30 * 60 * 1000L, "30分", "30 min"),
    ONE_HOUR(60 * 60 * 1000L, "1時間", "1 hour"),
    THREE_HOURS(3 * 60 * 60 * 1000L, "3時間", "3 hours"),
    SIX_HOURS(6 * 60 * 60 * 1000L, "6時間", "6 hours"),
    TWELVE_HOURS(12 * 60 * 60 * 1000L, "12時間", "12 hours"),
    ONE_DAY(24 * 60 * 60 * 1000L, "1日", "1 day"),
    ;

    val label: String get() = tr(jaLabel, enLabel)
}

internal const val ThemeRotationEnabledKey = "theme_rotation_enabled"
private const val ThemeRotationIntervalKey = "theme_rotation_interval_millis"
private const val ThemeRotationThemesKey = "theme_rotation_themes"
private const val ThemeRotationRequestCode = 8314
private const val ThemeRotationAction = "com.fiilda.launcher.action.ROTATE_THEME"

internal fun readThemeRotationConfig(context: Context): ThemeRotationConfig = runCatching {
    val preferences = context.getSharedPreferences(LauncherThemePreferencesName, Context.MODE_PRIVATE)
    val allThemes = LauncherTheme.values().toSet()
    val savedThemes = preferences.getStringSet(ThemeRotationThemesKey, null)
        ?.mapNotNull { token -> allThemes.firstOrNull { it.token == token } }
        ?.toSet()
        ?.takeIf { it.size >= 2 }
        ?: allThemes
    ThemeRotationConfig(
        enabled = preferences.getBoolean(ThemeRotationEnabledKey, false),
        intervalMillis = preferences.getLong(
            ThemeRotationIntervalKey,
            ThemeRotationInterval.ONE_HOUR.millis,
        ).takeIf { interval -> ThemeRotationInterval.values().any { it.millis == interval } }
            ?: ThemeRotationInterval.ONE_HOUR.millis,
        themes = savedThemes,
    )
}.getOrDefault(ThemeRotationConfig())

internal fun saveThemeRotationConfig(context: Context, config: ThemeRotationConfig): Boolean {
    if (config.themes.size < 2 || config.themes.any { it !in LauncherTheme.values() }) return false
    if (ThemeRotationInterval.values().none { it.millis == config.intervalMillis }) return false
    return runCatching {
        val editor = context.getSharedPreferences(LauncherThemePreferencesName, Context.MODE_PRIVATE)
            .edit()
        if (config.enabled) {
            if (readSystemThemeConfig(context).enabled) {
                editor.putString(LauncherThemePreferenceKey, currentSystemTheme(context).token)
            }
            editor.putBoolean(SystemThemeEnabledKey, false)
        }
        editor.putBoolean(ThemeRotationEnabledKey, config.enabled)
            .putLong(ThemeRotationIntervalKey, config.intervalMillis)
            .putStringSet(ThemeRotationThemesKey, config.themes.map { it.token }.toSet())
            .commit()
    }.getOrDefault(false)
}

internal fun nextThemeInRotation(
    current: LauncherTheme,
    selectedThemes: Set<LauncherTheme>,
): LauncherTheme? {
    val orderedThemes = LauncherTheme.values().filter { it in selectedThemes }
    if (orderedThemes.size < 2) return null
    val currentIndex = orderedThemes.indexOf(current)
    return orderedThemes[(currentIndex + 1).mod(orderedThemes.size)]
}

internal object ThemeRotationScheduler {
    fun update(context: Context, config: ThemeRotationConfig): Boolean =
        runCatching {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
                ?: return@runCatching false
            val pendingIntent = rotationPendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)
                ?: return@runCatching false
            if (!config.enabled || readSystemThemeConfig(context).enabled || config.themes.size < 2) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                return@runCatching true
            }
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + config.intervalMillis,
                pendingIntent,
            )
            true
        }.getOrDefault(false)

    fun cancel(context: Context) {
        runCatching {
            val existing = rotationPendingIntent(context, PendingIntent.FLAG_NO_CREATE) ?: return@runCatching
            context.getSystemService(AlarmManager::class.java)?.cancel(existing)
            existing.cancel()
        }
    }

    private fun rotationPendingIntent(context: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            ThemeRotationRequestCode,
            Intent(context, ThemeRotationReceiver::class.java).setAction(ThemeRotationAction),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )
}

class ThemeRotationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            ThemeRotationScheduler.update(appContext, readThemeRotationConfig(appContext))
            return
        }
        if (intent?.action != ThemeRotationAction) return

        val config = readThemeRotationConfig(appContext)
        if (!config.enabled || readSystemThemeConfig(appContext).enabled || config.themes.size < 2) {
            ThemeRotationScheduler.cancel(appContext)
            return
        }
        nextThemeInRotation(readLauncherTheme(appContext), config.themes)?.let { nextTheme ->
            saveLauncherTheme(appContext, nextTheme)
        }
        ThemeRotationScheduler.update(appContext, config)
    }
}
