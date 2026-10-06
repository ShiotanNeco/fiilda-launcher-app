package com.fiilda.launcher

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DynamicAppIconsTest {
    @Test
    fun calendarDayIndexResetsAtMonthRollover() {
        val zone = TimeZone.getTimeZone("Asia/Tokyo")
        assertEquals(30, calendarDayIndexAt(epochMillis(zone, 2026, 1, 31), zone))
        assertEquals(0, calendarDayIndexAt(epochMillis(zone, 2026, 2, 1), zone))
    }

    @Test
    fun calendarDayIndexUsesCurrentTimezone() {
        val utc = TimeZone.getTimeZone("UTC")
        val instant = epochMillis(utc, 2026, 1, 1, hour = 0, minute = 30)
        assertEquals(0, calendarDayIndexAt(instant, TimeZone.getTimeZone("Asia/Tokyo")))
        assertEquals(30, calendarDayIndexAt(instant, TimeZone.getTimeZone("America/Los_Angeles")))
    }

    @Test
    fun clockHandLevelsFollowAospOffsets() {
        assertEquals(
            ClockHandLevels(hour = 184, minute = 184, second = 50),
            clockHandLevelsAt(
                hour12 = 3,
                minute = 4,
                second = 5,
                defaultHour = 0,
                defaultMinute = 0,
                defaultSecond = 0,
            ),
        )
        assertEquals(
            ClockHandLevels(hour = 327, minute = 197, second = 110),
            clockHandLevelsAt(
                hour12 = 3,
                minute = 27,
                second = 41,
                defaultHour = 10,
                defaultMinute = 10,
                defaultSecond = 30,
            ),
        )
    }

    @Test
    fun malformedClockMetadataFallsBack() {
        val noUsableHands = Bundle().apply {
            putInt("com.android.launcher3.LEVEL_PER_TICK_ICON_ROUND", 1)
            putInt("com.android.launcher3.HOUR_LAYER_INDEX", 9)
            putInt("com.android.launcher3.MINUTE_LAYER_INDEX", -1)
            putInt("com.android.launcher3.SECOND_LAYER_INDEX", -1)
        }
        assertNull(parseClockDynamicIconMetadata(noUsableHands, layerCount = 3))

        val wrongType = Bundle().apply {
            putString("com.android.launcher3.LEVEL_PER_TICK_ICON_ROUND", "drawable")
            putInt("com.android.launcher3.HOUR_LAYER_INDEX", 0)
        }
        assertNull(parseClockDynamicIconMetadata(wrongType, layerCount = 3))

        val oneHand = Bundle().apply {
            putInt("com.android.launcher3.LEVEL_PER_TICK_ICON_ROUND", 1)
            putInt("com.android.launcher3.HOUR_LAYER_INDEX", 0)
        }
        assertEquals(
            0,
            parseClockDynamicIconMetadata(oneHand, layerCount = 3)?.hourLayerIndex,
        )
    }

    @Test
    fun tickerStopsWhenNoVisibleDynamicIconsAndUsesSecondHandCadence() {
        assertNull(dynamicIconTickIntervalMillis(false, false))
        assertEquals(
            DynamicIconMinuteTickMillis,
            dynamicIconTickIntervalMillis(true, false),
        )
        assertEquals(
            DynamicIconSecondTickMillis,
            dynamicIconTickIntervalMillis(true, true),
        )
    }

    @Test
    fun eachRenderedViewReceivesAnIndependentDynamicDrawable() {
        val context = RuntimeEnvironment.getApplication()
        val updateCount = AtomicInteger()
        val spec = object : DynamicAppIconSpec() {
            override val updateIntervalMillis: Long = DynamicIconSecondTickMillis
            override val hasSecondHand: Boolean = true
            override fun stateKey(atMillis: Long): Long = 0L
            override fun newDrawable(
                context: android.content.Context,
                atMillis: Long,
            ) = ColorDrawable(Color.RED)
            override fun updateDrawable(
                context: android.content.Context,
                current: android.graphics.drawable.Drawable,
                atMillis: Long,
            ): android.graphics.drawable.Drawable? {
                updateCount.incrementAndGet()
                return null
            }
        }
        val app = LaunchableApp(
            packageName = "example.package",
            className = "example.package.MainActivity",
            label = "Example",
            icon = ColorDrawable(Color.BLUE),
            tileColorArgb = Color.BLUE,
            tileContentColorArgb = Color.WHITE,
            dynamicIcon = spec,
        )
        val first = DynamicIconImageView(context)
        val second = DynamicIconImageView(context)
        first.bindApp(app)
        second.bindApp(app)

        assertNotSame(first.drawable, second.drawable)
        first.refreshDynamicIcon(1234L)
        assertEquals(1, updateCount.get())
        assertTrue(first.visibility == View.VISIBLE)
    }

    private fun epochMillis(
        zone: TimeZone,
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
    ): Long = Calendar.getInstance(zone).apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis
}
