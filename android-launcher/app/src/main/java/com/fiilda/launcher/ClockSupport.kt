package com.fiilda.launcher

import android.app.AlarmManager
import android.content.Context
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val AlarmTimeFormat = DateTimeFormatter.ofPattern("H:mm")

/** "7:00", "明日 7:00" / "Tomorrow 7:00", or "10/2(金) 7:00" / "Fri 10/2 7:00" relative to [now]. */
internal fun nextAlarmLabel(triggerMillis: Long, now: LocalDateTime, zone: ZoneId): String {
    val alarm = Instant.ofEpochMilli(triggerMillis).atZone(zone).toLocalDateTime()
    val time = alarm.format(AlarmTimeFormat)
    val today = now.toLocalDate()
    return when (alarm.toLocalDate()) {
        today -> time
        today.plusDays(1) -> tr("明日 $time", "Tomorrow $time")
        else -> {
            val weekday = shortWeekday(alarm.dayOfWeek)
            tr("${alarm.monthValue}/${alarm.dayOfMonth}($weekday) $time", "$weekday ${alarm.monthValue}/${alarm.dayOfMonth} $time")
        }
    }
}

/** The next alarm set in any clock app, read without a permission. */
internal fun nextAlarmTriggerMillis(context: Context): Long? =
    runCatching { context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime }.getOrNull()

/** Reiwa year for the Japanese calendar line; Reiwa 1 is 2019. */
internal fun reiwaYear(year: Int): Int = year - 2018
