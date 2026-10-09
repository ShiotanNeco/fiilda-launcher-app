package com.fiilda.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Settings-specific search source management. The drawer keeps its compact bespoke controls;
 * this surface uses Material 3 components while delegating every mutation to the controller.
 */
@Composable
internal fun SettingsSearchTargetManagement(
    state: DrawerSearchUiState,
    controller: DrawerSearchController,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = tr("端末内検索の対象", "On-device search sources"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        state.sourceStates.forEachIndexed { index, source ->
            SettingsSearchSourceRow(source = source, controller = controller)
            if (index < state.sourceStates.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = controller::addFolder,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(painterResource(R.drawable.ms_folder), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tr("フォルダを追加", "Add folder"),
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                )
            }
            Button(
                onClick = controller::addDocuments,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) {
                Icon(painterResource(R.drawable.ms_add), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tr("ファイルを追加", "Add file"),
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                )
            }
        }
        state.documents.forEachIndexed { index, target ->
            SettingsSearchDocumentRow(target = target, controller = controller)
            if (index < state.documents.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        if (shouldShowSettingsDocumentStatus(state)) {
            SettingsSearchStatusMessage(
                status = state.documentsStatus,
                message = settingsDocumentManagementStatusMessage(state),
            )
        }
        if (state.canContinueDocuments) {
            TextButton(
                onClick = controller::continueDocuments,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(painterResource(R.drawable.ms_refresh), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("検索を続ける", "Continue search"), maxLines = 2, overflow = TextOverflow.Clip)
            }
        }
        OutlinedButton(
            onClick = controller::refresh,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(painterResource(R.drawable.ms_refresh), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr("再読み込み", "Reload"), maxLines = 2, overflow = TextOverflow.Clip)
        }
    }
}

@Composable
private fun SettingsSearchSourceRow(
    source: DrawerSearchSourceState,
    controller: DrawerSearchController,
) {
    val statusLabel = settingsSearchSourceStatusLabel(source)
    val sourceIcon = when (source.source) {
        DeviceSearchSource.CONTACTS -> R.drawable.ms_person
        DeviceSearchSource.AUDIO -> R.drawable.ms_music_note
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = source.enabled,
                    role = Role.Switch,
                    onValueChange = { enabled ->
                        controller.toggleSource(source.source, enabled)
                    },
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = tr("${source.label}の検索", "${source.label} search")
                    stateDescription = statusLabel
                },
            leadingContent = {
                Icon(
                    painter = painterResource(sourceIcon),
                    contentDescription = null,
                    tint = if (source.enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            headlineContent = {
                Text(
                    text = source.label,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(
                    text = statusLabel,
                    color = settingsSearchStatusColor(source.status),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Switch(
                    checked = source.enabled,
                    onCheckedChange = null,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        )
        when {
            source.status == SearchSourceStatus.DENIED ||
                source.status == SearchSourceStatus.PARTIAL -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 72.dp, end = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(
                        onClick = { controller.manageAccess(source.source) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics {
                                contentDescription = tr("${source.label}のアクセスを設定", "Set up ${source.label} access")
                            },
                    ) {
                        Icon(painterResource(R.drawable.ms_settings), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = source.actionLabel ?: tr("アクセスを管理", "Manage access"),
                            maxLines = 2,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }

            source.status == SearchSourceStatus.ERROR -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 72.dp, end = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = controller::refresh,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics {
                                contentDescription = tr("${source.label}を再試行", "Retry ${source.label}")
                            },
                    ) {
                        Icon(painterResource(R.drawable.ms_refresh), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(tr("再試行", "Retry"), maxLines = 2, overflow = TextOverflow.Clip)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSearchDocumentRow(
    target: SearchDocumentTarget,
    controller: DrawerSearchController,
) {
    val actionDescription = tr("${target.label}を再選択", "Select ${target.label} again")
    val removeDescription = tr("${target.label}を検索対象から削除", "Remove ${target.label} from search")
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        leadingContent = {
            Icon(
                painter = painterResource(if (target.isTree) R.drawable.ms_folder else R.drawable.ms_description),
                contentDescription = null,
                tint = if (target.requiresReselection) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        },
        headlineContent = {
            Text(
                text = target.label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = if (target.requiresReselection) {
                    tr("再選択が必要", "Select again")
                } else if (target.isTree) {
                    tr("フォルダ", "Folder")
                } else {
                    tr("ファイル", "File")
                },
                color = if (target.requiresReselection) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(
                    onClick = { controller.reselectDocumentTarget(target.id) },
                    modifier = Modifier.semantics {
                        contentDescription = actionDescription
                    },
                ) {
                    Icon(painterResource(R.drawable.ms_refresh), contentDescription = null)
                }
                IconButton(
                    onClick = { controller.removeDocumentTarget(target.id) },
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = removeDescription
                    },
                ) {
                    Icon(painterResource(R.drawable.ms_delete), contentDescription = null)
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}

@Composable
private fun SettingsSearchStatusMessage(
    status: SearchSourceStatus,
    message: String?,
) {
    if (status == SearchSourceStatus.READY && message == null) return
    val displayMessage = message?.takeIf { it.isNotBlank() } ?: when (status) {
        SearchSourceStatus.DISABLED -> tr("無効", "Off")
        SearchSourceStatus.LOADING -> tr("検索中…", "Searching…")
        SearchSourceStatus.READY -> tr("検索可能", "Ready")
        SearchSourceStatus.NO_RESULTS -> tr("一致なし", "No matches")
        SearchSourceStatus.DENIED -> tr("アクセスが許可されていません", "Access not allowed")
        SearchSourceStatus.PARTIAL -> tr("一部のみ許可されています", "Partially allowed")
        SearchSourceStatus.ERROR -> tr("読み込めませんでした", "Couldn't load")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (status == SearchSourceStatus.ERROR || status == SearchSourceStatus.DENIED) {
            Icon(
                painter = painterResource(R.drawable.ms_error),
                contentDescription = null,
                tint = settingsSearchStatusColor(status),
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = displayMessage,
            color = settingsSearchStatusColor(status),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun settingsSearchSourceStatusLabel(source: DrawerSearchSourceState): String =
    source.statusMessage?.takeIf { it.isNotBlank() }
        ?: source.errorMessage?.takeIf { it.isNotBlank() }
        ?: when (source.status) {
            SearchSourceStatus.DISABLED -> tr("オフ", "Off")
            SearchSourceStatus.LOADING -> tr("検索中…", "Searching…")
            SearchSourceStatus.READY -> tr("検索可能", "Ready")
            SearchSourceStatus.NO_RESULTS -> tr("一致なし", "No matches")
            SearchSourceStatus.DENIED -> tr("アクセスが必要", "Needs access")
            SearchSourceStatus.PARTIAL -> tr("一部のみ許可", "Partial")
            SearchSourceStatus.ERROR -> tr("エラー", "Error")
        }

@Composable
private fun settingsSearchStatusColor(status: SearchSourceStatus) = when (status) {
    SearchSourceStatus.DENIED,
    SearchSourceStatus.ERROR,
    -> MaterialTheme.colorScheme.error

    SearchSourceStatus.PARTIAL -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun shouldShowSettingsDocumentStatus(state: DrawerSearchUiState): Boolean =
    state.documents.isNotEmpty() || state.documentsStatus in setOf(
        SearchSourceStatus.LOADING,
        SearchSourceStatus.READY,
        SearchSourceStatus.PARTIAL,
        SearchSourceStatus.ERROR,
    )

private fun settingsDocumentManagementStatusMessage(state: DrawerSearchUiState): String? {
    val message = state.documentsStatusMessage ?: state.documentsErrorMessage
    return if (state.documentsStatus == SearchSourceStatus.NO_RESULTS &&
        (message == null || settingsIsNoResultsMessage(message))
    ) {
        tr("選択したフォルダ・ファイルに一致する項目はありません", "No matches in the selected folders and files")
    } else {
        message
    }
}

private fun settingsIsNoResultsMessage(message: String): Boolean {
    val trimmed = message.trim()
    // Matches the "no results" labels produced above in either UI language.
    return trimmed == "一致なし" ||
        trimmed == "一致する項目はありません" ||
        (trimmed.startsWith("一致する") && trimmed.endsWith("はありません")) ||
        trimmed.startsWith("No match", ignoreCase = true)
}
