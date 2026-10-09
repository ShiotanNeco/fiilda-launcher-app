package com.fiilda.launcher

import android.Manifest
import android.os.Build
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/**
 * Converts user input and labels to the same comparison form. NFKC handles full-width and
 * half-width variants (including a half-width dakuten), while the explicit kana conversion makes
 * hiragana and katakana behave as one search alphabet.
 */
internal fun normalizeDrawerSearchText(value: String): String {
    val nfkc = Normalizer.normalize(value, Normalizer.Form.NFKC)
    val lower = nfkc.lowercase(Locale.ROOT)
    return lower
        .map { character ->
            if (character in '\u30A1'..'\u30F6') {
                (character.code - 0x60).toChar()
            } else {
                character
            }
        }
        .joinToString("")
        .trim()
}

/** Short alias for tests and UI code that only needs the pure normalization operation. */
internal fun normalizeSearchQuery(value: String): String = normalizeDrawerSearchText(value)

internal enum class SearchMatchRank {
    EXACT,
    PREFIX,
    CONTAINS,
}

internal data class RankedSearchValue<T>(
    val value: T,
    val rank: SearchMatchRank,
    val originalIndex: Int,
)

/**
 * Ranks exact matches before prefixes before other containing matches. Kotlin's indexed sort
 * keeps equal labels stable even on runtimes where the underlying sort implementation changes.
 */
internal fun <T> rankDrawerSearchValues(
    query: String,
    values: List<T>,
    label: (T) -> String,
): List<T> {
    val normalizedQuery = normalizeDrawerSearchText(query)
    if (normalizedQuery.isBlank()) return values
    return values.mapIndexedNotNull { index, value ->
        val normalizedLabel = normalizeDrawerSearchText(label(value))
        val rank = when {
            normalizedLabel == normalizedQuery -> SearchMatchRank.EXACT
            normalizedLabel.startsWith(normalizedQuery) -> SearchMatchRank.PREFIX
            normalizedLabel.contains(normalizedQuery) -> SearchMatchRank.CONTAINS
            else -> null
        }
        rank?.let { RankedSearchValue(value, it, index) }
    }.sortedWith(
        compareBy<RankedSearchValue<T>> { it.rank.ordinal }
            .thenBy { it.originalIndex },
    ).map { it.value }
}

/** Pure app ranking used by the root/drawer without changing [LaunchableApp]. */
internal fun rankLaunchableAppsForDrawerSearch(
    query: String,
    apps: List<LaunchableApp>,
): List<LaunchableApp> = rankDrawerSearchValues(query, apps) { it.label }

internal fun rankLaunchableApps(
    query: String,
    apps: List<LaunchableApp>,
): List<LaunchableApp> = rankLaunchableAppsForDrawerSearch(query, apps)

internal fun searchMatchRank(query: String, label: String): SearchMatchRank? {
    val normalizedQuery = normalizeDrawerSearchText(query)
    if (normalizedQuery.isBlank()) return null
    val normalizedLabel = normalizeDrawerSearchText(label)
    return when {
        normalizedLabel == normalizedQuery -> SearchMatchRank.EXACT
        normalizedLabel.startsWith(normalizedQuery) -> SearchMatchRank.PREFIX
        normalizedLabel.contains(normalizedQuery) -> SearchMatchRank.CONTAINS
        else -> null
    }
}

internal fun externalSearchUrl(
    target: ExternalSearchTarget,
    query: String,
): String {
    // java.net.URLEncoder is available to JVM unit tests as well as Android. Convert its
    // form-encoding '+' to %20 because these are URL query components rather than form bodies.
    val encodedQuery = URLEncoder
        .encode(query.trim(), Charsets.UTF_8.name())
        .replace("+", "%20")
    return when (target) {
        ExternalSearchTarget.GOOGLE -> "https://www.google.com/search?q=$encodedQuery"
        ExternalSearchTarget.MAPS ->
            "https://www.google.com/maps/search/?api=1&query=$encodedQuery"
        ExternalSearchTarget.YOUTUBE ->
            "https://www.youtube.com/results?search_query=$encodedQuery"
    }
}

internal enum class DrawerSearchPermissionAccess {
    NONE,
    FULL,
}

internal data class DrawerSearchPermissionState(
    val source: DeviceSearchSource,
    val access: DrawerSearchPermissionAccess,
    val requiredPermissions: List<String>,
    val grantedPermissions: Set<String>,
) {
    val isDenied: Boolean
        get() = access == DrawerSearchPermissionAccess.NONE
    val isFull: Boolean
        get() = access == DrawerSearchPermissionAccess.FULL
}

/** Returns the runtime permission set for the current media-permission generation. */
internal fun drawerSearchPermissionsFor(
    source: DeviceSearchSource,
    sdkInt: Int = Build.VERSION.SDK_INT,
): List<String> = when (source) {
    DeviceSearchSource.CONTACTS -> listOf(Manifest.permission.READ_CONTACTS)
    DeviceSearchSource.AUDIO -> if (sdkInt <= 32) {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    } else {
        listOf(Manifest.permission.READ_MEDIA_AUDIO)
    }
}

internal fun resolveDrawerSearchPermissionState(
    source: DeviceSearchSource,
    grantedPermissions: Set<String>,
    sdkInt: Int,
): DrawerSearchPermissionState {
    val required = drawerSearchPermissionsFor(source, sdkInt)
    val granted = grantedPermissions.intersect(required.toSet())
    val access = if (granted.size == required.size) {
        DrawerSearchPermissionAccess.FULL
    } else {
        DrawerSearchPermissionAccess.NONE
    }
    return DrawerSearchPermissionState(
        source = source,
        access = access,
        requiredPermissions = required,
        grantedPermissions = granted,
    )
}

internal fun drawerSearchStatusForPermission(
    enabled: Boolean,
    permission: DrawerSearchPermissionState,
): SearchSourceStatus = when {
    !enabled -> SearchSourceStatus.DISABLED
    permission.access == DrawerSearchPermissionAccess.NONE -> SearchSourceStatus.DENIED
    else -> SearchSourceStatus.READY
}

/**
 * Recovers the source that launched a restored RequestMultiplePermissions callback. An empty
 * result returns null; the controller then invalidates all source caches and rechecks every
 * enabled source.
 */
internal fun inferDrawerSearchPermissionSource(
    permissionNames: Collection<String>,
    sdkInt: Int,
): DeviceSearchSource? {
    val names = permissionNames.toSet()
    if (names.isEmpty()) return null
    val candidates = DeviceSearchSource.values().filter { source ->
        drawerSearchPermissionsFor(source, sdkInt).any { it in names }
    }
    return candidates.singleOrNull()
}

/** Minimal tree metadata used by the bounded breadth-first scanner. */
internal data class SearchDocumentEntry(
    val id: String,
    val uri: String,
    val label: String,
    val mimeType: String? = null,
    val isDirectory: Boolean = false,
)

internal fun interface SearchCancellation {
    fun throwIfCanceled()
}

internal object NoopSearchCancellation : SearchCancellation {
    override fun throwIfCanceled() = Unit
}

internal class AndroidSearchCancellation(
    internal val signal: android.os.CancellationSignal,
) : SearchCancellation {
    override fun throwIfCanceled() = signal.throwIfCanceled()
}

internal interface SearchDocumentReader {
    fun children(parentUri: String, cancellation: SearchCancellation): List<SearchDocumentEntry>

    /**
     * Returns one bounded child page. Existing fakes can implement [children] only; production
     * readers override this method to pass a provider LIMIT/OFFSET and enforce the same bound while
     * iterating the cursor.
     */
    fun children(
        parentUri: String,
        cancellation: SearchCancellation,
        offset: Int,
        limit: Int,
    ): List<SearchDocumentEntry> = children(parentUri, cancellation)
        .drop(offset.coerceAtLeast(0))
        .take(limit.coerceAtLeast(0))

    fun metadata(uri: String, cancellation: SearchCancellation): SearchDocumentEntry?
}

internal data class DocumentScanRound(
    val results: List<SearchResult>,
    val entriesVisited: Int,
    val hasMore: Boolean,
)

/**
 * A stateful BFS that does no I/O itself. Keeping the frontier here makes Continue deterministic
 * and lets tests use pure fakes. A round visits and reads at most [maxEntries] work/child rows.
 */
internal class SearchDocumentScanSession(
    targets: List<SearchDocumentTarget>,
    private val reader: SearchDocumentReader,
    private val maxEntries: Int = SearchDocumentScanSession.DEFAULT_MAX_ENTRIES,
) {
    companion object {
        const val DEFAULT_MAX_ENTRIES = 5_000
    }

    private data class FrontierItem(
        val entry: SearchDocumentEntry,
        val childOffset: Int = 0,
        val isContinuation: Boolean = false,
        val needsMetadata: Boolean = false,
    )

    private val frontier = ArrayDeque<FrontierItem>()
    private val visited = LinkedHashSet<String>()
    private val accumulated = LinkedHashMap<String, SearchResult>()
    private val initialItems = targets
        .filterNot { it.requiresReselection }
        .map { target ->
            FrontierItem(
                entry = SearchDocumentEntry(
                    id = target.id,
                    uri = target.uri,
                    label = target.label,
                    isDirectory = target.isTree,
                ),
                needsMetadata = !target.isTree,
            )
        }

    init {
        frontier.addAll(initialItems)
    }

    fun scanRound(
        query: String,
        cancellation: SearchCancellation = NoopSearchCancellation,
    ): DocumentScanRound {
        val normalizedQuery = normalizeDrawerSearchText(query)
        var workItemsThisRound = 0
        var childRowsReadThisRound = 0
        while (
            frontier.isNotEmpty() &&
            workItemsThisRound < maxEntries
        ) {
            // A page may consume the whole row budget while placing file rows in the frontier.
            // Those rows still need to be matched in this round; only another directory query is
            // deferred until Continue.
            val next = frontier.first()
            if (next.entry.isDirectory && childRowsReadThisRound >= maxEntries) break
            cancellation.throwIfCanceled()
            val item = frontier.removeFirst()
            val entry = item.entry
            if (!item.isContinuation && !visited.add(entry.id)) continue
            workItemsThisRound++
            try {
                if (entry.isDirectory) {
                    val pageLimit = (maxEntries - childRowsReadThisRound).coerceAtLeast(1)
                    val page = reader.children(
                        parentUri = entry.uri,
                        cancellation = cancellation,
                        offset = item.childOffset,
                        limit = pageLimit,
                    ).take(pageLimit)
                    childRowsReadThisRound += page.size
                    // Match the current page's files before asking for the next sibling page. Put
                    // directory descendants after that continuation so a large flat directory is
                    // drained in stable order before nested work is expanded.
                    val pageFiles = page.filterNot { it.isDirectory }
                    val pageDirectories = page.filter { it.isDirectory }
                    pageDirectories.asReversed().forEach { child ->
                        if (child.id !in visited) {
                            frontier.addFirst(
                                FrontierItem(
                                    entry = child,
                                    needsMetadata = false,
                                ),
                            )
                        }
                    }
                    if (page.size == pageLimit) {
                        frontier.addFirst(
                            item.copy(
                                childOffset = item.childOffset + page.size,
                                isContinuation = true,
                            ),
                        )
                    }
                    pageFiles.asReversed().forEach { child ->
                        if (child.id !in visited) {
                            frontier.addFirst(
                                FrontierItem(
                                    entry = child,
                                    needsMetadata = false,
                                ),
                            )
                        }
                    }
                } else {
                    // Child rows already carry display name/mime metadata. Only standalone file
                    // targets need the extra metadata lookup (and therefore cannot cause 5,000
                    // redundant provider queries during a tree scan).
                    val metadata = if (item.needsMetadata) {
                        reader.metadata(entry.uri, cancellation) ?: entry
                    } else {
                        entry
                    }
                    val match = searchMatchRank(normalizedQuery, metadata.label)
                    if (match != null) {
                        accumulated[metadata.id] = SearchResult(
                            id = metadata.id,
                            label = metadata.label,
                            subtitle = metadata.mimeType,
                            uri = metadata.uri,
                            mimeType = metadata.mimeType,
                            source = null,
                        )
                    }
                }
            } catch (cancelled: Throwable) {
                // A Continue round may reuse the session. Put the entry back before propagating
                // cancellation so an interrupted provider call cannot silently skip a directory.
                if (cancelled is kotlinx.coroutines.CancellationException ||
                    cancelled is android.os.OperationCanceledException
                ) {
                    if (!item.isContinuation) visited.remove(entry.id)
                    frontier.addFirst(item)
                }
                throw cancelled
            }
        }
        val ranked = rankDrawerSearchValues(normalizedQuery, accumulated.values.toList()) { it.label }
        return DocumentScanRound(
            results = ranked,
            entriesVisited = workItemsThisRound,
            hasMore = frontier.isNotEmpty(),
        )
    }

    fun reset() {
        frontier.clear()
        visited.clear()
        accumulated.clear()
        frontier.addAll(initialItems)
    }

    val hasMore: Boolean
        get() = frontier.isNotEmpty()

    val visitedIds: Set<String>
        get() = visited.toSet()
}
