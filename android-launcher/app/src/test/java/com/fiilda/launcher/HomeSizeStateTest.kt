package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSizeStateTest {
    @Test
    fun missingWideMapsMigrateTheSharedMapsWithoutChangingAppearance() {
        val narrow = HomeSizeMaps(
            appTileSizes = mapOf("app/a" to AppTileSize.LARGE),
            widgetSizeOverrides = mapOf(HomeWidget.CLOCK.id to WidgetSizeChoice.ROW_1_COLUMN_2),
        )

        assertEquals(narrow, migrateWideHomeSizeMaps(narrow, null))
        assertEquals(
            HomeSizeMaps(appTileSizes = mapOf("app/b" to AppTileSize.WIDE)),
            migrateWideHomeSizeMaps(
                narrow = narrow,
                storedWide = HomeSizeMaps(appTileSizes = mapOf("app/b" to AppTileSize.WIDE)),
            ),
        )
    }

    @Test
    fun presentationSelectionUsesWideOnlyWhenSeparationIsEnabled() {
        val narrow = HomeSizeMaps(appTileSizes = mapOf("app/a" to AppTileSize.TALL))
        val wide = HomeSizeMaps(appTileSizes = mapOf("app/a" to AppTileSize.LARGE))

        assertEquals(
            narrow,
            homeSizeMapsForPresentation(
                narrow,
                wide,
                separateWideOrder = false,
                presentation = HomeSizePresentation.WIDE,
            ),
        )
        assertEquals(
            wide,
            homeSizeMapsForPresentation(
                narrow,
                wide,
                separateWideOrder = true,
                presentation = HomeSizePresentation.WIDE,
            ),
        )
        assertEquals(
            narrow,
            homeSizeMapsForPresentation(
                narrow,
                wide,
                separateWideOrder = true,
                presentation = HomeSizePresentation.NARROW,
            ),
        )
    }

    @Test
    fun appSizeUpdatesAreIndependentWhenEnabledAndSynchronizedWhenDisabled() {
        val narrow = HomeSizeMaps(appTileSizes = mapOf("app/a" to AppTileSize.TALL))
        val wide = HomeSizeMaps(
            appTileSizes = mapOf("app/a" to AppTileSize.WIDE),
            widgetSizeOverrides = mapOf(HomeWidget.CLOCK.id to WidgetSizeChoice.ROW_1_COLUMN_2),
        )

        val wideOnly = updateHomeAppTileSize(
            narrow = narrow,
            wide = wide,
            id = "app/a",
            size = AppTileSize.LARGE,
            presentation = HomeSizePresentation.WIDE,
            separateWideOrder = true,
        )
        assertEquals(narrow, wideOnly.first)
        assertEquals(AppTileSize.LARGE, wideOnly.second.appTileSizes["app/a"])

        val narrowOnly = updateHomeAppTileSize(
            narrow = narrow,
            wide = wide,
            id = "app/a",
            size = AppTileSize.SMALL,
            presentation = HomeSizePresentation.NARROW,
            separateWideOrder = true,
        )
        assertTrue("app/a" !in narrowOnly.first.appTileSizes)
        assertEquals(wide, narrowOnly.second)

        val shared = updateHomeAppTileSize(
            narrow = narrow,
            wide = wide,
            id = "app/a",
            size = AppTileSize.LARGE,
            presentation = HomeSizePresentation.WIDE,
            separateWideOrder = false,
        )
        assertEquals(shared.first.appTileSizes, shared.second.appTileSizes)
        assertEquals(wide.widgetSizeOverrides, shared.second.widgetSizeOverrides)
        assertEquals(AppTileSize.LARGE, shared.first.appTileSizes["app/a"])
    }

    @Test
    fun widgetSizeUpdatesUseTheSameIsolationAndSynchronizationRules() {
        val narrow = HomeSizeMaps(
            widgetSizeOverrides = mapOf(HomeWidget.CLOCK.id to WidgetSizeChoice.ROW_1_COLUMN_2),
        )
        val wide = HomeSizeMaps(
            widgetSizeOverrides = mapOf(HomeWidget.CLOCK.id to WidgetSizeChoice.ROW_2_COLUMN_4),
        )

        val wideOnly = updateHomeWidgetSize(
            narrow = narrow,
            wide = wide,
            id = HomeWidget.CLOCK.id,
            choice = WidgetSizeChoice.ROW_1_COLUMN_4,
            builtIn = true,
            presentation = HomeSizePresentation.WIDE,
            separateWideOrder = true,
        )
        assertEquals(narrow, wideOnly.first)
        assertEquals(
            WidgetSizeChoice.ROW_1_COLUMN_4,
            wideOnly.second.widgetSizeOverrides[HomeWidget.CLOCK.id],
        )

        val shared = updateHomeWidgetSize(
            narrow = narrow,
            wide = wide,
            id = HomeWidget.CLOCK.id,
            choice = WidgetSizeChoice.ROW_2_COLUMN_2,
            builtIn = true,
            presentation = HomeSizePresentation.WIDE,
            separateWideOrder = false,
        )
        assertEquals(shared.first, shared.second)
        assertTrue(HomeWidget.CLOCK.id !in shared.first.widgetSizeOverrides)
    }

    @Test
    fun removePrunesBothMapsAndSerializersRoundTrip() {
        val narrow = HomeSizeMaps(
            appTileSizes = mapOf("app/a" to AppTileSize.WIDE),
            widgetSizeOverrides = mapOf(HomeWidget.CLOCK.id to WidgetSizeChoice.ROW_1_COLUMN_2),
        )
        val wide = HomeSizeMaps(
            appTileSizes = mapOf("app/a" to AppTileSize.LARGE),
            widgetSizeOverrides = mapOf(HomeWidget.CLOCK.id to WidgetSizeChoice.ROW_2_COLUMN_4),
        )
        val removed = removeHomeSizeIdFromMaps(narrow, wide, "app/a")
        assertTrue("app/a" !in removed.first.appTileSizes)
        assertTrue("app/a" !in removed.second.appTileSizes)
        assertEquals(narrow.widgetSizeOverrides, removed.first.widgetSizeOverrides)

        val appSizes = mapOf("app/b" to AppTileSize.LARGE, "app/a" to AppTileSize.WIDE)
        assertEquals(appSizes, parseAppTileSizes(serializeAppTileSizes(appSizes)))
        val widgetSizes = mapOf(
            HomeWidget.CLOCK.id to WidgetSizeChoice.ROW_1_COLUMN_2,
            "${ExternalWidgetIdPrefix}42" to WidgetSizeChoice.AUTO,
        )
        assertEquals(widgetSizes, parseWidgetSizeOverrides(serializeWidgetSizeOverrides(widgetSizes)))

        val pruned = pruneAppTileSizes(
            sizes = appSizes + ("app/removed" to AppTileSize.LARGE),
            favoriteIds = setOf("app/a"),
        )
        assertEquals(mapOf("app/a" to AppTileSize.WIDE), pruned)
        assertNotEquals(appSizes, pruned)
    }

    @Test
    fun invalidSizeRowDoesNotBlockAValidLaterRowForTheSameId() {
        assertEquals(
            mapOf("app/x" to AppTileSize.LARGE),
            parseAppTileSizes("app/x\tUNKNOWN\napp/x\tLARGE"),
        )
    }

    @Test
    fun verticalThreeCellSizesRoundTripAndPruneLikeExistingOverrides() {
        val sizes = mapOf(
            "app/vertical-one" to AppTileSize.TALL_3X1,
            "app/vertical-two" to AppTileSize.TALL_3X2,
        )

        assertEquals(sizes, parseAppTileSizes(serializeAppTileSizes(sizes)))
        assertEquals(
            mapOf("app/vertical-two" to AppTileSize.TALL_3X2),
            pruneAppTileSizes(sizes, setOf("app/vertical-two")),
        )
    }
}
