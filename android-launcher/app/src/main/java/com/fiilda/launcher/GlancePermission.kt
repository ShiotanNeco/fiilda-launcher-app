package com.fiilda.launcher

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Runtime permission used by a built-in home tile. [granted] is rechecked on every resume. */
internal class GlancePermissionState(
    val granted: Boolean,
    val request: () -> Unit,
)

@Composable
internal fun rememberGlancePermission(permission: String): GlancePermissionState {
    val context = LocalContext.current
    var granted by remember(permission) { mutableStateOf(context.hasPermission(permission)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        // A denial without rationale means the system will not show the dialog again.
        val activity = context.findGlanceActivity()
        if (!result && activity != null && !activity.shouldShowRequestPermissionRationale(permission)) {
            context.openGlanceApplicationSettings()
        }
    }
    LifecycleResumeEffect(permission) {
        granted = context.hasPermission(permission)
        onPauseOrDispose { }
    }
    return GlancePermissionState(granted = granted, request = { launcher.launch(permission) })
}

internal fun Context.hasPermission(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findGlanceActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findGlanceActivity()
    else -> null
}

private fun Context.openGlanceApplicationSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:$packageName"),
    ).apply {
        if (this@openGlanceApplicationSettings !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(intent) }
}
