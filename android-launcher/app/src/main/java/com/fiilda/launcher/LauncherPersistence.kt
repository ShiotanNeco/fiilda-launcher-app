package com.fiilda.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.selected
import java.util.Locale

/** SharedPreferences serialization, migration, and atomic home persistence. */
internal const val FavoritePreferencesName = "fiilda_preferences"
private const val FavoriteIdsKey = "favorite_ids"
private const val ExpandedFavoriteIdsKey = "expanded_favorite_ids"
private const val AppTileSizesKey = "app_tile_sizes"
private const val WideAppTileSizesKey = "wide_app_tile_sizes"
private const val AppTileContentModesKey = "app_tile_content_modes"
private const val WidgetSizeOverridesKey = "widget_size_overrides"
private const val WideWidgetSizeOverridesKey = "wide_widget_size_overrides"
internal const val HomeOrderKey = "home_order"
internal const val HomePagesKey = "home_pages"
internal const val HomeLayoutKey = "home_layout"
private const val WidgetDescriptorsKey = "external_widget_descriptors"
internal const val HomeFoldersKey = "home_folders"
private const val PhotoUriKey = "photo_widget_uri"
private const val PhotoUrisKey = "photo_widget_uris"
private const val PhotoVideoMutesKey = "photo_widget_video_mutes"

internal fun readFavoriteIds(context: Context): List<String>? {
    val raw = context
        .getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
        .getString(FavoriteIdsKey, null)
        ?: return null
    return raw.split("\n").filter { it.isNotBlank() }
}

private fun saveFavoriteIds(context: Context, ids: List<String>) {
    context
        .getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
        .edit()
        .putString(FavoriteIdsKey, ids.joinToString("\n"))
        .apply()
}

/**
 * Atomically persists a newly added favorite and its two-page ownership. Compose state must only
 * be updated after this commit returns true; otherwise a failed write cannot be rendered as a
 * successful add or be repaired into page 1 by a later reload.
 */
private fun persistFavoriteHomePageAddition(
    context: Context,
    addition: FavoriteHomePageAddition,
): Boolean {
    val preferences = context.getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
    val pageLayout = homeLayoutFromPages(addition.homePages)
    val priorLayout = parseHomeLayout(preferences.getString(HomeLayoutKey, null))
    val layout = if (priorLayout == null) {
        pageLayout
    } else {
        canonicalizeHomeLayout(
            HomeLayout(
                order = priorLayout.order.filter { it in pageLayout.allIds } +
                    pageLayout.order.filterNot { it in priorLayout.order },
                narrowOrder = priorLayout.narrowOrder.filter { it in pageLayout.allIds } +
                    pageLayout.narrowOrder.filterNot { it in priorLayout.narrowOrder },
                narrowPageById = priorLayout.narrowPageById + pageLayout.narrowPageById,
                pageCount = pageLayout.pageCount,
            ),
        )
    }
    return preferences
        .edit()
        .putString(FavoriteIdsKey, addition.favoriteIds.joinToString("\n"))
        .putString(HomePagesKey, serializeHomePages(addition.homePages))
        .putString(HomeLayoutKey, serializeHomeLayout(layout))
        // Keep the legacy single-page mirror in the same transaction as the v2 record.
        .putString(HomeOrderKey, addition.homePages[0].joinToString("\n"))
        .commit()
}

internal fun readAppTileSizes(context: Context): Map<String, AppTileSize> {
    val preferences = context.getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
    preferences.getString(AppTileSizesKey, null)?.let { return parseAppTileSizes(it) }

    // 以前の保存形式は「拡大したアプリID」の一覧だったため、2×2として引き継ぐ。
    return preferences.getString(ExpandedFavoriteIdsKey, null)
        ?.split("\n")
        ?.filter { it.isNotBlank() }
        ?.associateWith { AppTileSize.LARGE }
        .orEmpty()
}

/** Returns null when the independent wide key has not been written yet, triggering migration. */
internal fun readWideAppTileSizes(context: Context): Map<String, AppTileSize>? {
    val preferences = context.getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
    if (!preferences.contains(WideAppTileSizesKey)) return null
    return parseAppTileSizes(preferences.getString(WideAppTileSizesKey, null))
}

private fun saveAppTileSizes(context: Context, sizes: Map<String, AppTileSize>) {
    context
        .getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
        .edit()
        .putString(AppTileSizesKey, serializeAppTileSizes(sizes))
        .apply()
}

internal fun readAppTileContentModes(context: Context): Map<String, AppTileContentMode> {
    val preferences = context.getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
    return parseAppTileContentModes(preferences.getString(AppTileContentModesKey, null))
}

private fun saveAppTileContentModes(
    context: Context,
    modes: Map<String, AppTileContentMode>,
) {
    context
        .getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
        .edit()
        .putString(AppTileContentModesKey, serializeAppTileContentModes(modes))
        .apply()
}

internal fun readPhotoUris(context: Context): Map<String, String> {
    val preferences = context
        .getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
    val raw = preferences.getString(PhotoUrisKey, null)
    val parsed = raw
        ?.split("\n")
        ?.mapNotNull { line ->
            val fields = line.split("\t", limit = 2)
            if (fields.size != 2) return@mapNotNull null
            val id = fields[0].trim()
            val uri = fields[1].trim()
            if (!isPhotoWidgetHomeId(id) || uri.isBlank()) null else id to uri
        }
        ?.toMap()
        .orEmpty()
    if (parsed.isNotEmpty()) return parsed
    return preferences.getString(PhotoUriKey, null)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { mapOf(PhotoWidgetHomeId to it) }
        .orEmpty()
}

private fun serializePhotoUris(photoUris: Map<String, String>): String = photoUris
    .filter { (id, uri) -> id.isNotBlank() && uri.isNotBlank() }
    .toSortedMap()
    .entries
    .joinToString("\n") { (id, uri) -> "$id\t$uri" }

internal fun readPhotoVideoMutes(context: Context): Map<String, Boolean> {
    val raw = context
        .getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
        .getString(PhotoVideoMutesKey, null)
        ?: return emptyMap()
    return raw
        .split("\n")
        .mapNotNull { line ->
            val fields = line.split("\t", limit = 2)
            if (fields.size != 2) return@mapNotNull null
            val id = fields[0].trim()
            val muted = when (fields[1].trim().lowercase(Locale.ROOT)) {
                "1", "true" -> true
                "0", "false" -> false
                else -> null
            }
            if (!isPhotoWidgetHomeId(id) || muted == null) null else id to muted
        }
        .toMap()
}

private fun serializePhotoVideoMutes(mutes: Map<String, Boolean>): String = mutes
    .filterKeys(::isPhotoWidgetHomeId)
    .toSortedMap()
    .entries
    .joinToString("\n") { (id, muted) -> "$id\t${if (muted) 1 else 0}" }

internal fun savePhotoVideoMutes(context: Context, mutes: Map<String, Boolean>): Boolean = context
    .getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
    .edit()
    .putString(PhotoVideoMutesKey, serializePhotoVideoMutes(mutes))
    .commit()

/**
 * Reads the durable home snapshot used by a PHOTO callback that beats the asynchronous startup
 * load. Raw v2/legacy IDs are included as temporary allowed IDs so migration cannot drop an app
 * merely because the package query has not completed yet.
 */
private fun readPhotoSelectionHomeLayout(context: Context): HomeLayout {
    val storedLayout = parseHomeLayout(readHomeLayoutRaw(context))
    val storedPages = parseHomePages(readHomePagesRaw(context))
    val legacyOrder = readHomeOrder(context)
    // A valid v4/v3 layout is authoritative. When it exists, derive recovery IDs from that same
    // canonical snapshot rather than a stale v2/legacy mirror (which can otherwise resurrect a
    // widget or shortcut deliberately removed from the layout).
    val storedIds = if (storedLayout != null) {
        storedLayout.allIds.toList()
    } else if (storedPages != null) {
        storedPages.pages.flatten()
    } else {
        legacyOrder.orEmpty()
    }
    val storedFavorites = readFavoriteIds(context).orEmpty()
    val storedFolders = normalizeHomeFolders(
        stored = parseHomeFolders(readHomeFoldersRaw(context)),
        favoriteIds = (storedFavorites + storedIds).toSet(),
    )
    val externalWidgetIds = (
        readWidgetDescriptors(context).map { it.homeId } +
            readPinnedShortcutRecords(context).map { it.homeId }
        ).distinct()
    return normalizeHomeLayout(
        storedLayout = storedLayout,
        storedPages = storedPages,
        legacyOrder = legacyOrder,
        favoriteIds = (storedFavorites + storedIds).distinct(),
        externalWidgetIds = externalWidgetIds,
        homeFolders = storedFolders,
    )
}

internal data class PersistedPhotoSelection(
    val widgetId: String,
    val layout: HomeLayout,
    val photoUris: Map<String, String>,
    val photoVideoMutes: Map<String, Boolean>,
)

/**
 * Takes a durable read grant before committing the URI. For the first add, the canonical layout
 * and its page mirrors are written by this same editor, so a successful picker result cannot
 * leave a URI without its PHOTO home item (or vice versa). A failed grant/commit leaves the old
 * URI and grant untouched.
 */
internal fun persistPhotoSelection(
    context: Context,
    widgetId: String,
    uri: Uri,
    addToHome: Boolean,
    targetHomePage: Int,
    videoMuted: Boolean,
): PersistedPhotoSelection? = synchronized(LauncherUriPersistenceLock) {
    persistPhotoSelectionLocked(
        context = context,
        widgetId = widgetId,
        uri = uri,
        addToHome = addToHome,
        targetHomePage = targetHomePage,
        videoMuted = videoMuted,
    )
}

private fun persistPhotoSelectionLocked(
    context: Context,
    widgetId: String,
    uri: Uri,
    addToHome: Boolean,
    targetHomePage: Int,
    videoMuted: Boolean,
): PersistedPhotoSelection? {
    val readPermission = Intent.FLAG_GRANT_READ_URI_PERMISSION
    // Snapshot before taking the grant. A failed editor commit may only release a grant that this
    // operation newly acquired; a pre-existing grant can belong to another photo/search target.
    val grantStore = ContentResolverReadGrantStore(context)
    val grantSnapshot = snapshotPersistedReadGrants(grantStore)
    val permissionTaken = runCatching {
        context.contentResolver.takePersistableUriPermission(uri, readPermission)
        true
    }.getOrDefault(false)
    if (!permissionTaken) return null

    val preferences = context.getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
    val currentUris = readPhotoUris(context)
    val currentMutes = readPhotoVideoMutes(context)
    val layoutSelection = homeLayoutAfterPhotoSelection(
        layout = readPhotoSelectionHomeLayout(context),
        widgetId = widgetId,
        targetHomePage = targetHomePage,
        addToHome = addToHome,
        existingIds = currentUris.keys,
    )
    val currentFolders = normalizeHomeFolders(
        stored = parseHomeFolders(readHomeFoldersRaw(context)),
        favoriteIds = (
            readFavoriteIds(context).orEmpty() + layoutSelection.layout.allIds
            ).toSet(),
    )
    // The durable snapshot remains the source of truth for both add and replace. In particular,
    // a pre-load callback must never serialize an empty/partial Compose page snapshot over it.
    val committedLayout = layoutSelection.layout
    val committedWidgetId = layoutSelection.widgetId
    val previousUri = currentUris[committedWidgetId]
    val updatedUris = currentUris + (committedWidgetId to uri.toString())
    val updatedMutes = if (videoMuted) {
        currentMutes + (committedWidgetId to true)
    } else {
        currentMutes - committedWidgetId
    }
    val editor = preferences
        .edit()
        .putString(PhotoUrisKey, serializePhotoUris(updatedUris))
        .putString(PhotoVideoMutesKey, serializePhotoVideoMutes(updatedMutes))
        .remove(PhotoUriKey)
        .putString(HomePagesKey, serializeHomePages(committedLayout.toHomePages()))
        .putString(HomeLayoutKey, serializeHomeLayout(committedLayout))
        .putString(HomeFoldersKey, serializeHomeFolders(currentFolders))
        // Keep the legacy key synchronized so an older build can still see page 1.
        .putString(HomeOrderKey, committedLayout.toHomePages()[0].joinToString("\n"))
    val persisted = editor.commit()
    if (!persisted) {
        // If this is the same URI, its existing grant is still the one the current widget uses.
        // Releasing it on a failed no-op write would make a healthy restored widget unreadable.
        if (previousUri != uri.toString() && uri.toString() !in currentUris.values &&
            wasReadGrantNewlyAcquired(uri.toString(), grantSnapshot, grantStore)
        ) {
            releaseNewlyAcquiredReadGrantIfUnowned(
                context = context,
                uriString = uri.toString(),
                before = grantSnapshot,
                grantStore = grantStore,
            )
        }
        return null
    }
    val newlyAcquired = wasReadGrantNewlyAcquired(uri.toString(), grantSnapshot, grantStore)
    if (newlyAcquired) recordLauncherOwnedReadGrants(context, listOf(uri.toString()))
    if (!previousUri.isNullOrBlank() && previousUri != uri.toString()) {
        // The previous URI was represented by a photo target, including records created before
        // the ownership ledger existed. Mark it before the target is replaced so release remains
        // safe and can also retire any overlapping tree grant.
        recordLauncherOwnedReadGrants(context, listOf(previousUri))
    }
    if (!previousUri.isNullOrBlank() && previousUri != uri.toString() &&
        previousUri !in updatedUris.values
    ) {
        releasePhotoUriPermission(context, previousUri)
    }
    return PersistedPhotoSelection(
        widgetId = committedWidgetId,
        layout = canonicalizeHomeLayout(committedLayout),
        photoUris = updatedUris,
        photoVideoMutes = updatedMutes,
    )
}

internal data class PhotoRemovalPersistence(
    val committed: Boolean,
    val removedUri: String?,
)

/**
 * Removes all PHOTO-owned preferences in one commit. The caller updates Compose state after
 * [committed] is true; the ownership-aware grant release is completed inside the same transaction.
 */
internal fun removePhotoWidgetAtomically(
    context: Context,
    homePages: HomePages,
    layout: HomeLayout,
    widgetSizes: Map<String, WidgetSizeChoice>,
    wideWidgetSizes: Map<String, WidgetSizeChoice>,
    widgetId: String,
    folders: List<HomeFolder>? = null,
): PhotoRemovalPersistence = synchronized(LauncherUriPersistenceLock) {
    removePhotoWidgetAtomicallyLocked(
        context = context,
        homePages = homePages,
        layout = layout,
        widgetSizes = widgetSizes,
        wideWidgetSizes = wideWidgetSizes,
        widgetId = widgetId,
        folders = folders,
    )
}

private fun removePhotoWidgetAtomicallyLocked(
    context: Context,
    homePages: HomePages,
    layout: HomeLayout,
    widgetSizes: Map<String, WidgetSizeChoice>,
    wideWidgetSizes: Map<String, WidgetSizeChoice>,
    widgetId: String,
    folders: List<HomeFolder>?,
): PhotoRemovalPersistence {
    val preferences = context.getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
    val currentUris = readPhotoUris(context)
    val currentMutes = readPhotoVideoMutes(context)
    val previousUri = currentUris[widgetId]
    // Keep the durable v2 and v4 records defensive as well as the caller's in-memory state. A
    // stale owner page or a transition callback must never be able to leave an empty PHOTO tile in
    // the wide order after its URI has been removed.
    val persistedPages = removeHomeItemFromPages(homePages, widgetId)
    val persistedOrder = removeHomeItemFromLayout(layout, widgetId)
    val priorLayout = parseHomeLayout(preferences.getString(HomeLayoutKey, null))
    val persistedLayout = if (priorLayout == null) persistedOrder else canonicalizeHomeLayout(
        HomeLayout(
            order = persistedOrder.order,
            narrowOrder = persistedOrder.narrowOrder,
            narrowPageById = priorLayout.narrowPageById + persistedOrder.narrowPageById,
            pageCount = persistedOrder.pageCount,
        ),
    )
    val editor = preferences
        .edit()
        .putString(HomePagesKey, serializeHomePages(persistedPages))
        .putString(HomeLayoutKey, serializeHomeLayout(persistedLayout))
        .putString(HomeOrderKey, persistedPages[0].joinToString("\n"))
        .putString(PhotoUrisKey, serializePhotoUris(currentUris - widgetId))
        .putString(PhotoVideoMutesKey, serializePhotoVideoMutes(currentMutes - widgetId))
        .remove(PhotoUriKey)
        .putString(WidgetSizeOverridesKey, serializeWidgetSizeOverrides(widgetSizes))
        .putString(WideWidgetSizeOverridesKey, serializeWidgetSizeOverrides(wideWidgetSizes))
    folders?.let { editor.putString(HomeFoldersKey, serializeHomeFolders(it)) }
    val committed = editor.commit()
    if (committed && !previousUri.isNullOrBlank()) {
        recordLauncherOwnedReadGrants(context, listOf(previousUri))
        // Keep the preference commit, ledger update, and ownership-aware release in the same
        // process-wide transaction. The caller still receives removedUri for state updates; its
        // legacy follow-up release is an idempotent no-op.
        releaseLauncherUriPermissionIfUnowned(context, previousUri)
    }
    return PhotoRemovalPersistence(
        committed = committed,
        removedUri = previousUri.takeIf { committed },
    )
}

internal fun releasePhotoUriPermission(context: Context, uriString: String) {
    releaseLauncherUriPermissionIfUnowned(context, uriString)
}

internal fun readWidgetSizeOverrides(context: Context): Map<String, WidgetSizeChoice> {
    val preferences = context.getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
    return parseWidgetSizeOverrides(preferences.getString(WidgetSizeOverridesKey, null))
}

/** Returns null when the independent wide key has not been written yet, triggering migration. */
internal fun readWideWidgetSizeOverrides(context: Context): Map<String, WidgetSizeChoice>? {
    val preferences = context.getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
    if (!preferences.contains(WideWidgetSizeOverridesKey)) return null
    return parseWidgetSizeOverrides(preferences.getString(WideWidgetSizeOverridesKey, null))
}

private fun saveWidgetSizeOverrides(
    context: Context,
    overrides: Map<String, WidgetSizeChoice>,
    synchronous: Boolean = false,
): Boolean {
    val editor = context
        .getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
        .edit()
        .putString(WidgetSizeOverridesKey, serializeWidgetSizeOverrides(overrides))
    return if (synchronous) editor.commit() else {
        editor.apply()
        true
    }
}

internal fun readHomeOrder(context: Context): List<String>? {
    val raw = context
        .getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
        .getString(HomeOrderKey, null)
        ?: return null
    return raw.split("\n").filter { it.isNotBlank() }
}

internal fun readHomePagesRaw(context: Context): String? = context
    .getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
    .getString(HomePagesKey, null)

internal fun readHomeLayoutRaw(context: Context): String? = context
    .getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
    .getString(HomeLayoutKey, null)

internal fun readHomeFoldersRaw(context: Context): String? = context
    .getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
    .getString(HomeFoldersKey, null)

internal fun readHomeFolders(context: Context): List<HomeFolder> =
    parseHomeFolders(readHomeFoldersRaw(context)).orEmpty()

private fun saveHomeLayout(
    context: Context,
    layout: HomeLayout,
    synchronous: Boolean = false,
): Boolean {
    val editor = context
        .getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
        .edit()
        .putString(HomeLayoutKey, serializeHomeLayout(layout))
    return if (synchronous) editor.commit() else {
        editor.apply()
        true
    }
}

/**
 * Commits the canonical board and both compatibility mirrors as one durable transition. Optional
 * favorite/widget records are included when the logical operation changed them, preventing a
 * successful wide reorder from being observed with an older narrow projection after a crash.
 */
internal fun persistHomeLayoutTransaction(
    context: Context,
    layout: HomeLayout,
    favoriteIds: List<String>? = null,
    appTileSizes: Map<String, AppTileSize>? = null,
    wideAppTileSizes: Map<String, AppTileSize>? = null,
    appTileContentModes: Map<String, AppTileContentMode>? = null,
    widgetSizes: Map<String, WidgetSizeChoice>? = null,
    wideWidgetSizes: Map<String, WidgetSizeChoice>? = null,
    descriptors: List<LauncherWidgetDescriptor>? = null,
    pinnedShortcuts: List<PinnedShortcutRecord>? = null,
    pendingPinnedShortcutUnpins: List<PendingPinnedShortcutUnpin>? = null,
    folders: List<HomeFolder>? = null,
    expectedLayout: HomeLayout? = null,
): Boolean = synchronized(LauncherUriPersistenceLock) {
    val canonical = canonicalizeHomeLayout(layout)
    val pages = canonical.toHomePages()
    val preferences = context.getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
    // A page-count save runs on IO; reject a snapshot changed by another layout writer.
    if (expectedLayout != null && parseHomeLayout(preferences.getString(HomeLayoutKey, null)) != expectedLayout) {
        return@synchronized false
    }
    val editor = preferences.edit()
        .putString(HomeLayoutKey, serializeHomeLayout(canonical))
        .putString(HomePagesKey, serializeHomePages(pages))
        .putString(HomeOrderKey, pages[0].joinToString("\n"))
    favoriteIds?.let { editor.putString(FavoriteIdsKey, it.distinct().joinToString("\n")) }
    appTileSizes?.let {
        editor.putString(AppTileSizesKey, serializeAppTileSizes(it))
    }
    wideAppTileSizes?.let {
        editor.putString(WideAppTileSizesKey, serializeAppTileSizes(it))
    }
    appTileContentModes?.let {
        editor.putString(AppTileContentModesKey, serializeAppTileContentModes(it))
    }
    widgetSizes?.let { editor.putString(WidgetSizeOverridesKey, serializeWidgetSizeOverrides(it)) }
    wideWidgetSizes?.let {
        editor.putString(WideWidgetSizeOverridesKey, serializeWidgetSizeOverrides(it))
    }
    descriptors?.let { editor.putString(WidgetDescriptorsKey, serializeWidgetDescriptors(it)) }
    pinnedShortcuts?.let {
        editor.putString(PinnedShortcutRecordsKey, serializePinnedShortcutRecords(it))
    }
    pendingPinnedShortcutUnpins?.let {
        editor.putString(
            PendingPinnedShortcutUnpinsKey,
            serializePendingPinnedShortcutUnpins(it),
        )
    }
    folders?.let { editor.putString(HomeFoldersKey, serializeHomeFolders(it)) }
    editor.commit()
}

private fun saveHomePages(
    context: Context,
    pages: HomePages,
    synchronous: Boolean = false,
): Boolean {
    // v4 remains authoritative for the wide order. Updating narrow pages refreshes only the
    // narrow order while retaining any prior independent wide reordering.
    val pageIds = pages.pages.flatten().toSet()
    val priorLayout = parseHomeLayout(readHomeLayoutRaw(context))
    val layout = if (priorLayout == null) {
        homeLayoutFromPages(pages)
    } else {
        val pageLayout = homeLayoutFromPages(pages)
        canonicalizeHomeLayout(
            HomeLayout(
                order = priorLayout.order.filter { it in pageIds } +
                    pageLayout.order.filterNot { it in priorLayout.order },
                narrowOrder = pageLayout.narrowOrder,
                narrowPageById = priorLayout.narrowPageById + pageLayout.narrowPageById,
                pageCount = pageLayout.pageCount,
            ),
        )
    }
    val editor = context
        .getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
        .edit()
        .putString(HomePagesKey, serializeHomePages(pages))
        .putString(HomeLayoutKey, serializeHomeLayout(layout))
        // This compatibility mirror is deliberately page 1 only. Legacy builds cannot render
        // page 2, but they can still open the migrated original home board intact.
        .putString(HomeOrderKey, pages[0].joinToString("\n"))
    return if (synchronous) editor.commit() else {
        editor.apply()
        true
    }
}

private fun saveHomeOrder(
    context: Context,
    ids: List<String>,
    synchronous: Boolean = false,
): Boolean {
    val editor = context
        .getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
        .edit()
        .putString(HomeOrderKey, ids.joinToString("\n"))
    return if (synchronous) editor.commit() else {
        editor.apply()
        true
    }
}

internal fun readWidgetDescriptors(context: Context): List<LauncherWidgetDescriptor> {
    val raw = context
        .getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
        .getString(WidgetDescriptorsKey, null)
    return parseWidgetDescriptors(raw)
}

internal fun saveWidgetDescriptors(
    context: Context,
    descriptors: List<LauncherWidgetDescriptor>,
    synchronous: Boolean = false,
): Boolean {
    val editor = context
        .getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
        .edit()
        .putString(WidgetDescriptorsKey, serializeWidgetDescriptors(descriptors))
    return if (synchronous) editor.commit() else {
        editor.apply()
        true
    }
}

private fun interleavedDefaultHomeOrder(favoriteIds: List<String>): List<String> {
    val result = mutableListOf<String>()
    val count = maxOf(DefaultHomeOrder.size, favoriteIds.size)
    repeat(count) { index ->
        DefaultHomeOrder.getOrNull(index)?.let(result::add)
        favoriteIds.getOrNull(index)?.let(result::add)
    }
    return result
}

internal fun normalizeHomeOrder(
    stored: List<String>?,
    favoriteIds: List<String>,
    externalWidgetIds: List<String> = emptyList(),
    folderIds: List<String> = emptyList(),
    hiddenIds: Set<String> = emptySet(),
): List<String> {
    // PHOTO is opt-in for fresh installs, but remains a valid persisted built-in after the user
    // has selected an image. Keep the complete built-in ID namespace in `allowed`; only the
    // default additions below intentionally omit PHOTO.
    val allowed = (HomeWidget.values().map { it.id } + favoriteIds + externalWidgetIds + folderIds)
        .toSet()
    val base = stored.orEmpty()
        .filter { (it in allowed || isPhotoWidgetHomeId(it)) && it !in hiddenIds }
        .distinct()
    val used = base.toSet()
    val additions = if (stored == null) {
        interleavedDefaultHomeOrder(favoriteIds) + externalWidgetIds + folderIds
    } else {
        // A non-null order is user-owned. In particular, a built-in widget intentionally removed
        // by the user must not be resurrected on every activity restart.
        favoriteIds + externalWidgetIds + folderIds
    }
    return base + additions.distinct().filterNot { it in used || it in hiddenIds }
}
