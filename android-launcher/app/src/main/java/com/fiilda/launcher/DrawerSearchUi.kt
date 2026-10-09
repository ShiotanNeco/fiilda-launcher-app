package com.fiilda.launcher

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Launches the two platform pickers requested by the search controller. The controller owns the
 * pending request and durable targets; this composable only bridges Activity Result callbacks.
 */
@Composable
internal fun DrawerSearchPickerEffects(
    controller: DrawerSearchController,
    allowWhenInactive: Boolean = false,
) {
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        controller.onFolderPicked(uri)
    }
    val documentsPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        controller.onDocumentsPicked(uris)
    }
    val pending = controller.state.pendingPicker
    LaunchedEffect(pending?.requestId, controller.state.active, allowWhenInactive) {
        val request = pending ?: return@LaunchedEffect
        if (!controller.state.active && !allowWhenInactive) return@LaunchedEffect
        when (request.kind) {
            DrawerSearchPickerKind.FOLDER -> folderPicker.launch(null)
            DrawerSearchPickerKind.DOCUMENTS -> documentsPicker.launch(arrayOf("*/*"))
        }
        controller.consumePickerRequest(request.requestId)
    }
}

/** Search results and target controls rendered inside the drawer's one vertical lazy surface. */
@Composable
internal fun DrawerSearchAppsHeading(
    appCount: Int,
    expanded: Boolean,
    canExpand: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = tr("アプリ", "Apps"),
            color = FiiLDAInk,
            fontSize = 20.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.weight(1f))
        Text(text = tr("${appCount}件", "${appCount}"), color = FiiLDAMuted, fontSize = 9.sp)
        if (canExpand) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = FiiLDAMuted,
                modifier = Modifier.padding(start = 5.dp).size(20.dp),
            )
        }
    }
}

@Composable
internal fun DrawerSearchExpansionAction(
    expanded: Boolean,
    onClick: () -> Unit,
) {
    SearchTextAction(
        label = if (expanded) tr("折りたたむ", "Collapse") else tr("すべて表示", "Show all"),
        accessibilityLabel = if (expanded) tr("アプリを折りたたむ", "Collapse apps") else tr("アプリをすべて表示", "Show all apps"),
        icon = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
        onClick = onClick,
    )
}

@Composable
internal fun DrawerSearchExternalSection(
    onOpen: (ExternalSearchTarget) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ModeHeading(kicker = tr("外部検索", "Web search"), title = tr("Web・地図・動画", "Web, maps, and videos"), count = tr("3件", "3"))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ExternalSearchButton(
                modifier = Modifier.weight(1f),
                label = "Google",
                accessibilityLabel = tr("Googleで検索", "Search with Google"),
                icon = Icons.Filled.Search,
                onClick = { onOpen(ExternalSearchTarget.GOOGLE) },
            )
            ExternalSearchButton(
                modifier = Modifier.weight(1f),
                label = tr("Googleマップ", "Google Maps"),
                accessibilityLabel = tr("Googleマップで検索", "Search with Google Maps"),
                icon = Icons.Filled.Map,
                onClick = { onOpen(ExternalSearchTarget.MAPS) },
            )
            ExternalSearchButton(
                modifier = Modifier.weight(1f),
                label = "YouTube",
                accessibilityLabel = tr("YouTubeで検索", "Search with YouTube"),
                icon = Icons.Filled.OndemandVideo,
                onClick = { onOpen(ExternalSearchTarget.YOUTUBE) },
            )
        }
    }
}

@Composable
private fun ExternalSearchButton(
    modifier: Modifier,
    label: String,
    accessibilityLabel: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .heightIn(min = 58.dp)
            .then(
                if (LocalLauncherGlass.current.enabled) {
                    Modifier.launcherGlassSearchControl(fallbackColor = FiiLDAAccentSurface)
                } else {
                    Modifier.launcherShapedSurface(
                        LauncherSearchControlShape,
                        FiiLDALineStrong,
                        FiiLDAAccentSurface,
                    )
                },
            )
            .clickable(role = Role.Button, onClickLabel = accessibilityLabel, onClick = onClick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = accessibilityLabel
                onClick(label = accessibilityLabel) {
                    onClick()
                    true
                }
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier.launcherGlassContributor(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = FiiLDACyan, modifier = Modifier.size(22.dp))
            Text(
                text = label,
                color = FiiLDAInk,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun DrawerSearchDeviceSection(
    state: DrawerSearchUiState,
    controller: DrawerSearchController,
    contactsVisibleCount: Int,
    filesVisibleCount: Int,
    onShowMoreContacts: () -> Unit,
    onCollapseContacts: () -> Unit,
    onShowMoreFiles: () -> Unit,
    onCollapseFiles: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ModeHeading(
            kicker = tr("端末内", "On device"),
            title = tr("連絡先・ファイル", "Contacts and files"),
            count = tr("${state.contacts.size + state.files.size}件", "${state.contacts.size + state.files.size}"),
        )
        DeviceSearchResultGroup(
            title = tr("連絡先", "Contacts"),
            icon = Icons.Filled.Person,
            results = state.contacts,
            status = state.contactsStatus,
            sourceMessage = state.sourceStates
                .firstOrNull { it.source == DeviceSearchSource.CONTACTS }
                ?.displayStatusMessageOrNull(),
            visibleCount = contactsVisibleCount,
            moreAccessibilityLabel = tr("連絡先をもっと表示", "Show more contacts"),
            onShowMore = onShowMoreContacts,
            onCollapse = onCollapseContacts,
            onOpen = controller::openResult,
        )
        Spacer(Modifier.height(5.dp))
        DeviceSearchResultGroup(
            title = tr("ファイル", "Files"),
            icon = Icons.Filled.InsertDriveFile,
            results = state.files,
            status = aggregateDrawerFileSearchStatus(state),
            sourceMessage = aggregateDrawerFileSearchMessage(state),
            visibleCount = filesVisibleCount,
            moreAccessibilityLabel = tr("ファイルをもっと表示", "Show more files"),
            onShowMore = onShowMoreFiles,
            onCollapse = onCollapseFiles,
            onOpen = controller::openResult,
        )
    }
}

@Composable
private fun DeviceSearchResultGroup(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    results: List<SearchResult>,
    status: SearchSourceStatus,
    sourceMessage: String?,
    visibleCount: Int,
    moreAccessibilityLabel: String,
    onShowMore: () -> Unit,
    onCollapse: () -> Unit,
    onOpen: (SearchResult) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = FiiLDACyan, modifier = Modifier.size(19.dp))
            Text(title, color = FiiLDAInk, fontSize = 16.sp, modifier = Modifier.padding(start = 6.dp))
            Spacer(Modifier.weight(1f))
            Text(tr("${results.size}件", "${results.size}"), color = FiiLDAMuted, fontSize = 9.sp)
        }
        SearchSourceStatusMessage(status = status, message = sourceMessage)
        if (results.isNotEmpty()) {
            val visibleResults = results.take(visibleCount.coerceAtLeast(5))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (LocalLauncherGlass.current.enabled) {
                            Modifier.launcherGlassSearchControl(fallbackColor = FiiLDASurface)
                        } else {
                            Modifier.launcherShapedSurface(
                                LauncherSearchControlShape,
                                FiiLDALine,
                                FiiLDASurface,
                            )
                        },
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .launcherGlassContributor(),
                ) {
                    visibleResults.forEach { result ->
                        SearchResultRow(
                            result = result,
                            icon = icon,
                            onClick = { onOpen(result) },
                        )
                    }
                }
            }
            if (results.size > visibleResults.size) {
                SearchTextAction(
                    label = tr("もっと表示", "Show more"),
                    accessibilityLabel = moreAccessibilityLabel,
                    icon = Icons.Filled.ExpandMore,
                    onClick = onShowMore,
                )
            } else if (visibleCount > 5 && results.size > 5) {
                SearchTextAction(
                    label = tr("折りたたむ", "Collapse"),
                    accessibilityLabel = tr("${title}を折りたたむ", "Collapse ${title}"),
                    icon = Icons.Filled.ExpandLess,
                    onClick = onCollapse,
                )
            }
        } else if (status == SearchSourceStatus.READY) {
            Text(
                tr("一致する${title}はありません", "No matching ${title}"),
                color = FiiLDAMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun SearchResultRow(
    result: SearchResult,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClickLabel = tr("${result.label}を開く", "Open ${result.label}"), onClick = onClick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = buildResultDescription(result)
                onClick(label = tr("${result.label}を開く", "Open ${result.label}")) {
                    onClick()
                    true
                }
            }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = FiiLDACyan, modifier = Modifier.size(24.dp))
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                result.label,
                color = FiiLDAInk,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            result.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    color = FiiLDAMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun buildResultDescription(result: SearchResult): String = buildString {
    append(result.label)
    result.subtitle?.takeIf { it.isNotBlank() }?.let { append(tr("、$it", ", $it")) }
}

@Composable
internal fun DrawerSearchTargetManagement(
    state: DrawerSearchUiState,
    controller: DrawerSearchController,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(tr("端末内検索の対象", "On-device search sources"), color = FiiLDAMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
        state.sourceStates.forEach { source ->
            DrawerSearchSourceRow(source = source, controller = controller)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            SearchTextAction(
                modifier = Modifier.weight(1f),
                label = tr("フォルダを追加", "Add folder"),
                accessibilityLabel = tr("検索対象フォルダを追加", "Add a folder to search"),
                icon = Icons.Filled.Folder,
                onClick = controller::addFolder,
            )
            SearchTextAction(
                modifier = Modifier.weight(1f),
                label = tr("ファイルを追加", "Add file"),
                accessibilityLabel = tr("検索対象ファイルを追加", "Add a file to search"),
                icon = Icons.Filled.Add,
                onClick = controller::addDocuments,
            )
        }
        state.documents.forEach { target ->
            DrawerSearchDocumentRow(target = target, controller = controller)
        }
        if (shouldShowDocumentStatus(state)) {
            SearchSourceStatusMessage(
                status = state.documentsStatus,
                message = documentManagementStatusMessage(state),
            )
        }
        if (state.canContinueDocuments) {
            SearchTextAction(
                label = tr("検索を続ける", "Continue search"),
                accessibilityLabel = tr("文書の検索を続ける", "Continue searching documents"),
                icon = Icons.Filled.Refresh,
                onClick = controller::continueDocuments,
            )
        }
        SearchTextAction(
            label = tr("再読み込み", "Reload"),
            accessibilityLabel = tr("端末内検索を再読み込み", "Reload on-device search"),
            icon = Icons.Filled.Refresh,
            onClick = controller::refresh,
        )
    }
}

@Composable
private fun DrawerSearchSourceRow(
    source: DrawerSearchSourceState,
    controller: DrawerSearchController,
) {
    val icon = when (source.source) {
        DeviceSearchSource.CONTACTS -> Icons.Filled.Person
        DeviceSearchSource.AUDIO -> Icons.Filled.MusicNote
    }
    val statusLabel = source.displayStatusMessage()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .launcherGlassContributor(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Keep the source switch as its own semantics node. The access action is a sibling so
        // clearAndSetSemantics cannot hide the permission/settings affordance from TalkBack.
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clickable(
                    role = Role.Switch,
                    onClickLabel = if (source.enabled) tr("${source.label}の検索を無効化", "Turn off ${source.label} search") else tr("${source.label}の検索を有効化", "Turn on ${source.label} search"),
                    onClick = { controller.toggleSource(source.source, !source.enabled) },
                )
                .clearAndSetSemantics {
                    role = Role.Switch
                    selected = source.enabled
                    contentDescription = tr("${source.label}の検索", "${source.label} search")
                    stateDescription = statusLabel
                    onClick(label = if (source.enabled) tr("${source.label}の検索を無効化", "Turn off ${source.label} search") else tr("${source.label}の検索を有効化", "Turn on ${source.label} search")) {
                        controller.toggleSource(source.source, !source.enabled)
                        true
                    }
                }
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = if (source.enabled) FiiLDACyan else FiiLDAQuiet, modifier = Modifier.size(19.dp))
            Column(modifier = Modifier.weight(1f).padding(start = 7.dp)) {
                Text(source.label, color = FiiLDAInk, fontSize = 12.sp)
                Text(statusLabel, color = FiiLDAMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(
                imageVector = if (source.enabled) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = null,
                tint = if (source.enabled) FiiLDACyan else FiiLDAMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        if (source.status == SearchSourceStatus.DENIED ||
            source.status == SearchSourceStatus.PARTIAL
        ) {
            SearchIconAction(
                label = tr("${source.label}のアクセスを設定", "Set up ${source.label} access"),
                icon = Icons.Filled.Settings,
                onClick = { controller.manageAccess(source.source) },
            )
        } else if (source.status == SearchSourceStatus.ERROR) {
            SearchIconAction(
                label = tr("${source.label}を再試行", "Retry ${source.label}"),
                icon = Icons.Filled.Refresh,
                onClick = controller::refresh,
            )
        }
    }
}

@Composable
private fun DrawerSearchDocumentRow(
    target: SearchDocumentTarget,
    controller: DrawerSearchController,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .launcherGlassContributor(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (target.isTree) Icons.Filled.Folder else Icons.Filled.Description,
            contentDescription = null,
            tint = if (target.requiresReselection) FiiLDAMuted else FiiLDACyan,
            modifier = Modifier.size(19.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 7.dp)) {
            Text(target.label, color = FiiLDAInk, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (target.requiresReselection) tr("再選択が必要", "Select again") else if (target.isTree) tr("フォルダ", "Folder") else tr("ファイル", "File"),
                color = if (target.requiresReselection) FiiLDAQuiet else FiiLDAMuted,
                fontSize = 9.sp,
            )
        }
        SearchIconAction(
            label = tr("${target.label}を再選択", "Select ${target.label} again"),
            icon = Icons.Filled.Refresh,
            onClick = { controller.reselectDocumentTarget(target.id) },
        )
        SearchIconAction(
            label = tr("${target.label}を検索対象から削除", "Remove ${target.label} from search"),
            icon = Icons.Filled.DeleteOutline,
            onClick = { controller.removeDocumentTarget(target.id) },
        )
    }
}

@Composable
private fun SearchSourceStatusMessage(status: SearchSourceStatus, message: String?) {
    val text = message?.takeIf { it.isNotBlank() } ?: when (status) {
        SearchSourceStatus.DISABLED -> tr("無効", "Off")
        SearchSourceStatus.LOADING -> tr("検索中…", "Searching…")
        SearchSourceStatus.READY -> tr("検索可能", "Ready")
        SearchSourceStatus.NO_RESULTS -> tr("一致なし", "No matches")
        SearchSourceStatus.DENIED -> tr("アクセスが許可されていません", "Access not allowed")
        SearchSourceStatus.PARTIAL -> tr("一部のみ許可されています", "Partially allowed")
        SearchSourceStatus.ERROR -> tr("読み込めませんでした", "Couldn't load")
    }
    if (status != SearchSourceStatus.READY || message != null) {
        Text(text, color = if (status == SearchSourceStatus.ERROR || status == SearchSourceStatus.DENIED) FiiLDAQuiet else FiiLDAMuted, fontSize = 10.sp, modifier = Modifier.padding(vertical = 2.dp))
    }
}

private fun DrawerSearchSourceState.displayStatusMessage(): String =
    statusMessage?.takeIf { it.isNotBlank() }
        ?: errorMessage?.takeIf { it.isNotBlank() }
        ?: when (status) {
            SearchSourceStatus.DISABLED -> tr("オフ", "Off")
            SearchSourceStatus.LOADING -> tr("検索中…", "Searching…")
            SearchSourceStatus.READY -> tr("検索可能", "Ready")
            SearchSourceStatus.NO_RESULTS -> tr("一致なし", "No matches")
            SearchSourceStatus.DENIED -> tr("アクセスが必要", "Needs access")
            SearchSourceStatus.PARTIAL -> tr("一部のみ許可", "Partial")
            SearchSourceStatus.ERROR -> tr("エラー", "Error")
        }

private fun DrawerSearchSourceState.displayStatusMessageOrNull(): String? =
    displayStatusMessage().takeUnless {
        status == SearchSourceStatus.READY && statusMessage == null && errorMessage == null
    }

private fun shouldShowDocumentStatus(state: DrawerSearchUiState): Boolean =
    state.documents.isNotEmpty() || state.documentsStatus in setOf(
        SearchSourceStatus.LOADING,
        SearchSourceStatus.READY,
        SearchSourceStatus.PARTIAL,
        SearchSourceStatus.ERROR,
    )

private fun fileSearchStatuses(state: DrawerSearchUiState): List<SearchSourceStatus> {
    val sourceStatuses = state.sourceStates
        .filter { it.source == DeviceSearchSource.AUDIO }
        .map { it.status }
    // A persisted SAF target is a file-search source too. The default NO_RESULTS state with no
    // targets means that the document source is not configured yet, so it should not make the
    // whole file section look enabled.
    val includeDocuments = shouldShowDocumentStatus(state)
    return if (includeDocuments) sourceStatuses + state.documentsStatus else sourceStatuses
}

internal fun aggregateDrawerFileSearchStatus(state: DrawerSearchUiState): SearchSourceStatus {
    val statuses = fileSearchStatuses(state)
    val hasLoading = statuses.any { it == SearchSourceStatus.LOADING }
    val hasError = statuses.any { it == SearchSourceStatus.ERROR }
    val hasUsableSource = state.files.isNotEmpty() || statuses.any {
        it == SearchSourceStatus.READY ||
            it == SearchSourceStatus.PARTIAL ||
            it == SearchSourceStatus.LOADING
    }
    return when {
        hasError && hasUsableSource -> SearchSourceStatus.PARTIAL
        hasError -> SearchSourceStatus.ERROR
        hasLoading -> SearchSourceStatus.LOADING
        statuses.any { it == SearchSourceStatus.PARTIAL } -> SearchSourceStatus.PARTIAL
        state.files.isNotEmpty() -> SearchSourceStatus.READY
        statuses.any { it == SearchSourceStatus.READY } -> SearchSourceStatus.NO_RESULTS
        statuses.any { it == SearchSourceStatus.DENIED } && statuses.none { it == SearchSourceStatus.READY } -> SearchSourceStatus.DENIED
        statuses.all { it == SearchSourceStatus.DISABLED } -> SearchSourceStatus.DISABLED
        else -> SearchSourceStatus.NO_RESULTS
    }
}

internal fun aggregateDrawerFileSearchMessage(state: DrawerSearchUiState): String? {
    val hasResults = state.files.isNotEmpty()
    val sourceMessages = state.sourceStates
        .filter { it.source == DeviceSearchSource.AUDIO }
        .mapNotNull { source ->
            source.displayStatusMessage()
                .takeIf { it !in setOf(tr("オフ", "Off"), tr("検索可能", "Ready")) }
                // A source can have no matches while another source supplies rows. Keep the
                // useful limitation/error text, but do not claim that the whole file search is
                // empty below a successful result.
                ?.takeUnless { hasResults && (source.status == SearchSourceStatus.NO_RESULTS || isNoResultsMessage(it)) }
        }
    val documentMessage = if (shouldShowDocumentStatus(state)) {
        (state.documentsStatusMessage ?: state.documentsErrorMessage)
            ?.takeIf { it.isNotBlank() }
            ?.takeUnless {
                hasResults &&
                    (state.documentsStatus == SearchSourceStatus.NO_RESULTS || isNoResultsMessage(it))
            }
    } else {
        null
    }
    return (sourceMessages + documentMessage?.let { listOf(it) }.orEmpty())
        .distinct()
        .joinToString(tr("・", ", "))
        .takeIf { it.isNotBlank() }
}

private fun documentManagementStatusMessage(state: DrawerSearchUiState): String? {
    val message = state.documentsStatusMessage ?: state.documentsErrorMessage
    return if (state.documentsStatus == SearchSourceStatus.NO_RESULTS &&
        (message == null || isNoResultsMessage(message))
    ) {
        tr("選択したフォルダ・ファイルに一致する項目はありません", "No matches in the selected folders and files")
    } else {
        message
    }
}

private fun isNoResultsMessage(message: String): Boolean {
    val trimmed = message.trim()
    // Matches the "no results" labels produced above in either UI language.
    return trimmed == "一致なし" ||
        trimmed == "一致する項目はありません" ||
        (trimmed.startsWith("一致する") && trimmed.endsWith("はありません")) ||
        trimmed.startsWith("No match", ignoreCase = true)
}

@Composable
private fun SearchTextAction(
    label: String,
    accessibilityLabel: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = accessibilityLabel, onClick = onClick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = accessibilityLabel
                onClick(label = accessibilityLabel) {
                    onClick()
                    true
                }
            }
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .launcherGlassContributor(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = FiiLDACyan, modifier = Modifier.size(19.dp))
        Text(label, color = FiiLDAMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 5.dp))
    }
}

@Composable
private fun SearchIconAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .launcherGlassContributor()
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = FiiLDAMuted, modifier = Modifier.size(20.dp))
    }
}
