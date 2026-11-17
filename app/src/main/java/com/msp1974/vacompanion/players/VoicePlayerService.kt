package com.msp1974.vacompanion.players

import android.media.audiofx.LoudnessEnhancer
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.IBinder
import androidx.media3.common.util.UnstableApi
import com.msp1974.vacompanion.audio.AudioOutputRouter
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject


@UnstableApi
@AndroidEntryPoint
class VoicePlayerService : Service(), EventListener {

    @Inject
    lateinit var deviceManager: DeviceManager

    private val config get() = deviceManager.config

    private lateinit var audioManager: AudioManager
    private var mediaPlayer: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    var hasAudioFocus = false

    var isReady = false
    var isPlaying = false

    companion object {
        var sInstance: VoicePlayerService? = null
        const val DEFAULT_RATE = 22050
        const val DEFAULT_CHANNELS = 1
        const val DEFAULT_WIDTH = 2
    }

    val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun onCreate() {
        super.onCreate()
        sInstance = this
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        config.eventBroadcaster.addListener(this)
    }

    override fun onEventTriggered(event: Event) {
        if (event.eventName == "audioOutputId") {
            mediaPlayer?.setPreferredDevice(AudioOutputRouter.resolvePreferredDevice(this, config))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        requestAudioFocus()
        return START_NOT_STICKY
    }

    private fun createPlayer(rate: Int, width: Int, channels: Int ): AudioTrack {
        val channels = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val encoding = if (width == 2) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(rate)
            .setChannelMask(channels)
            .setEncoding(encoding)
            .build()

        return AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(
                    rate,
                    channels,
                    encoding
                )
            )
            .build()
            .apply { setPreferredDevice(AudioOutputRouter.resolvePreferredDevice(this@VoicePlayerService, config)) }
    }

    fun start(rate: Int, width: Int, channels: Int) {
        Timber.d("Playing voice audio")
        // Each AudioTrack holds buffer memory from a small per-process pool in AudioFlinger, so
        // a track replaced without release() leaks that memory until GC finalises it - enough
        // of them and the music player can no longer create its own track (error -12).
        releaseTrack()
        mediaPlayer = createPlayer(rate, width, channels)
        mediaPlayer?.setVolume(1.0f)
        applyVoiceGain()

        try {
            mediaPlayer?.play()
            isReady = true
        } catch (e: Exception) {
            Timber.e("Error playing voice audio: $e")
        }
    }

    fun writeAudio(buffer: ByteArray) {
        if (!isReady) {
            Timber.w("Sending voice audio to non ready player")
            return
        }
        try {
            isPlaying = true
            val writeResult = mediaPlayer?.write(buffer, 0, buffer.size) ?: 0
            if (writeResult < 0) {
                Timber.w("AudioTrack write failed with code $writeResult")
            }
        } catch (e: Exception) {
            Timber.e("Error writing voice audio: $e")
        }
    }

    fun stop(force: Boolean) {
        Timber.d("Stopping voice audio...")
        mediaPlayer?.let { track ->
            try {
                if (force) {
                    track.pause()
                    track.flush()
                } else {
                    track.stop()
                }
            } catch (e: Exception) {
                Timber.w("Error stopping voice audio: ${e.message}")
            }
            // Always release, even if pause/flush/stop threw - otherwise the track (and its
            // buffer memory) is orphaned.
            releaseTrack()
            abandonAudioFocus()
        }
    }

    // Extra loudness for the assistant's voice (voice_gain, dB), so a TTS voice that already
    // peaks at full scale can match louder music. Android's LoudnessEnhancer compresses as it
    // boosts, so the voice doesn't clip.
    private var loudness: LoudnessEnhancer? = null

    private fun applyVoiceGain() {
        val gainDb = config.voiceGainDb
        if (gainDb <= 0) return
        val track = mediaPlayer ?: return
        try {
            loudness = LoudnessEnhancer(track.audioSessionId).apply {
                setTargetGain(gainDb * 100) // millibels
                enabled = true
            }
        } catch (e: Exception) {
            Timber.w("Voice gain of $gainDb dB not available: ${e.message}")
        }
    }

    private fun releaseTrack() {
        loudness?.let { runCatching { it.release() } }
        loudness = null
        isReady = false
        isPlaying = false
        val track = mediaPlayer ?: return
        mediaPlayer = null
        try {
            track.release()
        } catch (e: Exception) {
            Timber.w("Error releasing voice audio track: ${e.message}")
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun requestAudioFocus(): Boolean {
        @SuppressLint("UnsafeOptInUsageError")
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { focusChange ->
                Timber.d("Voice onAudioFocusChanged: $focusChange")
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        hasAudioFocus = true
                    }

                    AudioManager.AUDIOFOCUS_LOSS -> {
                        hasAudioFocus = false
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        hasAudioFocus = false
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        hasAudioFocus = false
                        mediaPlayer?.setVolume(0.2f)
                    }
                }
            }
            .build()

        val result = audioManager.requestAudioFocus(focusRequest!!)

        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Timber.d("Voice requestAudioFocus: $result")
        return hasAudioFocus
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun abandonAudioFocus() {
        if (hasAudioFocus) audioManager.abandonAudioFocusRequest(focusRequest!!)
        hasAudioFocus = false
        Timber.d("Voice abandonAudioFocus")
    }

    override fun onDestroy() {
        config.eventBroadcaster.removeListener(this)
        stop(true)
        abandonAudioFocus()
        sInstance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

}
