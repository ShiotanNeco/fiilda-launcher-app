package com.fiilda.launcher

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The launcher speaks Japanese when the device (or per-app) language is Japanese, and English
 * otherwise. The default locale is read on every call so a language change applies as soon as
 * the activity is recreated.
 */
internal fun isJapaneseUi(): Boolean = Locale.getDefault().language == Locale.JAPANESE.language

/** Picks the Japanese or English text for the current UI language. */
internal fun tr(ja: String, en: String): String = if (isJapaneseUi()) ja else en

private val JapaneseWeekdayLetters = listOf("月", "火", "水", "木", "金", "土", "日")
private val EnglishShortDate = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)

/** "9月28日 月曜日" or "Mon, Sep 28". */
internal fun fullDateLabel(date: LocalDate): String =
    if (isJapaneseUi()) "${date.monthValue}月${date.dayOfMonth}日 ${weekdayLabel(date)}" else date.format(EnglishShortDate)

/** "月曜日" or "Monday". */
internal fun weekdayLabel(date: LocalDate): String =
    if (isJapaneseUi()) "${shortWeekday(date.dayOfWeek)}曜日" else date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

/** "月" or "Mon". */
internal fun shortWeekday(day: DayOfWeek): String =
    if (isJapaneseUi()) JapaneseWeekdayLetters[day.value - 1] else day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

/** Calendar column header: "月" or "M". */
internal fun weekdayInitial(day: DayOfWeek): String =
    if (isJapaneseUi()) JapaneseWeekdayLetters[day.value - 1] else day.getDisplayName(TextStyle.NARROW, Locale.ENGLISH)

/** "September 2026". */
internal fun englishMonthYear(month: YearMonth): String =
    "${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${month.year}"
