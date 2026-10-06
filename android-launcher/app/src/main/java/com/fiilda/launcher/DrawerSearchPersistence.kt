package com.fiilda.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.nio.charset.StandardCharsets
import java.net.URI as JavaUri
import java.net.URLDecoder
import java.util.Base64

/** Search storage is deliberately separate from the home/favorite preference namespace. */
internal const val DrawerSearchPreferencesName = "fiilda_drawer_search"
/** Process-wide lock shared by search-target and photo URI read/modify/write transactions. */
internal object LauncherUriPersistenceLock
private const val SearchSourcesKey = "enabled_sources_v1"
private const val SearchDocumentTargetsKey = "document_targets_v1"
private const val OwnedReadGrantUrisKey = "owned_read_grants_v1"
private const val SearchStoragePrefix = "v1:"

internal data class DrawerSearchSourcePreferences(
    val enabled: Map<DeviceSearchSource, Boolean>,
) {
    fun isEnabled(source: DeviceSearchSource): Boolean = enabled[source] == true
}

internal fun readDrawerSearchSourcePreferences(context: Context): DrawerSearchSourcePreferences {
    val raw = context
        .getSharedPreferences(DrawerSearchPreferencesName, Context.MODE_PRIVATE)
        .getString(SearchSourcesKey, null)
    val enabled = DeviceSearchSource.values().associateWith { source ->
        raw
            ?.split("\n")
            ?.firstOrNull { it.substringBefore('=') == source.name }
            ?.substringAfter('=', "0") == "1"
    }
    return DrawerSearchSourcePreferences(enabled)
}

internal fun saveDrawerSearchSourceEnabled(
    context: Context,
    source: DeviceSearchSource,
    enabled: Boolean,
): Boolean {
    val current = readDrawerSearchSourcePreferences(context).enabled.toMutableMap()
    current[source] = enabled
    val value = DeviceSearchSource.values().joinToString("\n") {
        "${it.name}=${if (current[it] == true) 1 else 0}"
    }
    return context
        .getSharedPreferences(DrawerSearchPreferencesName, Context.MODE_PRIVATE)
        .edit()
        .putString(SearchSourcesKey, value)
        .commit()
}

internal fun searchDocumentTargetId(uri: Uri, isTree: Boolean): String =
    (if (isTree) "tree:" else "file:") + uri.toString()

internal fun searchDocumentTargetId(uriString: String, isTree: Boolean): String =
    (if (isTree) "tree:" else "file:") + uriString

internal fun readSearchDocumentTargets(context: Context): List<SearchDocumentTarget> {
    val raw = context
        .getSharedPreferences(DrawerSearchPreferencesName, Context.MODE_PRIVATE)
        .getString(SearchDocumentTargetsKey, null)
        ?: return emptyList()
    return parseSearchDocumentTargets(raw)
}

internal fun saveSearchDocumentTargets(
    context: Context,
    targets: List<SearchDocumentTarget>,
): Boolean = context
    .getSharedPreferences(DrawerSearchPreferencesName, Context.MODE_PRIVATE)
    .edit()
    .putString(SearchDocumentTargetsKey, serializeSearchDocumentTargets(targets))
    .commit()

/**
 * Records grants acquired by this launcher. It is separate from the target list because a tree
 * grant can outlive the target that first caused it to be taken (for example while a child file
 * target remains). Pre-existing provider grants are intentionally never added to this ledger.
 */
internal fun readLauncherOwnedReadGrants(context: Context): Set<String> {
    val raw = context
        .getSharedPreferences(DrawerSearchPreferencesName, Context.MODE_PRIVATE)
        .getString(OwnedReadGrantUrisKey, null)
        ?: return emptySet()
    return raw.split("\n")
        .mapNotNull { encoded -> decodeSearchText(encoded)?.takeIf { it.isNotBlank() } }
        .toSet()
}

internal fun recordLauncherOwnedReadGrants(
    context: Context,
    uriStrings: Collection<String>,
): Boolean {
    val merged = (readLauncherOwnedReadGrants(context) + uriStrings.filter { it.isNotBlank() })
        .toSortedSet()
    val raw = merged.joinToString("\n") { encodeSearchText(it) }
    return context
        .getSharedPreferences(DrawerSearchPreferencesName, Context.MODE_PRIVATE)
        .edit()
        .putString(OwnedReadGrantUrisKey, raw)
        .commit()
}

private fun forgetLauncherOwnedReadGrants(
    context: Context,
    uriStrings: Collection<String>,
): Boolean {
    if (uriStrings.isEmpty()) return true
    val remaining = readLauncherOwnedReadGrants(context) - uriStrings.toSet()
    val raw = remaining.toSortedSet().joinToString("\n") { encodeSearchText(it) }
    return context
        .getSharedPreferences(DrawerSearchPreferencesName, Context.MODE_PRIVATE)
        .edit()
        .putString(OwnedReadGrantUrisKey, raw)
        .commit()
}

internal fun serializeSearchDocumentTargets(
    targets: List<SearchDocumentTarget>,
): String = SearchStoragePrefix + targets
    .filter { it.id.isNotBlank() && it.uri.isNotBlank() }
    .distinctBy { it.id }
    .joinToString(";") { target ->
        listOf(
            encodeSearchText(target.id),
            encodeSearchText(target.label),
            encodeSearchText(target.uri),
            if (target.isTree) "1" else "0",
        ).joinToString(",")
    }

internal fun parseSearchDocumentTargets(raw: String?): List<SearchDocumentTarget> {
    if (raw.isNullOrBlank() || !raw.startsWith(SearchStoragePrefix)) return emptyList()
    return raw.removePrefix(SearchStoragePrefix)
        .split(';')
        .mapNotNull { record ->
            val fields = record.split(',', limit = 4)
            if (fields.size != 4) return@mapNotNull null
            val id = decodeSearchText(fields[0]) ?: return@mapNotNull null
            val label = decodeSearchText(fields[1]) ?: return@mapNotNull null
            val uri = decodeSearchText(fields[2]) ?: return@mapNotNull null
            val isTree = when (fields[3]) {
                "1" -> true
                "0" -> false
                else -> return@mapNotNull null
            }
            if (id.isBlank() || uri.isBlank()) return@mapNotNull null
            SearchDocumentTarget(
                id = id,
                label = label.ifBlank { uri.substringAfterLast('/').ifBlank { uri } },
                uri = uri,
                isTree = isTree,
            )
        }
        .distinctBy { it.id }
}

private fun encodeSearchText(value: String): String = Base64.getUrlEncoder()
    .withoutPadding()
    .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decodeSearchText(value: String): String? = runCatching {
    String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}.getOrNull()

internal fun searchDocumentDisplayName(uri: Uri): String {
    return searchDocumentDisplayName(uri.toString())
}

internal fun searchDocumentDisplayName(uriString: String): String {
    val rawPath = runCatching { JavaUri(uriString).rawPath.orEmpty() }.getOrDefault(uriString)
    val segment = rawPath.substringAfterLast('/').takeIf { it.isNotBlank() } ?: uriString
    return runCatching { URLDecoder.decode(segment, StandardCharsets.UTF_8.name()) }
        .getOrDefault(segment)
        .ifBlank { uriString }
}

internal fun searchDocumentTarget(
    uri: Uri,
    isTree: Boolean,
    label: String = searchDocumentDisplayName(uri),
): SearchDocumentTarget = SearchDocumentTarget(
    id = searchDocumentTargetId(uri.toString(), isTree),
    label = label.ifBlank { searchDocumentDisplayName(uri) },
    uri = uri.toString(),
    isTree = isTree,
)

internal fun searchDocumentTarget(
    uriString: String,
    isTree: Boolean,
    label: String = searchDocumentDisplayName(uriString),
): SearchDocumentTarget = SearchDocumentTarget(
    id = searchDocumentTargetId(uriString, isTree),
    label = label.ifBlank { searchDocumentDisplayName(uriString) },
    uri = uriString,
    isTree = isTree,
)

/** The small abstraction makes grant rollback tests independent of a real ContentResolver. */
internal interface PersistedReadGrantStore {
    fun persistedReadUris(): Set<String>
    fun takeReadPermission(uri: Uri): Boolean
    fun releaseReadPermission(uri: Uri)
}

internal data class PersistedReadGrantSnapshot(
    val uris: Set<String>,
)

internal fun snapshotPersistedReadGrants(
    store: PersistedReadGrantStore,
): PersistedReadGrantSnapshot = PersistedReadGrantSnapshot(store.persistedReadUris())

internal fun wasReadGrantNewlyAcquired(
    uriString: String,
    before: PersistedReadGrantSnapshot,
    after: PersistedReadGrantStore,
): Boolean = uriString !in before.uris && uriString in after.persistedReadUris()

internal fun shouldReleaseNewlyAcquiredReadGrant(
    uriString: String,
    before: PersistedReadGrantSnapshot,
    afterUris: Set<String>,
    currentOwners: Collection<String>,
): Boolean {
    if (uriString in before.uris || uriString !in afterUris) return false
    // An overlapping owner does not automatically justify retaining a newly acquired grant. The
    // new URI must directionally cover that owner's target, and no grant from the pre-operation
    // snapshot may already cover it. This releases a redundant child grant beside an existing
    // tree grant (and the inverse redundant tree grant beside an existing child grant), while
    // preserving a grant that a concurrent/new owner actually needs.
    val ownerNeedsNewGrant = currentOwners.any { owner ->
        uriGrantOwnershipOverlaps(owner, uriString) &&
            persistedReadGrantCoversTarget(uriString, owner) &&
            before.uris.none { existingGrant ->
                persistedReadGrantCoversTarget(existingGrant, owner)
            }
    }
    return !ownerNeedsNewGrant
}

internal fun ownedGrantsToReleaseAfterOwnerRemoval(
    removedUri: String,
    ownedGrants: Collection<String>,
    remainingOwners: Collection<String>,
): List<String> = ownedGrants.filter { grant ->
    uriGrantOwnershipOverlaps(grant, removedUri) &&
        remainingOwners.none { owner -> uriGrantOwnershipOverlaps(owner, grant) }
}

internal class ContentResolverReadGrantStore(
    private val context: Context,
) : PersistedReadGrantStore {
    private val resolver
        get() = context.contentResolver

    override fun persistedReadUris(): Set<String> = resolver.persistedUriPermissions
        .asSequence()
        .filter { it.isReadPermission }
        .map { it.uri.toString() }
        .toSet()

    override fun takeReadPermission(uri: Uri): Boolean = runCatching {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    }.getOrDefault(false)

    override fun releaseReadPermission(uri: Uri) {
        runCatching {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

/**
 * URI grants can overlap: a tree grant covers descendants, and a file target may sit below a tree
 * target. This conservative relation is intentionally symmetric so neither ownership direction
 * can accidentally release a grant still needed by the other target.
 */
internal fun uriGrantOwnershipOverlaps(first: String, second: String): Boolean {
    if (first == second) return true
    val firstUri = runCatching { JavaUri(first) }.getOrNull() ?: return false
    val secondUri = runCatching { JavaUri(second) }.getOrNull() ?: return false
    if (firstUri.scheme != secondUri.scheme || firstUri.rawAuthority != secondUri.rawAuthority) {
        return false
    }
    if (javaUriGrantCovers(firstUri, secondUri) || javaUriGrantCovers(secondUri, firstUri)) return true
    val firstPath = firstUri.rawPath.orEmpty().trimEnd('/')
    val secondPath = secondUri.rawPath.orEmpty().trimEnd('/')
    return firstPath.isNotBlank() && secondPath.isNotBlank() && (
        secondPath.startsWith("$firstPath/") || firstPath.startsWith("$secondPath/")
        )
}

/** True only when the persisted grant in [grantUri] covers the requested target. */
internal fun persistedReadGrantCoversTarget(
    grantUri: String,
    targetUri: String,
): Boolean {
    if (grantUri == targetUri) return true
    val grant = runCatching { JavaUri(grantUri) }.getOrNull() ?: return false
    val target = runCatching { JavaUri(targetUri) }.getOrNull() ?: return false
    if (grant.scheme != target.scheme || grant.rawAuthority != target.rawAuthority) return false
    if (javaUriGrantCovers(grant, target)) return true
    val grantPath = grant.rawPath.orEmpty().trimEnd('/')
    val targetPath = target.rawPath.orEmpty().trimEnd('/')
    return grantPath.isNotBlank() && targetPath.startsWith("$grantPath/")
}

private fun javaUriGrantCovers(owner: JavaUri, candidate: JavaUri): Boolean {
    val ownerDocumentId = javaDocumentId(owner.rawPath.orEmpty()) ?: return false
    val candidateDocumentId = javaDocumentId(candidate.rawPath.orEmpty()) ?: return false
    val decodedOwner = decodeUriPart(ownerDocumentId)
    val decodedCandidate = decodeUriPart(candidateDocumentId)
    return decodedCandidate == decodedOwner || decodedCandidate.startsWith("$decodedOwner/")
}

private fun javaDocumentId(path: String): String? {
    // A tree child URI contains both `/tree/<root>` and `/document/<child>`; the latter is the
    // identity that must be compared. For a persisted root URI only the tree marker exists.
    val documentMarkerIndex = path.lastIndexOf("/document/")
    val marker = if (documentMarkerIndex >= 0) {
        "/document/"
    } else if ("/tree/" in path) {
        "/tree/"
    } else {
        return null
    }
    return path.substringAfter(marker).substringBefore('/').takeIf { it.isNotBlank() }
}

private fun decodeUriPart(value: String): String = runCatching {
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}.getOrDefault(value)

internal fun hasLauncherUriOwner(
    context: Context,
    uriString: String,
    additionalOwners: Collection<String> = emptyList(),
): Boolean {
    val owners = readPhotoUris(context).values +
        readSearchDocumentTargets(context).map { it.uri } +
        additionalOwners
    return owners.any { owner -> uriGrantOwnershipOverlaps(owner, uriString) }
}

/** Releases only when the URI is no longer owned by the photo or search preference records. */
internal fun releaseLauncherUriPermissionIfUnowned(
    context: Context,
    uriString: String,
    grantStore: PersistedReadGrantStore = ContentResolverReadGrantStore(context),
    additionalOwners: Collection<String> = emptyList(),
): Boolean = synchronized(LauncherUriPersistenceLock) {
    if (hasLauncherUriOwner(context, uriString, additionalOwners)) return false
    // Release every owned grant that overlaps the removed target. This also retires a deferred
    // tree grant after its last child target disappears. Unknown/pre-existing grants are kept.
    val remainingOwners = readPhotoUris(context).values +
        readSearchDocumentTargets(context).map { it.uri } +
        additionalOwners
    val ownedGrants = ownedGrantsToReleaseAfterOwnerRemoval(
        removedUri = uriString,
        ownedGrants = readLauncherOwnedReadGrants(context),
        remainingOwners = remainingOwners,
    )
    if (ownedGrants.isEmpty()) return false
    ownedGrants.forEach { grant -> grantStore.releaseReadPermission(Uri.parse(grant)) }
    forgetLauncherOwnedReadGrants(context, ownedGrants)
    true
}

/** Rollback path: the pre-acquire snapshot is the authority, so no persistent ledger is needed. */
internal fun releaseNewlyAcquiredReadGrantIfUnowned(
    context: Context,
    uriString: String,
    before: PersistedReadGrantSnapshot,
    grantStore: PersistedReadGrantStore,
    additionalOwners: Collection<String> = emptyList(),
): Boolean {
    if (!shouldReleaseNewlyAcquiredReadGrant(
            uriString = uriString,
            before = before,
            afterUris = grantStore.persistedReadUris(),
            currentOwners = readPhotoUris(context).values +
                readSearchDocumentTargets(context).map { it.uri } + additionalOwners,
        )
    ) return false
    grantStore.releaseReadPermission(Uri.parse(uriString))
    return true
}

internal fun persistSearchDocumentTargetsWithReadGrants(
    context: Context,
    selected: List<SearchDocumentTarget>,
    grantStore: PersistedReadGrantStore = ContentResolverReadGrantStore(context),
): Boolean = synchronized(LauncherUriPersistenceLock) {
    persistSearchDocumentTargetsWithReadGrants(
        context = context,
        selected = selected,
        replacedId = null,
        grantStore = grantStore,
    )
}

/**
 * Persists a picker round and, when [replacedId] is supplied, removes that old target in the same
 * preference commit. Grants are acquired before the commit; a failed grant or write leaves the
 * old target and all of its ownership intact. The old URI is released only after the replacement
 * is durably visible and no remaining target/photo owns an overlapping grant.
 */
internal fun replaceSearchDocumentTargetWithReadGrants(
    context: Context,
    replacedId: String,
    selected: List<SearchDocumentTarget>,
    grantStore: PersistedReadGrantStore = ContentResolverReadGrantStore(context),
): Boolean = synchronized(LauncherUriPersistenceLock) {
    persistSearchDocumentTargetsWithReadGrants(
        context = context,
        selected = selected,
        replacedId = replacedId,
        grantStore = grantStore,
    )
}

private fun persistSearchDocumentTargetsWithReadGrants(
    context: Context,
    selected: List<SearchDocumentTarget>,
    replacedId: String?,
    grantStore: PersistedReadGrantStore,
): Boolean {
    val normalized = selected
        .filter { it.uri.isNotBlank() }
        .distinctBy { it.id }
    if (normalized.isEmpty()) return false
    val previous = readSearchDocumentTargets(context)
    val replaced = replacedId?.let { id -> previous.firstOrNull { it.id == id } }
    if (replacedId != null && replaced == null) return false
    val previousWithoutReplaced = if (replacedId == null) {
        previous
    } else {
        previous.filterNot { it.id == replacedId }
    }
    val previousUris = previous.map { it.uri }.toSet()
    val snapshot = snapshotPersistedReadGrants(grantStore)
    val newlyTaken = mutableListOf<String>()
    for (target in normalized) {
        if (!grantStore.takeReadPermission(Uri.parse(target.uri))) {
            newlyTaken.forEach { uri ->
                releaseNewlyAcquiredReadGrantIfUnowned(
                    context = context,
                    uriString = uri,
                    before = snapshot,
                    grantStore = grantStore,
                    additionalOwners = previousUris,
                )
            }
            return false
        }
        if (wasReadGrantNewlyAcquired(target.uri, snapshot, grantStore)) {
            newlyTaken += target.uri
        }
    }
    // A reselected/recovered target has the freshest provider display name and metadata. Put it
    // first so distinctBy replaces a stale opaque URI-tail label from the previous record.
    val merged = (normalized + previousWithoutReplaced)
        .distinctBy { it.id }
    if (!saveSearchDocumentTargets(context, merged)) {
        newlyTaken.forEach { uri ->
            releaseNewlyAcquiredReadGrantIfUnowned(
                context = context,
                uriString = uri,
                before = snapshot,
                grantStore = grantStore,
                additionalOwners = previousUris,
            )
        }
        return false
    }
    if (replaced != null) {
        // Register a legacy target only after its replacement is committed. A failed write then
        // leaves both the old preference record and its ownership ledger unchanged.
        recordLauncherOwnedReadGrants(context, listOf(replaced.uri))
    }
    recordLauncherOwnedReadGrants(context, newlyTaken)
    if (replaced != null && replaced.uri !in merged.map { it.uri }) {
        releaseLauncherUriPermissionIfUnowned(context, replaced.uri, grantStore)
    }
    return true
}

internal fun removeSearchDocumentTarget(
    context: Context,
    id: String,
    grantStore: PersistedReadGrantStore = ContentResolverReadGrantStore(context),
): SearchDocumentTarget? = synchronized(LauncherUriPersistenceLock) {
    val previous = readSearchDocumentTargets(context)
    val removed = previous.firstOrNull { it.id == id } ?: return null
    val remaining = previous.filterNot { it.id == id }
    if (!saveSearchDocumentTargets(context, remaining)) return null
    // The target itself proved launcher ownership even when it was restored from an older build
    // before the ledger existed. Record it after the preference commit so a failed write leaves
    // both the target and the ownership ledger untouched.
    recordLauncherOwnedReadGrants(context, listOf(removed.uri))
    releaseLauncherUriPermissionIfUnowned(context, removed.uri, grantStore)
    removed
}
