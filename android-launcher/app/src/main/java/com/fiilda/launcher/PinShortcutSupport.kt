package com.fiilda.launcher

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import java.util.Base64
import java.util.UUID

/**
 * The preference name is shared with the existing home/favorite store so a pin accepted by the
 * platform launcher and the corresponding FiiLDA tile can be committed in one editor transaction.
 */
internal const val FiildaPreferencesName = "fiilda_preferences"
internal const val PinnedShortcutRecordsKey = "pinned_shortcut_records"
internal const val PinnedShortcutHomeIdPrefix = "shortcut:pin:"
internal const val PendingPinnedShortcutUnpinsKey = "pending_pinned_shortcut_unpins"

private const val PinnedShortcutStoragePrefix = "v1:"
private const val PinnedShortcutFieldSeparator = ";"
private const val PinnedShortcutRecordSeparator = "\n"
private const val PinShortcutRequestType = 1
private const val PinnedShortcutCleanupStoragePrefix = "v1:"

/** Serialised identity of one platform shortcut whose local FiiLDA copy was removed. */
internal data class PendingPinnedShortcutUnpin(
    val packageName: String,
    val shortcutId: String,
    val userSerial: Long,
)

internal enum class PinnedShortcutCleanupAttempt {
    COMPLETE,
    RETRY,
}

/** Serialises acceptance/removal/retry decisions inside the launcher process. */
internal val PinnedShortcutCleanupLock = Any()

internal data class PinnedShortcutPlatformCleanupPlan(
    val shouldUpdatePlatform: Boolean,
    val retainedShortcutIds: List<String>,
)

internal data class PinnedShortcutLocalRemovalCommit(
    val record: PinnedShortcutRecord,
    val updatedRecords: List<PinnedShortcutRecord>,
    val pendingUnpins: List<PendingPinnedShortcutUnpin>,
    val updatedLayout: HomeLayout,
)

/**
 * Durable identity for one accepted platform pin request.
 *
 * [instanceId] is intentionally independent of package/shortcut/user identity. Android permits
 * the same shortcut to be pinned more than once, so deduplicating those three platform fields
 * would make one accepted request disappear from FiiLDA's home board.
 */
internal data class PinnedShortcutRecord(
    val instanceId: String,
    val packageName: String,
    val shortcutId: String,
    val userSerial: Long,
    val label: String,
    val longLabel: String = "",
) {
    val homeId: String
        get() = PinnedShortcutHomeIdPrefix + instanceId
}

/** Snapshot used by Compose; platform resolution is kept out of the UI tree. */
internal data class ResolvedPinnedShortcut(
    val record: PinnedShortcutRecord,
    val label: String,
    val icon: Drawable?,
    val isAvailable: Boolean,
) {
    val homeId: String
        get() = record.homeId
}

internal enum class PinShortcutRequestState {
    READY,
    INVALID,
}

internal enum class PinnedShortcutRecordMergeDecision {
    ADD,
    IDEMPOTENT_REPLAY,
    INSTANCE_COLLISION,
}

/** Pure validation boundary for an ACTION_CONFIRM_PIN_SHORTCUT request. */
internal fun pinShortcutRequestState(
    requestType: Int,
    requestIsValid: Boolean,
    packageName: String?,
    shortcutId: String?,
    userSerial: Long?,
    label: String?,
): PinShortcutRequestState = if (
        requestType == PinShortcutRequestType &&
        requestIsValid &&
        !packageName.isNullOrBlank() &&
        !shortcutId.isNullOrEmpty() &&
        (userSerial ?: -1L) >= 0L &&
        !label.isNullOrBlank()
) {
    PinShortcutRequestState.READY
} else {
    PinShortcutRequestState.INVALID
}

/** A canceled/failed platform acceptance must never create a local home record. */
internal fun shouldPersistAcceptedPin(
    requestState: PinShortcutRequestState,
    acceptResult: Boolean,
): Boolean = requestState == PinShortcutRequestState.READY && acceptResult

internal fun pinnedShortcutRecordIsValid(record: PinnedShortcutRecord): Boolean =
        record.instanceId.isNotBlank() &&
        record.packageName.isNotBlank() &&
        record.shortcutId.isNotEmpty() &&
        record.userSerial >= 0L &&
        record.label.isNotBlank()

internal fun pinnedShortcutRecordMergeDecision(
    currentRecords: List<PinnedShortcutRecord>,
    incoming: PinnedShortcutRecord,
): PinnedShortcutRecordMergeDecision = currentRecords
    .firstOrNull { it.instanceId == incoming.instanceId }
    ?.let { current ->
        if (current == incoming) {
            PinnedShortcutRecordMergeDecision.IDEMPOTENT_REPLAY
        } else {
            PinnedShortcutRecordMergeDecision.INSTANCE_COLLISION
        }
    }
    ?: PinnedShortcutRecordMergeDecision.ADD

internal fun pendingPinnedShortcutUnpinIsValid(
    pending: PendingPinnedShortcutUnpin,
): Boolean = pending.packageName.isNotBlank() &&
    pending.shortcutId.isNotEmpty() &&
    pending.userSerial >= 0L

internal fun samePinnedShortcutPlatformIdentity(
    left: PinnedShortcutRecord,
    right: PinnedShortcutRecord,
): Boolean = left.packageName == right.packageName &&
    left.shortcutId == right.shortcutId &&
    left.userSerial == right.userSerial

internal fun samePinnedShortcutPlatformIdentity(
    left: PendingPinnedShortcutUnpin,
    right: PendingPinnedShortcutUnpin,
): Boolean = left.packageName == right.packageName &&
    left.shortcutId == right.shortcutId &&
    left.userSerial == right.userSerial

internal fun samePinnedShortcutPlatformIdentity(
    left: PinnedShortcutRecord,
    right: PendingPinnedShortcutUnpin,
): Boolean = left.packageName == right.packageName &&
    left.shortcutId == right.shortcutId &&
    left.userSerial == right.userSerial

internal fun samePinnedShortcutPlatformIdentity(
    left: PendingPinnedShortcutUnpin,
    right: PinnedShortcutRecord,
): Boolean = samePinnedShortcutPlatformIdentity(right, left)

internal fun pendingPinnedShortcutUnpinFor(
    record: PinnedShortcutRecord,
): PendingPinnedShortcutUnpin = PendingPinnedShortcutUnpin(
    packageName = record.packageName,
    shortcutId = record.shortcutId,
    userSerial = record.userSerial,
)

/**
 * Removing one local instance only schedules platform cleanup when it was the final local copy.
 * A later duplicate/repin cancels the pending operation before it can unpin the platform ID.
 */
internal fun pendingPinnedShortcutUnpinsAfterRemoval(
    currentRecords: List<PinnedShortcutRecord>,
    currentPending: List<PendingPinnedShortcutUnpin>,
    removedRecord: PinnedShortcutRecord,
): List<PendingPinnedShortcutUnpin> {
    val remainingRecords = currentRecords.filterNot { it.instanceId == removedRecord.instanceId }
    val removedIdentity = pendingPinnedShortcutUnpinFor(removedRecord)
    val withoutRemovedIdentity = currentPending.filterNot {
        samePinnedShortcutPlatformIdentity(it, removedIdentity)
    }
    return if (remainingRecords.any { samePinnedShortcutPlatformIdentity(it, removedRecord) }) {
        withoutRemovedIdentity
    } else {
        (withoutRemovedIdentity + removedIdentity)
            .filter(::pendingPinnedShortcutUnpinIsValid)
            .distinctBy { Triple(it.packageName, it.shortcutId, it.userSerial) }
    }
}

/** Keep every actual platform ID except the removed target when no local duplicate remains. */
internal fun pinnedShortcutPlatformCleanupPlan(
    platformPinnedShortcutIds: List<String>,
    targetShortcutId: String,
    hasRemainingLocalCopy: Boolean,
): PinnedShortcutPlatformCleanupPlan {
    val distinctPlatformIds = platformPinnedShortcutIds
        .filter(String::isNotEmpty)
        .distinct()
    return if (hasRemainingLocalCopy) {
        PinnedShortcutPlatformCleanupPlan(
            shouldUpdatePlatform = false,
            retainedShortcutIds = distinctPlatformIds,
        )
    } else {
        PinnedShortcutPlatformCleanupPlan(
            shouldUpdatePlatform = targetShortcutId in distinctPlatformIds,
            retainedShortcutIds = distinctPlatformIds.filterNot { it == targetShortcutId },
        )
    }
}

/** A failed binder/query operation keeps its durable queue entry for a later retry. */
internal fun pendingPinnedShortcutUnpinsAfterAttempt(
    pending: List<PendingPinnedShortcutUnpin>,
    completed: PendingPinnedShortcutUnpin,
    attempt: PinnedShortcutCleanupAttempt,
): List<PendingPinnedShortcutUnpin> = when (attempt) {
    PinnedShortcutCleanupAttempt.COMPLETE -> pending.filterNot {
        samePinnedShortcutPlatformIdentity(it, completed)
    }
    PinnedShortcutCleanupAttempt.RETRY -> pending
}

internal fun resolvedPinnedShortcutIsAvailable(
    userResolved: Boolean,
    infoFound: Boolean,
    infoEnabled: Boolean,
): Boolean = userResolved && infoFound && infoEnabled

private fun encodePinnedShortcutField(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

private fun decodePinnedShortcutField(value: String): String? = runCatching {
    String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}.getOrNull()

/**
 * Versioned line storage keeps arbitrary provider labels safe while retaining a human-inspectable
 * record boundary. Invalid lines are ignored and valid records are de-duplicated only by their
 * generated instance ID.
 */
internal fun serializePinnedShortcutRecords(records: List<PinnedShortcutRecord>): String = records
    .filter(::pinnedShortcutRecordIsValid)
    .distinctBy { it.instanceId }
    .joinToString(PinnedShortcutRecordSeparator) { record ->
        listOf(
            record.instanceId,
            record.packageName,
            record.shortcutId,
            record.userSerial.toString(),
            record.label,
            record.longLabel,
        ).joinToString(PinnedShortcutFieldSeparator, transform = ::encodePinnedShortcutField)
    }
    .let { PinnedShortcutStoragePrefix + it }

internal fun parsePinnedShortcutRecords(raw: String?): List<PinnedShortcutRecord> {
    if (raw.isNullOrBlank() || !raw.startsWith(PinnedShortcutStoragePrefix)) return emptyList()
    return raw.removePrefix(PinnedShortcutStoragePrefix)
        .split(PinnedShortcutRecordSeparator)
        .mapNotNull { line ->
            val fields = line.split(PinnedShortcutFieldSeparator)
            if (fields.size != 6) return@mapNotNull null
            val decoded = fields.map(::decodePinnedShortcutField)
            if (decoded.any { it == null }) return@mapNotNull null
            val values = decoded.requireNoNulls()
            val userSerial = values[3].toLongOrNull() ?: return@mapNotNull null
            PinnedShortcutRecord(
                instanceId = values[0],
                packageName = values[1],
                shortcutId = values[2],
                userSerial = userSerial,
                label = values[4],
                longLabel = values[5],
            ).takeIf(::pinnedShortcutRecordIsValid)
        }
        .distinctBy { it.instanceId }
}

internal fun serializePendingPinnedShortcutUnpins(
    pending: List<PendingPinnedShortcutUnpin>,
): String = pending
    .filter(::pendingPinnedShortcutUnpinIsValid)
    .distinctBy { Triple(it.packageName, it.shortcutId, it.userSerial) }
    .joinToString(PinnedShortcutRecordSeparator) { item ->
        listOf(
            item.packageName,
            item.shortcutId,
            item.userSerial.toString(),
        ).joinToString(PinnedShortcutFieldSeparator, transform = ::encodePinnedShortcutField)
    }
    .let { PinnedShortcutCleanupStoragePrefix + it }

internal fun parsePendingPinnedShortcutUnpins(raw: String?): List<PendingPinnedShortcutUnpin> {
    if (raw.isNullOrBlank() || !raw.startsWith(PinnedShortcutCleanupStoragePrefix)) {
        return emptyList()
    }
    return raw.removePrefix(PinnedShortcutCleanupStoragePrefix)
        .split(PinnedShortcutRecordSeparator)
        .mapNotNull { line ->
            val fields = line.split(PinnedShortcutFieldSeparator)
            if (fields.size != 3) return@mapNotNull null
            val decoded = fields.map(::decodePinnedShortcutField)
            if (decoded.any { it == null }) return@mapNotNull null
            val values = decoded.requireNoNulls()
            val userSerial = values[2].toLongOrNull() ?: return@mapNotNull null
            PendingPinnedShortcutUnpin(
                packageName = values[0],
                shortcutId = values[1],
                userSerial = userSerial,
            ).takeIf(::pendingPinnedShortcutUnpinIsValid)
        }
        .distinctBy { Triple(it.packageName, it.shortcutId, it.userSerial) }
}

internal fun readPinnedShortcutRecords(context: Context): List<PinnedShortcutRecord> = context
    .getSharedPreferences(FiildaPreferencesName, Context.MODE_PRIVATE)
    .getString(PinnedShortcutRecordsKey, null)
    .let(::parsePinnedShortcutRecords)

internal fun readPendingPinnedShortcutUnpins(
    context: Context,
): List<PendingPinnedShortcutUnpin> = context
    .getSharedPreferences(FiildaPreferencesName, Context.MODE_PRIVATE)
    .getString(PendingPinnedShortcutUnpinsKey, null)
    .let(::parsePendingPinnedShortcutUnpins)

/**
 * Creates a local record from the exact ShortcutInfo carried by the platform request. The profile
 * serial is stored instead of a UserHandle object so the identity survives process and config
 * recreation while still resolving the current handle through UserManager.
 */
internal fun pinnedShortcutRecordFromShortcutInfo(
    context: Context,
    info: ShortcutInfo,
    instanceId: String = UUID.randomUUID().toString(),
): PinnedShortcutRecord? {
    val userHandle = runCatching { info.userHandle }.getOrNull() ?: return null
    val userManager = context.getSystemService(UserManager::class.java) ?: return null
    val userSerial = runCatching { userManager.getSerialNumberForUser(userHandle) }
        .getOrDefault(-1L)
    if (userSerial < 0L) return null
    val packageName = runCatching { info.`package` }.getOrNull().orEmpty().trim()
    // Shortcut IDs are opaque provider identities. Preserve every character exactly; trimming
    // here would make a valid provider ID impossible to launch after it is persisted.
    val shortcutId = runCatching { info.id }.getOrNull().orEmpty()
    val shortLabel = runCatching { info.shortLabel?.toString() }.getOrNull().orEmpty().trim()
    val longLabel = runCatching { info.longLabel?.toString() }.getOrNull().orEmpty().trim()
    val appLabel = runCatching {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(packageName, 0),
        ).toString()
    }.getOrDefault("").trim()
    val label = shortLabel.ifBlank { longLabel }.ifBlank { appLabel }
    return PinnedShortcutRecord(
        instanceId = instanceId,
        packageName = packageName,
        shortcutId = shortcutId,
        userSerial = userSerial,
        label = label,
        longLabel = longLabel,
    ).takeIf(::pinnedShortcutRecordIsValid)
}

private fun userForSerial(userManager: UserManager?, serial: Long): UserHandle? =
    if (userManager == null || serial < 0L) null else {
        runCatching { userManager.getUserForSerialNumber(serial) }.getOrNull()
    }

private fun shortcutQueryFlags(): Int {
    var flags = LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
        LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        flags = flags or LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED
    }
    return flags
}

private fun findPinnedShortcutInfo(
    launcherApps: LauncherApps?,
    record: PinnedShortcutRecord,
    user: UserHandle,
): ShortcutInfo? {
    if (launcherApps == null) return null
    return runCatching {
        val query = LauncherApps.ShortcutQuery()
            .setPackage(record.packageName)
            .setShortcutIds(listOf(record.shortcutId))
            .setQueryFlags(shortcutQueryFlags())
        launcherApps.getShortcuts(query, user)
            ?.firstOrNull { info ->
                info.id == record.shortcutId &&
                    runCatching { info.`package` == record.packageName }.getOrDefault(false)
            }
    }.getOrNull()
}

private fun fallbackShortcutIcon(context: Context, packageName: String): Drawable? = runCatching {
    context.packageManager.getApplicationIcon(packageName)
}.getOrNull() ?: runCatching {
    context.getDrawable(android.R.drawable.sym_def_app_icon)
}.getOrNull()

/**
 * Resolves every durable record, including records whose profile is currently locked or whose
 * package is temporarily unavailable. Keeping a placeholder preserves the user's home ID and
 * lets a later LauncherApps callback fill in the real icon without a destructive prune.
 */
internal fun queryResolvedPinnedShortcuts(
    context: Context,
    records: List<PinnedShortcutRecord> = readPinnedShortcutRecords(context),
): List<ResolvedPinnedShortcut> {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val userManager = context.getSystemService(UserManager::class.java)
    val densityDpi = context.resources.displayMetrics.densityDpi
    return records.filter(::pinnedShortcutRecordIsValid).map { record ->
        val user = userForSerial(userManager, record.userSerial)
        val info = user?.let { findPinnedShortcutInfo(launcherApps, record, it) }
        val label = runCatching { info?.shortLabel?.toString()?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: record.label
        val icon = info?.let {
            runCatching { launcherApps?.getShortcutIconDrawable(it, densityDpi) }.getOrNull()
        } ?: fallbackShortcutIcon(context, record.packageName)
        ResolvedPinnedShortcut(
            record = record,
            label = label,
            icon = icon,
            isAvailable = resolvedPinnedShortcutIsAvailable(
                userResolved = user != null,
                infoFound = info != null,
                infoEnabled = info?.let {
                    runCatching { it.isEnabled }.getOrDefault(false)
                } == true,
            ),
        )
    }
}

internal fun launchPinnedShortcut(
    context: Context,
    shortcut: ResolvedPinnedShortcut,
): Boolean {
    val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return false
    val userManager = context.getSystemService(UserManager::class.java) ?: return false
    val user = userForSerial(userManager, shortcut.record.userSerial) ?: return false
    return runCatching {
        launcherApps.startShortcut(
            shortcut.record.packageName,
            shortcut.record.shortcutId,
            null,
            null,
            user,
        )
        true
    }.getOrDefault(false)
}

private fun queryPlatformPinnedShortcutIds(
    launcherApps: LauncherApps,
    pending: PendingPinnedShortcutUnpin,
    user: UserHandle,
): List<String> {
    val query = LauncherApps.ShortcutQuery()
        .setPackage(pending.packageName)
        .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
    val shortcuts = launcherApps.getShortcuts(query, user)
        ?: throw IllegalStateException("LauncherApps returned no pinned shortcut result")
    return shortcuts.map { info ->
        val packageName = runCatching { info.`package` }
            .getOrElse { throw IllegalStateException("Pinned shortcut package is unavailable") }
        check(packageName == pending.packageName) {
            "LauncherApps returned a shortcut for another package"
        }
        runCatching { info.id }
            .getOrElse { throw IllegalStateException("Pinned shortcut ID is unavailable") }
            .also { id -> check(id.isNotEmpty()) { "Pinned shortcut ID is empty" } }
    }.distinct()
}

/**
 * Completes durable platform cleanup entries. The local tile is removed before this operation, so
 * every query/call failure leaves a retry entry rather than an orphaned local ID. A newly accepted
 * duplicate cancels its entry while holding [PinnedShortcutCleanupLock].
 */
internal fun cleanupPendingPinnedShortcutUnpins(
    context: Context,
): PinnedShortcutCleanupAttempt = synchronized(PinnedShortcutCleanupLock) {
    val preferences = context.getSharedPreferences(FiildaPreferencesName, Context.MODE_PRIVATE)
    val pending = parsePendingPinnedShortcutUnpins(
        preferences.getString(PendingPinnedShortcutUnpinsKey, null),
    )
    if (pending.isEmpty()) return@synchronized PinnedShortcutCleanupAttempt.COMPLETE

    val currentRecords = parsePinnedShortcutRecords(
        preferences.getString(PinnedShortcutRecordsKey, null),
    )
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val userManager = context.getSystemService(UserManager::class.java)
    val remaining = mutableListOf<PendingPinnedShortcutUnpin>()
    var needsRetry = false

    pending.forEach { item ->
        // A duplicate accepted while a prior cleanup was waiting must keep the platform ID alive.
        if (currentRecords.any { samePinnedShortcutPlatformIdentity(it, item) }) return@forEach
        val user = userForSerial(userManager, item.userSerial)
        if (launcherApps == null || user == null) {
            remaining += item
            needsRetry = true
            return@forEach
        }
        val platformIds = runCatching {
            queryPlatformPinnedShortcutIds(launcherApps, item, user)
        }.getOrElse {
            remaining += item
            needsRetry = true
            return@forEach
        }
        val plan = pinnedShortcutPlatformCleanupPlan(
            platformPinnedShortcutIds = platformIds,
            targetShortcutId = item.shortcutId,
            hasRemainingLocalCopy = false,
        )
        if (!plan.shouldUpdatePlatform) return@forEach
        val platformUpdated = runCatching {
            // pinShortcuts replaces the package/profile pin set, so pass every queried ID except
            // the removed target to preserve platform pins not represented in FiiLDA's records.
            launcherApps.pinShortcuts(item.packageName, plan.retainedShortcutIds, user)
            true
        }.getOrDefault(false)
        if (!platformUpdated) {
            remaining += item
            needsRetry = true
        }
    }

    if (remaining != pending) {
        val queueCommitted = preferences.edit()
            .putString(PendingPinnedShortcutUnpinsKey, serializePendingPinnedShortcutUnpins(remaining))
            .commit()
        if (!queueCommitted) needsRetry = true
    }
    if (needsRetry) PinnedShortcutCleanupAttempt.RETRY
    else PinnedShortcutCleanupAttempt.COMPLETE
}

/**
 * Selects the IDs that may be used while repairing an old home snapshot for a pin request. A
 * parsed v4 record is authoritative; its compatibility mirrors are intentionally ignored because
 * they can still contain an item the user removed after the canonical write.
 */
internal fun pinnedShortcutRecoveryIds(
    storedLayout: HomeLayout?,
    storedPages: HomePages?,
    legacyOrder: List<String>?,
): List<String> = (
    if (storedLayout != null) {
        storedLayout.allIds.toList()
    } else {
        storedPages?.pages.orEmpty().flatten() + legacyOrder.orEmpty()
    }
    ).filter { it.isNotBlank() }.distinct()

/**
 * Atomically appends the accepted record and its page-0 home placement. The current canonical
 * layout is authoritative; if an older/malformed layout is all that exists, all raw stored IDs are
 * supplied as recovery IDs so existing widgets and favorites cannot be lost in this separate
 * confirmation Activity.
 */
internal fun persistAcceptedPinnedShortcut(
    context: Context,
    record: PinnedShortcutRecord,
    targetHomePage: Int = 0,
): Boolean = synchronized(PinnedShortcutCleanupLock) {
    if (!pinnedShortcutRecordIsValid(record)) return false
    val preferences = context.getSharedPreferences(FiildaPreferencesName, Context.MODE_PRIVATE)
    val currentRecords = parsePinnedShortcutRecords(
        preferences.getString(PinnedShortcutRecordsKey, null),
    )
    val mergeDecision = pinnedShortcutRecordMergeDecision(currentRecords, record)
    when (mergeDecision) {
        PinnedShortcutRecordMergeDecision.INSTANCE_COLLISION -> return false
        PinnedShortcutRecordMergeDecision.ADD,
        PinnedShortcutRecordMergeDecision.IDEMPOTENT_REPLAY -> Unit
    }
    val currentPending = parsePendingPinnedShortcutUnpins(
        preferences.getString(PendingPinnedShortcutUnpinsKey, null),
    )

    val storedLayoutRaw = preferences.getString("home_layout", null)
    val storedPagesRaw = preferences.getString("home_pages", null)
    val storedLegacyOrder = preferences.getString("home_order", null)
        ?.split("\n")
        ?.filter { it.isNotBlank() }
    val storedLayout = parseHomeLayout(storedLayoutRaw)
    val storedPages = parseHomePages(storedPagesRaw)
    val rawStoredIds = pinnedShortcutRecoveryIds(
        storedLayout = storedLayout,
        storedPages = storedPages,
        legacyOrder = storedLegacyOrder,
    )
    val storedFavorites = preferences.getString("favorite_ids", null)
        ?.split("\n")
        ?.filter { it.isNotBlank() }
        .orEmpty()
    val storedFolders = normalizeHomeFolders(
        stored = parseHomeFolders(preferences.getString(HomeFoldersKey, null)),
        favoriteIds = (storedFavorites + rawStoredIds).toSet(),
    )
    val previousPinnedIds = currentRecords.map { it.homeId }
    val layout = normalizeHomeLayout(
        storedLayout = storedLayout,
        storedPages = storedPages,
        legacyOrder = storedLegacyOrder,
        favoriteIds = (storedFavorites + rawStoredIds).distinct(),
        externalWidgetIds = (previousPinnedIds + record.homeId).distinct(),
        homeFolders = storedFolders,
    )
    val updatedLayout = addHomeItemToLayout(layout, record.homeId, targetHomePage)
    val updatedRecords = when (mergeDecision) {
        PinnedShortcutRecordMergeDecision.ADD -> currentRecords + record
        PinnedShortcutRecordMergeDecision.IDEMPOTENT_REPLAY -> currentRecords
        PinnedShortcutRecordMergeDecision.INSTANCE_COLLISION -> return false
    }
    // A fresh platform acceptance makes this identity live again. Clear any queued cleanup for it
    // in the same commit so a later retry cannot unpin the newly accepted duplicate.
    val updatedPending = currentPending.filterNot {
        samePinnedShortcutPlatformIdentity(it, record)
    }
    val pages = updatedLayout.toHomePages()
    preferences.edit()
        .putString(PinnedShortcutRecordsKey, serializePinnedShortcutRecords(updatedRecords))
        .putString(PendingPinnedShortcutUnpinsKey, serializePendingPinnedShortcutUnpins(updatedPending))
        .putString("home_layout", serializeHomeLayout(updatedLayout))
        .putString("home_pages", serializeHomePages(pages))
        .putString("home_order", pages[0].joinToString("\n"))
        .putString(HomeFoldersKey, serializeHomeFolders(storedFolders))
        .commit()
}

internal fun removePinnedShortcutRecord(
    context: Context,
    record: PinnedShortcutRecord,
): Boolean = synchronized(PinnedShortcutCleanupLock) {
    val preferences = context.getSharedPreferences(FiildaPreferencesName, Context.MODE_PRIVATE)
    val currentRecords = parsePinnedShortcutRecords(
        preferences.getString(PinnedShortcutRecordsKey, null),
    )
    val persistedRecord = currentRecords.firstOrNull { it.instanceId == record.instanceId }
        ?: return true
    val updatedRecords = currentRecords.filterNot { it.instanceId == persistedRecord.instanceId }
    val updatedPending = pendingPinnedShortcutUnpinsAfterRemoval(
        currentRecords = currentRecords,
        currentPending = parsePendingPinnedShortcutUnpins(
            preferences.getString(PendingPinnedShortcutUnpinsKey, null),
        ),
        removedRecord = persistedRecord,
    )
    val storedFolders = parseHomeFolders(preferences.getString(HomeFoldersKey, null)).orEmpty()
    val currentLayout = parseHomeLayout(preferences.getString("home_layout", null))
    val updatedLayout = currentLayout?.let { removeHomeItemFromLayout(it, persistedRecord.homeId) }
    val editor = preferences.edit()
        .putString(PinnedShortcutRecordsKey, serializePinnedShortcutRecords(updatedRecords))
        .putString(PendingPinnedShortcutUnpinsKey, serializePendingPinnedShortcutUnpins(updatedPending))
        .putString(HomeFoldersKey, serializeHomeFolders(storedFolders))
    if (updatedLayout != null) {
        val pages = updatedLayout.toHomePages()
        editor
            .putString("home_layout", serializeHomeLayout(updatedLayout))
            .putString("home_pages", serializeHomePages(pages))
            .putString("home_order", pages[0].joinToString("\n"))
    }
    editor.commit()
}
