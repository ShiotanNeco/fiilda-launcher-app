package com.fiilda.launcher

/** The two content surfaces that can be presented by the launcher. */
internal enum class LauncherPage {
    HOME,
    DRAWER,
}

internal const val LauncherPageHomeToken = "home"
internal const val LauncherPageDrawerToken = "drawer"

/** Stable, explicit tokens for saving the visible launcher surface across Activity recreation. */
internal fun launcherPageToken(page: LauncherPage): String = when (page) {
    LauncherPage.HOME -> LauncherPageHomeToken
    LauncherPage.DRAWER -> LauncherPageDrawerToken
}

/** Restores unknown or missing state safely to HOME rather than relying on enum ordinals. */
internal fun launcherPageFromToken(token: String?): LauncherPage = when (token) {
    LauncherPageDrawerToken -> LauncherPage.DRAWER
    LauncherPageHomeToken -> LauncherPage.HOME
    else -> LauncherPage.HOME
}

/** How the home surface is represented to the user in the current posture. */
internal enum class LauncherHomePresentation {
    /** Cover and inner portrait: one page is visible and the pager owns page-to-page motion. */
    PAGER,

    /** Inner landscape: one continuous Windows-8-style horizontal Start canvas. */
    START_CANVAS,

    /** Source compatibility for callers compiled against the pre-canvas WIP. */
    @Deprecated("Use START_CANVAS")
    DUAL_PANE,
}

/**
 * The destinations exposed by the bottom bar, in their spatial order.
 *
 * Home pages intentionally remain one [LauncherPage] so the existing pager owns home-to-home
 * motion. [HOME] is the presentation-level destination for both the pager and Start canvas
 * presentations; it does not encode the selected or add-target home page.
 */
internal enum class LauncherDestination {
    HOME,
    DRAWER,
    ;

    val page: LauncherPage
        get() = if (this == DRAWER) LauncherPage.DRAWER else LauncherPage.HOME

    val homePage: Int?
        get() = when (this) {
            HOME -> null
            DRAWER -> null
        }
}

/** Maps the current content state to the destination represented by the bottom bar. */
internal fun launcherDestinationFor(
    page: LauncherPage,
    selectedHomePage: Int,
    presentation: LauncherHomePresentation = LauncherHomePresentation.PAGER,
): LauncherDestination = when (page) {
    LauncherPage.HOME -> LauncherDestination.HOME
    LauncherPage.DRAWER -> LauncherDestination.DRAWER
}

/** Whether an explicit tap on the already-selected Apps tab should restore the drawer baseline. */
internal fun shouldResetDrawerOnExplicitTabTap(
    currentDestination: LauncherDestination,
    destination: LauncherDestination,
    explicitTabTap: Boolean,
): Boolean = explicitTabTap &&
    currentDestination == LauncherDestination.DRAWER &&
    destination == LauncherDestination.DRAWER

/** Accessibility copy for the Apps tab's navigation action in each selection state. */
internal fun launcherDrawerTabActionLabel(active: Boolean): String = if (active) {
    tr("検索をクリアしてアプリ一覧の先頭へ戻る", "Clear search and go to the top of the app list")
} else {
    tr("アプリ一覧を表示", "Show app list")
}

/** State text explains that tapping the active Apps tab resets search and scroll position. */
internal fun launcherDrawerTabStateDescription(active: Boolean): String = if (active) {
    tr("選択中。タップで検索をクリアしてアプリ一覧の先頭へ戻る", "Selected. Tap to clear search and go to the top of the app list")
} else {
    ""
}

/** Returns the bottom-bar items for a home presentation in their visual order. */
internal fun launcherContextDestinations(
    presentation: LauncherHomePresentation,
): List<LauncherDestination> = listOf(
    LauncherDestination.HOME,
    LauncherDestination.DRAWER,
)

/** Returns the consolidated destination used when returning from the drawer to home. */
internal fun launcherHomeDestination(@Suppress("UNUSED_PARAMETER") homePage: Int): LauncherDestination =
    LauncherDestination.HOME

/**
 * Returns the home page selected by HOME navigation.
 *
 * HOME is a stable destination in the wide Start canvas, where tapping the bottom item only
 * changes the canvas anchor. In the narrow pager, an explicit bottom HOME tap while HOME is
 * already visible is the legacy page-toggle gesture and pager swipes remain the direct alternative.
 * Returning from another destination (including the drawer) must preserve the page.
 */
internal fun launcherHomePageAfterExplicitTap(
    selectedHomePage: Int,
    currentDestination: LauncherDestination,
    presentation: LauncherHomePresentation,
    explicitHomeTap: Boolean = true,
    homePageCount: Int = DefaultHomePageCount,
): Int {
    val normalizedPage = selectedHomePage.coerceIn(0, homePageCount.coerceAtLeast(1) - 1)
    if (!explicitHomeTap ||
        currentDestination != LauncherDestination.HOME ||
        presentation == LauncherHomePresentation.START_CANVAS ||
        presentation == LauncherHomePresentation.DUAL_PANE
    ) {
        return normalizedPage
    }
    return if (homePageCount <= 1) {
        normalizedPage
    } else {
        (normalizedPage + 1) % homePageCount
    }
}

/** Accessibility text for the consolidated Home tab in each presentation and visible surface. */
internal data class LauncherHomeTabAccessibility(
    val contentDescription: String,
    val stateDescription: String,
    val actionLabel: String,
)

internal fun launcherHomeTabAccessibility(
    presentation: LauncherHomePresentation,
    destination: LauncherDestination,
    selectedHomePage: Int,
    homePageCount: Int = DefaultHomePageCount,
): LauncherHomeTabAccessibility {
    val normalizedPage = selectedHomePage.coerceIn(0, homePageCount.coerceAtLeast(1) - 1)
    val nextPage = if (homePageCount <= 1) {
        normalizedPage
    } else {
        (normalizedPage + 1) % homePageCount
    }
    return when (presentation) {
        LauncherHomePresentation.PAGER -> if (destination == LauncherDestination.HOME) {
            LauncherHomeTabAccessibility(
                contentDescription = tr("ホーム${normalizedPage + 1}表示中", "Showing Home ${normalizedPage + 1}"),
                stateDescription =
                    tr("ホーム${normalizedPage + 1}表示中。タップでホーム${nextPage + 1}へ切り替え", "Showing Home ${normalizedPage + 1}. Tap to switch to Home ${nextPage + 1}"),
                actionLabel = tr("ホーム${nextPage + 1}へ切り替え", "Switch to Home ${nextPage + 1}"),
            )
        } else {
            LauncherHomeTabAccessibility(
                contentDescription = tr("ホーム。アプリ一覧を表示中", "Home. Showing app list"),
                stateDescription =
                    tr("アプリ一覧を表示中。タップでホーム${normalizedPage + 1}へ戻る", "Showing app list. Tap to go back to Home ${normalizedPage + 1}"),
                actionLabel = tr("ホーム${normalizedPage + 1}へ戻る", "Back to Home ${normalizedPage + 1}"),
            )
        }
        LauncherHomePresentation.START_CANVAS,
        LauncherHomePresentation.DUAL_PANE -> LauncherHomeTabAccessibility(
            contentDescription = tr("ホーム。横スクロールのStartキャンバス", "Home. Sideways Start canvas"),
            stateDescription = tr("ホームタイルを横スクロール", "Scroll Home tiles sideways"),
            actionLabel = tr("ホームタイルを横スクロール", "Scroll Home tiles sideways"),
        )
    }
}

/**
 * Returns the target horizontal offset for a stable surface layer.
 *
 * The visible layer is always at rest. The other layer is positioned on the side from which it
 * will enter (or to which it will leave), so changing [visiblePage] simply retargets one spring
 * on each of the two already-composed layers.
 */
internal fun launcherSurfaceLayerTargetOffset(
    visiblePage: LauncherPage,
    layer: LauncherPage,
    viewportWidthPx: Int,
): Int {
    val distance = viewportWidthPx.coerceAtLeast(0) / 4
    return when {
        visiblePage == layer -> 0
        visiblePage == LauncherPage.HOME && layer == LauncherPage.DRAWER -> distance
        visiblePage == LauncherPage.DRAWER && layer == LauncherPage.HOME -> -distance
        else -> 0
    }
}

/**
 * Visual target for one of the permanently composed surface layers.
 *
 * The narrow pager presentation keeps the established horizontal movement. The unfolded
 * horizontal canvas uses a centered reveal instead: the layer is already centered, and its
 * content resolves from a small, transparent state to its settled form. Opaque themes also blur
 * that reveal; glass retains its per-tile material filtering. Keeping this target pure makes the
 * posture boundary explicit and keeps interrupted transitions retargetable from the current
 * Compose animation values.
 */
internal data class LauncherSurfaceLayerVisualTarget(
    val offsetPx: Int,
    val alpha: Float,
    val scale: Float,
    val blurRadiusDp: Float,
)

internal fun launcherSurfaceLayerVisualTarget(
    visiblePage: LauncherPage,
    layer: LauncherPage,
    posture: Posture,
    viewportWidthPx: Int,
    transparentBackground: Boolean = false,
): LauncherSurfaceLayerVisualTarget {
    if (posture != Posture.INNER_LANDSCAPE) {
        val visible = visiblePage == layer
        return LauncherSurfaceLayerVisualTarget(
            offsetPx = launcherSurfaceLayerTargetOffset(
                visiblePage = visiblePage,
                layer = layer,
                viewportWidthPx = viewportWidthPx,
            ),
            alpha = if (transparentBackground && !visible) 0f else 1f,
            scale = 1f,
            blurRadiusDp = 0f,
        )
    }

    val visible = visiblePage == layer
    return LauncherSurfaceLayerVisualTarget(
        offsetPx = 0,
        alpha = if (visible) 1f else 0f,
        scale = if (visible) 1f else 0.96f,
        // Glass already filters the wallpaper per tile. Blurring both full-screen glass layers
        // again during the reveal adds large render targets; retain the fade and scale instead.
        blurRadiusDp = if (visible || transparentBackground) 0f else 18f,
    )
}

/** Places the current surface above the other stable layer without any retained-child ordering. */
internal fun launcherSurfaceLayerZIndex(
    visiblePage: LauncherPage,
    layer: LauncherPage,
): Float = if (visiblePage == layer) 1f else 0f

/** A live LazyRow position that can be restored without inventing a second pager state. */
internal data class StartCanvasScrollPosition(
    val itemIndex: Int,
    val itemOffsetPx: Int,
)

/**
 * Purely derives the active bottom anchor from what is visible, without issuing a scroll command.
 * The search panel is the boundary: once its leading edge crosses the viewport center, APPS is
 * active. If it has already left the viewport, the first visible item index is sufficient.
 */
internal fun wideHeaderTargetDestination(page: LauncherPage): LauncherDestination =
    if (page == LauncherPage.HOME) LauncherDestination.DRAWER else LauncherDestination.HOME

/**
 * Returns the Home position that is safe to persist after observing the Start canvas.
 *
 * Pixel positions are intentionally ignored while scrolling. This lets the UI track the finger
 * directly without sending a parent state update for every frame; the final manual or animated
 * position is committed once the shared LazyListState becomes idle.
 */
internal data class LauncherPostureTransition(
    val page: LauncherPage,
    val wideAnchor: LauncherDestination,
    val selectedHomePage: Int,
)

/** Explicitly maps the two surface models when a fold posture changes. */
internal fun mapLauncherPostureTransition(
    from: LauncherHomePresentation,
    to: LauncherHomePresentation,
    page: LauncherPage,
    wideAnchor: LauncherDestination,
    selectedHomePage: Int,
    wideHomeOwnerPage: Int = selectedHomePage,
    homePageCount: Int = DefaultHomePageCount,
): LauncherPostureTransition {
    val owner = wideHomeOwnerPage.coerceIn(0, homePageCount.coerceAtLeast(1) - 1)
    val selected = selectedHomePage.coerceIn(0, homePageCount.coerceAtLeast(1) - 1)
    val fromWide = from == LauncherHomePresentation.START_CANVAS ||
        from == LauncherHomePresentation.DUAL_PANE
    val toWide = to == LauncherHomePresentation.START_CANVAS ||
        to == LauncherHomePresentation.DUAL_PANE
    return when {
        !fromWide && toWide ->
            LauncherPostureTransition(
                page = page,
                wideAnchor = if (page == LauncherPage.DRAWER) {
                    LauncherDestination.DRAWER
                } else {
                    LauncherDestination.HOME
                },
                selectedHomePage = selected,
            )
        fromWide && !toWide && wideAnchor == LauncherDestination.DRAWER ->
            LauncherPostureTransition(LauncherPage.DRAWER, LauncherDestination.DRAWER, selected)
        fromWide && !toWide ->
            LauncherPostureTransition(LauncherPage.HOME, LauncherDestination.HOME, owner)
        else -> LauncherPostureTransition(page, wideAnchor, selected)
    }
}
