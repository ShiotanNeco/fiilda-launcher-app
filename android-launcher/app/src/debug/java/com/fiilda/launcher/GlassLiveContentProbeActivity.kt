package com.fiilda.launcher

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Debug-only live-content fixture for verifying the launcher's notification and shortcut tiles.
 * Every resource created here has the same private prefix and is removed by [cleanupFixture].
 */
class GlassLiveContentProbeActivity : ComponentActivity() {
    private var screenState by mutableStateOf(ProbeScreenState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        screenState = stateForIntent(intent)
        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = screenState.title,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    screenState.description?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Text(
                        text = "Glass QA live notification and dynamic shortcut fixture",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        screenState = stateForIntent(intent)
    }

    private fun stateForIntent(intent: Intent): ProbeScreenState {
        val operation = intent.getStringExtra(EXTRA_OPERATION).orEmpty()
        return when (operation) {
            OP_PUBLISH -> ProbeScreenState(title = publishFixture())
            OP_UPDATE -> ProbeScreenState(title = updateFixture())
            OP_CLEANUP -> ProbeScreenState(title = cleanupFixture())
            OP_TAP -> ProbeScreenState(
                title = "Glass QA action opened",
                description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty(),
            )
            else -> ProbeScreenState(
                title = "Glass live content probe\noperation: ${operation.ifBlank { "none" }}",
            )
        }
    }

    private fun publishFixture(): String {
        val shortcutManager = getSystemService(ShortcutManager::class.java)
        val notificationManager = getSystemService(NotificationManager::class.java)
        return runCatching {
            requireNotificationPermission()
            check(shortcutManager.addDynamicShortcuts(buildShortcuts())) {
                "ShortcutManager rejected fixture shortcuts"
            }
            ensureFixtureChannel(notificationManager)
            postNotifications(notificationManager, updated = false)
            "Published 3 dynamic shortcuts and 2 notifications"
        }.getOrElse { error ->
            val cleanupError = runCatching {
                removeFixtureResources(shortcutManager, notificationManager)
            }.exceptionOrNull()
            val cleanupStatus = cleanupError?.let {
                " Fixture cleanup failed: ${it.javaClass.simpleName}: ${it.message.orEmpty()}"
            } ?: " Fixture cleanup attempted."
            "Publish failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}.$cleanupStatus"
        }
    }

    private fun updateFixture(): String {
        val notificationManager = getSystemService(NotificationManager::class.java)
        return runCatching {
            requireNotificationPermission()
            ensureFixtureChannel(notificationManager)
            postNotifications(notificationManager, updated = true)
            "Updated 2 notifications"
        }.getOrElse { error ->
            "Update failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        }
    }

    private fun cleanupFixture(): String {
        val shortcutManager = getSystemService(ShortcutManager::class.java)
        val notificationManager = getSystemService(NotificationManager::class.java)
        return runCatching {
            removeFixtureResources(shortcutManager, notificationManager)
            "Cleaned up fixture shortcuts, notifications, and channel"
        }.getOrElse { error ->
            "Cleanup failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        }
    }

    private fun removeFixtureResources(
        shortcutManager: ShortcutManager,
        notificationManager: NotificationManager,
    ) {
        shortcutManager.removeDynamicShortcuts(SHORTCUT_IDS.toList())
        NOTIFICATION_IDS.forEach { notificationId ->
            notificationManager.cancel(FIXTURE_NOTIFICATION_TAG, notificationId)
        }
        notificationManager.deleteNotificationChannel(FIXTURE_CHANNEL_ID)
    }

    private fun buildShortcuts(): List<ShortcutInfo> = SHORTCUT_IDS.mapIndexed { index, id ->
        ShortcutInfo.Builder(this, id)
            .setShortLabel("Glass QA ${index + 1}")
            .setLongLabel("Glass QA shortcut ${index + 1}")
            .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher))
            .setIntent(
                actionIntent(
                    description = "dynamic shortcut ${index + 1}",
                    requestCode = 100 + index,
                ),
            )
            .setRank(index)
            .build()
    }

    private fun ensureFixtureChannel(notificationManager: NotificationManager) {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                FIXTURE_CHANNEL_ID,
                "Glass QA live content",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Debug-only FiiLDA Glass live notification fixture"
                setShowBadge(true)
            },
        )
    }

    private fun requireNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("POST_NOTIFICATIONS is not granted")
        }
    }

    private fun postNotifications(
        notificationManager: NotificationManager,
        updated: Boolean,
    ) {
        val texts = if (updated) UPDATED_NOTIFICATION_TEXTS else INITIAL_NOTIFICATION_TEXTS
        texts.forEachIndexed { index, text ->
            val notification = Notification.Builder(this, FIXTURE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(text.title)
                .setContentText(text.body)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        200 + index,
                        actionIntent(
                            description = "notification ${index + 1}",
                            requestCode = 200 + index,
                        ),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .setAutoCancel(true)
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build()
            notificationManager.notify(FIXTURE_NOTIFICATION_TAG, NOTIFICATION_IDS[index], notification)
        }
    }

    private fun actionIntent(description: String, requestCode: Int): Intent =
        Intent(this, GlassLiveContentProbeActivity::class.java).apply {
            action = "com.fiilda.launcher.action.GLASS_QA_TAP_$requestCode"
            putExtra(EXTRA_OPERATION, OP_TAP)
            putExtra(EXTRA_DESCRIPTION, description)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

    private data class NotificationText(
        val title: String,
        val body: String,
    )

    private data class ProbeScreenState(
        val title: String = "Glass live content probe",
        val description: String? = null,
    )

    companion object {
        private const val EXTRA_OPERATION = "operation"
        private const val EXTRA_DESCRIPTION = "description"
        private const val OP_PUBLISH = "publish"
        private const val OP_UPDATE = "update"
        private const val OP_CLEANUP = "cleanup"
        private const val OP_TAP = "tap"
        private const val FIXTURE_PREFIX = "fiilda_glass_qa_"
        private const val FIXTURE_CHANNEL_ID = "${FIXTURE_PREFIX}channel"
        private const val FIXTURE_NOTIFICATION_TAG = "${FIXTURE_PREFIX}notification"
        private const val NOTIFICATION_ONE_ID = 41_701
        private const val NOTIFICATION_TWO_ID = 41_702
        private val NOTIFICATION_IDS = intArrayOf(NOTIFICATION_ONE_ID, NOTIFICATION_TWO_ID)
        private val SHORTCUT_IDS = arrayOf(
            "${FIXTURE_PREFIX}shortcut_1",
            "${FIXTURE_PREFIX}shortcut_2",
            "${FIXTURE_PREFIX}shortcut_3",
        )
        private val INITIAL_NOTIFICATION_TEXTS = listOf(
            NotificationText("Glass QA live 1", "Initial notification one"),
            NotificationText("Glass QA live 2", "Initial notification two"),
        )
        private val UPDATED_NOTIFICATION_TEXTS = listOf(
            NotificationText("Glass QA updated 1", "Updated notification one"),
            NotificationText("Glass QA updated 2", "Updated notification two"),
        )
    }
}
