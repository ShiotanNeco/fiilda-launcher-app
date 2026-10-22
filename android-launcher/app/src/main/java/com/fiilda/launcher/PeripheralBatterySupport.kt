package com.fiilda.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Method

internal enum class PeripheralKind {
    AUDIO,
    WATCH,
    COMPUTER,
    PHONE,
    INPUT,
    OTHER,
}

internal data class PeripheralBattery(
    val address: String,
    val name: String,
    val kind: PeripheralKind,
    val percent: Int,
)

internal val PeripheralBatteryPermission: String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Manifest.permission.BLUETOOTH_CONNECT
    } else {
        Manifest.permission.BLUETOOTH
    }

internal fun peripheralKindFor(majorDeviceClass: Int?): PeripheralKind = when (majorDeviceClass) {
    BluetoothClass.Device.Major.AUDIO_VIDEO -> PeripheralKind.AUDIO
    BluetoothClass.Device.Major.WEARABLE -> PeripheralKind.WATCH
    BluetoothClass.Device.Major.COMPUTER -> PeripheralKind.COMPUTER
    BluetoothClass.Device.Major.PHONE -> PeripheralKind.PHONE
    BluetoothClass.Device.Major.PERIPHERAL -> PeripheralKind.INPUT
    else -> PeripheralKind.OTHER
}

/**
 * Keeps devices that report a level. Disconnected devices report -1 (unknown) or -100 (Bluetooth
 * off), so the level doubles as the connection check.
 */
internal fun reportedPeripheralBatteries(devices: List<PeripheralBattery>): List<PeripheralBattery> =
    devices
        .filter { it.percent in 0..100 }
        .distinctBy { it.address }
        .sortedWith(compareBy<PeripheralBattery> { it.kind.ordinal }.thenBy { it.name })

// Not in the public SDK, but it is the level Android shows in its own Bluetooth settings. If a
// release removes it, the widget shows the phone alone.
private val BatteryLevelMethod: Method? by lazy {
    runCatching { BluetoothDevice::class.java.getMethod("getBatteryLevel") }.getOrNull()
}

private const val ActionBatteryLevelChanged = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"

@SuppressLint("MissingPermission")
private fun readPeripheralBatteries(context: Context): List<PeripheralBattery> = runCatching {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    val method = BatteryLevelMethod
    if (adapter == null || method == null || !adapter.isEnabled) return@runCatching emptyList()
    reportedPeripheralBatteries(
        adapter.bondedDevices.orEmpty().mapNotNull { device ->
            val level = runCatching { method.invoke(device) as Int }.getOrNull() ?: return@mapNotNull null
            val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias else null
            PeripheralBattery(
                address = device.address,
                name = alias ?: device.name.orEmpty(),
                kind = peripheralKindFor(device.bluetoothClass?.majorDeviceClass),
                percent = level,
            )
        },
    )
}.getOrDefault(emptyList())

/** Connected Bluetooth devices with a known battery level; followed only while resumed. */
@Composable
internal fun rememberPeripheralBatteries(enabled: Boolean): List<PeripheralBattery> {
    val context = LocalContext.current
    var refreshToken by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(enabled) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                refreshToken++
            }
        }
        if (enabled) {
            val filter = IntentFilter().apply {
                addAction(ActionBatteryLevelChanged)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            runCatching { context.registerReceiver(receiver, filter) }
            refreshToken++
        }
        onPauseOrDispose { if (enabled) runCatching { context.unregisterReceiver(receiver) } }
    }
    val batteries by produceState(emptyList<PeripheralBattery>(), enabled, refreshToken) {
        value = if (enabled) withContext(Dispatchers.IO) { readPeripheralBatteries(context) } else emptyList()
    }
    return batteries
}
