package com.fiilda.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.unit.Constraints
import kotlin.math.floor
import kotlin.math.roundToInt

/** Shared dp spacing for the home board and its multi-cell app presentations. */
internal const val HomeGridGapDp = 3

/** Pixel values obtained by rounding the cell and gap independently, as HomeGridLayout does. */
internal data class HomeGridPixelStride(
    val cellWidthPx: Int,
    val gapPx: Int,
) {
    val stridePx: Int
        get() = cellWidthPx + gapPx
}

/**
 * Keeps StartCanvas' scroll stride in lockstep with HomeGridLayout's separate Dp-to-pixel
 * rounding. Rounding the combined Dp width instead can differ by one pixel at fractional density.
 */
internal fun homeGridPixelStride(
    cellWidthDp: Float,
    gapDp: Float,
    density: Float,
): HomeGridPixelStride {
    val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    return HomeGridPixelStride(
        cellWidthPx = (cellWidthDp.coerceAtLeast(0f) * safeDensity).roundToInt(),
        gapPx = (gapDp.coerceAtLeast(0f) * safeDensity).roundToInt(),
    )
}

/**
 * A home item footprint used by the pure 2D placement model.
 *
 * The persisted home order remains a list, but a list is only the deterministic input to this
 * dense first-fit model.  The resulting row/column coordinates are deliberately derived from the
 * footprint instead of being inferred from measured rectangles during a drag.
 */
internal data class HomeGridItem(
    val id: String,
    val columnSpan: Int,
    val rowSpan: Int,
)

internal data class HomeGridCell(
    val column: Int,
    val row: Int,
)

internal data class HomeGridPlacement(
    val id: String,
    val column: Int,
    val row: Int,
    val columnSpan: Int,
    val rowSpan: Int,
) {
    val rightColumn: Int
        get() = column + columnSpan

    val bottomRow: Int
        get() = row + rowSpan

    fun contains(cell: HomeGridCell): Boolean =
        cell.column in column until rightColumn && cell.row in row until bottomRow
}

internal data class HomeGridPlan(
    val columns: Int,
    val placements: List<HomeGridPlacement>,
    val rows: Int,
) {
    fun placementOf(id: String): HomeGridPlacement? = placements.firstOrNull { it.id == id }

    fun placementAt(cell: HomeGridCell): HomeGridPlacement? =
        placements.firstOrNull { it.contains(cell) }
}

/** Pixel dimensions shared by the planner, the custom Layout, and board hit testing. */
internal fun homeGridSpanSizePx(cellWidthPx: Int, gapPx: Int, span: Int): Int {
    val safeCell = cellWidthPx.coerceAtLeast(1)
    val safeGap = gapPx.coerceAtLeast(0)
    val safeSpan = span.coerceAtLeast(1)
    return safeCell * safeSpan + safeGap * (safeSpan - 1)
}

internal fun homeGridBoardWidthPx(cellWidthPx: Int, gapPx: Int, columns: Int): Int {
    val safeColumns = columns.coerceAtLeast(1)
    return homeGridSpanSizePx(cellWidthPx, gapPx, safeColumns)
}

internal fun homeGridBoardHeightPx(cellWidthPx: Int, gapPx: Int, rows: Int): Int {
    val safeRows = rows.coerceAtLeast(1)
    return homeGridSpanSizePx(cellWidthPx, gapPx, safeRows)
}

/**
 * Places [items] in row-major dense first-fit order.
 *
 * Every item occupies all cells in its footprint.  In particular, a 2x2 calendar followed by two
 * 1x1 apps leaves the lower-right cell of the calendar row available to the next 1x1 app instead
 * of forcing it to the next visual line.  This is the topology that LazyVerticalGrid plus a
 * pseudo row height cannot represent.
 */
internal fun denseHomeGridPlan(
    items: List<HomeGridItem>,
    columns: Int,
): HomeGridPlan {
    val safeColumns = columns.coerceAtLeast(1)
    val occupancy = HomeGridOccupancy(safeColumns)
    val placements = mutableListOf<HomeGridPlacement>()

    items.forEach { item ->
        val width = item.columnSpan.coerceIn(1, safeColumns)
        val height = item.rowSpan.coerceAtLeast(1)
        var placed: HomeGridPlacement? = null
        var row = 0
        while (placed == null) {
            for (column in 0..(safeColumns - width)) {
                if (occupancy.canPlace(column, row, width, height)) {
                    occupancy.place(column, row, width, height)
                    placed = HomeGridPlacement(
                        id = item.id,
                        column = column,
                        row = row,
                        columnSpan = width,
                        rowSpan = height,
                    )
                    placements += placed
                    break
                }
            }
            row++
        }
    }

    return HomeGridPlan(
        columns = safeColumns,
        placements = placements,
        rows = placements.maxOfOrNull { it.bottomRow }?.coerceAtLeast(1) ?: 1,
    )
}

/**
 * Fixed-row, column-major placement used by the inner-landscape Start canvas.
 *
 * Unlike the narrow board, the wide board grows horizontally while keeping exactly [rows]
 * logical rows. Items are still dense and multi-cell, but the scan order is columns first so a
 * horizontal fling reveals stable vertical stacks instead of a second page or vertical board.
 */
internal data class HorizontalHomeGridPlan(
    val rows: Int,
    val placements: List<HomeGridPlacement>,
    val columns: Int,
) {
    fun placementOf(id: String): HomeGridPlacement? = placements.firstOrNull { it.id == id }

    fun placementAt(cell: HomeGridCell): HomeGridPlacement? =
        placements.firstOrNull { it.contains(cell) }
}

internal fun horizontalHomeGridPlan(
    items: List<HomeGridItem>,
    rows: Int = 6,
): HorizontalHomeGridPlan {
    val safeRows = rows.coerceAtLeast(1)
    val occupancy = HorizontalHomeGridOccupancy(safeRows)
    val placements = mutableListOf<HomeGridPlacement>()

    items.forEach { item ->
        val height = item.rowSpan.coerceIn(1, safeRows)
        val width = item.columnSpan.coerceAtLeast(1)
        var placed: HomeGridPlacement? = null
        var column = 0
        while (placed == null) {
            for (row in 0..(safeRows - height)) {
                if (occupancy.canPlace(column, row, width, height)) {
                    occupancy.place(column, row, width, height)
                    placed = HomeGridPlacement(
                        id = item.id,
                        column = column,
                        row = row,
                        columnSpan = width,
                        rowSpan = height,
                    )
                    placements += placed
                    break
                }
            }
            column++
        }
    }

    return HorizontalHomeGridPlan(
        rows = safeRows,
        placements = placements,
        columns = placements.maxOfOrNull { it.rightColumn }?.coerceAtLeast(1) ?: 1,
    )
}

/** Alias naming the layout by its visible Start-canvas role. */
internal fun startCanvasGridPlan(
    items: List<HomeGridItem>,
    rows: Int = 6,
): HorizontalHomeGridPlan = horizontalHomeGridPlan(items, rows)

internal fun reorderHorizontalHomeOrderForTargetCell(
    order: List<String>,
    draggedId: String,
    targetCell: HomeGridCell,
    itemSizes: Map<String, HomeGridItem>,
    rows: Int = 6,
): List<String> {
    val originalIndex = order.indexOf(draggedId)
    if (originalIndex < 0) return order
    val remaining = order.filterNot { it == draggedId }
    val candidates = (0..remaining.size).map { insertionIndex ->
        val candidateOrder = remaining.toMutableList().apply { add(insertionIndex, draggedId) }
        val plan = horizontalHomeGridPlan(
            candidateOrder.map { id -> itemSizes[id] ?: HomeGridItem(id, 1, 1) },
            rows,
        )
        val placement = plan.placementOf(draggedId)
        HorizontalGridCandidate(
            order = candidateOrder,
            exact = placement?.contains(targetCell) == true,
            distance = placement?.let { placementDistanceSquared(it, targetCell) }
                ?: Float.POSITIVE_INFINITY,
            insertionDistance = kotlin.math.abs(insertionIndex - originalIndex),
            insertionIndex = insertionIndex,
        )
    }
    return candidates.sortedWith(
        compareByDescending<HorizontalGridCandidate> { it.exact }
            .thenBy { it.distance }
            .thenBy { it.insertionDistance }
            .thenBy { it.insertionIndex },
    ).first().order
}

private data class HorizontalGridCandidate(
    val order: List<String>,
    val exact: Boolean,
    val distance: Float,
    val insertionDistance: Int,
    val insertionIndex: Int,
)

private class HorizontalHomeGridOccupancy(private val rows: Int) {
    private val columns = mutableListOf<BooleanArray>()

    fun canPlace(column: Int, row: Int, width: Int, height: Int): Boolean {
        if (column < 0 || row < 0 || width < 1 || height < 1 || row + height > rows) {
            return false
        }
        for (currentColumn in column until column + width) {
            val cells = columns.getOrNull(currentColumn) ?: continue
            for (currentRow in row until row + height) {
                if (cells[currentRow]) return false
            }
        }
        return true
    }

    fun place(column: Int, row: Int, width: Int, height: Int) {
        while (columns.size < column + width) columns += BooleanArray(rows)
        for (currentColumn in column until column + width) {
            for (currentRow in row until row + height) {
                columns[currentColumn][currentRow] = true
            }
        }
    }
}

/**
 * Converts a root-space pointer to the cell under the finger.
 *
 * Gaps intentionally belong to the following cell so a release close to a divider remains
 * forgiving.  The caller still owns the board bounds check; this function only clamps the
 * horizontal/vertical cell coordinate to the board's valid column range.
 */
internal fun homeGridCellAt(
    pointer: HomePointer,
    board: HomeItemBounds,
    cellWidthPx: Float,
    gapPx: Float,
    columns: Int,
): HomeGridCell? {
    val safeCell = cellWidthPx.takeIf { it.isFinite() && it > 0f } ?: return null
    val safeGap = gapPx.takeIf { it.isFinite() && it >= 0f } ?: return null
    val safeColumns = columns.coerceAtLeast(1)
    val localX = pointer.x - board.left
    val localY = pointer.y - board.top
    if (!localX.isFinite() || !localY.isFinite() || localX < 0f || localY < 0f) return null

    val stride = safeCell + safeGap
    val column = floor(localX / stride).toInt().coerceIn(0, safeColumns - 1)
    val row = floor(localY / stride).toInt().coerceAtLeast(0)
    return HomeGridCell(column = column, row = row)
}

/**
 * Returns the order whose dense placement puts [draggedId] on [targetCell].
 *
 * All possible insertion indices are evaluated against the same occupancy algorithm.  This is
 * intentionally different from nearest-rectangle insertion: a visual hole has no rectangle to
 * be nearest to, while a cell has an exact, deterministic meaning.  If no candidate can cover the
 * requested cell (for example, a large footprint cannot fit there), the closest candidate is
 * selected and ties retain the item's original insertion distance.
 */
internal fun reorderHomeOrderForTargetCell(
    order: List<String>,
    draggedId: String,
    targetCell: HomeGridCell,
    itemSizes: Map<String, HomeGridItem>,
    columns: Int,
): List<String> {
    val originalIndex = order.indexOf(draggedId)
    if (originalIndex < 0) return order

    val remaining = order.filterNot { it == draggedId }
    val candidates = (0..remaining.size).map { insertionIndex ->
        val candidateOrder = remaining.toMutableList().apply { add(insertionIndex, draggedId) }
        val candidateItems = candidateOrder.map { id ->
            itemSizes[id] ?: HomeGridItem(id, 1, 1)
        }
        val plan = denseHomeGridPlan(candidateItems, columns)
        val placement = plan.placementOf(draggedId)
        val exact = placement?.contains(targetCell) == true
        val distance = placement?.let { placementDistanceSquared(it, targetCell) }
            ?: Float.POSITIVE_INFINITY
        val insertionDistance = kotlin.math.abs(insertionIndex - originalIndex)
        HomeGridCandidate(
            order = candidateOrder,
            exact = exact,
            distance = distance,
            insertionDistance = insertionDistance,
            insertionIndex = insertionIndex,
        )
    }

    return candidates
        .sortedWith(
            compareByDescending<HomeGridCandidate> { it.exact }
                .thenBy { it.distance }
                .thenBy { it.insertionDistance }
                .thenBy { it.insertionIndex },
        )
        .first()
        .order
}

/**
 * Returns the columns that need to be composed for a horizontally scrolling board.
 *
 * The board itself remains one finite layout with its complete dimensions; this range only
 * controls which children are measured for the current viewport.  The arithmetic is deliberately
 * integer/column based so a pixel scroll does not invalidate the composition until a column
 * boundary is crossed.  Two columns of overscan keep a fast fling from exposing an empty edge.
 */
internal fun homeGridColumnWindow(
    scrollOffsetPx: Int,
    viewportWidthPx: Int,
    cellStridePx: Int,
    totalColumns: Int,
    overscanColumns: Int = 2,
): IntRange {
    val safeTotalColumns = totalColumns.coerceAtLeast(1)
    val lastColumn = safeTotalColumns - 1
    val safeStride = cellStridePx.coerceAtLeast(1)
    val safeScroll = scrollOffsetPx.coerceAtLeast(0)
    val safeViewport = viewportWidthPx.coerceAtLeast(1)
    val safeOverscan = overscanColumns.coerceAtLeast(0)
    val firstVisibleColumn = safeScroll / safeStride
    // Use the viewport's fixed column count instead of the exact trailing pixel. Otherwise the
    // right edge would enter the next column one pixel before the leading edge crosses a boundary,
    // defeating the derived-state coalescing this window is meant to provide.
    val visibleColumnCount = (safeViewport + safeStride - 1) / safeStride
    val lastVisibleColumn = firstVisibleColumn + visibleColumnCount - 1
    val start = (firstVisibleColumn - safeOverscan).coerceIn(0, lastColumn)
    val end = (lastVisibleColumn + safeOverscan).coerceIn(start, lastColumn)
    return start..end
}

/**
 * Filters children for a horizontal viewport while retaining the active dragged tile.
 *
 * A multi-cell tile is retained when any of its columns intersects the window.  The dragged item
 * is appended when it has moved outside the overscan region so its graphics layer and pointer
 * interaction stay alive during edge auto-scroll.  Passing null keeps the original non-windowed
 * behavior used by the narrow pager boards.
 */
internal fun homeGridPlacementsInColumnWindow(
    plan: HomeGridPlan,
    columnWindow: IntRange?,
    retainedItemId: String? = null,
): List<HomeGridPlacement> {
    if (columnWindow == null) return plan.placements

    val visible = if (columnWindow.isEmpty()) {
        emptyList()
    } else {
        val firstColumn = columnWindow.first.coerceAtLeast(0)
        val lastColumn = columnWindow.last.coerceAtMost(plan.columns - 1)
        if (firstColumn > lastColumn) {
            emptyList()
        } else {
            plan.placements.filter { placement ->
                placement.column <= lastColumn && placement.rightColumn > firstColumn
            }
        }
    }
    val retained = retainedItemId?.let(plan::placementOf)
    return if (retained != null && visible.none { it.id == retained.id }) {
        visible + retained
    } else {
        visible
    }
}

/**
 * Places already-planned children at their exact cell rectangles.
 *
 * [plan] always describes the complete board and therefore controls the measured board size.
 * Horizontal callers may pass [visiblePlacements] to window child composition/measurement near a
 * LazyRow viewport; omitted placements keep their logical coordinates and do not change that board
 * size. Placement coordinates are logical cell coordinates; any visual reflow animation belongs
 * inside the child so measured outer bounds remain stable and cannot feed a layout feedback loop.
 */
@Composable
internal fun HomeGridLayout(
    plan: HomeGridPlan,
    cellWidthPx: Int,
    gapPx: Int,
    modifier: Modifier = Modifier,
    visiblePlacements: List<HomeGridPlacement> = plan.placements,
    content: @Composable (HomeGridPlacement) -> Unit,
) {
    val measurePolicy = remember(plan, cellWidthPx, gapPx, visiblePlacements) {
        MeasurePolicy { measurables, constraints ->
            val safeCell = cellWidthPx.coerceAtLeast(1)
            val safeGap = gapPx.coerceAtLeast(0)
            val boardWidth = homeGridBoardWidthPx(cellWidthPx, gapPx, plan.columns)
            val boardHeight = homeGridBoardHeightPx(cellWidthPx, gapPx, plan.rows)
            val placeables = measurables.mapIndexed { index, measurable ->
                val placement = visiblePlacements[index]
                val width = homeGridSpanSizePx(cellWidthPx, gapPx, placement.columnSpan)
                val height = homeGridSpanSizePx(cellWidthPx, gapPx, placement.rowSpan)
                measurable.measure(Constraints.fixed(width, height))
            }
            val layoutWidth = if (constraints.maxWidth != Constraints.Infinity) {
                constraints.maxWidth
            } else {
                boardWidth
            }.coerceIn(constraints.minWidth, constraints.maxWidth)
            val layoutHeight = boardHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(layoutWidth, layoutHeight) {
                placeables.forEachIndexed { index, placeable ->
                    val placement = visiblePlacements[index]
                    placeable.placeRelative(
                        x = placement.column * (safeCell + safeGap),
                        y = placement.row * (safeCell + safeGap),
                    )
                }
            }
        }
    }

    Layout(
        content = {
            visiblePlacements.forEach { placement ->
                key(placement.id) {
                    content(placement)
                }
            }
        },
        modifier = modifier,
        measurePolicy = measurePolicy,
    )
}

private data class HomeGridCandidate(
    val order: List<String>,
    val exact: Boolean,
    val distance: Float,
    val insertionDistance: Int,
    val insertionIndex: Int,
)

private fun placementDistanceSquared(
    placement: HomeGridPlacement,
    target: HomeGridCell,
): Float {
    val dx = when {
        target.column < placement.column -> (placement.column - target.column).toFloat()
        target.column >= placement.rightColumn -> (target.column - placement.rightColumn + 1).toFloat()
        else -> 0f
    }
    val dy = when {
        target.row < placement.row -> (placement.row - target.row).toFloat()
        target.row >= placement.bottomRow -> (target.row - placement.bottomRow + 1).toFloat()
        else -> 0f
    }
    return dx * dx + dy * dy
}

private class HomeGridOccupancy(private val columns: Int) {
    private val rows = mutableListOf<BooleanArray>()

    fun canPlace(column: Int, row: Int, width: Int, height: Int): Boolean {
        if (column < 0 || row < 0 || width < 1 || height < 1 || column + width > columns) {
            return false
        }
        for (currentRow in row until row + height) {
            val cells = rows.getOrNull(currentRow) ?: continue
            for (currentColumn in column until column + width) {
                if (cells[currentColumn]) return false
            }
        }
        return true
    }

    fun place(column: Int, row: Int, width: Int, height: Int) {
        while (rows.size < row + height) rows += BooleanArray(columns)
        for (currentRow in row until row + height) {
            for (currentColumn in column until column + width) {
                rows[currentRow][currentColumn] = true
            }
        }
    }
}
