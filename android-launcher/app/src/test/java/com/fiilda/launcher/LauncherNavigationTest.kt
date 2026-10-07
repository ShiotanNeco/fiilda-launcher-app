package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherNavigationTest {
    @Test
    fun wideGlassRevealKeepsFadeAndScaleWithoutFullScreenBlur() {
        for (page in LauncherPage.entries) {
            for (layer in LauncherPage.entries) {
                val glass = launcherSurfaceLayerVisualTarget(
                    visiblePage = page,
                    layer = layer,
                    posture = Posture.INNER_LANDSCAPE,
                    viewportWidthPx = 2152,
                    transparentBackground = true,
                )
                val opaque = launcherSurfaceLayerVisualTarget(
                    visiblePage = page,
                    layer = layer,
                    posture = Posture.INNER_LANDSCAPE,
                    viewportWidthPx = 2152,
                )
                assertEquals(opaque.offsetPx, glass.offsetPx)
                assertEquals(opaque.alpha, glass.alpha, 0f)
                assertEquals(opaque.scale, glass.scale, 0f)
                assertEquals(0f, glass.blurRadiusDp, 0f)
                assertEquals(if (page == layer) 0f else 18f, opaque.blurRadiusDp, 0f)
            }
        }
    }

    @Test
    fun failedCanonicalMigrationRemainsRetryableUntilCommitted() {
        assertEquals(
            false,
            launcherStateLoadCompletedAfterMigration(
                needsCanonicalMigration = true,
                migrationCommitted = false,
            ),
        )
        assertTrue(
            launcherStateLoadCompletedAfterMigration(
                needsCanonicalMigration = true,
                migrationCommitted = true,
            ),
        )
        assertTrue(
            launcherStateLoadCompletedAfterMigration(
                needsCanonicalMigration = false,
                migrationCommitted = false,
            ),
        )
    }

    @Test
    fun postureClassifierUsesWholeWindowDimensionsAndStableBoundaries() {
        assertEquals(
            Posture.COVER,
            launcherPostureForWindow(widthDp = 599, heightDp = 900),
        )
        assertEquals(
            Posture.INNER_PORTRAIT,
            launcherPostureForWindow(widthDp = 600, heightDp = 900),
        )
        assertEquals(
            Posture.INNER_PORTRAIT,
            launcherPostureForWindow(widthDp = 852, heightDp = 883),
        )
        assertEquals(
            Posture.INNER_LANDSCAPE,
            launcherPostureForWindow(widthDp = 883, heightDp = 852),
        )
        assertEquals(
            Posture.INNER_PORTRAIT,
            launcherPostureForWindow(widthDp = 852, heightDp = 852),
        )
    }

    @Test
    fun pageTokensRoundTripAndRejectUnknownValuesSafely() {
        assertEquals(
            LauncherPage.HOME,
            launcherPageFromToken(launcherPageToken(LauncherPage.HOME)),
        )
        assertEquals(
            LauncherPage.DRAWER,
            launcherPageFromToken(launcherPageToken(LauncherPage.DRAWER)),
        )
        assertEquals(LauncherPage.HOME, launcherPageFromToken(null))
        assertEquals(LauncherPage.HOME, launcherPageFromToken("old-ordinal-99"))
    }

    @Test
    fun destinationFollowsHomePageAndSurface() {
        assertEquals(
            LauncherDestination.HOME,
            launcherDestinationFor(LauncherPage.HOME, selectedHomePage = 0),
        )
        assertEquals(
            LauncherDestination.HOME,
            launcherDestinationFor(LauncherPage.HOME, selectedHomePage = 1),
        )
        assertEquals(
            LauncherDestination.HOME,
            launcherDestinationFor(LauncherPage.HOME, selectedHomePage = 99),
        )
        assertEquals(
            LauncherDestination.DRAWER,
            launcherDestinationFor(LauncherPage.DRAWER, selectedHomePage = 0),
        )
    }

    @Test
    fun repeatedAppsTabTapResetsOnlyAnAlreadyActiveDrawer() {
        assertTrue(
            shouldResetDrawerOnExplicitTabTap(
                currentDestination = LauncherDestination.DRAWER,
                destination = LauncherDestination.DRAWER,
                explicitTabTap = true,
            ),
        )
        assertTrue(
            !shouldResetDrawerOnExplicitTabTap(
                currentDestination = LauncherDestination.HOME,
                destination = LauncherDestination.DRAWER,
                explicitTabTap = true,
            ),
        )
        assertTrue(
            !shouldResetDrawerOnExplicitTabTap(
                currentDestination = LauncherDestination.DRAWER,
                destination = LauncherDestination.DRAWER,
                explicitTabTap = false,
            ),
        )
    }

    @Test
    fun appsTabAccessibilityExplainsTheActiveResetAction() {
        assertEquals(
            "検索をクリアしてアプリ一覧の先頭へ戻る",
            launcherDrawerTabActionLabel(active = true),
        )
        assertEquals(
            "アプリ一覧を表示",
            launcherDrawerTabActionLabel(active = false),
        )
        assertEquals(
            "選択中。タップで検索をクリアしてアプリ一覧の先頭へ戻る",
            launcherDrawerTabStateDescription(active = true),
        )
        assertEquals("", launcherDrawerTabStateDescription(active = false))
    }

    @Test
    fun dualPaneDestinationKeepsHomeActiveForEitherStoredPage() {
        assertEquals(
            LauncherDestination.HOME,
            launcherDestinationFor(
                page = LauncherPage.HOME,
                selectedHomePage = 0,
                presentation = LauncherHomePresentation.DUAL_PANE,
            ),
        )
        assertEquals(
            LauncherDestination.HOME,
            launcherDestinationFor(
                page = LauncherPage.HOME,
                selectedHomePage = 1,
                presentation = LauncherHomePresentation.DUAL_PANE,
            ),
        )
        assertEquals(
            LauncherDestination.DRAWER,
            launcherDestinationFor(
                page = LauncherPage.DRAWER,
                selectedHomePage = 1,
                presentation = LauncherHomePresentation.DUAL_PANE,
            ),
        )
    }

    @Test
    fun contextDestinationsConsolidateHomeTabsInBothPresentations() {
        assertEquals(
            listOf(
                LauncherDestination.HOME,
                LauncherDestination.DRAWER,
            ),
            launcherContextDestinations(LauncherHomePresentation.PAGER),
        )
        assertEquals(
            listOf(LauncherDestination.HOME, LauncherDestination.DRAWER),
            launcherContextDestinations(LauncherHomePresentation.DUAL_PANE),
        )
    }

    @Test
    fun consolidatedHomeDestinationRoutesToHomeWithoutChangingTargetPage() {
        assertEquals(LauncherPage.HOME, LauncherDestination.HOME.page)
        assertEquals(null, LauncherDestination.HOME.homePage)
    }

    @Test
    fun homeDestinationAlwaysReturnsConsolidatedHome() {
        assertEquals(LauncherDestination.HOME, launcherHomeDestination(-1))
        assertEquals(LauncherDestination.HOME, launcherHomeDestination(0))
        assertEquals(LauncherDestination.HOME, launcherHomeDestination(1))
        assertEquals(LauncherDestination.HOME, launcherHomeDestination(99))
    }

    @Test
    fun explicitHomeTapTogglesPagerPageAndClampsMalformedState() {
        assertEquals(
            1,
            launcherHomePageAfterExplicitTap(
                selectedHomePage = 0,
                currentDestination = LauncherDestination.HOME,
                presentation = LauncherHomePresentation.PAGER,
            ),
        )
        assertEquals(
            0,
            launcherHomePageAfterExplicitTap(
                selectedHomePage = 1,
                currentDestination = LauncherDestination.HOME,
                presentation = LauncherHomePresentation.PAGER,
            ),
        )
        assertEquals(
            1,
            launcherHomePageAfterExplicitTap(
                selectedHomePage = -1,
                currentDestination = LauncherDestination.HOME,
                presentation = LauncherHomePresentation.PAGER,
            ),
        )
        assertEquals(
            0,
            launcherHomePageAfterExplicitTap(
                selectedHomePage = 99,
                currentDestination = LauncherDestination.HOME,
                presentation = LauncherHomePresentation.PAGER,
            ),
        )
    }

    @Test
    fun explicitHomeTapFromDrawerPreservesSelectedPagerPage() {
        assertEquals(
            0,
            launcherHomePageAfterExplicitTap(
                selectedHomePage = 0,
                currentDestination = LauncherDestination.DRAWER,
                presentation = LauncherHomePresentation.PAGER,
                explicitHomeTap = true,
            ),
        )
        assertEquals(
            1,
            launcherHomePageAfterExplicitTap(
                selectedHomePage = 1,
                currentDestination = LauncherDestination.DRAWER,
                presentation = LauncherHomePresentation.PAGER,
                explicitHomeTap = true,
            ),
        )
    }

    @Test
    fun nonExplicitHomeNavigationPreservesSelectedPagerPage() {
        assertEquals(
            0,
            launcherHomePageAfterExplicitTap(
                selectedHomePage = 0,
                currentDestination = LauncherDestination.HOME,
                presentation = LauncherHomePresentation.PAGER,
                explicitHomeTap = false,
            ),
        )
        assertEquals(
            1,
            launcherHomePageAfterExplicitTap(
                selectedHomePage = 1,
                currentDestination = LauncherDestination.HOME,
                presentation = LauncherHomePresentation.PAGER,
                explicitHomeTap = false,
            ),
        )
    }

    @Test
    fun sequentialExplicitHomeTapsTogglePagerPageBackAndForth() {
        val afterFirstTap = launcherHomePageAfterExplicitTap(
            selectedHomePage = 0,
            currentDestination = LauncherDestination.HOME,
            presentation = LauncherHomePresentation.PAGER,
            explicitHomeTap = true,
        )
        val afterSecondTap = launcherHomePageAfterExplicitTap(
            selectedHomePage = afterFirstTap,
            currentDestination = LauncherDestination.HOME,
            presentation = LauncherHomePresentation.PAGER,
            explicitHomeTap = true,
        )

        assertEquals(1, afterFirstTap)
        assertEquals(0, afterSecondTap)
    }

    @Test
    fun explicitHomeTapDoesNotChangeSelectedPageInStartCanvas() {
        assertEquals(
            0,
            launcherHomePageAfterExplicitTap(
                selectedHomePage = 0,
                currentDestination = LauncherDestination.HOME,
                presentation = LauncherHomePresentation.START_CANVAS,
            ),
        )
        assertEquals(
            1,
            launcherHomePageAfterExplicitTap(
                selectedHomePage = 1,
                currentDestination = LauncherDestination.HOME,
                presentation = LauncherHomePresentation.START_CANVAS,
            ),
        )
        assertEquals(
            1,
            launcherHomePageAfterExplicitTap(
                selectedHomePage = 99,
                currentDestination = LauncherDestination.HOME,
                presentation = LauncherHomePresentation.START_CANVAS,
            ),
        )
    }

    @Test
    fun homeTabAccessibilityDescribesTheActivePagerSurfaceAndToggleTarget() {
        assertEquals(
            LauncherHomeTabAccessibility(
                contentDescription = "ホーム1表示中",
                stateDescription = "ホーム1表示中。タップでホーム2へ切り替え",
                actionLabel = "ホーム2へ切り替え",
            ),
            launcherHomeTabAccessibility(
                presentation = LauncherHomePresentation.PAGER,
                destination = LauncherDestination.HOME,
                selectedHomePage = 0,
            ),
        )
        assertEquals(
            LauncherHomeTabAccessibility(
                contentDescription = "ホーム。アプリ一覧を表示中",
                stateDescription = "アプリ一覧を表示中。タップでホーム1へ戻る",
                actionLabel = "ホーム1へ戻る",
            ),
            launcherHomeTabAccessibility(
                presentation = LauncherHomePresentation.PAGER,
                destination = LauncherDestination.DRAWER,
                selectedHomePage = 0,
            ),
        )
    }

    @Test
    fun homeTabAccessibilityKeepsStartCanvasCopyStable() {
        assertEquals(
            LauncherHomeTabAccessibility(
                contentDescription = "ホーム。横スクロールのStartキャンバス",
                stateDescription = "ホームタイルを横スクロール",
                actionLabel = "ホームタイルを横スクロール",
            ),
            launcherHomeTabAccessibility(
                presentation = LauncherHomePresentation.START_CANVAS,
                destination = LauncherDestination.DRAWER,
                selectedHomePage = 1,
            ),
        )
    }

    @Test
    fun stableSurfaceLayerOffsetUsesTheFullViewportWidthContract() {
        assertEquals(
            0,
            launcherSurfaceLayerTargetOffset(
                visiblePage = LauncherPage.HOME,
                layer = LauncherPage.HOME,
                viewportWidthPx = 400,
            ),
        )
        assertEquals(
            100,
            launcherSurfaceLayerTargetOffset(
                visiblePage = LauncherPage.HOME,
                layer = LauncherPage.DRAWER,
                viewportWidthPx = 400,
            ),
        )
        assertEquals(
            -100,
            launcherSurfaceLayerTargetOffset(
                visiblePage = LauncherPage.DRAWER,
                layer = LauncherPage.HOME,
                viewportWidthPx = 400,
            ),
        )
    }

    @Test
    fun centeredSurfaceRevealIsLimitedToTheInnerLandscapeCanvas() {
        listOf(Posture.COVER, Posture.INNER_PORTRAIT).forEach { posture ->
            assertEquals(
                LauncherSurfaceLayerVisualTarget(
                    offsetPx = 100,
                    alpha = 1f,
                    scale = 1f,
                    blurRadiusDp = 0f,
                ),
                launcherSurfaceLayerVisualTarget(
                    visiblePage = LauncherPage.HOME,
                    layer = LauncherPage.DRAWER,
                    posture = posture,
                    viewportWidthPx = 400,
                ),
            )
        }

        assertEquals(
            LauncherSurfaceLayerVisualTarget(
                offsetPx = 0,
                alpha = 1f,
                scale = 1f,
                blurRadiusDp = 0f,
            ),
            launcherSurfaceLayerVisualTarget(
                visiblePage = LauncherPage.DRAWER,
                layer = LauncherPage.DRAWER,
                posture = Posture.INNER_LANDSCAPE,
                viewportWidthPx = 400,
            ),
        )
        assertEquals(
            LauncherSurfaceLayerVisualTarget(
                offsetPx = 0,
                alpha = 0f,
                scale = 0.96f,
                blurRadiusDp = 18f,
            ),
            launcherSurfaceLayerVisualTarget(
                visiblePage = LauncherPage.HOME,
                layer = LauncherPage.DRAWER,
                posture = Posture.INNER_LANDSCAPE,
                viewportWidthPx = 400,
            ),
        )
    }

    @Test
    fun transparentNarrowSurfaceHidesOnlyTheInactiveLayer() {
        assertEquals(
            LauncherSurfaceLayerVisualTarget(
                offsetPx = 100,
                alpha = 0f,
                scale = 1f,
                blurRadiusDp = 0f,
            ),
            launcherSurfaceLayerVisualTarget(
                visiblePage = LauncherPage.HOME,
                layer = LauncherPage.DRAWER,
                posture = Posture.INNER_PORTRAIT,
                viewportWidthPx = 400,
                transparentBackground = true,
            ),
        )
        assertEquals(
            LauncherSurfaceLayerVisualTarget(
                offsetPx = 0,
                alpha = 1f,
                scale = 1f,
                blurRadiusDp = 0f,
            ),
            launcherSurfaceLayerVisualTarget(
                visiblePage = LauncherPage.HOME,
                layer = LauncherPage.HOME,
                posture = Posture.COVER,
                viewportWidthPx = 400,
                transparentBackground = true,
            ),
        )
    }

    @Test
    fun wideHeaderAlwaysTargetsTheOtherSurface() {
        assertEquals(LauncherDestination.DRAWER, wideHeaderTargetDestination(LauncherPage.HOME))
        assertEquals(LauncherDestination.HOME, wideHeaderTargetDestination(LauncherPage.DRAWER))
    }

    @Test
    fun postureTransitionMapsDrawerAndHomeAcrossTheTwoSurfaceModels() {
        assertEquals(
            LauncherPostureTransition(
                page = LauncherPage.DRAWER,
                wideAnchor = LauncherDestination.DRAWER,
                selectedHomePage = 1,
            ),
            mapLauncherPostureTransition(
                from = LauncherHomePresentation.PAGER,
                to = LauncherHomePresentation.START_CANVAS,
                page = LauncherPage.DRAWER,
                wideAnchor = LauncherDestination.HOME,
                selectedHomePage = 1,
            ),
        )
        assertEquals(
            LauncherPostureTransition(
                page = LauncherPage.DRAWER,
                wideAnchor = LauncherDestination.DRAWER,
                selectedHomePage = 0,
            ),
            mapLauncherPostureTransition(
                from = LauncherHomePresentation.START_CANVAS,
                to = LauncherHomePresentation.PAGER,
                page = LauncherPage.HOME,
                wideAnchor = LauncherDestination.DRAWER,
                selectedHomePage = 0,
            ),
        )
        assertEquals(
            LauncherPostureTransition(
                page = LauncherPage.HOME,
                wideAnchor = LauncherDestination.HOME,
                selectedHomePage = 1,
            ),
            mapLauncherPostureTransition(
                from = LauncherHomePresentation.START_CANVAS,
                to = LauncherHomePresentation.PAGER,
                page = LauncherPage.HOME,
                wideAnchor = LauncherDestination.HOME,
                selectedHomePage = 0,
                wideHomeOwnerPage = 1,
            ),
        )
    }

    @Test
    fun currentStableSurfaceLayerAlwaysRendersAboveTheOtherLayer() {
        assertTrue(
            launcherSurfaceLayerZIndex(LauncherPage.HOME, LauncherPage.HOME) >
                launcherSurfaceLayerZIndex(LauncherPage.HOME, LauncherPage.DRAWER),
        )
        assertTrue(
            launcherSurfaceLayerZIndex(LauncherPage.DRAWER, LauncherPage.DRAWER) >
                launcherSurfaceLayerZIndex(LauncherPage.DRAWER, LauncherPage.HOME),
        )
    }
}
