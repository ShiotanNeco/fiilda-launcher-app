package com.fiilda.launcher

import android.os.Build
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.runtime.State
import dev.glasslab.glass.GlassGeometrySignal
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** State coordinator and top level navigation for the launcher surfaces. */
private val NavigationEdgeFadeHeight = 24.dp

private val LauncherPageSaver = Saver<LauncherPage, String>(
    save = { launcherPageToken(it) },
    restore = { launcherPageFromToken(it) },
)

private val LauncherDestinationSaver = Saver<LauncherDestination, String>(
    save = { it.name },
    restore = { raw ->
        runCatching { LauncherDestination.valueOf(raw) }
            .getOrDefault(LauncherDestination.HOME)
    },
)

private val StartCanvasScrollPositionSaver = Saver<StartCanvasScrollPosition, List<Int>>(
    save = { listOf(it.itemIndex.coerceAtLeast(0), it.itemOffsetPx.coerceAtLeast(0)) },
    restore = { raw ->
        if (raw.size != 2) {
            StartCanvasScrollPosition(0, 0)
        } else {
            StartCanvasScrollPosition(
                itemIndex = raw[0].coerceAtLeast(0),
                itemOffsetPx = raw[1].coerceAtLeast(0),
            )
        }
    },
)

/**
 * Shields a hidden surface's full shell from pointer and accessibility interaction. The shield
 * is only installed on hidden layers and consumes at Initial so no child pager/search gesture can
 * win through an uncovered part of the visible layer.
 */
private fun Modifier.hiddenSurfaceInteractionShield(): Modifier =
    this
        .zIndex(1f)
        .clearAndSetSemantics {}
        .pointerInput(Unit) {
            awaitEachGesture {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) break
                }
            }
        }

/**
 * Applies surface opacity and the wide reveal while keeping the interaction shell intact. The
 * values are read while drawing, so the transition does not recompose the surface every frame.
 */
private fun Modifier.launcherSurfaceVisual(
    enabled: Boolean,
    alpha: () -> Float,
    scale: () -> Float,
    blurRadiusDp: () -> Float,
): Modifier {
    if (!enabled) return this
    return this.graphicsLayer {
        val currentAlpha = alpha()
        this.alpha = currentAlpha
        val currentScale = scale()
        scaleX = currentScale
        scaleY = currentScale
        transformOrigin = TransformOrigin.Center
        // Compose ignores RenderEffect blur on API < 31; no effect at zero keeps the settled
        // surface free of an extra offscreen layer.
        val radius = blurRadiusDp().dp.toPx()
        val blur = currentAlpha > 0f && radius > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        renderEffect = if (blur) BlurEffect(radius, radius, TileMode.Clamp) else null
        clip = blur
    }
}

/** Animated surface values, read through [State] so only drawing observes each frame. */
private class LauncherSurfaceAnimationStates(
    val homeAlpha: State<Float>,
    val homeScale: State<Float>,
    val homeBlurRadiusDp: State<Float>,
    val drawerAlpha: State<Float>,
    val drawerScale: State<Float>,
    val drawerBlurRadiusDp: State<Float>,
)

@Composable
internal fun FiiLDALauncher(
    appWidgetHost: AppWidgetHost,
    appWidgetManager: AppWidgetManager,
    onPickExternalWidget: (WidgetPickerProvider, Int) -> Unit,
    onWidgetResultListener: (WidgetResultListener?) -> Unit,
    onAppLaunchFailure: () -> Unit,
    lifecycleRefreshToken: Int,
    appCatalogRefreshToken: Int = 0,
    homeIntentRequest: Int = 0,
) {
    val context = LocalContext.current
    val separateWideHomeOrder = LocalSeparateWideHomeOrder.current
    // Reorder gestures may finish after a settings recomposition. Read the latest mode at the
    // commit boundary instead of relying on the lambda's creation-time capture.
    val currentSeparateWideHomeOrder = rememberUpdatedState(separateWideHomeOrder)
    val configuration = LocalConfiguration.current
    val currentPosture = launcherPostureForWindow(
        widthDp = configuration.screenWidthDp,
        heightDp = configuration.screenHeightDp,
    )
    var installedApps by remember { mutableStateOf(emptyList<LaunchableApp>()) }
    var favoriteIds by remember { mutableStateOf(emptyList<String>()) }
    var appTileSizes by remember { mutableStateOf(emptyMap<String, AppTileSize>()) }
    var wideAppTileSizes by remember { mutableStateOf(emptyMap<String, AppTileSize>()) }
    var appTileContentModes by remember { mutableStateOf(emptyMap<String, AppTileContentMode>()) }
    var widgetSizeOverrides by remember { mutableStateOf(emptyMap<String, WidgetSizeChoice>()) }
    var wideWidgetSizeOverrides by remember { mutableStateOf(emptyMap<String, WidgetSizeChoice>()) }
    var externalWidgets by remember { mutableStateOf(emptyList<LauncherWidgetDescriptor>()) }
    var pinnedShortcuts by remember { mutableStateOf(emptyList<ResolvedPinnedShortcut>()) }
    var homeFolders by remember { mutableStateOf(emptyList<HomeFolder>()) }
    var folderExpansionSession by remember { mutableStateOf<FolderExpansionSession?>(null) }
    var homePages by remember { mutableStateOf(HomePages.empty()) }
    // v4 keeps an independent wide order and a narrow order represented by HomePages. The latter
    // remains the pager-friendly projection and compatibility mirror.
    var wideHomeOrder by remember { mutableStateOf(emptyList<String>()) }
    // Keep the v4 record as the single in-memory source of truth. HomePages is only its narrow
    // projection; rebuilding a layout from that projection would lose an interleaved global
    // narrow order such as page0=[a,c], page1=[b], narrowOrder=[b,a,c].
    var committedHomeLayout by remember { mutableStateOf(HomeLayout.empty()) }
    // The last committed order is intentionally separate from the live drag preview so a cancel
    // after a posture change or app-area drop can restore the persisted snapshot exactly.
    var persistedWideHomeOrder by remember { mutableStateOf(emptyList<String>()) }
    var selectedHomePage by rememberSaveable { mutableIntStateOf(0) }
    var navigationBarHeightPx by remember { mutableIntStateOf(0) }
    // Narrow page ownership to restore when the wide canvas returns to a pager posture. This is
    // captured when entering the wide posture and never inferred from the global wide order.
    var wideHomeOwnerPage by rememberSaveable { mutableIntStateOf(0) }
    var wideCanvasAnchor by rememberSaveable(stateSaver = LauncherDestinationSaver) {
        mutableStateOf(LauncherDestination.HOME)
    }
    var wideCanvasLastHomePosition by rememberSaveable(stateSaver = StartCanvasScrollPositionSaver) {
        mutableStateOf(StartCanvasScrollPosition(itemIndex = 0, itemOffsetPx = 0))
    }
    var photoUris by remember { mutableStateOf(emptyMap<String, String>()) }
    var photoVideoMutes by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    var photoSelectionAddsWidget by rememberSaveable { mutableStateOf(false) }
    var photoSelectionHomePage by rememberSaveable { mutableIntStateOf(0) }
    var photoSelectionWidgetId by rememberSaveable { mutableStateOf<String?>(null) }
    var photoPreviewWidgetId by remember { mutableStateOf<String?>(null) }
    // Drag reordering is preview-only until the finger is released successfully.
    var homePreviewPage by remember { mutableStateOf<Int?>(null) }
    var homePreviewOrder by remember { mutableStateOf<List<String>?>(null) }
    // A wide drag in shared mode previews against the narrow order without overwriting the
    // preserved independent wide snapshot until the drop is committed.
    var homePreviewWideOrder by remember { mutableStateOf<List<String>?>(null) }
    var page by rememberSaveable(stateSaver = LauncherPageSaver) {
        mutableStateOf(LauncherPage.HOME)
    }
    var contextMode by remember { mutableStateOf(ContextMode.GLANCE) }
    var query by remember { mutableStateOf("") }
    var drawerResetRequest by remember { mutableIntStateOf(0) }
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var actionApp by remember { mutableStateOf<LaunchableApp?>(null) }
    var actionAppHomePage by remember { mutableStateOf<Int?>(null) }
    var actionAppPresentation by remember { mutableStateOf(HomeSizePresentation.NARROW) }
    var actionWidget by remember { mutableStateOf<HomeItem?>(null) }
    var actionWidgetHomePage by remember { mutableIntStateOf(0) }
    var actionWidgetPresentation by remember { mutableStateOf(HomeSizePresentation.NARROW) }
    var actionPinnedShortcut by remember { mutableStateOf<ResolvedPinnedShortcut?>(null) }
    var actionFolder by remember { mutableStateOf<HomeFolder?>(null) }
    var actionFolderHomePage by remember { mutableIntStateOf(0) }
    var actionFolderPresentation by remember { mutableStateOf(HomeSizePresentation.NARROW) }
    var pendingPinnedShortcutCleanupRevision by remember { mutableIntStateOf(0) }
    val currentActionAppPresentation = rememberUpdatedState(actionAppPresentation)
    val currentActionWidgetPresentation = rememberUpdatedState(actionWidgetPresentation)
    val currentActionWidgetHomePage = rememberUpdatedState(actionWidgetHomePage)
    val currentNarrowHomeSizes = rememberUpdatedState(
        HomeSizeMaps(
            appTileSizes = appTileSizes,
            widgetSizeOverrides = widgetSizeOverrides,
        ),
    )
    val currentWideHomeSizes = rememberUpdatedState(
        HomeSizeMaps(
            appTileSizes = wideAppTileSizes,
            widgetSizeOverrides = wideWidgetSizeOverrides,
        ),
    )
    var showSettingsScreen by rememberSaveable { mutableStateOf(false) }
    val drawerSearchController = rememberDrawerSearchController(
        query = query,
        active = page == LauncherPage.DRAWER && !showSettingsScreen,
        refreshToken = lifecycleRefreshToken,
    )
    var showWidgetSelector by remember { mutableStateOf(false) }
    var preferredWidgetPackage by remember { mutableStateOf<String?>(null) }
    var widgetPickerHomePage by remember { mutableIntStateOf(0) }
    var externalWidgetRefreshToken by remember { mutableStateOf(0) }
    var latestWidgetResultId by remember { mutableStateOf<Int?>(null) }
    var latestWidgetResultHomePage by remember { mutableIntStateOf(0) }
    var latestWidgetResultAcknowledgement by remember { mutableStateOf<(() -> Unit)?>(null) }
    var launcherStateLoaded by remember { mutableStateOf(false) }
    val launcherView = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val homePagerState = rememberPagerState(
        initialPage = selectedHomePage,
        pageCount = { homePages.count },
    )
    val homeDragCoordinator = remember { HomeDragCoordinator() }
    var previousPosture by remember { mutableStateOf<Posture?>(null) }
    var hasObservedInitialSettledPage by remember { mutableStateOf(false) }
    // True while a tab-driven animation owns the pager. Between a canceled animation and its
    // replacement the pager is briefly idle at an intermediate page; that settledPage must not
    // overwrite the tapped target, or rapid taps lose or reverse steps.
    val explicitPagerTarget = remember { HomePagerTargetGuard() }

    fun navigateToDestination(
        destination: LauncherDestination,
        withHaptic: Boolean,
        presentation: LauncherHomePresentation = LauncherHomePresentation.PAGER,
        explicitHomeTap: Boolean = false,
    ) {
        val currentDestination = launcherDestinationFor(
            page = page,
            selectedHomePage = selectedHomePage,
            presentation = presentation,
        )
        if (shouldResetDrawerOnExplicitTabTap(
                currentDestination = currentDestination,
                destination = destination,
                explicitTabTap = explicitHomeTap,
            )
        ) {
            query = ""
            drawerResetRequest++
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
        if (presentation == LauncherHomePresentation.START_CANVAS) {
            if (destination == LauncherDestination.HOME || destination == LauncherDestination.DRAWER) {
                if (page == LauncherPage.DRAWER && destination == LauncherDestination.HOME) {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                }
                page = destination.page
                wideCanvasAnchor = destination
                if (withHaptic) launcherView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                return
            }
        }
        if (currentDestination == destination && !explicitHomeTap) return
        if (page == LauncherPage.DRAWER && destination.page != LauncherPage.DRAWER) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
        if (destination == LauncherDestination.HOME) {
            val targetHomePage = launcherHomePageAfterExplicitTap(
                selectedHomePage = selectedHomePage,
                currentDestination = currentDestination,
                presentation = presentation,
                explicitHomeTap = explicitHomeTap,
                homePageCount = homePages.count,
            )
            selectedHomePage = targetHomePage
        }
        page = destination.page
        if (withHaptic) {
            // This is a commit cue for a tab tap only. Swipes and recompositions never call this
            // path, so they cannot duplicate the feedback while the pager settles.
            launcherView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    LaunchedEffect(homeIntentRequest) {
        if (homeIntentRequest == 0) return@LaunchedEffect
        showSettingsScreen = false
        if (page == LauncherPage.DRAWER) {
            val presentation = launcherHomePresentationFor(currentPosture)
            if (presentation == LauncherHomePresentation.START_CANVAS) {
                page = LauncherPage.HOME
                wideCanvasAnchor = LauncherDestination.HOME
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
            } else {
                navigateToDestination(
                    destination = launcherHomeDestination(selectedHomePage),
                    withHaptic = false,
                    presentation = presentation,
                    explicitHomeTap = false,
                )
            }
            contextMode = ContextMode.GLANCE
        }
    }

    // Keep the pager selection synchronized here, outside HomeSurface. HomeSurface is animated in
    // and out of the destination stack; currentPage changes while a swipe or explicit HOME
    // animation is in flight, so it must not overwrite the logical selected target. settledPage
    // updates only after the gesture/animation settles and still preserves swipe behavior.
    LaunchedEffect(homePagerState.settledPage) {
        if (!hasObservedInitialSettledPage) {
            hasObservedInitialSettledPage = true
            return@LaunchedEffect
        }
        if (explicitPagerTarget.active) return@LaunchedEffect
        val settledPage = homePagerState.settledPage.coerceIn(0, homePages.count - 1)
        // The pager already shows this page; record it without asking the pager to move again.
        if (settledPage != selectedHomePage) explicitPagerTarget.fromPager = settledPage
        selectedHomePage = settledPage
        if (homePreviewPage != settledPage) {
            homePreviewPage = null
            homePreviewOrder = null
        }
    }
    LaunchedEffect(selectedHomePage, homePages.count) {
        // Always retarget the pager, including when the logical target is already currentPage.
        // currentPage can still be at that page while an interrupted animation leaves a
        // fractional offset; animateScrollToPage then settles the presentation at the boundary.
        val target = selectedHomePage.coerceIn(0, homePages.count - 1)
        val fromPager = explicitPagerTarget.fromPager
        explicitPagerTarget.fromPager = null
        // A selection that came from the pager itself (a swipe settling) needs no animation.
        // Animating here could also land on top of the user's next fling and cancel it, which
        // made quick repeated swipes snap back to the previous page.
        if (fromPager == target) return@LaunchedEffect
        explicitPagerTarget.active = true
        try {
            homePagerState.animateScrollToPage(target)
            explicitPagerTarget.active = false
        } catch (cancellation: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                // A new logical target canceled this effect. Let that replacement effect own the
                // pager (the guard stays active) so rapid taps retarget from the current offset.
                throw cancellation
            }
            explicitPagerTarget.active = false
            // User input can cancel the animation without changing settledPage. Reconcile the
            // logical selection now so the next target effect follows the actual settled page.
            val settledPage = homePagerState.settledPage.coerceIn(0, homePages.count - 1)
            if (settledPage != selectedHomePage) explicitPagerTarget.fromPager = settledPage
            selectedHomePage = settledPage
            if (homePreviewPage != settledPage) {
                homePreviewPage = null
                homePreviewOrder = null
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(appWidgetHost) {
        onWidgetResultListener { appWidgetId, targetHomePage, acknowledgePersistence ->
            latestWidgetResultId = appWidgetId
            latestWidgetResultHomePage = targetHomePage.coerceAtLeast(0)
            latestWidgetResultAcknowledgement = acknowledgePersistence
            externalWidgetRefreshToken++
        }
        onDispose { onWidgetResultListener(null) }
    }

    val now by produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(30_000)
        }
    }
    // NotificationListenerService publishes only its current in-memory active set. The same
    // snapshot feeds home badges/live rows and the favorite app action sheet without any durable
    // notification history.
    val notificationState = rememberNotificationState(context, lifecycleRefreshToken)

    fun currentHomeLayout(): HomeLayout = canonicalizeHomeLayout(committedHomeLayout)

    fun applyCommittedHomeLayout(layout: HomeLayout) {
        val canonical = canonicalizeHomeLayout(layout)
        committedHomeLayout = canonical
        homePages = canonical.toHomePages()
        wideHomeOrder = canonical.order
        persistedWideHomeOrder = canonical.order
        selectedHomePage = selectedHomePage.coerceIn(0, canonical.pageCount - 1)
        wideHomeOwnerPage = wideHomeOwnerPage.coerceIn(0, canonical.pageCount - 1)
    }

    var homePageChangePending by remember { mutableStateOf(false) }
    val homePageScope = androidx.compose.runtime.rememberCoroutineScope()

    fun changeHomePages(removedPage: Int? = null, onFinished: (Int?) -> Unit) {
        if (homePageChangePending) return
        val current = currentHomeLayout()
        val updated = if (removedPage == null) addHomePage(current) else removeHomePage(current, removedPage)
        if (updated == null) {
            onFinished(null)
            return
        }
        homePageChangePending = true
        homePageScope.launch {
            val persisted = withContext(Dispatchers.IO) {
                runCatching {
                    persistHomeLayoutTransaction(context = context, layout = updated, expectedLayout = current)
                }.getOrDefault(false)
            }
            homePageChangePending = false
            if (!persisted) {
                Toast.makeText(context, tr("ホーム画面を保存できませんでした", "Couldn't save Home"), Toast.LENGTH_SHORT).show()
                onFinished(null)
            } else {
                homePreviewPage = null
                homePreviewOrder = null
                homePreviewWideOrder = null
                val newSelected = if (removedPage == null) updated.pageCount - 1 else
                    homePageAfterRemoval(selectedHomePage, removedPage, updated.pageCount)
                selectedHomePage = newSelected
                if (removedPage != null) {
                    wideHomeOwnerPage = homePageAfterRemoval(wideHomeOwnerPage, removedPage, updated.pageCount)
                    photoSelectionHomePage = homePageAfterRemoval(photoSelectionHomePage, removedPage, updated.pageCount)
                    latestWidgetResultHomePage = homePageAfterRemoval(latestWidgetResultHomePage, removedPage, updated.pageCount)
                    actionWidgetHomePage = homePageAfterRemoval(actionWidgetHomePage, removedPage, updated.pageCount)
                    actionFolderHomePage = homePageAfterRemoval(actionFolderHomePage, removedPage, updated.pageCount)
                }
                widgetPickerHomePage = if (removedPage == null) widgetPickerHomePage else
                    homePageAfterRemoval(widgetPickerHomePage, removedPage, updated.pageCount)
                actionAppHomePage = actionAppHomePage?.let {
                    if (removedPage == null) it else homePageAfterRemoval(it, removedPage, updated.pageCount)
                }
                applyCommittedHomeLayout(updated)
                onFinished(if (removedPage == null) newSelected else removedPage)
            }
        }
    }

    fun currentHomeSizeMaps(presentation: HomeSizePresentation): HomeSizeMaps =
        homeSizeMapsForPresentation(
            narrow = currentNarrowHomeSizes.value,
            wide = currentWideHomeSizes.value,
            separateWideOrder = currentSeparateWideHomeOrder.value,
            presentation = presentation,
        )

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { selectedUri ->
        val shouldAddWidget = photoSelectionAddsWidget
        val targetHomePage = photoSelectionHomePage.coerceAtLeast(0)
        val targetWidgetId = photoSelectionWidgetId ?: PhotoWidgetHomeId
        photoSelectionAddsWidget = false
        photoSelectionWidgetId = null
        if (selectedUri == null) {
            // Cancellation deliberately leaves both the old image and home order untouched.
        } else {
            val selectedIsVideo = isPhotoFrameVideo(context, selectedUri.toString())
            val persistedSelection = persistPhotoSelection(
                context = context,
                widgetId = targetWidgetId,
                uri = selectedUri,
                addToHome = shouldAddWidget,
                targetHomePage = targetHomePage,
                videoMuted = selectedIsVideo,
            )
            if (persistedSelection == null) {
                // No Compose state is changed until the URI and (for first add) home order have
                // committed together. The helper also releases only a newly taken grant on error.
                Toast.makeText(context, tr("画像または動画へのアクセスを保存できませんでした", "Couldn't save access to the image or video"), Toast.LENGTH_SHORT).show()
            } else {
                // Apply the exact maps/layout that were committed. This matters when the result
                // arrives before the asynchronous launcher-state load, because the live Compose
                // snapshot is still empty while preferences already contain the user's board.
                photoUris = persistedSelection.photoUris
                photoVideoMutes = persistedSelection.photoVideoMutes
                applyCommittedHomeLayout(persistedSelection.layout)
                Toast.makeText(context, tr("画像または動画をフォトフレームに設定しました", "Set the image or video in the photo frame"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // PackageManager queries, icon loading, and the tiny drawable raster pass can be expensive on
    // a foldable. A producer is cancelled with this composition, does the whole query off the
    // main thread, and publishes the completed immutable list back on the main snapshot context.
    // Keeping the prior installedApps value while a refresh is in flight avoids a visible blank
    // drawer and prevents stale work from mutating state after navigation away. The query is keyed
    // on package changes only; an ordinary resume reuses the catalog (and its Drawables) as is.
    val queriedApps by produceState<List<LaunchableApp>?>(
        initialValue = null,
        key1 = context,
        key2 = appCatalogRefreshToken,
    ) {
        value = withContext(Dispatchers.Default) {
            queryLaunchableApps(context)
        }
    }

    // Standalone pins are resolved through LauncherApps off the main thread. The durable records
    // are read before querying so a locked profile/package outage still produces a retained
    // placeholder instead of shrinking the user's home layout during a refresh.
    val queriedPinnedShortcuts by produceState<List<ResolvedPinnedShortcut>?>(
        initialValue = null,
        key1 = context,
        key2 = lifecycleRefreshToken,
    ) {
        val records = readPinnedShortcutRecords(context)
        value = withContext(Dispatchers.Default) {
            queryResolvedPinnedShortcuts(context, records)
        }
    }

    // App packages can change while this Activity instance remains alive. Tie the query to the
    // Activity's lifecycle refresh token so a newly installed launcher activity becomes searchable
    // without replaying the persisted home/favorite migration. The first completed pass also
    // performs the complete launcher-state load; subsequent passes intentionally update only the
    // app catalog.
    LaunchedEffect(queriedApps, lifecycleRefreshToken) {
        val apps = queriedApps ?: return@LaunchedEffect
        installedApps = apps
        if (launcherStateLoaded) return@LaunchedEffect

        photoUris = readPhotoUris(context)
        photoVideoMutes = readPhotoVideoMutes(context)
        val storedFavorites = readFavoriteIds(context)
        val installedAppIds = apps.map(::favoriteId)
        // Favorites missing from this catalog snapshot are retained unless their package is
        // confirmed uninstalled; a renamed launcher activity is followed to its replacement.
        val reconciliation = storedFavorites?.let { stored ->
            withContext(Dispatchers.IO) {
                reconcileStoredFavorites(stored, installedAppIds) { packageName ->
                    isFavoritePackageInstalled(context, packageName)
                }
            }
        }
        val retainedFavorites = reconciliation?.favoriteIds.orEmpty()
        val favoriteRenames = reconciliation?.renamedIds.orEmpty()
        val shouldRestoreDefaults = storedFavorites != null && storedFavorites.isNotEmpty() &&
            retainedFavorites.isEmpty() && installedAppIds.isNotEmpty()
        val resolvedFavoriteIds = if (storedFavorites == null || shouldRestoreDefaults) {
            val rolePackages = withContext(Dispatchers.IO) { starterRolePackages(context) }
            pickStarterApps(apps, rolePackages).map(::favoriteId)
        } else {
            retainedFavorites
        }
        val favoritesChanged = storedFavorites != resolvedFavoriteIds
        favoriteIds = resolvedFavoriteIds
        val favoriteIdSet = resolvedFavoriteIds.toSet()
        val storedAppTileSizes = readAppTileSizes(context)
        val normalizedAppTileSizes = pruneAppTileSizes(
            sizes = storedAppTileSizes.renameHomeIdKeys(favoriteRenames),
            favoriteIds = favoriteIdSet,
        )
        val storedWideAppTileSizes = readWideAppTileSizes(context)
        val normalizedWideAppTileSizes = pruneAppTileSizes(
            sizes = migrateWideHomeSizeMaps(
                narrow = HomeSizeMaps(appTileSizes = normalizedAppTileSizes),
                storedWide = storedWideAppTileSizes?.let {
                    HomeSizeMaps(appTileSizes = it.renameHomeIdKeys(favoriteRenames))
                },
            ).appTileSizes,
            favoriteIds = favoriteIdSet,
        )
        appTileSizes = normalizedAppTileSizes
        wideAppTileSizes = normalizedWideAppTileSizes
        val storedAppTileContentModes = readAppTileContentModes(context)
        val normalizedAppTileContentModes = pruneAppTileContentModes(
            modes = storedAppTileContentModes.renameHomeIdKeys(favoriteRenames),
            favoriteIds = favoriteIdSet,
        )
        appTileContentModes = normalizedAppTileContentModes
        externalWidgets = loadValidWidgetDescriptors(context, appWidgetManager, appWidgetHost)
        val storedPinnedShortcutRecords = readPinnedShortcutRecords(context)
        val storedPinnedShortcutIds = storedPinnedShortcutRecords.map { it.homeId }
        val storedHomePagesRaw = readHomePagesRaw(context)
        val storedHomeLayoutRaw = readHomeLayoutRaw(context)
        val storedLegacyOrder = readHomeOrder(context)
        val storedHomeFoldersRaw = readHomeFoldersRaw(context)
        val normalizedHomeFolders = normalizeHomeFolders(
            stored = parseHomeFolders(storedHomeFoldersRaw)?.renameHomeFolderMembers(favoriteRenames),
            favoriteIds = favoriteIdSet,
        )
        val storedHomeLayout = parseHomeLayout(storedHomeLayoutRaw)?.renameHomeIds(favoriteRenames)
        // A valid v4 payload is canonical. The v3/v2 records and legacy key are migration
        // fallbacks; once the canonical payload exists, mirrors cannot reorder either presentation
        // on a later launch.
        val normalizedHomeLayout = normalizeHomeLayout(
            storedLayout = storedHomeLayout,
            storedPages = parseHomePages(storedHomePagesRaw)?.renameHomeIds(favoriteRenames),
            legacyOrder = storedLegacyOrder?.renameHomeIds(favoriteRenames),
            favoriteIds = resolvedFavoriteIds,
            externalWidgetIds = (externalWidgets.map { it.homeId } + storedPinnedShortcutIds).distinct(),
            homeFolders = normalizedHomeFolders,
        )
        val storedWidgetSizes = readWidgetSizeOverrides(context)
        val normalizedWidgetSizes = pruneWidgetSizeOverrides(
            overrides = storedWidgetSizes,
            presentHomeIds = normalizedHomeLayout.allIds,
        )
        val storedWideWidgetSizes = readWideWidgetSizeOverrides(context)
        val normalizedWideWidgetSizes = pruneWidgetSizeOverrides(
            overrides = migrateWideHomeSizeMaps(
                narrow = HomeSizeMaps(widgetSizeOverrides = normalizedWidgetSizes),
                storedWide = storedWideWidgetSizes?.let {
                    HomeSizeMaps(widgetSizeOverrides = it)
                },
            ).widgetSizeOverrides,
            presentHomeIds = normalizedHomeLayout.allIds,
        )
        val needsCanonicalMigration = homeLayoutMirrorsNeedMigration(
            storedLayoutRaw = storedHomeLayoutRaw,
            storedPagesRaw = storedHomePagesRaw,
            storedLegacyOrder = storedLegacyOrder,
            canonicalLayout = normalizedHomeLayout,
        ) ||
            storedHomeFoldersRaw != serializeHomeFolders(normalizedHomeFolders) ||
            storedAppTileSizes != normalizedAppTileSizes ||
            storedWidgetSizes != normalizedWidgetSizes ||
            storedWideAppTileSizes == null ||
            storedWideAppTileSizes != normalizedWideAppTileSizes ||
            storedWideWidgetSizes == null ||
            storedWideWidgetSizes != normalizedWideWidgetSizes ||
            storedAppTileContentModes != normalizedAppTileContentModes ||
            favoritesChanged
        val migrationCommitted = if (needsCanonicalMigration) {
            persistHomeLayoutTransaction(
                context = context,
                layout = normalizedHomeLayout,
                favoriteIds = resolvedFavoriteIds.takeIf { favoritesChanged },
                appTileSizes = normalizedAppTileSizes,
                wideAppTileSizes = normalizedWideAppTileSizes,
                widgetSizes = normalizedWidgetSizes,
                wideWidgetSizes = normalizedWideWidgetSizes,
                appTileContentModes = normalizedAppTileContentModes,
                folders = normalizedHomeFolders,
            )
        } else {
            true
        }
        // Render the canonical v4 projection even when a first-run commit is unavailable; the
        // next lifecycle refresh will retry the same migration without exposing a partial mirror.
        applyCommittedHomeLayout(normalizedHomeLayout)
        homeFolders = normalizedHomeFolders
        widgetSizeOverrides = normalizedWidgetSizes
        wideWidgetSizeOverrides = normalizedWideWidgetSizes
        appTileContentModes = normalizedAppTileContentModes
        if (!migrationCommitted) {
            Toast.makeText(context, tr("ホーム配置を保存できませんでした", "Couldn't save the Home layout"), Toast.LENGTH_SHORT).show()
        }
        launcherStateLoaded = launcherStateLoadCompletedAfterMigration(
            needsCanonicalMigration = needsCanonicalMigration,
            migrationCommitted = migrationCommitted,
        )
    }

    LaunchedEffect(queriedPinnedShortcuts, lifecycleRefreshToken, launcherStateLoaded) {
        val resolved = queriedPinnedShortcuts ?: return@LaunchedEffect
        pinnedShortcuts = resolved
        if (!launcherStateLoaded) return@LaunchedEffect
        val persistedPinnedIds = readPinnedShortcutRecords(context).map { it.homeId }
        val storedLayout = parseHomeLayout(readHomeLayoutRaw(context))
        val storedFolders = normalizeHomeFolders(
            stored = parseHomeFolders(readHomeFoldersRaw(context)),
            favoriteIds = favoriteIds.toSet(),
        )
        val normalized = normalizeHomeLayout(
            storedLayout = storedLayout,
            storedPages = parseHomePages(readHomePagesRaw(context)),
            legacyOrder = readHomeOrder(context),
            favoriteIds = favoriteIds,
            externalWidgetIds = (
                externalWidgets.map { it.homeId } + persistedPinnedIds
                ).distinct(),
            homeFolders = storedFolders,
        )
        // A refresh may discover an accepted record after the separate confirmation Activity has
        // returned. Recover its placement through the same canonical transaction while preserving
        // the existing wide order and narrow ownership.
        if (normalized != currentHomeLayout()) {
            if (persistHomeLayoutTransaction(
                    context = context,
                    layout = normalized,
                    folders = storedFolders,
                )
            ) {
                applyCommittedHomeLayout(normalized)
                homeFolders = storedFolders
            }
        }
        if (actionPinnedShortcut?.record?.instanceId !in resolved.map { it.record.instanceId }) {
            actionPinnedShortcut = null
        }
    }

    // Local removal commits a durable platform-cleanup entry before attempting the binder call.
    // Retry it after resume and package/profile callbacks so a temporary locked profile or query
    // failure cannot leave the platform pin permanently behind.
    LaunchedEffect(
        lifecycleRefreshToken,
        launcherStateLoaded,
        pendingPinnedShortcutCleanupRevision,
    ) {
        if (!launcherStateLoaded) return@LaunchedEffect
        val attempt = withContext(Dispatchers.Default) {
            cleanupPendingPinnedShortcutUnpins(context)
        }
        if (attempt == PinnedShortcutCleanupAttempt.RETRY &&
            readPendingPinnedShortcutUnpins(context).isNotEmpty()
        ) {
            Toast.makeText(
                context,
                tr("ショートカットの削除を完了できませんでした。次回もう一度試します", "Couldn't finish removing the shortcut. It will be retried next time"),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // Only favorite tiles with a shortcut-capable size in either presentation need shortcut
    // metadata. The union is important when an app is small in the pager but large on the wide
    // canvas; otherwise the wide presentation would render without its published shortcuts.
    // Platform calls remain isolated in a cancellable producer keyed by this current subset.
    val shortcutApps = remember(
        installedApps,
        appTileSizes,
        wideAppTileSizes,
        appTileContentModes,
        favoriteIds,
    ) {
        installedApps.filter {
            val id = favoriteId(it)
            favoriteId(it) in favoriteIds &&
                appTileContentModeFor(id, appTileContentModes) == AppTileContentMode.SHORTCUTS &&
                (
                    appTileSizes[id]?.supportsShortcuts() == true ||
                        wideAppTileSizes[id]?.supportsShortcuts() == true
                    )
        }
    }
    val appShortcuts by produceState<Map<String, List<ResolvedLauncherShortcut>>>(
        initialValue = emptyMap(),
        key1 = context,
        key2 = lifecycleRefreshToken,
        key3 = shortcutApps,
    ) {
        value = withContext(Dispatchers.Default) {
            queryAppShortcuts(context, shortcutApps)
        }
    }

    // Provider installs, updates, and removals arrive as package changes. A resume alone does not
    // revalidate every descriptor; a tile whose provider was locked retries its own info on resume.
    LaunchedEffect(externalWidgetRefreshToken, appCatalogRefreshToken, launcherStateLoaded) {
        if (!launcherStateLoaded ||
            (externalWidgetRefreshToken == 0 && appCatalogRefreshToken == 0)
        ) return@LaunchedEffect
        val pendingResultId = latestWidgetResultId
            ?.takeIf { latestWidgetResultAcknowledgement != null }
        val newDescriptor = pendingResultId
            ?.let { descriptorForWidget(context, appWidgetManager, it) }
        val existing = readWidgetDescriptors(context)
        val merged = if (newDescriptor == null) {
            existing
        } else {
            existing.filterNot { it.appWidgetId == newDescriptor.appWidgetId } + newDescriptor
        }
        if (newDescriptor == null && pendingResultId != null) {
            (context as? MainActivity)?.deleteAppWidgetIdSafely(pendingResultId)
            Toast.makeText(context, tr("ウィジェットを読み込めませんでした", "Couldn't load the widget"), Toast.LENGTH_SHORT).show()
        }
        // Validate the candidate list in memory. The descriptor, canonical layout, mirrors, and
        // size overrides are committed below by one editor transaction; a failed write therefore
        // leaves both the old board and the framework result marker retryable.
        val loaded = loadValidWidgetDescriptors(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetHost = appWidgetHost,
            storedDescriptors = merged,
        )
        val storedPagesRaw = readHomePagesRaw(context)
        val storedLayoutRaw = readHomeLayoutRaw(context)
        val storedFolders = normalizeHomeFolders(
            stored = parseHomeFolders(readHomeFoldersRaw(context)),
            favoriteIds = favoriteIds.toSet(),
        )
        // Keep a successfully parsed v4/v3 order and assignment authoritative; mirrors are
        // only used when the canonical record is absent or malformed.
        var normalizedLayout = normalizeHomeLayout(
            storedLayout = parseHomeLayout(storedLayoutRaw),
            storedPages = parseHomePages(storedPagesRaw),
            legacyOrder = readHomeOrder(context),
            favoriteIds = favoriteIds,
            externalWidgetIds = (
                loaded.map { it.homeId } + readPinnedShortcutRecords(context).map { it.homeId }
                ).distinct(),
            homeFolders = storedFolders,
        )
        if (newDescriptor != null) {
            // A recycled framework ID may still have a stale page assignment after a crash.
            // Re-home the completed picker result explicitly to the page captured at launch.
            normalizedLayout = addHomeItemToLayout(
                layout = normalizedLayout.copy(
                    order = normalizedLayout.order.filterNot { it == newDescriptor.homeId },
                ),
                id = newDescriptor.homeId,
                page = latestWidgetResultHomePage,
            )
        }
        val normalized = normalizedLayout.toHomePages()
        val normalizedWidgetSizes = pruneWidgetSizeOverrides(
            overrides = widgetSizeOverrides,
            presentHomeIds = normalized.allIds,
        )
        val normalizedWideWidgetSizes = pruneWidgetSizeOverrides(
            overrides = wideWidgetSizeOverrides,
            presentHomeIds = normalized.allIds,
        )
        val persisted = persistHomeLayoutTransaction(
            context = context,
            layout = normalizedLayout,
            widgetSizes = normalizedWidgetSizes,
            wideWidgetSizes = normalizedWideWidgetSizes,
            descriptors = loaded,
            folders = storedFolders,
        )
        if (persisted) {
            externalWidgets = loaded
            applyCommittedHomeLayout(normalizedLayout)
            homeFolders = storedFolders
            widgetSizeOverrides = normalizedWidgetSizes
            wideWidgetSizeOverrides = normalizedWideWidgetSizes
            if (actionWidget?.id != null && actionWidget?.id !in normalized.allIds) {
                actionWidget = null
            }
            latestWidgetResultAcknowledgement?.invoke()
            latestWidgetResultAcknowledgement = null
            latestWidgetResultId = null
        }
    }

    LaunchedEffect(page) {
        if (page != LauncherPage.HOME) {
            homePreviewOrder = null
            homePreviewPage = null
            homePreviewWideOrder = null
            homeDragCoordinator.reset()
        }
    }
    LaunchedEffect(separateWideHomeOrder) {
        // A setting change is a presentation boundary; do not carry a transient wide preview
        // into the newly selected source order.
        homePreviewWideOrder = null
    }
    val filteredApps = remember(installedApps, query) {
        rankLaunchableAppsForDrawerSearch(query, installedApps)
    }

    val addFavorite: (LaunchableApp, Int) -> Boolean = { app, requestedTargetHomePage ->
        val id = favoriteId(app)
        val currentPages = currentHomeLayout().toHomePages()
        val addition = computeFavoriteHomePageAddition(
            favoriteIds = favoriteIds,
            homePages = currentPages,
            id = id,
            targetHomePage = requestedTargetHomePage,
        )
        if (addition == null) {
            Toast.makeText(context, tr("お気に入りに登録済みです", "Already on Home"), Toast.LENGTH_SHORT).show()
            true
        } else {
            val layout = addHomeItemToLayout(
                removeHomeItemFromLayout(currentHomeLayout(), id),
                id = id,
                page = requestedTargetHomePage,
            )
            val committed = persistHomeLayoutTransaction(
                context = context,
                layout = layout,
                favoriteIds = addition.favoriteIds,
                folders = homeFolders,
            )
            if (committed) {
            // Do not expose either half of the transition to Compose until both durable records
            // (including the page-1 legacy mirror) have committed successfully.
            favoriteIds = addition.favoriteIds
            applyCommittedHomeLayout(layout)
            Toast.makeText(context, tr("お気に入りに追加しました", "Added to Home"), Toast.LENGTH_SHORT).show()
                true
            } else {
            Toast.makeText(context, tr("お気に入りへの追加を保存できませんでした", "Couldn't save adding to Home"), Toast.LENGTH_SHORT).show()
                false
            }
        }
    }
    val moveFavorite: (LaunchableApp, Int) -> Boolean = { app, requestedTargetHomePage ->
        val id = favoriteId(app)
        val currentLayout = currentHomeLayout()
        val installedAppIds = installedApps.asSequence().map(::favoriteId).toSet()
        if (!isHomeItemPageTransferEligible(
                layout = currentLayout,
                favoriteIds = favoriteIds,
                installedAppIds = installedAppIds,
                id = id,
            )
        ) {
            // The action sheet can outlive a package/favorite refresh. Do not recreate or write a
            // stale item; keep the sheet open so the user can dismiss it explicitly.
            Toast.makeText(context, tr("このアプリは移動できなくなりました", "This app can no longer be moved"), Toast.LENGTH_SHORT).show()
            false
        } else {
            val transfer = computeHomeItemPageTransfer(
                layout = currentLayout,
                favoriteIds = favoriteIds,
                id = id,
                targetPage = requestedTargetHomePage,
            )
            if (transfer == null) {
                // The eligibility snapshot above should make this impossible, but retain the
                // failure guard if another state transition races this callback.
                Toast.makeText(context, tr("このアプリは移動できなくなりました", "This app can no longer be moved"), Toast.LENGTH_SHORT).show()
                false
            } else if (!transfer.changed) {
                Toast.makeText(
                    context,
                    tr("このアプリはすでにホーム${transfer.sourcePage + 1}にあります", "This app is already on Home ${transfer.sourcePage + 1}"),
                    Toast.LENGTH_SHORT,
                ).show()
                true
            } else if (persistHomeLayoutTransaction(
                    context = context,
                    layout = transfer.layout,
                    folders = homeFolders,
                )
            ) {
                // The v4 layout and both compatibility mirrors are visible to Compose only after
                // the single persistence transaction succeeds.
                applyCommittedHomeLayout(transfer.layout)
                Toast.makeText(
                    context,
                    tr("ホーム${transfer.targetPage + 1}へ移動しました", "Moved to Home ${transfer.targetPage + 1}"),
                    Toast.LENGTH_SHORT,
                ).show()
                true
            } else {
                Toast.makeText(context, tr("ホームの移動を保存できませんでした", "Couldn't save the move"), Toast.LENGTH_SHORT).show()
                false
            }
        }
    }
    val removeFavorite: (LaunchableApp) -> Boolean = { app ->
        val id = favoriteId(app)
        val updated = favoriteIds.filterNot { it == id }
        if (updated.size == favoriteIds.size) {
            true
        } else {
            val folderRemoval = removeAppFromHomeFolders(
                layout = currentHomeLayout(),
                folders = homeFolders,
                appId = id,
            )
            val updatedLayout = removeHomeItemFromLayout(folderRemoval.layout, id)
            val updatedFolders = folderRemoval.folders
            val updatedSizeMaps = removeHomeSizeIdFromMaps(
                narrow = HomeSizeMaps(
                    appTileSizes = appTileSizes,
                    widgetSizeOverrides = widgetSizeOverrides,
                ),
                wide = HomeSizeMaps(
                    appTileSizes = wideAppTileSizes,
                    widgetSizeOverrides = wideWidgetSizeOverrides,
                ),
                id = id,
            )
            val updatedTileSizes = updatedSizeMaps.first.appTileSizes
            val updatedWideTileSizes = updatedSizeMaps.second.appTileSizes
            val updatedContentModes = appTileContentModes - id
            val committed = persistHomeLayoutTransaction(
                context = context,
                layout = updatedLayout,
                favoriteIds = updated,
                appTileSizes = updatedTileSizes,
                wideAppTileSizes = updatedWideTileSizes,
                appTileContentModes = updatedContentModes,
                widgetSizes = updatedSizeMaps.first.widgetSizeOverrides,
                wideWidgetSizes = updatedSizeMaps.second.widgetSizeOverrides,
                folders = updatedFolders,
            )
            if (committed) {
                favoriteIds = updated
                applyCommittedHomeLayout(updatedLayout)
                appTileSizes = updatedTileSizes
                wideAppTileSizes = updatedWideTileSizes
                widgetSizeOverrides = updatedSizeMaps.first.widgetSizeOverrides
                wideWidgetSizeOverrides = updatedSizeMaps.second.widgetSizeOverrides
                appTileContentModes = updatedContentModes
                homeFolders = updatedFolders
                Toast.makeText(context, tr("お気に入りから削除しました", "Removed from Home"), Toast.LENGTH_SHORT).show()
                true
            } else {
                Toast.makeText(context, tr("お気に入りからの削除を保存できませんでした", "Couldn't save removing from Home"), Toast.LENGTH_SHORT).show()
                false
            }
        }
    }
    val setAppTileSize: (LaunchableApp, AppTileSize) -> Boolean = { app, size ->
        val id = favoriteId(app)
        val updatedSizeMaps = updateHomeAppTileSize(
            narrow = currentNarrowHomeSizes.value,
            wide = currentWideHomeSizes.value,
            id = id,
            size = size,
            presentation = currentActionAppPresentation.value,
            separateWideOrder = currentSeparateWideHomeOrder.value,
        )
        if (updatedSizeMaps.first == currentNarrowHomeSizes.value &&
            updatedSizeMaps.second == currentWideHomeSizes.value
        ) {
            true
        } else if (persistHomeLayoutTransaction(
                context = context,
                layout = currentHomeLayout(),
                appTileSizes = updatedSizeMaps.first.appTileSizes,
                wideAppTileSizes = updatedSizeMaps.second.appTileSizes,
                folders = homeFolders,
            )
        ) {
            appTileSizes = updatedSizeMaps.first.appTileSizes
            wideAppTileSizes = updatedSizeMaps.second.appTileSizes
            Toast.makeText(context, tr("アプリを${size.label}にしました", "App set to ${size.label}"), Toast.LENGTH_SHORT).show()
            true
        } else {
            Toast.makeText(context, tr("アプリサイズを保存できませんでした", "Couldn't save the app size"), Toast.LENGTH_SHORT).show()
            false
        }
    }

    val setAppTileContentMode: (LaunchableApp, AppTileContentMode) -> Boolean = { app, mode ->
        val id = favoriteId(app)
        if (id !in favoriteIds) {
            Toast.makeText(context, tr("表示内容を変更できませんでした", "Couldn't change the content"), Toast.LENGTH_SHORT).show()
            false
        } else {
            val current = appTileContentModeFor(id, appTileContentModes)
            val updated = if (mode == AppTileContentMode.SHORTCUTS) {
                appTileContentModes - id
            } else {
                appTileContentModes + (id to mode)
            }
            if (current == mode && updated == appTileContentModes) {
                Toast.makeText(context, tr("表示内容は${mode.label}です", "Content is ${mode.label}"), Toast.LENGTH_SHORT).show()
                true
            } else if (persistHomeLayoutTransaction(
                    context = context,
                    layout = currentHomeLayout(),
                    appTileContentModes = updated,
                    folders = homeFolders,
                )
            ) {
                appTileContentModes = updated
                Toast.makeText(context, tr("表示内容を${mode.label}にしました", "Content set to ${mode.label}"), Toast.LENGTH_SHORT).show()
                true
            } else {
                Toast.makeText(context, tr("表示内容を保存できませんでした", "Couldn't save the content setting"), Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    val setFolderSize: (HomeFolder, HomeFolderSize) -> Boolean = { folder, size ->
        val current = homeFolders.firstOrNull { it.id == folder.id }
        val updated = resizeHomeFolder(homeFolders, folder.id, size)
        if (current == null || updated == null) {
            Toast.makeText(context, tr("フォルダを変更できませんでした", "Couldn't change the folder"), Toast.LENGTH_SHORT).show()
            false
        } else if (current.size == size) {
            true
        } else if (persistHomeLayoutTransaction(
                context = context,
                layout = currentHomeLayout(),
                folders = updated,
            )
        ) {
            homeFolders = updated
            actionFolder = updated.firstOrNull { it.id == folder.id }
            true
        } else {
            Toast.makeText(context, tr("フォルダサイズを保存できませんでした", "Couldn't save the folder size"), Toast.LENGTH_SHORT).show()
            false
        }
    }

    val renameFolder: (HomeFolder, String) -> Boolean = { folder, name ->
        val updated = renameHomeFolder(homeFolders, folder.id, name)
        if (updated == null) {
            Toast.makeText(context, tr("フォルダ名を変更できませんでした", "Couldn't rename the folder"), Toast.LENGTH_SHORT).show()
            false
        } else if (persistHomeLayoutTransaction(
                context = context,
                layout = currentHomeLayout(),
                folders = updated,
            )
        ) {
            homeFolders = updated
            actionFolder = updated.firstOrNull { it.id == folder.id }
            true
        } else {
            Toast.makeText(context, tr("フォルダ名を保存できませんでした", "Couldn't save the folder name"), Toast.LENGTH_SHORT).show()
            false
        }
    }

    val dissolveFolder: (HomeFolder) -> Boolean = { folder ->
        val transition = dissolveHomeFolder(
            layout = currentHomeLayout(),
            folders = homeFolders,
            folderId = folder.id,
        )
        if (transition == null) {
            Toast.makeText(context, tr("フォルダを解散できませんでした", "Couldn't ungroup the folder"), Toast.LENGTH_SHORT).show()
            false
        } else {
            val updatedFavorites = favoriteIdsForHomeLayout(
                layout = transition.layout,
                favoriteIds = favoriteIds,
            )
            if (persistHomeLayoutTransaction(
                    context = context,
                    layout = transition.layout,
                    favoriteIds = updatedFavorites,
                    folders = transition.folders,
                )
            ) {
                favoriteIds = updatedFavorites
                homeFolders = transition.folders
                applyCommittedHomeLayout(transition.layout)
                actionFolder = null
                Toast.makeText(context, tr("フォルダを解散しました", "Folder ungrouped"), Toast.LENGTH_SHORT).show()
                true
            } else {
                Toast.makeText(context, tr("フォルダを解散できませんでした", "Couldn't ungroup the folder"), Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    /** Adds catalog apps through the same transaction that updates folder membership and home ownership. */
    val addAppsToFolder: (HomeFolder, List<LaunchableApp>) -> Boolean = { folder, selectedApps ->
        val currentFolder = homeFolders.firstOrNull { it.id == folder.id }
        if (currentFolder == null) {
            Toast.makeText(context, tr("フォルダを変更できませんでした", "Couldn't change the folder"), Toast.LENGTH_SHORT).show()
            false
        } else {
            val currentMembers = currentFolder.memberIds.toSet()
            val ownedByOtherFolder = homeFolders
                .filterNot { it.id == currentFolder.id }
                .flatMap { it.memberIds }
                .toSet()
            val installedIds = installedApps.mapTo(mutableSetOf(), ::favoriteId)
            val newIds = selectedApps
                .asSequence()
                .map(::favoriteId)
                .filter { it in installedIds && it !in currentMembers && it !in ownedByOtherFolder }
                .distinct()
                .toList()
            if (newIds.isEmpty()) {
                Toast.makeText(context, tr("追加するアプリを選択してください", "Choose apps to add"), Toast.LENGTH_SHORT).show()
                false
            } else {
                val updatedFolders = homeFolders.map { candidate ->
                    if (candidate.id == currentFolder.id) {
                        candidate.copy(memberIds = candidate.memberIds + newIds)
                    } else {
                        candidate
                    }
                }
                // Any selected app that was already a direct home item leaves that visible slot
                // atomically as it becomes a folder member. New favorites stay out of both
                // orders until they are returned to Home from the edit view.
                val updatedLayout = newIds.fold(currentHomeLayout()) { layout, id ->
                    removeHomeItemFromLayout(layout, id)
                }
                val updatedFavorites = favoriteIdsForHomeLayout(
                    layout = updatedLayout,
                    favoriteIds = favoriteIds + newIds,
                )
                if (persistHomeLayoutTransaction(
                        context = context,
                        layout = updatedLayout,
                        favoriteIds = updatedFavorites,
                        folders = updatedFolders,
                    )
                ) {
                    favoriteIds = updatedFavorites
                    homeFolders = updatedFolders
                    applyCommittedHomeLayout(updatedLayout)
                    true
                } else {
                    Toast.makeText(context, tr("アプリ追加を保存できませんでした", "Couldn't save the added apps"), Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }
    }

    val reorderFolderMembers: (HomeFolder, List<String>) -> Boolean = { folder, memberIds ->
        val updated = reorderHomeFolderMembers(homeFolders, folder.id, memberIds)
        if (updated == null) {
            Toast.makeText(context, tr("アプリの並び順を変更できませんでした", "Couldn't reorder the apps"), Toast.LENGTH_SHORT).show()
            false
        } else if (updated == homeFolders) {
            true
        } else if (persistHomeLayoutTransaction(
                context = context,
                layout = currentHomeLayout(),
                folders = updated,
            )
        ) {
            homeFolders = updated
            true
        } else {
            Toast.makeText(context, tr("アプリの並び順を保存できませんでした", "Couldn't save the app order"), Toast.LENGTH_SHORT).show()
            false
        }
    }

    val returnFolderAppToHome: (HomeFolder, LaunchableApp) -> Boolean = { folder, app ->
        val appId = favoriteId(app)
        val currentFolder = homeFolders.firstOrNull { it.id == folder.id }
        val transition = returnAppFromHomeFolder(
            layout = currentHomeLayout(),
            folders = homeFolders,
            appId = appId,
        )
        if (currentFolder == null || appId !in currentFolder.memberIds ||
            transition == null || appId !in transition.layout.allIds
        ) {
            Toast.makeText(context, tr("アプリをホームへ戻せませんでした", "Couldn't move the app back to Home"), Toast.LENGTH_SHORT).show()
            false
        } else {
            val updatedFavorites = favoriteIdsForHomeLayout(
                layout = transition.layout,
                favoriteIds = favoriteIds,
            )
            if (persistHomeLayoutTransaction(
                    context = context,
                    layout = transition.layout,
                    favoriteIds = updatedFavorites,
                    folders = transition.folders,
                )
            ) {
                favoriteIds = updatedFavorites
                homeFolders = transition.folders
                applyCommittedHomeLayout(transition.layout)
                true
            } else {
                Toast.makeText(context, tr("アプリのホーム復帰を保存できませんでした", "Couldn't save moving the app back to Home"), Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    val renameFolderInSheet: (HomeFolder, String) -> Boolean = { folder, name ->
        val updated = renameHomeFolder(homeFolders, folder.id, name)
        if (updated == null) {
            Toast.makeText(context, tr("フォルダ名を変更できませんでした", "Couldn't rename the folder"), Toast.LENGTH_SHORT).show()
            false
        } else if (updated == homeFolders) {
            true
        } else if (persistHomeLayoutTransaction(
                context = context,
                layout = currentHomeLayout(),
                folders = updated,
            )
        ) {
            homeFolders = updated
            true
        } else {
            Toast.makeText(context, tr("フォルダ名を保存できませんでした", "Couldn't save the folder name"), Toast.LENGTH_SHORT).show()
            false
        }
    }

    val requestPhotoSelection: (Boolean, Int, String) -> Unit = {
        addToHome,
        targetHomePage,
        widgetId,
    ->
        photoSelectionAddsWidget = addToHome
        photoSelectionHomePage = targetHomePage.coerceIn(0, homePages.count - 1)
        photoSelectionWidgetId = widgetId
        showWidgetSelector = false
        preferredWidgetPackage = null
        runCatching {
            photoPickerLauncher.launch(arrayOf("image/*", "video/*"))
        }.onFailure {
            photoSelectionAddsWidget = false
            photoSelectionWidgetId = null
            Toast.makeText(context, tr("画像または動画を選択できませんでした", "Couldn't choose the image or video"), Toast.LENGTH_SHORT).show()
        }
    }

    val addBuiltInWidget: (HomeWidget, Int) -> Unit = { widget, requestedTargetHomePage ->
        val targetHomePage = requestedTargetHomePage.coerceIn(0, homePages.count - 1)
        if (widget == HomeWidget.PHOTO) {
            // The widget is added only from the successful OpenDocument callback. In particular,
            // a picker cancellation cannot leave an empty PHOTO item on the board.
            requestPhotoSelection(
                true,
                targetHomePage,
                newPhotoWidgetHomeId(homePages.allIds),
            )
        } else {
            val currentLayout = currentHomeLayout()
            if (widget.id !in currentLayout.allIds) {
                val layout = addHomeItemToLayout(currentLayout, widget.id, targetHomePage)
                if (persistHomeLayoutTransaction(
                        context = context,
                        layout = layout,
                        widgetSizes = widgetSizeOverrides,
                        wideWidgetSizes = wideWidgetSizeOverrides,
                        folders = homeFolders,
                    )
                ) {
                    applyCommittedHomeLayout(layout)
                    Toast.makeText(context, tr("${widget.label}を追加しました", "Added ${widget.label}"), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, tr("${widget.label}を保存できませんでした", "Couldn't save ${widget.label}"), Toast.LENGTH_SHORT).show()
                }
            }
            showWidgetSelector = false
            preferredWidgetPackage = null
        }
    }
    val removeBuiltInWidget: (HomeItem.Widget, Int) -> Boolean = { item, _ ->
        val widget = item.widget
        if (widget == HomeWidget.PHOTO) {
            // Remove every stale occurrence, not only the page reported by the current posture.
            // The wide canvas derives that owner from the narrow projection and it can be one
            // frame behind during a fold transition.
            val updatedSizeMaps = removeHomeSizeIdFromMaps(
                narrow = HomeSizeMaps(widgetSizeOverrides = widgetSizeOverrides),
                wide = HomeSizeMaps(widgetSizeOverrides = wideWidgetSizeOverrides),
                id = item.id,
            )
            val updatedSizes = updatedSizeMaps.first.widgetSizeOverrides
            val updatedWideSizes = updatedSizeMaps.second.widgetSizeOverrides
            val updatedLayout = removeHomeItemFromLayout(currentHomeLayout(), item.id)
            val removal = removePhotoWidgetAtomically(
                context = context,
                homePages = updatedLayout.toHomePages(),
                layout = updatedLayout,
                widgetSizes = updatedSizes,
                wideWidgetSizes = updatedWideSizes,
                widgetId = item.id,
                folders = homeFolders,
            )
            if (!removal.committed) {
                // Keep the action dialog and all in-memory state intact when the durable removal
                // fails; in particular, do not release the grant still referenced by the URI key.
                Toast.makeText(context, tr("フォトフレームを削除できませんでした", "Couldn't remove the photo frame"), Toast.LENGTH_SHORT).show()
                false
            } else {
                applyCommittedHomeLayout(updatedLayout)
                widgetSizeOverrides = updatedSizes
                wideWidgetSizeOverrides = updatedWideSizes
                photoUris = photoUris - item.id
                photoVideoMutes = photoVideoMutes - item.id
                removal.removedUri?.let { removedUri ->
                    if (removedUri !in photoUris.values) {
                        releasePhotoUriPermission(context, removedUri)
                    }
                }
                actionWidget = null
                Toast.makeText(context, tr("${widget.label}を削除しました", "Removed ${widget.label}"), Toast.LENGTH_SHORT).show()
                true
            }
        } else {
            val currentLayout = currentHomeLayout()
            val layout = removeHomeItemFromLayout(currentLayout, widget.id)
            if (layout != currentLayout) {
                val updatedSizeMaps = removeHomeSizeIdFromMaps(
                    narrow = HomeSizeMaps(widgetSizeOverrides = widgetSizeOverrides),
                    wide = HomeSizeMaps(widgetSizeOverrides = wideWidgetSizeOverrides),
                    id = widget.id,
                )
                val updatedSizes = updatedSizeMaps.first.widgetSizeOverrides
                val updatedWideSizes = updatedSizeMaps.second.widgetSizeOverrides
                if (persistHomeLayoutTransaction(
                        context = context,
                        layout = layout,
                        widgetSizes = updatedSizes,
                        wideWidgetSizes = updatedWideSizes,
                        folders = homeFolders,
                    )
                ) {
                    applyCommittedHomeLayout(layout)
                    widgetSizeOverrides = updatedSizes
                    wideWidgetSizeOverrides = updatedWideSizes
                    Toast.makeText(context, tr("${widget.label}を削除しました", "Removed ${widget.label}"), Toast.LENGTH_SHORT).show()
                    true
                } else {
                    Toast.makeText(context, tr("${widget.label}を削除できませんでした", "Couldn't remove ${widget.label}"), Toast.LENGTH_SHORT).show()
                    false
                }
            } else {
                // The item was already gone. Treat this as a successful no-op so the stale
                // action sheet can be closed without pretending a save occurred.
                true
            }
        }
    }

    val setPhotoVideoMute: (String, Boolean) -> Unit = { widgetId, muted ->
        val updatedMutes = photoVideoMutes + (widgetId to muted)
        if (savePhotoVideoMutes(context, updatedMutes)) {
            photoVideoMutes = updatedMutes
        } else {
            Toast.makeText(context, tr("ミュート設定を保存できませんでした", "Couldn't save the mute setting"), Toast.LENGTH_SHORT).show()
        }
    }
    val removeExternalWidget: (LauncherWidgetDescriptor) -> Boolean = { descriptor ->
        // The dialog may outlive a provider refresh. Never let a stale dialog delete a recycled
        // framework ID that now belongs to another provider; a stale action is a successful no-op
        // and the next composition will discard the obsolete board entry.
        val currentDescriptor = externalWidgets.firstOrNull {
            it.homeId == descriptor.homeId &&
                it.appWidgetId == descriptor.appWidgetId &&
                it.provider == descriptor.provider
        }
        if (currentDescriptor == null) {
            true
        } else {
            val currentLayout = currentHomeLayout()
            val updatedWidgets = externalWidgets.filterNot { it.appWidgetId == descriptor.appWidgetId }
            val layout = removeHomeItemFromLayout(currentLayout, descriptor.homeId)
            val updatedSizeMaps = removeHomeSizeIdFromMaps(
                narrow = HomeSizeMaps(widgetSizeOverrides = widgetSizeOverrides),
                wide = HomeSizeMaps(widgetSizeOverrides = wideWidgetSizeOverrides),
                id = descriptor.homeId,
            )
            val updatedSizes = updatedSizeMaps.first.widgetSizeOverrides
            val updatedWideSizes = updatedSizeMaps.second.widgetSizeOverrides
            if (updatedWidgets == externalWidgets &&
                layout == currentLayout &&
                updatedSizes == widgetSizeOverrides &&
                updatedWideSizes == wideWidgetSizeOverrides
            ) {
                true
            } else if (persistHomeLayoutTransaction(
                    context = context,
                    layout = layout,
                    widgetSizes = updatedSizes,
                    wideWidgetSizes = updatedWideSizes,
                    descriptors = updatedWidgets,
                    folders = homeFolders,
                )
            ) {
                (context as? MainActivity)?.deleteAppWidgetIdSafely(descriptor.appWidgetId)
                externalWidgets = updatedWidgets
                applyCommittedHomeLayout(layout)
                widgetSizeOverrides = updatedSizes
                wideWidgetSizeOverrides = updatedWideSizes
                actionWidget = null
                val removedLabel = descriptor.label.ifBlank { tr("ウィジェット", "Widget") }
                Toast.makeText(context, tr("${removedLabel}を削除しました", "Removed $removedLabel"), Toast.LENGTH_SHORT).show()
                true
            } else {
                Toast.makeText(context, tr("ウィジェットを削除できませんでした", "Couldn't remove the widget"), Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    val removePinnedShortcut: (ResolvedPinnedShortcut) -> Boolean = { shortcut ->
        // The action sheet may remain open while a package/profile callback refreshes the query.
        // Match the opaque instance ID and platform identity before removing anything so a stale
        // sheet can never delete a newly accepted duplicate.
        var localCommitFailed = false
        val removalCommit = synchronized(PinnedShortcutCleanupLock) {
            val currentRecords = readPinnedShortcutRecords(context)
            val persistedRecord = currentRecords.firstOrNull {
                it.instanceId == shortcut.record.instanceId &&
                    it.packageName == shortcut.record.packageName &&
                    it.shortcutId == shortcut.record.shortcutId &&
                    it.userSerial == shortcut.record.userSerial
            }
            if (persistedRecord == null) {
                null
            } else {
                val preferences = context.getSharedPreferences(
                    FavoritePreferencesName,
                    Context.MODE_PRIVATE,
                )
                // SharedPreferences.commit() can update its in-memory map before reporting a
                // disk failure. Snapshot every key touched by this removal so the failed attempt
                // cannot leave a half-written queue/record state for a lifecycle retry.
                val removalPreferenceSnapshot = listOf(
                    HomeLayoutKey,
                    HomePagesKey,
                    HomeOrderKey,
                    PinnedShortcutRecordsKey,
                    PendingPinnedShortcutUnpinsKey,
                ).map { key ->
                    key to (preferences.contains(key) to preferences.getString(key, null))
                }
                val removalRecord = persistedRecord
                val updatedRecords = currentRecords.filterNot {
                    it.instanceId == removalRecord.instanceId
                }
                val updatedPending = pendingPinnedShortcutUnpinsAfterRemoval(
                    currentRecords = currentRecords,
                    currentPending = readPendingPinnedShortcutUnpins(context),
                    removedRecord = removalRecord,
                )
                val updatedLayout = removeHomeItemFromLayout(
                    currentHomeLayout(),
                    removalRecord.homeId,
                )
                val localCommit = runCatching {
                    persistHomeLayoutTransaction(
                        context = context,
                        layout = updatedLayout,
                        pinnedShortcuts = updatedRecords,
                        pendingPinnedShortcutUnpins = updatedPending,
                        folders = homeFolders,
                    )
                }.getOrDefault(false)
                if (localCommit) {
                    PinnedShortcutLocalRemovalCommit(
                        record = removalRecord,
                        updatedRecords = updatedRecords,
                        pendingUnpins = updatedPending,
                        updatedLayout = updatedLayout,
                    )
                } else {
                    val rollbackEditor = preferences.edit()
                    removalPreferenceSnapshot.forEach { (key, snapshot) ->
                        val (wasPresent, rawValue) = snapshot
                        if (wasPresent) {
                            rollbackEditor.putString(key, rawValue)
                        } else {
                            rollbackEditor.remove(key)
                        }
                    }
                    // commit() restores the in-memory map before returning even when its disk
                    // write reports false. If it throws, apply() still restores that snapshot.
                    runCatching { rollbackEditor.commit() }
                        .onFailure { rollbackEditor.apply() }
                    localCommitFailed = true
                    null
                }
            }
        }
        if (removalCommit == null && localCommitFailed) {
            Toast.makeText(context, tr("ショートカットを削除できませんでした", "Couldn't remove the shortcut"), Toast.LENGTH_SHORT).show()
            false
        } else if (removalCommit == null) {
            actionPinnedShortcut = null
            true
        } else {
            pinnedShortcuts = pinnedShortcuts.filterNot {
                it.record.instanceId == removalCommit.record.instanceId
            }
            applyCommittedHomeLayout(removalCommit.updatedLayout)
            actionPinnedShortcut = null
            pendingPinnedShortcutCleanupRevision++
            Toast.makeText(context, tr("ショートカットをホームから削除しました", "Removed the shortcut from Home"), Toast.LENGTH_SHORT).show()
            true
        }
    }

    val previewHomeOrder: (Int, List<String>) -> Unit = { ownerPage, updated ->
        val normalized = updated.distinct()
        // Returning to the persisted order must clear an earlier preview instead of leaving the
        // stale transient list visible until drop.
        homePreviewPage = ownerPage
        homePreviewOrder = normalized.takeUnless { it == homePages[ownerPage] }
    }
    val commitHomeOrder: (Int, List<String>) -> Unit = { ownerPage, updated ->
        homePreviewPage = null
        homePreviewOrder = null
        val normalized = updated.distinct()
        val currentLayout = currentHomeLayout()
        val updatedLayout = homeLayoutAfterNarrowReorder(
            layout = currentLayout,
            page = ownerPage,
            order = normalized,
            separateWideOrder = currentSeparateWideHomeOrder.value,
        )
        val updatedHomePages = updatedLayout.toHomePages()
        if (updatedLayout != currentLayout) {
            // App favorites retain the same relative order as the committed home board.
            // Folder members are intentionally absent from the visible order but remain
            // favorites so a later reload can resolve them back into their folder.
            val orderedFavoriteIds = favoriteIdsForHomeLayout(
                layout = updatedLayout,
                favoriteIds = favoriteIds,
            )
            if (persistHomeLayoutTransaction(
                    context = context,
                    layout = updatedLayout,
                    favoriteIds = orderedFavoriteIds,
                    folders = homeFolders,
                )
            ) {
                applyCommittedHomeLayout(updatedLayout)
                favoriteIds = orderedFavoriteIds
            }
        }
    }
    val cancelHomeOrderPreview: (Int) -> Unit = { ownerPage ->
        if (homePreviewPage == ownerPage) {
            homePreviewPage = null
            homePreviewOrder = null
        }
    }

    BackHandler(enabled = showSettingsScreen) {
        showSettingsScreen = false
    }

    BackHandler(enabled = !showSettingsScreen) {
        val currentPresentation = launcherHomePresentationFor(
            currentPosture,
        )
        if (currentPresentation == LauncherHomePresentation.START_CANVAS &&
            page == LauncherPage.DRAWER
        ) {
            page = LauncherPage.HOME
            wideCanvasAnchor = LauncherDestination.HOME
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        } else if (page != LauncherPage.HOME) {
            navigateToDestination(
                destination = launcherHomeDestination(selectedHomePage),
                withHaptic = false,
                explicitHomeTap = false,
            )
            contextMode = ContextMode.GLANCE
        }
    }

    LaunchedEffect(
        folderExpansionSession?.folder?.id,
        homeFolders,
        page,
        currentPosture,
        lifecycleRefreshToken,
    ) {
        val session = folderExpansionSession ?: return@LaunchedEffect
        when {
            homeFolders.none { it.id == session.folder.id } -> {
                // The model is authoritative. If a folder is dissolved or removed while the
                // transient frame is alive, drop it immediately so the source cannot resurrect
                // a stale presentation.
                folderExpansionSession = null
            }
            session.lifecycleRefreshToken != lifecycleRefreshToken -> {
                // A resume refresh can replace the installed-app snapshot and home geometry.
                // The captured source rectangle is no longer authoritative after that boundary.
                folderExpansionSession = null
            }
            page != LauncherPage.HOME || session.posture != currentPosture -> {
                // Destination and posture changes invalidate root coordinates. Restore the source
                // immediately instead of animating toward a stale rectangle from the old layout.
                folderExpansionSession = null
            }
        }
    }

    LauncherGlassHost(modifier = Modifier.fillMaxSize()) {
        val glassEnabled = LocalLauncherGlass.current.enabled
        Box(
            modifier = Modifier
                .fillMaxSize()
                .launcherGlassBaseBackground(FiiLDABlack)
                .then(if (showSettingsScreen) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = LauncherHorizontalMargin),
        ) {
            BoxWithPosture(
                modifier = Modifier
                    .weight(1f)
                    // The top inset belongs to each surface's scrollable content. Keeping it out of
                    // this shared viewport lets HOME and DRAWER move behind the status bar without
                    // changing the viewport height when the active surface changes.
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                    ),
                content = { posture ->
                val homePresentation = launcherHomePresentationFor(posture)
                val navigationVisible = homePresentation != LauncherHomePresentation.START_CANVAS &&
                    !(glassEnabled && WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 0.dp)
                val navigationSafeBottom = WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Bottom)
                    .asPaddingValues()
                    .calculateBottomPadding()
                val navigationOcclusion = if (navigationVisible) {
                    if (navigationBarHeightPx > 0) {
                        with(LocalDensity.current) { navigationBarHeightPx.toDp() }
                    } else {
                        61.dp + navigationSafeBottom
                    }
                } else {
                    0.dp
                }
                // Glass scrolls content under the whole bar; the opaque themes stop above it and
                // only need room for the edge fade so the last row can scroll fully into view.
                val navigationScrollPadding = when {
                    glassEnabled -> navigationOcclusion
                    navigationVisible -> NavigationEdgeFadeHeight
                    else -> 0.dp
                }
                // A posture or destination change can produce one composition before the
                // cleanup effect runs. Do not hide the source or render an overlay from the old
                // root coordinate space during that frame.
                val activeFolderExpansionSession = folderExpansionSession?.takeIf {
                    it.posture == posture &&
                        it.lifecycleRefreshToken == lifecycleRefreshToken &&
                        page == LauncherPage.HOME
                }
                val postureChanged = previousPosture != null && previousPosture != posture
                val displayedHomePages = if (!postureChanged &&
                    homePreviewPage != null &&
                    homePreviewOrder != null
                ) {
                    homePages.withPage(homePreviewPage!!, homePreviewOrder!!)
                } else {
                    homePages
                }
                // A posture switch invalidates board geometry and any transient order. The
                // postureChanged branch above hides that transient frame immediately; this
                // side effect then clears the source state and shared drag lease before the next
                // stable composition.
                SideEffect {
                    if (previousPosture != posture) {
                        if (previousPosture != null) {
                            val fromPresentation = launcherHomePresentationFor(previousPosture!!)
                            val transition = mapLauncherPostureTransition(
                                from = fromPresentation,
                                to = homePresentation,
                                page = page,
                                wideAnchor = wideCanvasAnchor,
                                selectedHomePage = selectedHomePage,
                                wideHomeOwnerPage = wideHomeOwnerPage,
                                homePageCount = homePages.count,
                            )
                            val wasWide = fromPresentation == LauncherHomePresentation.START_CANVAS
                            if (!wasWide && homePresentation == LauncherHomePresentation.START_CANVAS) {
                                wideHomeOwnerPage = selectedHomePage.coerceIn(0, homePages.count - 1)
                            }
                            page = transition.page
                            selectedHomePage = transition.selectedHomePage
                            wideCanvasAnchor = transition.wideAnchor
                            // A drag preview is never a posture-level navigation state. Restore
                            // the last committed snapshot before the new surface is allowed to
                            // measure, so a transition cannot commit a stale order on drop.
                            wideHomeOrder = persistedWideHomeOrder
                            homePreviewPage = null
                            homePreviewOrder = null
                            homePreviewWideOrder = null
                            homeDragCoordinator.reset()
                        }
                        previousPosture = posture
                    }
                }
                var folderOverlayOrigin by remember { mutableStateOf(IntOffset.Zero) }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coordinates ->
                            val position = coordinates.positionInRoot()
                            val next = IntOffset(position.x.roundToInt(), position.y.roundToInt())
                            if (next != folderOverlayOrigin) folderOverlayOrigin = next
                        },
                ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            // Legacy themes keep their original viewport above the bar. Glass
                            // extends below the same sibling bar, with padding inside each scroll
                            // container instead. The content call site stays stable across themes.
                            .padding(bottom = if (glassEnabled) 0.dp else navigationOcclusion)
                            .clipToBoundsWithSideMargin()
                            .launcherGlassBaseBackground(FiiLDABlack),
                    ) {
                    val viewportWidthPx = with(LocalDensity.current) {
                        maxWidth.toPx().roundToInt()
                    }
                    val homeVisualTarget = launcherSurfaceLayerVisualTarget(
                        visiblePage = page,
                        layer = LauncherPage.HOME,
                        posture = posture,
                        viewportWidthPx = viewportWidthPx,
                        transparentBackground = glassEnabled,
                    )
                    val drawerVisualTarget = launcherSurfaceLayerVisualTarget(
                        visiblePage = page,
                        layer = LauncherPage.DRAWER,
                        posture = posture,
                        viewportWidthPx = viewportWidthPx,
                        transparentBackground = glassEnabled,
                    )
                    // Reset only animation values when posture or glass visibility rules change.
                    // Home and Drawer stay composed, while a hidden layer immediately adopts
                    // transparent-theme opacity instead of leaking its previous opaque state.
                    val animatedSurfaceStates = key(posture, glassEnabled) {
                        val surfaceTransitionSpec = spring<Float>(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        )
                        val animatedHomeAlpha = animateFloatAsState(
                            targetValue = homeVisualTarget.alpha,
                            animationSpec = surfaceTransitionSpec,
                            label = "home surface alpha",
                        )
                        val animatedHomeScale = animateFloatAsState(
                            targetValue = homeVisualTarget.scale,
                            animationSpec = surfaceTransitionSpec,
                            label = "home surface scale",
                        )
                        val animatedHomeBlurRadiusDp = animateFloatAsState(
                            targetValue = homeVisualTarget.blurRadiusDp,
                            animationSpec = surfaceTransitionSpec,
                            label = "home surface blur",
                        )
                        val animatedDrawerAlpha = animateFloatAsState(
                            targetValue = drawerVisualTarget.alpha,
                            animationSpec = surfaceTransitionSpec,
                            label = "drawer surface alpha",
                        )
                        val animatedDrawerScale = animateFloatAsState(
                            targetValue = drawerVisualTarget.scale,
                            animationSpec = surfaceTransitionSpec,
                            label = "drawer surface scale",
                        )
                        val animatedDrawerBlurRadiusDp = animateFloatAsState(
                            targetValue = drawerVisualTarget.blurRadiusDp,
                            animationSpec = surfaceTransitionSpec,
                            label = "drawer surface blur",
                        )
                        LauncherSurfaceAnimationStates(
                            homeAlpha = animatedHomeAlpha,
                            homeScale = animatedHomeScale,
                            homeBlurRadiusDp = animatedHomeBlurRadiusDp,
                            drawerAlpha = animatedDrawerAlpha,
                            drawerScale = animatedDrawerScale,
                            drawerBlurRadiusDp = animatedDrawerBlurRadiusDp,
                        )
                    }
                    // A posture change is a layout boundary. Snap wide content to the centered
                    // origin and snap narrow visuals back to their legacy settled form so an
                    // interrupted wide reveal cannot leak into the other posture.
                    val narrowFadeOnly = glassEnabled || posture == Posture.INNER_LANDSCAPE
                    val wide = posture == Posture.INNER_LANDSCAPE
                    val homeAlpha: () -> Float = { if (narrowFadeOnly) animatedSurfaceStates.homeAlpha.value else 1f }
                    val homeScale: () -> Float = { if (wide) animatedSurfaceStates.homeScale.value else 1f }
                    val homeBlurRadiusDp: () -> Float = { if (wide) animatedSurfaceStates.homeBlurRadiusDp.value else 0f }
                    val drawerAlpha: () -> Float = { if (narrowFadeOnly) animatedSurfaceStates.drawerAlpha.value else 1f }
                    val drawerScale: () -> Float = { if (wide) animatedSurfaceStates.drawerScale.value else 1f }
                    val drawerBlurRadiusDp: () -> Float = { if (wide) animatedSurfaceStates.drawerBlurRadiusDp.value else 0f }
                    val animatedHomeOffset = animateIntOffsetAsState(
                        targetValue = IntOffset(
                            x = homeVisualTarget.offsetPx,
                            y = 0,
                        ),
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        label = "home surface transition",
                    )
                    val animatedDrawerOffset = animateIntOffsetAsState(
                        targetValue = IntOffset(
                            x = drawerVisualTarget.offsetPx,
                            y = 0,
                        ),
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        label = "drawer surface transition",
                    )
                    val reduceMotion = rememberReduceMotion()
                    val maxSurfaceBlurPx = with(LocalDensity.current) { MaxSurfaceMotionBlur.toPx() }
                    // The narrow Home/Drawer switch is a sideways slide; blur each surface by how
                    // far it is from its settled place. The wide posture keeps its own reveal blur.
                    val slideBlur: (() -> Int) -> Modifier = { offsetX ->
                        // As on Home pages, glass skips the surface blur: it would re-render
                        // every glass tile into an extra offscreen layer each frame.
                        if (reduceMotion || glassEnabled || posture == Posture.INNER_LANDSCAPE || viewportWidthPx <= 0) {
                            Modifier
                        } else {
                            Modifier.motionBlur {
                                maxSurfaceBlurPx * surfaceMotionBlurFraction(offsetX() / viewportWidthPx.toFloat())
                            }
                        }
                    }
                    val homeOffset: () -> IntOffset = { if (wide) IntOffset.Zero else animatedHomeOffset.value }
                    val drawerOffset: () -> IntOffset = { if (wide) IntOffset.Zero else animatedDrawerOffset.value }
                    // Scene membership changes only at the ends of a transition, and the scene
                    // alpha is coarse, so contributors recompose a few times rather than per frame.
                    val homeSceneVisible by remember(animatedSurfaceStates, viewportWidthPx, wide) {
                        derivedStateOf { homeAlpha() > 0f && kotlin.math.abs(homeOffset().x) < viewportWidthPx }
                    }
                    val drawerSceneVisible by remember(animatedSurfaceStates, viewportWidthPx, wide) {
                        derivedStateOf { drawerAlpha() > 0f && kotlin.math.abs(drawerOffset().x) < viewportWidthPx }
                    }
                    val homeSceneAlpha by remember(animatedSurfaceStates, wide) {
                        derivedStateOf { (homeAlpha() * 4f).roundToInt() / 4f }
                    }
                    val drawerSceneAlpha by remember(animatedSurfaceStates, wide) {
                        derivedStateOf { (drawerAlpha() * 4f).roundToInt() / 4f }
                    }
                    val homeGeometry = remember(animatedSurfaceStates, posture, page) {
                        GlassGeometrySignal {
                            listOf("home", homeAlpha(), homeScale(), homeOffset(), homeBlurRadiusDp())
                        }
                    }
                    val drawerGeometry = remember(animatedSurfaceStates, posture, page) {
                        GlassGeometrySignal {
                            listOf("drawer", drawerAlpha(), drawerScale(), drawerOffset(), drawerBlurRadiusDp())
                        }
                    }

                    // Both surfaces remain composed exactly once, with the current layer above.
                    // Opaque themes retain their backings; glass fades the inactive content and
                    // its scene contribution together so it cannot show through tile gaps.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // Only the visible layer reaches into the side margin; the hidden one
                            // sits slightly offset behind it and must not peek out there.
                            .clipToBoundsWithSideMargin(expand = page == LauncherPage.HOME)
                            .zIndex(launcherSurfaceLayerZIndex(page, LauncherPage.HOME))
                            .then(
                                if (posture == Posture.INNER_LANDSCAPE) {
                                    Modifier
                                } else {
                                    Modifier.launcherGlassBaseBackground(FiiLDABlack)
                                },
                            )
                            .then(
                                if (page == LauncherPage.HOME && activeFolderExpansionSession == null) {
                                    Modifier
                                } else {
                                    Modifier.clearAndSetSemantics {}
                                },
                            ),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .launcherSurfaceVisual(
                                    enabled = glassEnabled || posture == Posture.INNER_LANDSCAPE,
                                    alpha = homeAlpha,
                                    scale = homeScale,
                                    blurRadiusDp = homeBlurRadiusDp,
                                )
                                .then(slideBlur { homeOffset().x })
                                .then(
                                    if (posture == Posture.INNER_LANDSCAPE) {
                                        Modifier.launcherGlassBaseBackground(FiiLDABlack)
                                    } else {
                                        Modifier
                                    },
                                )
                                .offset { homeOffset() },
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                if (posture == Posture.INNER_LANDSCAPE) {
                                    Header(
                                        showWideSwitcher = true,
                                        page = LauncherPage.HOME,
                                        onToggleWideSurface = {
                                            navigateToDestination(
                                                destination = wideHeaderTargetDestination(LauncherPage.HOME),
                                                withHaptic = true,
                                                presentation = LauncherHomePresentation.START_CANVAS,
                                            )
                                        },
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        // StartCanvas has no footer in the wide posture. Preserve its
                                        // existing safe viewport while the sibling drawer can use the
                                        // full height and place its own trailing scroll padding.
                                        .then(
                                            if (posture == Posture.INNER_LANDSCAPE) {
                                                Modifier.windowInsetsPadding(
                                                    WindowInsets.systemBars
                                                        .union(WindowInsets.displayCutout)
                                                        .only(WindowInsetsSides.Bottom),
                                                )
                                            } else {
                                                Modifier
                                            },
                                        ),
                                ) {
                                    LauncherGlassSceneScope(
                                        enabled = !showSettingsScreen &&
                                            activeFolderExpansionSession == null &&
                                            homeSceneVisible,
                                        alpha = homeSceneAlpha,
                                        zIndex = launcherSurfaceLayerZIndex(page, LauncherPage.HOME),
                                        geometryVersion = homeGeometry,
                                    ) {
                                    HomeSurface(
                                    posture = posture,
                                    isVisible = page == LauncherPage.HOME,
                                    navigationBottomPadding = navigationScrollPadding,
                                    glassSceneEnabled = activeFolderExpansionSession == null,
                                    glassSceneAlpha = 1f,
                                    glassSceneZIndex = 0f,
                                    glassSceneGeometryVersion = homeGeometry,
                                    now = now,
                                    installedApps = installedApps,
                                    homePages = displayedHomePages,
                                    separateWideHomeOrder = separateWideHomeOrder,
                                    wideAppTileSizes = wideAppTileSizes,
                                    wideWidgetSizeOverrides = wideWidgetSizeOverrides,
                                    wideHomeOrder = if (!separateWideHomeOrder) {
                                        homePreviewWideOrder ?: currentHomeLayout().narrowOrder
                                    } else {
                                        wideHomeOrder
                                    },
                                    wideCanvasLastHomePosition = wideCanvasLastHomePosition,
                                    lifecycleRefreshToken = lifecycleRefreshToken,
                                    pagerState = homePagerState,
                                    selectedPackage = selectedPackage,
                                    appTileSizes = appTileSizes,
                                    appTileContentModes = appTileContentModes,
                                    appShortcuts = appShortcuts,
                                    notificationState = notificationState,
                                    widgetSizeOverrides = widgetSizeOverrides,
                                    pinnedShortcuts = pinnedShortcuts,
                                    homeFolders = homeFolders,
                                    // The renewed presentation is a bottom sheet, so keep its
                                    // source tile rendered beneath the scrim. The home layer is
                                    // still semantics-shielded while the overlay is active above.
                                    expandedFolderId = null,
                                    onFolderOpen = { folder, bounds ->
                                        folderExpansionSession = FolderExpansionSession(
                                            folder = folder,
                                            sourceBounds = bounds,
                                            overlayOrigin = folderOverlayOrigin,
                                            posture = posture,
                                            lifecycleRefreshToken = lifecycleRefreshToken,
                                        )
                                    },
                                    onWeather = { contextMode = ContextMode.WEATHER },
                                    onCalendar = { contextMode = ContextMode.CALENDAR },
                                    onMedia = { contextMode = ContextMode.MEDIA },
                                    photoUris = photoUris,
                                    photoVideoMutes = photoVideoMutes,
                                    onPhotoMuteChanged = setPhotoVideoMute,
                                    onPhotoPreview = { widgetId -> photoPreviewWidgetId = widgetId },
                                    onOpenApp = { app ->
                                        selectedPackage = app.packageIdentity()
                                        if (launchApp(context, app) != AppLaunchResult.STARTED) {
                                            onAppLaunchFailure()
                                        }
                                    },
                                    onOpenShortcut = { app, shortcut ->
                                        selectedPackage = app.packageIdentity()
                                        launchShortcut(context, app, shortcut)
                                    },
                                    onOpenPinnedShortcut = { shortcut ->
                                        selectedPackage = shortcut.record.packageName
                                        if (!launchPinnedShortcut(context, shortcut)) {
                                            Toast.makeText(
                                                context,
                                                tr("ショートカットを開けませんでした", "Couldn't open the shortcut"),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            onAppLaunchFailure()
                                        }
                                    },
                                    onLongPressApp = { app, ownerPage, presentation ->
                                        actionApp = app
                                        actionAppHomePage = ownerPage
                                        actionAppPresentation = presentation
                                    },
                                    onLongPressWidget = { item, ownerPage, presentation ->
                                        actionWidget = item
                                        actionWidgetHomePage = ownerPage
                                        actionWidgetPresentation = presentation
                                    },
                                    onLongPressPinnedShortcut = { shortcut, _, _ ->
                                        actionPinnedShortcut = shortcut
                                    },
                                    onLongPressFolder = { folder, ownerPage, presentation ->
                                        actionFolder = folder
                                        actionFolderHomePage = ownerPage
                                        actionFolderPresentation = presentation
                                    },
                                    appWidgetHost = appWidgetHost,
                                    appWidgetManager = appWidgetManager,
                                    externalWidgets = externalWidgets,
                                    homeDragCoordinator = homeDragCoordinator,
                                    onPreviewOrder = previewHomeOrder,
                                    onCommitOrder = commitHomeOrder,
                                    onCancelOrder = cancelHomeOrderPreview,
                                    onFolderDrop = { draggedId, targetId ->
                                        val transition = homeFolderDropTransition(
                                            layout = currentHomeLayout(),
                                            folders = homeFolders,
                                            draggedAppId = draggedId,
                                            targetId = targetId,
                                        )
                                        if (transition == null) {
                                            false
                                        } else if (!persistHomeLayoutTransaction(
                                                context = context,
                                                layout = transition.layout,
                                                favoriteIds = favoriteIds,
                                                folders = transition.folders,
                                            )
                                        ) {
                                            Toast.makeText(
                                                context,
                                                tr("フォルダを保存できませんでした", "Couldn't save the folder"),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            false
                                        } else {
                                            homeFolders = transition.folders
                                            // Folder creation/removal changes item ownership, so
                                            // discard every presentation's transient reorder
                                            // projection before publishing the new committed layout.
                                            // A failed persistence path above intentionally retains
                                            // the preview for the normal cancel/rollback flow.
                                            homePreviewPage = null
                                            homePreviewOrder = null
                                            homePreviewWideOrder = null
                                            applyCommittedHomeLayout(transition.layout)
                                            true
                                        }
                                    },
                                    onPreviewWideOrder = { updated ->
                                        if (currentSeparateWideHomeOrder.value) {
                                            wideHomeOrder = updated.distinct()
                                        } else {
                                            homePreviewWideOrder = updated.distinct()
                                        }
                                    },
                                    onCommitWideOrder = { updated ->
                                        val currentLayout = currentHomeLayout()
                                        // Shared mode treats a reorder from either presentation as an
                                        // explicit synchronization event. Toggling the setting alone
                                        // never reaches this path, so a preserved independent order
                                        // remains available when the setting is re-enabled.
                                        val normalized = homeLayoutAfterWideReorder(
                                            layout = currentLayout,
                                            order = updated,
                                            separateWideOrder = currentSeparateWideHomeOrder.value,
                                        )
                                        homePreviewWideOrder = null
                                        val orderedFavoriteIds = favoriteIdsForHomeLayout(
                                            layout = normalized,
                                            favoriteIds = favoriteIds,
                                        )
                                        if (persistHomeLayoutTransaction(
                                                context = context,
                                                layout = normalized,
                                                favoriteIds = orderedFavoriteIds,
                                                folders = homeFolders,
                                            )
                                        ) {
                                            applyCommittedHomeLayout(normalized)
                                            favoriteIds = orderedFavoriteIds
                                        } else {
                                            wideHomeOrder = currentLayout.order
                                            // The v4 editor is transactional; restore the exact
                                            // pre-commit projection as well as the order if the commit
                                            // fails, instead of deriving ownership from the preview.
                                            homePages = currentLayout.toHomePages()
                                        }
                                    },
                                    onCancelWideOrder = {
                                        homePreviewWideOrder = null
                                        wideHomeOrder = persistedWideHomeOrder
                                    },
                                    onCanvasHomePositionChanged = { position ->
                                        wideCanvasLastHomePosition = position
                                    },
                                    )
                                    }
                                }
                            }
                        }
                        if (page != LauncherPage.HOME) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .hiddenSurfaceInteractionShield(),
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBoundsWithSideMargin(expand = page == LauncherPage.DRAWER)
                            .zIndex(launcherSurfaceLayerZIndex(page, LauncherPage.DRAWER))
                            .then(
                                if (posture == Posture.INNER_LANDSCAPE) {
                                    Modifier
                                } else {
                                    Modifier.launcherGlassBaseBackground(FiiLDABlack)
                                },
                            )
                            .then(
                                if (page == LauncherPage.DRAWER && activeFolderExpansionSession == null) {
                                    Modifier
                                } else {
                                    Modifier.clearAndSetSemantics {}
                                },
                            ),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .launcherSurfaceVisual(
                                    enabled = glassEnabled || posture == Posture.INNER_LANDSCAPE,
                                    alpha = drawerAlpha,
                                    scale = drawerScale,
                                    blurRadiusDp = drawerBlurRadiusDp,
                                )
                                .then(slideBlur { drawerOffset().x })
                                .then(
                                    if (posture == Posture.INNER_LANDSCAPE) {
                                        Modifier.launcherGlassBaseBackground(FiiLDABlack)
                                    } else {
                                        Modifier
                                    },
                                )
                                .offset { drawerOffset() },
                        ) {
                            LauncherGlassSceneScope(
                                enabled = !showSettingsScreen &&
                                    activeFolderExpansionSession == null &&
                                    drawerSceneVisible,
                                alpha = drawerSceneAlpha,
                                zIndex = launcherSurfaceLayerZIndex(page, LauncherPage.DRAWER),
                                geometryVersion = drawerGeometry,
                            ) {
                            AppDrawer(
                                posture = posture,
                                query = query,
                                apps = filteredApps,
                                navigationBottomPadding = navigationScrollPadding,
                                searchController = drawerSearchController,
                                allowSearchTargetPickerWhenInactive = showSettingsScreen,
                                selectedPackage = selectedPackage,
                                resetRequest = drawerResetRequest,
                                onQueryChange = { query = it },
                                onToggleWideSurface = {
                                    navigateToDestination(
                                        destination = wideHeaderTargetDestination(LauncherPage.DRAWER),
                                        withHaptic = true,
                                        presentation = LauncherHomePresentation.START_CANVAS,
                                    )
                                },
                                onLongPressApp = {
                                    actionApp = it
                                    actionAppHomePage = null
                                    actionAppPresentation = HomeSizePresentation.NARROW
                                },
                                onOpenApp = { app ->
                                    selectedPackage = app.packageIdentity()
                                    if (launchApp(context, app) != AppLaunchResult.STARTED) {
                                        onAppLaunchFailure()
                                    }
                                },
                            )
                            }
                        }
                        if (page != LauncherPage.DRAWER) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .hiddenSurfaceInteractionShield(),
                            )
                        }
                    }
                    if (navigationVisible && !glassEnabled) {
                        // Opaque themes clip content at the bar's top edge. Fade it into the
                        // background there so tiles are not cut on a straight line next to the
                        // bar's rounded corners. Draw-only: touches pass through.
                        val fadeColor = FiiLDABlack
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(NavigationEdgeFadeHeight)
                                .zIndex(2f)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(fadeColor.copy(alpha = 0f), fadeColor),
                                    ),
                                ),
                        )
                    }
                    }
                    if (navigationVisible) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .onSizeChanged { measured ->
                                    if (navigationBarHeightPx != measured.height) {
                                        navigationBarHeightPx = measured.height
                                    }
                                }
                                .windowInsetsPadding(
                                    WindowInsets.systemBars
                                        .union(WindowInsets.displayCutout)
                                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                                )
                                .then(
                                    if (activeFolderExpansionSession == null) {
                                        Modifier
                                    } else {
                                        Modifier.clearAndSetSemantics {}
                                    },
                                ),
                        ) {
                            ContextBar(
                                destination = launcherDestinationFor(
                                    page = page,
                                    selectedHomePage = selectedHomePage,
                                    presentation = homePresentation,
                                ),
                                presentation = homePresentation,
                                selectedHomePage = selectedHomePage,
                                homePageCount = homePages.count,
                                glassSceneEnabled = activeFolderExpansionSession == null,
                                glassGeometryVersion = "navigation-$posture-$page-$homePresentation-$navigationBarHeightPx",
                                onSelect = { destination ->
                                    navigateToDestination(
                                        destination = destination,
                                        withHaptic = true,
                                        presentation = homePresentation,
                                        explicitHomeTap = true,
                                    )
                                },
                            )
                        }
                    }
                }
                }
            },
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        folderExpansionSession
            ?.takeIf {
                it.posture == currentPosture &&
                    it.lifecycleRefreshToken == lifecycleRefreshToken &&
                    page == LauncherPage.HOME
            }
            ?.let { session ->
                val currentFolder = homeFolders.firstOrNull { it.id == session.folder.id }
                if (currentFolder != null) {
                    val appById = installedApps.associateBy(::favoriteId)
                    FolderExpansionOverlay(
                        modifier = Modifier.fillMaxSize(),
                        session = session.copy(folder = currentFolder),
                        apps = currentFolder.memberIds.mapNotNull(appById::get),
                        allApps = installedApps,
                        unavailableAppIds = homeFolders
                            .filterNot { it.id == currentFolder.id }
                            .flatMapTo(mutableSetOf()) { it.memberIds },
                        onOpenApp = { app ->
                            folderExpansionSession = session.copy(isOpen = false)
                            selectedPackage = app.packageIdentity()
                            if (launchApp(context, app) != AppLaunchResult.STARTED) {
                                onAppLaunchFailure()
                            }
                        },
                        onAddApps = { selectedApps ->
                            addAppsToFolder(currentFolder, selectedApps)
                        },
                        onReorder = { memberIds ->
                            reorderFolderMembers(currentFolder, memberIds)
                        },
                        onRemoveApp = { app ->
                            returnFolderAppToHome(currentFolder, app)
                        },
                        onRename = { name ->
                            renameFolderInSheet(currentFolder, name)
                        },
                        onDismiss = {
                            if (session.isOpen) {
                                folderExpansionSession = session.copy(isOpen = false)
                            }
                        },
                        onFinishedClosing = {
                            val current = folderExpansionSession
                            if (current != null &&
                                current.folder.id == session.folder.id &&
                                !current.isOpen
                            ) {
                                folderExpansionSession = null
                            }
                        },
                    )
                }
            }
        }
    }

    actionApp?.let { app ->
        val actionAppId = favoriteId(app)
        val currentActionAppHomePage = currentHomeLayout()
            .takeIf {
                actionAppId in favoriteIds &&
                    actionAppId in it.allIds &&
                    installedApps.any { installed -> favoriteId(installed) == actionAppId }
            }
            ?.pageOf(actionAppId)
        val actionAppSizes = currentHomeSizeMaps(currentActionAppPresentation.value)
        AppActionDialog(
            app = app,
            isFavorite = favoriteIds.contains(actionAppId),
            tileSize = actionAppSizes.appTileSizes[actionAppId] ?: AppTileSize.SMALL,
            contentMode = appTileContentModeFor(actionAppId, appTileContentModes),
            notificationCount = projectFavoriteNotifications(
                notifications = notificationState.snapshots,
                favoritePackages = setOf(app.notificationPackageKey()),
            )[app.notificationPackageKey()].orEmpty().size,
            notificationAccessGranted = notificationListenerAccessGranted(context),
            canUninstall = canUninstallApp(context, app),
            initialTargetHomePage = actionAppHomePage ?: selectedHomePage,
            currentHomePage = currentActionAppHomePage,
            homePageCount = homePages.count,
            homePageChangePending = homePageChangePending,
            onAddHomePage = { done -> changeHomePages(onFinished = done) },
            onRemoveHomePage = { removed, done -> changeHomePages(removed, done) },
            onDismiss = { actionApp = null },
            onOpenAppInfo = {
                if (openAppDetails(context, app) == AppLaunchResult.STARTED) actionApp = null
            },
            onOpenSettings = {
                actionApp = null
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                showSettingsScreen = true
            },
            onAddFavorite = { targetHomePage ->
                addFavorite(app, targetHomePage)
            },
            onMoveFavorite = { targetHomePage ->
                moveFavorite(app, targetHomePage)
            },
            onRemoveFavorite = {
                removeFavorite(app)
            },
            onUninstall = {
                uninstallApp(context, app) == AppLaunchResult.STARTED
            },
            onSetTileSize = { size ->
                setAppTileSize(app, size)
            },
            onSetContentMode = { mode ->
                setAppTileContentMode(app, mode)
            },
            onOpenNotificationSettings = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }.onFailure {
                    Toast.makeText(context, tr("通知へのアクセス設定を開けませんでした", "Couldn't open notification access settings"), Toast.LENGTH_SHORT).show()
                }
            },
            onAddWidget = { targetHomePage ->
                preferredWidgetPackage = app.packageName
                widgetPickerHomePage = targetHomePage.coerceIn(0, homePages.count - 1)
                showWidgetSelector = true
                true
            },
        )
    }

    actionFolder?.let { folder ->
        FolderActionDialog(
            folder = folder,
            onDismiss = { actionFolder = null },
            onSetSize = { size -> setFolderSize(folder, size) },
            onRename = { name -> renameFolder(folder, name) },
            onDissolve = { dissolveFolder(folder) },
        )
    }

    actionWidget?.let { item ->
        val actionWidgetSizes = currentHomeSizeMaps(currentActionWidgetPresentation.value)
        val currentSize = when (item) {
            is HomeItem.Widget -> actionWidgetSizes.widgetSizeOverrides[item.id]
                ?.takeUnless { it.isAuto }
                ?: WidgetSizeChoice.ROW_2_COLUMN_2
            is HomeItem.ExternalWidget -> actionWidgetSizes.widgetSizeOverrides[item.id]
                ?: WidgetSizeChoice.AUTO
            is HomeItem.App -> null
            is HomeItem.PinnedShortcut -> null
            is HomeItem.Folder -> null
        }
        if (currentSize != null) {
            WidgetActionDialog(
                label = when (item) {
                    is HomeItem.Widget -> item.widget.label
                    is HomeItem.ExternalWidget -> item.descriptor.label.ifBlank { tr("ウィジェット", "Widget") }
                    is HomeItem.App -> tr("ウィジェット", "Widget")
                    is HomeItem.PinnedShortcut -> tr("ショートカット", "Shortcut")
                    is HomeItem.Folder -> tr("フォルダ", "Folder")
                },
                currentSize = currentSize,
                includeAuto = item is HomeItem.ExternalWidget,
                onEdit = if (item is HomeItem.Widget && item.widget == HomeWidget.PHOTO) {
                    {
                        actionWidget = null
                        requestPhotoSelection(false, actionWidgetHomePage, item.id)
                    }
                } else {
                    null
                },
                availableSizes = when (item) {
                    is HomeItem.Widget -> if (item.widget == HomeWidget.PHOTO) {
                        PhotoWidgetSizeChoices
                    } else {
                        FixedWidgetSizeChoices
                    }
                    else -> FixedWidgetSizeChoices
                },
                onDismiss = { actionWidget = null },
                onOpenSettings = {
                    actionWidget = null
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    showSettingsScreen = true
                },
                onSetSize = { choice ->
                    val ownerPage = currentActionWidgetHomePage.value
                    val itemStillPresent = item.id in homePages[ownerPage] && when (item) {
                        is HomeItem.Widget -> true
                        is HomeItem.ExternalWidget -> externalWidgets.any {
                            it.homeId == item.id && it.provider == item.descriptor.provider
                        } && runCatching {
                            appWidgetManager.getAppWidgetInfo(item.descriptor.appWidgetId)
                                ?.provider
                                ?.flattenToString() == item.descriptor.provider
                        }.getOrDefault(false)
                        is HomeItem.App -> false
                        is HomeItem.PinnedShortcut -> false
                        is HomeItem.Folder -> false
                    }
                    if (!itemStillPresent) {
                        Toast.makeText(
                            context,
                            tr("このウィジェットは利用できないため、サイズを変更できません", "This widget is unavailable, so its size can't be changed"),
                            Toast.LENGTH_SHORT,
                        ).show()
                        false
                    } else {
                        val updatedSizeMaps = updateHomeWidgetSize(
                            narrow = currentNarrowHomeSizes.value,
                            wide = currentWideHomeSizes.value,
                            id = item.id,
                            choice = choice,
                            builtIn = item is HomeItem.Widget,
                            presentation = currentActionWidgetPresentation.value,
                            separateWideOrder = currentSeparateWideHomeOrder.value,
                        )
                        if (updatedSizeMaps.first != currentNarrowHomeSizes.value ||
                            updatedSizeMaps.second != currentWideHomeSizes.value
                        ) {
                            if (persistHomeLayoutTransaction(
                                    context = context,
                                    layout = currentHomeLayout(),
                                    widgetSizes = updatedSizeMaps.first.widgetSizeOverrides,
                                    wideWidgetSizes = updatedSizeMaps.second.widgetSizeOverrides,
                                    folders = homeFolders,
                                )
                            ) {
                                widgetSizeOverrides = updatedSizeMaps.first.widgetSizeOverrides
                                wideWidgetSizeOverrides = updatedSizeMaps.second.widgetSizeOverrides
                                true
                            } else {
                                Toast.makeText(
                                    context,
                                    tr("ウィジェットサイズを保存できませんでした", "Couldn't save the widget size"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                false
                            }
                        } else {
                            true
                        }
                    }
                },
                onRemove = when (item) {
                    is HomeItem.Widget -> { { removeBuiltInWidget(item, actionWidgetHomePage) } }
                    is HomeItem.ExternalWidget -> { { removeExternalWidget(item.descriptor) } }
                    is HomeItem.App -> { { true } }
                    is HomeItem.PinnedShortcut -> { { removePinnedShortcut(item.shortcut) } }
                    is HomeItem.Folder -> { { true } }
                },
            )
        }
    }

    actionPinnedShortcut?.let { shortcut ->
        PinnedShortcutActionDialog(
            shortcut = shortcut,
            onDismiss = { actionPinnedShortcut = null },
            onOpen = {
                if (launchPinnedShortcut(context, shortcut)) {
                    actionPinnedShortcut = null
                } else {
                    Toast.makeText(
                        context,
                        tr("ショートカットを開けませんでした", "Couldn't open the shortcut"),
                        Toast.LENGTH_SHORT,
                    ).show()
                    onAppLaunchFailure()
                }
            },
            onOpenSettings = {
                actionPinnedShortcut = null
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                showSettingsScreen = true
            },
            onRemove = { removePinnedShortcut(shortcut) },
        )
    }

    if (showSettingsScreen) {
        SettingsScreen(
            searchController = drawerSearchController,
            onBack = { showSettingsScreen = false },
        )
    }

    if (showWidgetSelector) {
        val providerGroups = remember(showWidgetSelector, lifecycleRefreshToken) {
            widgetPickerGroups(context, appWidgetManager)
        }
        WidgetSelectorDialog(
            availableBuiltIns = HomeWidget.values().filter {
                it == HomeWidget.PHOTO || it.id !in homePages.allIds
            },
            providerGroups = providerGroups,
            preferredPackage = preferredWidgetPackage,
            selectedHomePage = widgetPickerHomePage,
            homePageCount = homePages.count,
            homePageChangePending = homePageChangePending,
            onAddHomePage = { done -> changeHomePages(onFinished = done) },
            onRemoveHomePage = { removed, done -> changeHomePages(removed, done) },
            onHomePageSelected = { targetHomePage ->
                widgetPickerHomePage = targetHomePage.coerceIn(0, homePages.count - 1)
            },
            onBuiltIn = { widget, targetHomePage ->
                // Pass the selector value at the actual item click. This keeps a page change from
                // being lost when a built-in opens the asynchronous photo picker.
                addBuiltInWidget(widget, targetHomePage)
            },
            onExternal = { provider, targetHomePage ->
                showWidgetSelector = false
                preferredWidgetPackage = null
                onPickExternalWidget(
                    provider,
                    targetHomePage.coerceIn(0, homePages.count - 1),
                )
            },
            onDismiss = {
                showWidgetSelector = false
                preferredWidgetPackage = null
            },
        )
    }

    photoPreviewWidgetId?.let { widgetId ->
        PhotoPreviewDialog(
            uriString = photoUris[widgetId],
            isMuted = photoVideoMutes[widgetId] ?: true,
            onDismiss = { photoPreviewWidgetId = null },
        )
    }
}

@Composable
private fun BoxWithPosture(
    modifier: Modifier,
    content: @Composable (Posture) -> Unit,
) {
    // LocalConfiguration follows window resize/configuration changes and reports the activity
    // window bounds. BoxWithConstraints below is intentionally retained for layout, but must not
    // classify posture from its reduced content height (header, bars, and insets are already gone).
    val configuration = LocalConfiguration.current
    val posture = launcherPostureForWindow(
        widthDp = configuration.screenWidthDp,
        heightDp = configuration.screenHeightDp,
    )
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clipToBoundsWithSideMargin(),
    ) {
        content(posture)
    }
}

private fun launcherHomePresentationFor(posture: Posture): LauncherHomePresentation =
    if (posture == Posture.INNER_LANDSCAPE) {
        LauncherHomePresentation.START_CANVAS
    } else {
        LauncherHomePresentation.PAGER
    }

/** Plain (non-snapshot) flag: reading it must not restart the settled-page effect. */
private class HomePagerTargetGuard {
    var active = false

    /** The page most recently reported by the pager itself, consumed by the target effect. */
    var fromPager: Int? = null
}

