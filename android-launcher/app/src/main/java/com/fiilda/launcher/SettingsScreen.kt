package com.fiilda.launcher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** A section is intentionally just a title and rows, so new settings can be added locally. */
private data class SettingsSection(
    val title: String,
    val rows: List<SettingsRow> = emptyList(),
    val content: (@Composable () -> Unit)? = null,
)

/** A null [onClick] marks an informational row and keeps it from looking actionable. */
private data class SettingsRow(
    val title: String,
    val summary: String? = null,
    val value: String? = null,
    val selected: Boolean = false,
    val isThemeOption: Boolean = false,
    val isToggle: Boolean = false,
    val onValueChange: ((Boolean) -> Unit)? = null,
    val onClick: (() -> Unit)? = null,
)

@Composable
internal fun SettingsScreen(
    searchController: DrawerSearchController,
    onBack: () -> Unit,
) {
    SettingsMaterialTheme {
        SettingsScreenContent(
            searchController = searchController,
            onBack = onBack,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenContent(
    searchController: DrawerSearchController,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val currentTheme = LocalLauncherTheme.current
    val changeTheme = LocalLauncherThemeChanger.current
    val showAppLabels = LocalShowAppLabels.current
    val changeAppLabels = LocalShowAppLabelsChanger.current
    val showNotificationBadges = LocalShowNotificationBadges.current
    val changeNotificationBadges = LocalShowNotificationBadgesChanger.current
    val separateWideHomeOrder = LocalSeparateWideHomeOrder.current
    val changeSeparateWideHomeOrder = LocalSeparateWideHomeOrderChanger.current
    val reduceMotion = LocalReduceMotion.current
    val changeReduceMotion = LocalReduceMotionChanger.current
    var themeRotation by remember { mutableStateOf(readThemeRotationConfig(context)) }
    var showThemeRotationIntervalDialog by remember { mutableStateOf(false) }
    val notificationAccessGranted = notificationListenerAccessGranted(context)
    val versionName = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: tr("不明", "Unknown")
    fun openSystemSettings(intent: Intent, destinationLabel: String) {
        val unavailableMessage = tr("${destinationLabel}を開けませんでした", "Couldn't open ${destinationLabel}")
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, unavailableMessage, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, unavailableMessage, Toast.LENGTH_SHORT).show()
        }
    }
    fun selectTheme(theme: LauncherTheme) {
        if (theme == currentTheme) return
        if (!changeTheme(theme)) {
            Toast.makeText(context, tr("テーマを保存できませんでした", "Couldn't save the theme"), Toast.LENGTH_SHORT).show()
        }
    }
    fun setAppLabelsVisible(visible: Boolean) {
        if (visible == showAppLabels) return
        if (!changeAppLabels(visible)) {
            Toast.makeText(context, tr("アプリ名の表示設定を保存できませんでした", "Couldn't save the app name setting"), Toast.LENGTH_SHORT).show()
        }
    }
    fun setNotificationBadgesVisible(visible: Boolean) {
        if (visible == showNotificationBadges) return
        if (!changeNotificationBadges(visible)) {
            Toast.makeText(context, tr("通知件数バッジの表示設定を保存できませんでした", "Couldn't save the badge setting"), Toast.LENGTH_SHORT).show()
        }
    }
    fun setReduceMotion(reduce: Boolean) {
        if (reduce == reduceMotion) return
        if (!changeReduceMotion(reduce)) {
            Toast.makeText(context, tr("アニメーション効果の設定を保存できませんでした", "Couldn't save the animation setting"), Toast.LENGTH_SHORT).show()
        }
    }
    fun setSeparateWideHomeOrder(separate: Boolean) {
        if (separate == separateWideHomeOrder) return
        if (!changeSeparateWideHomeOrder(separate)) {
            Toast.makeText(context, tr("横スクロール時の配置設定を保存できませんでした", "Couldn't save the sideways layout setting"), Toast.LENGTH_SHORT).show()
        }
    }
    fun saveThemeRotation(updated: ThemeRotationConfig) {
        if (!saveThemeRotationConfig(context, updated)) {
            Toast.makeText(context, tr("テーマローテーション設定を保存できませんでした", "Couldn't save the theme rotation setting"), Toast.LENGTH_SHORT).show()
            return
        }
        if (!ThemeRotationScheduler.update(context, updated)) {
            saveThemeRotationConfig(context, themeRotation)
            ThemeRotationScheduler.update(context, themeRotation)
            Toast.makeText(context, tr("テーマ切替を予約できませんでした", "Couldn't schedule the theme change"), Toast.LENGTH_SHORT).show()
            return
        }
        themeRotation = updated
    }
    val showOnboarding = LocalShowOnboarding.current
    val sections = listOf(
        SettingsSection(
            title = tr("ランチャー情報", "About"),
            rows = listOf(
                SettingsRow(
                    title = tr("バージョン", "Version"),
                    value = versionName,
                ),
                SettingsRow(
                    title = tr("使い方を見る", "How to use"),
                    summary = tr("はじめに表示される案内をもう一度表示します", "Shows the introduction again"),
                    onClick = showOnboarding,
                ),
            ),
        ),
        SettingsSection(
            title = tr("検索", "Search"),
            content = {
                SettingsSearchTargetManagement(
                    state = searchController.state,
                    controller = searchController,
                )
            },
        ),
        SettingsSection(
            title = tr("表示", "Display"),
            rows = listOf(
                SettingsRow(
                    title = tr("アプリ名を表示", "Show app names"),
                    summary = tr("ホームとアプリ一覧でアプリ名を表示します", "Shows app names on Home and in the app list"),
                    value = if (showAppLabels) tr("オン", "On") else tr("オフ", "Off"),
                    selected = showAppLabels,
                    isToggle = true,
                    onValueChange = ::setAppLabelsVisible,
                ),
                SettingsRow(
                    title = tr("横スクロール時の配置を分ける", "Separate sideways layout"),
                    summary = tr("通常表示と横スクロール時で、並び順とタイル／ウィジェットサイズを別々に保存します", "Saves a separate order and tile/widget sizes for the sideways layout"),
                    value = if (separateWideHomeOrder) tr("オン", "On") else tr("オフ", "Off"),
                    selected = separateWideHomeOrder,
                    isToggle = true,
                    onValueChange = ::setSeparateWideHomeOrder,
                ),
                SettingsRow(
                    title = tr("アニメーション効果を減らす", "Reduce animation"),
                    summary = tr("ホームをスクロールしたときにタイルが浮かぶ動きを止めます", "Stops tiles floating when you scroll Home"),
                    value = if (reduceMotion) tr("オン", "On") else tr("オフ", "Off"),
                    selected = reduceMotion,
                    isToggle = true,
                    onValueChange = ::setReduceMotion,
                ),
            ),
        ),
        SettingsSection(
            title = tr("テーマ", "Theme"),
            rows = LauncherTheme.values().map { theme ->
                SettingsRow(
                    title = theme.displayName,
                    summary = theme.description,
                    value = if (theme == currentTheme) tr("選択中", "Selected") else null,
                    selected = theme == currentTheme,
                    isThemeOption = true,
                    onClick = { selectTheme(theme) },
                )
            },
        ),
        SettingsSection(
            title = tr("テーマローテーション", "Theme rotation"),
            rows = listOf(
                SettingsRow(
                    title = tr("テーマを自動で切り替える", "Switch themes automatically"),
                    summary = tr("選択したテーマを順番に切り替えます。省電力中は時刻が前後する場合があります", "Cycles through the selected themes. Timing may shift in battery saver"),
                    selected = themeRotation.enabled,
                    isToggle = true,
                    onValueChange = { enabled ->
                        saveThemeRotation(themeRotation.copy(enabled = enabled))
                    },
                ),
                SettingsRow(
                    title = tr("切替間隔", "Interval"),
                    value = ThemeRotationInterval.values()
                        .first { it.millis == themeRotation.intervalMillis }.label,
                    onClick = { showThemeRotationIntervalDialog = true },
                ),
            ),
        ),
        SettingsSection(
            title = tr("ローテーション対象", "Themes to rotate"),
            rows = LauncherTheme.values().map { theme ->
                SettingsRow(
                    title = theme.displayName,
                    summary = theme.description,
                    selected = theme in themeRotation.themes,
                    isToggle = true,
                    onValueChange = { selected ->
                        val updatedThemes = themeRotation.themes.toMutableSet().apply {
                            if (selected) add(theme) else remove(theme)
                        }
                        if (updatedThemes.size < 2) {
                            Toast.makeText(context, tr("対象テーマを2つ以上選んでください", "Choose at least two themes"), Toast.LENGTH_SHORT).show()
                        } else {
                            saveThemeRotation(themeRotation.copy(themes = updatedThemes))
                        }
                    },
                )
            },
        ),
        SettingsSection(
            title = tr("端末の設定", "Device settings"),
            rows = listOf(
                SettingsRow(
                    title = tr("ホームランチャー", "Home app"),
                    summary = tr("ホームアプリを選ぶ画面を開きます", "Opens the screen for choosing your Home app"),
                    onClick = {
                        openSystemSettings(
                            Intent(Settings.ACTION_HOME_SETTINGS),
                            tr("ホームランチャーの設定", "Home app settings"),
                        )
                    },
                ),
            ),
        ),
        SettingsSection(
            title = tr("通知", "Notifications"),
            rows = listOf(
                SettingsRow(
                    title = tr("通知件数バッジを表示", "Show notification badges"),
                    summary = tr("通常のホームタイルに通知件数を表示します。通知ライブタイルの内容と件数には影響しません", "Shows notification counts on regular Home tiles. Live notification tiles are not affected"),
                    value = if (showNotificationBadges) tr("オン", "On") else tr("オフ", "Off"),
                    selected = showNotificationBadges,
                    isToggle = true,
                    onValueChange = ::setNotificationBadgesVisible,
                ),
                SettingsRow(
                    title = tr("通知へのアクセス", "Notification access"),
                    summary = tr("通知ライブタイルにアプリの通知を表示します", "Shows app notifications on live notification tiles"),
                    value = if (notificationAccessGranted) tr("許可済み", "Allowed") else tr("未許可", "Not allowed"),
                    selected = notificationAccessGranted,
                    onClick = {
                        openSystemSettings(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                            tr("通知へのアクセス設定", "Notification access settings"),
                        )
                    },
                ),
            ),
        ),
    )
    val displayedSections = if (currentTheme == LauncherTheme.GLASS) {
        sections + SettingsSection(
            title = tr("ガラス", "Glass"),
            content = { GlassThemeSettings() },
        )
    } else {
        sections
    }
    val settingsTrailingPadding = WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .asPaddingValues()
        .calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .zIndex(1f)
            .semantics {
                contentDescription = tr("設定画面", "Settings")
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        // Keep taps outside the content from reaching the launcher underneath. The shield is a
        // sibling behind the settings column, so the column's own controls and scroll gestures
        // remain interactive while the rest of the full-screen surface is inert.
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                },
        )
        Scaffold(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = tr("設定", "Settings"),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.semantics {
                                contentDescription = tr("ランチャーに戻る", "Back to launcher")
                                role = Role.Button
                            },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    windowInsets = WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 12.dp,
                        top = 8.dp,
                        end = 12.dp,
                        bottom = 8.dp + settingsTrailingPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                displayedSections.forEach { section ->
                    SettingsSectionContent(section)
                }
            }
        }
    }
    if (showThemeRotationIntervalDialog) {
        AlertDialog(
            onDismissRequest = { showThemeRotationIntervalDialog = false },
            title = { Text(tr("テーマの切替間隔", "Theme change interval")) },
            text = {
                Column {
                    ThemeRotationInterval.values().forEach { interval ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = themeRotation.intervalMillis == interval.millis,
                                    role = Role.RadioButton,
                                    onClick = {
                                        showThemeRotationIntervalDialog = false
                                        saveThemeRotation(themeRotation.copy(intervalMillis = interval.millis))
                                    },
                                )
                                .semantics(mergeDescendants = true) {
                                    stateDescription = if (themeRotation.intervalMillis == interval.millis) {
                                        tr("選択中", "Selected")
                                    } else {
                                        tr("選択可能", "Available")
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = themeRotation.intervalMillis == interval.millis,
                                onClick = null,
                            )
                            Text(
                                text = interval.label,
                                modifier = Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeRotationIntervalDialog = false }) {
                    Text(tr("閉じる", "Close"))
                }
            },
        )
    }
}

@Composable
private fun SettingsSectionContent(section: SettingsSection) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = section.title,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            val content = section.content
            if (content != null) {
                content()
            } else {
                Column {
                    section.rows.forEachIndexed { index, row ->
                        SettingsRowContent(row)
                        if (index < section.rows.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRowContent(row: SettingsRow) {
    val action = row.onClick
    val onValueChange = row.onValueChange
    val interactiveModifier = when {
        row.isToggle && onValueChange != null -> {
            Modifier
                .toggleable(
                    value = row.selected,
                    role = Role.Switch,
                    onValueChange = onValueChange,
                )
                // toggleable owns the switch role, state, and accessibility click action. This
                // outer merged node only supplies the stable label/state wording and deliberately
                // does not add a second onClick action.
                .semantics(mergeDescendants = true) {
                    stateDescription = if (row.selected) tr("オン", "On") else tr("オフ", "Off")
                }
        }

        row.isThemeOption && action != null -> {
            Modifier
                .selectable(
                    selected = row.selected,
                    role = Role.RadioButton,
                    onClick = action,
                )
                .semantics(mergeDescendants = true) {
                    stateDescription = if (row.selected) tr("選択中", "Selected") else tr("選択可能", "Available")
                }
        }

        action != null -> {
            Modifier
                .clickable(
                    role = Role.Button,
                    onClickLabel = row.title,
                    onClick = action,
                )
                .semantics(mergeDescendants = true) {}
        }

        else -> Modifier
    }
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(interactiveModifier),
        headlineContent = {
            Text(
                text = row.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = row.summary?.let { summary ->
            {
                Text(
                    text = summary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            when {
                row.isToggle -> {
                    Switch(
                        checked = row.selected,
                        onCheckedChange = null,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }

                row.isThemeOption -> {
                    RadioButton(
                        selected = row.selected,
                        onClick = null,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }

                row.value != null || action != null -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.value?.let { value ->
                            Text(
                                text = value,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (action != null) {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (row.isThemeOption && row.selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                androidx.compose.ui.graphics.Color.Transparent
            },
        ),
    )
}
