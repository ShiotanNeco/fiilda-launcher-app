package com.fiilda.launcher

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.CompositionLocalProvider
import dev.glasslab.glass.GlassGeometrySignal
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt
import java.time.LocalDateTime
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Home presentation, board layout, and drag coordinator UI. */
@Composable
internal fun HomeSurface(
    posture: Posture,
    isVisible: Boolean,
    glassSceneEnabled: Boolean = true,
    glassSceneAlpha: Float = 1f,
    glassSceneZIndex: Float = 0f,
    glassSceneGeometryVersion: Any? = null,
    navigationBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    now: LocalDateTime,
    installedApps: List<LaunchableApp>,
    homePages: HomePages,
    separateWideHomeOrder: Boolean,
    wideAppTileSizes: Map<String, AppTileSize>,
    wideWidgetSizeOverrides: Map<String, WidgetSizeChoice>,
    wideHomeOrder: List<String>,
    wideCanvasLastHomePosition: StartCanvasScrollPosition,
    lifecycleRefreshToken: Int,
    pagerState: PagerState,
    selectedPackage: String?,
    appTileSizes: Map<String, AppTileSize>,
    appTileContentModes: Map<String, AppTileContentMode>,
    appShortcuts: Map<String, List<ResolvedLauncherShortcut>>,
    notificationState: ActiveNotificationState,
    widgetSizeOverrides: Map<String, WidgetSizeChoice>,
    externalWidgets: List<LauncherWidgetDescriptor>,
    pinnedShortcuts: List<ResolvedPinnedShortcut>,
    homeFolders: List<HomeFolder>,
    expandedFolderId: String?,
    onFolderOpen: (HomeFolder, IntRect) -> Unit,
    appWidgetHost: AppWidgetHost,
    appWidgetManager: AppWidgetManager,
    onWeather: () -> Unit,
    onCalendar: () -> Unit,
    onMedia: () -> Unit,
    photoUris: Map<String, String>,
    photoVideoMutes: Map<String, Boolean>,
    onPhotoMuteChanged: (String, Boolean) -> Unit,
    onPhotoPreview: (String) -> Unit,
    onOpenApp: (LaunchableApp) -> Unit,
    onOpenShortcut: (LaunchableApp, ResolvedLauncherShortcut) -> Unit,
    onOpenPinnedShortcut: (ResolvedPinnedShortcut) -> Unit,
    onLongPressApp: (LaunchableApp, Int, HomeSizePresentation) -> Unit,
    onLongPressWidget: (HomeItem, Int, HomeSizePresentation) -> Unit,
    onLongPressPinnedShortcut: (ResolvedPinnedShortcut, Int, HomeSizePresentation) -> Unit,
    onLongPressFolder: (HomeFolder, Int, HomeSizePresentation) -> Unit,
    onFolderDrop: (String, String) -> Boolean,
    homeDragCoordinator: HomeDragCoordinator,
    onPreviewOrder: (Int, List<String>) -> Unit,
    onCommitOrder: (Int, List<String>) -> Unit,
    onCancelOrder: (Int) -> Unit,
    onPreviewWideOrder: (List<String>) -> Unit,
    onCommitWideOrder: (List<String>) -> Unit,
    onCancelWideOrder: () -> Unit,
    onCanvasHomePositionChanged: (StartCanvasScrollPosition) -> Unit,
) {
    val context = LocalContext.current
    val mediaState = rememberMediaSessionState(context, lifecycleRefreshToken)
    val openMediaSettings = {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
    val currentOnMedia = rememberUpdatedState(onMedia)
    val openMedia = {
        if (!launchMediaApp(context, mediaState.controller)) {
            currentOnMedia.value()
        }
    }
    val notificationByPackage = remember(notificationState.snapshots, homePages, installedApps) {
        val homePackageNames = installedApps
            .asSequence()
            .filter { app -> favoriteId(app) in homePages.allIds }
            .map { app -> app.notificationPackageKey() }
            .toSet()
        projectFavoriteNotifications(
            notifications = notificationState.snapshots,
            favoritePackages = homePackageNames,
        )
    }
    val glassEnabled = LocalLauncherGlass.current.enabled
    val homeImeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    // The navigation bar is a sibling overlay in the launcher root. Keep the final board item
    // reachable while preserving the existing wide canvas, which intentionally has no bar.
    val homeTrailingOverlayPadding = if (
        posture != Posture.INNER_LANDSCAPE &&
        homeImeBottomPadding <= 0.dp
    ) {
        navigationBottomPadding
    } else {
        0.dp
    }

    // Home pages are hosted by subcompositions owned by HorizontalPager/LazyRow. Keep the
    // routing callbacks at this stable parent boundary so a reused board cannot retain the
    // callback from the other posture after CLOSED -> OPENED (or vice versa).
    val currentOnPreviewOrder = rememberUpdatedState(onPreviewOrder)
    val currentOnCommitOrder = rememberUpdatedState(onCommitOrder)
    val currentOnCancelOrder = rememberUpdatedState(onCancelOrder)
    val currentOnPreviewWideOrder = rememberUpdatedState(onPreviewWideOrder)
    val currentOnCommitWideOrder = rememberUpdatedState(onCommitWideOrder)
    val currentOnCancelWideOrder = rememberUpdatedState(onCancelWideOrder)
    val renderedSizeMaps = homeSizeMapsForPresentation(
        narrow = HomeSizeMaps(
            appTileSizes = appTileSizes,
            widgetSizeOverrides = widgetSizeOverrides,
        ),
        wide = HomeSizeMaps(
            appTileSizes = wideAppTileSizes,
            widgetSizeOverrides = wideWidgetSizeOverrides,
        ),
        separateWideOrder = separateWideHomeOrder,
        presentation = if (posture == Posture.INNER_LANDSCAPE) {
            HomeSizePresentation.WIDE
        } else {
            HomeSizePresentation.NARROW
        },
    )

    @Composable
    fun HomePageContent(
        homePageIndex: Int,
        boardPosture: Posture,
        scrollState: ScrollState,
        isPageActive: Boolean,
        homeFloat: HomeFloatState?,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .homeFloatViewport(homeFloat)
                .verticalScroll(scrollState)
                // Keep the first board row clear of the status bar at scroll 0, while making
                // that clearance part of the scrollable content so it moves away on a drag.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            HomeBoard(
                posture = boardPosture,
                isVisible = isVisible && isPageActive,
                now = now,
                items = buildHomeItems(
                    order = homePages[homePageIndex],
                    apps = installedApps,
                    externalWidgets = externalWidgets,
                    pinnedShortcuts = pinnedShortcuts,
                    folders = homeFolders,
                    webLinks = LocalWebLinkTiles.current,
                ),
                installedApps = installedApps,
                selectedPackage = selectedPackage,
                appTileSizes = renderedSizeMaps.appTileSizes,
                appTileContentModes = appTileContentModes,
                appShortcuts = appShortcuts,
                notificationByPackage = notificationByPackage,
                widgetSizeOverrides = renderedSizeMaps.widgetSizeOverrides,
                externalWidgets = externalWidgets,
                folders = homeFolders,
                expandedFolderId = expandedFolderId,
                onFolderOpen = onFolderOpen,
                appWidgetHost = appWidgetHost,
                appWidgetManager = appWidgetManager,
                onWeather = onWeather,
                onCalendar = onCalendar,
                onMedia = openMedia,
                photoUris = photoUris,
                photoVideoMutes = photoVideoMutes,
                onPhotoMuteChanged = onPhotoMuteChanged,
                onPhotoPreview = onPhotoPreview,
                mediaState = mediaState,
                onOpenMediaSettings = openMediaSettings,
                onOpenApp = onOpenApp,
                onOpenShortcut = onOpenShortcut,
                onOpenPinnedShortcut = onOpenPinnedShortcut,
                onLongPressApp = { app ->
                    onLongPressApp(app, homePageIndex, HomeSizePresentation.NARROW)
                },
                onLongPressWidget = { item ->
                    onLongPressWidget(item, homePageIndex, HomeSizePresentation.NARROW)
                },
                onLongPressPinnedShortcut = { shortcut ->
                    onLongPressPinnedShortcut(
                        shortcut,
                        homePageIndex,
                        HomeSizePresentation.NARROW,
                    )
                },
                onLongPressFolder = { folder ->
                    onLongPressFolder(folder, homePageIndex, HomeSizePresentation.NARROW)
                },
                onFolderDrop = onFolderDrop,
                homePageIndex = homePageIndex,
                homeDragCoordinator = homeDragCoordinator,
                onPreviewOrder = { updated -> currentOnPreviewOrder.value(homePageIndex, updated) },
                onCommitOrder = { updated -> currentOnCommitOrder.value(homePageIndex, updated) },
                onCancelOrder = { currentOnCancelOrder.value(homePageIndex) },
                glassSceneEnabled = glassSceneEnabled,
                glassSceneAlpha = glassSceneAlpha,
                glassSceneZIndex = glassSceneZIndex,
                glassSceneGeometryVersion = glassSceneGeometryVersion,
                homeFloat = homeFloat,
            )
            Spacer(modifier = Modifier.height(homeTrailingOverlayPadding))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (posture == Posture.INNER_LANDSCAPE) {
            key("home-start-canvas") {
                StartCanvas(
                    isVisible = isVisible,
                    glassSceneEnabled = glassSceneEnabled,
                    glassSceneAlpha = glassSceneAlpha,
                    glassSceneZIndex = glassSceneZIndex,
                    glassSceneGeometryVersion = glassSceneGeometryVersion,
                    now = now,
                    order = wideHomeOrder,
                    lastHomePosition = wideCanvasLastHomePosition,
                    onHomePositionChanged = onCanvasHomePositionChanged,
                    installedApps = installedApps,
                    selectedPackage = selectedPackage,
                    appTileSizes = renderedSizeMaps.appTileSizes,
                    appTileContentModes = appTileContentModes,
                    appShortcuts = appShortcuts,
                    notificationByPackage = notificationByPackage,
                    widgetSizeOverrides = renderedSizeMaps.widgetSizeOverrides,
                    externalWidgets = externalWidgets,
                    pinnedShortcuts = pinnedShortcuts,
                    folders = homeFolders,
                    expandedFolderId = expandedFolderId,
                    onFolderOpen = onFolderOpen,
                    appWidgetHost = appWidgetHost,
                    appWidgetManager = appWidgetManager,
                    onWeather = onWeather,
                    onCalendar = onCalendar,
                    onMedia = openMedia,
                    photoUris = photoUris,
                    photoVideoMutes = photoVideoMutes,
                    onPhotoMuteChanged = onPhotoMuteChanged,
                    onPhotoPreview = onPhotoPreview,
                    onOpenApp = onOpenApp,
                    onOpenShortcut = onOpenShortcut,
                    onOpenPinnedShortcut = onOpenPinnedShortcut,
                    onLongPressApp = { app ->
                        onLongPressApp(
                            app,
                            homePageContaining(homePages, favoriteId(app)) ?: 0,
                            HomeSizePresentation.WIDE,
                        )
                    },
                    onLongPressWidget = { item ->
                        onLongPressWidget(
                            item,
                            homePageContaining(homePages, item.id) ?: 0,
                            HomeSizePresentation.WIDE,
                        )
                    },
                    onLongPressPinnedShortcut = { shortcut ->
                        onLongPressPinnedShortcut(
                            shortcut,
                            homePageContaining(homePages, shortcut.homeId) ?: 0,
                            HomeSizePresentation.WIDE,
                        )
                    },
                    onLongPressFolder = { folder ->
                        onLongPressFolder(
                            folder,
                            homePageContaining(homePages, folder.id) ?: 0,
                            HomeSizePresentation.WIDE,
                        )
                    },
                    onFolderDrop = onFolderDrop,
                    mediaState = mediaState,
                    onOpenMediaSettings = openMediaSettings,
                    homeDragCoordinator = homeDragCoordinator,
                    onPreviewOrder = { updated -> currentOnPreviewWideOrder.value(updated) },
                    onCommitOrder = { updated -> currentOnCommitWideOrder.value(updated) },
                    onCancelOrder = { currentOnCancelWideOrder.value() },
                )
            }
        } else {
            key("home-narrow-pager") {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // HorizontalPager clips its pages horizontally at its own bounds. Extend
                        // it through the side margin so floating tiles are cut at the screen edge,
                        // and give each page the margin back as padding below.
                        .extendIntoSideMargin()
                        .semantics {
                            contentDescription =
                                tr("ホームページ。左右にスワイプで切り替え。${pagerState.currentPage + 1}/${homePages.count}", "Home pages. Swipe left or right to switch. ${pagerState.currentPage + 1}/${homePages.count}")
                            stateDescription = tr("ページ ${pagerState.currentPage + 1} / ${homePages.count}", "Page ${pagerState.currentPage + 1} of ${homePages.count}")
                        },
                    // Keep the neighbors composed. Composing a page at the start of a swipe stalled
                    // the gesture long enough for quick repeated swipes to snap back. Hidden pages
                    // pause their video through LocalHomeBoardVisible.
                    beyondViewportPageCount = 1,
                ) { homePageIndex ->
                    val scrollState = rememberScrollState()
                    val homeFloat = rememberHomeFloatState { scrollState.isScrollInProgress }
                    val reduceMotion = rememberReduceMotion()
                    val maxPageBlurPx = with(LocalDensity.current) { MaxSurfaceMotionBlur.toPx() }
                    val isPageActive by remember(homePageIndex, pagerState) {
                        derivedStateOf { pagerState.currentPage == homePageIndex }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // A neighbor page kept composed off screen is not drawn, so its glass
                            // and tiles do no per-frame work until a swipe brings it into view.
                            .drawWithContent {
                                val distance = kotlin.math.abs(
                                    pagerState.currentPage + pagerState.currentPageOffsetFraction - homePageIndex,
                                )
                                if (distance < 1f) drawContent()
                            }
                            .padding(horizontal = LauncherHorizontalMargin)
                            .then(
                                // Blurring a whole page re-renders every glass tile into an extra
                                // offscreen layer each frame; glass is already soft, so skip it.
                                if (reduceMotion || glassEnabled) {
                                    Modifier
                                } else {
                                    Modifier.motionBlur {
                                        val pageOffset = pagerState.currentPage - homePageIndex +
                                            pagerState.currentPageOffsetFraction
                                        maxPageBlurPx * surfaceMotionBlurFraction(pageOffset)
                                    }
                                },
                            ),
                    ) {
                    LauncherGlassSceneScope(
                        enabled = isPageActive || pagerState.isScrollInProgress,
                        geometryVersion = if (glassEnabled) {
                            // Read while drawing: a scroll or float frame then redraws the glass
                            // without recomposing the page. Floating tiles keep moving after the
                            // scroll stops, so the glass resamples until they settle.
                            remember(scrollState, pagerState, homeFloat) {
                                GlassGeometrySignal {
                                    listOf(
                                        scrollState.value,
                                        pagerState.currentPage,
                                        pagerState.currentPageOffsetFraction,
                                        homeFloat?.frame?.intValue,
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    ) {
                        HomePageContent(
                            homePageIndex = homePageIndex,
                            boardPosture = posture,
                            scrollState = scrollState,
                            isPageActive = isPageActive,
                            homeFloat = homeFloat,
                        )
                    }
                    if (homePages[homePageIndex].isEmpty()) {
                        EmptyHomePageHint(modifier = Modifier.align(Alignment.Center))
                    }
                    }
                }
            }
        }
    }
}

/** Inner-landscape horizontal Home canvas. Apps and search live on the separate Drawer surface. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StartCanvas(
    isVisible: Boolean,
    glassSceneEnabled: Boolean = true,
    glassSceneAlpha: Float = 1f,
    glassSceneZIndex: Float = 0f,
    glassSceneGeometryVersion: Any? = null,
    now: LocalDateTime,
    order: List<String>,
    lastHomePosition: StartCanvasScrollPosition,
    onHomePositionChanged: (StartCanvasScrollPosition) -> Unit,
    installedApps: List<LaunchableApp>,
    selectedPackage: String?,
    appTileSizes: Map<String, AppTileSize>,
    appTileContentModes: Map<String, AppTileContentMode>,
    appShortcuts: Map<String, List<ResolvedLauncherShortcut>>,
    notificationByPackage: Map<String, List<ActiveNotificationSnapshot>>,
    widgetSizeOverrides: Map<String, WidgetSizeChoice>,
    externalWidgets: List<LauncherWidgetDescriptor>,
    pinnedShortcuts: List<ResolvedPinnedShortcut>,
    folders: List<HomeFolder>,
    expandedFolderId: String?,
    onFolderOpen: (HomeFolder, IntRect) -> Unit,
    appWidgetHost: AppWidgetHost,
    appWidgetManager: AppWidgetManager,
    onWeather: () -> Unit,
    onCalendar: () -> Unit,
    onMedia: () -> Unit,
    photoUris: Map<String, String>,
    photoVideoMutes: Map<String, Boolean>,
    onPhotoMuteChanged: (String, Boolean) -> Unit,
    onPhotoPreview: (String) -> Unit,
    onOpenApp: (LaunchableApp) -> Unit,
    onOpenShortcut: (LaunchableApp, ResolvedLauncherShortcut) -> Unit,
    onOpenPinnedShortcut: (ResolvedPinnedShortcut) -> Unit,
    onLongPressApp: (LaunchableApp) -> Unit,
    onLongPressWidget: (HomeItem) -> Unit,
    onLongPressPinnedShortcut: (ResolvedPinnedShortcut) -> Unit,
    onLongPressFolder: (HomeFolder) -> Unit,
    onFolderDrop: (String, String) -> Boolean,
    mediaState: MediaSessionState,
    onOpenMediaSettings: () -> Unit,
    homeDragCoordinator: HomeDragCoordinator,
    onPreviewOrder: (List<String>) -> Unit,
    onCommitOrder: (List<String>) -> Unit,
    onCancelOrder: () -> Unit,
) {
    // Capture once: parent callbacks update lastHomePosition on every scroll frame, but those
    // live values must never re-key this LazyListState.
    val initialHomePosition = remember {
        StartCanvasScrollPosition(
            itemIndex = lastHomePosition.itemIndex.coerceAtLeast(0),
            itemOffsetPx = lastHomePosition.itemOffsetPx.coerceAtLeast(0),
        )
    }
    val rowState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialHomePosition.itemIndex,
        initialFirstVisibleItemScrollOffset = initialHomePosition.itemOffsetPx,
    )
    val homeFloat = rememberHomeFloatState(scrollOrientation = Orientation.Horizontal) {
        rowState.isScrollInProgress
    }
    var initialHomePositionRestored by remember { mutableStateOf(false) }
    LaunchedEffect(order.isNotEmpty()) {
        if (!initialHomePositionRestored && order.isNotEmpty()) {
            // The first empty async-load frame can clamp LazyListState to zero. Reapply the saved
            // position only after the Home item exists, then allow live persistence to begin.
            rowState.scrollToItem(
                initialHomePosition.itemIndex,
                initialHomePosition.itemOffsetPx,
            )
            initialHomePositionRestored = true
        }
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val mediaOpenSettings = onOpenMediaSettings
    val currentOnHomePositionChanged = rememberUpdatedState(onHomePositionChanged)
    val currentInitialHomePositionRestored = rememberUpdatedState(initialHomePositionRestored)
    val homeCanvasItemVisible by remember(rowState) {
        derivedStateOf {
            rowState.layoutInfo.visibleItemsInfo.any { item -> item.index == 0 }
        }
    }
    val canvasBounds = remember { LatestHomeItemBounds() }
    val edgeDragReevaluation = remember { HomeDragReevaluationHolder() }
    val edgeScrollState = remember { HomeEdgeScrollState() }
    val homePositionCommitGate = remember { HomePositionCommitGate() }
    val commitHomePosition: (StartCanvasScrollPosition) -> Unit = { position ->
        if (homePositionCommitGate.commitIfChanged(position)) {
            currentOnHomePositionChanged.value(position)
        }
    }
    // This is only an effect key. The plain holder above is authoritative for ownership, so an
    // older cancelled effect can never reset a newer reversed direction through stale Compose
    // state.
    var edgeScrollEffectGeneration by remember { mutableStateOf(0L) }
    val edgeThresholdPx = with(density) { 48.dp.toPx() }
    val edgeScrollSpeedPxPerSecond = with(density) { 240.dp.toPx() }
    val updateEdgeScrollDirection: (HomePointer?) -> Unit = { pointer ->
        val direction = horizontalEdgeAutoScrollDirection(
            pointer = pointer,
            viewport = canvasBounds.value,
            edgePx = edgeThresholdPx,
        )
        // Update ownership before publishing the effect key. This ordering is what makes a
        // cancellation/reversal race harmless on the main thread.
        if (edgeScrollState.updateDirection(direction)) {
            edgeScrollEffectGeneration = edgeScrollState.generation
        }
    }
    // The list scroll runs independently of the pointer detector, so edge motion remains fluid
    // while the captured tile is being dragged. scrollBy uses the same LazyListState mutex as
    // anchor animations, making a new finger gesture interrupt an in-flight anchor naturally.
    LaunchedEffect(edgeScrollEffectGeneration) {
        val capturedGeneration = edgeScrollState.generation
        val capturedDirection = edgeScrollState.direction
        val frameMillis = 16L
        if (capturedDirection == 0) return@LaunchedEffect
        try {
            while (edgeScrollState.owns(capturedGeneration, capturedDirection)) {
                val consumed = rowState.scrollBy(
                    capturedDirection * edgeScrollSpeedPxPerSecond * frameMillis / 1_000f,
                )
                if (consumed == 0f) {
                    // A saturated edge must terminate the loop. Continuing to invalidate the board
                    // after LazyListState can no longer consume motion creates an infinite busy loop.
                    if (edgeScrollState.stopIfOwned(capturedGeneration, capturedDirection)) {
                        edgeScrollEffectGeneration = edgeScrollState.generation
                    }
                    break
                }
                // Re-evaluate the active drag directly from the loop. This avoids a mutable pulse
                // state and one LaunchedEffect per retained tile for every edge-scroll frame.
                delay(frameMillis)
                // Let the LazyRow publish its new layout before converting the retained pointer to a
                // cell. The callback remains a direct holder call; the delay only gives placement and
                // bounds callbacks one frame to observe the consumed scroll.
                if (edgeScrollState.owns(capturedGeneration, capturedDirection)) {
                    edgeDragReevaluation.callback?.invoke()
                }
            }
        } finally {
            // A cancelled scrollBy must stop only the effect that owns this direction. A direction
            // change has already started a new keyed effect, so its finally block must not clobber
            // that newer direction.
            if (edgeScrollState.stopIfOwned(capturedGeneration, capturedDirection)) {
                edgeScrollEffectGeneration = edgeScrollState.generation
            }
            // scrollBy is complete and therefore idle here. Commit once when an edge loop ends
            // (also when the pointer leaves the edge) so the parent never receives one update per
            // pulse.
            if (
                currentInitialHomePositionRestored.value &&
                edgeScrollState.direction == 0 &&
                !rowState.isScrollInProgress
            ) {
                commitHomePosition(
                    StartCanvasScrollPosition(
                        itemIndex = rowState.firstVisibleItemIndex,
                        itemOffsetPx = rowState.firstVisibleItemScrollOffset,
                    ),
                )
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            // Invalidate the holder before the callback/effect can be cancelled. The old effect's
            // finally block then fails its ownership check and cannot clobber a newer direction.
            edgeScrollState.updateDirection(0)
            edgeDragReevaluation.callback = null
            // A posture transition disposes this canvas while a fling or edge pulse can still
            // have a non-zero LazyListState offset. Commit that final in-memory position before
            // the state holder disappears. Read the latest restore gate/callback through
            // rememberUpdatedState so this effect never retains the initial false callback.
            val finalPosition = StartCanvasScrollPosition(
                itemIndex = rowState.firstVisibleItemIndex,
                itemOffsetPx = rowState.firstVisibleItemScrollOffset,
            )
            val shouldCommit = homePositionCommitGate.disposeAndCommit(finalPosition)
            edgeScrollState.dispose()
            if (currentInitialHomePositionRestored.value && shouldCommit) {
                currentOnHomePositionChanged.value(finalPosition)
            }
        }
    }
    LaunchedEffect(rowState) {
        var wasScrolling = false
        snapshotFlow {
            rowState.isScrollInProgress to StartCanvasScrollPosition(
                itemIndex = rowState.firstVisibleItemIndex,
                itemOffsetPx = rowState.firstVisibleItemScrollOffset,
            )
        }.distinctUntilChanged()
            .collect { (isScrolling, position) ->
                if (isScrolling) {
                    wasScrolling = true
                } else if (
                    wasScrolling &&
                    currentInitialHomePositionRestored.value &&
                    edgeScrollState.direction == 0
                ) {
                    // Position is observed continuously for the scroll itself, but only the
                    // transition back to idle is allowed to update the parent/saveable state.
                    commitHomePosition(position)
                    wasScrolling = false
                } else if (!isScrolling) {
                    wasScrolling = false
                }
            }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInRoot()
                val size = coordinates.size
                canvasBounds.value = HomeItemBounds(
                    id = "start-canvas",
                    left = position.x,
                    top = position.y,
                    right = position.x + size.width,
                    bottom = position.y + size.height,
                )
            }
            .semantics { contentDescription = tr("ホームタイルを横スクロール", "Scroll Home tiles sideways") },
    ) {
        val viewportWidth = maxWidth.coerceAtLeast(320.dp)
        val canvasRows = 6
        val gap = HomeGridGapDp.dp
        val homeCellSize = (maxHeight - gap * (canvasRows - 1)) / canvasRows
        // Resolve the provider's current measured size once for the canvas. The exact map is
        // passed to HomeBoard below so planning and rendering cannot disagree about a widget's
        // width after a provider update or density change.
        val sharedExternalWidgetSizes = remember(
            externalWidgets,
            appWidgetManager,
            homeCellSize,
            density.density,
        ) {
            externalWidgets.associate { descriptor ->
                val current = descriptorForWidget(context, appWidgetManager, descriptor.appWidgetId)
                    ?.takeIf { it.provider == descriptor.provider }
                    ?: descriptor
                descriptor.homeId to calculateWidgetGridSpans(
                    spec = current.sizeSpec,
                    cellWidthDp = homeCellSize.value,
                    gapDp = gap.value,
                    columns = canvasRows,
                )
            }
        }
        val webLinks = LocalWebLinkTiles.current
        val homeCanvasItems = remember(
            order,
            installedApps,
            externalWidgets,
            pinnedShortcuts,
            folders,
            webLinks,
            appTileSizes,
            sharedExternalWidgetSizes,
            widgetSizeOverrides,
        ) {
            buildHomeItems(order, installedApps, externalWidgets, pinnedShortcuts, folders, webLinks).map { item ->
                val size = homeItemSize(
                    item = item,
                    columns = 6,
                    appTileSizes = appTileSizes,
                    externalWidgetSizes = sharedExternalWidgetSizes,
                    widgetSizeOverrides = widgetSizeOverrides,
                )
                HomeGridItem(item.id, size.columnSpan, size.rowSpan)
            }
        }
        val estimatedHomePlan = remember(homeCanvasItems) {
            horizontalHomeGridPlan(homeCanvasItems, rows = 6)
        }
        val viewportWidthPx = with(density) { viewportWidth.roundToPx() }
        val pixelStride = homeGridPixelStride(
            cellWidthDp = homeCellSize.value,
            gapDp = gap.value,
            density = density.density,
        )
        val cellWidthPx = pixelStride.cellWidthPx
        val gapPx = pixelStride.gapPx
        val cellStridePx = pixelStride.stridePx
        val visibleCanvasColumnWindow by remember(
            rowState,
            viewportWidthPx,
            cellStridePx,
            estimatedHomePlan.columns,
        ) {
            derivedStateOf {
                homeGridColumnWindow(
                    scrollOffsetPx = rowState.firstVisibleItemScrollOffset,
                    viewportWidthPx = viewportWidthPx,
                    cellStridePx = cellStridePx,
                    totalColumns = estimatedHomePlan.columns,
                )
            }
        }
        val homeCanvasWidth = maxOf(
            viewportWidth,
            with(density) {
                homeGridBoardWidthPx(
                    cellWidthPx = cellWidthPx,
                    gapPx = gapPx,
                    columns = estimatedHomePlan.columns,
                ).toDp()
            },
        )
        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxSize().homeFloatViewport(homeFloat).extendIntoSideMargin(),
            // Widen only the drawing viewport; matching padding keeps tile positions and the
            // scroll range unchanged while animated tiles can enter the existing side margins.
            contentPadding = PaddingValues(horizontal = with(density) {
                (gap.roundToPx() + LauncherHorizontalMargin.roundToPx()).toDp()
            }),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Keep the canvas slot distinct from any pager slot that may have existed before a
            // posture change. This prevents LazyRow reuse from carrying a narrow board's gesture
            // routing into the wide presentation.
            item(key = "start-home-canvas") {
                Box(Modifier.width(homeCanvasWidth).fillMaxHeight()) {
                    LauncherGlassSceneScope(
                        enabled = homeCanvasItemVisible,
                        geometryVersion = if (LocalLauncherGlass.current.enabled) {
                            remember(rowState, homeFloat) {
                                GlassGeometrySignal {
                                    listOf(
                                        rowState.firstVisibleItemIndex,
                                        rowState.firstVisibleItemScrollOffset,
                                        homeFloat?.frame?.intValue,
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    ) {
                    HomeBoard(
                        posture = Posture.INNER_LANDSCAPE,
                        isVisible = isVisible && homeCanvasItemVisible,
                        now = now,
                        items = buildHomeItems(order, installedApps, externalWidgets, pinnedShortcuts, folders, webLinks),
                        installedApps = installedApps,
                        selectedPackage = selectedPackage,
                        appTileSizes = appTileSizes,
                        appTileContentModes = appTileContentModes,
                        appShortcuts = appShortcuts,
                        notificationByPackage = notificationByPackage,
                        widgetSizeOverrides = widgetSizeOverrides,
                        externalWidgets = externalWidgets,
                        folders = folders,
                        expandedFolderId = expandedFolderId,
                        onFolderOpen = onFolderOpen,
                        externalWidgetSizesOverride = sharedExternalWidgetSizes,
                        appWidgetHost = appWidgetHost,
                        appWidgetManager = appWidgetManager,
                        onWeather = onWeather,
                        onCalendar = onCalendar,
                        onMedia = onMedia,
                        photoUris = photoUris,
                        photoVideoMutes = photoVideoMutes,
                        onPhotoMuteChanged = onPhotoMuteChanged,
                        onPhotoPreview = onPhotoPreview,
                        mediaState = mediaState,
                        onOpenMediaSettings = mediaOpenSettings,
                        onOpenApp = onOpenApp,
                        onOpenShortcut = onOpenShortcut,
                        onOpenPinnedShortcut = onOpenPinnedShortcut,
                        onLongPressApp = onLongPressApp,
                        onLongPressWidget = onLongPressWidget,
                        onLongPressPinnedShortcut = onLongPressPinnedShortcut,
                        onLongPressFolder = onLongPressFolder,
                        onFolderDrop = onFolderDrop,
                        homePageIndex = 0,
                        homeDragCoordinator = homeDragCoordinator,
                        edgeDragReevaluation = edgeDragReevaluation,
                        horizontalVisibleColumnWindow = visibleCanvasColumnWindow,
                        homeFloat = homeFloat,
                        onPreviewOrder = onPreviewOrder,
                        onCommitOrder = onCommitOrder,
                        onCancelOrder = onCancelOrder,
                        onEdgeAutoScroll = updateEdgeScrollDirection,
                        glassSceneEnabled = glassSceneEnabled,
                        glassSceneAlpha = glassSceneAlpha,
                        glassSceneZIndex = glassSceneZIndex,
                        glassSceneGeometryVersion = glassSceneGeometryVersion,
                    )
                    }
                }
            }
        }
    }
}

private fun widgetSizeLabel(
    id: String,
    overrides: Map<String, WidgetSizeChoice>,
    builtIn: Boolean,
): String {
    val choice = overrides[id]
    return if (builtIn) {
        choice?.takeUnless { it.isAuto }?.label ?: WidgetSizeChoice.ROW_2_COLUMN_2.label
    } else {
        choice?.label ?: WidgetSizeChoice.AUTO.label
    }
}

private fun homeItemSize(
    item: HomeItem,
    columns: Int,
    appTileSizes: Map<String, AppTileSize>,
    externalWidgetSizes: Map<String, WidgetGridSize> = emptyMap(),
    widgetSizeOverrides: Map<String, WidgetSizeChoice> = emptyMap(),
): GridItemSize = when (item) {
    is HomeItem.App -> {
        val size = appTileSizes[item.id] ?: AppTileSize.SMALL
        GridItemSize(
            columnSpan = minOf(size.columnSpan, columns),
            rowSpan = size.rowSpan,
        )
    }

    is HomeItem.Widget -> resolveWidgetGridSize(
        choice = widgetSizeOverrides[item.id],
        providerSize = WidgetGridSize(columnSpan = 2, rowSpan = 2),
        columns = columns,
        builtIn = true,
    ).let { size -> GridItemSize(size.columnSpan, size.rowSpan) }

    is HomeItem.ExternalWidget -> resolveWidgetGridSize(
        choice = widgetSizeOverrides[item.id],
        providerSize = externalWidgetSizes[item.id] ?: WidgetGridSize(1, 1),
        columns = columns,
        builtIn = false,
    ).let { size -> GridItemSize(size.columnSpan, size.rowSpan) }

    is HomeItem.PinnedShortcut -> GridItemSize(columnSpan = 1, rowSpan = 1)

    is HomeItem.WebLink -> resolveWidgetGridSize(
        choice = widgetSizeOverrides[item.id],
        providerSize = WidgetGridSize(columnSpan = 1, rowSpan = 1),
        columns = columns,
        builtIn = false,
    ).let { size -> GridItemSize(size.columnSpan, size.rowSpan) }

    is HomeItem.Folder -> GridItemSize(
        columnSpan = minOf(item.folder.size.columnSpan, columns),
        rowSpan = item.folder.size.rowSpan,
    )
}

/**
 * Stable decorative accents for the home board.
 *
 * The position, rather than the app identity, drives the pattern so it remains a quiet part of
 * the board even when the user reorders favorites. Keeping this deterministic also prevents
 * recomposition or a posture change from making the cyan frames jump between tiles.
 */
private fun homePatternAccent(column: Int, row: Int, homePageIndex: Int): Boolean {
    val phase = homePageIndex.coerceAtLeast(0) * 2
    return (column + row + phase) % 2 == 0
}

private class LatestHomeItemBounds(initial: HomeItemBounds? = null) {
    // Every access is from a synchronous pointer/event callback. This is deliberately not
    // snapshot state: scrolling can update these rectangles every pixel, and no composition
    // observes them directly.
    var value: HomeItemBounds? = initial
}

/** Latest layout node used synchronously by a pointer callback; intentionally non-observable. */
private class LatestHomeLayoutCoordinates {
    var value: LayoutCoordinates? = null
}

/** Current folder tile bounds for the opening callback; deliberately plain, not snapshot state. */
private class LatestFolderBounds {
    var value: IntRect? = null
}

/** Chooses a fresh layout measurement before the last published folder bounds. */
internal fun homeFolderOpenBounds(
    latestBounds: IntRect?,
    publishedBounds: IntRect?,
): IntRect? = latestBounds ?: publishedBounds

/**
 * Synchronous bridge from the StartCanvas edge-scroll loop to the currently dragged tile.
 *
 * This is intentionally plain state: the loop and pointer callbacks both run on the main thread,
 * and publishing it as Compose state would invalidate every retained tile once per frame.
 */
private class HomeDragReevaluationHolder {
    var callback: (() -> Unit)? = null
}

/**
 * Lets an AndroidView child request the owning HomeDragItem's logical bounds immediately before
 * it accepts a long press. The callback is synchronous and plain so ordinary scroll layout passes
 * do not allocate bounds or write to HomeDragState for every item.
 */
private class HomeDragBoundsSyncHolder {
    var callback: (() -> Boolean)? = null
    var boundsCallback: (() -> IntRect?)? = null
}

@Composable
private fun HomeBoard(
    posture: Posture,
    isVisible: Boolean = true,
    glassSceneEnabled: Boolean = true,
    glassSceneAlpha: Float = 1f,
    glassSceneZIndex: Float = 0f,
    glassSceneGeometryVersion: Any? = null,
    now: LocalDateTime,
    items: List<HomeItem>,
    installedApps: List<LaunchableApp>,
    selectedPackage: String?,
    appTileSizes: Map<String, AppTileSize>,
    appTileContentModes: Map<String, AppTileContentMode>,
    appShortcuts: Map<String, List<ResolvedLauncherShortcut>>,
    notificationByPackage: Map<String, List<ActiveNotificationSnapshot>>,
    widgetSizeOverrides: Map<String, WidgetSizeChoice>,
    externalWidgets: List<LauncherWidgetDescriptor>,
    folders: List<HomeFolder>,
    expandedFolderId: String?,
    onFolderOpen: (HomeFolder, IntRect) -> Unit,
    externalWidgetSizesOverride: Map<String, WidgetGridSize> = emptyMap(),
    appWidgetHost: AppWidgetHost,
    appWidgetManager: AppWidgetManager,
    onWeather: () -> Unit,
    onCalendar: () -> Unit,
    onMedia: () -> Unit,
    photoUris: Map<String, String>,
    photoVideoMutes: Map<String, Boolean>,
    onPhotoMuteChanged: (String, Boolean) -> Unit,
    onPhotoPreview: (String) -> Unit,
    mediaState: MediaSessionState,
    onOpenMediaSettings: () -> Unit,
    onOpenApp: (LaunchableApp) -> Unit,
    onOpenShortcut: (LaunchableApp, ResolvedLauncherShortcut) -> Unit,
    onOpenPinnedShortcut: (ResolvedPinnedShortcut) -> Unit,
    onLongPressApp: (LaunchableApp) -> Unit,
    onLongPressWidget: (HomeItem) -> Unit,
    onLongPressPinnedShortcut: (ResolvedPinnedShortcut) -> Unit,
    onLongPressFolder: (HomeFolder) -> Unit,
    onFolderDrop: (String, String) -> Boolean,
    homePageIndex: Int,
    homeDragCoordinator: HomeDragCoordinator,
    edgeDragReevaluation: HomeDragReevaluationHolder? = null,
    horizontalVisibleColumnWindow: IntRange? = null,
    onPreviewOrder: (List<String>) -> Unit,
    onCommitOrder: (List<String>) -> Unit,
    onCancelOrder: () -> Unit,
    onEdgeAutoScroll: (HomePointer?) -> Unit = {},
    homeFloat: HomeFloatState? = null,
) {
    val isHorizontalStartCanvas = posture == Posture.INNER_LANDSCAPE
    val columns = if (posture == Posture.COVER) 4 else 6
    val fixedCanvasRows = 6
    val context = LocalContext.current
    val density = LocalDensity.current
    val battery = rememberBatteryStatus()
    val homeTouchSlop = ViewConfiguration.get(LocalView.current.context).scaledTouchSlop.toFloat()
    val dragState = remember { HomeDragState() }
    val previewState = remember { HomePreviewState() }
    val activeDragSession = remember { mutableStateOf<HomeDragSession?>(null) }
    var folderDropTargetId by remember { mutableStateOf<String?>(null) }
    var folderDropCandidateId by remember { mutableStateOf<String?>(null) }
    val externalOrder = items.map { it.id }
    // Gesture/edge/accessibility callbacks can outlive the composition that created them. Keep
    // the model callbacks behind updated-state bridges just like cancel/edge handlers so a
    // delayed drop cannot publish through an old presentation-mode closure.
    val currentOnPreviewOrder = rememberUpdatedState(onPreviewOrder)
    val currentOnCommitOrder = rememberUpdatedState(onCommitOrder)
    val currentOnCancelOrder = rememberUpdatedState(onCancelOrder)
    val currentOnEdgeAutoScroll = rememberUpdatedState(onEdgeAutoScroll)
    val boardBounds = remember { LatestHomeItemBounds() }
    val reorderCandidateCache = remember { HomeReorderCandidateCache() }
    SideEffect {
        previewState.syncExternalOrder(externalOrder, dragState.isDragging)
    }
    val activeItemIds = remember(items) { items.mapTo(linkedSetOf()) { it.id } }
    val itemById = remember(items) { items.associateBy { it.id } }
    val appsById = remember(installedApps) { installedApps.associateBy(::favoriteId) }
    val resetLocalPreview: () -> Unit = {
        previewState.endDrag()
        previewState.resetToBaseOrder()
        reorderCandidateCache.invalidate()
        folderDropTargetId = null
        folderDropCandidateId = null
    }
    val cancelActiveDrag: () -> Unit = {
        val session = activeDragSession.value
        val ownsSession = homeDragCoordinator.isOwner(session)
        if (ownsSession) {
            homeDragCoordinator.release(session)
        }
        activeDragSession.value = null
        resetLocalPreview()
        currentOnEdgeAutoScroll.value(null)
        if (ownsSession) {
            currentOnCancelOrder.value()
        }
    }
    val cancelDragForItem: (String) -> Unit = { itemId ->
        val session = activeDragSession.value
        // A rejected item/pane must not cancel the session owned by a different item/page.
        if (session?.itemId == itemId) {
            cancelActiveDrag()
        }
    }
    val acquireDrag: (String, Long) -> HomeDragSession? = { itemId, generation ->
        val session = homeDragCoordinator.acquire(
            homePage = homePageIndex,
            itemId = itemId,
            expectedGeneration = generation,
        )
        if (session == null) {
            null
        } else {
            activeDragSession.value = session
            previewState.beginDrag()
            reorderCandidateCache.invalidate()
            session
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            val session = activeDragSession.value
            val ownsSession = homeDragCoordinator.isOwner(session)
            if (ownsSession) {
                homeDragCoordinator.release(session)
            }
            activeDragSession.value = null
            dragState.cancel()
            resetLocalPreview()
            currentOnEdgeAutoScroll.value(null)
            if (ownsSession) {
                currentOnCancelOrder.value()
            }
        }
    }

    // A posture change or removal of the active item invalidates the measured rectangles. Do not
    // commit a stale preview in either case.
    LaunchedEffect(posture) {
        if (dragState.isDragging) {
            dragState.cancel()
            cancelActiveDrag()
        }
    }
    LaunchedEffect(isVisible) {
        if (!isVisible) {
            // The inactive HomeSurface remains composed behind the drawer so the transition can
            // reverse without rebuilding it. Its gesture state must nevertheless be discarded
            // immediately; otherwise a later pointer-up could commit a hidden preview.
            dragState.cancel()
            cancelActiveDrag()
            currentOnEdgeAutoScroll.value(null)
            resetLocalPreview()
        }
    }
    LaunchedEffect(activeItemIds) {
        if (dragState.draggedId != null && dragState.draggedId !in activeItemIds) {
            dragState.cancel()
            cancelActiveDrag()
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            // Common-ancestor, read-only monitor: Initial pass observes all hit paths, including
            // a second finger landing on another tile, without consuming child tap/drag changes.
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (dragState.isDragging && event.changes.count { it.pressed } >= 2) {
                            dragState.cancel()
                            cancelActiveDrag()
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
    ) {
        val gap = HomeGridGapDp.dp
        val cellWidth = if (isHorizontalStartCanvas) {
            (maxHeight - gap * (fixedCanvasRows - 1)) / fixedCanvasRows
        } else {
            (maxWidth - gap * (columns - 1)) / columns
        }
        // Provider metadata and HostView default padding can change after a package update or a
        // density/configuration change. Re-query on every actual board-width change so AUTO
        // sizing uses the same current provider data that the host view will receive.
        val currentExternalDescriptors = remember(
            externalWidgets,
            cellWidth,
            columns,
            density.density,
        ) {
            externalWidgets.map { descriptor ->
                descriptorForWidget(context, appWidgetManager, descriptor.appWidgetId)
                    ?.takeIf { it.provider == descriptor.provider }
                    ?: descriptor
            }
        }
        val measuredExternalWidgetSizes = remember(currentExternalDescriptors, cellWidth, columns) {
            currentExternalDescriptors.associate { descriptor ->
                descriptor.homeId to calculateWidgetGridSpans(
                    spec = descriptor.sizeSpec,
                    cellWidthDp = cellWidth.value,
                    gapDp = gap.value,
                    columns = columns,
                )
            }
        }
        val externalWidgetSizes = remember(
            measuredExternalWidgetSizes,
            externalWidgetSizesOverride,
        ) {
            measuredExternalWidgetSizes + externalWidgetSizesOverride
        }
        val gridItems = remember(items, columns, appTileSizes, externalWidgetSizes, widgetSizeOverrides) {
            items.map { item ->
                val size = homeItemSize(
                    item = item,
                    columns = columns,
                    appTileSizes = appTileSizes,
                    externalWidgetSizes = externalWidgetSizes,
                    widgetSizeOverrides = widgetSizeOverrides,
                )
                HomeGridItem(
                    id = item.id,
                    columnSpan = size.columnSpan,
                    rowSpan = size.rowSpan,
                )
            }
        }
        val gridPlan = remember(gridItems, columns, isHorizontalStartCanvas) {
            if (isHorizontalStartCanvas) {
                val horizontal = horizontalHomeGridPlan(gridItems, rows = fixedCanvasRows)
                HomeGridPlan(
                    columns = horizontal.columns,
                    placements = horizontal.placements,
                    rows = horizontal.rows,
                )
            } else {
                denseHomeGridPlan(gridItems, columns)
            }
        }
        // A wide Start board keeps its complete logical plan and dimensions, but only composes
        // placements near the LazyRow viewport. The active drag is retained even when it leaves
        // the overscan range so its graphics layer and pointer callbacks remain alive.
        val visiblePlacements = remember(
            gridPlan,
            horizontalVisibleColumnWindow,
            dragState.draggedId,
        ) {
            homeGridPlacementsInColumnWindow(
                plan = gridPlan,
                columnWindow = horizontalVisibleColumnWindow,
                retainedItemId = dragState.draggedId,
            )
        }
        val itemSizesById = remember(gridItems) { gridItems.associateBy { it.id } }
        val cellWidthPx = with(density) { cellWidth.roundToPx() }
        val gapPx = with(density) { gap.roundToPx() }
        val boardHeightPx = homeGridBoardHeightPx(cellWidthPx, gapPx, gridPlan.rows)
        val boardWidthPx = homeGridBoardWidthPx(cellWidthPx, gapPx, gridPlan.columns)
        val boardHeight = with(density) { boardHeightPx.toDp() }
        val boardWidth = with(density) { boardWidthPx.toDp() }
        fun targetCellAt(pointer: HomePointer): HomeGridCell? = boardBounds.value
            ?.takeIf { board ->
                pointer.x in board.left..board.right &&
                    pointer.y in board.top..board.bottom
            }
            ?.let { board ->
                homeGridCellAt(
                    pointer = pointer,
                    board = board,
                    cellWidthPx = cellWidthPx.toFloat(),
                    gapPx = gapPx.toFloat(),
                    columns = gridPlan.columns,
                )
            }

        fun folderTargetPlacementAt(
            pointer: HomePointer,
            draggedId: String,
        ): HomeGridPlacement? {
            val board = boardBounds.value ?: return null
            return gridPlan.placements.firstOrNull { placement ->
                val item = itemById[placement.id]
                placement.id != draggedId &&
                    (item is HomeItem.App || item is HomeItem.Folder) &&
                    homeFolderDropHitZone(
                        pointer = pointer,
                        board = board,
                        placement = placement,
                        cellWidthPx = cellWidthPx.toFloat(),
                        gapPx = gapPx.toFloat(),
                        centralFraction = 1f,
                    )
            }
        }

        fun folderDecisionAt(
            pointer: HomePointer,
            draggedId: String,
        ): HomeFolderDropDecision {
            val activeId = folderDropCandidateId
            val hovered = folderTargetPlacementAt(pointer, draggedId)
            return homeFolderDropDecision(
                pointer = pointer,
                board = boardBounds.value,
                activeTargetPlacement = activeId?.let(gridPlan::placementOf),
                activeTargetId = activeId,
                hoveredTargetPlacement = hovered,
                hoveredTargetId = hovered?.id,
                draggedId = draggedId,
                cellWidthPx = cellWidthPx.toFloat(),
                gapPx = gapPx.toFloat(),
            )
        }

        fun reorderForPointer(
            pointer: HomePointer,
            draggedId: String,
            workingOrder: List<String>,
        ): List<String> {
            val targetCell = targetCellAt(pointer) ?: run {
                reorderCandidateCache.invalidate()
                return workingOrder
            }
            return reorderCandidateCache.getOrCompute(
                draggedId = draggedId,
                targetCell = targetCell,
                workingOrder = workingOrder,
                itemSizes = itemSizesById,
                columns = gridPlan.columns,
                rows = fixedCanvasRows,
                horizontal = isHorizontalStartCanvas,
            ) {
                if (isHorizontalStartCanvas) {
                    reorderHorizontalHomeOrderForTargetCell(
                        order = workingOrder,
                        draggedId = draggedId,
                        targetCell = targetCell,
                        itemSizes = itemSizesById,
                        rows = fixedCanvasRows,
                    )
                } else {
                    reorderHomeOrderForTargetCell(
                        order = workingOrder,
                        draggedId = draggedId,
                        targetCell = targetCell,
                        itemSizes = itemSizesById,
                        columns = gridPlan.columns,
                    )
                }
            }
        }

        val processPointerMove: (String, HomePointer) -> Unit = { itemId, pointer ->
            val session = activeDragSession.value
            if (homeDragCoordinator.isOwner(session) && session?.itemId == itemId) {
                currentOnEdgeAutoScroll.value(pointer)
                val workingOrder = previewState.order
                val folderDecision = if (itemById[itemId] is HomeItem.App) {
                    folderDecisionAt(pointer, itemId)
                } else {
                    HomeFolderDropDecision(HomeFolderDropMode.REORDER)
                }
                folderDropCandidateId = folderDecision.targetId
                folderDropTargetId = folderDecision
                    .takeIf { it.mode == HomeFolderDropMode.MERGE }
                    ?.targetId
                if (folderDecision.mode == HomeFolderDropMode.REORDER) {
                    val candidateOrder = reorderForPointer(pointer, itemId, workingOrder)
                    val stabilizedOrder = previewState.preview(
                        candidateOrder = candidateOrder,
                        pointer = pointer,
                        hysteresisRadius = dragState.reorderHysteresisRadius,
                    )
                    if (stabilizedOrder != workingOrder) {
                        currentOnPreviewOrder.value(stabilizedOrder)
                    }
                } else {
                    // Hold the source over a valid target so dense reflow cannot move the target
                    // away before release. The target highlight gives the user a clear drop cue.
                    reorderCandidateCache.invalidate()
                }
            }
        }
        edgeDragReevaluation?.let { holder ->
            SideEffect {
                holder.callback = {
                    if (isHorizontalStartCanvas) {
                        val draggedId = dragState.draggedId
                        val pointer = dragState.pointer
                        if (draggedId != null && pointer != null) {
                            processPointerMove(draggedId, pointer)
                        }
                    }
                }
            }
            androidx.compose.runtime.DisposableEffect(holder) {
                onDispose {
                    holder.callback = null
                }
            }
        }
        // Pages next to the visible one stay composed so a swipe never waits on composing them;
        // media on those hidden pages pauses until the page is shown.
        CompositionLocalProvider(LocalHomeBoardVisible provides isVisible) {
        HomeGridLayout(
            plan = gridPlan,
            cellWidthPx = cellWidthPx,
            gapPx = gapPx,
            visiblePlacements = visiblePlacements,
            modifier = Modifier
                .then(if (isHorizontalStartCanvas) Modifier.width(boardWidth) else Modifier.fillMaxWidth())
                .height(boardHeight)
                .onGloballyPositioned { coordinates ->
                    val position = coordinates.positionInRoot()
                    val size = coordinates.size
                    homeFloat?.boardOrigin = position
                    boardBounds.value = HomeItemBounds(
                        id = "home-board",
                        left = position.x,
                        top = position.y,
                        right = position.x + size.width,
                        bottom = position.y + size.height,
                    )
                },
        ) { placement ->
                val item = itemById[placement.id] ?: return@HomeGridLayout
                val appContentMode = if (item is HomeItem.App) {
                    appTileContentModeFor(item.id, appTileContentModes)
                } else {
                    AppTileContentMode.SHORTCUTS
                }
                val shortcutsForItem = if (
                    item is HomeItem.App &&
                        appContentMode == AppTileContentMode.SHORTCUTS &&
                        appTileSizes[item.id]?.supportsShortcuts() == true
                ) {
                    appShortcuts[item.id].orEmpty()
                } else {
                    emptyList()
                }
                val notificationsForItem = if (item is HomeItem.App) {
                    notificationByPackage[item.app.notificationPackageKey()].orEmpty()
                } else {
                    emptyList()
                }
                val hasShortcutPresentation = item is HomeItem.App && shortcutsForItem.isNotEmpty()
                val hasLiveNotificationPresentation = item is HomeItem.App &&
                    appContentMode == AppTileContentMode.NOTIFICATIONS &&
                    notificationTileCapacity(appTileSizes[item.id] ?: AppTileSize.SMALL) > 0 &&
                    notificationsForItem.isNotEmpty()
                val folderApps = if (item is HomeItem.Folder) {
                    item.folder.memberIds.mapNotNull(appsById::get)
                } else {
                    emptyList()
                }
                val hasFolderAppChildren = item is HomeItem.Folder &&
                    item.folder.size == HomeFolderSize.LARGE &&
                    folderApps.isNotEmpty()
                val hasInteractiveAppChildren =
                    hasShortcutPresentation || hasLiveNotificationPresentation || hasFolderAppChildren
                val itemBoundsSync = remember(item.id) { HomeDragBoundsSyncHolder() }
                val folderBounds = remember(item.id) { LatestFolderBounds() }
                val openFolder = {
                    if (item is HomeItem.Folder) {
                        homeFolderOpenBounds(
                            latestBounds = itemBoundsSync.boundsCallback?.invoke(),
                            publishedBounds = folderBounds.value,
                        )?.let { bounds -> onFolderOpen(item.folder, bounds) }
                    }
                }
                val externalWidgetHostBounds = remember(item.id) {
                    LatestHomeItemBounds()
                }
                val pointerIsInsideExternalHost: (HomePointer?) -> Boolean = { pointer ->
                    if (pointer == null) {
                        false
                    } else {
                        val bounds = externalWidgetHostBounds.value
                        // A missing/error host is launcher-owned. This keeps EmptyPanel and
                        // transient host failures removable/reorderable instead of swallowing
                        // their long press as an unavailable child gesture.
                        bounds != null && (
                            pointer.x in bounds.left..bounds.right &&
                                pointer.y in bounds.top..bounds.bottom
                            )
                    }
                }
                val onDragStart: (Long) -> HomeDragSession? = { generation ->
                    acquireDrag(item.id, generation)
                }
                val onPointerMove: (HomePointer) -> Unit = { pointer ->
                    processPointerMove(item.id, pointer)
                }
                val onDrop: (HomePointer) -> Boolean = { pointer ->
                    val session = activeDragSession.value
                    if (!homeDragCoordinator.isOwner(session) || session?.itemId != item.id) {
                        false
                    } else {
                        val folderDecision = if (item is HomeItem.App) {
                            folderDecisionAt(pointer, item.id)
                        } else {
                            HomeFolderDropDecision(HomeFolderDropMode.REORDER)
                        }
                        val targetCell = targetCellAt(pointer)
                        if (targetCell != null) {
                            // Reuse the exact spatial decision from pointer-move. A release in a
                            // target's outer band is a reorder, so compute its current-cell
                            // candidate even though preview was held while approaching the tile.
                            val folderTargetId = folderDecision
                                .takeIf { it.mode == HomeFolderDropMode.MERGE }
                                ?.targetId
                            val committedOrder = if (folderTargetId == null) {
                                reorderForPointer(pointer, item.id, previewState.order)
                            } else {
                                previewState.order
                            }
                            currentOnEdgeAutoScroll.value(null)
                            previewState.endDrag()
                            reorderCandidateCache.invalidate()
                            homeDragCoordinator.release(session)
                            activeDragSession.value = null
                            folderDropTargetId = null
                            folderDropCandidateId = null
                            if (folderTargetId != null) {
                                val accepted = onFolderDrop(item.id, folderTargetId)
                                if (!accepted) {
                                    resetLocalPreview()
                                    currentOnCancelOrder.value()
                                }
                                accepted
                            } else {
                                currentOnCommitOrder.value(committedOrder)
                                true
                            }
                        } else {
                            cancelActiveDrag()
                            false
                        }
                    }
                }
                val onCancel = { cancelDragForItem(item.id) }
                val accessibilityReorderActions = listOf(
                    CustomAccessibilityAction(tr("前へ移動", "Move earlier")) {
                        val base = previewState.order
                        val updated = moveHomeOrderBy(base, item.id, -1)
                        if (updated == base) {
                            false
                        } else {
                            previewState.endDrag()
                            previewState.update(updated)
                            reorderCandidateCache.invalidate()
                            currentOnCommitOrder.value(updated)
                            true
                        }
                    },
                    CustomAccessibilityAction(tr("次へ移動", "Move later")) {
                        val base = previewState.order
                        val updated = moveHomeOrderBy(base, item.id, 1)
                        if (updated == base) {
                            false
                        } else {
                            previewState.endDrag()
                            previewState.update(updated)
                            reorderCandidateCache.invalidate()
                            currentOnCommitOrder.value(updated)
                            true
                        }
                    },
                )
                val canInvokeLongPress: (HomeDragSession?) -> Boolean = { session ->
                    homeDragCoordinator.isOwner(session) && session?.itemId == item.id
                }
                val widgetPointerFromLocal: (Float, Float) -> HomePointer? = { x, y ->
                    externalWidgetHostBounds.value?.let { bounds ->
                        HomePointer(bounds.left + x, bounds.top + y)
                            .takeIf { it.x.isFinite() && it.y.isFinite() }
                    }
                }
                val onWidgetLongPressAccepted: (Float, Float, Long) -> Boolean = { x, y, generation ->
                    // AndroidView owns this timeout, so the HomeDragItem pointer loop cannot
                    // perform its normal pre-begin synchronization. Use the outer tile's logical
                    // coordinates rather than the inset host bounds for the drag footprint.
                    val boundsSynchronized = itemBoundsSync.callback?.invoke() == true
                    val pointer = widgetPointerFromLocal(x, y)
                    val session = pointer?.let {
                        homeDragCoordinator.acquire(
                            homePage = homePageIndex,
                            itemId = item.id,
                            expectedGeneration = generation,
                        )
                    }
                    if (!boundsSynchronized || pointer == null || session == null ||
                        !dragState.begin(item.id, pointer, homeTouchSlop)
                    ) {
                        session?.let(homeDragCoordinator::release)
                        dragState.cancel()
                        onCancel()
                        false
                    } else {
                        activeDragSession.value = session
                        previewState.beginDrag()
                        reorderCandidateCache.invalidate()
                        true
                    }
                }
                val onWidgetPointerMove: (Float, Float) -> Unit = { x, y ->
                    val pointer = widgetPointerFromLocal(x, y)
                    val session = activeDragSession.value
                    if (pointer == null ||
                        !homeDragCoordinator.isOwner(session) ||
                        session?.itemId != item.id
                    ) {
                        dragState.cancel()
                        onCancel()
                    } else if (dragState.updatePointer(pointer)) {
                        onPointerMove(pointer)
                    }
                }
                val onWidgetPointerUp: (Float, Float) -> Boolean = { x, y ->
                    val pointer = widgetPointerFromLocal(x, y)
                    val session = activeDragSession.value
                    if (pointer == null ||
                        !homeDragCoordinator.isOwner(session) ||
                        session?.itemId != item.id
                    ) {
                        dragState.cancel()
                        onCancel()
                        false
                    } else {
                        if (dragState.updatePointer(pointer)) onPointerMove(pointer)
                        val result = dragState.finish(item.id)
                        when {
                            result == null -> {
                                onCancel()
                                false
                            }
                            !result.didMove -> {
                                if (item is HomeItem.ExternalWidget &&
                                    homeDragCoordinator.isOwner(session) &&
                                    session.itemId == item.id
                                ) {
                                    onLongPressWidget(item)
                                }
                                onCancel()
                                false
                            }
                            else -> {
                                val dropped = onDrop(pointer)
                                if (!dropped) onCancel()
                                dropped
                            }
                        }
                    }
                }
                val onWidgetGestureCancel = {
                    dragState.cancel()
                    onCancel()
                }
                var wasDragged by remember(item.id) { mutableStateOf(false) }
                val isDragged = dragState.draggedId == item.id
                val justReleased = !isDragged && wasDragged
                val stridePx = cellWidthPx + gapPx
                val targetOffset = IntOffset(
                    x = placement.column * stridePx,
                    y = placement.row * stridePx,
                )
                if (homeFloat != null) {
                    SideEffect {
                        homeFloat.simulation.place(
                            id = item.id,
                            centerX = targetOffset.x + (placement.columnSpan * stridePx - gapPx) / 2f,
                            centerY = targetOffset.y + (placement.rowSpan * stridePx - gapPx) / 2f,
                        )
                    }
                    androidx.compose.runtime.DisposableEffect(homeFloat, item.id) {
                        onDispose { homeFloat.simulation.remove(item.id) }
                    }
                }
                val animatedTargetOffset by animateIntOffsetAsState(
                    targetValue = targetOffset,
                    animationSpec = if (isDragged || justReleased) {
                        snap()
                    } else {
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        )
                    },
                    label = "home placement ${item.id}",
                )
                SideEffect {
                    wasDragged = isDragged
                }
                val visualPlacementOffset = if (isDragged) {
                    IntOffset.Zero
                } else {
                    IntOffset(
                        x = animatedTargetOffset.x - targetOffset.x,
                        y = animatedTargetOffset.y - targetOffset.y,
                    )
                }
                HomeDragItem(
                    id = item.id,
                    state = dragState,
                    placementModifier = Modifier,
                    visualPlacementModifier = Modifier
                        .offset { visualPlacementOffset }
                        .then(
                            if (homeFloat != null) {
                                // A tile under reorder follows the finger, so no tile floats then.
                                Modifier.homeFloatOffset(homeFloat, item.id, enabled = dragState.draggedId == null)
                            } else {
                                Modifier
                            },
                        )
                        .then(
                            if (folderDropTargetId == item.id) {
                                Modifier.border(2.dp, FiiLDACyan, LauncherTileShape)
                            } else {
                                Modifier
                            },
                        ),
                    // App semantics intentionally retain the existing merged tile node. Widget
                    // wrappers expose the size state and action entry point; nested provider and
                    // FiiLDATile semantics remain independently discoverable.
                    accessibilityLabel = when (item) {
                        is HomeItem.App -> item.app.label
                        is HomeItem.Widget -> item.widget.label
                        is HomeItem.ExternalWidget -> item.descriptor.label.ifBlank { tr("ウィジェット", "Widget") }
                        is HomeItem.PinnedShortcut -> item.shortcut.label
                        is HomeItem.Folder -> item.folder.name
                        is HomeItem.WebLink -> item.link.label.ifBlank { defaultWebLinkLabel(item.link.url) }
                    },
                    // Keep the outer app node for reorder/long-press, but expose every clickable
                    // child of a populated shortcut presentation as its own TalkBack action.
                    mergeAccessibilityDescendants = !hasInteractiveAppChildren,
                    accessibilityLongPressLabel = when (item) {
                        is HomeItem.App -> tr("アプリ操作", "App actions")
                        is HomeItem.PinnedShortcut -> tr("ショートカット操作", "Shortcut actions")
                        is HomeItem.Folder -> tr("フォルダ操作", "Folder actions")
                        is HomeItem.WebLink -> tr("Webリンク操作", "Web link actions")
                        else -> tr("ウィジェット操作", "Widget actions")
                    },
                    accessibilityClickLabel = if (item is HomeItem.Widget && item.widget == HomeWidget.PHOTO) {
                        tr("画像または動画を表示", "Show image or video")
                    } else if (item is HomeItem.PinnedShortcut) {
                        tr("ショートカットを開く", "Open shortcut")
                    } else if (item is HomeItem.WebLink) {
                        tr("リンクを開く", "Open link")
                    } else if (item is HomeItem.Folder) {
                        tr("フォルダを開く", "Open folder")
                    } else {
                        tr("アプリを開く", "Open app")
                    },
                    accessibilityStateDescription = when (item) {
                        is HomeItem.App -> buildString {
                            append(tr("ホーム ${items.indexOfFirst { candidate -> candidate.id == item.id } + 1}番目", "Home item ${items.indexOfFirst { candidate -> candidate.id == item.id } + 1}"))
                            if (notificationsForItem.isNotEmpty()) {
                                append(tr("。通知 ${notificationsForItem.size}件", ". ${notificationsForItem.size} notifications"))
                            } else if (appContentMode == AppTileContentMode.NOTIFICATIONS) {
                                append(tr("。通知ライブ、通知なし", ". Live notifications, none"))
                            }
                        }
                        is HomeItem.Widget -> tr("サイズ ${widgetSizeLabel(item.id, widgetSizeOverrides, builtIn = true)}", "Size ${widgetSizeLabel(item.id, widgetSizeOverrides, builtIn = true)}")
                        is HomeItem.ExternalWidget -> tr("サイズ ${widgetSizeLabel(item.id, widgetSizeOverrides, builtIn = false)}", "Size ${widgetSizeLabel(item.id, widgetSizeOverrides, builtIn = false)}")
                        is HomeItem.PinnedShortcut -> tr("ホーム ${items.indexOfFirst { candidate -> candidate.id == item.id } + 1}番目", "Home item ${items.indexOfFirst { candidate -> candidate.id == item.id } + 1}")
                        is HomeItem.Folder -> tr("サイズ ${item.folder.size.label}", "Size ${item.folder.size.label}")
                        is HomeItem.WebLink -> tr("Webリンク、${item.link.url}", "Web link, ${item.link.url}")
                    },
                    accessibilityReorderActions = accessibilityReorderActions,
                    onClick = when (item) {
                        // A populated split tile has its own exclusive child targets. Leaving the
                        // outer tap action installed would make a subdued empty cell launch the
                        // app, and would also duplicate the top-left TalkBack action.
                        is HomeItem.App -> if (hasInteractiveAppChildren) {
                            null
                        } else {
                            { onOpenApp(item.app) }
                        }
                        is HomeItem.Widget -> if (item.widget == HomeWidget.PHOTO) {
                            { onPhotoPreview(item.id) }
                        } else {
                            null
                        }
                        is HomeItem.ExternalWidget -> null
                        is HomeItem.PinnedShortcut -> { { onOpenPinnedShortcut(item.shortcut) } }
                        // Nested app cells consume their own taps; the outer action remains the
                        // fallback for empty/background cells and accessibility activation.
                        is HomeItem.Folder -> openFolder
                        is HomeItem.WebLink -> { { openWebLink(context, item.link) } }
                    },
                    shouldStartDrag = if (item is HomeItem.ExternalWidget) {
                        { pointer -> !pointerIsInsideExternalHost(pointer) }
                    } else {
                        null
                    },
                    dragGeneration = { homeDragCoordinator.currentGeneration },
                    canInvokeLongPress = canInvokeLongPress,
                    onLongPress = when (item) {
                        is HomeItem.App -> { _ -> onLongPressApp(item.app) }
                        is HomeItem.Widget -> { _ -> onLongPressWidget(item) }
                        // HomeDragItem only invokes this callback after its launcher-owned
                        // pointer-down path has latched ownership. Do not re-hit-test on release:
                        // a small sub-slop move into the AndroidView must not hide the menu that
                        // belongs to the border/outside-host long press.
                        is HomeItem.ExternalWidget -> { _ -> onLongPressWidget(item) }
                        is HomeItem.PinnedShortcut -> { _ -> onLongPressPinnedShortcut(item.shortcut) }
                        is HomeItem.Folder -> { _ -> onLongPressFolder(item.folder) }
                        is HomeItem.WebLink -> { _ -> onLongPressWidget(item) }
                    },
                    onDragStart = onDragStart,
                    onPointerMove = onPointerMove,
                    onDrop = onDrop,
                    onCancel = onCancel,
                    childLongPressBoundsSync = itemBoundsSync,
                    hiddenFromInteraction = item is HomeItem.Folder && item.id == expandedFolderId,
                    onBoundsChanged = if (item is HomeItem.Folder) {
                        { bounds -> folderBounds.value = bounds }
                    } else {
                        null
                    },
                ) {
                    when (item) {
                        is HomeItem.App -> AppTile(
                            app = item.app,
                            posture = posture,
                            selected = item.app.packageIdentity() == selectedPackage,
                            // The cyan frame is a board motif on the home surface, not a
                            // persistent marker for whichever app was opened last.
                            showSelectedBorder = false,
                            patternAccent = homePatternAccent(
                                column = placement.column,
                                row = placement.row,
                                homePageIndex = homePageIndex,
                            ),
                            size = appTileSizes[item.id] ?: AppTileSize.SMALL,
                            exactGridFootprint = true,
                            contentMode = appContentMode,
                            shortcuts = shortcutsForItem,
                            notifications = notificationsForItem,
                            cellSize = cellWidth,
                            interactive = false,
                            // The outer HomeDragItem owns board gestures, while the populated
                            // large grid owns short taps. Keep this callback on the AppTile so
                            // its top-left child can launch the app without re-enabling a parent
                            // catch-all tap on empty cells.
                            onClick = { onOpenApp(item.app) },
                            onShortcutClick = { shortcut ->
                                onOpenShortcut(item.app, shortcut)
                            },
                            onNotificationClick = { notification ->
                                if (!sendNotificationContentIntent(notification)) {
                                    Toast.makeText(
                                        context,
                                        tr("通知を開けませんでした", "Couldn't open the notification"),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        )

                        is HomeItem.Widget -> {
                            val gridSize = homeItemSize(
                                item = item,
                                columns = columns,
                                appTileSizes = appTileSizes,
                                externalWidgetSizes = externalWidgetSizes,
                                widgetSizeOverrides = widgetSizeOverrides,
                            ).let { GridItemSize(it.columnSpan, it.rowSpan) }
                            HomeWidgetTile(
                                widget = item.widget,
                                now = now,
                                posture = posture,
                                gridSize = WidgetGridSize(gridSize.columnSpan, gridSize.rowSpan),
                                battery = battery,
                            onWeather = onWeather,
                            onCalendar = onCalendar,
                            onMedia = onMedia,
                            photoUri = photoUris[item.id],
                            isVideoMuted = photoVideoMutes[item.id] ?: true,
                            onVideoMuteChanged = { muted ->
                                onPhotoMuteChanged(item.id, muted)
                            },
                            mediaState = mediaState,
                                onOpenMediaSettings = onOpenMediaSettings,
                            )
                        }

                        is HomeItem.ExternalWidget -> ExternalWidgetTile(
                            descriptor = item.descriptor,
                            appWidgetHost = appWidgetHost,
                            appWidgetManager = appWidgetManager,
                            gridSize = resolveWidgetGridSize(
                                choice = widgetSizeOverrides[item.id],
                                providerSize = externalWidgetSizes[item.id] ?: WidgetGridSize(1, 1),
                                columns = columns,
                                builtIn = false,
                            ),
                            sizeLabel = widgetSizeLabel(
                                id = item.id,
                                overrides = widgetSizeOverrides,
                                builtIn = false,
                            ),
                            cellWidth = cellWidth,
                            gap = gap,
                            onLongPressAccepted = onWidgetLongPressAccepted,
                            onGestureStarted = { homeDragCoordinator.currentGeneration },
                            onPointerMove = onWidgetPointerMove,
                            onPointerUp = onWidgetPointerUp,
                            onGestureCancel = onWidgetGestureCancel,
                            onGestureEnabled = {
                                externalWidgetHostBounds.value != null
                            },
                            onHostBoundsChanged = { bounds ->
                                externalWidgetHostBounds.value = bounds
                            },
                            glassSceneEnabled = glassSceneEnabled && isVisible,
                            // Root-level scene scope owns transition alpha/z/geometry. Keep the
                            // native fallback contributor's local values neutral so they are not
                            // multiplied twice when HomeSurface is nested in that scope.
                            glassSceneAlpha = 1f,
                            glassSceneZIndex = 0f,
                            glassSceneGeometryVersion = null,
                        )

                        is HomeItem.PinnedShortcut -> PinnedShortcutTile(
                            shortcut = item.shortcut,
                        )

                        is HomeItem.WebLink -> WebLinkTileView(
                            link = item.link,
                            posture = posture,
                            size = homeItemSize(
                                item = item,
                                columns = MaxTileColumnSpan,
                                appTileSizes = appTileSizes,
                                widgetSizeOverrides = widgetSizeOverrides,
                            ),
                        )

                        is HomeItem.Folder -> FolderTile(
                            folder = item.folder,
                            apps = folderApps,
                            directLaunchEnabled = hasFolderAppChildren,
                            onOpenApp = onOpenApp,
                            onOpenFolder = openFolder,
                        )
                    }
                }
            }
        }
    }
}

internal enum class HomePressOutcome { TAP, CANCEL, LONG_PRESS }

/**
 * Classifies one event from the pre-long-press gesture loop.
 *
 * The loop observes the Final pass so that an ancestor scroll detector has already had a chance
 * to consume movement. Consumption and touch-slop movement must win over an UP in the same event;
 * otherwise a scroll release can be mistaken for a tap.
 */
internal fun classifyHomePressEvent(
    pointerChangedToUp: Boolean,
    pointerChangeConsumed: Boolean,
    movedBeyondTouchSlop: Boolean,
): HomePressOutcome? = when {
    // A child clickable may consume DOWN while it tracks a possible tap. That must not prevent
    // the board from recognizing a stationary long press; a consumed release still cancels the
    // board tap so the child remains the sole owner of the short gesture.
    (pointerChangedToUp && pointerChangeConsumed) || movedBeyondTouchSlop -> HomePressOutcome.CANCEL
    pointerChangedToUp -> HomePressOutcome.TAP
    else -> null
}

/**
 * Converts a pointer-local position through the same layout node that receives the pointer.
 * LayoutCoordinates can become detached during placement/posture changes, so a failed
 * conversion is treated as an invalid gesture position rather than committing stale state.
 */
private fun LayoutCoordinates.homePointerInRoot(localPosition: Offset): HomePointer? {
    if (!isAttached) return null
    return runCatching { localToRoot(localPosition) }
        .getOrNull()
        ?.takeIf { it.x.isFinite() && it.y.isFinite() }
        ?.let { HomePointer(it.x, it.y) }
}

/** Reads a tile's current logical bounds only when a gesture is about to use them. */
private fun LayoutCoordinates.homeBoundsInRoot(id: String): HomeItemBounds? {
    if (!isAttached) return null
    return runCatching {
        val position = positionInRoot()
        val size = size
        HomeItemBounds(
            id = id,
            left = position.x,
            top = position.y,
            right = position.x + size.width,
            bottom = position.y + size.height,
        )
    }.getOrNull()
}

/**
 * One HomeBoard item owns the complete tap/long-press/drag gesture. Keeping it outside AppTile
 * lets widget internals keep their own click targets while the board consumes movement only after
 * a long press has actually succeeded.
 */
@Composable
private fun HomeDragItem(
    id: String,
    state: HomeDragState,
    placementModifier: Modifier,
    visualPlacementModifier: Modifier = Modifier,
    accessibilityLabel: String?,
    mergeAccessibilityDescendants: Boolean = true,
    accessibilityLongPressLabel: String = tr("アプリ操作", "App actions"),
    accessibilityClickLabel: String = tr("アプリを開く", "Open app"),
    accessibilityStateDescription: String?,
    accessibilityReorderActions: List<CustomAccessibilityAction> = emptyList(),
    onClick: (() -> Unit)?,
    shouldStartDrag: ((HomePointer?) -> Boolean)? = null,
    dragGeneration: () -> Long,
    canInvokeLongPress: (HomeDragSession?) -> Boolean,
    onLongPress: ((HomePointer?) -> Unit)?,
    onDragStart: (Long) -> HomeDragSession?,
    onPointerMove: (HomePointer) -> Unit,
    onDrop: (HomePointer) -> Boolean,
    onCancel: () -> Unit,
    childLongPressBoundsSync: HomeDragBoundsSyncHolder? = null,
    hiddenFromInteraction: Boolean = false,
    onBoundsChanged: ((IntRect) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val isWindows8 = LocalLauncherTheme.current == LauncherTheme.WINDOWS_8
    // The dragged item moves through an ancestor graphics layer without layout. Its optical
    // surface and sharp scene record follow that movement through a draw-time signal, so pointer
    // moves redraw the glass without recomposing the item.
    val glassEnabledForDrag = LocalLauncherGlass.current.enabled
    val glassDragGeometry = if (glassEnabledForDrag) {
        remember(state, id) { GlassGeometrySignal { state.translationFor(id) } }
    } else {
        null
    }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentDragGeneration by rememberUpdatedState(dragGeneration)
    val currentCanInvokeLongPress by rememberUpdatedState(canInvokeLongPress)
    val currentShouldStartDrag by rememberUpdatedState(shouldStartDrag)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnPointerMove by rememberUpdatedState(onPointerMove)
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentOnCancel by rememberUpdatedState(onCancel)
    val itemCoordinates = remember { LatestHomeLayoutCoordinates() }
    val pointerCoordinates = remember { LatestHomeLayoutCoordinates() }
    childLongPressBoundsSync?.let { holder ->
        SideEffect {
            holder.callback = {
                // Sync the visual child rather than the logical outer cell. During Spring reflow
                // the child is the surface the finger actually grabbed, so using its current
                // coordinates preserves the grab offset for both standard and widget tiles.
                pointerCoordinates.value
                    ?.homeBoundsInRoot(id)
                    ?.let {
                        state.updateBounds(it)
                        true
                    }
                    ?: false
            }
            holder.boundsCallback = {
                fun currentBounds(coordinates: LayoutCoordinates?): IntRect? =
                    coordinates?.homeBoundsInRoot(id)?.let { bounds ->
                        IntRect(
                            left = bounds.left.roundToInt(),
                            top = bounds.top.roundToInt(),
                            right = bounds.right.roundToInt(),
                            bottom = bounds.bottom.roundToInt(),
                        )
                    }
                currentBounds(pointerCoordinates.value) ?: currentBounds(itemCoordinates.value)
            }
        }
        androidx.compose.runtime.DisposableEffect(holder) {
            onDispose {
                holder.callback = null
                holder.boundsCallback = null
            }
        }
    }
    val accessibilityModifier = if (hiddenFromInteraction) {
        Modifier.clearAndSetSemantics {}
    } else if (
        accessibilityLabel == null && onLongPress == null && accessibilityReorderActions.isEmpty()
    ) {
        Modifier
    } else {
        Modifier.semantics(mergeDescendants = mergeAccessibilityDescendants) {
            if (accessibilityLabel != null) {
                contentDescription = accessibilityLabel
                if (onClick != null) {
                    role = Role.Button
                    onClick(label = accessibilityClickLabel) {
                        currentOnClick?.let {
                            it()
                            true
                        } ?: false
                    }
                }
            }
            accessibilityStateDescription?.let { stateDescription = it }
            if (accessibilityReorderActions.isNotEmpty()) {
                customActions = accessibilityReorderActions
            }
            if (onLongPress != null) {
                onLongClick(label = accessibilityLongPressLabel) {
                    currentOnLongPress?.let { callback ->
                        callback(null)
                        true
                    } ?: false
                }
            }
        }
    }

    Box(
        modifier = placementModifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (hiddenFromInteraction) 0f else 1f }
            .onGloballyPositioned { coordinates ->
                itemCoordinates.value = coordinates
                onBoundsChanged?.invoke(
                    coordinates.homeBoundsInRoot(id)?.let { bounds ->
                        IntRect(
                            left = bounds.left.roundToInt(),
                            top = bounds.top.roundToInt(),
                            right = bounds.right.roundToInt(),
                            bottom = bounds.bottom.roundToInt(),
                        )
                    } ?: return@onGloballyPositioned,
                )
                // Non-dragged tiles only refresh this plain coordinate holder. Construct and
                // publish bounds while scrolling for the active tile alone so its graphics layer
                // keeps following the finger without a per-pixel map update for every tile.
                if (state.draggedId == id) {
                    coordinates.homeBoundsInRoot(id)?.let(state::updateBounds)
                }
            }
            .zIndex(if (state.draggedId == id) 2f else 0f)
            .then(accessibilityModifier)
    ) {
        // Keep measurement on the outer layout, before this visual layer. That means a translated
        // item can be remeasured while placement animation runs without feeding its own
        // translation back into the grab offset calculation.
        Box(
            modifier = Modifier
                // Reflow animation belongs inside the measured outer box. The outer bounds stay
                // on logical cells for hit testing while this layer follows the presentation
                // spring; that prevents animated bounds from becoming the next drag candidate.
                .then(visualPlacementModifier)
                // This coordinate observer and pointerInput belong to the same element. Convert
                // every pointer position with these coordinates, rather than rebuilding root
                // coordinates from the outer logical-cell bounds while placement is in flight.
                .onGloballyPositioned { coordinates ->
                    pointerCoordinates.value = coordinates
                    onBoundsChanged?.invoke(
                        coordinates.homeBoundsInRoot(id)?.let { bounds ->
                            IntRect(
                                left = bounds.left.roundToInt(),
                                top = bounds.top.roundToInt(),
                                right = bounds.right.roundToInt(),
                                bottom = bounds.bottom.roundToInt(),
                            )
                        } ?: return@onGloballyPositioned,
                    )
                }
                .pointerInput(id) {
                var acceptedGesture = false
                var gestureEnded = false
                var childOwnsGesture = false
                try {
                    awaitEachGesture {
                        acceptedGesture = false
                        gestureEnded = false
                        childOwnsGesture = false
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Main,
                        )
                        val gestureGeneration = currentDragGeneration()
                        val pointerAtDown = pointerCoordinates.value
                            ?.homePointerInRoot(down.position)
                        val childOwnsFromDown =
                            currentShouldStartDrag?.invoke(pointerAtDown) == false
                        childOwnsGesture = childOwnsFromDown
                        if (childOwnsFromDown) {
                            // AndroidView owns the complete body gesture. Observe until the
                            // provider finishes without consuming anything, leaving its tap,
                            // scroll, and the host-level long-press bridge independent from this
                            // Compose reorder detector.
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                if (event.changes.none { it.pressed }) break
                            }
                            return@awaitEachGesture
                        }
                        var lastPosition = down.position
                        val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                // The parent verticalScroll consumes movement during Main. Read
                                // the Final pass so that consumption is visible before deciding
                                // whether this release is a tap.
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                if (event.changes.any { it.id != down.id && it.pressed }) {
                                    return@withTimeoutOrNull HomePressOutcome.CANCEL
                                }
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: return@withTimeoutOrNull HomePressOutcome.CANCEL
                                lastPosition = change.position
                                // Compare with the original down point, not only this event's
                                // delta. Slow cumulative movement must cancel long-press just as
                                // a single fast move does.
                                val eventOutcome = classifyHomePressEvent(
                                    pointerChangedToUp = change.changedToUpIgnoreConsumed(),
                                    pointerChangeConsumed = change.isConsumed,
                                    movedBeyondTouchSlop =
                                        (change.position - down.position).getDistance() >
                                            viewConfiguration.touchSlop,
                                )
                                if (eventOutcome != null) {
                                    return@withTimeoutOrNull eventOutcome
                                }
                            }
                        } ?: HomePressOutcome.LONG_PRESS

                        if (outcome == HomePressOutcome.LONG_PRESS) {
                            // Once the timeout wins, this pointer belongs to the launcher until
                            // its UP/cancel. Even if another observer cancels HomeDragState in
                            // the meantime, keep the local gesture alive to consume the release
                            // before a FiiLDATile/AppWidget child can turn it into a click.
                            acceptedGesture = true
                            // change.position is local to this pointerInput node. The node's
                            // LayoutCoordinates keep this conversion tied to the physical item,
                            // even when its visual placement is being animated.
                            val pointer = pointerCoordinates.value
                                ?.homePointerInRoot(lastPosition)
                            // Ownership is latched at DOWN. Layout/host bounds may move while the
                            // timeout is pending, but that must not switch a provider gesture into
                            // a launcher gesture (or vice versa) halfway through the touch.
                            val shouldStartDrag = !childOwnsGesture
                            // Synchronize the current visual rectangle immediately before begin.
                            // Non-dragged tiles are not tracked on every scroll frame, but the
                            // long-press path always has a fresh bounds entry to use for the grab
                            // offset. A detached visual node cancels instead of manufacturing a
                            // (0, 0) pointer that could start a drag in the wrong cell.
                            val boundsSynchronized = pointerCoordinates.value
                                ?.homeBoundsInRoot(id)
                                ?.let {
                                    state.updateBounds(it)
                                    true
                                } == true
                            var dragCancelled = !shouldStartDrag ||
                                !boundsSynchronized ||
                                pointer == null
                            if (!dragCancelled) {
                                dragCancelled = pointer?.let {
                                    !state.begin(id, it, viewConfiguration.touchSlop)
                                } ?: true
                            }
                            val acceptedDragSession = if (!dragCancelled) {
                                currentOnDragStart(gestureGeneration)
                            } else {
                                null
                            }
                            if (!dragCancelled) {
                                if (acceptedDragSession != null) {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                } else {
                                    // Another home pane already owns the drag. Cancel this
                                    // local state without invoking the owner's preview callback.
                                    state.cancel()
                                    dragCancelled = true
                                    currentOnCancel()
                                }
                            } else if (!childOwnsGesture) {
                                // A detached node or an already-active board drag cannot own the
                                // reorder state, but it still must shield the child through UP.
                                currentOnCancel()
                            }

                            while (true) {
                                if (!dragCancelled && state.draggedId != id) {
                                    dragCancelled = true
                                    currentOnCancel()
                                }
                                // The accepted drag must observe the Initial pass so consumption
                                // reaches widget/built-in children before their Main-pass click
                                // handlers see the release. Short taps never enter this loop and
                                // therefore remain fully child-interactive.
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.any { it.id != down.id && it.pressed }) {
                                    if (!dragCancelled) {
                                        dragCancelled = true
                                        state.cancel()
                                        currentOnCancel()
                                    }
                                    // Consume both streams during multi-touch cancellation so a
                                    // nested control cannot receive a partial gesture.
                                    event.changes.forEach { it.consume() }
                                    continue
                                }
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    if (!dragCancelled) {
                                        dragCancelled = true
                                        state.cancel()
                                        currentOnCancel()
                                    }
                                    gestureEnded = true
                                    break
                                }
                                if (change.changedToUpIgnoreConsumed() || !change.pressed) {
                                    // Consume in Initial before the child receives Main. This is
                                    // the cancellation boundary for clickable and RemoteViews
                                    // controls after a long press has been accepted.
                                    change.consume()
                                    gestureEnded = true
                                    if (dragCancelled) {
                                        if (!childOwnsGesture && state.draggedId == id) state.cancel()
                                        break
                                    }
                                    // A quick fling can deliver the release before the previous
                                    // move callback is observed by composition. Apply the release
                                    // position first so the committed order is never one event
                                    // behind the finger.
                                    val finalPointer = pointerCoordinates.value
                                        ?.homePointerInRoot(change.position)
                                    if (finalPointer == null) {
                                        // A detached/stale coordinate node must never commit the
                                        // last known pointer position.
                                        state.cancel()
                                        dragCancelled = true
                                        currentOnCancel()
                                        break
                                    }
                                    if (state.updatePointer(finalPointer)) {
                                        currentOnPointerMove(finalPointer)
                                    }
                                    val dropPointer = state.pointer
                                    val result = state.finish(id)
                                    if (result == null) {
                                        // An ancestor (posture/item change or multi-pointer monitor)
                                        // may have cancelled between the loop check and this up.
                                        // Never turn that cancellation into the app action dialog.
                                        dragCancelled = true
                                        currentOnCancel()
                                    } else if (!result.didMove) {
                                        // Only a valid long-press with no movement opens the
                                        // legacy action dialog. Re-check shared ownership after
                                        // finishing: posture reset or another pane may have
                                        // invalidated this local drag while the pointer was up.
                                        if (currentCanInvokeLongPress(acceptedDragSession)) {
                                            currentOnLongPress?.invoke(dropPointer)
                                        }
                                        currentOnCancel()
                                    } else if (result.didMove) {
                                        if (dropPointer != null && currentOnDrop(dropPointer)) {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        } else {
                                            currentOnCancel()
                                        }
                                    }
                                    break
                                }

                                val pointerNow = pointerCoordinates.value
                                    ?.homePointerInRoot(change.position)
                                if (pointerNow == null) {
                                    // Coordinate invalidation during a posture/layout change is a
                                    // cancellation, not permission to reuse an old root point.
                                    change.consume()
                                    if (!dragCancelled) {
                                        dragCancelled = true
                                        state.cancel()
                                        currentOnCancel()
                                    }
                                    continue
                                }
                                // Once long-press is accepted, keep the outer verticalScroll
                                // from competing with the board drag, including sub-slop jitter.
                                change.consume()
                                if (!dragCancelled && state.updatePointer(pointerNow)) {
                                    currentOnPointerMove(pointerNow)
                                }
                            }
                        }

                        if (outcome == HomePressOutcome.TAP) {
                            currentOnClick?.invoke()
                        }
                    }
                } finally {
                    if (acceptedGesture && !gestureEnded && !childOwnsGesture) {
                        state.cancel()
                        currentOnCancel()
                    }
                }
                }
                .graphicsLayer {
                    val translation = state.translationFor(id)
                    if (translation != null) {
                        translationX = translation.x
                        translationY = translation.y
                        scaleX = 1.025f
                        scaleY = 1.025f
                        alpha = 0.94f
                        shadowElevation = if (isWindows8) 0f else 4.dp.toPx()
                    }
                },
        ) {
            LauncherGlassSceneScope(
                enabled = !hiddenFromInteraction,
                alpha = if (glassEnabledForDrag && state.draggedId == id) 0.94f else 1f,
                zIndex = if (state.draggedId == id) 2f else 0f,
                geometryVersion = glassDragGeometry,
                content = content,
            )
        }
    }
}

/** Explains an empty page so it doesn't look like a loading failure. */
@Composable
private fun EmptyHomePageHint(modifier: Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = tr("このページは空です", "This page is empty"),
            color = FiiLDAInk,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = tr(
                "アプリ一覧でアプリを長押しして、このページに追加できます。",
                "Long press an app in the app list to add it to this page.",
            ),
            color = FiiLDAMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** False on a Home page that is composed but not shown (a pager neighbor or a hidden surface). */
internal val LocalHomeBoardVisible = androidx.compose.runtime.compositionLocalOf { true }

