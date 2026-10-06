package com.fiilda.launcher

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies that a page transfer commits the v4 record and both compatibility projections. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeLayoutPersistenceIntegrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearPreferences()
    }

    @After
    fun tearDown() {
        clearPreferences()
    }

    @Test
    fun favoritePageTransferPersistsAllMirrorsWithoutChangingWideOrder() {
        val original = HomeLayout(
            order = listOf("direct", "widget:clock", "sibling", "other"),
            narrowPageById = mapOf(
                "direct" to 0,
                "widget:clock" to 1,
                "sibling" to 0,
                "other" to 1,
            ),
            narrowOrder = listOf("sibling", "direct", "other", "widget:clock"),
        )
        val preferences = context.getSharedPreferences(
            FavoritePreferencesName,
            Context.MODE_PRIVATE,
        )
        val existingAppSizes = mapOf("direct" to AppTileSize.LARGE)
        val existingWideAppSizes = mapOf("other" to AppTileSize.WIDE)
        val existingWidgetSizes = mapOf(
            "widget:clock" to WidgetSizeChoice.ROW_1_COLUMN_2,
        )
        val existingWideWidgetSizes = mapOf(
            "widget:weather" to WidgetSizeChoice.ROW_2_COLUMN_4,
        )
        val existingFolders = listOf(
            HomeFolder(
                id = "folder:existing",
                name = "既存フォルダ",
                memberIds = listOf("folder-member"),
                size = HomeFolderSize.LARGE,
            ),
        )
        assertTrue(
            preferences.edit()
                .putString(HomeLayoutKey, serializeHomeLayout(original))
                .putString(HomePagesKey, serializeHomePages(original.toHomePages()))
                .putString(HomeOrderKey, original.toHomePages()[0].joinToString("\n"))
                .putString("app_tile_sizes", serializeAppTileSizes(existingAppSizes))
                .putString("wide_app_tile_sizes", serializeAppTileSizes(existingWideAppSizes))
                .putString("widget_size_overrides", serializeWidgetSizeOverrides(existingWidgetSizes))
                .putString(
                    "wide_widget_size_overrides",
                    serializeWidgetSizeOverrides(existingWideWidgetSizes),
                )
                .putString(HomeFoldersKey, serializeHomeFolders(existingFolders))
                .putString("unrelated_layout_setting", "keep")
                .commit(),
        )

        val transfer = requireNotNull(
            computeHomeItemPageTransfer(
                layout = original,
                favoriteIds = listOf("direct", "other"),
                id = "direct",
                targetPage = 1,
            ),
        )
        assertTrue(persistHomeLayoutTransaction(context, transfer.layout))

        val persistedLayout = requireNotNull(parseHomeLayout(readHomeLayoutRaw(context)))
        assertEquals(transfer.layout, persistedLayout)
        assertEquals(transfer.layout.order, persistedLayout.order)
        assertEquals(transfer.layout.narrowOrder, persistedLayout.narrowOrder)
        assertEquals(
            transfer.layout.toHomePages(),
            parseHomePages(readHomePagesRaw(context)),
        )
        assertEquals(transfer.layout.toHomePages()[0], readHomeOrder(context))
        assertEquals(existingAppSizes, readAppTileSizes(context))
        assertEquals(existingWideAppSizes, readWideAppTileSizes(context))
        assertEquals(existingWidgetSizes, readWidgetSizeOverrides(context))
        assertEquals(existingWideWidgetSizes, readWideWidgetSizeOverrides(context))
        assertEquals(existingFolders, readHomeFolders(context))
        assertEquals("keep", preferences.getString("unrelated_layout_setting", null))
    }

    @Test
    fun addingAndDeletingPagesPreservesItemsAndUnrelatedRecords() {
        val preferences = context.getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
        val original = HomeLayout(
            order = listOf("app", "widget:photo:1", "folder:1", "widget:external:5"),
            narrowOrder = listOf("folder:1", "app", "widget:external:5", "widget:photo:1"),
            narrowPageById = mapOf("app" to 0, "widget:photo:1" to 1, "folder:1" to 2, "widget:external:5" to 3),
            pageCount = 4,
        )
        val records = mapOf(
            "favorite_ids" to "app",
            HomeFoldersKey to "saved-folder-members",
            "external_widget_descriptors" to "saved-widget-provider",
            "photo_widget_uris" to "saved-photo-uri",
            "photo_widget_video_mutes" to "saved-video-mute",
            "app_tile_sizes" to "saved-size",
            "wide_app_tile_sizes" to "saved-wide-size",
            "widget_size_overrides" to "saved-widget-size",
            "wide_widget_size_overrides" to "saved-wide-widget-size",
            "unrelated_setting" to "keep",
        )
        preferences.edit().apply { records.forEach { (key, value) -> putString(key, value) } }.commit()
        assertTrue(persistHomeLayoutTransaction(context, original))
        val added = addHomePage(original)
        assertTrue(persistHomeLayoutTransaction(context, added, expectedLayout = original))
        assertEquals(added, parseHomeLayout(readHomeLayoutRaw(context)))
        assertEquals(5, parseHomePages(readHomePagesRaw(context))!!.count)
        val deleted = removeHomePage(added, 1)!!
        assertTrue(persistHomeLayoutTransaction(context, deleted, expectedLayout = added))
        assertEquals(deleted, parseHomeLayout(readHomeLayoutRaw(context)))
        assertEquals(deleted.toHomePages(), parseHomePages(readHomePagesRaw(context)))
        assertEquals(deleted.order, original.order)
        assertEquals(deleted.narrowOrder, original.narrowOrder)
        records.forEach { (key, value) -> assertEquals(key, value, preferences.getString(key, null)) }
    }

    @Test
    fun stalePageCountSaveDoesNotOverwriteAnotherLayoutChange() {
        val original = HomeLayout(order = listOf("app"), narrowPageById = mapOf("app" to 0))
        assertTrue(persistHomeLayoutTransaction(context, original))
        val changed = addHomeItemToLayout(original, "widget:clock", 1)
        assertTrue(persistHomeLayoutTransaction(context, changed))
        assertFalse(persistHomeLayoutTransaction(context, addHomePage(original), expectedLayout = original))
        assertEquals(changed, parseHomeLayout(readHomeLayoutRaw(context)))
        assertEquals(changed.toHomePages(), parseHomePages(readHomePagesRaw(context)))
    }

    private fun clearPreferences() {
        context.getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
