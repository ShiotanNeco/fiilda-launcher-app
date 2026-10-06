package com.fiilda.launcher

import android.os.BatteryManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

class UiLanguageTest {
    private val original = Locale.getDefault()
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val now = LocalDateTime.of(2026, 9, 28, 21, 0)

    @Before
    fun useEnglish() = Locale.setDefault(Locale.US)

    @After
    fun restore() = Locale.setDefault(original)

    private fun millis(time: LocalDateTime) = time.atZone(tokyo).toInstant().toEpochMilli()

    @Test
    fun languageFollowsTheDefaultLocale() {
        assertFalse(isJapaneseUi())
        assertEquals("Weather", tr("天気", "Weather"))
        Locale.setDefault(Locale.JAPAN)
        assertTrue(isJapaneseUi())
        assertEquals("天気", tr("天気", "Weather"))
        // Any non-Japanese language falls back to English.
        Locale.setDefault(Locale.FRANCE)
        assertEquals("Weather", tr("天気", "Weather"))
    }

    @Test
    fun datesUseEnglishNames() {
        val date = LocalDate.of(2026, 9, 28)
        assertEquals("Mon, Sep 28", fullDateLabel(date))
        assertEquals("Monday", weekdayLabel(date))
        assertEquals("Mon", shortWeekday(DayOfWeek.MONDAY))
        assertEquals("S", weekdayInitial(DayOfWeek.SUNDAY))
        assertEquals("September 2026", englishMonthYear(YearMonth.of(2026, 9)))
        assertEquals("Today", forecastDayLabel(date, date))
        assertEquals("Tue", forecastDayLabel(date.plusDays(1), date))
    }

    @Test
    fun japaneseDatesAreUnchanged() {
        Locale.setDefault(Locale.JAPAN)
        assertEquals("9月28日 月曜日", fullDateLabel(LocalDate.of(2026, 9, 28)))
        assertEquals("日", weekdayInitial(DayOfWeek.SUNDAY))
    }

    @Test
    fun alarmAndCountdownLabelsAreEnglish() {
        assertEquals("Tomorrow 7:00", nextAlarmLabel(millis(now.plusDays(1).withHour(7)), now, tokyo))
        assertEquals("Fri 10/2 7:00", nextAlarmLabel(millis(LocalDateTime.of(2026, 10, 2, 7, 0)), now, tokyo))
        val soon = AgendaEvent(1, "e", millis(now.plusMinutes(25)), millis(now.plusMinutes(60)), false, "")
        assertEquals("in 25 min", agendaCountdownLabel(soon, now, tokyo))
        val ongoing = AgendaEvent(2, "e", millis(now.minusMinutes(5)), millis(now.plusMinutes(5)), false, "")
        assertEquals("Now", agendaCountdownLabel(ongoing, now, tokyo))
    }

    @Test
    fun batteryAndWeatherLabelsAreEnglish() {
        val charging = batteryStatusFromExtras(
            level = 72,
            scale = 100,
            status = BatteryManager.BATTERY_STATUS_CHARGING,
            plugged = BatteryManager.BATTERY_PLUGGED_USB,
            temperatureTenths = 300,
            health = BatteryManager.BATTERY_HEALTH_GOOD,
            chargeTimeRemainingMillis = 80 * 60_000L,
        )
        assertEquals("Charging · full in 1 h 20 min", batteryStatusLabel(charging))
        assertEquals("Good", charging.healthLabel)
        assertEquals("Not plugged in", batteryStatusLabel(charging.copy(charging = false, source = null)))
        assertEquals("Thunderstorm", WeatherKind.THUNDER.label)
        assertEquals("Weekly forecast", HomeWidget.FORECAST.label)
        assertEquals("Windows", LauncherTheme.WINDOWS_8.displayName)
    }

    @Test
    fun enumLabelsFollowALanguageChangeWithoutRestart() {
        assertEquals("Clear", WeatherKind.CLEAR.label)
        Locale.setDefault(Locale.JAPAN)
        assertEquals("快晴", WeatherKind.CLEAR.label)
    }
}
