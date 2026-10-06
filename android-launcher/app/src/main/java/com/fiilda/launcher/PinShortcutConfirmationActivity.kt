package com.fiilda.launcher

import android.os.Bundle
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Receives Chrome and other publisher requests to create a launcher shortcut. This activity is
 * deliberately separate from MainActivity: the platform request is a one-shot Parcelable whose
 * lifecycle must not be coupled to the already-running home board.
 */
class PinShortcutConfirmationActivity : ComponentActivity() {
    private companion object {
        const val SavedAcceptedPinKey = "saved_accepted_pin"
        const val SavedAcceptedRecordKey = "saved_accepted_record"
    }

    private var pinItemRequest: LauncherApps.PinItemRequest? = null
    private var pendingRecord: PinnedShortcutRecord? = null
    private var hasHandledRequest = false
    private var platformAccepted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val restoredPlatformAccepted = savedInstanceState
            ?.getBoolean(SavedAcceptedPinKey, false) == true
        val restoredRecord = parsePinnedShortcutRecords(
            savedInstanceState?.getString(SavedAcceptedRecordKey),
        ).firstOrNull()
        val launcherApps = getSystemService(LauncherApps::class.java)
        val request = launcherApps?.let {
            runCatching { it.getPinItemRequest(intent) }.getOrNull()
        }
        val info = request?.let {
            runCatching { it.getShortcutInfo() }.getOrNull()
        }
        val requestRecord = info?.let { pinnedShortcutRecordFromShortcutInfo(this, it) }
        val record = if (restoredPlatformAccepted) restoredRecord else requestRecord
        val restoredIdentityMatchesRequest = !restoredPlatformAccepted ||
            requestRecord == null ||
            restoredRecord == null ||
            samePinnedShortcutPlatformIdentity(restoredRecord, requestRecord)
        val requestType = request?.let {
            runCatching { it.getRequestType() }.getOrDefault(-1)
        } ?: -1
        val requestIsValid = request?.let {
            runCatching { it.isValid() }.getOrDefault(false)
        } == true
        val requestState = if (restoredPlatformAccepted) {
            if (record != null && pinnedShortcutRecordIsValid(record)) {
                PinShortcutRequestState.READY
            } else {
                PinShortcutRequestState.INVALID
            }
        } else {
            pinShortcutRequestState(
                requestType = requestType,
                requestIsValid = requestIsValid,
                packageName = info?.let { runCatching { it.`package` }.getOrNull() },
                shortcutId = info?.let { runCatching { it.id }.getOrNull() },
                userSerial = record?.userSerial,
                label = record?.label,
            )
        }
        if (record == null ||
            requestState == PinShortcutRequestState.INVALID ||
            !restoredIdentityMatchesRequest ||
            (!restoredPlatformAccepted && (request == null || info == null))
        ) {
            finish()
            return
        }

        pinItemRequest = request
        pendingRecord = record
        platformAccepted = restoredPlatformAccepted
        val icon = shortcutIcon(info, record)
        setContent {
            FiiLDATheme {
                PinShortcutConfirmationDialog(
                    label = record.label,
                    icon = icon,
                    onConfirm = ::acceptPin,
                    onCancel = ::cancelPin,
                )
            }
        }
    }

    private fun shortcutIcon(info: ShortcutInfo?, record: PinnedShortcutRecord): Drawable? {
        val launcherApps = getSystemService(LauncherApps::class.java)
        return info?.let {
            runCatching {
                launcherApps?.getShortcutIconDrawable(
                    it,
                    resources.displayMetrics.densityDpi,
                )
            }.getOrNull()
        } ?: runCatching {
            packageManager.getApplicationIcon(record.packageName)
        }.getOrNull() ?: getDrawable(android.R.drawable.sym_def_app_icon)
    }

    private fun acceptPin() = synchronized(PinnedShortcutCleanupLock) {
        if (hasHandledRequest) return
        hasHandledRequest = true
        val request = pinItemRequest
        val record = pendingRecord
        val stillValid = request?.let { runCatching { it.isValid() }.getOrDefault(false) } == true
        val accepted = when {
            platformAccepted -> true
            !stillValid -> false
            else -> (request?.let {
                runCatching { it.accept() }.getOrDefault(false)
            } ?: false).also {
                platformAccepted = it
            }
        }
        val requestState = if (record == null) {
            PinShortcutRequestState.INVALID
        } else if (platformAccepted) {
            if (pinnedShortcutRecordIsValid(record)) {
                PinShortcutRequestState.READY
            } else {
                PinShortcutRequestState.INVALID
            }
        } else {
            pinShortcutRequestState(
                requestType = request?.let {
                    runCatching { it.getRequestType() }.getOrDefault(-1)
                } ?: -1,
                requestIsValid = stillValid,
                packageName = record.packageName,
                shortcutId = record.shortcutId,
                userSerial = record.userSerial,
                label = record.label,
            )
        }
        if (!shouldPersistAcceptedPin(requestState, accepted) || record == null) {
            if (accepted) Toast.makeText(
                this,
                tr("ショートカットを保存できませんでした", "Couldn't save the shortcut"),
                Toast.LENGTH_SHORT,
            ).show()
            else Toast.makeText(
                this,
                tr("ショートカットを追加できませんでした", "Couldn't add the shortcut"),
                Toast.LENGTH_SHORT,
            ).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        if (!persistAcceptedPinnedShortcut(this, record)) {
            // accept() already changed the platform pin state. Keep this request visible so the
            // user can retry the local commit while the accepted request is still on screen.
            hasHandledRequest = false
            Toast.makeText(
                this,
                tr("ホーム配置を保存できませんでした。もう一度お試しください", "Couldn't save the Home layout. Please try again"),
                Toast.LENGTH_LONG,
            ).show()
            return
        } else {
            setResult(RESULT_OK)
        }
        finish()
    }

    private fun cancelPin() = synchronized(PinnedShortcutCleanupLock) {
        if (hasHandledRequest) return
        hasHandledRequest = true
        if (platformAccepted && pendingRecord != null) {
            if (persistAcceptedPinnedShortcut(this, pendingRecord!!)) {
                setResult(RESULT_OK)
                finish()
            } else {
                hasHandledRequest = false
                Toast.makeText(
                    this,
                    tr("ホーム配置を保存できませんでした。もう一度お試しください", "Couldn't save the Home layout. Please try again"),
                    Toast.LENGTH_LONG,
                ).show()
            }
        } else {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (platformAccepted) {
            outState.putBoolean(SavedAcceptedPinKey, true)
            pendingRecord?.let { record ->
                outState.putString(
                    SavedAcceptedRecordKey,
                    serializePinnedShortcutRecords(listOf(record)),
                )
            }
        }
        super.onSaveInstanceState(outState)
    }
}

@Composable
private fun PinShortcutConfirmationDialog(
    label: String,
    icon: Drawable?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
                        setImageDrawable(icon)
                    }
                },
                modifier = Modifier.size(48.dp),
            )
        },
        title = { Text(tr("ホーム画面に追加", "Add to Home")) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                Text(tr("「$label」をホーム画面に追加しますか？", "Add \"$label\" to Home?"))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(tr("追加", "Add"))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(tr("キャンセル", "Cancel"))
            }
        },
    )
}
