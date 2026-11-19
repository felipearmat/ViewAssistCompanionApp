package com.msp1974.vacompanion.service

import kotlinx.coroutines.delay
import android.content.Context
import com.msp1974.vacompanion.broadcasts.VacaUdpBroadcast
import com.msp1974.vacompanion.btproxy.BluetoothProxyManager
import com.msp1974.vacompanion.data.AvailableAlarms
import com.msp1974.vacompanion.data.AvailableWakeSounds
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.sendspin.SendspinManager
import com.msp1974.vacompanion.service.rtsp.RtspServerManager
import com.msp1974.vacompanion.wakeword.AvailableWakeWords
import com.msp1974.vacompanion.wyoming.ServerState
import com.msp1974.vacompanion.wyoming.WyomingTCPServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import timber.log.Timber

internal class BackgroundTaskController (private val context: Context, val deviceManager: DeviceManager) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var server: WyomingTCPServer? = null
    private val config = deviceManager.config
    private val rtspServerManager = RtspServerManager(context, deviceManager)
    private val sendspinManager = SendspinManager(context, deviceManager)
    private val bluetoothProxyManager = BluetoothProxyManager(context, deviceManager)
    private val vacaUdpBroadcast = VacaUdpBroadcast(context, deviceManager)


    fun start() {
        // Runs for the lifetime of the background task, independent of whether a Wyoming/HA
        // satellite session is connected - the camera stream is useful on its own.
        rtspServerManager.start()
        sendspinManager.start()
        bluetoothProxyManager.start()
        vacaUdpBroadcast.start()

        server = object: WyomingTCPServer(context, deviceManager) {
            override fun onEvent(event: String, data: JsonObject) {
                Timber.d("BackgroundTask - Event: $event - $data")
            }
            override fun onState(state: ServerState, restartIfStopped: Boolean) {
                Timber.d("BackgroundTask - State: $state")
                when (state) {
                    ServerState.RUNNING -> {}
                    ServerState.STOPPED -> {
                        Timber.d("Wyoming server stopped. Restart: $restartIfStopped")
                        // Restart server
                        if (restartIfStopped) {
                            runServer()
                        } else if (!shuttingDown) {
                            // Stopped while the app keeps running (seen right after every app
                            // update on two phones: the satellite stayed off the network until
                            // the app was reopened by hand). Bring it back.
                            Timber.w("Wyoming server stopped unexpectedly, restarting in ${UNEXPECTED_STOP_RESTART_MS / 1000} s")
                            scope.launch {
                                delay(UNEXPECTED_STOP_RESTART_MS)
                                if (!shuttingDown) runServer()
                            }
                        }
                    }
                    ServerState.ERRORED -> {
                        restartOnError()
                    }
                    else -> {}
                }
            }
        }
        runServer()
        Timber.d("Background task initialisation completed")
    }

    fun restartOnError() {
        server?.let {
            it.stopServer()
            start()
        }
    }

    fun runServer() {
        // TODO: Implement a recovery process
        try {
            if (server != null && server?.state == ServerState.STOPPED) {
                scope.launch {
                    config.availableWakeWords = AvailableWakeWords(context).get()
                    config.availableAlarms = AvailableAlarms(context, deviceManager).get()
                    config.availableWakeSounds = AvailableWakeSounds(context, deviceManager).get()
                    server?.startServer()
                }
            } else {
                Timber.d("Server not setup or already running")
            }
        } catch (e: Exception) {
            Timber.e("Error starting server: ${e.message.toString()}")
        }
    }

    fun serverWatchDog() {
        // TODO: Add restart watchdog
    }

    @Volatile private var shuttingDown = false

    fun shutdown() {
        shuttingDown = true
        Timber.i("Shutting down")
        server?.stopServer()
        rtspServerManager.shutdown()
        sendspinManager.shutdown()
        bluetoothProxyManager.shutdown()
        vacaUdpBroadcast.shutdown()
    }

    companion object {
        private const val UNEXPECTED_STOP_RESTART_MS = 2_000L
    }
}
