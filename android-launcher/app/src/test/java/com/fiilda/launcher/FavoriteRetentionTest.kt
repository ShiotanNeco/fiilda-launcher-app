package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteRetentionTest {
    @Test
    fun activityRenameCannotSwitchBetweenPersonalAndWorkProfiles() {
        val result = reconcileStoredFavorites(
            storedFavorites = listOf("mail/Old", "mail@42/Old"),
            installedAppIds = listOf("mail/New", "mail@42/New"),
            isPackageInstalled = { error("not needed for a resolvable rename") },
        )
        assertEquals(listOf("mail/New", "mail@42/New"), result.favoriteIds)
        assertEquals(mapOf("mail/Old" to "mail/New", "mail@42/Old" to "mail@42/New"), result.renamedIds)

        val paused = reconcileStoredFavorites(
            storedFavorites = listOf("mail@42/Old"),
            installedAppIds = listOf("mail/New"),
            isPackageInstalled = { it == "mail@42" },
        )
        assertEquals(listOf("mail@42/Old"), paused.favoriteIds)
        assertTrue(paused.renamedIds.isEmpty())
    }

    @Test
    fun unresolvedFavoriteIsRetainedWhilePackageIsStillInstalled() {
        // An archived or disabled app disappears from the launcher query but keeps its package.
        val result = reconcileStoredFavorites(
            storedFavorites = listOf("a/A", "archived/Main", "b/B"),
            installedAppIds = listOf("a/A", "b/B"),
            isPackageInstalled = { it == "archived" },
        )

        assertEquals(listOf("a/A", "archived/Main", "b/B"), result.favoriteIds)
        assertTrue(result.removedIds.isEmpty())
        assertTrue(result.renamedIds.isEmpty())
    }

    @Test
    fun onlyConfirmedUninstalledPackagesAreRemoved() {
        val result = reconcileStoredFavorites(
            storedFavorites = listOf("a/A", "gone/Main"),
            installedAppIds = listOf("a/A"),
            isPackageInstalled = { false },
        )

        assertEquals(listOf("a/A"), result.favoriteIds)
        assertEquals(setOf("gone/Main"), result.removedIds)
    }

    @Test
    fun emptyCatalogDoesNotWipeFavoritesOfInstalledPackages() {
        val result = reconcileStoredFavorites(
            storedFavorites = listOf("a/A", "b/B"),
            installedAppIds = emptyList(),
            isPackageInstalled = { true },
        )

        assertEquals(listOf("a/A", "b/B"), result.favoriteIds)
    }

    @Test
    fun renamedLauncherActivityIsFollowedWhenUnambiguous() {
        val result = reconcileStoredFavorites(
            storedFavorites = listOf("pkg/OldMain", "a/A"),
            installedAppIds = listOf("a/A", "pkg/NewMain"),
            isPackageInstalled = { error("not needed for a resolvable rename") },
        )

        assertEquals(listOf("pkg/NewMain", "a/A"), result.favoriteIds)
        assertEquals(mapOf("pkg/OldMain" to "pkg/NewMain"), result.renamedIds)
    }

    @Test
    fun ambiguousOrAlreadyFavoriteReplacementIsNotRenamed() {
        val ambiguous = reconcileStoredFavorites(
            storedFavorites = listOf("pkg/Old"),
            installedAppIds = listOf("pkg/One", "pkg/Two"),
            isPackageInstalled = { true },
        )
        assertEquals(listOf("pkg/Old"), ambiguous.favoriteIds)
        assertTrue(ambiguous.renamedIds.isEmpty())

        val alreadyFavorite = reconcileStoredFavorites(
            storedFavorites = listOf("pkg/Old", "pkg/Other"),
            installedAppIds = listOf("pkg/Other"),
            isPackageInstalled = { true },
        )
        assertEquals(listOf("pkg/Old", "pkg/Other"), alreadyFavorite.favoriteIds)
        assertTrue(alreadyFavorite.renamedIds.isEmpty())
    }

    @Test
    fun retainedFavoriteKeepsPageFolderAndSizeThroughStartupNormalization() {
        val stored = listOf("a/A", "archived/Main", "folded/Main", "b/B")
        val favorites = reconcileStoredFavorites(
            storedFavorites = stored,
            installedAppIds = listOf("a/A", "b/B"),
            isPackageInstalled = { true },
        ).favoriteIds
        val folder = HomeFolder(id = "folder:1", memberIds = listOf("folded/Main"))
        val layout = HomeLayout(
            order = listOf("a/A", "archived/Main", "folder:1", "b/B"),
            narrowPageById = mapOf("a/A" to 0, "archived/Main" to 1, "folder:1" to 0, "b/B" to 1),
        )

        val folders = normalizeHomeFolders(listOf(folder), favorites.toSet())
        val normalized = normalizeHomeLayout(
            storedLayout = layout,
            storedPages = null,
            legacyOrder = null,
            favoriteIds = favorites,
            homeFolders = folders,
        )
        val sizes = pruneAppTileSizes(
            sizes = mapOf("archived/Main" to AppTileSize.LARGE),
            favoriteIds = favorites.toSet(),
        )

        assertEquals(listOf(folder), folders)
        assertEquals(layout.order, normalized.order)
        assertEquals(1, normalized.pageOf("archived/Main"))
        assertEquals(mapOf("archived/Main" to AppTileSize.LARGE), sizes)
    }

    @Test
    fun renameRewritesLayoutFoldersAndSizeKeys() {
        val renames = mapOf("pkg/Old" to "pkg/New")
        val layout = HomeLayout(
            order = listOf("a/A", "pkg/Old"),
            narrowPageById = mapOf("a/A" to 0, "pkg/Old" to 1),
            narrowOrder = listOf("pkg/Old", "a/A"),
        )

        val renamed = layout.renameHomeIds(renames)

        assertEquals(listOf("a/A", "pkg/New"), renamed.order)
        assertEquals(listOf("pkg/New", "a/A"), renamed.narrowOrder)
        assertEquals(1, renamed.pageOf("pkg/New"))
        assertEquals(
            listOf(HomeFolder(id = "folder:1", memberIds = listOf("pkg/New"))),
            listOf(HomeFolder(id = "folder:1", memberIds = listOf("pkg/Old")))
                .renameHomeFolderMembers(renames),
        )
        assertEquals(
            mapOf("pkg/New" to AppTileSize.WIDE),
            mapOf("pkg/Old" to AppTileSize.WIDE).renameHomeIdKeys(renames),
        )
        assertEquals(
            HomePages(listOf(listOf("pkg/New"), emptyList())),
            HomePages(listOf(listOf("pkg/Old"), emptyList())).renameHomeIds(renames),
        )
    }

    @Test
    fun reorderOfVisibleItemsKeepsHiddenItemInItsSlot() {
        val layout = HomeLayout(
            order = listOf("a", "hidden", "b", "c"),
            narrowPageById = mapOf("a" to 0, "hidden" to 0, "b" to 0, "c" to 0),
        )

        val narrow = updateNarrowHomePageOrder(layout, page = 0, order = listOf("c", "b", "a"))
        val wide = updateWideHomeOrder(layout, listOf("b", "c", "a"))

        assertEquals(listOf("c", "hidden", "b", "a"), narrow.narrowOrder)
        assertEquals(listOf("b", "hidden", "c", "a"), wide.order)
    }

    @Test
    fun missingWidgetProviderIsKeptUnlessTheFrameworkReleasedTheId() {
        assertEquals(
            StoredWidgetDisposition.REFRESH,
            storedWidgetDescriptorDisposition("p/W", "p/W", hostStillOwnsId = true),
        )
        // Locked work profile or private space: info is unavailable but the host still owns the ID.
        assertEquals(
            StoredWidgetDisposition.KEEP_STORED,
            storedWidgetDescriptorDisposition("p/W", null, hostStillOwnsId = true),
        )
        assertEquals(
            StoredWidgetDisposition.KEEP_STORED,
            storedWidgetDescriptorDisposition("p/W", null, hostStillOwnsId = null),
        )
        assertEquals(
            StoredWidgetDisposition.DROP,
            storedWidgetDescriptorDisposition("p/W", null, hostStillOwnsId = false),
        )
        assertEquals(
            StoredWidgetDisposition.DELETE,
            storedWidgetDescriptorDisposition("p/W", "other/W", hostStillOwnsId = true),
        )
    }
}
