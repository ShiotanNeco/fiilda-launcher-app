package com.fiilda.launcher

import android.bluetooth.BluetoothClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeripheralBatterySupportTest {
    @Test
    fun majorDeviceClassesMapToWidgetIcons() {
        assertEquals(PeripheralKind.AUDIO, peripheralKindFor(BluetoothClass.Device.Major.AUDIO_VIDEO))
        assertEquals(PeripheralKind.WATCH, peripheralKindFor(BluetoothClass.Device.Major.WEARABLE))
        assertEquals(PeripheralKind.COMPUTER, peripheralKindFor(BluetoothClass.Device.Major.COMPUTER))
        assertEquals(PeripheralKind.PHONE, peripheralKindFor(BluetoothClass.Device.Major.PHONE))
        assertEquals(PeripheralKind.INPUT, peripheralKindFor(BluetoothClass.Device.Major.PERIPHERAL))
        assertEquals(PeripheralKind.OTHER, peripheralKindFor(BluetoothClass.Device.Major.HEALTH))
        assertEquals(PeripheralKind.OTHER, peripheralKindFor(null))
    }

    @Test
    fun onlyDevicesReportingALevelAreShownInAStableOrder() {
        val shown = reportedPeripheralBatteries(
            listOf(
                PeripheralBattery("aa", "Watch", PeripheralKind.WATCH, 62),
                PeripheralBattery("bb", "Old buds", PeripheralKind.AUDIO, -1),
                PeripheralBattery("cc", "Laptop", PeripheralKind.COMPUTER, -100),
                PeripheralBattery("dd", "Buds", PeripheralKind.AUDIO, 80),
                PeripheralBattery("dd", "Buds", PeripheralKind.AUDIO, 80),
                PeripheralBattery("ee", "Keyboard", PeripheralKind.INPUT, 0),
            ),
        )
        assertEquals(listOf("dd", "aa", "ee"), shown.map { it.address })
    }

    @Test
    fun lowBatteryOnlyWhileDischargingAtOrBelowTwentyPercent() {
        assertTrue(isLowBattery(20, charging = false))
        assertTrue(isLowBattery(3, charging = false))
        assertFalse(isLowBattery(21, charging = false))
        assertFalse(isLowBattery(15, charging = true))
    }
}
