package com.fiilda.launcher

import dev.glasslab.glass.GlassGeometrySignal
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** App tile rendering, shortcut projections, and the searchable app drawer. */
private fun gridRowSpans(
    apps: List<LaunchableApp>,
    columns: Int,
    appTileSizes: Map<String, AppTileSize>,
): List<Int> {
    if (apps.isEmpty()) return listOf(1)
    val rows = mutableListOf<Int>()
    var occupied = 0
    var rowSpan = 1
    apps.forEach { app ->
        val size = appGridItemSize(app, columns, appTileSizes)
        if (occupied > 0 && occupied + size.columnSpan > columns) {
            rows += rowSpan
            occupied = 0
            rowSpan = 1
        }
        occupied += size.columnSpan
        rowSpan = maxOf(rowSpan, size.rowSpan)
        if (occupied >= columns) {
            rows += rowSpan
            occupied = 0
            rowSpan = 1
        }
    }
    if (occupied > 0) rows += rowSpan
    return rows
}

private fun appGridItemSize(
    app: LaunchableApp,
    columns: Int,
    appTileSizes: Map<String, AppTileSize>,
): GridItemSize {
    val size = appTileSizes[favoriteId(app)] ?: AppTileSize.SMALL
    return GridItemSize(
        columnSpan = minOf(size.columnSpan, columns),
        rowSpan = size.rowSpan,
    )
}

@Composable
private fun AppGrid(
    apps: List<LaunchableApp>,
    columns: Int,
    posture: Posture,
    selectedPackage: String?,
    appTileSizes: Map<String, AppTileSize> = emptyMap(),
    onOpenApp: (LaunchableApp) -> Unit,
    onLongPress: ((LaunchableApp) -> Unit)? = null,
) {
    val rowSpans = gridRowSpans(apps, columns, appTileSizes)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gap = 3.dp
        val cellWidth = (maxWidth - gap * (columns - 1)) / columns
        val gridHeight = rowSpans.foldIndexed(0.dp) { index, total, rowSpan ->
            total + cellWidth * rowSpan +
                (if (rowSpan > 1) gap else 0.dp) +
                (if (index > 0) gap else 0.dp)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxWidth().height(gridHeight),
            userScrollEnabled = false,
            contentPadding = PaddingValues(0.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            items(
                items = apps,
                key = { "home-${it.packageName}-${it.className}" },
                span = { app ->
                    GridItemSpan(appGridItemSize(app, columns, appTileSizes).columnSpan)
                },
            ) { app ->
                AppTile(
                    app = app,
                    posture = posture,
                    selected = app.packageName == selectedPackage,
                    size = appTileSizes[favoriteId(app)] ?: AppTileSize.SMALL,
                    cellSize = cellWidth,
                    onClick = { onOpenApp(app) },
                    onLongClick = onLongPress?.let { callback -> { callback(app) } },
                )
            }
        }
    }
}

@Composable
internal fun AppTile(
    app: LaunchableApp,
    posture: Posture,
    selected: Boolean,
    showSelectedBorder: Boolean = true,
    patternAccent: Boolean = false,
    size: AppTileSize = AppTileSize.SMALL,
    exactGridFootprint: Boolean = false,
    contentMode: AppTileContentMode = AppTileContentMode.SHORTCUTS,
    cellSize: Dp? = null,
    interactive: Boolean = true,
    accessibilityLabel: String? = null,
    drawerGestureSignal: AppDrawerGestureSignal? = null,
    shortcuts: List<ResolvedLauncherShortcut> = emptyList(),
    notifications: List<ActiveNotificationSnapshot> = emptyList(),
    onClick: () -> Unit,
    onShortcutClick: (ResolvedLauncherShortcut) -> Unit = {},
    onNotificationClick: (ActiveNotificationSnapshot) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val isWindows8 = LocalLauncherTheme.current == LauncherTheme.WINDOWS_8
    val glassEnabled = LocalLauncherGlass.current.enabled
    val showNotificationBadges = LocalShowNotificationBadges.current
    val iconTileColor = Color(app.tileColorArgb.toLong() and 0xFFFF_FFFFL)
    val selectedTileColorArgb = selectedIconTileColor(app.tileColorArgb)
    val tileSurface = if (isWindows8) {
        Color(selectedTileColorArgb.toLong() and 0xFFFF_FFFFL)
            .takeIf { selected }
            ?: iconTileColor
    } else {
        if (selected) FiiLDASelectedSurface else FiiLDASurface
    }
    val tileContentColor = if (isWindows8) {
        val contentColorArgb = if (selected) {
            accessibleTileForegroundArgb(selectedTileColorArgb)
        } else {
            app.tileContentColorArgb
        }
        Color(contentColorArgb.toLong() and 0xFFFF_FFFFL)
    } else {
        if (selected) FiiLDAInk else FiiLDAMuted
    }
    val hasShortcutPresentation = contentMode == AppTileContentMode.SHORTCUTS &&
        size.supportsShortcuts() &&
        shortcuts.isNotEmpty()
    val hasNotificationPresentation = contentMode == AppTileContentMode.NOTIFICATIONS &&
        size != AppTileSize.SMALL &&
        notifications.isNotEmpty()
    val shortcutAppCellSurface = if (isWindows8) {
        tileSurface
    } else {
        FiiLDAAccentSurface
    }
    val shortcutCellSurface = if (isWindows8) iconTileColor else FiiLDASurface
    val shortcutEmptyCellSurface = if (isWindows8) {
        subduedAppTileColor(iconTileColor)
    } else {
        FiiLDADeep
    }
    val shortcutDividerColor = when {
        isWindows8 -> Color.Transparent
        else -> FiiLDALine
    }
    val shortcutAppContentColor = if (isWindows8) {
        tileContentColor
    } else {
        FiiLDAInk
    }
    // On Windows 8 each shortcut cell stays on the app's own flat color, so use the foreground
    // computed for that color rather than the selected-state foreground of the outer tile.
    val shortcutContentColor = if (isWindows8) {
        Color(app.tileContentColorArgb.toLong() and 0xFFFF_FFFFL)
    } else {
        tileContentColor
    }
    val tileView = LocalView.current
    val interaction = when {
        !interactive -> Modifier
        onLongClick == null -> Modifier.clickable(onClick = onClick)
        else -> Modifier.combinedClickable(
            onClick = {
                if (drawerGestureSignal?.isContaminated() != true) {
                    onClick()
                }
            },
            hapticFeedbackEnabled = drawerGestureSignal == null,
            onLongClick = {
                if (drawerGestureSignal?.isContaminated() != true) {
                    if (drawerGestureSignal != null) {
                        tileView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    onLongClick()
                }
            },
        )
    }
    val tileShape = when {
        exactGridFootprint -> {
            // HomeBoard's custom layout already supplies the exact placement footprint, including
            // inter-cell gaps. Do not reconstruct TALL/LARGE height from an aspect ratio here.
            Modifier.fillMaxSize()
        }
        size == AppTileSize.WIDE && cellSize != null -> Modifier.height(cellSize)
        else -> Modifier.aspectRatio(size.columnSpan.toFloat() / size.rowSpan.toFloat())
    }
    val wideIconSize = fittedAppIconSizeDp(
        preferredDp = 63f,
        availableWidthDp = cellSize?.value ?: 63f,
        availableHeightDp = cellSize?.value ?: 63f,
        labelHeightDp = 0f,
    ).dp
    val wideIconSidePadding = cellSize
        ?.let { ((it - wideIconSize) / 2).coerceAtLeast(6.dp) }
        ?: 12.dp
    val tileBorder = when {
        isWindows8 -> Color.Transparent
        size != AppTileSize.SMALL -> FiiLDACyan
        showSelectedBorder && selected -> FiiLDACyan
        patternAccent -> FiiLDACyan.copy(alpha = 0.78f)
        else -> FiiLDALine
    }
    val accessibilityModifier = accessibilityLabel?.let { label ->
        Modifier.clearAndSetSemantics {
            contentDescription = label
            role = Role.Button
            onClick(label = tr("アプリを開く", "Open app")) {
                onClick()
                true
            }
            onLongClick?.let { callback ->
                onLongClick(label = tr("アプリ操作", "App actions")) {
                    callback()
                    true
                }
            }
        }
    } ?: Modifier

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(tileShape)
            .clipToBounds()
            .then(
                if (glassEnabled) {
                    Modifier.launcherGlassTile(fallbackColor = tileSurface)
                } else {
                    Modifier.launcherShapedSurface(LauncherTileShape, tileBorder, tileSurface)
                },
            )
            .then(interaction)
            .then(accessibilityModifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                if (hasShortcutPresentation) {
                        // Child surfaces own the complete visible footprint so their 3dp internal
                        // divider matches the outer HomeGrid gap rather than inheriting tile
                        // padding.
                        Modifier.padding(0.dp)
                    } else if (size == AppTileSize.WIDE && !hasNotificationPresentation) {
                        Modifier.padding(
                            start = wideIconSidePadding,
                            top = 6.dp,
                            end = 6.dp,
                            bottom = 6.dp,
                        )
                    } else {
                        Modifier.padding(2.dp)
                    },
                )
                // Register only the sharp icon/label layer. The outer tile glass remains excluded
                // from the navigation scene replay.
                .launcherGlassContributor(),
            contentAlignment = Alignment.Center,
        ) {
        if (size == AppTileSize.WIDE && !hasShortcutPresentation && !hasNotificationPresentation) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LauncherIcon(app = app, size = wideIconSize)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (shouldRenderAppLabel(LocalShowAppLabels.current)) {
                        Text(
                            text = app.label,
                            color = tileContentColor,
                            fontSize = homeAppTileLabelFontSizeSp(posture).sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        } else if (hasNotificationPresentation) {
            NotificationLiveTile(
                app = app,
                size = size,
                notifications = notifications,
                appContentColor = tileContentColor,
                notificationSurface = shortcutCellSurface,
                notificationContentColor = shortcutContentColor,
                onAppClick = onClick,
                onNotificationClick = onNotificationClick,
            )
        } else if (size == AppTileSize.WIDE && hasShortcutPresentation) {
            AppShortcutSplit(
                app = app,
                shortcut = shortcuts.first(),
                axis = AppShortcutSplitAxis.HORIZONTAL,
                appCellSurface = shortcutAppCellSurface,
                shortcutCellSurface = shortcutCellSurface,
                dividerColor = shortcutDividerColor,
                appContentColor = shortcutAppContentColor,
                shortcutContentColor = shortcutContentColor,
                onClick = onClick,
                onShortcutClick = onShortcutClick,
            )
        } else if (size == AppTileSize.TALL && hasShortcutPresentation) {
            AppShortcutSplit(
                app = app,
                shortcut = shortcuts.first(),
                axis = AppShortcutSplitAxis.VERTICAL,
                appCellSurface = shortcutAppCellSurface,
                shortcutCellSurface = shortcutCellSurface,
                dividerColor = shortcutDividerColor,
                appContentColor = shortcutAppContentColor,
                shortcutContentColor = shortcutContentColor,
                onClick = onClick,
                onShortcutClick = onShortcutClick,
            )
        } else if (size == AppTileSize.LARGE && hasShortcutPresentation) {
            LargeAppShortcutGrid(
                app = app,
                shortcuts = shortcuts,
                appCellSurface = shortcutAppCellSurface,
                shortcutCellSurface = shortcutCellSurface,
                emptyCellSurface = shortcutEmptyCellSurface,
                dividerColor = shortcutDividerColor,
                appContentColor = shortcutAppContentColor,
                shortcutContentColor = shortcutContentColor,
                onClick = onClick,
                onShortcutClick = onShortcutClick,
            )
        } else if (hasShortcutPresentation) {
            AppShortcutList(
                app = app,
                size = size,
                shortcuts = shortcuts,
                appCellSurface = shortcutAppCellSurface,
                shortcutCellSurface = shortcutCellSurface,
                dividerColor = shortcutDividerColor,
                appContentColor = shortcutAppContentColor,
                shortcutContentColor = shortcutContentColor,
                onClick = onClick,
                onShortcutClick = onShortcutClick,
            )
        } else {
            val showLabel = shouldRenderAppLabel(LocalShowAppLabels.current)
            val labelFontSize = homeAppTileLabelFontSizeSp(posture).sp
            val labelHeight = with(LocalDensity.current) { labelFontSize.toDp() } * AppLabelLineHeightRatio + 2.dp
            BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val iconSize = fittedAppIconSizeDp(
                    preferredDp = if (size == AppTileSize.LARGE) 87f else 63f,
                    availableWidthDp = maxWidth.value,
                    availableHeightDp = maxHeight.value,
                    labelHeightDp = if (showLabel) labelHeight.value else 0f,
                ).dp
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    LauncherIcon(app = app, size = iconSize)
                    if (showLabel) {
                        FitText(
                            text = app.label,
                            color = tileContentColor,
                            maxFontSize = labelFontSize,
                            minFontSize = AppLabelMinFontSizeSp.sp,
                            modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
                        )
                    }
                }
            }
        }
        }
        if (shouldRenderNotificationBadge(
                showNotificationBadges = showNotificationBadges,
                notificationCount = notifications.size,
                isLiveTile = hasNotificationPresentation,
            )
        ) {
            NotificationBadge(
                count = notifications.size,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .launcherGlassContributor(cornerRadius = 0.dp),
                tileSurface = tileSurface,
                tileContentColor = tileContentColor,
            )
        }
    }
}

/** Keeps Windows 8 placeholders flat and app-derived while making them visibly subdued. */
private fun subduedAppTileColor(color: Color): Color = Color(
    red = color.red * 0.58f,
    green = color.green * 0.58f,
    blue = color.blue * 0.58f,
    alpha = 1f,
)

private enum class AppShortcutSplitAxis { HORIZONTAL, VERTICAL }

@Composable
private fun NotificationBadge(
    count: Int,
    modifier: Modifier = Modifier,
    tileSurface: Color,
    tileContentColor: Color,
) {
    val label = if (count > 99) "99+" else count.toString()
    val fontScale = LocalDensity.current.fontScale
    val glassEnabled = LocalLauncherGlass.current.enabled
    val colors = notificationBadgeColors(
        tileSurface = tileSurface,
        tileContentColor = tileContentColor,
    )
    Box(
        modifier = modifier
            .padding(5.dp)
            .size(NotificationBadgeSide)
            .then(
                if (glassEnabled) {
                    Modifier.launcherBorder(1.dp, FiiLDALineStrong)
                } else {
                    Modifier
                        .launcherBorder(1.dp, colors.border)
                        .background(colors.background)
                },
            )
            .semantics {
                contentDescription = tr("${count}件の通知", "${count} notifications")
                stateDescription = tr("現在の通知", "Current notifications")
            }
            .padding(horizontal = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (glassEnabled) FiiLDAInk else colors.content,
            fontSize = notificationBadgeTextSizeSp(label, fontScale).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}

private val NotificationBadgeSide = 24.dp

@Composable
private fun NotificationLiveTile(
    app: LaunchableApp,
    size: AppTileSize,
    notifications: List<ActiveNotificationSnapshot>,
    appContentColor: Color,
    notificationSurface: Color,
    notificationContentColor: Color,
    onAppClick: () -> Unit,
    onNotificationClick: (ActiveNotificationSnapshot) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val adaptedCapacity = notificationTileCapacityForFontScale(size, fontScale)
    val visible = notifications.take(adaptedCapacity)
    val rowMinHeight = when {
        fontScale >= 1.7f -> 38.dp
        fontScale >= 1.3f -> 34.dp
        else -> 30.dp
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = rowMinHeight)
                .clickable(role = Role.Button, onClick = onAppClick)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = tr("アプリ、${app.label}", "App, ${app.label}")
                    stateDescription = tr("${notifications.size}件の通知", "${notifications.size} notifications")
                    onClick(label = tr("アプリを開く", "Open app")) {
                        onAppClick()
                        true
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LauncherIcon(
                app = app,
                size = if (size == AppTileSize.TALL_3X2) 34.dp else 28.dp,
            )
            Text(
                text = app.label,
                color = appContentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 5.dp),
            )
            Text(
                text = tr("${notifications.size}件", "${notifications.size}"),
                color = FiiLDACyan,
                fontSize = 9.sp,
                maxLines = 1,
            )
        }
        visible.forEach { notification ->
            NotificationLiveRow(
                notification = notification,
                surface = notificationSurface,
                contentColor = notificationContentColor,
                fontScale = fontScale,
                onClick = { onNotificationClick(notification) },
            )
        }
        notificationOverflowLabel(notifications.size, adaptedCapacity)?.let { overflow ->
            Text(
                text = overflow,
                color = FiiLDAMuted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun NotificationLiveRow(
    notification: ActiveNotificationSnapshot,
    surface: Color,
    contentColor: Color,
    fontScale: Float,
    onClick: () -> Unit,
) {
    val title = notification.title.ifBlank {
        notification.body.ifBlank { tr("通知", "Notification") }
    }
    val glassEnabled = LocalLauncherGlass.current.enabled
    // At large accessibility scales, retaining a second text line would push a fixed-height row
    // beyond its tile. The title remains one ellipsized line and the body returns at normal scale.
    val body = notification.body.takeIf {
        fontScale < 1.3f && it.isNotBlank() && it != title
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(
                min = when {
                    fontScale >= 1.7f -> 38.dp
                    fontScale >= 1.3f -> 34.dp
                    else -> 30.dp
                },
            )
            .then(
                if (glassEnabled) {
                    Modifier.launcherBorder(1.dp, FiiLDALine)
                } else {
                    Modifier
                        .launcherBorder(1.dp, FiiLDALine)
                        .background(surface)
                },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = buildString {
                    append(tr("通知、", "Notification, "))
                    append(title)
                    body?.let { append(tr("、$it", ", $it")) }
                }
                onClick(label = tr("通知を開く", "Open notification")) {
                    onClick()
                    true
                }
            }
            .padding(horizontal = 5.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            color = contentColor,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        body?.let {
            Text(
                text = it,
                color = contentColor.copy(alpha = 0.75f),
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/** Compact shortcut list used by the two taller footprints added for notification-oriented use. */
@Composable
private fun AppShortcutList(
    app: LaunchableApp,
    size: AppTileSize,
    shortcuts: List<ResolvedLauncherShortcut>,
    appCellSurface: Color,
    shortcutCellSurface: Color,
    dividerColor: Color,
    appContentColor: Color,
    shortcutContentColor: Color,
    onClick: () -> Unit,
    onShortcutClick: (ResolvedLauncherShortcut) -> Unit,
) {
    val visible = shortcuts.take(notificationTileCapacity(size).coerceAtLeast(1))
    val iconSize = 42.dp
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(HomeGridGapDp.dp),
    ) {
        AppShortcutCell(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            surface = appCellSurface,
            dividerColor = dividerColor,
            contentColor = appContentColor,
            iconSize = iconSize,
            label = app.label,
            description = tr("アプリ、${app.label}", "App, ${app.label}"),
            actionLabel = tr("アプリを開く", "Open app"),
            isAppLabel = true,
            onClick = onClick,
            icon = { LauncherIcon(app = app, size = iconSize) },
        )
        visible.forEach { shortcut ->
            AppShortcutCell(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                surface = shortcutCellSurface,
                dividerColor = dividerColor,
                contentColor = shortcutContentColor,
                iconSize = iconSize,
                label = shortcut.label,
                description = tr("ショートカット、${shortcut.label}", "Shortcut, ${shortcut.label}"),
                actionLabel = tr("ショートカットを開く", "Open shortcut"),
                onClick = { onShortcutClick(shortcut) },
                icon = {
                    ShortcutIcon(
                        shortcut = shortcut,
                        fallback = app,
                        size = iconSize,
                    )
                },
            )
        }
    }
}

/** Renders the two-cell WIDE/TALL presentation without applying the legacy WIDE padding. */
@Composable
private fun AppShortcutSplit(
    app: LaunchableApp,
    shortcut: ResolvedLauncherShortcut,
    axis: AppShortcutSplitAxis,
    appCellSurface: Color,
    shortcutCellSurface: Color,
    dividerColor: Color,
    appContentColor: Color,
    shortcutContentColor: Color,
    onClick: () -> Unit,
    onShortcutClick: (ResolvedLauncherShortcut) -> Unit,
) {
    val iconSize = shortcutCellIconSizeDp().dp
    val appCell: @Composable (Modifier) -> Unit = { modifier ->
        AppShortcutCell(
            modifier = modifier,
            surface = appCellSurface,
            dividerColor = dividerColor,
            contentColor = appContentColor,
            iconSize = iconSize,
            label = app.label,
            description = tr("アプリ、${app.label}", "App, ${app.label}"),
            actionLabel = tr("アプリを開く", "Open app"),
            isAppLabel = true,
            onClick = onClick,
            icon = { LauncherIcon(app = app, size = iconSize) },
        )
    }
    val shortcutCell: @Composable (Modifier) -> Unit = { modifier ->
        AppShortcutCell(
            modifier = modifier,
            surface = shortcutCellSurface,
            dividerColor = dividerColor,
            contentColor = shortcutContentColor,
            iconSize = iconSize,
            label = shortcut.label,
            description = tr("ショートカット、${shortcut.label}", "Shortcut, ${shortcut.label}"),
            actionLabel = tr("ショートカットを開く", "Open shortcut"),
            onClick = { onShortcutClick(shortcut) },
            icon = {
                ShortcutIcon(
                    shortcut = shortcut,
                    fallback = app,
                    size = iconSize,
                )
            },
        )
    }
    if (axis == AppShortcutSplitAxis.HORIZONTAL) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(HomeGridGapDp.dp),
        ) {
            appCell(Modifier.weight(1f).fillMaxHeight())
            shortcutCell(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(HomeGridGapDp.dp),
        ) {
            appCell(Modifier.weight(1f).fillMaxWidth())
            shortcutCell(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/**
 * The populated large-tile presentation keeps the app launch in the top-left cell and exposes
 * up to three public shortcuts in the remaining cells. Empty cells intentionally have no
 * semantics or click handler: a partial shortcut list must never suggest an action that does not
 * exist.
 */
@Composable
private fun LargeAppShortcutGrid(
    app: LaunchableApp,
    shortcuts: List<ResolvedLauncherShortcut>,
    appCellSurface: Color,
    shortcutCellSurface: Color,
    emptyCellSurface: Color,
    dividerColor: Color,
    appContentColor: Color,
    shortcutContentColor: Color,
    onClick: () -> Unit,
    onShortcutClick: (ResolvedLauncherShortcut) -> Unit,
) {
    val visibleShortcuts = shortcuts.take(MaxLargeTileShortcuts)
    val iconSize = shortcutCellIconSizeDp().dp
    val appDescription = tr("アプリ、${app.label}", "App, ${app.label}")
    Column(
        modifier = Modifier
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(HomeGridGapDp.dp),
    ) {
        repeat(2) { row ->
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HomeGridGapDp.dp),
            ) {
                repeat(2) { column ->
                    val index = row * 2 + column
                    val shortcut = visibleShortcuts.getOrNull(index - 1)
                    when {
                        index == 0 -> {
                            AppShortcutCell(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                surface = appCellSurface,
                                dividerColor = dividerColor,
                                contentColor = appContentColor,
                                iconSize = iconSize,
                                label = app.label,
                                description = appDescription,
                                actionLabel = tr("アプリを開く", "Open app"),
                                isAppLabel = true,
                                onClick = onClick,
                                icon = {
                                    LauncherIcon(app = app, size = iconSize)
                                },
                            )
                        }

                        shortcut != null -> {
                            AppShortcutCell(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                surface = shortcutCellSurface,
                                dividerColor = dividerColor,
                                contentColor = shortcutContentColor,
                                iconSize = iconSize,
                                label = shortcut.label,
                                description = tr("ショートカット、${shortcut.label}", "Shortcut, ${shortcut.label}"),
                                actionLabel = tr("ショートカットを開く", "Open shortcut"),
                                onClick = { onShortcutClick(shortcut) },
                                icon = {
                                    ShortcutIcon(
                                        shortcut = shortcut,
                                        fallback = app,
                                        size = iconSize,
                                    )
                                },
                            )
                        }

                        else -> {
                            // A subdued visual placeholder communicates the fixed 2x2 layout
                            // without creating a misleading accessibility or touch target.
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .then(
                                        if (LocalLauncherGlass.current.enabled) {
                                            Modifier
                                        } else {
                                            Modifier
                                                .launcherBorder(1.dp, dividerColor)
                                                .background(emptyCellSurface)
                                        },
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppShortcutCell(
    modifier: Modifier,
    surface: Color,
    dividerColor: Color,
    contentColor: Color,
    iconSize: Dp,
    label: String,
    description: String,
    actionLabel: String,
    isAppLabel: Boolean = false,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val glassEnabled = LocalLauncherGlass.current.enabled
    Box(
        modifier = modifier
            .then(
                if (glassEnabled) {
                    Modifier
                } else {
                    Modifier
                        .launcherBorder(1.dp, dividerColor)
                        .background(surface)
                },
            )
            .clickable(
                role = Role.Button,
                onClick = onClick,
            )
            // clickable handles touch; this semantics action supplies a useful TalkBack verb
            // while keeping the cell itself (rather than its icon/text descendants) as one node.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                onClick(label = actionLabel) {
                    onClick()
                    true
                }
            }
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            icon()
            if (shouldRenderAppLabel(LocalShowAppLabels.current, isAppLabel)) {
                Text(
                    text = label,
                    color = contentColor,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ShortcutIcon(
    shortcut: ResolvedLauncherShortcut,
    fallback: LaunchableApp,
    size: Dp,
) {
    AndroidView(
        factory = { viewContext ->
            DynamicIconImageView(viewContext).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        },
        update = { imageView -> imageView.bindApp(fallback, overrideIcon = shortcut.icon) },
        modifier = Modifier.size(size),
    )
}

@Composable
private fun LauncherIcon(app: LaunchableApp, size: Dp) {
    AndroidView(
        factory = { viewContext ->
            DynamicIconImageView(viewContext).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = app.label
            }
        },
        update = { imageView -> imageView.bindApp(app) },
        modifier = Modifier.size(size),
    )
}

internal class AppDrawerGestureSignal {
    private val contaminated = AtomicBoolean(false)

    fun resetForNextGesture() {
        contaminated.set(false)
    }

    fun isContaminated(): Boolean = contaminated.get()

    fun markContaminated() {
        contaminated.set(true)
    }
}

@Composable
internal fun AppDrawer(
    posture: Posture,
    query: String,
    apps: List<LaunchableApp>,
    navigationBottomPadding: Dp = 0.dp,
    searchController: DrawerSearchController,
    allowSearchTargetPickerWhenInactive: Boolean,
    selectedPackage: String?,
    resetRequest: Int,
    onQueryChange: (String) -> Unit,
    onToggleWideSurface: () -> Unit,
    onLongPressApp: (LaunchableApp) -> Unit,
    onOpenApp: (LaunchableApp) -> Unit,
) {
    val drawerGestureSignal = remember { AppDrawerGestureSignal() }
    val drawerGridState = rememberLazyGridState()
    val drawerFloat = rememberHomeFloatState(centersMoveWithScroll = true) {
        drawerGridState.isScrollInProgress
    }
    val drawerSearchState = searchController.state
    var appResultsExpanded by remember { mutableStateOf(false) }
    var contactsVisibleCount by remember { mutableIntStateOf(5) }
    var filesVisibleCount by remember { mutableIntStateOf(5) }

    fun resetSearchPresentation() {
        appResultsExpanded = false
        contactsVisibleCount = 5
        filesVisibleCount = 5
    }

    DrawerSearchPickerEffects(
        controller = searchController,
        allowWhenInactive = allowSearchTargetPickerWhenInactive,
    )
    LaunchedEffect(query) {
        resetSearchPresentation()
        drawerGridState.scrollToItem(0)
    }
    LaunchedEffect(resetRequest) {
        if (resetRequest != 0) {
            resetSearchPresentation()
            drawerGridState.scrollToItem(0)
        }
    }
    val drawerGestureObserver = Modifier.pointerInput(drawerGestureSignal) {
        awaitEachGesture {
            drawerGestureSignal.resetForNextGesture()
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            var contaminated = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!contaminated && event.changes.any { it.id != down.id }) {
                    drawerGestureSignal.markContaminated()
                    contaminated = true
                }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
    val drawerTrailingPadding = if (posture == Posture.INNER_LANDSCAPE) {
        WindowInsets.systemBars
            .union(WindowInsets.displayCutout)
            .asPaddingValues()
            .calculateBottomPadding()
    } else {
        0.dp
    }
    val drawerTopPadding = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Top)
        .asPaddingValues()
        .calculateTopPadding()
    val columns = if (posture == Posture.INNER_LANDSCAPE) 6 else 4
    var drawerHeaderHeightPx by remember { mutableIntStateOf(0) }
    LauncherGlassSceneScope(
        enabled = true,
        geometryVersion = if (LocalLauncherGlass.current.enabled) {
            remember(drawerGridState) {
                GlassGeometrySignal {
                    drawerGridState.firstVisibleItemIndex to drawerGridState.firstVisibleItemScrollOffset
                }
            }
        } else {
            null
        },
    ) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        // MainActivity keeps adjustNothing; only the drawer consumes IME height so the final
        // result/footer remains reachable while the search field owns the keyboard.
        val drawerImeBottomPadding = WindowInsets.ime
            .asPaddingValues()
            .calculateBottomPadding()
        val drawerContentBottomPadding = 8.dp + drawerTrailingPadding + drawerImeBottomPadding +
            if (posture != Posture.INNER_LANDSCAPE && drawerImeBottomPadding <= 0.dp) {
                navigationBottomPadding
            } else {
                0.dp
            }
        val emptyPanelMinHeight = (
            maxHeight -
                with(LocalDensity.current) { drawerHeaderHeightPx.toDp() } -
                3.dp -
                drawerContentBottomPadding
            ).coerceAtLeast(0.dp)
        LazyVerticalGrid(
            modifier = Modifier
                .fillMaxSize()
                .homeFloatViewport(drawerFloat)
                .then(drawerGestureObserver),
            columns = GridCells.Fixed(columns),
            state = drawerGridState,
            contentPadding = PaddingValues(bottom = drawerContentBottomPadding),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item(
                key = "drawer-header",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { size ->
                            if (drawerHeaderHeightPx != size.height) {
                                drawerHeaderHeightPx = size.height
                            }
                        },
                ) {
                    // The inset is part of the first lazy item. It protects the header at scroll 0,
                    // then naturally scrolls away so the drawer can pass underneath the status bar.
                    Spacer(modifier = Modifier.height(drawerTopPadding))
                    if (posture == Posture.INNER_LANDSCAPE) {
                        Header(
                            showWideSwitcher = true,
                            page = LauncherPage.DRAWER,
                            onToggleWideSurface = onToggleWideSurface,
                            includeTopInset = false,
                        )
                    }
                    BasicSearchField(query = query, onQueryChange = onQueryChange)
                    if (query.isBlank()) {
                        ModeHeading(
                            kicker = tr("アプリドロワー", "App drawer"),
                            title = tr("すべてのアプリ", "All apps"),
                            count = tr("${apps.size}件", "${apps.size}"),
                        )
                    } else {
                        DrawerSearchAppsHeading(
                            appCount = apps.size,
                            expanded = appResultsExpanded,
                            canExpand = apps.size > columns * 2,
                        )
                    }
                    // LazyVerticalGrid inserts its 3.dp arrangement between the header and the
                    // first app. Keep the old 4.dp grid content padding by supplying the remaining
                    // 1.dp here, avoiding an initial 3/4.dp shift in the search/header geometry.
                    Spacer(modifier = Modifier.height(1.dp))
                }
            }
            val initialAppCount = columns * 2
            val visibleApps = if (query.isBlank() || appResultsExpanded) {
                apps
            } else {
                apps.take(initialAppCount)
            }
            if (visibleApps.isEmpty()) {
                item(
                    key = "drawer-empty",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    EmptyPanel(
                        text = tr("一致するアプリはありません", "No matching apps"),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = if (query.isBlank()) emptyPanelMinHeight else 96.dp),
                    )
                }
            } else {
                items(items = visibleApps, key = { "drawer-${it.packageName}-${it.className}" }) { app ->
                    val swayId = "drawer-${app.packageName}-${app.className}"
                    if (drawerFloat != null) {
                        DisposableEffect(drawerFloat, swayId) {
                            onDispose { drawerFloat.simulation.remove(swayId) }
                        }
                    }
                    Box(
                        modifier = if (drawerFloat != null) {
                            // Drawer tiles report their live center in root coordinates (its board
                            // origin stays at zero), measured outside their own float layer.
                            Modifier
                                .onGloballyPositioned { coordinates ->
                                    val center = coordinates.positionInRoot() + Offset(
                                        coordinates.size.width / 2f,
                                        coordinates.size.height / 2f,
                                    )
                                    drawerFloat.simulation.place(swayId, center.x, center.y)
                                }
                                .homeFloatOffset(drawerFloat, swayId, enabled = true)
                        } else {
                            Modifier
                        },
                        propagateMinConstraints = true,
                    ) {
                        AppTile(
                            app = app,
                            posture = posture,
                            selected = app.packageName == selectedPackage,
                            size = AppTileSize.SMALL,
                            accessibilityLabel = tr("アプリ、${app.label}", "App, ${app.label}"),
                            drawerGestureSignal = drawerGestureSignal,
                            onClick = { onOpenApp(app) },
                            onLongClick = { onLongPressApp(app) },
                        )
                    }
                }
            }
            if (query.isNotBlank() && apps.size > initialAppCount) {
                item(
                    key = "drawer-app-expansion",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    DrawerSearchExpansionAction(
                        expanded = appResultsExpanded,
                        onClick = { appResultsExpanded = !appResultsExpanded },
                    )
                }
            }
            if (query.isNotBlank()) {
                item(
                    key = "drawer-external-search",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    DrawerSearchExternalSection(onOpen = searchController::openExternal)
                }
                item(
                    key = "drawer-device-search",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    DrawerSearchDeviceSection(
                        state = drawerSearchState,
                        controller = searchController,
                        contactsVisibleCount = contactsVisibleCount,
                        filesVisibleCount = filesVisibleCount,
                        onShowMoreContacts = { contactsVisibleCount += 20 },
                        onCollapseContacts = { contactsVisibleCount = 5 },
                        onShowMoreFiles = { filesVisibleCount += 20 },
                        onCollapseFiles = { filesVisibleCount = 5 },
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun BasicSearchField(query: String, onQueryChange: (String) -> Unit) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val glassEnabled = LocalLauncherGlass.current.enabled
    androidx.compose.foundation.text.BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
        textStyle = androidx.compose.ui.text.TextStyle(
            color = FiiLDAInk,
            fontSize = 15.sp,
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(if (glassEnabled) FiiLDACyan else Color.Black),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .then(
                if (LocalLauncherGlass.current.enabled) {
                    Modifier.launcherGlassSearchControl(fallbackColor = FiiLDADeep)
                } else {
                    Modifier.launcherShapedSurface(
                        LauncherSearchControlShape,
                        FiiLDALineStrong,
                        FiiLDADeep,
                    )
                },
            )
            .padding(start = 10.dp),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .launcherGlassContributor(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = FiiLDACyan,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = tr("アプリ・Web・端末内を検索", "Search apps, web, and device"),
                            color = FiiLDAQuiet,
                            fontSize = 14.sp,
                        )
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = tr("検索文字を全消去", "Clear search"),
                            tint = FiiLDAQuiet,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(10.dp))
                }
            }
        },
    )
}

/**
 * Shared target-page control used by app and widget add dialogs. The page is deliberately
 * controlled by the caller so a selection can be captured synchronously with the item that was
 * clicked, including the asynchronous photo and platform-widget flows.
 */
