package com.fiilda.launcher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One calendar instance as shown on the agenda tile. */
internal data class AgendaEvent(
    val eventId: Long,
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val location: String,
    /** The calendar's display color, used as a thin marker beside the event. */
    val colorArgb: Int? = null,
)

internal data class AgendaEntry(
    val event: AgendaEvent,
    val timeLabel: String,
    val detail: String,
    val active: Boolean,
)

internal data class AgendaUiState(
    val hasAccess: Boolean,
    val loaded: Boolean,
    val entries: List<AgendaEntry>,
    val requestAccess: () -> Unit,
)

private val AgendaTimeFormat = DateTimeFormatter.ofPattern("H:mm")

/**
 * Keeps today's all-day events and the timed events that have not finished yet. All-day instances
 * are stored as UTC midnights, so their dates are read in UTC; a local-day query alone would also
 * return yesterday's all-day event east of UTC.
 */
internal fun todaysAgenda(
    events: List<AgendaEvent>,
    now: LocalDateTime,
    zone: ZoneId,
): List<AgendaEntry> {
    val today = now.toLocalDate()
    val nowMillis = now.atZone(zone).toInstant().toEpochMilli()
    val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val allDay = events.filter { it.allDay }.filter { event ->
        val start = Instant.ofEpochMilli(event.beginMillis).atOffset(ZoneOffset.UTC).toLocalDate()
        val end = Instant.ofEpochMilli(event.endMillis).atOffset(ZoneOffset.UTC).toLocalDate()
        !today.isBefore(start) && today.isBefore(maxOf(end, start.plusDays(1)))
    }
    val timed = events
        .filter { !it.allDay && it.endMillis > nowMillis && it.beginMillis < dayEnd }
        .sortedWith(compareBy({ it.beginMillis }, { it.endMillis }))
    val activeId = timed.firstOrNull()?.let { it.eventId to it.beginMillis }
    return allDay.map { event ->
        AgendaEntry(event, timeLabel = tr("終日", "All day"), detail = event.location, active = false)
    } + timed.map { event ->
        val begin = Instant.ofEpochMilli(event.beginMillis).atZone(zone).toLocalTime()
        val end = Instant.ofEpochMilli(event.endMillis).atZone(zone)
        val endLabel = if (end.toLocalDate() == today) end.format(AgendaTimeFormat) else tr("翌日", "next day")
        AgendaEntry(
            event = event,
            timeLabel = if (event.beginMillis < dayStart) tr("〜${endLabel}", "until ${endLabel}") else begin.format(AgendaTimeFormat),
            detail = event.location.ifBlank {
                if (event.beginMillis < dayStart) "" else "${begin.format(AgendaTimeFormat)}–$endLabel"
            },
            active = (event.eventId to event.beginMillis) == activeId,
        )
    }
}

/** "進行中", "あと12分", "あと2時間" for timed events; null for all-day or distant events. */
internal fun agendaCountdownLabel(event: AgendaEvent, now: LocalDateTime, zone: ZoneId): String? {
    if (event.allDay) return null
    val nowMillis = now.atZone(zone).toInstant().toEpochMilli()
    if (event.beginMillis <= nowMillis) return if (event.endMillis > nowMillis) tr("進行中", "Now") else null
    val minutes = (event.beginMillis - nowMillis + 59_999L) / 60_000L
    return when {
        minutes < 60 -> tr("あと${minutes}分", "in ${minutes} min")
        minutes < 12 * 60 -> tr("あと${minutes / 60}時間", "in ${minutes / 60} h")
        else -> null
    }
}

private fun queryTodaysEvents(context: Context, date: LocalDate, zone: ZoneId): List<AgendaEvent> {
    // Widen by a day on each side so all-day instances in any time zone are returned; the
    // pure filter decides what belongs to today.
    val begin = date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = date.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, begin)
        ContentUris.appendId(it, end)
    }.build()
    val projection = arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.DISPLAY_COLOR,
    )
    val selection = "${CalendarContract.Instances.VISIBLE} = 1 AND " +
        "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}"
    val events = mutableListOf<AgendaEvent>()
    context.contentResolver.query(uri, projection, selection, null, "${CalendarContract.Instances.BEGIN} ASC")
        ?.use { cursor ->
            while (cursor.moveToNext()) {
                events += AgendaEvent(
                    eventId = cursor.getLong(0),
                    title = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: tr("（タイトルなし）", "(No title)"),
                    beginMillis = cursor.getLong(2),
                    endMillis = cursor.getLong(3),
                    allDay = cursor.getInt(4) != 0,
                    location = cursor.getString(5).orEmpty(),
                    colorArgb = if (cursor.isNull(6)) null else cursor.getInt(6),
                )
            }
        }
    return events
}

@Composable
internal fun rememberAgendaState(now: LocalDateTime): AgendaUiState {
    val context = LocalContext.current
    val permission = rememberGlancePermission(Manifest.permission.READ_CALENDAR)
    val zone = ZoneId.systemDefault()
    var reloadToken by remember { mutableIntStateOf(0) }
    // Calendar sync can land while the launcher is visible; observe only while resumed.
    LifecycleResumeEffect(permission.granted) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reloadToken++
            }
        }
        if (permission.granted) {
            reloadToken++
            runCatching {
                context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            }
        }
        onPauseOrDispose { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }
    // The resume effect advances the token, so returning from the calendar app reloads today.
    val events by produceState<List<AgendaEvent>?>(null, now.toLocalDate(), reloadToken) {
        if (!permission.granted) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { queryTodaysEvents(context, now.toLocalDate(), zone) }.getOrNull()
        } ?: value
    }
    val entries = remember(events, now) { todaysAgenda(events.orEmpty(), now, zone) }
    return AgendaUiState(
        hasAccess = permission.granted,
        loaded = events != null,
        entries = entries,
        requestAccess = permission.request,
    )
}

internal fun openCalendarEvent(context: Context, event: AgendaEvent) {
    val intent = Intent(
        Intent.ACTION_VIEW,
        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId),
    ).apply {
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.beginMillis)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endMillis)
    }
    runCatching { context.startActivity(intent) }
}

internal fun openCalendarDay(context: Context, now: LocalDateTime) {
    val millis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also {
        ContentUris.appendId(it, millis)
    }.build()
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

internal fun insertCalendarEvent(context: Context) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI))
    }
}
