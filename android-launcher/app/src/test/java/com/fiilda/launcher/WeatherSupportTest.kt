package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeatherSupportTest {
    private val body = """
        {
          "current": {"time": "2026-09-28T12:00", "temperature_2m": 24.6, "apparent_temperature": 26.2, "weather_code": 61},
          "daily": {
            "time": ["2026-09-27", "2026-09-28", "2026-09-29"],
            "weather_code": [0, 3, 95],
            "temperature_2m_max": [29.4, 30.5, 27.0],
            "temperature_2m_min": [21.0, 22.4, 19.6],
            "precipitation_probability_max": [0, null, 80]
          }
        }
    """.trimIndent()

    @Test
    fun parsesCurrentAndDailyForecast() {
        val forecast = parseOpenMeteoForecast(body)!!

        assertEquals(25, forecast.temperatureC)
        assertEquals(26, forecast.apparentC)
        assertEquals(WeatherKind.RAIN, forecast.kind)
        assertEquals(3, forecast.daily.size)
        assertEquals(DailyWeather(LocalDate.of(2026, 9, 29), WeatherKind.THUNDER, 27, 20, 80), forecast.daily[2])
        assertNull(forecast.daily[1].precipitationPercent)
    }

    @Test
    fun malformedBodyYieldsNull() {
        assertNull(parseOpenMeteoForecast("{\"current\": {}}"))
        assertNull(parseOpenMeteoForecast("not json"))
    }

    @Test
    fun cachedDaysBeforeTodayAreDropped() {
        val report = WeatherReport(parseOpenMeteoForecast(body)!!, placeName = "高松市", fetchedAtMillis = 0L)

        assertEquals(
            listOf(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 29)),
            report.dailyFrom(LocalDate.of(2026, 9, 28)).map { it.date },
        )
    }

    @Test
    fun wmoCodesMapToJapaneseKinds() {
        assertEquals(WeatherKind.CLEAR, weatherKindForCode(0))
        assertEquals(WeatherKind.PARTLY_CLOUDY, weatherKindForCode(2))
        assertEquals(WeatherKind.FOG, weatherKindForCode(48))
        assertEquals(WeatherKind.DRIZZLE, weatherKindForCode(53))
        assertEquals(WeatherKind.SHOWERS, weatherKindForCode(81))
        assertEquals(WeatherKind.SNOW, weatherKindForCode(86))
        assertEquals(WeatherKind.UNKNOWN, weatherKindForCode(42))
    }

    @Test
    fun forecastUrlUsesDotDecimalsRegardlessOfLocale() {
        val url = openMeteoForecastUrl(34.3428, 134.0466)

        assertEquals(true, url.contains("latitude=34.34&longitude=134.05"))
    }

    @Test
    fun dayLabelsUseTodayThenJapaneseWeekdays() {
        val today = LocalDate.of(2026, 9, 28)

        assertEquals("今日", forecastDayLabel(today, today))
        assertEquals("火", forecastDayLabel(today.plusDays(1), today))
        assertEquals("日", forecastDayLabel(today.plusDays(6), today))
    }
}
