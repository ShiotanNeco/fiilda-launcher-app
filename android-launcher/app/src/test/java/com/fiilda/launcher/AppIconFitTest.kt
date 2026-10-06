package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class AppIconFitTest {
    @Test
    fun roomyTilesKeepThePreferredIconSize() {
        assertEquals(63f, fittedAppIconSizeDp(63f, availableWidthDp = 100f, availableHeightDp = 100f, labelHeightDp = 25f))
    }

    @Test
    fun narrowTilesShrinkTheIconSoTheLabelFits() {
        // A 4-column grid on a ~374dp-wide phone leaves about 83dp inside a tile.
        val icon = fittedAppIconSizeDp(63f, availableWidthDp = 83f, availableHeightDp = 83f, labelHeightDp = 25f)
        assertEquals(52.2f, icon, 0.01f)
        assert(icon + 25f <= 83f)
    }

    @Test
    fun withoutALabelOnlyTheTileBoundsLimitTheIcon() {
        assertEquals(63f, fittedAppIconSizeDp(63f, availableWidthDp = 83f, availableHeightDp = 83f, labelHeightDp = 0f))
    }

    @Test
    fun iconsNeverCollapseBelowAUsableSize() {
        assertEquals(24f, fittedAppIconSizeDp(63f, availableWidthDp = 20f, availableHeightDp = 20f, labelHeightDp = 25f))
    }
}
