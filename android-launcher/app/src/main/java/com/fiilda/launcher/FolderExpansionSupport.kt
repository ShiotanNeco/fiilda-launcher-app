package com.fiilda.launcher

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/** A transient folder presentation owned by the stable launcher root. */
internal data class FolderExpansionSession(
    val folder: HomeFolder,
    /** Retained for lifecycle identity and compatibility with the board's opening callback. */
    val sourceBounds: IntRect,
    /** Retained for lifecycle identity; the sheet itself is rooted in the full overlay host. */
    val overlayOrigin: IntOffset,
    val posture: Posture,
    val lifecycleRefreshToken: Int,
    val isOpen: Boolean = true,
)

/** Full-width, bottom anchored sheet geometry used by the renewed folder presentation. */
internal fun folderSheetTargetRect(
    viewport: IntSize,
    sheetHeight: Int,
): IntRect {
    val safeWidth = viewport.width.coerceAtLeast(1)
    val safeHeight = sheetHeight.coerceIn(1, viewport.height.coerceAtLeast(1))
    return IntRect(
        left = 0,
        top = viewport.height.coerceAtLeast(safeHeight) - safeHeight,
        right = safeWidth,
        bottom = viewport.height.coerceAtLeast(safeHeight),
    )
}

/** Moves one member in a transient drag order, returning null for an invalid target. */
internal fun moveFolderMember(
    order: List<String>,
    fromIndex: Int,
    toIndex: Int,
): List<String>? {
    if (fromIndex !in order.indices || toIndex !in order.indices || fromIndex == toIndex) return null
    return order.toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}

private const val FolderSheetMaxWidthDp = 640
private const val FolderSheetMinHeightDp = 240
private const val FolderSheetDefaultMaxHeightFraction = 0.65f
private const val FolderSheetImeMaxHeightFraction = 0.86f
private val FolderSheetSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

private enum class FolderSheetMode {
    VIEW,
    ADD,
    EDIT,
}

/**
 * Dismisses the sheet only for a fresh, stationary outside tap.
 *
 * The folder tile can compose this overlay while the opening gesture is still in progress. A
 * handler that waits for any pointer-up would then treat that opening gesture as an outside tap,
 * producing the intermittent open-then-immediately-close animation.
 */
private fun Modifier.folderExpansionScrim(
    touchSlopPx: Float,
    onDismiss: () -> Unit,
): Modifier = pointerInput(touchSlopPx, onDismiss) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        var moved = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if ((change.position - down.position).getDistance() > touchSlopPx) {
                moved = true
            }
            change.consume()
            if (change.changedToUpIgnoreConsumed() || !change.pressed) {
                if (!moved) onDismiss()
                break
            }
        }
    }
}

/**
 * Lets the expanded folder sheet follow a downward pull and dismisses it after a deliberate
 * travel distance. Pointer events already consumed by the member grid are left alone so vertical
 * grid scrolling and long-press reordering keep their existing ownership.
 */
private fun Modifier.folderSheetDismissGesture(
    enabled: Boolean,
    touchSlopPx: Float,
    dismissThresholdPx: Float,
    dragOffset: MutableState<Float>,
    onDismiss: State<() -> Unit>,
): Modifier = pointerInput(enabled, touchSlopPx, dismissThresholdPx) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Main,
        )
        var dragging = false
        var totalDragY = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.changedToUpIgnoreConsumed() || !change.pressed) break

            val fromDown = change.position - down.position
            val delta = change.position - change.previousPosition
            if (!dragging) {
                val passedSlop = fromDown.getDistance() > touchSlopPx
                val isDownward = fromDown.y > 0f &&
                    fromDown.y >= kotlin.math.abs(fromDown.x)
                if (!change.isConsumed && passedSlop && isDownward) {
                    dragging = true
                    totalDragY = fromDown.y
                    change.consume()
                    dragOffset.value = totalDragY
                }
            } else {
                totalDragY = (totalDragY + delta.y).coerceAtLeast(0f)
                change.consume()
                dragOffset.value = totalDragY
            }
        }

        if (dragging) {
            if (totalDragY >= dismissThresholdPx) {
                onDismiss.value()
            } else {
                dragOffset.value = 0f
            }
        }
    }
}

/**
 * Full-screen folder sheet. The outer surface owns the scrim and input shield; its content is
 * capped and centered inside the full-width sheet so narrow and wide Fold windows share one
 * geometry. Member edits are callback based: the launcher root remains the only owner of
 * favorites, folders, and the canonical home layout.
 */
@Composable
internal fun FolderExpansionOverlay(
    session: FolderExpansionSession,
    apps: List<LaunchableApp>,
    allApps: List<LaunchableApp> = apps,
    unavailableAppIds: Set<String> = emptySet(),
    onOpenApp: (LaunchableApp) -> Unit,
    onDismiss: () -> Unit,
    onAddApps: (List<LaunchableApp>) -> Boolean = { false },
    onReorder: (List<String>) -> Boolean = { false },
    onRemoveApp: (LaunchableApp) -> Boolean = { false },
    onRename: (String) -> Boolean = { false },
    onFinishedClosing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = remember(session.folder.id) { Animatable(0f) }
    val target = if (session.isOpen) 1f else 0f
    LaunchedEffect(session.folder.id, target) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            progress.snapTo(target)
        } else {
            progress.animateTo(target, FolderSheetSpring, initialVelocity = progress.velocity)
        }
        if (target == 0f) onFinishedClosing()
    }

    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val scrimAlpha = (progress.value * 0.58f).coerceIn(0f, 0.58f)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .zIndex(100f),
    ) {
        // The regular sheet stays visually anchored near the bottom instead of filling most of
        // the home surface for a small folder. When the IME is visible, allow the taller bound so
        // the editor and action row still have a reachable viewport above the keyboard.
        val imeBottomPadding = WindowInsets.ime
            .asPaddingValues()
            .calculateBottomPadding()
        val sheetMaxHeight = maxHeight * if (imeBottomPadding > 0.dp) {
            FolderSheetImeMaxHeightFraction
        } else {
            FolderSheetDefaultMaxHeightFraction
        }
        val density = LocalDensity.current
        val viewport = IntSize(
            width = with(density) { maxWidth.roundToPx().coerceAtLeast(1) },
            height = with(density) { maxHeight.roundToPx().coerceAtLeast(1) },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
                .clearAndSetSemantics {}
                // A full-screen pointer shield is above every launcher surface, including the
                // bottom navigation bar. It consumes outside taps before they can reach Home.
                .folderExpansionScrim(
                    touchSlopPx = with(density) { 12.dp.toPx() },
                    onDismiss = { if (session.isOpen) currentOnDismiss() },
                ),
        )
        FolderSheetSurface(
            session = session,
            apps = apps,
            allApps = allApps,
            unavailableAppIds = unavailableAppIds,
            progress = progress.value,
            maxHeight = sheetMaxHeight,
            viewport = viewport,
            onOpenApp = onOpenApp,
            onDismiss = onDismiss,
            onAddApps = onAddApps,
            onReorder = onReorder,
            onRemoveApp = onRemoveApp,
            onRename = onRename,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun FolderSheetSurface(
    session: FolderExpansionSession,
    apps: List<LaunchableApp>,
    allApps: List<LaunchableApp>,
    unavailableAppIds: Set<String>,
    progress: Float,
    maxHeight: Dp,
    viewport: IntSize,
    onOpenApp: (LaunchableApp) -> Unit,
    onDismiss: () -> Unit,
    onAddApps: (List<LaunchableApp>) -> Boolean,
    onReorder: (List<String>) -> Boolean,
    onRemoveApp: (LaunchableApp) -> Boolean,
    onRename: (String) -> Boolean,
    modifier: Modifier,
) {
    val palette = LocalLauncherPalette.current
    val p = progress.coerceIn(0f, 1f)
    val density = LocalDensity.current
    val sheetDragOffset = remember(session.folder.id) { mutableStateOf(0f) }
    val dismissThresholdPx = with(density) { 96.dp.toPx() }
    var sheetHeightPx by remember(session.folder.id) { mutableStateOf(0) }
    var mode by remember(session.folder.id) { mutableStateOf(FolderSheetMode.VIEW) }
    var draftName by rememberSaveable(session.folder.id) { mutableStateOf(session.folder.name) }
    var selectedIds by remember(session.folder.id) {
        mutableStateOf<Set<String>>(emptySet())
    }
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    // Nested add/edit modes get the first Back. This lets the keyboard or an inline editor close
    // before the sheet itself, while a second Back dismisses the sheet through the root callback.
    BackHandler(enabled = true) {
        when {
            mode != FolderSheetMode.VIEW -> {
                mode = FolderSheetMode.VIEW
                selectedIds = emptySet()
            }
            session.isOpen -> onDismiss()
        }
    }
    val memberIdsKey = apps.joinToString("\u0000", transform = ::favoriteId)
    var workingOrder by remember(session.folder.id, memberIdsKey) {
        mutableStateOf(apps.map(::favoriteId))
    }
    val currentWorkingOrder by rememberUpdatedState(workingOrder)
    val currentOnReorder by rememberUpdatedState(onReorder)
    LaunchedEffect(memberIdsKey) {
        workingOrder = apps.map(::favoriteId)
    }

    val orderedApps = remember(workingOrder, apps) {
        val byId = apps.associateBy(::favoriteId)
        workingOrder.mapNotNull(byId::get)
    }
    val sheetRect = folderSheetTargetRect(viewport = viewport, sheetHeight = sheetHeightPx)
    // Keep the first unmeasured frame below the viewport. Without this guard a 0px measurement
    // is clamped to a 1px target and briefly draws the sheet at the bottom before layout reports
    // its real height.
    val translationY = if (sheetHeightPx <= 0) {
        viewport.height.toFloat()
    } else {
        (sheetRect.height * (1f - p)).coerceAtLeast(0f)
    }
    val glassGeometryVersion = "folder-${session.folder.id}-$translationY-${sheetDragOffset.value}"

    Box(
        modifier = modifier
            .widthIn(max = FolderSheetMaxWidthDp.dp)
            .fillMaxWidth()
            .heightIn(min = FolderSheetMinHeightDp.dp, max = maxHeight)
            .onSizeChanged { sheetHeightPx = it.height }
            .folderSheetDismissGesture(
                enabled = session.isOpen,
                touchSlopPx = with(density) { 12.dp.toPx() },
                dismissThresholdPx = dismissThresholdPx,
                dragOffset = sheetDragOffset,
                onDismiss = currentOnDismiss,
            )
            .graphicsLayer {
                this.translationY = translationY + sheetDragOffset.value
            }
            .then(
                if (LocalLauncherGlass.current.enabled) {
                    Modifier.launcherGlassFolderSheet(
                        fallbackColor = palette.surface,
                        geometryVersion = glassGeometryVersion,
                    )
                } else {
                    Modifier.launcherShapedSurface(
                        LauncherFolderSheetShape,
                        palette.accent,
                        palette.surface,
                    )
                },
            )
            .semantics {
                paneTitle = session.folder.name
            },
    ) {
        CompositionLocalProvider(
            LocalLauncherGlassGeometryVersion provides glassGeometryVersion,
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = FolderSheetMaxWidthDp.dp)
                .align(Alignment.Center)
                .windowInsetsPadding(
                    // MainActivity uses edge-to-edge with adjustNothing, so the sheet must
                    // consume whichever bottom inset is larger: the navigation bar or the IME.
                    // Applying their union once keeps the edit actions above the keyboard without
                    // stacking both paddings.
                    WindowInsets.navigationBars
                        .union(WindowInsets.ime)
                        .only(WindowInsetsSides.Bottom),
                )
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FolderSheetHeader(
                title = when (mode) {
                    FolderSheetMode.VIEW -> session.folder.name
                    FolderSheetMode.ADD -> tr("アプリを追加", "Add apps")
                    FolderSheetMode.EDIT -> tr("フォルダを編集", "Edit folder")
                },
                palette = palette,
                onDismiss = onDismiss,
            )
            when (mode) {
                FolderSheetMode.VIEW -> {
                    FolderMemberGrid(
                        apps = orderedApps,
                        posture = session.posture,
                        editable = false,
                        onOpenApp = onOpenApp,
                        onRemoveApp = onRemoveApp,
                        onReorder = currentOnReorder,
                        workingOrder = currentWorkingOrder,
                        onWorkingOrderChanged = { workingOrder = it },
                        modifier = Modifier.weight(1f),
                    )
                    FolderSheetActions(
                        palette = palette,
                        onAdd = { selectedIds = emptySet(); mode = FolderSheetMode.ADD },
                        onEdit = { draftName = session.folder.name; mode = FolderSheetMode.EDIT },
                    )
                }
                FolderSheetMode.ADD -> {
                    val currentMembers = apps.mapTo(linkedSetOf(), ::favoriteId)
                    val candidates = allApps.filter {
                        favoriteId(it) !in currentMembers && favoriteId(it) !in unavailableAppIds
                    }
                    FolderAddGrid(
                        apps = candidates,
                        posture = session.posture,
                        selectedIds = selectedIds,
                        onToggle = { id ->
                            selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                        },
                        modifier = Modifier.weight(1f),
                    )
                    FolderSheetActionRow(
                        palette = palette,
                        primaryLabel = tr("追加", "Add"),
                        primaryIcon = Icons.Filled.Add,
                        onPrimary = {
                            val chosen = candidates.filter { favoriteId(it) in selectedIds }
                            if (chosen.isNotEmpty() && onAddApps(chosen)) {
                                selectedIds = emptySet()
                                mode = FolderSheetMode.VIEW
                            }
                        },
                        secondaryLabel = tr("キャンセル", "Cancel"),
                        onSecondary = { selectedIds = emptySet(); mode = FolderSheetMode.VIEW },
                    )
                }
                FolderSheetMode.EDIT -> {
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it.take(48) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(tr("フォルダ名", "Folder name")) },
                    )
                    FolderMemberGrid(
                        apps = orderedApps,
                        posture = session.posture,
                        editable = true,
                        onOpenApp = onOpenApp,
                        onRemoveApp = onRemoveApp,
                        onReorder = currentOnReorder,
                        workingOrder = currentWorkingOrder,
                        onWorkingOrderChanged = { workingOrder = it },
                        modifier = Modifier.weight(1f),
                    )
                    FolderSheetActionRow(
                        palette = palette,
                        primaryLabel = tr("名前を保存", "Save name"),
                        primaryIcon = Icons.Filled.Check,
                        onPrimary = {
                            if (onRename(draftName)) mode = FolderSheetMode.VIEW
                        },
                        secondaryLabel = tr("完了", "Done"),
                        onSecondary = { mode = FolderSheetMode.VIEW },
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun FolderSheetHeader(
    title: String,
    palette: LauncherPalette,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { paneTitle = title },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            color = palette.ink,
            fontSize = 18.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(42.dp)
                .clickable(role = Role.Button, onClick = onDismiss)
                .semantics {
                    role = Role.Button
                    contentDescription = tr("閉じる", "Close")
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = palette.accent)
        }
    }
}

@Composable
private fun FolderMemberGrid(
    apps: List<LaunchableApp>,
    posture: Posture,
    editable: Boolean,
    onOpenApp: (LaunchableApp) -> Unit,
    onRemoveApp: (LaunchableApp) -> Boolean,
    onReorder: (List<String>) -> Boolean,
    workingOrder: List<String>,
    onWorkingOrderChanged: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalLauncherPalette.current
    val gridState = rememberLazyGridState()
    val currentOrder by rememberUpdatedState(workingOrder)
    val currentOnReorder by rememberUpdatedState(onReorder)
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var orderChangedDuringDrag by remember { mutableStateOf(false) }
    var dragStartOrder by remember { mutableStateOf(emptyList<String>()) }
    // Pointer events can arrive before Compose has applied the preview state update. Keep the
    // gesture's order synchronously so rapid crossings and onDragEnd always submit the latest
    // preview instead of the last recomposed value.
    var dragOrder by remember { mutableStateOf(emptyList<String>()) }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(3),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 0.dp, max = 430.dp)
            .clipToBounds(),
        horizontalArrangement = Arrangement.spacedBy(HomeGridGapDp.dp),
        verticalArrangement = Arrangement.spacedBy(HomeGridGapDp.dp),
    ) {
        items(
            items = apps,
            key = ::favoriteId,
        ) { app ->
            val id = favoriteId(app)
            val isDragging = draggingId == id
            val accessibilityReorderActions = if (editable) {
                listOf(
                    CustomAccessibilityAction(tr("前へ移動", "Move earlier")) {
                        val base = currentOrder
                        val fromIndex = base.indexOf(id)
                        val updated = moveFolderMember(base, fromIndex, fromIndex - 1)
                        if (updated == null || !currentOnReorder(updated)) {
                            false
                        } else {
                            onWorkingOrderChanged(updated)
                            true
                        }
                    },
                    CustomAccessibilityAction(tr("次へ移動", "Move later")) {
                        val base = currentOrder
                        val fromIndex = base.indexOf(id)
                        val updated = moveFolderMember(base, fromIndex, fromIndex + 1)
                        if (updated == null || !currentOnReorder(updated)) {
                            false
                        } else {
                            onWorkingOrderChanged(updated)
                            true
                        }
                    },
                )
            } else {
                emptyList()
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = if (isDragging) 0.76f else 1f
                        if (isDragging) {
                            translationX = dragOffset.x
                            translationY = dragOffset.y
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(86.dp)
                        .then(
                            if (editable) {
                                Modifier.pointerInput(id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingId = id
                                            dragOffset = Offset.Zero
                                            orderChangedDuringDrag = false
                                            dragStartOrder = currentOrder.toList()
                                            dragOrder = currentOrder.toList()
                                        },
                                        onDragCancel = {
                                            // Mode changes dispose every cell's pointer input. Only
                                            // the cell that owns the active gesture may rollback;
                                            // stale callbacks from sibling cells must not replace a
                                            // valid order with their already-cleared empty snapshot.
                                            if (draggingId == id) {
                                                val rollbackOrder = dragStartOrder
                                                draggingId = null
                                                dragOffset = Offset.Zero
                                                orderChangedDuringDrag = false
                                                dragOrder = emptyList()
                                                dragStartOrder = emptyList()
                                                onWorkingOrderChanged(rollbackOrder)
                                            }
                                        },
                                        onDragEnd = {
                                            // A disposed pointer input can still deliver a stale
                                            // end callback after its owner has cancelled. Commit
                                            // only while this cell still owns the gesture, and clear
                                            // ownership before persistence so a recomposition from
                                            // the callback cannot trigger a second rollback.
                                            if (draggingId == id) {
                                                val finalOrder = dragOrder
                                                val committedOrder = dragStartOrder
                                                val shouldCommit = orderChangedDuringDrag
                                                draggingId = null
                                                dragOffset = Offset.Zero
                                                orderChangedDuringDrag = false
                                                dragOrder = emptyList()
                                                dragStartOrder = emptyList()
                                                if (shouldCommit && !currentOnReorder(finalOrder)) {
                                                    onWorkingOrderChanged(committedOrder)
                                                }
                                            }
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount
                                            val draggedInfo = gridState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.key == id }
                                                ?: return@detectDragGesturesAfterLongPress
                                            val center = Offset(
                                                draggedInfo.offset.x + draggedInfo.size.width / 2f + dragOffset.x,
                                                draggedInfo.offset.y + draggedInfo.size.height / 2f + dragOffset.y,
                                            )
                                            val target = gridState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { info ->
                                                    info.key != id &&
                                                        center.x in info.offset.x.toFloat()..(info.offset.x + info.size.width).toFloat() &&
                                                        center.y in info.offset.y.toFloat()..(info.offset.y + info.size.height).toFloat()
                                                }
                                            val targetIndex = target?.index
                                                ?: return@detectDragGesturesAfterLongPress
                                            val sourceIndex = dragOrder.indexOf(id)
                                            val nextOrder = moveFolderMember(
                                                order = dragOrder,
                                                fromIndex = sourceIndex,
                                                toIndex = targetIndex,
                                            ) ?: return@detectDragGesturesAfterLongPress
                                            dragOrder = nextOrder
                                            onWorkingOrderChanged(nextOrder)
                                            dragOffset = Offset.Zero
                                            orderChangedDuringDrag = true
                                        },
                                    )
                                }
                            } else {
                                Modifier.clickable(role = Role.Button) { onOpenApp(app) }
                            },
                        )
                        .semantics {
                            contentDescription = if (editable) {
                                tr("${app.label}、長押しで並べ替え", "${app.label}, long press to reorder")
                            } else {
                                tr("アプリ、${app.label}", "App, ${app.label}")
                            }
                            role = Role.Button
                            if (accessibilityReorderActions.isNotEmpty()) {
                                customActions = accessibilityReorderActions
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    FolderAppIcon(app, shortcutCellIconSizeDp().dp)
                    if (editable) {
                        Icon(
                            imageVector = Icons.Filled.DragHandle,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(18.dp),
                        )
                    }
                }
                Text(
                    text = app.label,
                    color = palette.ink,
                    fontSize = homeAppTileLabelFontSizeSp(posture).sp,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                if (editable) {
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable(role = Role.Button) {
                                if (onRemoveApp(app)) {
                                    onWorkingOrderChanged(currentOrder.filterNot { it == id })
                                }
                            }
                            .semantics {
                                role = Role.Button
                                contentDescription = tr("${app.label}をホームへ戻す", "Move ${app.label} back to Home")
                            },
                    ) {
                        Text(tr("ホームへ", "To Home"), color = palette.accent, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderAddGrid(
    apps: List<LaunchableApp>,
    posture: Posture,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalLauncherPalette.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 0.dp, max = 430.dp),
        horizontalArrangement = Arrangement.spacedBy(HomeGridGapDp.dp),
        verticalArrangement = Arrangement.spacedBy(HomeGridGapDp.dp),
    ) {
        items(apps, key = ::favoriteId) { app ->
            val id = favoriteId(app)
            val selected = id in selectedIds
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(102.dp)
                    .clickable(role = Role.Checkbox) { onToggle(id) }
                    .semantics {
                        role = Role.Checkbox
                        contentDescription = if (selected) {
                            tr("${app.label}、選択中", "${app.label}, selected")
                        } else {
                            app.label
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        FolderAppIcon(app, shortcutCellIconSizeDp().dp)
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Text(
                        text = app.label,
                        color = palette.ink,
                        fontSize = homeAppTileLabelFontSizeSp(posture).sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderSheetActions(
    palette: LauncherPalette,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FolderSheetActionButton(
            label = tr("アプリを追加", "Add apps"),
            icon = Icons.Filled.Add,
            palette = palette,
            onClick = onAdd,
            modifier = Modifier.weight(1f),
        )
        FolderSheetActionButton(
            label = tr("編集", "Edit"),
            icon = Icons.Filled.Edit,
            palette = palette,
            onClick = onEdit,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FolderSheetActionRow(
    palette: LauncherPalette,
    primaryLabel: String,
    primaryIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FolderSheetActionButton(
            label = primaryLabel,
            icon = primaryIcon,
            palette = palette,
            onClick = onPrimary,
            modifier = Modifier.weight(1f),
        )
        FolderSheetActionButton(
            label = secondaryLabel,
            icon = Icons.Filled.Close,
            palette = palette,
            onClick = onSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FolderSheetActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    palette: LauncherPalette,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier
            .height(46.dp)
            .border(1.dp, palette.accent, RectangleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
        Text(label, color = palette.ink, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
internal fun FolderTile(
    folder: HomeFolder,
    apps: List<LaunchableApp>,
    directLaunchEnabled: Boolean,
    onOpenApp: (LaunchableApp) -> Unit,
    onOpenFolder: () -> Unit,
) {
    val palette = LocalLauncherPalette.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (LocalLauncherGlass.current.enabled) {
                    Modifier.launcherGlassTile(fallbackColor = palette.surface)
                } else {
                    // The folder outline is intentionally drawn in every non-Glass theme.
                    Modifier
                        .clip(LauncherTileShape)
                        .background(palette.surface, LauncherTileShape)
                        .border(1.dp, palette.accent, LauncherTileShape)
                },
            ),
    ) {
        val tileIconSize = (maxWidth / 3f * 0.66f).coerceIn(12.dp, 34.dp)
        FolderTileGrid(
            folder = folder,
            apps = apps,
            directLaunchEnabled = directLaunchEnabled,
            onOpenApp = onOpenApp,
            onOpenFolder = onOpenFolder,
            iconSize = tileIconSize,
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp)
                .launcherGlassContributor(),
        )
    }
}

@Composable
private fun FolderTileGrid(
    folder: HomeFolder,
    apps: List<LaunchableApp>,
    directLaunchEnabled: Boolean,
    onOpenApp: (LaunchableApp) -> Unit,
    onOpenFolder: () -> Unit,
    iconSize: Dp,
    modifier: Modifier,
) {
    val palette = LocalLauncherPalette.current
    val glassEnabled = LocalLauncherGlass.current.enabled
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(3) { row ->
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                repeat(3) { column ->
                    val index = row * 3 + column
                    val app = apps.getOrNull(index)
                    val expandCell = folder.size == HomeFolderSize.LARGE && index == 8
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .then(
                                if (glassEnabled) {
                                    // The folder tile already owns one optical surface. Keep its
                                    // preview cells transparent and use only a fine neutral
                                    // divider so the wallpaper and icons remain visible through it.
                                    Modifier.launcherBorder(0.5.dp, FiiLDALineStrong)
                                } else {
                                    Modifier.background(if (app == null) palette.deep else palette.accentSurface)
                                },
                            )
                            .then(
                                if (expandCell) {
                                    Modifier
                                        .clickable(role = Role.Button) { onOpenFolder() }
                                        .semantics {
                                            role = Role.Button
                                            contentDescription = tr("${folder.name}を開く", "Open ${folder.name}")
                                        }
                                } else if (directLaunchEnabled && app != null) {
                                    Modifier
                                        .clickable(role = Role.Button) { onOpenApp(app) }
                                        .semantics {
                                            role = Role.Button
                                            contentDescription = tr("アプリ、${app.label}", "App, ${app.label}")
                                        }
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            expandCell -> Icon(
                                imageVector = Icons.Filled.OpenInFull,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(iconSize),
                            )
                            app != null -> FolderAppIcon(app, iconSize)
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}
