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
 * Sound played while the assistant works out its answer: from the end of the request
 * (transcript received) until the reply starts or the interaction ends.
 *
 * Nothing plays if the answer comes within the start delay, so quick answers stay silent. After
 * it, an optional intro sound (e.g. a spoken "let me think...") plays once, then the loop sound
 * repeats, for at most [MAX_DURATION_MS]. It is a separate player from [SoundEffectsPlayer] so it
 * never cuts off, or is cut off by, the wake or error sound.
 *
 * Safe to call from any thread; ExoPlayer is only touched on the main thread.
 */
@SuppressLint("UnsafeOptInUsageError")
class ThinkingSoundPlayer(private val context: Context, private val config: APPConfig) {
    companion object {
        const val MAX_DURATION_MS = 35_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    private var pendingStart: Runnable? = null
    private val timeout = Runnable {
        Timber.w("Thinking sound still playing after ${MAX_DURATION_MS / 1000} s, stopping it")
        stopOnMain()
    }

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_NOTIFICATION)
        .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * Plays [intro] once (if given) then repeats [loop], after [delayMs]. A [stop] before the
     * delay is over cancels it without any sound.
     */
    fun start(loop: Uri, intro: Uri? = null, delayMs: Long = 0) {
        handler.post {
            cancelPending()
            val run = Runnable {
                pendingStart = null
                play(loop, intro)
            }
            pendingStart = run
            handler.postDelayed(run, delayMs.coerceAtLeast(0))
        }
    }

    fun stop() {
        handler.post { stopOnMain() }
    }

    fun release() {
        handler.post {
            stopOnMain()
            player?.release()
            player = null
        }
    }

    private fun play(loop: Uri, intro: Uri?) {
        try {
            val p = player ?: createPlayer().also { player = it }
            val items = listOfNotNull(intro, loop).map { MediaItem.fromUri(it) }
            // With an intro, repeat only once the loop item is reached (see the listener).
            p.repeatMode = if (intro == null) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            p.setMediaItems(items)
            p.setPreferredAudioDevice(AudioOutputRouter.resolvePreferredDevice(context, config))
            p.prepare()
            p.play()
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, MAX_DURATION_MS)
            Timber.d("Thinking sound started (intro=${intro != null})")
        } catch (e: Exception) {
            Timber.e(e, "Error starting thinking sound")
        }
    }

    private fun cancelPending() {
        pendingStart?.let { handler.removeCallbacks(it) }
        pendingStart = null
    }

    private fun stopOnMain() {
        cancelPending()
        handler.removeCallbacks(timeout)
        player?.let {
            if (it.isPlaying) Timber.d("Thinking sound stopped")
            it.stop()
            it.clearMediaItems()
        }
    }

    private fun createPlayer(): ExoPlayer =
        ExoPlayer.Builder(context).build().also { p ->
            p.setAudioAttributes(audioAttributes, false)
            p.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // Reached the last item (the loop sound): keep repeating it.
                    if (p.currentMediaItemIndex == p.mediaItemCount - 1) {
                        p.repeatMode = Player.REPEAT_MODE_ONE
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Timber.w(error, "Thinking sound player error")
                }
            })
        }
}
