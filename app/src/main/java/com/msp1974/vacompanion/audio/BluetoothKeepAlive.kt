package com.msp1974.vacompanion.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.msp1974.vacompanion.settings.APPConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.random.Random

/**
 * Keeps a Bluetooth speaker awake (bt_keep_alive): while one is connected, a continuous stream of
 * very faint noise (about -60 dBFS, inaudible in a room) is played to it. That keeps the Bluetooth
 * audio stream open, so the speaker never sees "nothing playing" and doesn't switch itself off
 * (a JBL Flip 4 does after ~20-30 min), and the start of each sound isn't lost while the stream
 * reopens. Periodic pulses and a 25 Hz tone didn't keep a Flip 4 on; a continuous stream did.
 * It can't wake a speaker that is already off: once the speaker connects again, it resumes.
 */
class BluetoothKeepAlive(
    private val context: Context,
    private val config: APPConfig,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val speaker = if (config.btKeepAlive) bluetoothSpeaker() else null
                if (speaker == null) {
                    delay(CHECK_MS)
                    continue
                }
                runCatching { stream(speaker) }.onFailure { Timber.w(it, "Bluetooth keep-alive stream failed") }
                delay(1_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun bluetoothSpeaker(): AudioDeviceInfo? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER }
    }

    /** Plays to [speaker] until it disconnects, the setting is turned off or the job ends. */
    private suspend fun stream(speaker: AudioDeviceInfo) {
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_SAMPLES * 4))
            .build()
        Timber.i("Bluetooth keep-alive: streaming faint noise to ${speaker.productName}")
        try {
            AudioOutputRouter.routeIfNeeded(track, speaker)
            track.play()
            // Not digital silence, which some speakers treat as nothing playing.
            val amplitude = 32767 * NOISE_LEVEL
            val chunk = ShortArray(CHUNK_SAMPLES)
            var checkedAt = 0L
            while (currentCoroutineContext().isActive) {
                for (i in chunk.indices) chunk[i] = ((Random.nextFloat() * 2 - 1) * amplitude).toInt().toShort()
                if (track.write(chunk, 0, chunk.size) < 0) break // blocks for about a chunk's length
                val now = System.currentTimeMillis()
                if (now - checkedAt > CHECK_MS) {
                    checkedAt = now
                    val current = if (config.btKeepAlive) bluetoothSpeaker() else null
                    if (current?.id != speaker.id) break
                }
            }
        } finally {
            Timber.i("Bluetooth keep-alive: stream stopped")
            runCatching { track.stop() }
            track.release()
        }
    }

    companion object {
        private const val CHECK_MS = 5_000L
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = 1_600 // 100 ms
        private const val NOISE_LEVEL = 0.001f // about -60 dBFS, as the Orange Pi's jbl-bt-aberto
    }
}
