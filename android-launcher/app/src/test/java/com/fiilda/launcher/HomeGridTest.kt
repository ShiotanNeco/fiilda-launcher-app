package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGridTest {
    @Test
    fun homeGridUsesThreeDpSharedGapBaseline() {
        assertEquals(3, HomeGridGapDp)
        assertEquals(203, homeGridSpanSizePx(cellWidthPx = 100, gapPx = HomeGridGapDp, span = 2))
    }

    @Test
    fun horizontalPlanKeepsSixRowsAndGrowsByColumns() {
        val plan = horizontalHomeGridPlan(
            listOf(
                HomeGridItem("wide", columnSpan = 2, rowSpan = 1),
                HomeGridItem("large", columnSpan = 1, rowSpan = 2),
                HomeGridItem("tall", columnSpan = 1, rowSpan = 3),
                HomeGridItem("small", columnSpan = 1, rowSpan = 1),
            ),
            rows = 6,
        )

        assertEquals(6, plan.rows)
        assertEquals(HomeGridPlacement("wide", 0, 0, 2, 1), plan.placementOf("wide"))
        assertEquals(HomeGridPlacement("large", 0, 1, 1, 2), plan.placementOf("large"))
        assertEquals(HomeGridPlacement("tall", 0, 3, 1, 3), plan.placementOf("tall"))
        assertEquals(HomeGridPlacement("small", 1, 1, 1, 1), plan.placementOf("small"))
        assertTrue(plan.columns >= 2)
        assertNoOverlaps(plan.placements, plan.rows)
    }

    @Test
    fun horizontalPlanClampsOversizedHeightAndIsDeterministic() {
        val items = listOf(
            HomeGridItem("oversized", columnSpan = 2, rowSpan = 99),
            HomeGridItem("next", columnSpan = 1, rowSpan = 1),
        )
        val first = startCanvasGridPlan(items, rows = 6)
        val second = startCanvasGridPlan(items, rows = 6)
        assertEquals(first, second)
        assertEquals(HomeGridPlacement("oversized", 0, 0, 2, 6), first.placementOf("oversized"))
        assertEquals(HomeGridPlacement("next", 2, 0, 1, 1), first.placementOf("next"))
        assertNoOverlaps(first.placements, first.rows)
    }

    @Test
    fun horizontalTargetReorderUsesColumnMajorCellWithoutChangingFootprints() {
        val order = listOf("a", "b", "c", "d")
        val sizes = order.associateWith { HomeGridItem(it, 1, 1) }
        val updated = reorderHorizontalHomeOrderForTargetCell(
            order = order,
            draggedId = "d",
            targetCell = HomeGridCell(column = 0, row = 1),
            itemSizes = sizes,
            rows = 6,
        )
        val plan = horizontalHomeGridGridPlanForTest(updated, sizes)
        assertEquals(HomeGridCell(0, 1), plan.placementOf("d")?.let {
            HomeGridCell(it.column, it.row)
        })
        assertEquals(order.toSet(), updated.toSet())
    }

    @Test
    fun photoCasePlacesPayPayBelowYouTubeWithoutMovingX() {
        val items = listOf(
            HomeGridItem("line", columnSpan = 2, rowSpan = 1),
            HomeGridItem("chrome", columnSpan = 1, rowSpan = 1),
            HomeGridItem("camera", columnSpan = 1, rowSpan = 1),
            HomeGridItem("calendar", columnSpan = 2, rowSpan = 2),
            HomeGridItem("youtube", columnSpan = 1, rowSpan = 1),
            HomeGridItem("x", columnSpan = 1, rowSpan = 1),
            HomeGridItem("paypay", columnSpan = 1, rowSpan = 1),
            HomeGridItem("touhou", columnSpan = 2, rowSpan = 1),
            HomeGridItem("spotify", columnSpan = 1, rowSpan = 1),
        )

        val plan = denseHomeGridPlan(items, columns = 4)

        assertEquals(HomeGridPlacement("line", 0, 0, 2, 1), plan.placementOf("line"))
        assertEquals(HomeGridPlacement("chrome", 2, 0, 1, 1), plan.placementOf("chrome"))
        assertEquals(HomeGridPlacement("camera", 3, 0, 1, 1), plan.placementOf("camera"))
        assertEquals(HomeGridPlacement("calendar", 0, 1, 2, 2), plan.placementOf("calendar"))
        assertEquals(HomeGridPlacement("youtube", 2, 1, 1, 1), plan.placementOf("youtube"))
        assertEquals(HomeGridPlacement("x", 3, 1, 1, 1), plan.placementOf("x"))
        assertEquals(HomeGridPlacement("paypay", 2, 2, 1, 1), plan.placementOf("paypay"))
        assertEquals(HomeGridPlacement("touhou", 0, 3, 2, 1), plan.placementOf("touhou"))
        assertEquals(HomeGridPlacement("spotify", 3, 2, 1, 1), plan.placementOf("spotify"))
        assertEquals(4, plan.rows)
        assertNoOverlaps(plan)
    }

    @Test
    fun pointerTargetOrderKeepsXAtItsCellWhenPayPayTargetsTheHole() {
        val order = listOf("line", "chrome", "camera", "calendar", "youtube", "x", "paypay")
        val sizes = order.associateWith { id ->
            when (id) {
                "line" -> HomeGridItem(id, 2, 1)
                "calendar" -> HomeGridItem(id, 2, 2)
                "touhou" -> HomeGridItem(id, 2, 1)
                else -> HomeGridItem(id, 1, 1)
            }
        }

        val updated = reorderHomeOrderForTargetCell(
            order = order,
            draggedId = "paypay",
            targetCell = HomeGridCell(column = 2, row = 2),
            itemSizes = sizes,
            columns = 4,
        )
        val plan = denseHomeGridPlan(
            updated.map { id -> sizes.getValue(id) },
            columns = 4,
        )

        assertEquals(order, updated)
        assertEquals(HomeGridCell(2, 2), plan.placementOf("paypay")?.let {
            HomeGridCell(it.column, it.row)
        })
        assertEquals(HomeGridCell(3, 1), plan.placementOf("x")?.let {
            HomeGridCell(it.column, it.row)
        })
        assertNoOverlaps(plan)
    }

    @Test
    fun targetCellReordersPayPayBeforeWideTileWithoutMovingX() {
        val order = listOf(
            "line", "chrome", "camera", "calendar", "youtube", "x", "touhou", "spotify", "paypay",
        )
        val sizes = order.associateWith { id ->
            when (id) {
                "line", "touhou" -> HomeGridItem(id, 2, 1)
                "calendar" -> HomeGridItem(id, 2, 2)
                else -> HomeGridItem(id, 1, 1)
            }
        }

        val updated = reorderHomeOrderForTargetCell(
            order = order,
            draggedId = "paypay",
            targetCell = HomeGridCell(column = 2, row = 2),
            itemSizes = sizes,
            columns = 4,
        )
        val plan = denseHomeGridPlan(updated.map { sizes.getValue(it) }, columns = 4)

        assertEquals(
            listOf("line", "chrome", "camera", "calendar", "youtube", "x", "paypay", "touhou", "spotify"),
            updated,
        )
        assertEquals(HomeGridCell(2, 2), plan.placementOf("paypay")?.let {
            HomeGridCell(it.column, it.row)
        })
        assertEquals(HomeGridCell(3, 1), plan.placementOf("x")?.let {
            HomeGridCell(it.column, it.row)
        })
        assertNoOverlaps(plan)
    }

    @Test
    fun sixColumnPlanSupportsMixedFootprintsAndIsDeterministic() {
        val items = listOf(
            HomeGridItem("wide", columnSpan = 2, rowSpan = 1),
            HomeGridItem("large", columnSpan = 2, rowSpan = 2),
            HomeGridItem("external", columnSpan = 3, rowSpan = 2),
            HomeGridItem("small", columnSpan = 1, rowSpan = 1),
        )

        val first = denseHomeGridPlan(items, columns = 6)
        val second = denseHomeGridPlan(items, columns = 6)

        assertEquals(first, second)
        assertEquals(HomeGridPlacement("wide", 0, 0, 2, 1), first.placementOf("wide"))
        assertEquals(HomeGridPlacement("large", 2, 0, 2, 2), first.placementOf("large"))
        assertEquals(HomeGridPlacement("external", 0, 2, 3, 2), first.placementOf("external"))
        assertEquals(HomeGridPlacement("small", 4, 0, 1, 1), first.placementOf("small"))
        assertEquals(4, first.rows)
        assertNoOverlaps(first)
    }

    @Test
    fun pointerMapsToLowerCellAndClampsHorizontalEdge() {
        val board = HomeItemBounds(
            id = "board",
            left = 10f,
            top = 20f,
            right = 410f,
            bottom = 300f,
        )

        assertEquals(
            HomeGridCell(column = 2, row = 1),
            homeGridCellAt(
                pointer = HomePointer(x = 10f + 2f * 103f + 50f, y = 20f + 1f * 103f + 50f),
                board = board,
                cellWidthPx = 100f,
                gapPx = 3f,
                columns = 4,
            ),
        )
        assertEquals(
            HomeGridCell(column = 3, row = 0),
            homeGridCellAt(
                pointer = HomePointer(x = 10_000f, y = 70f),
                board = board,
                cellWidthPx = 100f,
                gapPx = 3f,
                columns = 4,
            ),
        )
        assertEquals(
            null,
            homeGridCellAt(
                pointer = HomePointer(x = 5f, y = 50f),
                board = board,
                cellWidthPx = 100f,
                gapPx = 3f,
                columns = 4,
            ),
        )
    }

    @Test
    fun oversizedSpansClampToColumnsWithoutOverlap() {
        val plan = denseHomeGridPlan(
            listOf(
                HomeGridItem("oversized", columnSpan = 99, rowSpan = 2),
                HomeGridItem("small", columnSpan = 1, rowSpan = 1),
            ),
            columns = 4,
        )

        assertEquals(HomeGridPlacement("oversized", 0, 0, 4, 2), plan.placementOf("oversized"))
        assertEquals(HomeGridPlacement("small", 0, 2, 1, 1), plan.placementOf("small"))
        assertNoOverlaps(plan)
    }

    @Test
    fun boardPixelDimensionsUseRoundedCellAndGapForBothPostures() {
        assertEquals(413, homeGridBoardHeightPx(cellWidthPx = 101, gapPx = 3, rows = 4))
        assertEquals(602, homeGridBoardHeightPx(cellWidthPx = 97, gapPx = 4, rows = 6))
        assertEquals(413, homeGridBoardWidthPx(cellWidthPx = 101, gapPx = 3, columns = 4))
        assertEquals(602, homeGridBoardWidthPx(cellWidthPx = 97, gapPx = 4, columns = 6))
    }

    @Test
    fun startCanvasStrideRoundsCellAndGapIndependently() {
        val stride = homeGridPixelStride(
            cellWidthDp = 10.4f,
            gapDp = 2.4f,
            density = 1.5f,
        )

        assertEquals(16, stride.cellWidthPx)
        assertEquals(4, stride.gapPx)
        assertEquals(20, stride.stridePx)
    }

    @Test
    fun horizontalColumnWindowChangesOnlyAtColumnBoundariesAndAddsOverscan() {
        assertEquals(
            0..4,
            homeGridColumnWindow(
                scrollOffsetPx = 0,
                viewportWidthPx = 300,
                cellStridePx = 100,
                totalColumns = 12,
            ),
        )
        // Pixel movement within the same leading column leaves the window unchanged.
        assertEquals(
            0..4,
            homeGridColumnWindow(
                scrollOffsetPx = 99,
                viewportWidthPx = 300,
                cellStridePx = 100,
                totalColumns = 12,
            ),
        )
        assertEquals(
            0..5,
            homeGridColumnWindow(
                scrollOffsetPx = 100,
                viewportWidthPx = 300,
                cellStridePx = 100,
                totalColumns = 12,
            ),
        )
    }

    @Test
    fun horizontalColumnWindowRetainsMultiCellAndDraggedPlacements() {
        val plan = HomeGridPlan(
            columns = 8,
            rows = 2,
            placements = listOf(
                HomeGridPlacement("near", column = 1, row = 0, columnSpan = 2, rowSpan = 1),
                HomeGridPlacement("far", column = 7, row = 0, columnSpan = 1, rowSpan = 1),
            ),
        )

        assertEquals(
            listOf("near", "far"),
            homeGridPlacementsInColumnWindow(
                plan = plan,
                columnWindow = 0..2,
                retainedItemId = "far",
            ).map { it.id },
        )
        assertEquals(
            listOf("near"),
            homeGridPlacementsInColumnWindow(
                plan = plan,
                columnWindow = 0..2,
            ).map { it.id },
        )
    }

    private fun assertNoOverlaps(plan: HomeGridPlan) {
        assertNoOverlaps(plan.placements, plan.rows)
    }

    private fun assertNoOverlaps(placements: List<HomeGridPlacement>, rows: Int) {
        placements.forEachIndexed { index, first ->
            placements.drop(index + 1).forEach { second ->
                val overlap = first.column < second.rightColumn &&
                    second.column < first.rightColumn &&
                    first.row < second.bottomRow &&
                    second.row < first.bottomRow
                assertFalse("${first.id} overlaps ${second.id}", overlap)
            }
        }
        assertTrue(rows >= 1)
    }

    private fun horizontalHomeGridGridPlanForTest(
        order: List<String>,
        sizes: Map<String, HomeGridItem>,
    ): HorizontalHomeGridPlan = horizontalHomeGridPlan(order.map { sizes.getValue(it) }, rows = 6)
}
