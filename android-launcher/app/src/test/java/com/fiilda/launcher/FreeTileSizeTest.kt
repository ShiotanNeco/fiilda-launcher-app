package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeTileSizeTest {
    @Test
    fun presetsKeepTheirHistoricalTokens() {
        AppTileSize.values().forEach { preset ->
            assertSame(preset, AppTileSize.valueOf(preset.name))
        }
        assertEquals("TALL_3X2", AppTileSize.of(3, 2).name)
        assertSame(WidgetSizeChoice.ROW_2_COLUMN_4, WidgetSizeChoice.of(2, 4))
        assertEquals(WidgetSizeChoice.AUTO, widgetSizeChoiceForToken("AUTO"))
    }

    @Test
    fun freeSizesRoundTripThroughPersistence() {
        val sizes = mapOf("a/A" to AppTileSize.of(6, 4), "b/B" to AppTileSize.of(4, 3))
        assertEquals(sizes, parseAppTileSizes(serializeAppTileSizes(sizes)))
        assertEquals("R6C4", AppTileSize.of(6, 4).name)
        assertEquals("6×4", AppTileSize.of(6, 4).label)

        val widgetId = "${ExternalWidgetIdPrefix}7"
        val overrides = mapOf(widgetId to WidgetSizeChoice.of(5, 3))
        assertEquals(overrides, parseWidgetSizeOverrides(serializeWidgetSizeOverrides(overrides)))
    }

    @Test
    fun sizesAreClampedToSixRowsByFourColumns() {
        assertEquals(AppTileSize.of(6, 4), AppTileSize.of(9, 7))
        assertEquals(AppTileSize.SMALL, AppTileSize.of(0, -1))
        assertEquals(WidgetSizeChoice.of(6, 4), WidgetSizeChoice.of(12, 12))
        // Out-of-range or malformed stored rows are dropped instead of being reinterpreted.
        assertTrue(parseAppTileSizes("a/A\tR7C4\nb/B\tR1C5\nc/C\tR2").isEmpty())
        assertNull(widgetSizeChoiceForToken("R0C2"))
    }

    @Test
    fun notificationCapacityGrowsWithFreeSizes() {
        assertEquals(0, notificationTileCapacity(AppTileSize.SMALL))
        assertEquals(1, notificationTileCapacity(AppTileSize.WIDE))
        assertEquals(1, notificationTileCapacity(AppTileSize.TALL))
        assertEquals(2, notificationTileCapacity(AppTileSize.LARGE))
        assertEquals(2, notificationTileCapacity(AppTileSize.TALL_3X1))
        assertEquals(3, notificationTileCapacity(AppTileSize.TALL_3X2))
        assertEquals(1, notificationTileCapacity(AppTileSize.of(1, 4)))
        assertEquals(6, notificationTileCapacity(AppTileSize.of(6, 4)))
    }
}
