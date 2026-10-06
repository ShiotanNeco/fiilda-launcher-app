package dev.glasslab.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassSceneTest {
    @Test
    fun contributorOrderFiltersUnavailableDisabledAndTransparentEntries() {
        val result = orderedVisibleGlassContributors(
            listOf(
                GlassSceneOrderEntry(
                    id = 4L,
                    enabled = true,
                    alpha = 1f,
                    zIndex = 2f,
                    captureReady = true,
                    hasFallback = false,
                ),
                GlassSceneOrderEntry(
                    id = 2L,
                    enabled = true,
                    alpha = 1f,
                    zIndex = 0f,
                    captureReady = false,
                    hasFallback = true,
                ),
                GlassSceneOrderEntry(
                    id = 1L,
                    enabled = false,
                    alpha = 1f,
                    zIndex = -2f,
                    captureReady = true,
                    hasFallback = false,
                ),
                GlassSceneOrderEntry(
                    id = 3L,
                    enabled = true,
                    alpha = 0f,
                    zIndex = -1f,
                    captureReady = true,
                    hasFallback = false,
                ),
                GlassSceneOrderEntry(
                    id = 5L,
                    enabled = true,
                    alpha = 1f,
                    zIndex = -1f,
                    captureReady = false,
                    hasFallback = false,
                ),
            ),
        )

        assertEquals(listOf(2L, 4L), result)
    }
}
