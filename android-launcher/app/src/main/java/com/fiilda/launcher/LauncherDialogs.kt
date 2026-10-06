package com.fiilda.launcher

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DisplaySettings
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import kotlin.math.roundToInt

/** Home action sheets and built in/provider widget picker dialogs. */

/**
 * Neutral per-theme roles for the long-press menus. The launcher's Material scheme only replaces a
 * few roles for the custom themes, so the container roles would fall back to Material's baseline
 * purple. Color is reserved for the selected option and for removal.
 */
private data class ActionMenuColors(
    val sheet: Color,
    val group: Color,
    val ink: Color,
    val muted: Color,
    val outline: Color,
    val selected: Color,
    val destructive: Color,
)

private val DarkDestructiveInk = Color(0xFFFFB4AB)

private val ActionSheetShape = RoundedCornerShape(
    topStart = LauncherFolderSheetCornerRadius,
    topEnd = LauncherFolderSheetCornerRadius,
)

private val ActionHeaderBadgeShape = RoundedCornerShape(14.dp)

@Composable
private fun actionMenuColors(): ActionMenuColors {
    val palette = LocalLauncherPalette.current
    val scheme = MaterialTheme.colorScheme
    return when (LocalLauncherTheme.current) {
        LauncherTheme.MATERIAL -> ActionMenuColors(
            sheet = scheme.surfaceContainerLow,
            group = scheme.surfaceContainerHigh,
            ink = scheme.onSurface,
            muted = scheme.onSurfaceVariant,
            outline = scheme.outlineVariant,
            selected = scheme.primary,
            destructive = scheme.error,
        )
        LauncherTheme.GLASS -> ActionMenuColors(
            // Translucent over the blurred home screen unless transparency is reduced.
            sheet = Color(0xFF17191D).copy(
                alpha = if (LocalGlassReduceTransparency.current) 1f else 0.78f,
            ),
            group = Color.White.copy(alpha = 0.08f),
            ink = palette.ink,
            muted = palette.muted,
            outline = Color.White.copy(alpha = 0.18f),
            selected = palette.accent,
            destructive = DarkDestructiveInk,
        )
        LauncherTheme.CLASSIC -> ActionMenuColors(
            sheet = palette.background,
            group = palette.surface,
            ink = palette.ink,
            muted = palette.muted,
            outline = palette.ink.copy(alpha = 0.16f),
            // Red is Classic's accent, so it marks removal and the deep blue marks selection.
            selected = palette.lineStrong,
            destructive = palette.accent,
        )
        LauncherTheme.DEFAULT -> ActionMenuColors(
            sheet = palette.surface,
            group = palette.enabledSurface,
            ink = palette.ink,
            muted = palette.muted,
            outline = palette.line,
            selected = palette.accent,
            destructive = DarkDestructiveInk,
        )
        LauncherTheme.WINDOWS_8 -> ActionMenuColors(
            sheet = palette.deep,
            group = palette.surface,
            ink = palette.ink,
            muted = palette.muted,
            outline = palette.ink.copy(alpha = 0.2f),
            selected = palette.accent,
            destructive = DarkDestructiveInk,
        )
    }
}

@Composable
private fun HomePageTargetSelector(
    selectedHomePage: Int,
    homePageCount: Int,
    homePageChangePending: Boolean,
    onAddHomePage: ((Int?) -> Unit) -> Unit,
    onRemoveHomePage: (Int, (Int?) -> Unit) -> Unit,
    onHomePageSelected: (Int) -> Unit,
    label: String = tr("追加先", "Add to"),
    modifier: Modifier = Modifier,
) {
    val colors = actionMenuColors()
    val normalizedSelectedPage = selectedHomePage.coerceIn(0, homePageCount - 1)
    val pageScrollState = rememberScrollState()
    val pageWidthPx = with(LocalDensity.current) { 104.dp.roundToPx() }
    LaunchedEffect(normalizedSelectedPage, homePageCount) {
        pageScrollState.animateScrollTo(normalizedSelectedPage * pageWidthPx)
    }
    var pageToRemove by remember { mutableStateOf<Int?>(null) }
    pageToRemove?.let { removed ->
        AlertDialog(
            onDismissRequest = { pageToRemove = null },
            title = { Text(tr("ホーム${removed + 1}を削除", "Delete Home ${removed + 1}")) },
            text = { Text(tr("この画面のアプリやウィジェットはホーム${(removed - 1).coerceAtLeast(0) + 1}へ移動します。", "Apps and widgets on this page move to Home ${(removed - 1).coerceAtLeast(0) + 1}.")) },
            confirmButton = {
                TextButton(onClick = {
                    pageToRemove = null
                    onRemoveHomePage(removed) { result ->
                        if (result != null) onHomePageSelected(
                            homePageAfterRemoval(normalizedSelectedPage, removed, homePageCount - 1),
                        )
                    }
                }) { Text(tr("削除", "Delete")) }
            },
            dismissButton = { TextButton(onClick = { pageToRemove = null }) { Text(tr("キャンセル", "Cancel")) } },
        )
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = tr("ホーム画面・$label", "Home pages · $label"),
                color = colors.muted,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = !homePageChangePending,
                onClick = { onAddHomePage { added -> if (added != null) onHomePageSelected(added) } },
            ) {
                Icon(Icons.Filled.Add, contentDescription = tr("ホーム画面を追加", "Add Home page"), tint = colors.ink)
            }
            IconButton(
                enabled = homePageCount > 1 && !homePageChangePending,
                onClick = { pageToRemove = normalizedSelectedPage },
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = tr("ホーム${normalizedSelectedPage + 1}を削除", "Delete Home ${normalizedSelectedPage + 1}"),
                    tint = if (homePageCount > 1) colors.destructive else colors.muted,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(pageScrollState)) {
            SingleChoiceSegmentedButtonRow {
                repeat(homePageCount) { page ->
                    val isSelected = page == normalizedSelectedPage
                    SegmentedButton(
                        selected = isSelected,
                        enabled = !homePageChangePending,
                        onClick = { onHomePageSelected(page) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = page,
                            count = homePageCount,
                        ),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = colors.selected.copy(alpha = 0.16f),
                            activeContentColor = colors.ink,
                            activeBorderColor = colors.outline,
                            inactiveContainerColor = Color.Transparent,
                            inactiveContentColor = colors.muted,
                            inactiveBorderColor = colors.outline,
                        ),
                        modifier = Modifier.width(104.dp).semantics(mergeDescendants = true) {
                            // SegmentedButton already exposes a radio-button role and selected
                            // state. These labels keep the target's purpose explicit in TalkBack.
                            contentDescription = tr("$label、ホーム${page + 1}", "$label, Home ${page + 1}")
                            selected = isSelected
                            stateDescription = if (isSelected) tr("選択中", "Selected") else tr("選択可能", "Available")
                        },
                    ) {
                        Text(
                            text = tr("ホーム${page + 1}", "Home ${page + 1}"),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

private fun appTileContentModeIcon(mode: AppTileContentMode): ImageVector = when (mode) {
    AppTileContentMode.SHORTCUTS -> Icons.Outlined.Extension
    AppTileContentMode.APP_ONLY -> Icons.Outlined.Apps
    AppTileContentMode.NOTIFICATIONS -> Icons.Outlined.NotificationsActive
}

@Composable
private fun ActionSectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = actionMenuColors().muted,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

/**
 * Dialogs use their own platform Window, so the activity-level bar colors do not reach these
 * surfaces. Action sheets use their theme's deep surface; the photo preview overrides the helper
 * with a black, media-focused full-screen bar and light system icons.
 */
@Composable
internal fun LauncherDialogSystemBars(
    barColor: Color? = null,
    lightBars: Boolean? = null,
) {
    val view = LocalView.current
    val palette = LocalLauncherPalette.current
    val dialogWindow = view.parent as? DialogWindowProvider
    SideEffect {
        dialogWindow?.window?.let { window ->
            val resolvedBarColor = barColor ?: palette.deep
            window.statusBarColor = resolvedBarColor.toArgb()
            window.navigationBarColor = resolvedBarColor.toArgb()
            WindowInsetsControllerCompat(window, window.decorView).apply {
                val resolvedLightBars = lightBars ?: palette.isLight
                isAppearanceLightStatusBars = resolvedLightBars
                isAppearanceLightNavigationBars = resolvedLightBars
            }
        }
    }
}

/** Blurs the home screen behind a Glass sheet when the device allows cross-window blur. */
@Composable
private fun DialogBlurBehind() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    val radius = with(LocalDensity.current) { 24.dp.roundToPx() }
    LaunchedEffect(window, radius) {
        if (window != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            window.windowManager.isCrossWindowBlurEnabled
        ) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.also { it.blurBehindRadius = radius }
        }
    }
}

private enum class ActionMenuPage {
    MAIN,
    SIZE,
    DISPLAY,
}

private data class ActionSizeOption(
    val label: String,
    val rows: Int,
    val columns: Int,
    val accessibilityLabel: String,
    val isSelected: Boolean,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionMenuSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = actionMenuColors()
    val blurBehind = LocalLauncherTheme.current == LauncherTheme.GLASS &&
        !LocalGlassReduceTransparency.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = ActionSheetShape,
        containerColor = colors.sheet,
        contentColor = colors.ink,
        tonalElevation = 0.dp,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.muted.copy(alpha = 0.4f)) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        LauncherDialogSystemBars(barColor = Color.Transparent)
        if (blurBehind) DialogBlurBehind()
        content()
    }
}

@Composable
private fun ActionSheet(
    onDismiss: () -> Unit,
    scrollToTopKey: Any? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(scrollToTopKey) {
        scrollState.scrollTo(0)
    }
    ActionMenuSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Keep the main menu reachable on the cover display while still allowing
                // larger font scales to scroll through a subpage's complete option set.
                .heightIn(max = 640.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun ActionSheetHeader(
    label: String,
    subtitle: String,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = actionMenuColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showBack) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = tr("戻る", "Back"),
                    tint = colors.ink,
                )
            }
        } else {
            leading?.invoke()
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = colors.ink,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = colors.muted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ActionHeaderBadge(icon: ImageVector) {
    val colors = actionMenuColors()
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(ActionHeaderBadgeShape)
            .background(colors.group),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.ink,
        )
    }
}

@Composable
private fun ActionTileRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** A quick action shaped like a home tile, used for the actions every menu opens with. */
@Composable
private fun RowScope.ActionTile(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val colors = actionMenuColors()
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .heightIn(min = 72.dp)
            .clip(LauncherTileShape)
            .background(colors.group)
            .clickable(role = Role.Button, onClickLabel = text, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.ink,
        )
        Text(
            text = text,
            color = colors.ink,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActionGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LauncherTileShape)
            .background(actionMenuColors().group),
        content = content,
    )
}

@Composable
private fun ActionSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ActionSectionHeading(title)
        content()
    }
}

@Composable
private fun ActionGroupPageSelector(
    selectedHomePage: Int,
    homePageCount: Int,
    homePageChangePending: Boolean,
    onAddHomePage: ((Int?) -> Unit) -> Unit,
    onRemoveHomePage: (Int, (Int?) -> Unit) -> Unit,
    onHomePageSelected: (Int) -> Unit,
    label: String,
) {
    HomePageTargetSelector(
        selectedHomePage = selectedHomePage,
        homePageCount = homePageCount,
        homePageChangePending = homePageChangePending,
        onAddHomePage = onAddHomePage,
        onRemoveHomePage = onRemoveHomePage,
        onHomePageSelected = onHomePageSelected,
        label = label,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ActionSizeChoiceGrid(
    options: List<ActionSizeOption>,
) {
    val colors = actionMenuColors()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(2).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowOptions.forEach { option ->
                    ActionRow(
                        modifier = Modifier
                            .weight(1f)
                            .clip(LauncherTileShape)
                            .background(colors.group),
                        text = option.label,
                        isSelected = option.isSelected,
                        accessibilityLabel = option.accessibilityLabel,
                        leadingContent = {
                            FootprintIcon(
                                rows = option.rows,
                                columns = option.columns,
                                tint = if (option.isSelected) colors.selected else colors.muted,
                            )
                        },
                        onClick = option.onClick,
                    )
                }
                if (rowOptions.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FootprintIcon(
    rows: Int,
    columns: Int,
    tint: Color,
) {
    val safeRows = rows.coerceAtLeast(1)
    val safeColumns = columns.coerceAtLeast(1)
    Canvas(
        modifier = Modifier
            .size(28.dp)
            .clearAndSetSemantics {},
    ) {
        val gap = 2.dp.toPx()
        // Use square cells and center the resulting footprint. This keeps 1×2 visibly wide and
        // 2×1 visibly tall while still fitting the same 28dp leading slot.
        val cellSize = minOf(
            (size.width - gap * (safeColumns - 1)) / safeColumns,
            (size.height - gap * (safeRows - 1)) / safeRows,
        )
        val footprintWidth = cellSize * safeColumns + gap * (safeColumns - 1)
        val footprintHeight = cellSize * safeRows + gap * (safeRows - 1)
        val offsetX = (size.width - footprintWidth) / 2f
        val offsetY = (size.height - footprintHeight) / 2f
        // Rounded like the home tiles the footprint stands for.
        val cornerRadius = cellSize * 0.25f
        repeat(safeRows) { row ->
            repeat(safeColumns) { column ->
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(
                        x = offsetX + (cellSize + gap) * column,
                        y = offsetY + (cellSize + gap) * row,
                    ),
                    size = Size(cellSize, cellSize),
                    cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                )
            }
        }
    }
}

private fun footprintAccessibilityLabel(
    prefix: String,
    rows: Int,
    columns: Int,
): String = tr("$prefix、${rows}行×${columns}列", "$prefix, ${rows} rows × ${columns} columns")

@Composable
internal fun AppActionDialog(
    app: LaunchableApp,
    isFavorite: Boolean,
    tileSize: AppTileSize,
    contentMode: AppTileContentMode,
    notificationCount: Int,
    notificationAccessGranted: Boolean,
    canUninstall: Boolean,
    initialTargetHomePage: Int,
    currentHomePage: Int?,
    homePageCount: Int,
    homePageChangePending: Boolean,
    onAddHomePage: ((Int?) -> Unit) -> Unit,
    onRemoveHomePage: (Int, (Int?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddWidget: (Int) -> Boolean,
    onAddFavorite: (Int) -> Boolean,
    onMoveFavorite: (Int) -> Boolean,
    onRemoveFavorite: () -> Boolean,
    onUninstall: () -> Boolean,
    onSetTileSize: (AppTileSize) -> Boolean,
    onSetContentMode: (AppTileContentMode) -> Boolean,
    onOpenNotificationSettings: () -> Unit,
) {
    val normalizedInitialTargetHomePage = initialTargetHomePage.coerceIn(0, homePageCount - 1)
    val defaultMoveTargetHomePage = if (currentHomePage != null) {
        (currentHomePage.coerceIn(0, homePageCount - 1) + 1) % homePageCount
    } else {
        normalizedInitialTargetHomePage
    }
    var addTargetHomePage by rememberSaveable(
        app.packageName,
        app.className,
        isFavorite,
        initialTargetHomePage,
    ) {
        mutableIntStateOf(normalizedInitialTargetHomePage)
    }
    var moveTargetHomePage by rememberSaveable(
        app.packageName,
        app.className,
        isFavorite,
    ) {
        mutableIntStateOf(defaultMoveTargetHomePage)
    }
    LaunchedEffect(homePageCount) {
        addTargetHomePage = addTargetHomePage.coerceIn(0, homePageCount - 1)
        moveTargetHomePage = moveTargetHomePage.coerceIn(0, homePageCount - 1)
    }
    var menuPage by remember { mutableStateOf(ActionMenuPage.MAIN) }
    ActionSheet(
        onDismiss = { if (!homePageChangePending) onDismiss() },
        scrollToTopKey = menuPage,
    ) {
        if (homePageChangePending) {
            Text(tr("ホーム画面を保存中…", "Saving Home…"), color = actionMenuColors().ink)
            return@ActionSheet
        }
        // The sheet owns the first back press on a subpage. On the main page the
        // ModalBottomSheet keeps its normal dismiss behavior.
        BackHandler(enabled = menuPage != ActionMenuPage.MAIN) {
            menuPage = ActionMenuPage.MAIN
        }
        when (menuPage) {
            ActionMenuPage.MAIN -> {
                ActionSheetHeader(
                    label = app.label,
                    subtitle = currentHomePage?.let { tr("ホーム${it + 1}のアプリ", "App on Home ${it + 1}") } ?: tr("アプリ", "Apps"),
                    leading = { FolderAppIcon(app, 48.dp) },
                )
                // Settings stays in the first viewport even on the cover display.
                ActionTileRow {
                    ActionTile(
                        text = tr("アプリ情報", "App info"),
                        icon = Icons.Outlined.Info,
                        onClick = onOpenAppInfo,
                    )
                    ActionTile(
                        text = tr("ランチャー設定", "Launcher settings"),
                        icon = Icons.Outlined.Settings,
                        onClick = onOpenSettings,
                    )
                }
                if (!isFavorite) {
                    ActionGroup {
                        // A target is only relevant while adding an app or widget.
                        ActionGroupPageSelector(
                            selectedHomePage = addTargetHomePage,
                            homePageCount = homePageCount,
                            homePageChangePending = homePageChangePending,
                            onAddHomePage = onAddHomePage,
                            onRemoveHomePage = onRemoveHomePage,
                            onHomePageSelected = { addTargetHomePage = it },
                            label = tr("追加先", "Add to"),
                        )
                        ActionRow(
                            text = tr("ホームに追加", "Add to Home"),
                            icon = Icons.Outlined.Home,
                            onClick = { if (onAddFavorite(addTargetHomePage)) onDismiss() },
                        )
                        ActionRow(
                            text = tr("ウィジェットを追加", "Add widget"),
                            icon = Icons.Outlined.Widgets,
                            onClick = { if (onAddWidget(addTargetHomePage)) onDismiss() },
                        )
                    }
                } else {
                    ActionGroup {
                        ActionRow(
                            text = tr("サイズ", "Size"),
                            icon = Icons.Outlined.AspectRatio,
                            value = tileSize.label,
                            showChevron = true,
                            accessibilityLabel = footprintAccessibilityLabel(
                                prefix = tr("サイズを変更。現在", "Change size. Currently"),
                                rows = tileSize.rowSpan,
                                columns = tileSize.columnSpan,
                            ),
                            onClick = { menuPage = ActionMenuPage.SIZE },
                        )
                        ActionRow(
                            text = tr("表示と通知", "Display and notifications"),
                            icon = Icons.Outlined.DisplaySettings,
                            value = contentMode.label,
                            showChevron = true,
                            accessibilityLabel = tr("表示と通知。現在 ${contentMode.label}", "Display and notifications. Currently ${contentMode.label}"),
                            onClick = { menuPage = ActionMenuPage.DISPLAY },
                        )
                        ActionRow(
                            text = tr("ウィジェットを追加", "Add widget"),
                            icon = Icons.Outlined.Widgets,
                            onClick = { if (onAddWidget(addTargetHomePage)) onDismiss() },
                        )
                    }
                    if (currentHomePage != null) {
                        ActionGroup {
                            ActionGroupPageSelector(
                                selectedHomePage = moveTargetHomePage,
                                homePageCount = homePageCount,
                                homePageChangePending = homePageChangePending,
                                onAddHomePage = onAddHomePage,
                                onRemoveHomePage = onRemoveHomePage,
                                onHomePageSelected = { moveTargetHomePage = it },
                                label = tr("移動先", "Move to"),
                            )
                            ActionRow(
                                text = tr("ホーム${moveTargetHomePage + 1}へ移動", "Move to Home ${moveTargetHomePage + 1}"),
                                icon = Icons.AutoMirrored.Outlined.DriveFileMove,
                                onClick = { if (onMoveFavorite(moveTargetHomePage)) onDismiss() },
                            )
                        }
                    }
                }
                if (isFavorite || canUninstall) {
                    ActionGroup {
                        if (isFavorite) {
                            ActionRow(
                                text = tr("ホームから削除", "Remove from Home"),
                                icon = Icons.Outlined.RemoveCircleOutline,
                                destructive = true,
                                onClick = { if (onRemoveFavorite()) onDismiss() },
                            )
                        }
                        if (canUninstall) {
                            ActionRow(
                                text = tr("アンインストール", "Uninstall"),
                                icon = Icons.Outlined.Delete,
                                destructive = true,
                                accessibilityLabel = tr("${app.label}をアンインストール", "Uninstall ${app.label}"),
                                onClick = { if (onUninstall()) onDismiss() },
                            )
                        }
                    }
                }
            }

            ActionMenuPage.SIZE -> {
                ActionSheetHeader(
                    label = app.label,
                    subtitle = tr("サイズ", "Size"),
                    showBack = true,
                    onBack = { menuPage = ActionMenuPage.MAIN },
                )
                val liveSizes = setOf(AppTileSize.TALL_3X1, AppTileSize.TALL_3X2)
                listOf(
                    tr("基本サイズ", "Standard sizes") to AppTileSize.values().filterNot { it in liveSizes },
                    tr("通知ライブ向けサイズ", "Sizes for live notifications") to AppTileSize.values().filter { it in liveSizes },
                ).forEach { (title, sizes) ->
                    ActionSection(title) {
                        ActionSizeChoiceGrid(
                            options = sizes.map { option ->
                                ActionSizeOption(
                                    label = option.label,
                                    rows = option.rowSpan,
                                    columns = option.columnSpan,
                                    accessibilityLabel = footprintAccessibilityLabel(
                                        prefix = tr("サイズ", "Size"),
                                        rows = option.rowSpan,
                                        columns = option.columnSpan,
                                    ),
                                    isSelected = option == tileSize,
                                    onClick = { if (onSetTileSize(option)) onDismiss() },
                                )
                            },
                        )
                    }
                }
            }

            ActionMenuPage.DISPLAY -> {
                ActionSheetHeader(
                    label = app.label,
                    subtitle = tr("表示と通知", "Display and notifications"),
                    showBack = true,
                    onBack = { menuPage = ActionMenuPage.MAIN },
                )
                ActionSection(tr("表示内容", "Content")) {
                    ActionGroup {
                        AppTileContentMode.values().forEach { option ->
                            ActionRow(
                                text = option.label,
                                icon = appTileContentModeIcon(option),
                                isSelected = option == contentMode,
                                accessibilityLabel = tr("表示内容、${option.label}", "Content, ${option.label}"),
                                onClick = { if (onSetContentMode(option)) onDismiss() },
                            )
                        }
                    }
                    if (contentMode == AppTileContentMode.NOTIFICATIONS &&
                        tileSize == AppTileSize.SMALL
                    ) {
                        ActionNote(
                            tr("1×1では通知ライブの行を表示せず、アプリアイコンと現在の通知件数バッジを表示します。", "At 1×1, live notification rows are hidden and the app icon shows a notification count badge."),
                        )
                    }
                }
                ActionSection(tr("通知ライブ", "Live notifications")) {
                    ActionNote(
                        if (notificationAccessGranted) {
                            tr("現在の通知：${notificationCount}件", "Current notifications: ${notificationCount}")
                        } else {
                            tr("通知へのアクセスが必要です（現在の通知：${notificationCount}件）", "Notification access is required (current notifications: ${notificationCount})")
                        },
                    )
                    if (!notificationAccessGranted) {
                        ActionGroup {
                            ActionRow(
                                text = tr("通知へのアクセスを設定", "Set up notification access"),
                                icon = Icons.Outlined.NotificationsActive,
                                showChevron = true,
                                onClick = onOpenNotificationSettings,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionNote(text: String) {
    Text(
        text = text,
        color = actionMenuColors().muted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WidgetSelectorDialog(
    availableBuiltIns: List<HomeWidget>,
    providerGroups: List<WidgetPickerGroup>,
    preferredPackage: String?,
    selectedHomePage: Int,
    homePageCount: Int,
    homePageChangePending: Boolean,
    onAddHomePage: ((Int?) -> Unit) -> Unit,
    onRemoveHomePage: (Int, (Int?) -> Unit) -> Unit,
    onHomePageSelected: (Int) -> Unit,
    onBuiltIn: (HomeWidget, Int) -> Unit,
    onExternal: (WidgetPickerProvider, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val orderedGroups = remember(providerGroups, preferredPackage) {
        providerGroups.sortedWith(
            compareByDescending<WidgetPickerGroup> { it.packageName == preferredPackage }
                .thenBy { it.appLabel.lowercase(Locale.getDefault()) }
                .thenBy { it.profileKey },
        )
    }
    var expandedKeys by remember(orderedGroups, preferredPackage) {
        mutableStateOf(
            preferredWidgetPickerGroupKeys(
                groups = orderedGroups.map { it.key },
                preferredPackage = preferredPackage,
            ),
        )
    }
    // Keep a local source of truth for the picker interaction. The parent is notified as well,
    // but the local value is updated first so a target tap followed immediately by an item tap
    // cannot read the parent's pre-recomposition value.
    var pickerHomePage by rememberSaveable(selectedHomePage) {
        mutableIntStateOf(selectedHomePage.coerceIn(0, homePageCount - 1))
    }
    val colors = actionMenuColors()
    ActionMenuSheet(onDismiss = { if (!homePageChangePending) onDismiss() }) {
        if (homePageChangePending) {
            Text(tr("ホーム画面を保存中…", "Saving Home…"), modifier = Modifier.padding(24.dp), color = colors.ink)
            return@ActionMenuSheet
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr("ウィジェットを追加", "Add widget"),
                        color = colors.ink,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = tr("アプリを開いて表示を選択してください", "Open an app to choose what to show"),
                        color = colors.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = tr("閉じる", "Close"),
                        tint = colors.ink,
                    )
                }
            }
            HomePageTargetSelector(
                selectedHomePage = pickerHomePage,
                homePageCount = homePageCount,
                homePageChangePending = homePageChangePending,
                onAddHomePage = onAddHomePage,
                onRemoveHomePage = onRemoveHomePage,
                onHomePageSelected = { targetHomePage ->
                    val normalizedTarget = targetHomePage.coerceIn(0, homePageCount - 1)
                    pickerHomePage = normalizedTarget
                    onHomePageSelected(normalizedTarget)
                },
                modifier = Modifier.padding(top = 8.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    // Fill the dialog's bounded remaining height so scrolling never remeasures
                    // the list from its visible content and shifts into the target selector.
                    .weight(1f)
                    .clipToBounds(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 2.dp),
            ) {
                if (availableBuiltIns.isNotEmpty()) {
                    item(key = "fiilda-widgets-heading") {
                        ActionSectionHeading(
                            tr("FiiLDA ウィジェット", "FiiLDA widgets"),
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        )
                    }
                    lazyItems(
                        items = availableBuiltIns,
                        key = { "fiilda-${it.id}" },
                    ) { widget ->
                        ActionRow(
                            modifier = Modifier
                                .clip(LauncherTileShape)
                                .background(colors.group),
                            text = widget.label,
                            icon = homeWidgetIcon(widget),
                            onClick = {
                                onBuiltIn(
                                    widget,
                                    pickerHomePage.coerceIn(0, homePageCount - 1),
                                )
                            },
                        )
                    }
                }
                if (orderedGroups.isNotEmpty()) {
                    item(key = "installed-widget-heading") {
                        ActionSectionHeading(
                            tr("インストール済みアプリ", "Installed apps"),
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        )
                    }
                    orderedGroups.forEach { group ->
                        item(key = "group-${group.key}") {
                            WidgetPickerGroupHeader(
                                group = group,
                                expanded = group.key in expandedKeys,
                                onClick = {
                                    expandedKeys = if (group.key in expandedKeys) {
                                        expandedKeys - group.key
                                    } else {
                                        expandedKeys + group.key
                                    }
                                },
                            )
                        }
                        if (group.key in expandedKeys) {
                            item(key = "group-divider-${group.key}") {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 16.dp),
                                    color = colors.outline,
                                )
                            }
                            lazyItems(
                                items = group.providers,
                                key = { provider ->
                                    "provider-${group.key}-${provider.componentKey}"
                                },
                            ) { provider ->
                                WidgetProviderPickerRow(
                                    provider = provider,
                                    appIcon = group.appIcon,
                                    modifier = Modifier.padding(start = 16.dp),
                                    onClick = {
                                        onExternal(
                                            provider,
                                            pickerHomePage.coerceIn(0, homePageCount - 1),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                if (availableBuiltIns.isEmpty() && orderedGroups.isEmpty()) {
                    item(key = "no-widgets") {
                        EmptyPanel(
                            text = tr("追加できるウィジェットがありません", "No widgets to add"),
                            modifier = Modifier.heightIn(min = 92.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetPickerGroupHeader(
    group: WidgetPickerGroup,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = actionMenuColors()
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LauncherTileShape)
            .heightIn(min = 68.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = tr("${group.appLabel}、${group.profileLabel}、${group.providers.size}個のウィジェット", "${group.appLabel}, ${group.profileLabel}, ${group.providers.size} widgets")
                stateDescription = if (expanded) tr("展開中", "Expanded") else tr("折りたたみ", "Collapsed")
            },
        headlineContent = {
            Text(
                text = group.appLabel,
                color = colors.ink,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = tr("${group.profileLabel}  ·  ${group.providers.size}個", "${group.profileLabel}  ·  ${group.providers.size}"),
                color = colors.muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            WidgetPickerIcon(
                icon = group.appIcon,
                contentDescription = group.appLabel,
                modifier = Modifier.size(40.dp),
            )
        },
        trailingContent = {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = colors.muted,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = colors.group,
        ),
    )
}

@Composable
private fun WidgetProviderPickerRow(
    provider: WidgetPickerProvider,
    appIcon: Drawable?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = actionMenuColors()
    val info = provider.info
    val context = LocalContext.current
    val label = remember(info.provider) {
        runCatching { info.loadLabel(context.packageManager).toString() }
            .getOrDefault("")
            .ifBlank { tr("ウィジェット", "Widget") }
    }
    val description = remember(info.provider, widgetDescriptionResource(info), provider.profile) {
        widgetDescription(context, provider)
    }
    val footprint = remember(
        info.provider,
        info.minWidth,
        info.minHeight,
        widgetTargetCellWidth(info),
        widgetTargetCellHeight(info),
    ) {
        widgetPickerFootprintLabel(widgetPickerGridSize(context, info))
    }
    val providerIcon = remember(info.provider) {
        runCatching {
            info.loadIcon(context, context.resources.displayMetrics.densityDpi)
        }.getOrNull() ?: appIcon
    }
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .then(modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = buildString {
                    append(label)
                    description?.takeIf { it.isNotBlank() }?.let { append(tr("、$it", ", $it")) }
                    append(tr("、$footprint", ", $footprint"))
                }
                onClick {
                    onClick()
                    true
                }
            },
        leadingContent = {
            WidgetPreviewCard(
                provider = provider,
                fallbackIcon = providerIcon,
                label = label,
                onClick = onClick,
                modifier = Modifier
                    .width(116.dp)
                    .height(96.dp),
            )
        },
        headlineContent = {
            Text(
                text = label,
                color = colors.ink,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = colors.muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = footprint,
                    color = colors.muted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = colors.muted,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
        ),
    )
}

@Composable
private fun WidgetPickerIcon(
    icon: Drawable?,
    contentDescription: String,
    modifier: Modifier,
    accessible: Boolean = true,
) {
    AndroidView(
        factory = { viewContext ->
            ImageView(viewContext).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(icon ?: viewContext.getDrawable(android.R.drawable.sym_def_app_icon))
                importantForAccessibility = if (accessible) {
                    View.IMPORTANT_FOR_ACCESSIBILITY_YES
                } else {
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                isFocusable = accessible
                this.contentDescription = contentDescription.takeIf { accessible }
            }
        },
        modifier = modifier,
    )
}

/** Measures an inflated preview layout with contain semantics so its aspect ratio is never cropped. */
private class WidgetPreviewLayoutHost(
    context: Context,
    preview: View,
) : FrameLayout(context) {
    private val previewView = preview
    private var previewWidth = 0
    private var previewHeight = 0

    init {
        clipChildren = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        isFocusable = false
        addView(previewView)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        previewView.measure(
            MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(availableHeight, MeasureSpec.AT_MOST),
        )
        val naturalWidth = previewView.measuredWidth.coerceAtLeast(1)
        val naturalHeight = previewView.measuredHeight.coerceAtLeast(1)
        val scale = minOf(
            availableWidth.toFloat() / naturalWidth,
            availableHeight.toFloat() / naturalHeight,
        )
        previewWidth = (naturalWidth * scale).roundToInt().coerceAtLeast(1)
        previewHeight = (naturalHeight * scale).roundToInt().coerceAtLeast(1)
        previewView.measure(
            MeasureSpec.makeMeasureSpec(previewWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(previewHeight, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(
            resolveSize(availableWidth, widthMeasureSpec),
            resolveSize(availableHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val childLeft = (right - left - previewWidth) / 2
        val childTop = (bottom - top - previewHeight) / 2
        previewView.layout(
            childLeft,
            childTop,
            childLeft + previewWidth,
            childTop + previewHeight,
        )
    }
}

@Composable
private fun WidgetPreviewCard(
    provider: WidgetPickerProvider,
    fallbackIcon: Drawable?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = actionMenuColors()
    val info = provider.info
    val context = LocalContext.current
    val asset = remember(
        info.provider,
        widgetPreviewLayoutResource(info),
        widgetPreviewImageResource(info),
        provider.profile,
    ) {
        loadWidgetPreviewAsset(context, provider)
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = colors.group,
        contentColor = colors.ink,
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Keep a visible icon behind provider previews. It guarantees that a transparent or
            // malformed preview layout never renders as an empty card.
            WidgetPickerIcon(
                icon = fallbackIcon,
                contentDescription = label,
                modifier = Modifier.fillMaxSize().padding(16.dp),
                accessible = false,
            )
            asset.layout?.let { previewLayout ->
                AndroidView(
                    factory = { viewContext ->
                        WidgetPreviewLayoutHost(viewContext, previewLayout)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } ?: asset.image?.let { previewImage ->
                AndroidView(
                    factory = { viewContext ->
                        ImageView(viewContext).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setImageDrawable(previewImage)
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            isFocusable = false
                            contentDescription = null
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Provider preview layouts can contain interactive Views that otherwise consume the
            // row tap (for example, contact action buttons). Keep the visual AndroidView intact
            // but put a full-size Compose hit target above it. Clearing this node's semantics
            // leaves the parent row as the single accessible button and prevents duplicate
            // actions.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .zIndex(1f)
                    .clickable(role = Role.Button, onClick = onClick)
                    .clearAndSetSemantics {},
            )
        }
    }
}

@Composable
internal fun WidgetActionDialog(
    label: String,
    currentSize: WidgetSizeChoice,
    includeAuto: Boolean,
    onEdit: (() -> Unit)? = null,
    availableSizes: List<WidgetSizeChoice> = FixedWidgetSizeChoices,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onSetSize: (WidgetSizeChoice) -> Boolean,
    onRemove: () -> Boolean,
) {
    var showSizes by remember { mutableStateOf(false) }
    ActionSheet(
        onDismiss = onDismiss,
        scrollToTopKey = showSizes,
    ) {
        BackHandler(enabled = showSizes) {
            showSizes = false
        }
        if (!showSizes) {
            ActionSheetHeader(
                label = label,
                subtitle = tr("ウィジェット", "Widget"),
                leading = { ActionHeaderBadge(Icons.Outlined.Widgets) },
            )
            // Settings stays in the first viewport even for a photo widget on the cover display.
            ActionTileRow {
                onEdit?.let {
                    ActionTile(
                        text = tr("編集", "Edit"),
                        icon = Icons.Outlined.Edit,
                        onClick = it,
                    )
                }
                ActionTile(
                    text = tr("ランチャー設定", "Launcher settings"),
                    icon = Icons.Outlined.Settings,
                    onClick = onOpenSettings,
                )
            }
            ActionGroup {
                ActionRow(
                    text = tr("サイズ", "Size"),
                    icon = Icons.Outlined.AspectRatio,
                    value = currentSize.label,
                    showChevron = true,
                    accessibilityLabel = if (currentSize.isAuto) {
                        tr("サイズを変更。現在は自動（元のサイズ）", "Change size. Currently automatic (original size)")
                    } else {
                        footprintAccessibilityLabel(
                            prefix = tr("サイズを変更。現在", "Change size. Currently"),
                            rows = currentSize.rowSpan,
                            columns = currentSize.columnSpan,
                        )
                    },
                    onClick = { showSizes = true },
                )
            }
            ActionGroup {
                ActionRow(
                    text = tr("ホームから削除", "Remove from Home"),
                    icon = Icons.Outlined.RemoveCircleOutline,
                    destructive = true,
                    onClick = { if (onRemove()) onDismiss() },
                )
            }
        } else {
            ActionSheetHeader(
                label = label,
                subtitle = tr("サイズ", "Size"),
                showBack = true,
                onBack = { showSizes = false },
            )
            if (includeAuto) {
                ActionGroup {
                    ActionRow(
                        text = WidgetSizeChoice.AUTO.label,
                        icon = Icons.Outlined.AutoMode,
                        isSelected = currentSize == WidgetSizeChoice.AUTO,
                        accessibilityLabel = tr("サイズ、自動（元のサイズ）", "Size, automatic (original size)"),
                        onClick = { if (onSetSize(WidgetSizeChoice.AUTO)) onDismiss() },
                    )
                }
            }
            ActionSizeChoiceGrid(
                options = availableSizes.distinct().map { option ->
                    ActionSizeOption(
                        label = option.label,
                        rows = option.rowSpan,
                        columns = option.columnSpan,
                        accessibilityLabel = footprintAccessibilityLabel(
                            prefix = tr("サイズ", "Size"),
                            rows = option.rowSpan,
                            columns = option.columnSpan,
                        ),
                        isSelected = currentSize == option,
                        onClick = { if (onSetSize(option)) onDismiss() },
                    )
                },
            )
        }
    }
}

@Composable
internal fun PinnedShortcutActionDialog(
    shortcut: ResolvedPinnedShortcut,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onOpenSettings: () -> Unit,
    onRemove: () -> Boolean,
) {
    ActionSheet(onDismiss = onDismiss) {
        ActionSheetHeader(
            label = shortcut.label,
            subtitle = tr("ショートカット", "Shortcut"),
            leading = {
                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            setImageDrawable(shortcut.icon)
                        }
                    },
                    modifier = Modifier.size(48.dp),
                )
            },
        )
        if (!shortcut.isAvailable) {
            ActionNote(tr("現在利用できません。プロフィールまたはアプリの状態を確認してください。", "Unavailable right now. Check the profile or the app."))
        }
        ActionTileRow {
            ActionTile(
                text = tr("開く", "Open"),
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                onClick = onOpen,
            )
            ActionTile(
                text = tr("ランチャー設定", "Launcher settings"),
                icon = Icons.Outlined.Settings,
                onClick = onOpenSettings,
            )
        }
        ActionGroup {
            ActionRow(
                text = tr("ホームから削除", "Remove from Home"),
                icon = Icons.Outlined.RemoveCircleOutline,
                destructive = true,
                onClick = { if (onRemove()) onDismiss() },
            )
        }
    }
}

@Composable
internal fun FolderActionDialog(
    folder: HomeFolder,
    onDismiss: () -> Unit,
    onSetSize: (HomeFolderSize) -> Boolean,
    onRename: (String) -> Boolean,
    onDissolve: () -> Boolean,
) {
    val colors = actionMenuColors()
    var name by rememberSaveable(folder.id) { mutableStateOf(folder.name) }
    val canRename = name != folder.name
    val rename = { if (onRename(name)) onDismiss() }
    ActionSheet(onDismiss = onDismiss) {
        ActionSheetHeader(
            label = folder.name.ifBlank { tr("フォルダ", "Folder") },
            subtitle = tr("フォルダ・${folder.memberIds.size}個のアプリ", "Folder · ${folder.memberIds.size} apps"),
            leading = { ActionHeaderBadge(Icons.Outlined.Folder) },
        )
        ActionSection(tr("名前", "Name")) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(48) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = LauncherTileShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canRename) rename() }),
                trailingIcon = {
                    IconButton(onClick = rename, enabled = canRename) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = tr("名前を保存", "Save name"),
                            tint = if (canRename) colors.selected else colors.muted.copy(alpha = 0.38f),
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.ink,
                    unfocusedTextColor = colors.ink,
                    focusedContainerColor = colors.group,
                    unfocusedContainerColor = colors.group,
                    focusedBorderColor = colors.selected,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = colors.selected,
                ),
            )
        }
        ActionSection(tr("サイズ", "Size")) {
            ActionSizeChoiceGrid(
                options = HomeFolderSize.values().map { size ->
                    ActionSizeOption(
                        label = size.label,
                        rows = size.rowSpan,
                        columns = size.columnSpan,
                        accessibilityLabel = footprintAccessibilityLabel(
                            prefix = tr("サイズ", "Size"),
                            rows = size.rowSpan,
                            columns = size.columnSpan,
                        ),
                        isSelected = size == folder.size,
                        onClick = { if (onSetSize(size)) onDismiss() },
                    )
                },
            )
        }
        ActionGroup {
            ActionRow(
                text = tr("フォルダを解散", "Ungroup folder"),
                icon = Icons.Outlined.FolderOff,
                destructive = true,
                onClick = { if (onDissolve()) onDismiss() },
            )
        }
    }
}

@Composable
private fun ActionRow(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    value: String? = null,
    showChevron: Boolean = false,
    destructive: Boolean = false,
    isSelected: Boolean? = null,
    accessibilityLabel: String = text,
    onClick: () -> Unit,
) {
    val colors = actionMenuColors()
    val selectedState = isSelected == true
    val iconColor = when {
        destructive -> colors.destructive
        selectedState -> colors.selected
        else -> colors.muted
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (selectedState) {
                    Modifier.background(colors.selected.copy(alpha = 0.14f))
                } else {
                    Modifier
                },
            )
            .clickable(
                role = Role.Button,
                onClickLabel = accessibilityLabel,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = accessibilityLabel
                isSelected?.let { selected = it }
                if (isSelected != null) {
                    stateDescription = if (selectedState) tr("選択中", "Selected") else tr("選択可能", "Available")
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            leadingContent != null -> leadingContent()
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
            )
        }
        Text(
            text = text,
            color = if (destructive) colors.destructive else colors.ink,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        value?.let {
            Text(
                text = it,
                color = colors.muted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            selectedState -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = colors.selected,
            )
            showChevron -> Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = colors.muted,
            )
        }
    }
}
