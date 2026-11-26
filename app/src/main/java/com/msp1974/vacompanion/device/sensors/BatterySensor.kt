package com.msp1974.vacompanion.device.sensors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.msp1974.vacompanion.device.info.DeviceHardware
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class BatterySensor(private val context: Context) : Sensor {

    companion object {
        /** Sent again this often even when unchanged, so HA can tell a live reading from a stale one. */
        private const val RESEND_INTERVAL_MS = 15 * 60_000L

        internal val basicSensor = Sensor.BasicSensor(
            "battery",
            type = -1, // Not a standard Android sensor type
            name = "Battery Sensor",
        )
    }

    val hasBattery = DeviceHardware(context).hardwareInfo.hasBattery

    override var onUpdate: ((String, Any) -> Unit)? = null
    private var lastLevel = -1f
    private var lastCharging = false
    private var resendJob: Job? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                processBatteryIntent(intent)
            }
        }
    }

    init {
        if (hasBattery) {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val stickyIntent = context.registerReceiver(batteryReceiver, intentFilter)
            // Process the initial sticky intent if available
            stickyIntent?.let { processBatteryIntent(it) }
            resendJob = Sensor.sensorWorkerScope.launch {
                while (isActive) {
                    delay(RESEND_INTERVAL_MS)
                    sendCurrent()
                }
            }
        }
    }

    /** Reads the battery now and sends it even if nothing changed. */
    private fun sendCurrent() {
        if (!hasBattery) return
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
            processBatteryIntent(it, force = true)
        }
    }

    override fun stop() {
        resendJob?.cancel()
        if (hasBattery) {
            try {
                context.unregisterReceiver(batteryReceiver)
            } catch (e: Exception) {
                Timber.e(e, "Error unregistering battery receiver")
            }
        }
    }

    private fun processBatteryIntent(intent: Intent, force: Boolean = false) {
        val level = intent.let { i ->
            val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (s > 0) l * 100 / s.toFloat() else 0f
        }

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val chargePlug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)

        val currentBatteryState = BatteryState(
            hasBattery = hasBattery,
            onBattery = chargePlug == 0,
            isCharging = isCharging,
            usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB,
            acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC,
            level = level
        )

        if (force || level != lastLevel || isCharging != lastCharging) {
            lastLevel = level
            lastCharging = isCharging
            Sensor.sensorWorkerScope.launch {
                onSensorUpdated(basicSensor.id, currentBatteryState)
            }
        }
    }

    override fun hasSensor(context: Context): Boolean {
        return hasBattery
    }

    override fun requiredPermissions(): Array<String> {
        return emptyArray()
    }

    override suspend fun getAvailableSensors(context: Context): List<Sensor.BasicSensor> {
        return if (hasBattery) listOf(basicSensor) else emptyList()
    }

    override suspend fun requestSensorUpdate(context: Context) {
        // Changes come from the BroadcastReceiver; this (on each connection to HA, and right after
        // onUpdate is wired) re-sends the current reading, which may have changed while HA was
        // away or been pushed before anyone listened.
        sendCurrent()
    }
}
