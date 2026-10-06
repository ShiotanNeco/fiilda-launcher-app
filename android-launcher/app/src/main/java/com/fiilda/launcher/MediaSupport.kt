package com.fiilda.launcher

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

internal data class MediaSnapshot(
    val title: String = tr("再生していません", "Not playing"),
    val artist: String = tr("メディア", "Media"),
    val albumArt: Bitmap? = null,
    /** Stable provider metadata identity; Bitmap wrapper identity is intentionally ignored. */
    val artworkKey: String? = null,
    val position: Long = 0L,
    val duration: Long = 0L,
    /** elapsedRealtime timestamp at which [position] was sampled by PlaybackState. */
    val positionUpdateTime: Long = 0L,
    val playbackSpeed: Float = 1f,
    val isPlaying: Boolean = false,
)

internal data class MediaSessionState(
    val controller: MediaController? = null,
    val snapshot: MediaSnapshot = MediaSnapshot(),
    val hasAccess: Boolean = false,
)

internal enum class MediaAppLaunchPath {
    SESSION_ACTIVITY,
    PACKAGE_LAUNCH,
    CONTEXT_PANEL,
}

/**
 * Chooses the launch path for the currently selected media controller.
 *
 * The callbacks are deliberately injected so the ordering and failure fallback can be tested
 * without constructing framework MediaController or PendingIntent instances. A failed session
 * activity launch is treated exactly like an unavailable one and allows the same controller's
 * package launch intent to be attempted.
 */
internal fun mediaAppLaunchPath(
    controllerAvailable: Boolean,
    sessionActivity: (() -> Boolean)?,
    packageLaunch: (() -> Boolean)?,
): MediaAppLaunchPath {
    if (!controllerAvailable) return MediaAppLaunchPath.CONTEXT_PANEL
    if (sessionActivity != null && runCatching { sessionActivity() }.getOrDefault(false)) {
        return MediaAppLaunchPath.SESSION_ACTIVITY
    }
    if (packageLaunch != null && runCatching { packageLaunch() }.getOrDefault(false)) {
        return MediaAppLaunchPath.PACKAGE_LAUNCH
    }
    return MediaAppLaunchPath.CONTEXT_PANEL
}

/**
 * Opens the application represented by [controller], returning false when the caller should
 * preserve the existing media context panel behavior.
 *
 * MediaSession providers can expose a cancelled or non-activity session PendingIntent. The
 * package fallback is intentionally derived from this exact controller, so tapping the tile can
 * never select an unrelated music application.
 */
internal fun launchMediaApp(
    context: Context,
    controller: MediaController?,
): Boolean {
    val sessionActivity = controller?.let { mediaController ->
        runCatching { mediaController.sessionActivity }
            .getOrNull()
            ?.takeIf { pendingIntent ->
                runCatching { pendingIntent.isActivity }.getOrDefault(false)
            }
    }
    val packageLaunchIntent = controller?.let { mediaController ->
        val packageName = runCatching { mediaController.packageName }.getOrNull()
        packageName
            ?.takeIf { it.isNotBlank() }
            ?.let { packageToLaunch ->
                runCatching {
                    context.packageManager.getLaunchIntentForPackage(packageToLaunch)
                }.getOrNull()
            }
    }
    return mediaAppLaunchPath(
        controllerAvailable = controller != null,
        sessionActivity = sessionActivity?.let { pendingIntent ->
            { sendMediaSessionActivity(pendingIntent) }
        },
        packageLaunch = packageLaunchIntent?.let { launchIntent ->
            {
                context.startActivity(launchIntent)
                true
            }
        },
    ) != MediaAppLaunchPath.CONTEXT_PANEL
}

/** Sends the media session's activity PendingIntent with the launcher tap BAL opt-in. */
@SuppressLint("InlinedApi", "NewApi")
@Suppress("DEPRECATION")
private fun sendMediaSessionActivity(intent: PendingIntent): Boolean = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && intent.isActivity) {
        val mode = notificationContentIntentBackgroundStartModeForSdk(
            sdkInt = Build.VERSION.SDK_INT,
            isActivity = true,
        )
        if (mode != null) {
            intent.send(
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(mode)
                    .toBundle(),
            )
        } else {
            intent.send()
        }
    } else {
        intent.send()
    }
    true
}.getOrDefault(false)

/**
 * Reconciles wrappers returned by MediaSessionManager by stable session token.
 *
 * MediaSessionManager may create a new MediaController wrapper on each query even when the
 * underlying session is unchanged. Preserve the existing wrapper/callback for equal tokens and
 * drop duplicate token entries while retaining manager order for controller selection.
 */
internal fun <Token, Controller> reconcileMediaControllersBySessionToken(
    incoming: List<Pair<Token, Controller>>,
    existing: Map<Token, Controller>,
): List<Pair<Token, Controller>> {
    val seen = HashSet<Token>()
    return incoming.mapNotNull { (token, candidate) ->
        if (!seen.add(token)) null else token to (existing[token] ?: candidate)
    }
}

/**
 * Returns the semantic artwork identity exposed by a media provider.
 *
 * Providers commonly recreate an otherwise identical Bitmap for every callback. URI/media-id
 * metadata is the stable signal for whether the artwork actually changed, so it is kept separate
 * from the Bitmap object held by [MediaSnapshot].
 */
internal fun mediaArtworkSemanticKey(
    mediaId: String?,
    artUri: String?,
    albumArtUri: String?,
    displayIconUri: String?,
    titleFallback: String? = null,
    artistFallback: String? = null,
    albumFallback: String? = null,
): String? {
    val values = listOf(mediaId, artUri, albumArtUri, displayIconUri)
        .map { it?.trim()?.takeIf { value -> value.isNotEmpty() } }
    if (values.any { it != null }) {
        return values.joinToString(separator = "\u001f") { it.orEmpty() }
    }
    val fallbackValues = listOf(titleFallback, artistFallback, albumFallback)
        .map { it?.trim()?.takeIf { value -> value.isNotEmpty() } }
    if (fallbackValues.all { it == null }) return null
    return listOf("fallback").plus(fallbackValues).joinToString(separator = "\u001f") {
        it.orEmpty()
    }
}

/** Compares only values observable by the media tile, excluding Bitmap wrapper identity. */
internal fun mediaSnapshotsSemanticallyEqual(
    left: MediaSnapshot,
    right: MediaSnapshot,
): Boolean = left.title == right.title &&
    left.artist == right.artist &&
    left.artworkKey == right.artworkKey &&
    (left.albumArt != null) == (right.albumArt != null) &&
    left.position == right.position &&
    left.duration == right.duration &&
    left.positionUpdateTime == right.positionUpdateTime &&
    left.playbackSpeed == right.playbackSpeed &&
    left.isPlaying == right.isPlaying

/** Plain bridge used to retry a failed media-session connection without replacing a live one. */
private class MediaSessionRefreshHolder(initialLifecycleRefreshToken: Int) {
    var lastLifecycleRefreshToken = initialLifecycleRefreshToken
    var callback: (() -> Unit)? = null
}

@Composable
internal fun rememberMediaSessionState(
    context: Context,
    lifecycleRefreshToken: Int,
): MediaSessionState {
    val appContext = context.applicationContext
    val mediaSessionManager = remember(appContext) {
        appContext.getSystemService(MediaSessionManager::class.java)
    }
    // These are plain main-thread holders intentionally keyed only by the manager. A lifecycle
    // refresh keeps destroyed-session tombstones and the token/artwork cache alive without
    // replacing a connected listener.
    val destroyedSessionTokens = remember(mediaSessionManager) {
        mutableSetOf<MediaSession.Token>()
    }
    val mediaSnapshotCache = remember(mediaSessionManager) {
        mutableMapOf<MediaSession.Token, MediaSnapshot>()
    }
    val mediaSessionRefreshHolder = remember(mediaSessionManager) {
        MediaSessionRefreshHolder(lifecycleRefreshToken)
    }
    var state by remember { mutableStateOf(MediaSessionState()) }

    // Permission changes happen in Settings while this composition remains alive. The listener
    // stays attached after a successful connection; the plain retry bridge below reconnects only
    // after a failed access attempt, while media updates remain callback driven rather than polled.
    androidx.compose.runtime.DisposableEffect(mediaSessionManager) {
        if (mediaSessionManager == null) {
            if (state != MediaSessionState()) state = MediaSessionState()
            onDispose { }
        } else {
            val component = ComponentName(appContext, LauncherNotificationListenerService::class.java)
            val callbackHandler = Handler(Looper.getMainLooper())
            var disposed = false
            var activeSessionsListenerRegistered = false
            var activeControllers = emptyList<MediaController>()
            class ControllerRegistration(
                val controller: MediaController,
                val callback: MediaController.Callback,
            )
            val controllerRegistrations = mutableMapOf<MediaSession.Token, ControllerRegistration>()
            lateinit var refreshControllers: () -> Unit

            fun sessionTokenOf(controller: MediaController): MediaSession.Token? =
                runCatching { controller.sessionToken }.getOrNull()

            fun publishAccessFailure() {
                activeControllers = emptyList()
                controllerRegistrations.values.toList().forEach { registration ->
                    runCatching {
                        registration.controller.unregisterCallback(registration.callback)
                    }
                }
                controllerRegistrations.clear()
                if (!disposed && state != MediaSessionState()) {
                    state = MediaSessionState()
                }
            }

            fun publish(controllers: List<MediaController>) {
                if (disposed) return
                val activeTokens = controllers.mapNotNull(::sessionTokenOf).toSet()
                mediaSnapshotCache.keys.retainAll(activeTokens)
                val controller = chooseMediaController(controllers)
                val token = controller?.let(::sessionTokenOf)
                val previousSnapshot = token?.let { mediaSnapshotCache[it] }
                val snapshot = controller?.let {
                    readMediaSnapshot(it, previousSnapshot)
                } ?: MediaSnapshot()
                if (token != null) mediaSnapshotCache[token] = snapshot
                val next = MediaSessionState(
                    controller = controller,
                    snapshot = snapshot,
                    hasAccess = true,
                )
                // MediaController callbacks can repeat an unchanged metadata/playback snapshot.
                // Avoid invalidating every HomeSurface when no observable value changed. Bitmap
                // instances are deliberately excluded; providers often wrap the same artwork in
                // a fresh object for each metadata callback.
                if (
                    state.hasAccess != next.hasAccess ||
                    state.controller !== next.controller ||
                    !mediaSnapshotsSemanticallyEqual(state.snapshot, next.snapshot)
                ) {
                    state = next
                }
            }

            fun synchronizeControllers(incoming: List<MediaController>) {
                if (disposed) return
                val incomingWithTokens = incoming.mapNotNull { controller ->
                    sessionTokenOf(controller)?.let { token -> token to controller }
                }
                val incomingTokens = incomingWithTokens.mapTo(hashSetOf()) { (token, _) -> token }
                // Retain tombstones only while the manager still reports that token. Once the
                // listener confirms removal, a later genuinely new session may use it normally.
                destroyedSessionTokens.retainAll(incomingTokens)
                val canonicalControllers = reconcileMediaControllersBySessionToken(
                    incoming = incomingWithTokens.filterNot { (token, _) ->
                        token in destroyedSessionTokens
                    },
                    existing = controllerRegistrations.mapValues { it.value.controller },
                )
                val activeTokens = canonicalControllers.mapTo(mutableSetOf()) { (token, _) -> token }
                controllerRegistrations.keys.toList()
                    .filterNot { token -> token in activeTokens }
                    .forEach { token ->
                        controllerRegistrations.remove(token)?.let { registration ->
                            runCatching {
                                registration.controller.unregisterCallback(registration.callback)
                            }
                        }
                    }

                val registeredControllers = mutableListOf<MediaController>()
                canonicalControllers.forEach { (token, controller) ->
                    val existing = controllerRegistrations[token]
                    if (existing != null) {
                        registeredControllers += existing.controller
                        return@forEach
                    }
                    lateinit var callback: MediaController.Callback
                    callback = object : MediaController.Callback() {
                        override fun onMetadataChanged(metadata: MediaMetadata?) {
                            refreshControllers()
                        }

                        override fun onPlaybackStateChanged(state: PlaybackState?) {
                            refreshControllers()
                        }

                        override fun onSessionDestroyed() {
                            // Remove this token immediately so a destroyed session cannot remain
                            // selected while the manager dispatches its list update.
                            val registration = controllerRegistrations[token]
                            if (registration?.callback !== callback) return
                            destroyedSessionTokens += token
                            controllerRegistrations.remove(token)
                            runCatching {
                                registration.controller.unregisterCallback(registration.callback)
                            }
                            activeControllers = activeControllers.filterNot {
                                sessionTokenOf(it) == token
                            }
                            publish(activeControllers)
                            // Do not query the manager here: its list can still contain the
                            // destroyed token for one dispatch turn. The active-session listener
                            // will reconcile it after the framework publishes the removal.
                        }
                    }
                    // Publish the token identity before registering so a provider that dispatches
                    // a callback synchronously during registration cannot recurse into a second
                    // registration for the same session.
                    controllerRegistrations[token] = ControllerRegistration(controller, callback)
                    val registered = runCatching {
                        controller.registerCallback(callback, callbackHandler)
                    }.isSuccess
                    if (registered && controllerRegistrations[token]?.callback === callback) {
                        registeredControllers += controller
                    } else {
                        // Do not publish a controller whose callback could not be installed; its
                        // snapshot would otherwise remain as stale UI until another session event.
                        controllerRegistrations.remove(token)
                    }
                }
                activeControllers = registeredControllers
                publish(activeControllers)
            }

            refreshControllers = {
                if (!disposed) {
                    try {
                        synchronizeControllers(mediaSessionManager.getActiveSessions(component))
                    } catch (_: SecurityException) {
                        publishAccessFailure()
                    }
                }
            }

            val activeSessionsListener = object : MediaSessionManager.OnActiveSessionsChangedListener {
                override fun onActiveSessionsChanged(controllers: List<MediaController>?) {
                    synchronizeControllers(controllers.orEmpty())
                }
            }

            fun connectAndRefresh() {
                if (disposed) return
                try {
                    if (!activeSessionsListenerRegistered) {
                        mediaSessionManager.addOnActiveSessionsChangedListener(
                            activeSessionsListener,
                            component,
                            callbackHandler,
                        )
                        activeSessionsListenerRegistered = true
                    }
                    refreshControllers()
                } catch (_: SecurityException) {
                    activeSessionsListenerRegistered = false
                    publishAccessFailure()
                }
            }

            mediaSessionRefreshHolder.callback = ::connectAndRefresh
            connectAndRefresh()

            onDispose {
                disposed = true
                mediaSessionRefreshHolder.callback = null
                if (activeSessionsListenerRegistered) {
                    runCatching {
                        mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener)
                    }
                }
                controllerRegistrations.values.toList().forEach { registration ->
                    runCatching {
                        registration.controller.unregisterCallback(registration.callback)
                    }
                }
                controllerRegistrations.clear()
                activeControllers = emptyList()
                activeSessionsListenerRegistered = false
            }
        }
    }

    // A successful listener remains attached across normal resumes. Only a failed connection
    // uses this token as a retry signal, avoiding a queued destroyed callback racing an effect
    // teardown/re-registration on every resume.
    LaunchedEffect(lifecycleRefreshToken) {
        if (mediaSessionRefreshHolder.lastLifecycleRefreshToken != lifecycleRefreshToken) {
            mediaSessionRefreshHolder.lastLifecycleRefreshToken = lifecycleRefreshToken
            if (!state.hasAccess) {
                mediaSessionRefreshHolder.callback?.invoke()
            }
        }
    }

    return state
}

private fun chooseMediaController(controllers: List<MediaController>): MediaController? =
    controllers.firstOrNull {
        runCatching { it.playbackState?.state == PlaybackState.STATE_PLAYING }.getOrDefault(false)
    }
        ?: controllers.firstOrNull { runCatching { it.metadata != null }.getOrDefault(false) }
        ?: controllers.firstOrNull()

/**
 * Reuses a previously loaded value only when the provider supplied a known, matching key and
 * the previous load actually produced a value. A missing value must remain retryable because
 * providers can publish the metadata before the corresponding bitmap becomes available.
 */
internal inline fun <Key, Value : Any> readCachedMediaArtwork(
    key: Key?,
    previousKey: Key?,
    previousValue: Value?,
    read: () -> Value?,
): Value? = if (key != null && key == previousKey && previousValue != null) {
    previousValue
} else {
    read()
}

private fun readMediaSnapshot(
    controller: MediaController,
    previousSnapshot: MediaSnapshot? = null,
): MediaSnapshot = runCatching {
    val metadata = controller.metadata
    val playback = controller.playbackState
    val duration = metadata
        ?.getLong(MediaMetadata.METADATA_KEY_DURATION)
        ?.takeIf { it > 0L }
        ?: 0L
    val semanticTitle = listOf(
        metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
        metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
    ).firstOrNull { !it.isNullOrBlank() }
    val title = semanticTitle ?: tr("再生中のメディア", "Now playing")
    val semanticArtist = listOf(
        metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
        metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
        metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
    ).firstOrNull { !it.isNullOrBlank() }
    val artist = semanticArtist ?: tr("メディア", "Media")
    val artworkKey = mediaArtworkSemanticKey(
        mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
        artUri = metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI),
        albumArtUri = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
        displayIconUri = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
        titleFallback = semanticTitle,
        artistFallback = semanticArtist,
        albumFallback = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
    )
    // Artwork is cached by session token. When the provider reports the same semantic key and a
    // non-null bitmap was previously loaded, reuse it without asking MediaMetadata for another
    // wrapper; metadata/playback fields are still read below and can update independently. A
    // previous null remains retryable because artwork can arrive after the metadata callback.
    // Providers without art identifiers use title/artist/album as a fallback fingerprint, so a
    // changed song re-reads its artwork. A completely empty semantic payload remains the
    // documented provider limitation: a changed Bitmap alone cannot be distinguished from a
    // wrapper copy.
    val albumArt = readCachedMediaArtwork(
        key = artworkKey,
        previousKey = previousSnapshot?.artworkKey,
        previousValue = previousSnapshot?.albumArt,
    ) {
        metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    }
    MediaSnapshot(
        title = title,
        artist = artist,
        albumArt = albumArt,
        artworkKey = artworkKey,
        position = playback?.position?.coerceAtLeast(0L) ?: 0L,
        duration = duration,
        positionUpdateTime = playback?.lastPositionUpdateTime?.coerceAtLeast(0L) ?: 0L,
        playbackSpeed = playback?.playbackSpeed?.takeIf { it.isFinite() } ?: 1f,
        isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
    )
}.getOrDefault(MediaSnapshot(title = tr("再生中のメディア", "Now playing")))

/**
 * Predicts a display-only media position from the last PlaybackState sample.
 *
 * MediaSession callbacks are intentionally not polled. The tile may still refresh its own small
 * progress ticker while playback is active, using the same elapsedRealtime clock as
 * PlaybackState.lastPositionUpdateTime. A duration of zero means that the provider did not expose
 * a seek range, so the prediction is left uncapped.
 */
internal fun mediaPositionAt(
    position: Long,
    duration: Long,
    positionUpdateTime: Long,
    playbackSpeed: Float,
    isPlaying: Boolean,
    nowElapsedRealtime: Long,
): Long {
    val basePosition = position.coerceAtLeast(0L)
    if (!isPlaying || positionUpdateTime <= 0L) {
        return if (duration > 0L) basePosition.coerceAtMost(duration) else basePosition
    }
    val elapsedMillis = (nowElapsedRealtime - positionUpdateTime).coerceAtLeast(0L)
    val speed = playbackSpeed.takeIf { it.isFinite() } ?: 1f
    val predicted = basePosition.toDouble() + elapsedMillis.toDouble() * speed.toDouble()
    val safePosition = if (predicted.isFinite()) {
        predicted.toLong().coerceAtLeast(0L)
    } else {
        basePosition
    }
    return if (duration > 0L) safePosition.coerceAtMost(duration) else safePosition
}

/** Converts one framework notification into the process-local snapshot used by HomeSurface. */
internal fun activeNotificationSnapshotOf(
    statusBarNotification: StatusBarNotification,
): ActiveNotificationSnapshot {
    val notification = statusBarNotification.notification
    val extras = notification.extras
    val title = notificationExtraText(extras, Notification.EXTRA_TITLE)
    val body = notificationExtraText(extras, Notification.EXTRA_BIG_TEXT)
        .ifBlank { notificationExtraText(extras, Notification.EXTRA_TEXT) }
    val metadata = NotificationMetadata(
        key = statusBarNotification.key,
        packageName = statusBarNotification.packageName,
        title = title,
        body = body,
        timestamp = statusBarNotification.postTime.coerceAtLeast(0L),
        clearable = statusBarNotification.isClearable,
        isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        isMediaTransport = notification.category == Notification.CATEGORY_TRANSPORT ||
            runCatching { extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true }
                .getOrDefault(false),
        isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0 ||
            notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
        isAutoCancel = notification.flags and Notification.FLAG_AUTO_CANCEL != 0,
    )
    return ActiveNotificationSnapshot(
        metadata = metadata,
        contentIntent = notification.contentIntent,
        profileUserId = statusBarNotification.user
            .takeUnless { it == android.os.Process.myUserHandle() }?.hashCode(),
    )
}

private fun notificationExtraText(
    extras: android.os.Bundle?,
    key: String,
): String = runCatching {
    extras
        ?.getCharSequence(key)
        ?.toString()
        ?.trim()
        .orEmpty()
}.getOrDefault("")

/**
 * Receives callbacks only; there is no polling or durable notification history. The active set
 * is rebuilt from [activeNotifications] on connection because callbacks posted during service
 * binding are not guaranteed to include the complete pre-existing set.
 */
class LauncherNotificationListenerService : NotificationListenerService() {
    companion object {
        private val serviceLock = Any()
        private var connectedService: LauncherNotificationListenerService? = null

        /** Best-effort cancellation for a current, clearable auto-cancel snapshot. */
        internal fun cancelActiveNotification(snapshot: ActiveNotificationSnapshot): Boolean {
            if (!snapshot.clearable || !snapshot.isAutoCancel) return false
            val service = synchronized(serviceLock) {
                connectedService?.takeIf { current ->
                    LauncherNotificationStore.state.value.snapshots.any {
                        it.key == snapshot.key && it.clearable && it.isAutoCancel
                    }
                }
            } ?: return false
            return runCatching {
                service.cancelNotification(snapshot.key)
                true
            }.getOrDefault(false)
        }
    }

    private fun isCurrentService(): Boolean = synchronized(serviceLock) {
        connectedService === this
    }

    private fun disconnectIfCurrent() {
        synchronized(serviceLock) {
            if (connectedService === this) {
                connectedService = null
                LauncherNotificationStore.disconnect()
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        synchronized(serviceLock) {
            connectedService = this
        }
        val current = runCatching {
            activeNotifications.orEmpty().mapNotNull { notification ->
                runCatching { activeNotificationSnapshotOf(notification) }
                    .getOrNull()
            }
        }.getOrElse {
            disconnectIfCurrent()
            return
        }
        LauncherNotificationStore.connect(current)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isCurrentService()) {
            // A malformed Bundle/Parcelable from one provider must not escape the framework
            // callback and tear down the listener. Drop only that record and keep the set alive.
            runCatching { activeNotificationSnapshotOf(sbn) }
                .onSuccess { snapshot -> LauncherNotificationStore.upsert(snapshot) }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (isCurrentService()) {
            LauncherNotificationStore.remove(sbn.key)
        }
    }

    override fun onListenerDisconnected() {
        disconnectIfCurrent()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        disconnectIfCurrent()
        super.onDestroy()
    }
}
