package com.fiilda.launcher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetPickerScrollConnectionTest {
    @Test
    fun flingLeftAtEitherListEdgeDoesNotReachTheSheet() = runBlocking {
        for (speed in listOf(-18000f, -500f, 0f, 500f, 18000f)) {
            val available = Velocity(120f, speed)
            val taken = WidgetPickerScrollConnection.onPostFling(Velocity(0f, -2000f), available)
            assertEquals(0f, (available - taken).y, 0f)
            assertEquals(120f, (available - taken).x, 0f)
        }
    }

    @Test
    fun fingerPullAndInitialFlingStillReachTheSheet() = runBlocking {
        val pull = Offset(0f, 80f)
        assertEquals(Offset.Zero, WidgetPickerScrollConnection.onPreScroll(pull, NestedScrollSource.UserInput))
        assertEquals(Offset.Zero, WidgetPickerScrollConnection.onPostScroll(Offset.Zero, pull, NestedScrollSource.UserInput))
        assertEquals(Velocity.Zero, WidgetPickerScrollConnection.onPreFling(Velocity(0f, 3000f)))
    }
}
