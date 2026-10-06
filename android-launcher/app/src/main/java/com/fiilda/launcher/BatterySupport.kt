package com.fiilda.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

internal enum class BatteryPowerSource(private val jaLabel: String, private val enLabel: String) {
    AC("AC電源", "AC"),
    USB("USB", "USB"),
    WIRELESS("ワイヤレス", "Wireless"),
    DOCK("ドック", "Dock"),
    ;

    val label: String get() = tr(jaLabel, enLabel)
}

internal data class BatteryStatus(
    val percent: Int,
    val charging: Boolean,
    val full: Boolean,
    val source: BatteryPowerSource?,
    val temperatureC: Float?,
    val healthLabel: String?,
    /** Estimated time to full while charging, as reported by the platform. */
    val chargeTimeRemainingMillis: Long?,
)

internal val UnknownBatteryStatus = BatteryStatus(
    percent = 0,
    charging = false,
    full = false,
    source = null,
    temperatureC = null,
    healthLabel = null,
    chargeTimeRemainingMillis = null,
)

internal fun batteryStatusFromExtras(
    level: Int,
    scale: Int,
    status: Int,
    plugged: Int,
    temperatureTenths: Int,
    health: Int,
    chargeTimeRemainingMillis: Long,
): BatteryStatus = BatteryStatus(
    percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0,
    charging = status == BatteryManager.BATTERY_STATUS_CHARGING,
    full = status == BatteryManager.BATTERY_STATUS_FULL,
    source = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> BatteryPowerSource.AC
        BatteryManager.BATTERY_PLUGGED_USB -> BatteryPowerSource.USB
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> BatteryPowerSource.WIRELESS
        BatteryManager.BATTERY_PLUGGED_DOCK -> BatteryPowerSource.DOCK
        else -> null
    },
    temperatureC = temperatureTenths.takeIf { it > 0 }?.let { it / 10f },
    healthLabel = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> tr("良好", "Good")
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> tr("高温", "Overheating")
        BatteryManager.BATTERY_HEALTH_COLD -> tr("低温", "Cold")
        BatteryManager.BATTERY_HEALTH_DEAD -> tr("劣化", "Degraded")
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> tr("過電圧", "Over voltage")
        else -> null
    },
    chargeTimeRemainingMillis = chargeTimeRemainingMillis.takeIf { it > 0L },
)

/** Short status line such as "充電中 · 満充電まで1時間20分" or "電源未接続". */
internal fun batteryStatusLabel(status: BatteryStatus): String = when {
    status.full || (status.source != null && status.percent >= 100) -> tr("充電完了", "Fully charged")
    status.charging -> {
        val remaining = status.chargeTimeRemainingMillis?.let { tr(" · 満充電まで${durationLabel(it)}", " · full in ${durationLabel(it)}") }.orEmpty()
        tr("充電中$remaining", "Charging$remaining")
    }
    status.source != null -> tr("接続中（充電停止）", "Plugged in (not charging)")
    else -> tr("電源未接続", "Not plugged in")
}

internal fun durationLabel(millis: Long): String {
    val totalMinutes = ((millis + 59_999L) / 60_000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0L -> tr("${minutes}分", "${minutes} min")
        minutes == 0L -> tr("${hours}時間", "${hours} h")
        else -> tr("${hours}時間${minutes}分", "${hours} h ${minutes} min")
    }
}

private fun Context.readBatteryStatus(intent: Intent?): BatteryStatus {
    intent ?: return UnknownBatteryStatus
    val manager = getSystemService(BatteryManager::class.java)
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    return batteryStatusFromExtras(
        level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
        scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
        status = status,
        plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
        temperatureTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
        health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, 0),
        chargeTimeRemainingMillis = if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
            manager?.computeChargeTimeRemaining() ?: -1L
        } else {
            -1L
        },
    )
}

/** Live battery state; the sticky broadcast is followed only while the launcher is resumed. */
@Composable
internal fun rememberBatteryStatus(): BatteryStatus {
    val context = LocalContext.current
    var status by remember(context) {
        mutableStateOf(
            context.readBatteryStatus(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))),
        )
    }
    LifecycleResumeEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                status = context.readBatteryStatus(intent)
            }
        }
        // Registering returns the sticky intent immediately, which also refreshes on resume.
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let { status = context.readBatteryStatus(it) }
        onPauseOrDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return status
}
