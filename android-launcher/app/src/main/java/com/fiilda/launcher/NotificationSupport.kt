package com.fiilda.launcher

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Notification fields used by the launcher projection. This value object deliberately does not
 * hold a platform [android.service.notification.StatusBarNotification], so filtering and ordering
 * remain straightforward to exercise on the JVM.
 *
 * [isGroupSummary], [isMediaTransport], [isOngoing], and [isAutoCancel] are retained even though
 * the default projection excludes them. Keeping the source flags in the snapshot lets a later
 * presentation policy change without persisting or reconstructing notification text/history.
 */
internal data class NotificationMetadata(
    val key: String,
    val packageName: String,
    val title: String = "",
    val body: String = "",
    val timestamp: Long = 0L,
    val clearable: Boolean = false,
    val isGroupSummary: Boolean = false,
    val isMediaTransport: Boolean = false,
    val isOngoing: Boolean = false,
    val isAutoCancel: Boolean = false,
)

/**
 * In-memory active notification snapshot. PendingIntent is intentionally kept only in this
 * process-local state; no notification text, history, or intent is written to preferences.
 */
internal data class ActiveNotificationSnapshot(
    val metadata: NotificationMetadata,
    val contentIntent: PendingIntent? = null,
    val profileUserId: Int? = null,
) {
    /** Alias kept explicit for callers that treat the handle as a generic pending intent. */
    val pendingIntent: PendingIntent?
        get() = contentIntent
    val key: String
        get() = metadata.key
    val packageName: String
        get() = metadata.packageName
    val title: String
        get() = metadata.title
    val body: String
        get() = metadata.body
    val timestamp: Long
        get() = metadata.timestamp
    val clearable: Boolean
        get() = metadata.clearable
    val isGroupSummary: Boolean
        get() = metadata.isGroupSummary
    val isMediaTransport: Boolean
        get() = metadata.isMediaTransport
    val isOngoing: Boolean
        get() = metadata.isOngoing
    val isAutoCancel: Boolean
        get() = metadata.isAutoCancel
}

internal fun notificationPackageKey(packageName: String, profileUserId: Int?): String =
    if (profileUserId == null) packageName else "$packageName@$profileUserId"

internal fun LaunchableApp.notificationPackageKey(): String =
    notificationPackageKey(packageName, profile?.user?.hashCode())

/** State exposed to the home surface; all values are process-local and intentionally ephemeral. */
internal data class ActiveNotificationState(
    val isConnected: Boolean = false,
    val snapshots: List<ActiveNotificationSnapshot> = emptyList(),
)

/**
 * The notification listener's active set. The service is the sole writer; Compose observes the
 * immutable StateFlow. A LinkedHashMap gives callback updates stable replacement semantics while
 * preserving the order of the latest active set until the pure projection sorts it.
 */
internal object LauncherNotificationStore {
    private val lock = Any()
    private val records = LinkedHashMap<String, ActiveNotificationSnapshot>()
    private val mutableState = MutableStateFlow(ActiveNotificationState())

    val state: StateFlow<ActiveNotificationState> = mutableState.asStateFlow()

    fun connect(snapshots: Collection<ActiveNotificationSnapshot>) {
        synchronized(lock) {
            records.clear()
            snapshots.forEach { snapshot ->
                if (snapshot.key.isNotBlank()) records[snapshot.key] = snapshot
            }
            publishLocked(isConnected = true)
        }
    }

    fun upsert(snapshot: ActiveNotificationSnapshot) {
        if (snapshot.key.isBlank()) return
        synchronized(lock) {
            records[snapshot.key] = snapshot
            publishLocked(isConnected = true)
        }
    }

    fun remove(key: String) {
        if (key.isBlank()) return
        synchronized(lock) {
            records.remove(key)
            publishLocked(isConnected = mutableState.value.isConnected)
        }
    }

    /** Clears text and PendingIntent references after disconnect or listener-access loss. */
    fun disconnect() {
        synchronized(lock) {
            records.clear()
            publishLocked(isConnected = false)
        }
    }

    private fun publishLocked(isConnected: Boolean) {
        val next = ActiveNotificationState(
            isConnected = isConnected,
            snapshots = records.values.toList(),
        )
        if (mutableState.value != next) mutableState.value = next
    }
}

/** Returns true for records allowed into the default favorite-app live projection. */
internal fun isProjectableNotification(metadata: NotificationMetadata): Boolean =
    metadata.key.isNotBlank() &&
        metadata.packageName.isNotBlank() &&
        !metadata.isGroupSummary &&
        !metadata.isMediaTransport &&
        !metadata.isOngoing

/**
 * De-duplicates notification updates by key and orders newest records first. When a malformed
 * callback stream contains the same key more than once, the newest timestamp wins; equal-time
 * updates use the later list entry, matching the callback's latest-value semantics.
 */
internal fun selectProjectableNotifications(
    notifications: Collection<NotificationMetadata>,
): List<NotificationMetadata> {
    if (notifications.isEmpty()) return emptyList()
    val latestByKey = LinkedHashMap<String, NotificationMetadata>()
    notifications.forEach { candidate ->
        if (!isProjectableNotification(candidate)) return@forEach
        val prior = latestByKey[candidate.key]
        if (prior == null || candidate.timestamp >= prior.timestamp) {
            latestByKey[candidate.key] = candidate
        }
    }
    return latestByKey.values.sortedWith(
        compareByDescending<NotificationMetadata> { it.timestamp }
            .thenBy { it.packageName }
            .thenBy { it.key },
    )
}

/** Projects only the favorite packages, retaining a newest-first list for each package. */
internal fun projectFavoriteNotificationMetadata(
    notifications: Collection<NotificationMetadata>,
    favoritePackages: Set<String>,
): Map<String, List<NotificationMetadata>> {
    if (favoritePackages.isEmpty()) return emptyMap()
    return selectProjectableNotifications(notifications)
        .filter { it.packageName in favoritePackages }
        .groupBy { it.packageName }
        .mapValues { (_, records) -> records.toList() }
}

/** Snapshot equivalent of [projectFavoriteNotificationMetadata], including PendingIntent handles. */
internal fun projectFavoriteNotifications(
    notifications: Collection<ActiveNotificationSnapshot>,
    favoritePackages: Set<String>,
): Map<String, List<ActiveNotificationSnapshot>> {
    if (favoritePackages.isEmpty()) return emptyMap()
    val latestByKey = LinkedHashMap<String, ActiveNotificationSnapshot>()
    notifications.forEach { candidate ->
        if (!isProjectableNotification(candidate.metadata) ||
            notificationPackageKey(candidate.packageName, candidate.profileUserId) !in favoritePackages
        ) return@forEach
        val prior = latestByKey[candidate.key]
        if (prior == null || candidate.timestamp >= prior.timestamp) {
            latestByKey[candidate.key] = candidate
        }
    }
    return latestByKey
        .values
        .sortedWith(
            compareByDescending<ActiveNotificationSnapshot> { it.timestamp }
                .thenBy { it.packageName }
                .thenBy { it.key },
        )
        .groupBy { notificationPackageKey(it.packageName, it.profileUserId) }
        .mapValues { (_, records) -> records.toList() }
}

/** Suggested visible row capacity for each supported app-tile footprint. */
internal fun notificationTileCapacity(size: AppTileSize): Int = when (size) {
    AppTileSize.SMALL -> 0
    AppTileSize.WIDE,
    AppTileSize.TALL -> 1
    AppTileSize.LARGE,
    AppTileSize.TALL_3X1 -> 2
    AppTileSize.TALL_3X2 -> 3
}

/**
 * Reduces only the number of rows at accessibility font scales where a fixed tile cannot safely
 * fit the normal header plus every compact row. The default scale preserves the product capacity.
 */
internal fun notificationTileCapacityForFontScale(
    size: AppTileSize,
    fontScale: Float,
): Int {
    val capacity = notificationTileCapacity(size)
    val safeScale = fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    return when {
        safeScale >= 1.7f -> minOf(capacity, 1)
        safeScale >= 1.3f -> minOf(capacity, if (capacity >= 3) 2 else 1)
        else -> capacity
    }
}

/** Returns the compact overflow copy when the current footprint cannot show every record. */
internal fun notificationOverflowLabel(totalCount: Int, capacity: Int): String? {
    val overflow = totalCount - capacity
    return if (capacity > 0 && overflow > 0) tr("ほか${overflow}件", "${overflow} more") else null
}

/**
 * Performs the post-send policy without coupling its success result to best-effort cancellation.
 * The injectable boundary keeps the ordering and failure semantics testable without constructing a
 * framework PendingIntent in a JVM unit test.
 */
internal fun deliverNotificationContentIntent(
    snapshot: ActiveNotificationSnapshot,
    sendIntent: () -> Unit,
    cancelNotification: () -> Unit,
): Boolean {
    val sent = runCatching { sendIntent() }.isSuccess
    if (sent && snapshot.isAutoCancel) {
        runCatching { cancelNotification() }
    }
    return sent
}

/**
 * Selects the sender-side background activity start mode for a user-tapped activity PendingIntent.
 * API 34 and 35 require the legacy opt-in, while API 36 narrows it to the visible-window mode.
 * Broadcast and service PendingIntents deliberately return null so they keep the normal send path.
 */
@SuppressLint("InlinedApi", "NewApi")
@Suppress("DEPRECATION")
internal fun notificationContentIntentBackgroundStartModeForSdk(
    sdkInt: Int,
    isActivity: Boolean,
): Int? {
    if (!isActivity) return null
    return when {
        sdkInt >= Build.VERSION_CODES.BAKLAVA ->
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        else -> null
    }
}

/** Sends an activity PendingIntent with the user-initiated sender BAL opt-in. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun sendActivityNotificationContentIntent(intent: PendingIntent) {
    val mode = notificationContentIntentBackgroundStartModeForSdk(
        sdkInt = Build.VERSION.SDK_INT,
        isActivity = true,
    ) ?: return
    intent.send(
        ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(mode)
            .toBundle(),
    )
}

/** A process-safe best effort for launching a notification's current content intent. */
internal fun sendNotificationContentIntent(snapshot: ActiveNotificationSnapshot): Boolean {
    val intent = snapshot.contentIntent ?: return false
    return deliverNotificationContentIntent(
        snapshot = snapshot,
        sendIntent = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && intent.isActivity) {
                sendActivityNotificationContentIntent(intent)
            } else {
                intent.send()
            }
        },
        cancelNotification = {
            LauncherNotificationListenerService.cancelActiveNotification(snapshot)
        },
    )
}

/** Reads the system listener allow-list without requiring a notification-history permission. */
internal fun notificationListenerAccessGranted(context: Context): Boolean {
    val expected = ComponentName(
        context,
        LauncherNotificationListenerService::class.java,
    )
    val enabled = runCatching {
        Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        )
    }.getOrNull().orEmpty()
    return enabled.split(':')
        .asSequence()
        .mapNotNull { raw -> ComponentName.unflattenFromString(raw) }
        .any { component -> component == expected }
}

/** Compose bridge for callback-driven listener state, with an access-loss safety recheck. */
@Composable
internal fun rememberNotificationState(
    context: Context,
    lifecycleRefreshToken: Int,
): ActiveNotificationState {
    val appContext = remember(context) { context.applicationContext }
    val state by LauncherNotificationStore.state.collectAsState()
    LaunchedEffect(appContext, lifecycleRefreshToken) {
        if (!notificationListenerAccessGranted(appContext)) {
            // A disabled listener may not receive a final framework callback. Clear any handles
            // and text retained by this process as soon as the Activity resumes/rechecks access.
            LauncherNotificationStore.disconnect()
        }
    }
    return state
}
