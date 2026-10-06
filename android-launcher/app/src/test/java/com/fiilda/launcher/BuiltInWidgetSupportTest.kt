package com.fiilda.launcher

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class BuiltInWidgetSupportTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val now = LocalDateTime.of(2026, 9, 28, 21, 0)

    private fun millis(time: LocalDateTime) = time.atZone(tokyo).toInstant().toEpochMilli()

    @Test
    fun everyThemeHasItsOwnWidgetLanguage() {
        assertEquals(WidgetLanguage.KEYBOARD, widgetLanguageFor(LauncherTheme.DEFAULT))
        assertEquals(WidgetLanguage.NISHIKIGOI, widgetLanguageFor(LauncherTheme.CLASSIC))
        assertEquals(WidgetLanguage.METRO, widgetLanguageFor(LauncherTheme.WINDOWS_8))
        assertEquals(WidgetLanguage.MATERIAL, widgetLanguageFor(LauncherTheme.MATERIAL))
        assertEquals(WidgetLanguage.GLASS, widgetLanguageFor(LauncherTheme.GLASS))
    }

    @Test
    fun monthGridStartsOnSundayAndCoversWholeWeeks() {
        val cells = calendarMonthCells(YearMonth.of(2026, 9))

        assertEquals(35, cells.size)
        assertEquals(LocalDate.of(2026, 8, 30), cells.first())
        assertEquals(DayOfWeek.SUNDAY, cells.first().dayOfWeek)
        assertEquals(LocalDate.of(2026, 10, 3), cells.last())
        // August 2026 starts on a Saturday and needs six weeks.
        assertEquals(42, calendarMonthCells(YearMonth.of(2026, 8)).size)
    }

    @Test
    fun alarmLabelIsRelativeToToday() {
        assertEquals("23:30", nextAlarmLabel(millis(now.withHour(23).withMinute(30)), now, tokyo))
        assertEquals("明日 7:00", nextAlarmLabel(millis(now.plusDays(1).withHour(7)), now, tokyo))
        assertEquals("10/2(金) 7:00", nextAlarmLabel(millis(LocalDateTime.of(2026, 10, 2, 7, 0)), now, tokyo))
    }

    @Test
    fun countdownCoversUpcomingOngoingAndDistantEvents() {
        fun event(begin: LocalDateTime, end: LocalDateTime, allDay: Boolean = false) =
            AgendaEvent(1, "e", millis(begin), millis(end), allDay, "")

        assertEquals("あと25分", agendaCountdownLabel(event(now.plusMinutes(25), now.plusMinutes(60)), now, tokyo))
        assertEquals("あと2時間", agendaCountdownLabel(event(now.plusMinutes(130), now.plusMinutes(180)), now, tokyo))
        assertEquals("進行中", agendaCountdownLabel(event(now.minusMinutes(5), now.plusMinutes(5)), now, tokyo))
        assertNull(agendaCountdownLabel(event(now.plusHours(13), now.plusHours(14)), now, tokyo))
        assertNull(agendaCountdownLabel(event(now, now.plusDays(1), allDay = true), now, tokyo))
    }

    @Test
    fun batteryStatusReflectsChargingState() {
        val charging = batteryStatusFromExtras(
            level = 36,
            scale = 50,
            status = BatteryManager.BATTERY_STATUS_CHARGING,
            plugged = BatteryManager.BATTERY_PLUGGED_USB,
            temperatureTenths = 314,
            health = BatteryManager.BATTERY_HEALTH_GOOD,
            chargeTimeRemainingMillis = 80 * 60_000L,
        )

        assertEquals(72, charging.percent)
        assertEquals(BatteryPowerSource.USB, charging.source)
        assertEquals(31.4f, charging.temperatureC!!, 0.001f)
        assertEquals("良好", charging.healthLabel)
        assertEquals("充電中 · 満充電まで1時間20分", batteryStatusLabel(charging))

        val unplugged = charging.copy(charging = false, source = null, chargeTimeRemainingMillis = null)
        assertEquals("電源未接続", batteryStatusLabel(unplugged))
        assertEquals("充電完了", batteryStatusLabel(charging.copy(percent = 100, charging = false)))
    }

    @Test
    fun durationLabelRoundsUpToWholeMinutes() {
        assertEquals("1分", durationLabel(10_000L))
        assertEquals("45分", durationLabel(45 * 60_000L))
        assertEquals("2時間", durationLabel(120 * 60_000L))
        assertEquals("1時間5分", durationLabel(65 * 60_000L - 30_000L))
    }

    @Test
    fun metroArtworkColorIsDarkenedUntilWhiteTextIsReadable() {
        val white = 0xFFFFFFFF.toInt()
        listOf(0xFFFFD166.toInt(), 0xFF9AD8FF.toInt(), 0xFF60A917.toInt()).forEach { light ->
            val readable = readableMetroTileColor(light)
            assert(contrastRatioArgb(readable, white) >= 5f) { Integer.toHexString(readable) }
        }
        // Already dark colors are returned unchanged.
        assertEquals(0xFF0063B1.toInt(), readableMetroTileColor(0xFF0063B1.toInt()))
    }
}
