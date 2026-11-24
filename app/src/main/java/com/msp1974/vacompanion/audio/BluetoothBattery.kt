package com.msp1974.vacompanion.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * The battery level Android knows for the connected Bluetooth speaker (or headset), as speakers
 * report it the standard way (HFP or AVRCP; AVRCP is what most speakers use). Many don't (a JBL
 * Flip 6 doesn't; a Flip 4 reports its level only in a CSR command Android ignores), so the level
 * is often unknown; the name is reported anyway.
 */
class BluetoothBattery(private val context: Context, private val onChange: () -> Unit) {

    data class Reading(val name: String, val level: Int?)

    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onChange()
        }
        runCatching {
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(ACTION_BATTERY_LEVEL_CHANGED), ContextCompat.RECEIVER_EXPORTED
            )
        }.onFailure { Timber.w("Bluetooth battery: receiver not registered: ${it.message}") }
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    /** The connected Bluetooth speaker and its level, or null if none is connected. */
    @SuppressLint("MissingPermission")
    fun read(): Reading? {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val output = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER }
            ?: return null
        val name = output.productName?.toString().orEmpty()
        val level = runCatching {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val device: BluetoothDevice = adapter.getRemoteDevice(output.address)
            // Not public before Android 13, but present (and used by Settings) on every version.
            (BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as Int)
        }.getOrNull()?.takeIf { it in 0..100 }
        return Reading(name, level)
    }

    companion object {
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    }
}
