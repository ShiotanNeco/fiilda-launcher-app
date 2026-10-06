package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeReorderTest {
    @Test
    fun accessibilityMoveChangesOnlyTheRequestedOrderSlot() {
        val order = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), moveHomeOrderBy(order, "a", 1))
        assertEquals(listOf("a", "c", "b"), moveHomeOrderBy(order, "b", 1))
        assertEquals(listOf("a", "b", "c"), moveHomeOrderBy(order, "a", -1))
        assertEquals(order, moveHomeOrderBy(order, "missing", 1))
    }

    @Test
    fun horizontalEdgeAutoScrollOnlyActivatesInsideTheViewportEdges() {
        val viewport = HomeItemBounds("viewport", 100f, 20f, 500f, 420f)
        assertEquals(-1, horizontalEdgeAutoScrollDirection(HomePointer(108f, 200f), viewport, 48f))
        assertEquals(1, horizontalEdgeAutoScrollDirection(HomePointer(492f, 200f), viewport, 48f))
        assertEquals(0, horizontalEdgeAutoScrollDirection(HomePointer(300f, 200f), viewport, 48f))
        assertEquals(0, horizontalEdgeAutoScrollDirection(HomePointer(108f, 10f), viewport, 48f))
        assertEquals(0, horizontalEdgeAutoScrollDirection(null, viewport, 48f))
    }

    @Test
    fun edgeScrollGenerationPreventsOldDirectionFromStoppingReversedOwner() {
        val state = HomeEdgeScrollState()
        assertTrue(state.updateDirection(1))
        val oldGeneration = state.generation

        assertTrue(state.updateDirection(-1))
        assertFalse(state.stopIfOwned(oldGeneration, 1))
        assertEquals(-1, state.direction)

        val currentGeneration = state.generation
        assertTrue(state.stopIfOwned(currentGeneration, -1))
        assertEquals(0, state.direction)
        state.dispose()
        assertFalse(state.updateDirection(1))
    }

    @Test
    fun positionCommitGateSuppressesDuplicatesAndLateOldInstanceCommits() {
        val gate = HomePositionCommitGate()
        val first = StartCanvasScrollPosition(itemIndex = 2, itemOffsetPx = 10)
        val newer = StartCanvasScrollPosition(itemIndex = 3, itemOffsetPx = 4)

        assertTrue(gate.commitIfChanged(first))
        assertFalse(gate.commitIfChanged(first))
        assertTrue(gate.commitIfChanged(newer))
        assertFalse(gate.disposeAndCommit(newer))
        assertFalse(gate.commitIfChanged(first))
    }

    @Test
    fun reorderCandidateCacheSkipsSameCellButInvalidatesForOrderCellAndExplicitReset() {
        val cache = HomeReorderCandidateCache()
        val itemSizes = mapOf(
            "a" to HomeGridItem("a", 1, 1),
            "b" to HomeGridItem("b", 1, 1),
        )
        val baseOrder = listOf("a", "b")
        var computations = 0

        fun candidate(
            targetCell: HomeGridCell,
            order: List<String> = baseOrder,
        ): List<String> = cache.getOrCompute(
            draggedId = "a",
            targetCell = targetCell,
            workingOrder = order,
            itemSizes = itemSizes,
            columns = 6,
            rows = 6,
            horizontal = true,
        ) {
            computations++
            order
        }

        assertEquals(listOf("a", "b"), candidate(HomeGridCell(0, 0)))
        assertEquals(listOf("a", "b"), candidate(HomeGridCell(0, 0)))
        assertEquals(1, computations)

        candidate(HomeGridCell(1, 0))
        assertEquals(2, computations)
        candidate(HomeGridCell(1, 0), order = listOf("b", "a"))
        assertEquals(3, computations)

        cache.invalidate()
        candidate(HomeGridCell(1, 0))
        assertEquals(4, computations)
    }

    private val bounds = listOf(
        HomeItemBounds("a", left = 0f, top = 0f, right = 100f, bottom = 100f),
        HomeItemBounds("b", left = 110f, top = 0f, right = 210f, bottom = 100f),
        HomeItemBounds("c", left = 220f, top = 0f, right = 320f, bottom = 100f),
    )

    @Test
    fun consumedReleaseCancelsInsteadOfOpeningApp() {
        assertEquals(
            HomePressOutcome.CANCEL,
            classifyHomePressEvent(
                pointerChangedToUp = true,
                pointerChangeConsumed = true,
                movedBeyondTouchSlop = false,
            ),
        )
    }

    @Test
    fun consumedPressBeforeReleaseStillAllowsLongPress() {
        assertNull(
            classifyHomePressEvent(
                pointerChangedToUp = false,
                pointerChangeConsumed = true,
                movedBeyondTouchSlop = false,
            ),
        )
    }

    @Test
    fun releaseAfterTouchSlopCancelsInsteadOfOpeningApp() {
        assertEquals(
            HomePressOutcome.CANCEL,
            classifyHomePressEvent(
                pointerChangedToUp = true,
                pointerChangeConsumed = false,
                movedBeyondTouchSlop = true,
            ),
        )
    }

    @Test
    fun unconsumedStationaryReleaseRemainsTap() {
        assertEquals(
            HomePressOutcome.TAP,
            classifyHomePressEvent(
                pointerChangedToUp = true,
                pointerChangeConsumed = false,
                movedBeyondTouchSlop = false,
            ),
        )
    }

    @Test
    fun pointerBeforeFirstItemMovesToFront() {
        assertEquals(
            listOf("c", "a", "b"),
            reorderHomeOrder(listOf("a", "b", "c"), "c", HomePointer(8f, 50f), bounds),
        )
    }

    @Test
    fun pointerAfterLastItemMovesToEnd() {
        assertEquals(
            listOf("b", "c", "a"),
            reorderHomeOrder(listOf("a", "b", "c"), "a", HomePointer(318f, 50f), bounds),
        )
    }

    @Test
    fun forwardAndBackwardMovesUseMeasuredGaps() {
        assertEquals(
            listOf("b", "a", "c"),
            reorderHomeOrder(listOf("a", "b", "c"), "a", HomePointer(180f, 50f), bounds),
        )
        assertEquals(
            listOf("c", "a", "b"),
            reorderHomeOrder(listOf("a", "b", "c"), "c", HomePointer(20f, 50f), bounds),
        )
    }

    @Test
    fun targetInteriorUsesDragDirectionInsteadOfCenterSplit() {
        // Forward: dragging from before b onto its center or its left interior inserts after b.
        assertEquals(
            listOf("b", "a", "c"),
            reorderHomeOrder(listOf("a", "b", "c"), "a", HomePointer(160f, 50f), bounds),
        )
        assertEquals(
            listOf("b", "a", "c"),
            reorderHomeOrder(listOf("a", "b", "c"), "a", HomePointer(120f, 50f), bounds),
        )

        // Backward: dragging from after a onto its center or its right interior inserts before a.
        assertEquals(
            listOf("c", "a", "b"),
            reorderHomeOrder(listOf("a", "b", "c"), "c", HomePointer(50f, 50f), bounds),
        )
        assertEquals(
            listOf("c", "a", "b"),
            reorderHomeOrder(listOf("a", "b", "c"), "c", HomePointer(80f, 50f), bounds),
        )
    }

    @Test
    fun targetInteriorDirectionStaysFixedToDragOriginAfterPreviewUpdates() {
        val base = listOf("a", "b", "c")
        val state = HomePreviewState()
        state.syncExternalOrder(base, dragging = false)
        state.beginDrag()
        assertEquals(base, state.dragOriginOrder)

        val firstForward = reorderHomeOrder(
            order = base,
            draggedId = "a",
            pointer = HomePointer(120f, 50f),
            bounds = bounds,
            directionOrder = state.dragOriginOrder,
        )
        assertEquals(listOf("b", "a", "c"), firstForward)
        state.update(firstForward)

        // This movement is well beyond touch-slop but remains inside b. A mutable working-order
        // direction would now reverse the item; the drag-origin direction must keep it after b.
        assertEquals(
            listOf("a", "b", "c"),
            reorderHomeOrder(firstForward, "a", HomePointer(180f, 50f), bounds),
        )
        assertEquals(
            listOf("b", "a", "c"),
            reorderHomeOrder(
                order = firstForward,
                draggedId = "a",
                pointer = HomePointer(180f, 50f),
                bounds = bounds,
                directionOrder = state.dragOriginOrder,
            ),
        )

        val firstBackward = reorderHomeOrder(
            order = base,
            draggedId = "c",
            pointer = HomePointer(80f, 50f),
            bounds = bounds,
            directionOrder = state.dragOriginOrder,
        )
        assertEquals(listOf("c", "a", "b"), firstBackward)
        state.update(firstBackward)
        assertEquals(
            listOf("c", "a", "b"),
            reorderHomeOrder(
                order = firstBackward,
                draggedId = "c",
                pointer = HomePointer(20f, 50f),
                bounds = bounds,
                directionOrder = state.dragOriginOrder,
            ),
        )

        state.endDrag()
        assertNull(state.dragOriginOrder)
    }

    @Test
    fun measuredGapsStillAllowInsertionBetweenTargets() {
        assertEquals(
            listOf("b", "a", "c"),
            reorderHomeOrder(listOf("a", "b", "c"), "a", HomePointer(215f, 50f), bounds),
        )
        assertEquals(
            listOf("a", "c", "b"),
            reorderHomeOrder(listOf("a", "b", "c"), "c", HomePointer(105f, 50f), bounds),
        )
    }

    @Test
    fun pointerAtCurrentSlotDoesNotChangeOrder() {
        assertEquals(
            listOf("a", "b", "c"),
            reorderHomeOrder(listOf("a", "b", "c"), "b", HomePointer(150f, 50f), bounds),
        )
    }

    @Test
    fun unknownIdOrMissingBoundsLeavesOrderUntouched() {
        val order = listOf("a", "b", "c")
        assertEquals(order, reorderHomeOrder(order, "missing", HomePointer(150f, 50f), bounds))
        assertEquals(order, reorderHomeOrder(order, "a", HomePointer(150f, 50f), emptyList()))
        assertEquals(
            order,
            reorderHomeOrder(order, "a", HomePointer(150f, 50f), bounds.take(1)),
        )
        assertEquals(
            null,
            calculateHomeInsertionIndex(order, "a", HomePointer(150f, 50f), bounds.take(1)),
        )
    }

    @Test
    fun wideAndTallMeasuredBoundsDriveRowAwareInsertion() {
        val measured = listOf(
            HomeItemBounds("wide", 0f, 0f, 220f, 100f),
            HomeItemBounds("small", 230f, 0f, 330f, 100f),
            HomeItemBounds("tall", 0f, 110f, 100f, 330f),
            HomeItemBounds("last", 110f, 110f, 210f, 210f),
        )
        assertEquals(
            listOf("wide", "small", "tall", "last"),
            reorderHomeOrder(listOf("wide", "small", "tall", "last"), "tall", HomePointer(20f, 220f), measured),
        )
        assertEquals(
            listOf("tall", "wide", "small", "last"),
            reorderHomeOrder(listOf("wide", "small", "tall", "last"), "tall", HomePointer(50f, 30f), measured),
        )
    }

    @Test
    fun dragStateUsesGrabOffsetSlopAndExcludesConcurrentDrag() {
        val state = HomeDragState()
        state.updateBounds(HomeItemBounds("a", 10f, 20f, 110f, 120f))
        state.updateBounds(HomeItemBounds("b", 120f, 20f, 220f, 120f))

        assertTrue(state.begin("a", HomePointer(35f, 55f), slop = 8f))
        assertFalse(state.begin("b", HomePointer(140f, 55f), slop = 8f))
        assertNull(state.finish("b"))
        assertFalse(state.updatePointer(HomePointer(40f, 60f)))
        assertEquals(HomePointer(5f, 5f), state.translationFor("a"))
        assertTrue(state.updatePointer(HomePointer(50f, 75f)))
        assertEquals(HomePointer(15f, 20f), state.translationFor("a"))
        // The active bounds revision remains observable to the graphics layer even though the
        // backing map for non-dragged items is intentionally plain.
        state.updateBounds(HomeItemBounds("a", 20f, 30f, 120f, 130f))
        assertEquals(HomePointer(5f, 10f), state.translationFor("a"))
        assertEquals(HomeDragResult("a", didMove = true), state.finish("a"))
    }

    @Test
    fun coordinatorAllowsOnlyOnePaneAndRejectsStaleRelease() {
        val coordinator = HomeDragCoordinator()
        val first = coordinator.acquire(homePage = 0, itemId = "a")

        assertEquals(HomeDragSession(1L, homePage = 0, itemId = "a"), first)
        assertNull(coordinator.acquire(homePage = 1, itemId = "b"))
        assertTrue(coordinator.isOwner(first))
        assertFalse(coordinator.release(HomeDragSession(999L, 1, "b")))
        assertTrue(coordinator.isOwner(first))

        assertTrue(coordinator.release(first))
        val second = coordinator.acquire(homePage = 1, itemId = "b")
        assertEquals(HomeDragSession(2L, homePage = 1, itemId = "b"), second)
        assertFalse(coordinator.release(first))
        assertTrue(coordinator.isOwner(second))
    }

    @Test
    fun coordinatorResetReleasesOwnerWithoutMakingOldSessionValid() {
        val coordinator = HomeDragCoordinator()
        val oldGeneration = coordinator.currentGeneration
        val first = coordinator.acquire(homePage = 0, itemId = "a")

        coordinator.reset()

        assertNull(coordinator.owner)
        assertFalse(coordinator.isOwner(first))
        assertFalse(coordinator.release(first))
        assertNull(
            coordinator.acquire(
                homePage = 0,
                itemId = "pending-before-reset",
                expectedGeneration = oldGeneration,
            ),
        )
        assertTrue(coordinator.acquire(homePage = 1, itemId = "b") != null)
    }

    @Test
    fun previewStateKeepsSynchronousWorkingOrderUntilCancelOrCommit() {
        val state = HomePreviewState()
        state.syncExternalOrder(listOf("a", "b", "c"), dragging = false)
        state.update(listOf("b", "a", "c"))
        state.syncExternalOrder(listOf("a", "b", "c"), dragging = true)
        assertEquals(listOf("b", "a", "c"), state.order)
        state.resetToBaseOrder()
        assertEquals(listOf("a", "b", "c"), state.order)
    }

    @Test
    fun dragSessionResetDoesNotLeakAcceptedPointerToNextSession() {
        val state = HomePreviewState()
        val base = listOf("a", "b", "c")
        val moved = listOf("b", "a", "c")
        val acceptedPointer = HomePointer(180f, 50f)

        state.syncExternalOrder(base, dragging = false)
        state.beginDrag()
        assertEquals(moved, state.preview(moved, acceptedPointer, hysteresisRadius = 8f))

        // The next drag starts from the current working order. Its first candidate is close to
        // the previous pointer, so this assertion catches an accepted pointer leaking across
        // sessions: beginDrag() must reset it explicitly.
        state.endDrag()
        state.beginDrag()
        assertEquals(
            base,
            state.preview(base, HomePointer(182f, 52f), hysteresisRadius = 8f),
        )
    }

    @Test
    fun explicitSessionResetAcceptsFirstCandidateAfterTouchSlopMovement() {
        val state = HomePreviewState()
        val base = listOf("a", "b", "c")
        val moved = listOf("b", "a", "c")

        state.syncExternalOrder(base, dragging = false)
        state.beginDrag()
        assertEquals(moved, state.preview(moved, HomePointer(100f, 50f), hysteresisRadius = 8f))
        state.endDrag()

        state.beginDrag()
        // This is a deliberate first move beyond touch-slop in the new session, not animation
        // jitter from the previous one.
        assertEquals(
            base,
            state.preview(base, HomePointer(120f, 50f), hysteresisRadius = 8f),
        )
    }

    @Test
    fun animationBoundsCandidateDoesNotReverseAtSamePointer() {
        val base = listOf("a", "b", "c")
        val firstBounds = bounds
        val firstPointer = HomePointer(180f, 50f)
        val accepted = reorderHomeOrder(base, "a", firstPointer, firstBounds)
        assertEquals(listOf("b", "a", "c"), accepted)

        val state = HomePreviewState()
        state.syncExternalOrder(base, dragging = false)
        assertEquals(accepted, state.preview(accepted, firstPointer, hysteresisRadius = 8f))

        // Placement animation temporarily shifts the remaining rectangles. The same finger
        // position would now calculate the old slot, but must not undo the accepted preview.
        val animationBounds = listOf(
            HomeItemBounds("a", 0f, 0f, 100f, 100f),
            HomeItemBounds("b", 180f, 0f, 280f, 100f),
            HomeItemBounds("c", 290f, 0f, 390f, 100f),
        )
        val animationCandidate = reorderHomeOrder(
            accepted,
            "a",
            HomePointer(182f, 51f),
            animationBounds,
        )
        assertEquals(base, animationCandidate)
        assertEquals(
            accepted,
            state.preview(animationCandidate, HomePointer(182f, 51f), hysteresisRadius = 8f),
        )
    }

    @Test
    fun hysteresisAllowsAnotherMoveAfterSpatialThreshold() {
        val gate = HomeReorderHysteresis()
        val base = listOf("a", "b", "c", "d")
        val first = listOf("b", "a", "c", "d")
        val second = listOf("b", "c", "a", "d")

        assertEquals(
            first,
            gate.stabilize(base, first, HomePointer(150f, 50f), hysteresisRadius = 8f),
        )
        // A small jitter in the opposite direction is still inside the accepted transition's
        // spatial hysteresis and cannot reverse the preview.
        assertEquals(
            first,
            gate.stabilize(first, base, HomePointer(156f, 53f), hysteresisRadius = 8f),
        )
        assertEquals(
            second,
            gate.stabilize(first, second, HomePointer(260f, 50f), hysteresisRadius = 8f),
        )
        // Moving back deliberately beyond the same threshold permits a real reverse transition.
        assertEquals(
            first,
            gate.stabilize(second, first, HomePointer(150f, 50f), hysteresisRadius = 8f),
        )
    }
}
