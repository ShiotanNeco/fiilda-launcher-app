package com.fiilda.launcher

import java.nio.charset.StandardCharsets
import java.util.Base64
import android.content.Context
import android.widget.ImageView
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView

/** Pure folder state helpers and the compact SharedPreferences codec. */
internal const val HomeFoldersStoragePrefix = "v1:"

private const val FolderRecordSeparator = ";"
private const val FolderFieldSeparator = "~"
private const val FolderMemberSeparator = ","

/** Returns true only while the pointer is over the forgiving central portion of a target tile. */
internal fun homeFolderDropHitZone(
    pointer: HomePointer,
    board: HomeItemBounds,
    placement: HomeGridPlacement,
    cellWidthPx: Float,
    gapPx: Float,
    centralFraction: Float = 0.58f,
): Boolean {
    if (cellWidthPx <= 0f || gapPx < 0f) return false
    val stride = cellWidthPx + gapPx
    val left = board.left + placement.column * stride
    val top = board.top + placement.row * stride
    val width = cellWidthPx * placement.columnSpan + gapPx * (placement.columnSpan - 1)
    val height = cellWidthPx * placement.rowSpan + gapPx * (placement.rowSpan - 1)
    val fraction = centralFraction.coerceIn(0.2f, 1f)
    val insetX = width * (1f - fraction) / 2f
    val insetY = height * (1f - fraction) / 2f
    return pointer.x in (left + insetX)..(left + width - insetX) &&
        pointer.y in (top + insetY)..(top + height - insetY)
}

internal enum class HomeFolderDropMode {
    /** The pointer is approaching a target tile; keep its geometry stable for a possible merge. */
    HOLD,

    /** The pointer is in the target's forgiving center region; create/add the folder. */
    MERGE,

    /** The pointer left the active target, so normal reorder may follow the current cell. */
    REORDER,
}

internal data class HomeFolderDropDecision(
    val mode: HomeFolderDropMode,
    val targetId: String? = null,
)

/**
 * Classifies one folder gesture from the same target geometry used by move and drop.
 *
 * A target remains active while the pointer is inside its full tile bounds. This keeps dense
 * reflow from moving the target out from under an approaching finger. The outer band is therefore
 * a HOLD during motion, becomes MERGE in the center, and becomes REORDER only after the pointer
 * exits the target. A release in the outer band is classified as HOLD here; the drop handler then
 * computes the current-cell reorder candidate before committing it.
 */
internal fun homeFolderDropDecision(
    pointer: HomePointer,
    board: HomeItemBounds?,
    activeTargetPlacement: HomeGridPlacement?,
    activeTargetId: String?,
    hoveredTargetPlacement: HomeGridPlacement?,
    hoveredTargetId: String?,
    draggedId: String,
    cellWidthPx: Float,
    gapPx: Float,
): HomeFolderDropDecision {
    if (board == null) return HomeFolderDropDecision(HomeFolderDropMode.REORDER)

    fun validTarget(id: String?, placement: HomeGridPlacement?): Pair<String, HomeGridPlacement>? {
        val validId = id?.takeUnless { it.isBlank() || it == draggedId } ?: return null
        return placement?.let { validId to it }
    }

    // Prefer the active identity while it still owns the pointer's tile. If it has moved or the
    // pointer left it, refresh from the current plan so a later target can be selected safely.
    val active = validTarget(activeTargetId, activeTargetPlacement)
        ?.takeIf { (_, placement) ->
            homeFolderDropHitZone(
                pointer = pointer,
                board = board,
                placement = placement,
                cellWidthPx = cellWidthPx,
                gapPx = gapPx,
                centralFraction = 1f,
            )
        }
    val hovered = validTarget(hoveredTargetId, hoveredTargetPlacement)
    val candidate = active ?: hovered ?: return HomeFolderDropDecision(HomeFolderDropMode.REORDER)
    val (targetId, placement) = candidate
    return HomeFolderDropDecision(
        mode = if (
            homeFolderDropHitZone(
                pointer = pointer,
                board = board,
                placement = placement,
                cellWidthPx = cellWidthPx,
                gapPx = gapPx,
            )
        ) {
            HomeFolderDropMode.MERGE
        } else {
            HomeFolderDropMode.HOLD
        },
        targetId = targetId,
    )
}

internal fun canonicalizeHomeFolders(folders: List<HomeFolder>): List<HomeFolder> {
    val usedIds = mutableSetOf<String>()
    val usedMembers = mutableSetOf<String>()
    return folders.mapNotNull { folder ->
        val id = folder.id.trim()
        if (!id.startsWith(HomeFolderIdPrefix) || id in usedIds) {
            return@mapNotNull null
        }
        usedIds += id
        val members = folder.memberIds
            .map(String::trim)
            .filter { it.isNotBlank() && usedMembers.add(it) }
            .distinct()
        if (members.isEmpty()) {
            return@mapNotNull null
        }
        HomeFolder(
            id = id,
            name = sanitizeHomeFolderName(folder.name),
            memberIds = members,
            size = folder.size,
        )
    }
}

/** Retains only currently resolved home apps and gives each app one folder owner. */
internal fun normalizeHomeFolders(
    stored: List<HomeFolder>?,
    favoriteIds: Set<String>,
): List<HomeFolder> {
    if (stored == null) return emptyList()
    return canonicalizeHomeFolders(stored).mapNotNull { folder ->
        val members = folder.memberIds.filter { it in favoriteIds }
        members.takeIf { it.isNotEmpty() }?.let { folder.copy(memberIds = it) }
    }
}

internal fun serializeHomeFolders(folders: List<HomeFolder>): String = HomeFoldersStoragePrefix +
    canonicalizeHomeFolders(folders).joinToString(FolderRecordSeparator) { folder ->
        listOf(
            encodeFolderText(folder.id),
            encodeFolderText(folder.name),
            folder.size.name,
            folder.memberIds.joinToString(FolderMemberSeparator, transform = ::encodeFolderText),
        ).joinToString(FolderFieldSeparator)
    }

/** Returns null for missing/version-mismatched/truncated or malformed folder storage. */
internal fun parseHomeFolders(raw: String?): List<HomeFolder>? {
    if (raw == null || !raw.startsWith(HomeFoldersStoragePrefix)) return null
    val payload = raw.removePrefix(HomeFoldersStoragePrefix)
    if (payload.isEmpty()) return emptyList()
    return payload.split(FolderRecordSeparator).map { record ->
        val fields = record.split(FolderFieldSeparator)
        if (fields.size != 4) return null
        val id = decodeFolderText(fields[0]) ?: return null
        val name = decodeFolderText(fields[1]) ?: return null
        val size = runCatching { HomeFolderSize.valueOf(fields[2]) }.getOrNull() ?: return null
        val members = if (fields[3].isEmpty()) {
            emptyList()
        } else {
            fields[3].split(FolderMemberSeparator).map { decodeFolderText(it) ?: return null }
        }
        HomeFolder(id = id, name = name, memberIds = members, size = size)
    }.let(::canonicalizeHomeFolders)
}

private fun encodeFolderText(value: String): String = Base64.getUrlEncoder()
    .withoutPadding()
    .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decodeFolderText(encoded: String): String? = runCatching {
    val value = Base64.getUrlDecoder().decode(encoded).toString(StandardCharsets.UTF_8)
    value.takeIf {
        it.isNotBlank() && encodeFolderText(it) == encoded &&
            '\n' !in it && '\r' !in it
    }
}.getOrNull()

internal fun newHomeFolderId(usedIds: Set<String>): String {
    var index = 1
    while ("$HomeFolderIdPrefix$index" in usedIds) index++
    return "$HomeFolderIdPrefix$index"
}

internal fun addAppToHomeFolder(
    folders: List<HomeFolder>,
    folderId: String,
    appId: String,
): List<HomeFolder>? {
    if (appId.isBlank()) return null
    val canonical = canonicalizeHomeFolders(folders)
    val target = canonical.firstOrNull { it.id == folderId } ?: return null
    if (appId in target.memberIds) return null
    return canonical.map { folder ->
        if (folder.id == folderId) folder.copy(memberIds = folder.memberIds + appId) else folder
    }
}

/** Keeps folder members in the durable favorite list even though they leave the visible order. */
internal fun favoriteIdsForHomeLayout(
    layout: HomeLayout,
    favoriteIds: List<String>,
): List<String> {
    val visible = layout.narrowOrder.filter { it in favoriteIds }
    return (visible + favoriteIds.filterNot { it in visible }).distinct()
}

internal data class HomeFolderDropTransition(
    val layout: HomeLayout,
    val folders: List<HomeFolder>,
)

/** Applies an app-on-app or app-on-folder drop. Folder-on-folder is intentionally unsupported. */
internal fun homeFolderDropTransition(
    layout: HomeLayout,
    folders: List<HomeFolder>,
    draggedAppId: String,
    targetId: String,
): HomeFolderDropTransition? {
    if (draggedAppId.isBlank() || draggedAppId == targetId) return null
    val canonicalLayout = canonicalizeHomeLayout(layout)
    val canonicalFolders = canonicalizeHomeFolders(folders)
    if (draggedAppId !in canonicalLayout.allIds || draggedAppId in canonicalFolders.map { it.id }) {
        return null
    }

    val targetFolder = canonicalFolders.firstOrNull { it.id == targetId }
    if (targetFolder != null) {
        val updatedFolders = addAppToHomeFolder(canonicalFolders, targetId, draggedAppId) ?: return null
        return HomeFolderDropTransition(
            layout = removeHomeItemFromLayout(canonicalLayout, draggedAppId),
            folders = updatedFolders,
        )
    }

    // The caller resolves targetId to a HomeItem.App before calling this helper. Keeping the
    // transition free of Android catalog objects makes it usable from JVM invariant tests.
    if (targetId !in canonicalLayout.allIds || targetId in canonicalFolders.map { it.id }) return null
    val folderId = newHomeFolderId(canonicalLayout.allIds + canonicalFolders.map { it.id })
    val ownerPage = canonicalLayout.pageOf(targetId)
    val folder = HomeFolder(
        id = folderId,
        memberIds = listOf(targetId, draggedAppId),
    )
    fun replaceInOrder(order: List<String>): List<String> {
        if (targetId !in order) return order
        return order.flatMap { id ->
            when (id) {
                targetId -> listOf(folderId)
                draggedAppId -> emptyList()
                else -> listOf(id)
            }
        }
    }
    val updatedLayout = canonicalizeHomeLayout(
        canonicalLayout.copy(
            order = replaceInOrder(canonicalLayout.order),
            narrowOrder = replaceInOrder(canonicalLayout.narrowOrder),
            narrowPageById = canonicalLayout.narrowPageById - targetId - draggedAppId +
                (folderId to ownerPage),
        ),
    )
    return HomeFolderDropTransition(
        layout = updatedLayout,
        folders = canonicalFolders + folder,
    )
}

internal data class HomeFolderMemberRemoval(
    val layout: HomeLayout,
    val folders: List<HomeFolder>,
)

/**
 * Restores one folder member as a direct home app at the folder's former slot and page. The
 * folder remains in place when it still has members; an empty folder is removed and the member
 * replaces its slot. This keeps wide and narrow orders independent while preserving ownership.
 */
internal fun returnAppFromHomeFolder(
    layout: HomeLayout,
    folders: List<HomeFolder>,
    appId: String,
): HomeFolderDropTransition? {
    val canonicalLayout = canonicalizeHomeLayout(layout)
    val canonicalFolders = canonicalizeHomeFolders(folders)
    val folder = canonicalFolders.firstOrNull { appId in it.memberIds } ?: return null
    val folderPage = canonicalLayout.pageOf(folder.id)
    val removal = removeAppFromHomeFolders(canonicalLayout, canonicalFolders, appId)
    val isEmpty = folder.memberIds.size == 1

    fun restore(order: List<String>): List<String> {
        val index = order.indexOf(folder.id)
        if (index < 0) return (order + appId).distinct()
        return order.toMutableList().apply {
            if (isEmpty) {
                set(index, appId)
            } else {
                add(index, appId)
            }
        }.distinct()
    }

    val restoredLayout = canonicalizeHomeLayout(
        removal.layout.copy(
            order = restore(canonicalLayout.order),
            narrowOrder = restore(canonicalLayout.narrowOrder),
            narrowPageById = removal.layout.narrowPageById + (appId to folderPage),
        ),
    )
    return HomeFolderDropTransition(
        layout = restoredLayout,
        folders = removal.folders,
    )
}

/** Returns a folder with the requested member order, rejecting unknown or duplicate IDs. */
internal fun reorderHomeFolderMembers(
    folders: List<HomeFolder>,
    folderId: String,
    memberIds: List<String>,
): List<HomeFolder>? {
    val canonical = canonicalizeHomeFolders(folders)
    val folder = canonical.firstOrNull { it.id == folderId } ?: return null
    // Validate the caller's complete order before any normalization. In particular, an extra
    // unknown ID must not be hidden by filtering when the list happens to have the same size.
    if (memberIds.size != folder.memberIds.size || memberIds.toSet() != folder.memberIds.toSet()) {
        return null
    }
    return canonical.map { current ->
        if (current.id == folderId) current.copy(memberIds = memberIds) else current
    }
}

/** Removes an app from a folder and removes an empty folder from both presentation orders. */
internal fun removeAppFromHomeFolders(
    layout: HomeLayout,
    folders: List<HomeFolder>,
    appId: String,
): HomeFolderMemberRemoval {
    val canonicalLayout = canonicalizeHomeLayout(layout)
    val canonicalFolders = canonicalizeHomeFolders(folders)
    val updatedFolders = canonicalFolders.mapNotNull { folder ->
        val members = folder.memberIds.filterNot { it == appId }
        folder.takeIf { members.isNotEmpty() }?.copy(memberIds = members)
    }
    val removedFolderIds = canonicalFolders
        .filter { it.memberIds.size == 1 && it.memberIds.firstOrNull() == appId }
        .map { it.id }
        .toSet()
    val updatedLayout = removedFolderIds.fold(canonicalLayout) { current, folderId ->
        removeHomeItemFromLayout(current, folderId)
    }
    return HomeFolderMemberRemoval(updatedLayout, updatedFolders)
}

internal fun renameHomeFolder(
    folders: List<HomeFolder>,
    folderId: String,
    name: String,
): List<HomeFolder>? {
    val canonical = canonicalizeHomeFolders(folders)
    if (canonical.none { it.id == folderId }) return null
    val cleanName = sanitizeHomeFolderName(name)
    return canonical.map { folder ->
        if (folder.id == folderId) folder.copy(name = cleanName) else folder
    }
}

private fun sanitizeHomeFolderName(name: String): String = name
    .map { character -> if (character.isWhitespace()) ' ' else character }
    .joinToString(separator = "")
    .replace(Regex(" +"), " ")
    .trim()
    .take(48)
    .ifBlank { tr("フォルダ", "Folder") }

internal fun resizeHomeFolder(
    folders: List<HomeFolder>,
    folderId: String,
    size: HomeFolderSize,
): List<HomeFolder>? {
    val canonical = canonicalizeHomeFolders(folders)
    if (canonical.none { it.id == folderId }) return null
    return canonical.map { folder ->
        if (folder.id == folderId) folder.copy(size = size) else folder
    }
}

/** Dissolving restores members at the folder's slot and preserves their order/ownership. */
internal fun dissolveHomeFolder(
    layout: HomeLayout,
    folders: List<HomeFolder>,
    folderId: String,
): HomeFolderDropTransition? {
    val canonicalLayout = canonicalizeHomeLayout(layout)
    val folder = canonicalizeHomeFolders(folders).firstOrNull { it.id == folderId } ?: return null
    fun expand(order: List<String>): List<String> {
        val index = order.indexOf(folderId)
        if (index < 0) return order
        return order.toMutableList().apply {
            removeAt(index)
            addAll(index, folder.memberIds)
        }
    }
    val owner = canonicalLayout.pageOf(folderId)
    val expanded = canonicalizeHomeLayout(
        canonicalLayout.copy(
            order = expand(canonicalLayout.order),
            narrowOrder = expand(canonicalLayout.narrowOrder),
            narrowPageById = (canonicalLayout.narrowPageById - folderId) +
                folder.memberIds.associateWith { owner },
        ),
    )
    return HomeFolderDropTransition(
        layout = expanded,
        folders = canonicalizeHomeFolders(folders.filterNot { it.id == folderId }),
    )
}

@Composable
internal fun FolderAppIcon(app: LaunchableApp, size: Dp) {
    AndroidView(
        factory = { context: Context ->
            DynamicIconImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                adjustViewBounds = true
            }
        },
        update = { view -> view.bindApp(app) },
        modifier = Modifier.size(size),
    )
}
