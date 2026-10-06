package com.fiilda.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.sqrt

/** A board-space point used by the reorder calculation. */
internal data class HomePointer(
    val x: Float,
    val y: Float,
)

/** The measured, board-space layout rectangle of one home item. */
internal data class HomeItemBounds(
    val id: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** Result returned when a pointer gesture ends. */
internal data class HomeDragResult(
    val id: String,
    val didMove: Boolean,
)

/** Moves one ordered item by a relative slot for accessibility reorder actions. */
internal fun moveHomeOrderBy(
    order: List<String>,
    itemId: String,
    delta: Int,
): List<String> {
    val from = order.indexOf(itemId)
    if (from < 0 || delta == 0) return order
    val to = (from + delta).coerceIn(0, order.lastIndex)
    if (to == from) return order
    return order.toMutableList().apply {
        add(to, removeAt(from))
    }
}

/**
 * Returns the horizontal edge-scroll direction for a pointer captured by a Start canvas drag.
 * The pointer must remain inside the viewport vertically; an item can continue reporting pointer
 * positions after it has left its own board, but those positions must not start a list scroll
 * outside the canvas. A zero result means that the current auto-scroll loop should stop.
 */
internal fun horizontalEdgeAutoScrollDirection(
    pointer: HomePointer?,
    viewport: HomeItemBounds?,
    edgePx: Float,
): Int {
    if (pointer == null || viewport == null || edgePx <= 0f) return 0
    if (pointer.y !in viewport.top..viewport.bottom) return 0
    return when {
        pointer.x <= viewport.left + edgePx -> -1
        pointer.x >= viewport.right - edgePx -> 1
        else -> 0
    }
}

/**
 * Main-thread ownership for one edge-scroll effect.
 *
 * The direction is deliberately plain state. A pointer callback updates it before publishing the
 * Compose effect key, so a cancelled effect can only stop the generation it captured and cannot
 * overwrite a newer reversed direction. Position commits use a separate plain gate so they can
 * be deduplicated by actual position rather than by edge-effect generation.
 */
internal class HomeEdgeScrollState {
    var direction: Int = 0
        private set
    var generation: Long = 0L
        private set
    private var disposed = false

    fun updateDirection(newDirection: Int): Boolean {
        if (disposed) return false
        if (direction == newDirection) return false
        direction = newDirection
        generation++
        return true
    }

    fun owns(capturedGeneration: Long, capturedDirection: Int): Boolean =
        !disposed && generation == capturedGeneration && direction == capturedDirection

    fun stopIfOwned(capturedGeneration: Long, capturedDirection: Int): Boolean {
        if (!owns(capturedGeneration, capturedDirection)) return false
        direction = 0
        generation++
        return true
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        direction = 0
        generation++
    }
}

/**
 * Plain main-thread gate for StartCanvas position callbacks.
 *
 * Edge-loop completion, LazyList idle observation, and posture disposal can all see the same
 * final position. Structural equality suppresses duplicate callbacks, while disposal prevents a
 * delayed finally block from an old canvas instance from publishing after a new instance enters.
 */
internal class HomePositionCommitGate {
    private var lastCommittedPosition: StartCanvasScrollPosition? = null
    private var disposed = false

    fun commitIfChanged(position: StartCanvasScrollPosition): Boolean {
        if (disposed || lastCommittedPosition == position) return false
        lastCommittedPosition = position
        return true
    }

    fun disposeAndCommit(position: StartCanvasScrollPosition): Boolean {
        if (disposed) return false
        disposed = true
        return if (lastCommittedPosition == position) {
            false
        } else {
            lastCommittedPosition = position
            true
        }
    }
}

/**
 * Identity of one accepted home drag. The token makes a stale callback harmless after a posture
 * change or a neighboring pane wins a later gesture.
 */
internal data class HomeDragSession(
    val token: Long,
    val homePage: Int,
    val itemId: String,
)

/**
 * Coordinates the transient drag owner shared by every visible home board.
 *
 * HomeDragState remains local to a board because it owns measured bounds and pointer translation,
 * while this coordinator owns the cross-pane invariant: at most one page/item may be in an
 * accepted drag session. Releasing a session is identity-checked so a rejected or stale callback
 * cannot cancel the active session in another pane.
 */
internal class HomeDragCoordinator {
    private var nextToken = 0L
    private var activeSession: HomeDragSession? = null
    private var generation = 0L

    fun acquire(
        homePage: Int,
        itemId: String,
        expectedGeneration: Long = generation,
    ): HomeDragSession? {
        if (expectedGeneration != generation || activeSession != null) return null
        val session = HomeDragSession(
            token = ++nextToken,
            homePage = homePage,
            itemId = itemId,
        )
        activeSession = session
        return session
    }

    fun isOwner(session: HomeDragSession?): Boolean =
        session != null && activeSession == session

    fun release(session: HomeDragSession?): Boolean {
        if (!isOwner(session)) return false
        activeSession = null
        return true
    }

    /** Cancels the active owner during a posture/presentation reset. */
    fun reset() {
        activeSession = null
        generation++
    }

    /** Captured at pointer-down so a pre-reset pending long press cannot acquire afterward. */
    val currentGeneration: Long
        get() = generation

    internal val owner: HomeDragSession?
        get() = activeSession
}

/**
 * Memoizes the geometry candidate for one drag target.
 *
 * Pointer coordinates can arrive many times while they remain inside one logical cell. The
 * pointer still flows through the hysteresis state on every event, but the comparatively expensive
 * dense-plan insertion is repeated only when the target cell, working order, footprint map, or
 * board shape changes. Callers explicitly invalidate this cache at drag boundaries.
 */
internal class HomeReorderCandidateCache {
    private var hasValue = false
    private var lastDraggedId: String? = null
    private var lastTargetCell: HomeGridCell? = null
    private var lastWorkingOrder: List<String>? = null
    private var lastItemSizes: Map<String, HomeGridItem>? = null
    private var lastColumns = 0
    private var lastRows = 0
    private var lastHorizontal = false
    private var lastCandidate: List<String>? = null

    fun invalidate() {
        hasValue = false
        lastDraggedId = null
        lastTargetCell = null
        lastWorkingOrder = null
        lastItemSizes = null
        lastCandidate = null
    }

    fun getOrCompute(
        draggedId: String,
        targetCell: HomeGridCell,
        workingOrder: List<String>,
        itemSizes: Map<String, HomeGridItem>,
        columns: Int,
        rows: Int,
        horizontal: Boolean,
        compute: () -> List<String>,
    ): List<String> {
        // Lists and maps are stable holders during one drag frame. Identity checks keep this hot
        // path allocation-free and still force recomputation when preview/external state replaces
        // either the working order or item footprint map.
        if (
            hasValue &&
            lastDraggedId == draggedId &&
            lastTargetCell == targetCell &&
            lastWorkingOrder === workingOrder &&
            lastItemSizes === itemSizes &&
            lastColumns == columns &&
            lastRows == rows &&
            lastHorizontal == horizontal
        ) {
            return lastCandidate ?: workingOrder
        }
        val candidate = compute()
        hasValue = true
        lastDraggedId = draggedId
        lastTargetCell = targetCell
        lastWorkingOrder = workingOrder
        lastItemSizes = itemSizes
        lastColumns = columns
        lastRows = rows
        lastHorizontal = horizontal
        lastCandidate = candidate
        return candidate
    }
}

/**
 * Spatial gate for preview transitions.
 *
 * Placement animation can temporarily move measured rectangles across an insertion boundary.
 * When that produces the opposite candidate at the same (or nearly the same) finger position,
 * keep the already accepted order. A deliberate move farther than the touch-slop radius can
 * still accept the next candidate, including a reversal back to the previous slot.
 */
internal class HomeReorderHysteresis {
    private var acceptedPointer: HomePointer? = null

    fun reset() {
        acceptedPointer = null
    }

    fun stabilize(
        currentOrder: List<String>,
        candidateOrder: List<String>,
        pointer: HomePointer,
        hysteresisRadius: Float,
    ): List<String> {
        if (candidateOrder == currentOrder) return currentOrder

        val previousPointer = acceptedPointer
        val radius = hysteresisRadius.coerceAtLeast(0f)
        if (previousPointer != null && distanceSquared(previousPointer, pointer) <= radius * radius) {
            return currentOrder
        }

        acceptedPointer = pointer
        return candidateOrder
    }

    private fun distanceSquared(a: HomePointer, b: HomePointer): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return dx * dx + dy * dy
    }
}

/**
 * Transient order used while a drag is active. The persisted order remains owned by the screen;
 * this holder lets the pointer loop synchronously retain the latest working order before parent
 * recomposition catches up with the preview callback.
 */
internal class HomePreviewState {
    var order: List<String> by mutableStateOf(emptyList())
        private set
    private var baseOrder: List<String> = emptyList()
    private val reorderHysteresis = HomeReorderHysteresis()
    private var originOrder: List<String>? = null

    /** The stable order from which this drag started, used for target-interior direction. */
    val dragOriginOrder: List<String>?
        get() = originOrder

    /** Starts a new drag session without relying on an external-order recomposition. */
    fun beginDrag() {
        reorderHysteresis.reset()
        originOrder = order.toList()
    }

    /** Ends the current drag session and prevents its accepted pointer from leaking forward. */
    fun endDrag() {
        reorderHysteresis.reset()
        originOrder = null
    }

    fun update(order: List<String>) {
        this.order = order
        reorderHysteresis.reset()
    }

    /**
     * Applies a geometry-derived candidate while suppressing reversals caused by animation jitter.
     */
    fun preview(
        candidateOrder: List<String>,
        pointer: HomePointer,
        hysteresisRadius: Float,
    ): List<String> {
        val stabilized = reorderHysteresis.stabilize(
            currentOrder = order,
            candidateOrder = candidateOrder,
            pointer = pointer,
            hysteresisRadius = hysteresisRadius,
        )
        if (stabilized != order) {
            order = stabilized
        }
        return stabilized
    }

    /** Sync external changes, but never overwrite an active drag's same-ID working order. */
    fun syncExternalOrder(externalOrder: List<String>, dragging: Boolean) {
        if (!dragging) {
            baseOrder = externalOrder
            if (order != externalOrder) {
                order = externalOrder
            }
            reorderHysteresis.reset()
            originOrder = null
        } else if (order.toSet() != externalOrder.toSet() || order.size != externalOrder.size) {
            if (order != externalOrder) {
                order = externalOrder
            }
        }
    }

    fun resetToBaseOrder() {
        endDrag()
        order = baseOrder
    }
}

/**
 * Computes the insertion slot for [draggedId] from actual item rectangles.
 *
 * The returned index is in the list with [draggedId] removed, and is therefore in the inclusive
 * range 0..(order.size - 1). Invalid or missing data deliberately leaves the order untouched.
 * Item rectangles are not synthesized from grid columns: the caller supplies the rectangles
 * measured by Compose after each preview placement.
 */
internal fun calculateHomeInsertionIndex(
    order: List<String>,
    draggedId: String,
    pointer: HomePointer,
    bounds: List<HomeItemBounds>,
    directionOrder: List<String>? = null,
): Int? {
    if (draggedId !in order) return null

    val remaining = order.filterNot { it == draggedId }
    if (remaining.isEmpty()) return 0

    val boundsById = bounds
        .asSequence()
        .filter { it.id in remaining && it.right >= it.left && it.bottom >= it.top }
        .associateBy { it.id }
    // A partial measurement could otherwise make the item jump to a slot based only on the
    // visible subset. Hold the preview until every remaining item has a current rectangle.
    if (boundsById.size != remaining.size) return null

    // The dragged tile itself is not a candidate target. While the finger is still over its
    // measured layout rectangle, keep its current slot; otherwise the nearest neighbour can make
    // a stationary drag jump around a tall or wide tile's edge.
    bounds.firstOrNull { it.id == draggedId }?.let { draggedBounds ->
        if (pointer.x in draggedBounds.left..draggedBounds.right &&
            pointer.y in draggedBounds.top..draggedBounds.bottom
        ) {
            return order.indexOf(draggedId).coerceIn(0, remaining.size)
        }
    }

    // Reading order comes from the current order, while geometry determines which item the
    // pointer is closest to. This stays correct for wide/tall tiles and for partially filled rows.
    val nearest = remaining.asSequence()
        .mapNotNull { id -> boundsById[id]?.let { id to distanceSquaredToRect(pointer, it) } }
        .minWithOrNull(compareBy<Pair<String, Float>> { it.second }.thenBy { remaining.indexOf(it.first) })
        ?.first
        ?: return null
    val nearestBounds = boundsById[nearest] ?: return null
    val nearestIndex = remaining.indexOf(nearest)
    if (nearestIndex < 0) return null

    // Keep direction tied to the order at drag start. The working order changes immediately after
    // each accepted preview, so using it here would make the same target flip sides on the next
    // pointer event even when the finger is still travelling in one direction.
    val stableDirectionOrder = directionOrder ?: order
    val draggedIndex = stableDirectionOrder.indexOf(draggedId)
    val targetIndex = stableDirectionOrder.indexOf(nearest)
    val before = pointerIsBeforeTarget(
        pointer = pointer,
        bounds = nearestBounds,
        draggedIndex = draggedIndex,
        targetIndex = targetIndex,
    )
    return (nearestIndex + if (before) 0 else 1).coerceIn(0, remaining.size)
}

/** Returns a new order with [draggedId] inserted into the geometry-derived slot. */
internal fun reorderHomeOrder(
    order: List<String>,
    draggedId: String,
    pointer: HomePointer,
    bounds: List<HomeItemBounds>,
    directionOrder: List<String>? = null,
): List<String> {
    val insertionIndex = calculateHomeInsertionIndex(
        order = order,
        draggedId = draggedId,
        pointer = pointer,
        bounds = bounds,
        directionOrder = directionOrder,
    )
        ?: return order
    val remaining = order.filterNot { it == draggedId }
    if (remaining.isEmpty()) return order
    return remaining.toMutableList().apply { add(insertionIndex, draggedId) }
}

private fun distanceSquaredToRect(pointer: HomePointer, bounds: HomeItemBounds): Float {
    val dx = when {
        pointer.x < bounds.left -> bounds.left - pointer.x
        pointer.x > bounds.right -> pointer.x - bounds.right
        else -> 0f
    }
    val dy = when {
        pointer.y < bounds.top -> bounds.top - pointer.y
        pointer.y > bounds.bottom -> pointer.y - bounds.bottom
        else -> 0f
    }
    return dx * dx + dy * dy
}

private fun pointerIsBeforeTarget(
    pointer: HomePointer,
    bounds: HomeItemBounds,
    draggedIndex: Int,
    targetIndex: Int,
): Boolean {
    // A target tile is an intentionally generous hit area. When the pointer is inside it, use
    // the direction of travel implied by the current order: an item coming from behind belongs
    // before the target, while an item coming from ahead belongs after it. This avoids making the
    // user's intended insertion depend on which half of a target's center they happened to hit.
    if (pointer.x in bounds.left..bounds.right && pointer.y in bounds.top..bounds.bottom &&
        draggedIndex >= 0 && targetIndex >= 0
    ) {
        return draggedIndex > targetIndex
    }

    // Outside a tile (including horizontal and row gaps), retain the measured midpoint rule so
    // the finger can still insert precisely between items.
    return pointerIsBeforeItemMidpoint(pointer, bounds)
}

private fun pointerIsBeforeItemMidpoint(pointer: HomePointer, bounds: HomeItemBounds): Boolean {
    // A pointer outside a measured tile uses the nearest rectangle's actual visual midpoint. For
    // gaps between rows, the major-axis position gives stable before/after edges.
    val verticalTolerance = (bounds.height * 0.12f).coerceAtLeast(1f)
    return when {
        pointer.y < bounds.top - verticalTolerance -> true
        pointer.y > bounds.bottom + verticalTolerance -> false
        abs(pointer.y - bounds.centerY) > verticalTolerance -> pointer.y < bounds.centerY
        else -> pointer.x < bounds.centerX
    }
}

/**
 * State shared by each measured home item and the board. It intentionally contains no persisted
 * order. The owner decides whether a finished gesture is committed or cancelled.
 */
internal class HomeDragState {
    // Bounds are read synchronously by the pointer loop. Keeping this backing map plain avoids a
    // snapshot write for every visible item whenever a scrolling board moves by one pixel. Only
    // the active item's bounds are mirrored into Compose state below because its graphics layer
    // must be redrawn when the board scrolls underneath the finger.
    private val itemBounds: MutableMap<String, HomeItemBounds> = mutableMapOf()
    private val draggedBounds = mutableStateOf<HomeItemBounds?>(null)
    private var initialPointer: HomePointer? = null
    private var grabOffsetX: Float = 0f
    private var grabOffsetY: Float = 0f
    private var touchSlop: Float = 0f

    var draggedId: String? by mutableStateOf(null)
        private set
    var pointer: HomePointer? by mutableStateOf(null)
        private set
    var didMove: Boolean by mutableStateOf(false)
        private set

    /** Touch-slop-sized spatial hysteresis for preview transitions while this drag is active. */
    val reorderHysteresisRadius: Float
        get() = touchSlop

    val isDragging: Boolean
        get() = draggedId != null

    fun updateBounds(bounds: HomeItemBounds) {
        if (itemBounds[bounds.id] == bounds) return
        itemBounds[bounds.id] = bounds
        if (draggedId == bounds.id) {
            draggedBounds.value = bounds
        }
    }

    fun boundsFor(id: String): HomeItemBounds? = itemBounds[id]

    fun allBounds(): List<HomeItemBounds> = itemBounds.values.toList()

    fun begin(id: String, pointer: HomePointer, slop: Float): Boolean {
        if (draggedId != null) return false
        val bounds = itemBounds[id] ?: return false
        draggedId = id
        initialPointer = pointer
        this.pointer = pointer
        draggedBounds.value = bounds
        grabOffsetX = (pointer.x - bounds.left).coerceIn(0f, bounds.width)
        grabOffsetY = (pointer.y - bounds.top).coerceIn(0f, bounds.height)
        touchSlop = slop.coerceAtLeast(0f)
        didMove = false
        return true
    }

    /** Updates the finger position and returns whether the drag crossed touch slop. */
    fun updatePointer(pointer: HomePointer): Boolean {
        if (!isDragging) return false
        this.pointer = pointer
        val start = initialPointer
        if (!didMove && start != null) {
            val distance = distance(start, pointer)
            if (distance > touchSlop) {
                didMove = true
            }
        }
        return didMove
    }

    /** Translation from the current layout bounds to keep the original grab point under the finger. */
    fun translationFor(id: String): HomePointer? {
        if (draggedId != id) return null
        val currentPointer = pointer ?: return null
        val currentBounds = draggedBounds.value ?: itemBounds[id] ?: return null
        return HomePointer(
            x = currentPointer.x - currentBounds.left - grabOffsetX,
            y = currentPointer.y - currentBounds.top - grabOffsetY,
        )
    }

    fun finish(id: String): HomeDragResult? {
        if (draggedId != id) return null
        val result = HomeDragResult(id = id, didMove = didMove)
        clear()
        return result
    }

    fun cancel() {
        clear()
    }

    private fun clear() {
        draggedId = null
        pointer = null
        initialPointer = null
        draggedBounds.value = null
        grabOffsetX = 0f
        grabOffsetY = 0f
        touchSlop = 0f
        didMove = false
    }

    private fun distance(a: HomePointer, b: HomePointer): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }
}
