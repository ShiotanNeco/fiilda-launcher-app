package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerRevealTest {
    @Test
    fun ordersRunAlongDiagonalsFromTheFirstVisibleRow() {
        assertEquals(0, drawerRevealOrder(appIndex = 0, columns = 6, firstVisibleRow = 0))
        assertEquals(1, drawerRevealOrder(appIndex = 1, columns = 6, firstVisibleRow = 0))
        assertEquals(1, drawerRevealOrder(appIndex = 6, columns = 6, firstVisibleRow = 0))
        // Scrolled down three rows: that row starts the reveal, and rows above it do not wait.
        assertEquals(0, drawerRevealOrder(appIndex = 18, columns = 6, firstVisibleRow = 3))
        assertEquals(0, drawerRevealOrder(appIndex = 0, columns = 6, firstVisibleRow = 3))
    }

    @Test
    fun farTilesWaitNoLongerThanTheCap() {
        val farthest = drawerRevealOrder(appIndex = 600, columns = 6, firstVisibleRow = 0)
        assertEquals(1f, drawerRevealTileProgress(farthest, DrawerRevealDurationMs), 0f)
    }

    @Test
    fun tilesStartHiddenSwellAndSettleAtFullSize() {
        assertEquals(0f, drawerRevealTileAlpha(drawerRevealTileProgress(0, 0f)), 0f)
        assertEquals(1f, drawerRevealTileScale(1f), 0.0001f)
        assertEquals(1f, drawerRevealTileAlpha(1f), 0f)
        assertTrue(drawerRevealTileScale(0.8f) > 1f)
    }

    @Test
    fun homeStartsAtTheBottomRightVisibleCell() {
        val visible = 4..13
        assertEquals(0, homeRevealOrder(columnEnd = 13, rowEnd = 5, visibleColumns = visible, rows = 6))
        assertEquals(1, homeRevealOrder(columnEnd = 12, rowEnd = 5, visibleColumns = visible, rows = 6))
        // A large tile counts from its bottom-right corner; tiles past the right edge go first.
        assertEquals(0, homeRevealOrder(columnEnd = 20, rowEnd = 5, visibleColumns = visible, rows = 6))
        // The top-left corner is far away and waits no longer than the cap.
        val topLeft = homeRevealOrder(columnEnd = 0, rowEnd = 0, visibleColumns = visible, rows = 6)
        assertEquals(1f, drawerRevealTileProgress(topLeft, DrawerRevealDurationMs), 0f)
    }

    @Test
    fun homeBlurClearsWhenTheRevealEnds() {
        assertTrue(homeRevealBlurDp(0f) > homeRevealBlurDp(DrawerRevealDurationMs / 2))
        assertEquals(0f, homeRevealBlurDp(DrawerRevealDurationMs - 100f), 0f)
    }
}
