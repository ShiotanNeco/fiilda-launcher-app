package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class AgendaSupportTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val now = LocalDateTime.of(2026, 9, 28, 12, 0)

    private fun timed(id: Long, begin: LocalDateTime, end: LocalDateTime, location: String = "") = AgendaEvent(
        eventId = id,
        title = "event$id",
        beginMillis = begin.atZone(tokyo).toInstant().toEpochMilli(),
        endMillis = end.atZone(tokyo).toInstant().toEpochMilli(),
        allDay = false,
        location = location,
    )

    private fun allDay(id: Long, start: LocalDate, endExclusive: LocalDate) = AgendaEvent(
        eventId = id,
        title = "allday$id",
        beginMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        endMillis = endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        allDay = true,
        location = "",
    )

    @Test
    fun finishedEventsAreDroppedAndNextEventIsActive() {
        val entries = todaysAgenda(
            listOf(
                timed(1, now.withHour(9), now.withHour(10)),
                timed(3, now.withHour(18), now.withHour(19), location = "高松駅"),
                timed(2, now.withHour(15), now.withHour(16)),
            ),
            now,
            tokyo,
        )

        assertEquals(listOf(2L, 3L), entries.map { it.event.eventId })
        assertTrue(entries[0].active)
        assertFalse(entries[1].active)
        assertEquals("15:00", entries[0].timeLabel)
        assertEquals("15:00–16:00", entries[0].detail)
        assertEquals("高松駅", entries[1].detail)
    }

    @Test
    fun ongoingEventStaysVisibleUntilItEnds() {
        val entries = todaysAgenda(listOf(timed(1, now.withHour(11), now.withHour(13))), now, tokyo)

        assertEquals(1, entries.size)
        assertTrue(entries.single().active)
    }

    @Test
    fun allDayEventsUseUtcDatesSoYesterdayIsNotShownEastOfUtc() {
        val today = now.toLocalDate()
        val entries = todaysAgenda(
            listOf(
                allDay(1, today.minusDays(1), today),
                allDay(2, today, today.plusDays(1)),
                allDay(3, today.minusDays(2), today.plusDays(2)),
            ),
            now,
            tokyo,
        )

        assertEquals(listOf(2L, 3L), entries.map { it.event.eventId })
        assertTrue(entries.all { it.timeLabel == "終日" })
    }

    @Test
    fun eventsStartedBeforeTodayShowTheirEndTime() {
        val entries = todaysAgenda(
            listOf(
                timed(1, now.minusDays(1).withHour(22), now.withHour(14)),
                timed(2, now.withHour(23), now.plusDays(1).withHour(1)),
            ),
            now,
            tokyo,
        )

        assertEquals("〜14:00", entries[0].timeLabel)
        assertEquals("23:00–翌日", entries[1].detail)
    }

    @Test
    fun tomorrowsEventsAreExcluded() {
        val entries = todaysAgenda(
            listOf(timed(1, now.plusDays(1).withHour(9), now.plusDays(1).withHour(10))),
            now,
            tokyo,
        )

        assertTrue(entries.isEmpty())
    }
}
