package com.fiilda.launcher

import org.junit.Assert.*
import org.junit.Test

class DynamicHomePagesTest {
    private val layout = HomeLayout(
        order = listOf("app", "widget:photo:1", "folder:1", "widget:external:5"),
        narrowOrder = listOf("folder:1", "app", "widget:external:5", "widget:photo:1"),
        narrowPageById = mapOf("app" to 0, "widget:photo:1" to 1, "folder:1" to 2, "widget:external:5" to 3),
        pageCount = 5,
    )

    @Test fun emptyPagesSurviveStorageAndNormalization() {
        val added = addHomePage(layout)
        assertEquals(6, added.pageCount)
        assertEquals(added, parseHomeLayout(serializeHomeLayout(added)))
        val pages = added.toHomePages()
        assertEquals(pages, parseHomePages(serializeHomePages(pages)))
        assertTrue(pages[5].isEmpty())
        val normalized = normalizeHomeLayout(
            storedLayout = added, storedPages = pages, legacyOrder = pages[0],
            favoriteIds = listOf("app"), externalWidgetIds = listOf("widget:external:5"),
            homeFolders = listOf(HomeFolder(id = "folder:1", memberIds = emptyList())),
        )
        assertEquals(6, normalized.pageCount)
        assertEquals(3, normalized.pageOf("widget:external:5"))
    }

    @Test fun deletingMiddlePageRetainsEveryItemAndBothOrders() {
        val deleted = removeHomePage(layout, 2)!!
        assertEquals(4, deleted.pageCount)
        assertEquals(layout.order, deleted.order)
        assertEquals(layout.narrowOrder, deleted.narrowOrder)
        assertEquals(mapOf("app" to 0, "widget:photo:1" to 1, "folder:1" to 1, "widget:external:5" to 2), deleted.narrowPageById)
        assertEquals(deleted, parseHomeLayout(serializeHomeLayout(deleted)))
        assertEquals(1, homePageAfterRemoval(2, 2, 4))
        assertEquals(2, homePageAfterRemoval(3, 2, 4))
    }

    @Test fun deletingFirstLastAndOnlyPage() {
        val first = removeHomePage(layout, 0)!!
        assertEquals(0, first.pageOf("app"))
        assertEquals(0, first.pageOf("widget:photo:1"))
        assertEquals(2, first.pageOf("widget:external:5"))
        val last = removeHomePage(layout, 4)!!
        assertEquals(layout.narrowPageById, last.narrowPageById)
        assertNull(removeHomePage(layout.copy(pageCount = 1), 0))
        assertNull(removeHomePage(layout, -1))
        assertNull(removeHomePage(layout, 5))
        assertEquals(0, homePageAfterRemoval(0, 0, 1))
    }

    @Test fun pageOperationsRetainOwnersBeyondSecondPage() {
        val transfer = computeHomeItemPageTransfer(layout, listOf("app"), "app", 4)!!
        assertEquals(4, transfer.layout.pageOf("app"))
        assertEquals(layout.order, transfer.layout.order)
        assertEquals(layout.narrowOrder, transfer.layout.narrowOrder)
        assertEquals(5, updateNarrowHomePageOrder(transfer.layout, 4, listOf("app")).pageCount)
        assertEquals(3, removeHomeItemFromLayout(layout, "app").pageOf("widget:external:5"))
        val withWidget = addHomeItemToLayout(layout, "widget:clock", 4)
        assertEquals(4, withWidget.pageOf("widget:clock"))
        val renamed = layout.renameHomeIds(mapOf("app" to "renamed"))
        assertEquals(5, renamed.pageCount)
        assertEquals(layout.toHomePages().count, layout.toHomePages().renameHomeIds(mapOf("app" to "renamed")).count)
    }

    @Test fun twoPageRecordsMigrateAndMalformedCountIsRejected() {
        val migrated = parseHomeLayout("v4:YQ,Yg;Yg,YQ;YQ~0,Yg~1")!!
        assertEquals(2, migrated.pageCount)
        assertEquals(listOf("b", "a"), migrated.narrowOrder)
        assertEquals(migrated, parseHomeLayout(serializeHomeLayout(migrated)))
        assertNull(parseHomeLayout("v5:0;;;"))
        assertNull(parseHomeLayout("v5:2;YQ;YQ;YQ~2"))
        assertEquals(HomeLayout(pageCount = 1), parseHomeLayout("v5:1;;;"))
        assertEquals(HomePages(listOf(emptyList())), parseHomePages("v3:"))
    }

    @Test fun restoredWidgetAndPhotoTargetsRetainThirdAndLaterPages() {
        val restored = resolvePendingWidgetRestore(
            savedAppWidgetId = 7, savedResultReady = true,
            persistedAppWidgetId = 7, persistedResultReady = true,
            savedHomePage = 4, persistedHomePage = 4,
        )
        assertEquals(4, restored.targetHomePage)
        val photo = homeLayoutAfterPhotoSelection(layout, "widget:photo:2", 4, true)
        assertEquals(4, photo.layout.pageOf(photo.widgetId))
        val folded = mapLauncherPostureTransition(
            from = LauncherHomePresentation.START_CANVAS, to = LauncherHomePresentation.PAGER,
            page = LauncherPage.HOME, wideAnchor = LauncherDestination.HOME,
            selectedHomePage = 4, wideHomeOwnerPage = 3, homePageCount = 5,
        )
        assertEquals(3, folded.selectedHomePage)
    }

    @Test fun homeTabCyclesAllPagesAndPreservesPageWhenReturningFromDrawer() {
        for (selected in 0 until 5) {
            assertEquals((selected + 1) % 5, launcherHomePageAfterExplicitTap(
                selected, LauncherDestination.HOME, LauncherHomePresentation.PAGER, homePageCount = 5,
            ))
            assertEquals(selected, launcherHomePageAfterExplicitTap(
                selected, LauncherDestination.DRAWER, LauncherHomePresentation.PAGER, homePageCount = 5,
            ))
        }
        assertEquals(0, launcherHomePageAfterExplicitTap(0, LauncherDestination.HOME, LauncherHomePresentation.PAGER, homePageCount = 1))
        assertEquals("ホーム1へ切り替え", launcherHomeTabAccessibility(
            LauncherHomePresentation.PAGER, LauncherDestination.HOME, 4, homePageCount = 5,
        ).actionLabel)
    }
}
