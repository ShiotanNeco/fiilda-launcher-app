package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize

class FolderSupportTest {
    @Test
    fun folderOpenUsesFreshBoundsBeforePublishedBounds() {
        val published = IntRect(left = 10, top = 20, right = 110, bottom = 120)
        val fresh = IntRect(left = 30, top = 40, right = 130, bottom = 140)

        assertEquals(fresh, homeFolderOpenBounds(fresh, published))
        assertEquals(published, homeFolderOpenBounds(null, published))
        assertNull(homeFolderOpenBounds(null, null))
    }

    @Test
    fun folderSheetTargetIsFullWidthAndBottomAnchored() {
        assertEquals(
            androidx.compose.ui.unit.IntRect(left = 0, top = 1020, right = 1080, bottom = 1920),
            folderSheetTargetRect(
                viewport = IntSize(1080, 1920),
                sheetHeight = 900,
            ),
        )
        assertEquals(
            androidx.compose.ui.unit.IntRect(left = 0, top = 0, right = 300, bottom = 120),
            folderSheetTargetRect(
                viewport = IntSize(300, 120),
                sheetHeight = 900,
            ),
        )
    }

    @Test
    fun folderCodecRoundTripsMembershipSizeAndSanitizedName() {
        val folders = listOf(
            HomeFolder(
                id = "folder:1",
                name = " 仕事\n用\tアプリ ",
                memberIds = listOf("app.one", "app.two"),
                size = HomeFolderSize.LARGE,
            ),
        )

        val renamed = renameHomeFolder(folders, "folder:1", folders.single().name)

        assertEquals("仕事 用 アプリ", renamed?.single()?.name)
        assertEquals(renamed, parseHomeFolders(serializeHomeFolders(renamed.orEmpty())))
    }

    @Test
    fun normalizeFoldersDropsUnknownAppsAndAssignsDuplicateMemberToFirstFolder() {
        val normalized = normalizeHomeFolders(
            stored = listOf(
                HomeFolder("folder:1", memberIds = listOf("a", "b")),
                HomeFolder("folder:2", memberIds = listOf("b", "c")),
                HomeFolder("folder:3", memberIds = listOf("missing")),
            ),
            favoriteIds = setOf("a", "b", "c"),
        )

        assertEquals(
            listOf(
                HomeFolder("folder:1", memberIds = listOf("a", "b")),
                HomeFolder("folder:2", memberIds = listOf("c")),
            ),
            normalized,
        )
    }

    @Test
    fun appDroppedOnAppCreatesFolderAndKeepsBothPresentationOrders() {
        val firstSourceLayout = HomeLayout(
            order = listOf("b", "a", "c"),
            narrowPageById = mapOf("b" to 0, "a" to 1, "c" to 0),
            narrowOrder = listOf("b", "a", "c"),
        )
        val secondSourceLayout = firstSourceLayout.copy(
            order = listOf("a", "b", "c"),
            narrowOrder = listOf("a", "b", "c"),
        )

        listOf(firstSourceLayout, secondSourceLayout).forEach { layout ->
            val transition = homeFolderDropTransition(
                layout = layout,
                folders = emptyList(),
                draggedAppId = "b",
                targetId = "a",
            )

            requireNotNull(transition)
            assertEquals(listOf("folder:1", "c"), transition.layout.order)
            assertEquals(listOf("folder:1", "c"), transition.layout.narrowOrder)
            assertEquals(listOf("a", "b"), transition.folders.single().memberIds)
            assertFalse("a" in transition.layout.allIds)
            assertFalse("b" in transition.layout.allIds)
            assertEquals(
                listOf("c", "a", "b"),
                favoriteIdsForHomeLayout(transition.layout, listOf("a", "b", "c")),
            )
        }
    }

    @Test
    fun appDroppedOnFolderAddsMemberWithoutMergingFolders() {
        val folder = HomeFolder("folder:1", memberIds = listOf("a", "b"))
        val layout = HomeLayout(
            order = listOf("folder:1", "c"),
            narrowPageById = mapOf("folder:1" to 0, "c" to 1),
            narrowOrder = listOf("folder:1", "c"),
        )

        val transition = homeFolderDropTransition(layout, listOf(folder), "c", "folder:1")

        assertEquals(listOf("folder:1"), transition?.layout?.order)
        assertEquals(listOf("a", "b", "c"), transition?.folders?.single()?.memberIds)
        assertNull(homeFolderDropTransition(layout, listOf(folder), "c", "folder:missing"))
    }

    @Test
    fun layoutNormalizationExcludesFolderMembersAndDoesNotRecoverStaleMirrorWidgets() {
        val folder = HomeFolder("folder:1", memberIds = listOf("a", "b"))
        val normalized = normalizeHomeLayout(
            storedLayout = HomeLayout(
                order = listOf("folder:1", "a", "c"),
                narrowPageById = mapOf("folder:1" to 0, "a" to 0, "c" to 1),
                narrowOrder = listOf("a", "folder:1", "c"),
            ),
            storedPages = HomePages(listOf(listOf("folder:1", "a", HomeWidget.WEATHER.id), listOf("c"))),
            legacyOrder = listOf(HomeWidget.WEATHER.id),
            favoriteIds = listOf("a", "b", "c"),
            homeFolders = listOf(folder),
        )

        assertEquals(listOf("folder:1", "c"), normalized.order)
        assertEquals(listOf("folder:1", "c"), normalized.narrowOrder)
        assertFalse("a" in normalized.allIds)
        assertFalse("b" in normalized.allIds)
        assertFalse(HomeWidget.WEATHER.id in normalized.allIds)
    }

    @Test
    fun folderDropHitZoneAcceptsCenterAndLeavesOuterEdgeForReorder() {
        val placement = HomeGridPlacement(
            id = "target",
            column = 1,
            row = 0,
            columnSpan = 1,
            rowSpan = 1,
        )
        val board = HomeItemBounds("board", 0f, 0f, 320f, 200f)

        assertTrue(
            homeFolderDropHitZone(
                pointer = HomePointer(160f, 50f),
                board = board,
                placement = placement,
                cellWidthPx = 100f,
                gapPx = 10f,
            ),
        )
        assertFalse(
            homeFolderDropHitZone(
                pointer = HomePointer(116f, 50f),
                board = board,
                placement = placement,
                cellWidthPx = 100f,
                gapPx = 10f,
            ),
        )
    }

    @Test
    fun fastOuterEdgeReleaseUsesReorderCandidateInsteadOfNoOp() {
        val board = HomeItemBounds("board", 0f, 0f, 640f, 640f)
        val target = HomeGridPlacement(
            id = "b",
            column = 1,
            row = 0,
            columnSpan = 1,
            rowSpan = 1,
        )
        val leadingEdge = homeFolderDropDecision(
            pointer = HomePointer(116f, 50f),
            board = board,
            activeTargetPlacement = null,
            activeTargetId = null,
            hoveredTargetPlacement = target,
            hoveredTargetId = "b",
            draggedId = "a",
            cellWidthPx = 100f,
            gapPx = 10f,
        )
        assertEquals(HomeFolderDropMode.HOLD, leadingEdge.mode)
        assertEquals("b", leadingEdge.targetId)

        val reorder = reorderHomeOrderForTargetCell(
            order = listOf("a", "b", "c"),
            draggedId = "a",
            targetCell = HomeGridCell(column = 1, row = 0),
            itemSizes = mapOf(
                "a" to HomeGridItem("a", 1, 1),
                "b" to HomeGridItem("b", 1, 1),
                "c" to HomeGridItem("c", 1, 1),
            ),
            columns = 3,
        )
        assertEquals(listOf("b", "a", "c"), reorder)
    }

    @Test
    fun slowLeadingEdgeThenCenterStillMergesTheSameTarget() {
        val board = HomeItemBounds("board", 0f, 0f, 640f, 640f)
        val target = HomeGridPlacement(
            id = "b",
            column = 1,
            row = 0,
            columnSpan = 1,
            rowSpan = 1,
        )
        val leadingEdge = homeFolderDropDecision(
            pointer = HomePointer(116f, 50f),
            board = board,
            activeTargetPlacement = null,
            activeTargetId = null,
            hoveredTargetPlacement = target,
            hoveredTargetId = "b",
            draggedId = "a",
            cellWidthPx = 100f,
            gapPx = 10f,
        )
        assertEquals(HomeFolderDropMode.HOLD, leadingEdge.mode)

        val center = homeFolderDropDecision(
            pointer = HomePointer(160f, 50f),
            board = board,
            activeTargetPlacement = target,
            activeTargetId = leadingEdge.targetId,
            hoveredTargetPlacement = target,
            hoveredTargetId = "b",
            draggedId = "a",
            cellWidthPx = 100f,
            gapPx = 10f,
        )
        assertEquals(HomeFolderDropMode.MERGE, center.mode)
        assertEquals("b", center.targetId)
    }

    @Test
    fun leavingOuterBandReleasesTargetAndReflowCanSelectAnotherTarget() {
        val board = HomeItemBounds("board", 0f, 0f, 640f, 640f)
        val target = HomeGridPlacement(
            id = "b",
            column = 1,
            row = 0,
            columnSpan = 1,
            rowSpan = 1,
        )
        val exit = homeFolderDropDecision(
            pointer = HomePointer(215f, 50f),
            board = board,
            activeTargetPlacement = target,
            activeTargetId = "b",
            hoveredTargetPlacement = null,
            hoveredTargetId = null,
            draggedId = "a",
            cellWidthPx = 100f,
            gapPx = 10f,
        )
        assertEquals(HomeFolderDropMode.REORDER, exit.mode)
        assertNull(exit.targetId)

        val sizes = mapOf(
            "a" to HomeGridItem("a", 1, 1),
            "b" to HomeGridItem("b", 1, 1),
            "c" to HomeGridItem("c", 1, 1),
        )
        val reflowedOrder = reorderHomeOrderForTargetCell(
            order = listOf("a", "b", "c"),
            draggedId = "a",
            targetCell = HomeGridCell(column = 3, row = 0),
            itemSizes = sizes,
            columns = 4,
        )
        val reflowedPlan = denseHomeGridPlan(
            reflowedOrder.mapNotNull(sizes::get),
            columns = 4,
        )
        val nextTarget = requireNotNull(reflowedPlan.placementOf("c"))
        val nextCenter = HomePointer(
            x = nextTarget.column * 110f + 50f,
            y = nextTarget.row * 110f + 50f,
        )
        val nextDecision = homeFolderDropDecision(
            pointer = nextCenter,
            board = board,
            activeTargetPlacement = null,
            activeTargetId = null,
            hoveredTargetPlacement = nextTarget,
            hoveredTargetId = "c",
            draggedId = "a",
            cellWidthPx = 100f,
            gapPx = 10f,
        )
        assertEquals(HomeFolderDropMode.MERGE, nextDecision.mode)
        assertEquals("c", nextDecision.targetId)
    }

    @Test
    fun wideColumnMajorCenterUsesSameMergeDecision() {
        val board = HomeItemBounds("board", 0f, 0f, 640f, 1000f)
        val widePlan = horizontalHomeGridPlan(
            items = listOf(
                HomeGridItem("a", 1, 1),
                HomeGridItem("b", 1, 1),
                HomeGridItem("c", 1, 1),
            ),
            rows = 6,
        )
        val target = requireNotNull(widePlan.placementOf("b"))
        val reverseCenter = homeFolderDropDecision(
            pointer = HomePointer(
                x = target.column * 110f + 50f,
                y = target.row * 110f + 50f,
            ),
            board = board,
            activeTargetPlacement = null,
            activeTargetId = null,
            hoveredTargetPlacement = target,
            hoveredTargetId = "b",
            draggedId = "c",
            cellWidthPx = 100f,
            gapPx = 10f,
        )
        assertEquals(HomeFolderDropMode.MERGE, reverseCenter.mode)
        assertEquals("b", reverseCenter.targetId)
    }

    @Test
    fun folderMembershipRemovalRestoresNoEmptyFolderAndDissolveRestoresMembers() {
        val folder = HomeFolder("folder:1", memberIds = listOf("a", "b"))
        val layout = HomeLayout(
            order = listOf("folder:1", "c"),
            narrowPageById = mapOf("folder:1" to 0, "c" to 1),
            narrowOrder = listOf("folder:1", "c"),
        )

        val removed = removeAppFromHomeFolders(layout, listOf(folder), "a")
        assertEquals(listOf("b"), removed.folders.single().memberIds)
        assertEquals(layout, removed.layout)

        val dissolved = dissolveHomeFolder(layout, listOf(folder), "folder:1")
        assertEquals(listOf("a", "b", "c"), dissolved?.layout?.narrowOrder)
        assertEquals(emptyList<HomeFolder>(), dissolved?.folders)
    }

    @Test
    fun returningFolderMemberRestoresFormerSlotAndPage() {
        val folder = HomeFolder("folder:1", memberIds = listOf("a", "b"))
        val layout = HomeLayout(
            order = listOf("c", "folder:1", "d"),
            narrowPageById = mapOf("c" to 1, "folder:1" to 1, "d" to 0),
            narrowOrder = listOf("d", "folder:1", "c"),
        )

        val returned = requireNotNull(
            returnAppFromHomeFolder(layout, listOf(folder), "a"),
        )
        assertEquals(listOf("c", "a", "folder:1", "d"), returned.layout.order)
        assertEquals(listOf("d", "a", "folder:1", "c"), returned.layout.narrowOrder)
        assertEquals(1, returned.layout.pageOf("a"))
        assertEquals(listOf("b"), returned.folders.single().memberIds)

        val last = requireNotNull(returnAppFromHomeFolder(layout, listOf(folder.copy(memberIds = listOf("a"))), "a"))
        assertEquals(listOf("c", "a", "d"), last.layout.order)
        assertEquals(emptyList<HomeFolder>(), last.folders)
    }

    @Test
    fun folderMemberReorderRejectsUnknownIdsAndPreservesRequestedOrder() {
        val folders = listOf(HomeFolder("folder:1", memberIds = listOf("a", "b", "c")))
        assertEquals(
            listOf(HomeFolder("folder:1", memberIds = listOf("c", "a", "b"))),
            reorderHomeFolderMembers(folders, "folder:1", listOf("c", "a", "b")),
        )
        assertNull(reorderHomeFolderMembers(folders, "folder:1", listOf("c", "a")))
        assertNull(reorderHomeFolderMembers(folders, "folder:1", listOf("c", "a", "x")))
        assertNull(reorderHomeFolderMembers(folders, "folder:1", listOf("c", "a", "b", "x")))
        assertNull(reorderHomeFolderMembers(folders, "folder:1", listOf("c", "a", "b", "b")))
    }

    @Test
    fun transientFolderMemberMoveUsesLatestOrderAndRejectsInvalidTargets() {
        assertEquals(
            listOf("b", "c", "a"),
            moveFolderMember(listOf("a", "b", "c"), fromIndex = 0, toIndex = 2),
        )
        assertEquals(
            listOf("c", "a", "b"),
            moveFolderMember(listOf("a", "b", "c"), fromIndex = 2, toIndex = 0),
        )
        assertNull(moveFolderMember(listOf("a", "b", "c"), fromIndex = 3, toIndex = 0))
        assertNull(moveFolderMember(listOf("a", "b", "c"), fromIndex = 1, toIndex = 1))
    }

}
