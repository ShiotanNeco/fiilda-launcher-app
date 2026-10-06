package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePagesTest {
    @Test
    fun v4LayoutRoundTripsIndependentOrdersAndNarrowOwnership() {
        val original = HomeLayout(
            order = listOf("a", "widget:clock", "b", "c"),
            narrowPageById = mapOf("a" to 0, "widget:clock" to 1, "b" to 0, "c" to 1),
            narrowOrder = listOf("c", "a", "widget:clock", "b"),
        )

        assertEquals(original, parseHomeLayout(serializeHomeLayout(original)))
        assertTrue(serializeHomeLayout(original).startsWith("v5:"))
        assertEquals(HomeLayout.empty(), parseHomeLayout("v3:;"))
        assertNull(parseHomeLayout("v2:YWJj;ZGVm"))
        assertNull(parseHomeLayout("v3:YWJj;YWJj-0"))
        assertNull(parseHomeLayout("v3:YWJj;YWJj~2"))
    }

    @Test
    fun v3LayoutMigratesItsOrderToBothV4Orders() {
        val migrated = parseHomeLayout("v3:YQ,Yg;YQ~0,Yg~1")

        assertEquals(
            HomeLayout(
                order = listOf("a", "b"),
                narrowPageById = mapOf("a" to 0, "b" to 1),
                narrowOrder = listOf("a", "b"),
            ),
            migrated,
        )
    }

    @Test
    fun wideReorderKeepsNarrowOwnershipAndNarrowReorderOnlyReplacesThatSubsequence() {
        val initial = HomeLayout(
            order = listOf("a", "b", "c", "d", "e"),
            narrowPageById = mapOf("a" to 0, "b" to 1, "c" to 0, "d" to 1, "e" to 0),
        )

        val wide = updateWideHomeOrder(initial, listOf("e", "d", "c", "b", "a"))
        assertEquals(listOf("e", "d", "c", "b", "a"), wide.order)
        assertEquals(initial.narrowPageById, wide.narrowPageById)

        val narrow = updateNarrowHomePageOrder(initial, page = 0, order = listOf("e", "a", "c"))
        assertEquals(initial.order, narrow.order)
        assertEquals(listOf("e", "b", "a", "d", "c"), narrow.narrowOrder)
        assertEquals(listOf("b", "d"), narrow.orderForPage(1))
        assertEquals(initial.narrowPageById, narrow.narrowPageById)
    }

    @Test
    fun independentReordersChangeOnlyTheirOwnOrder() {
        val initial = HomeLayout(
            order = listOf("a", "b", "c"),
            narrowPageById = mapOf("a" to 0, "b" to 1, "c" to 0),
            narrowOrder = listOf("c", "a", "b"),
        )

        val wide = updateWideHomeOrder(initial, listOf("b", "a", "c"))
        assertEquals(listOf("b", "a", "c"), wide.order)
        assertEquals(initial.narrowOrder, wide.narrowOrder)

        val narrow = updateNarrowHomePageOrder(initial, page = 0, order = listOf("a", "c"))
        assertEquals(initial.order, narrow.order)
        assertEquals(listOf("a", "c", "b"), narrow.narrowOrder)
    }

    @Test
    fun sharedOrderSynchronizationCopiesOneCompleteOrderToBothFields() {
        val initial = HomeLayout(
            order = listOf("a", "b", "c"),
            narrowPageById = mapOf("a" to 0, "b" to 1, "c" to 0),
            narrowOrder = listOf("c", "a", "b"),
        )

        val synchronized = synchronizeHomeLayoutOrders(initial, listOf("b", "a", "c"))

        assertEquals(listOf("b", "a", "c"), synchronized.order)
        assertEquals(synchronized.order, synchronized.narrowOrder)
        assertEquals(synchronized.order, wideOrderForHomePresentation(synchronized, true))
        assertEquals(synchronized.order, wideOrderForHomePresentation(synchronized, false))
    }

    @Test
    fun modeAwareWideCommitKeepsIndependentNarrowOrderWhenEnabled() {
        val initial = HomeLayout(
            order = listOf("a", "b", "c"),
            narrowPageById = mapOf("a" to 0, "b" to 1, "c" to 0),
            narrowOrder = listOf("c", "a", "b"),
        )

        val updated = homeLayoutAfterWideReorder(
            layout = initial,
            order = listOf("b", "c", "a"),
            separateWideOrder = true,
        )

        assertEquals(listOf("b", "c", "a"), updated.order)
        assertEquals(initial.narrowOrder, updated.narrowOrder)
    }

    @Test
    fun modeAwareNarrowCommitSynchronizesBothOrdersWhenDisabled() {
        val initial = HomeLayout(
            order = listOf("a", "b", "c"),
            narrowPageById = mapOf("a" to 0, "b" to 1, "c" to 0),
            narrowOrder = listOf("c", "a", "b"),
        )

        val updated = homeLayoutAfterNarrowReorder(
            layout = initial,
            page = 0,
            order = listOf("a", "c"),
            separateWideOrder = false,
        )

        assertEquals(updated.order, updated.narrowOrder)
        assertEquals(listOf("a", "c", "b"), updated.order)
    }

    @Test
    fun removingFromLayoutDropsWideOrderAndNarrowOwnership() {
        val initial = HomeLayout(
            order = listOf("a", "photo:1", "b"),
            narrowPageById = mapOf("a" to 0, "photo:1" to 1, "b" to 0),
        )

        val updated = removeHomeItemFromLayout(initial, "photo:1")

        assertEquals(listOf("a", "b"), updated.order)
        assertEquals(mapOf("a" to 0, "b" to 0), updated.narrowPageById)
        assertEquals(listOf("a", "b"), updated.toHomePages()[0])
        assertTrue(updated.toHomePages()[1].isEmpty())
    }

    @Test
    fun removingUnknownOrBlankLayoutIdLeavesCanonicalLayoutUnchanged() {
        val initial = HomeLayout(
            order = listOf("a"),
            narrowPageById = mapOf("a" to 0),
        )

        assertEquals(initial, removeHomeItemFromLayout(initial, "missing"))
        assertEquals(initial, removeHomeItemFromLayout(initial, ""))
    }

    @Test
    fun removedPhotoIsNotRecoveredFromAStaleCompatibilityMirror() {
        val removed = removeHomeItemFromLayout(
            HomeLayout(
                order = listOf("widget:photo:1", "a"),
                narrowPageById = mapOf("widget:photo:1" to 0, "a" to 0),
            ),
            "widget:photo:1",
        )

        val reopened = normalizeHomeLayout(
            storedLayout = removed,
            storedPages = HomePages(listOf(listOf("widget:photo:1", "a"), emptyList())),
            legacyOrder = listOf("widget:photo:1", "a"),
            favoriteIds = listOf("a"),
        )

        assertEquals(listOf("a"), reopened.order)
        assertFalse("widget:photo:1" in reopened.allIds)
    }

    @Test
    fun v3MigrationPrefersLayoutThenFallsBackThroughV2AndLegacy() {
        val layout = HomeLayout(
            order = listOf(HomeWidget.WEATHER.id, HomeWidget.CLOCK.id),
            narrowPageById = mapOf(HomeWidget.WEATHER.id to 1, HomeWidget.CLOCK.id to 0),
        )
        val migrated = normalizeHomeLayout(
            storedLayout = layout,
            storedPages = HomePages(listOf(listOf(HomeWidget.CLOCK.id), emptyList())),
            legacyOrder = listOf(HomeWidget.AGENDA.id),
            favoriteIds = emptyList(),
        )
        assertEquals(layout, migrated)

        val fromV2 = normalizeHomeLayout(
            storedLayout = null,
            storedPages = HomePages(listOf(listOf("a"), listOf("b"))),
            legacyOrder = listOf("legacy"),
            favoriteIds = listOf("a", "b"),
        )
        assertEquals(listOf("a", "b"), fromV2.order)
        assertEquals(0, fromV2.pageOf("a"))
        assertEquals(1, fromV2.pageOf("b"))

        val fromLegacy = normalizeHomeLayout(
            storedLayout = null,
            storedPages = null,
            legacyOrder = listOf("legacy"),
            favoriteIds = listOf("legacy"),
        )
        assertEquals(listOf("legacy"), fromLegacy.order)
        assertEquals(0, fromLegacy.pageOf("legacy"))
    }

    @Test
    fun v3OwnershipWinsWhenTheCompatibilityPagesDisagree() {
        val normalized = normalizeHomeLayout(
            storedLayout = HomeLayout(
                order = listOf(HomeWidget.CLOCK.id),
                narrowPageById = mapOf(HomeWidget.CLOCK.id to 1),
            ),
            storedPages = HomePages(listOf(listOf(HomeWidget.CLOCK.id), emptyList())),
            legacyOrder = null,
            favoriteIds = emptyList(),
        )
        assertEquals(1, normalized.pageOf(HomeWidget.CLOCK.id))
        assertEquals(listOf(HomeWidget.CLOCK.id), normalized.toHomePages()[1])
    }

    @Test
    fun validV3DoesNotResurrectBuiltInRemovedFromTheWideSnapshot() {
        val normalized = normalizeHomeLayout(
            storedLayout = HomeLayout(
                order = listOf(HomeWidget.CLOCK.id),
                narrowPageById = mapOf(HomeWidget.CLOCK.id to 0),
            ),
            storedPages = HomePages(listOf(listOf(HomeWidget.CLOCK.id, HomeWidget.WEATHER.id), emptyList())),
            legacyOrder = listOf(HomeWidget.CLOCK.id, HomeWidget.WEATHER.id),
            favoriteIds = emptyList(),
        )

        assertEquals(listOf(HomeWidget.CLOCK.id), normalized.order)
        assertEquals(0, normalized.pageOf(HomeWidget.CLOCK.id))
    }

    @Test
    fun mirrorComparisonRequiresOneMigrationWhenAnyCompatibilityRecordIsStale() {
        val layout = HomeLayout(
            order = listOf("b", "a"),
            narrowPageById = mapOf("a" to 0, "b" to 1),
        )
        val pages = layout.toHomePages()
        assertFalse(
            homeLayoutMirrorsNeedMigration(
                storedLayoutRaw = serializeHomeLayout(layout),
                storedPagesRaw = serializeHomePages(pages),
                storedLegacyOrder = pages[0],
                canonicalLayout = layout,
            ),
        )
        assertTrue(
            homeLayoutMirrorsNeedMigration(
                storedLayoutRaw = serializeHomeLayout(layout),
                storedPagesRaw = serializeHomePages(pages),
                storedLegacyOrder = listOf("b"),
                canonicalLayout = layout,
            ),
        )
        assertTrue(
            homeLayoutMirrorsNeedMigration(
                storedLayoutRaw = "v3:broken",
                storedPagesRaw = serializeHomePages(pages),
                storedLegacyOrder = pages[0],
                canonicalLayout = layout,
            ),
        )
    }

    @Test
    fun addingToLayoutAppendsWideOrderWithoutMovingExistingItems() {
        val initial = HomeLayout(
            order = listOf("a", "b"),
            narrowPageById = mapOf("a" to 0, "b" to 1),
        )
        val updated = addHomeItemToLayout(initial, "c", page = 1)
        assertEquals(listOf("a", "b", "c"), updated.order)
        assertEquals(listOf("b", "c"), updated.orderForPage(1))
        assertEquals(initial.narrowPageById + ("c" to 1), updated.narrowPageById)
        assertEquals(listOf("a", "b", "c"), updated.narrowOrder)
    }

    @Test
    fun photoResultBeforeHomeLoadAddsToDurableLayoutAndPreservesBothOrdersAndPages() {
        val stored = HomeLayout(
            order = listOf("widget:clock", "app.example/.Main", "widget:photo:1", "widget:weather"),
            narrowPageById = mapOf(
                "widget:clock" to 0,
                "app.example/.Main" to 1,
                "widget:photo:1" to 1,
                "widget:weather" to 0,
            ),
            narrowOrder = listOf(
                "widget:clock",
                "widget:photo:1",
                "app.example/.Main",
                "widget:weather",
            ),
        )

        // The empty layout models the pre-load Compose snapshot; the callback uses [stored] as
        // its durable base instead of serializing that empty snapshot.
        val committed = homeLayoutAfterPhotoSelection(
            layout = stored,
            widgetId = "widget:photo:2",
            targetHomePage = 0,
            addToHome = true,
            existingIds = emptySet(),
        ).layout

        assertEquals(
            listOf("widget:clock", "app.example/.Main", "widget:photo:1", "widget:weather", "widget:photo:2"),
            committed.order,
        )
        assertEquals(
            listOf("widget:clock", "widget:photo:1", "app.example/.Main", "widget:weather", "widget:photo:2"),
            committed.narrowOrder,
        )
        assertEquals(0, committed.pageOf("widget:photo:2"))
        assertEquals(
            HomePages(listOf(listOf("widget:clock", "widget:weather", "widget:photo:2"), listOf("widget:photo:1", "app.example/.Main"))),
            committed.toHomePages(),
        )
    }

    @Test
    fun photoAddCollisionGetsFreshIdAndReplacementLeavesLayoutUntouched() {
        val stored = HomeLayout(
            order = listOf("widget:photo:1", "app.example/.Main"),
            narrowPageById = mapOf("widget:photo:1" to 1, "app.example/.Main" to 0),
            narrowOrder = listOf("app.example/.Main", "widget:photo:1"),
        )

        val added = homeLayoutAfterPhotoSelection(
            layout = stored,
            widgetId = "widget:photo:1",
            targetHomePage = 0,
            addToHome = true,
            existingIds = setOf("widget:photo:1"),
        )
        assertEquals("widget:photo:2", added.widgetId)
        assertEquals(
            listOf("widget:photo:1", "app.example/.Main", "widget:photo:2"),
            added.layout.order,
        )
        assertEquals(1, added.layout.pageOf("widget:photo:1"))
        assertEquals(0, added.layout.pageOf("widget:photo:2"))

        val replaced = homeLayoutAfterPhotoSelection(
            layout = stored,
            widgetId = "widget:photo:1",
            targetHomePage = 0,
            addToHome = false,
            existingIds = setOf("widget:photo:1"),
        )
        assertEquals(stored, replaced.layout)
        assertEquals("widget:photo:1", replaced.widgetId)
    }

    @Test
    fun addAndRemoveKeepWideAndNarrowIdsCompleteAndUnique() {
        val initial = HomeLayout(
            order = listOf("a", "b"),
            narrowPageById = mapOf("a" to 0, "b" to 1),
            narrowOrder = listOf("b", "a"),
        )

        val added = addHomeItemToLayout(initial, "c", page = 0)
        assertEquals(setOf("a", "b", "c"), added.order.toSet())
        assertEquals(added.order.toSet(), added.narrowOrder.toSet())
        assertEquals(added.order.size, added.order.toSet().size)
        assertEquals(added.narrowOrder.size, added.narrowOrder.toSet().size)

        val removed = removeHomeItemFromLayout(added, "b")
        assertEquals(listOf("a", "c"), removed.order)
        assertEquals(listOf("a", "c"), removed.narrowOrder)
        assertEquals(removed.order.toSet(), removed.narrowPageById.keys)
    }

    @Test
    fun interleavedNarrowOrderSurvivesProjectionReloadAndLaterHomeEdits() {
        val initial = HomeLayout(
            order = listOf("a", "c", "b"),
            narrowPageById = mapOf("a" to 0, "c" to 0, "b" to 1),
            narrowOrder = listOf("a", "c", "b"),
        )

        // A shared wide reorder is also the explicit synchronization event. Its page projection
        // is still page 0=[a,c], page 1=[b], so rebuilding a layout from pages.flatten() would
        // incorrectly turn the committed global order into [a,c,b].
        val shared = homeLayoutAfterWideReorder(
            layout = initial,
            order = listOf("b", "a", "c"),
            separateWideOrder = false,
        )
        assertEquals(listOf("b", "a", "c"), shared.order)
        assertEquals(shared.order, shared.narrowOrder)
        assertEquals(
            HomePages(listOf(listOf("a", "c"), listOf("b"))),
            shared.toHomePages(),
        )

        val reloaded = normalizeHomeLayout(
            storedLayout = parseHomeLayout(serializeHomeLayout(shared)),
            storedPages = shared.toHomePages(),
            legacyOrder = shared.toHomePages()[0],
            favoriteIds = listOf("a", "b", "c"),
        )
        assertEquals(listOf("b", "a", "c"), reloaded.narrowOrder)
        assertEquals(shared.narrowPageById, reloaded.narrowPageById)

        // A later add/remove or a size transaction must use the canonical record as its base and
        // therefore cannot silently re-flatten the page projection.
        val afterAdd = addHomeItemToLayout(reloaded, id = "d", page = 1)
        assertEquals(listOf("b", "a", "c", "d"), afterAdd.narrowOrder)
        assertEquals(HomePages(listOf(listOf("a", "c"), listOf("b", "d"))), afterAdd.toHomePages())
        val persistedAfterSizeTransaction = parseHomeLayout(serializeHomeLayout(afterAdd))
        assertEquals(afterAdd, persistedAfterSizeTransaction)
        val afterRemove = removeHomeItemFromLayout(persistedAfterSizeTransaction!!, "c")
        assertEquals(listOf("b", "a", "d"), afterRemove.narrowOrder)
        assertEquals(HomePages(listOf(listOf("a"), listOf("b", "d"))), afterRemove.toHomePages())
    }

    @Test
    fun independentWideReorderAndNarrowReorderKeepTheirGlobalOrdersSeparate() {
        val initial = HomeLayout(
            order = listOf("a", "c", "b"),
            narrowPageById = mapOf("a" to 0, "c" to 0, "b" to 1),
            narrowOrder = listOf("b", "a", "c"),
        )

        val wide = homeLayoutAfterWideReorder(
            layout = initial,
            order = listOf("c", "b", "a"),
            separateWideOrder = true,
        )
        assertEquals(listOf("c", "b", "a"), wide.order)
        assertEquals(listOf("b", "a", "c"), wide.narrowOrder)

        val narrow = homeLayoutAfterNarrowReorder(
            layout = wide,
            page = 0,
            order = listOf("c", "a"),
            separateWideOrder = true,
        )
        assertEquals(listOf("c", "b", "a"), narrow.order)
        assertEquals(listOf("b", "c", "a"), narrow.narrowOrder)
        assertEquals(HomePages(listOf(listOf("c", "a"), listOf("b"))), narrow.toHomePages())
    }

    @Test
    fun legacyOrderMigratesIntactToPageOneAndLeavesPageTwoEmpty() {
        val legacy = listOf(
            HomeWidget.CLOCK.id,
            "app.example/.First",
            HomeWidget.WEATHER.id,
        )

        val migrated = normalizeHomePages(
            storedPages = null,
            legacyOrder = legacy,
            favoriteIds = listOf("app.example/.First"),
        )

        assertEquals(legacy, migrated[0])
        assertTrue(migrated[1].isEmpty())
    }

    @Test
    fun newFavoritesAndExternalWidgetsRecoverOnPageOneWithoutDuplicatingPageTwo() {
        val stored = HomePages(listOf(listOf(HomeWidget.CLOCK.id), listOf("widget:external:42")))

        val normalized = normalizeHomePages(
            storedPages = stored,
            legacyOrder = null,
            favoriteIds = listOf("app.example/.First"),
            externalWidgetIds = listOf("widget:external:42", "widget:external:99"),
        )

        assertEquals(
            listOf(HomeWidget.CLOCK.id, "app.example/.First", "widget:external:99"),
            normalized[0],
        )
        assertEquals(listOf("widget:external:42"), normalized[1])
    }

    @Test
    fun codecRoundTripsBothPagesAndRejectsLegacyPayload() {
        val original = HomePages(listOf(listOf("widget:clock", "pkg/name;with,punctuation"), listOf("widget:external:7")))

        assertEquals(original, parseHomePages(serializeHomePages(original)))
        assertNull(parseHomePages("widget:clock\nwidget:weather"))
        assertNull(parseHomePages("v2:only-one-page"))
        assertEquals(HomePages.empty(), parseHomePages("v2:;"))
        assertNull(parseHomePages("v2:;;"))
        assertNull(parseHomePages("v2:!!!;"))
        assertNull(parseHomePages("v2:YWJj,"))
        assertNull(parseHomePages("v2:YWJj,,ZGVm;"))
        assertNull(parseHomePages("v2:YWJj;ZGVm,"))
    }

    @Test
    fun validV2PagesRemainAuthoritativeOverAConflictingLegacyMirror() {
        val v2 = HomePages(listOf(listOf(HomeWidget.CLOCK.id), listOf(HomeWidget.WEATHER.id)))

        val normalized = normalizeHomePages(
            storedPages = v2,
            legacyOrder = listOf(HomeWidget.AGENDA.id),
            favoriteIds = emptyList(),
        )

        assertEquals(v2, normalized)
    }

    @Test
    fun pageUpdatesAreIndependentAndIdsRemainUnique() {
        val initial = HomePages(listOf(listOf("a", "b"), listOf("c")))

        val moved = addHomeItemToPage(initial, page = 1, id = "d")
        assertEquals(listOf("a", "b"), moved[0])
        assertEquals(listOf("c", "d"), moved[1])

        val unchanged = addHomeItemToPage(moved, page = 0, id = "c")
        assertEquals(moved, unchanged)

        val reordered = updateHomePage(moved, page = 1) { listOf("d", "c") }
        assertEquals(listOf("a", "b"), reordered[0])
        assertEquals(listOf("d", "c"), reordered[1])
        assertEquals(1, homePageContaining(reordered, "d"))
        assertEquals(HomePages(listOf(listOf("a"), listOf("d", "c"))), removeHomeItemFromPages(reordered, "b"))
    }

    @Test
    fun favoriteAdditionAppendsToRequestedPageAndRepairsStaleOwnership() {
        val addition = computeFavoriteHomePageAddition(
            favoriteIds = listOf("existing"),
            homePages = HomePages(listOf(listOf("widget:clock", "new-app"), listOf("widget:weather"))),
            id = "new-app",
            targetHomePage = 1,
        )

        assertEquals(
            FavoriteHomePageAddition(
                favoriteIds = listOf("existing", "new-app"),
                homePages = HomePages(listOf(listOf("widget:clock"), listOf("widget:weather", "new-app"))),
            ),
            addition,
        )
    }

    @Test
    fun favoriteAdditionClampsTargetAndRejectsExistingFavorite() {
        val addition = computeFavoriteHomePageAddition(
            favoriteIds = emptyList(),
            homePages = HomePages.empty(),
            id = "new-app",
            targetHomePage = 99,
        )

        assertEquals(listOf("new-app"), addition?.favoriteIds)
        assertEquals(listOf("new-app"), addition?.homePages?.get(1))
        assertNull(
            computeFavoriteHomePageAddition(
                favoriteIds = listOf("new-app"),
                homePages = HomePages.empty(),
                id = "new-app",
                targetHomePage = 0,
            ),
        )
    }

    @Test
    fun favoritePageTransferChangesOnlyNarrowOwnership() {
        val initial = HomeLayout(
            order = listOf("a", "widget:clock", "b", "c"),
            narrowPageById = mapOf(
                "a" to 0,
                "widget:clock" to 1,
                "b" to 0,
                "c" to 1,
            ),
            narrowOrder = listOf("c", "a", "widget:clock", "b"),
        )

        val transfer = computeHomeItemPageTransfer(
            layout = initial,
            favoriteIds = listOf("a", "b", "c"),
            id = "a",
            targetPage = 1,
        )

        requireNotNull(transfer)
        assertTrue(transfer.changed)
        assertEquals(0, transfer.sourcePage)
        assertEquals(1, transfer.targetPage)
        assertEquals(initial.order, transfer.layout.order)
        assertEquals(initial.narrowOrder, transfer.layout.narrowOrder)
        assertEquals(1, transfer.layout.pageOf("a"))
        assertEquals(0, transfer.layout.pageOf("b"))
        assertEquals(1, transfer.layout.pageOf("widget:clock"))
        assertEquals(
            HomePages(listOf(listOf("b"), listOf("c", "a", "widget:clock"))),
            transfer.layout.toHomePages(),
        )
        assertEquals(transfer.layout, parseHomeLayout(serializeHomeLayout(transfer.layout)))
    }

    @Test
    fun favoritePageTransferRejectsStaleOrNonFavoriteAndTreatsSamePageAsNoOp() {
        val initial = HomeLayout(
            order = listOf("a", "b"),
            narrowPageById = mapOf("a" to 0, "b" to 1),
            narrowOrder = listOf("b", "a"),
        )

        val samePage = computeHomeItemPageTransfer(
            layout = initial,
            favoriteIds = listOf("a", "b"),
            id = "a",
            targetPage = 0,
        )
        requireNotNull(samePage)
        assertFalse(samePage.changed)
        assertEquals(initial, samePage.layout)

        assertNull(
            computeHomeItemPageTransfer(
                layout = initial,
                favoriteIds = listOf("a", "b"),
                id = "removed",
                targetPage = 1,
            ),
        )
        assertNull(
            computeHomeItemPageTransfer(
                layout = initial,
                favoriteIds = listOf("a"),
                id = "b",
                targetPage = 0,
            ),
        )
    }

    @Test
    fun favoritePageTransferEligibilityRequiresInstalledDirectFavorite() {
        val layout = HomeLayout(
            order = listOf("direct", "other"),
            narrowPageById = mapOf("direct" to 0, "other" to 1),
        )

        assertTrue(
            isHomeItemPageTransferEligible(
                layout = layout,
                favoriteIds = listOf("direct"),
                installedAppIds = listOf("direct"),
                id = "direct",
            ),
        )
        assertFalse(
            isHomeItemPageTransferEligible(
                layout = layout,
                favoriteIds = listOf("direct"),
                installedAppIds = emptyList(),
                id = "direct",
            ),
        )
        assertFalse(
            isHomeItemPageTransferEligible(
                layout = layout,
                favoriteIds = listOf("direct"),
                installedAppIds = listOf("other"),
                id = "other",
            ),
        )
        assertFalse(
            isHomeItemPageTransferEligible(
                layout = layout,
                favoriteIds = listOf("folder-member"),
                installedAppIds = listOf("folder-member"),
                id = "folder-member",
            ),
        )
    }

    @Test
    fun codecCanonicalizesDuplicateOwnership() {
        val malformed = HomePages(listOf(listOf("same", "same"), listOf("same", "other")))

        assertEquals(
            HomePages(listOf(listOf("same"), listOf("other"))),
            parseHomePages(serializeHomePages(malformed)),
        )
    }
}
