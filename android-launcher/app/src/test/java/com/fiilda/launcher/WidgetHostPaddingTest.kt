package com.fiilda.launcher

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetHostPaddingTest {
    @Test
    fun galaxyAsymmetricPaddingDoesNotShiftWidgetAboveTileCenter() {
        val original = Rect(27, 9, 27, 45)
        val balanced = centeredWidgetHostPadding(original)
        assertEquals(Rect(27, 27, 27, 27), balanced)
        assertEquals(original.top + original.bottom, balanced.top + balanced.bottom)
        assertEquals(Rect(27, 9, 27, 45), original)
    }

    @Test
    fun symmetricPaddingAndZeroPaddingStayUnchanged() {
        for (padding in listOf(Rect(8, 8, 8, 8), Rect())) {
            assertEquals(padding, centeredWidgetHostPadding(padding))
        }
    }

    @Test
    fun oddVerticalTotalPreservesContentHeightAndHorizontalPadding() {
        assertEquals(Rect(3, 13, 7, 14), centeredWidgetHostPadding(Rect(3, 4, 7, 23)))
    }
}
