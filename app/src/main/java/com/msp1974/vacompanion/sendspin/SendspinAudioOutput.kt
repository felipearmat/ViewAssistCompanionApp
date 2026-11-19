package com.msp1974.vacompanion.sendspin

import com.msp1974.vacompanion.audio.EchoReference
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * AudioTrack output for the Sendspin player. sendspin-cpp owns all scheduling: this only writes
 * the PCM it is handed and reports how many frames have actually been presented, so the library
 * can keep playback aligned with the server clock.
 */
class SendspinAudioOutput {

    /** Frames presented since the previous [pollPlayed], and when (CLOCK_MONOTONIC, µs). */
    class Played(val frames: Long, val atUs: Long)

    private val lock = Any()
    private var track: AudioTrack? = null

    @Volatile
    var isStarted = false
        private set

    private var sampleRate = 0
    private var channels = 0
    private var bytesPerFrame = 0

    // Presented-frame bookkeeping, reset whenever the track is flushed.
    private var lastPresented = 0L
    private var headFrames = 0L
    private var lastHead = 0L
    private val timestamp = AudioTimestamp()

    @Volatile
    private var gain = 1f

    @Volatile
    private var preferredDevice: AudioDeviceInfo? = null

    /** (Re)starts output for a new stream. Reuses the track if the format is unchanged. */
    fun start(sampleRate: Int, channels: Int, bitDepth: Int): Boolean = synchronized(lock) {
        if (bitDepth != 16) {
            Timber.e("Sendspin: unsupported bit depth $bitDepth")
            return false
        }
        val channelMask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> {
                Timber.e("Sendspin: unsupported channel count $channels")
                return false
            }
        }

        val existing = track
        if (existing != null && this.sampleRate == sampleRate && this.channels == channels &&
            existing.state == AudioTrack.STATE_INITIALIZED
        ) {
            try {
                existing.pause()
                existing.flush()
                resetProgress()
                existing.setVolume(gain)
                existing.play()
                isStarted = true
                return true
            } catch (e: Exception) {
                Timber.w(e, "Sendspin: reusing AudioTrack failed, recreating")
            }
        }
        releaseLocked()

        val frameBytes = channels * 2
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            Timber.e("Sendspin: unsupported output format rate=$sampleRate channels=$channels")
            return false
        }
        // Deep enough to ride out scheduling jitter, shallow enough that gain changes stay prompt.
        val bufferBytes = maxOf(minBuffer * 2, sampleRate * BUFFER_FLOOR_MS / 1000 * frameBytes)

        val newTrack = try {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                bufferBytes,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        } catch (e: Exception) {
            Timber.e(e, "Sendspin: creating AudioTrack failed")
            return false
        }
        if (newTrack.state != AudioTrack.STATE_INITIALIZED) {
            Timber.e("Sendspin: AudioTrack failed to initialise")
            newTrack.release()
            return false
        }

        this.sampleRate = sampleRate
        this.channels = channels
        this.bytesPerFrame = frameBytes
        newTrack.setPreferredDevice(preferredDevice)
        newTrack.setVolume(gain)
        resetProgress()
        newTrack.play()
        track = newTrack
        isStarted = true
        Timber.d("Sendspin: output started rate=$sampleRate channels=$channels buffer=$bufferBytes")
        true
    }

    /**
     * Writes up to [length] bytes of [buffer], waiting at most [timeoutMs]. Returns the bytes
     * consumed - always a whole number of frames, as the library counts played frames from it -
     * and advances the buffer position by the same amount so the remainder is resent.
     */
    fun write(buffer: ByteBuffer, length: Int, timeoutMs: Int): Int {
        val t = track
        if (!isStarted || t == null || length <= 0) {
            // Nothing to play into: don't let the library's sync thread spin.
            sleepQuietly(minOf(timeoutMs, IDLE_WAIT_MS).toLong())
            return 0
        }
        val startPosition = buffer.position()
        val end = startPosition + length.coerceAtMost(buffer.remaining())
        buffer.limit(end)

        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceIn(1, MAX_WRITE_WAIT_MS)
        while (buffer.position() < end && isStarted) {
            val written = try {
                t.write(buffer, end - buffer.position(), AudioTrack.WRITE_NON_BLOCKING)
            } catch (e: Exception) {
                Timber.e(e, "Sendspin: AudioTrack write failed")
                markDead(t)
                break
            }
            if (written < 0) {
                Timber.w("Sendspin: AudioTrack write error $written")
                markDead(t)
                break
            }
            if (written == 0) {
                if (SystemClock.elapsedRealtime() >= deadline) break
                sleepQuietly(WRITE_RETRY_MS)
            }
        }

        var consumed = buffer.position() - startPosition
        val partialFrame = consumed % bytesPerFrame
        if (partialFrame != 0) {
            consumed -= partialFrame
            buffer.position(startPosition + consumed)
        }
        if (consumed > 0 && EchoReference.isActive) {
            EchoReference.queue(buffer, startPosition, consumed, sampleRate, channels, gain)
        }
        return consumed
    }

    /** Frames presented since the last call, or null if nothing new has been presented. */
    fun pollPlayed(): Played? = synchronized(lock) {
        val t = track ?: return null
        if (!isStarted) return null

        // The AudioTrack timestamp pairs a frame position with the exact moment it was
        // presented; the playback head is only a fallback for drivers that don't provide one.
        val presented: Long
        val atUs: Long
        if (runCatching { t.getTimestamp(timestamp) }.getOrDefault(false)) {
            presented = timestamp.framePosition
            atUs = timestamp.nanoTime / 1000
        } else {
            val head = t.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
            headFrames += (head - lastHead) and 0xFFFF_FFFFL
            lastHead = head
            presented = headFrames
            atUs = System.nanoTime() / 1000
        }
        // Bluetooth (A2DP) reports the presented position in bursts, ~90 ms apart on a Samsung
        // A20, which made the library lose and regain sync ten times a second (audible stutter).
        // Each reading gives the moment frame 0 was presented; that start time is smoothed, and
        // the position reported is where a steady clock from it says playback is now.
        val nowUs = System.nanoTime() / 1000
        val startUs = atUs - presented * 1_000_000L / sampleRate
        val smoothed = smoothedStartUs
        smoothedStartUs = if (smoothed == null || kotlin.math.abs(startUs - smoothed) > START_JUMP_US) {
            startUs.toDouble()
        } else {
            smoothed + (startUs - smoothed) * START_SMOOTHING
        }
        // Never further than a burst ahead of what the track reported, so a real stall (underrun)
        // still shows.
        val estimated = minOf(
            ((nowUs - smoothedStartUs!!) * sampleRate / 1_000_000.0).toLong(),
            presented + sampleRate * MAX_LEAD_MS / 1000,
        )
        val delta = estimated - lastPresented
        if (delta <= 0) return null
        lastPresented = estimated
        EchoReference.release(delta)
        Played(delta, nowUs)
    }

    // Smoothed moment (µs, CLOCK_MONOTONIC) frame 0 of the stream was presented; see pollPlayed.
    private var smoothedStartUs: Double? = null

    fun setGain(value: Float) {
        gain = value.coerceIn(0f, 1f)
        runCatching { track?.setVolume(gain) }
    }

    fun setPreferredDevice(device: AudioDeviceInfo?) {
        preferredDevice = device
        runCatching { track?.setPreferredDevice(device) }
    }

    /** Stops output and drops queued audio, keeping the track for the next stream. */
    fun pause() {
        synchronized(lock) {
            isStarted = false
            val t = track ?: return
            try {
                t.pause()
                t.flush()
                resetProgress()
            } catch (e: Exception) {
                Timber.w(e, "Sendspin: pausing AudioTrack failed, releasing it")
                releaseLocked()
            }
        }
    }

    fun release() {
        synchronized(lock) {
            isStarted = false
            releaseLocked()
        }
    }

    private fun resetProgress() {
        smoothedStartUs = null
        EchoReference.reset()
        lastPresented = 0
        headFrames = 0
        lastHead = 0
    }

    private fun markDead(t: AudioTrack) {
        synchronized(lock) {
            if (track === t) {
                isStarted = false
                releaseLocked()
            }
        }
    }

    private fun releaseLocked() {
        val t = track ?: return
        track = null
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.release() }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val BUFFER_FLOOR_MS = 250
        // Weight of each new reading in the smoothed start time, and the jump that resets it.
        private const val START_SMOOTHING = 0.02
        private const val START_JUMP_US = 400_000L
        private const val MAX_LEAD_MS = 150L
        private const val IDLE_WAIT_MS = 10
        private const val MAX_WRITE_WAIT_MS = 2000
        private const val WRITE_RETRY_MS = 3L
    }
}
