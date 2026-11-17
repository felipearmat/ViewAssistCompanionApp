package com.msp1974.vacompanion.players

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.msp1974.vacompanion.audio.AudioOutputRouter
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber

/**
 * Loops a short sound while the assistant works out its answer: from the end of the request
 * (transcript received) until the reply starts or the interaction ends. It is a separate player
 * from [SoundEffectsPlayer] so it never cuts off, or is cut off by, the wake or error sound.
 *
 * Safe to call from any thread; ExoPlayer is only touched on the main thread. Stops by itself
 * after [MAX_DURATION_MS] in case the end of the wait is never reported.
 */
@SuppressLint("UnsafeOptInUsageError")
class ThinkingSoundPlayer(private val context: Context, private val config: APPConfig) {
    companion object {
        const val MAX_DURATION_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var playerUri: Uri? = null
    private val timeout = Runnable {
        Timber.w("Thinking sound still playing after ${MAX_DURATION_MS / 1000} s, stopping it")
        stopOnMain()
    }

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_NOTIFICATION)
        .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
        .build()

    fun start(uri: Uri) {
        handler.post {
            try {
                val p = player?.takeIf { playerUri == uri } ?: createPlayer(uri)
                if (p.playerError != null) p.prepare()
                p.setPreferredAudioDevice(AudioOutputRouter.resolvePreferredDevice(context, config))
                p.seekTo(0)
                p.play()
                handler.removeCallbacks(timeout)
                handler.postDelayed(timeout, MAX_DURATION_MS)
                Timber.d("Thinking sound started")
            } catch (e: Exception) {
                Timber.e(e, "Error starting thinking sound")
            }
        }
    }

    fun stop() {
        handler.post { stopOnMain() }
    }

    fun release() {
        handler.post {
            handler.removeCallbacks(timeout)
            player?.release()
            player = null
            playerUri = null
        }
    }

    private fun stopOnMain() {
        handler.removeCallbacks(timeout)
        player?.let {
            if (it.isPlaying) {
                it.pause()
                Timber.d("Thinking sound stopped")
            }
            it.seekTo(0)
        }
    }

    private fun createPlayer(uri: Uri): ExoPlayer {
        player?.release()
        return ExoPlayer.Builder(context).build().also { p ->
            p.setAudioAttributes(audioAttributes, false)
            p.setMediaItem(MediaItem.fromUri(uri))
            p.repeatMode = Player.REPEAT_MODE_ONE
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Timber.w(error, "Thinking sound player error for $uri")
                }
            })
            p.prepare()
            player = p
            playerUri = uri
        }
    }
}
