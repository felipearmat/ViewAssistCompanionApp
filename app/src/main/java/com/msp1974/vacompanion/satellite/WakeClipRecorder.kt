package com.msp1974.vacompanion.satellite

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Keeps the audio around each wake word detection and hands it over, with what came of the
 * detection, so the wake word model can be retrained on real detections.
 *
 * The audio is the same the wake word engine heard (after the app's own mic processing):
 * [PRE_SECONDS] before the detection, which holds the wake word itself, and [POST_SECONDS] after.
 * A clip is sent once both the audio after the detection is in and the outcome is known (the
 * pipeline ended, or another device answered), or after [OUTCOME_TIMEOUT_MS] with outcome
 * "unknown". Clips live only in memory until sent; nothing is written to the device.
 *
 * Outcomes: answered, no_speech (nothing was transcribed), error, cancelled (stopped or
 * interrupted), timed_out, other_device (another device won the arbitration), unknown.
 */
class WakeClipRecorder(
    private val scope: CoroutineScope,
    private val sampleRate: Int,
    private val send: (meta: JsonObject, wav: ByteArray) -> Unit,
) {
    companion object {
        const val PRE_SECONDS = 3
        const val POST_SECONDS = 1
        const val OUTCOME_TIMEOUT_MS = 60_000L
        private const val MAX_PENDING = 4
    }

    private class Clip(
        val eventId: Long,
        val detection: JsonObject,
        val audio: ShortArray,
        var postRemaining: Int,
    ) {
        var written: Int = audio.size - postRemaining
        var outcome: String? = null
        var transcript: String = ""
        var response: String = ""
        var error: String = ""
        var timeout: Job? = null
    }

    private val preSamples = PRE_SECONDS * sampleRate
    private val postSamples = POST_SECONDS * sampleRate
    private val ring = ShortArray(preSamples)
    private var ringPos = 0
    private var ringFilled = 0
    private val pending = LinkedHashMap<Long, Clip>()
    private val lock = Any()

    /** Every frame the wake word engine processes. */
    fun onAudio(frame: ShortArray) {
        val ready = mutableListOf<Clip>()
        synchronized(lock) {
            for (sample in frame) {
                ring[ringPos] = sample
                ringPos = (ringPos + 1) % preSamples
            }
            ringFilled = minOf(preSamples, ringFilled + frame.size)

            for (clip in pending.values) {
                if (clip.postRemaining <= 0) continue
                val n = minOf(clip.postRemaining, frame.size)
                System.arraycopy(frame, 0, clip.audio, clip.written, n)
                clip.written += n
                clip.postRemaining -= n
                if (clip.postRemaining == 0 && clip.outcome != null) ready.add(clip)
            }
            ready.forEach { pending.remove(it.eventId) }
        }
        ready.forEach { deliver(it) }
    }

    /** A wake word was detected; [detection] describes it (score, level, wake word...). */
    fun onDetected(eventId: Long, detection: JsonObject) {
        synchronized(lock) {
            if (pending.size >= MAX_PENDING) {
                val oldest = pending.keys.first()
                pending.remove(oldest)?.timeout?.cancel()
                Timber.w("Too many wake word clips waiting, dropped $oldest")
            }
            // Pre-roll: whatever the ring holds, oldest first, then room for the audio after.
            val pre = ringFilled
            val audio = ShortArray(pre + postSamples)
            val start = (ringPos - pre + preSamples) % preSamples
            for (i in 0 until pre) audio[i] = ring[(start + i) % preSamples]
            val clip = Clip(eventId, detection, audio, postSamples)
            clip.timeout = scope.launch {
                delay(OUTCOME_TIMEOUT_MS)
                finish(eventId, "unknown", force = true)
            }
            pending[eventId] = clip
        }
    }

    fun onTranscript(eventId: Long, text: String) = update(eventId) { it.transcript = text }

    fun onResponse(eventId: Long, text: String) = update(eventId) { it.response = text }

    fun onError(eventId: Long, text: String) = update(eventId) { it.error = text }

    /**
     * The detection's outcome is known; the clip is sent once its audio is complete, or at once
     * when [force]d (the audio after the detection may then be short).
     */
    fun finish(eventId: Long, outcome: String, force: Boolean = false) {
        var ready: Clip? = null
        synchronized(lock) {
            val clip = pending[eventId] ?: return
            if (clip.outcome == null) clip.outcome = outcome
            if (clip.postRemaining <= 0 || force) {
                pending.remove(eventId)
                ready = clip
            }
        }
        ready?.let { deliver(it) }
    }

    /** Outcome for a pipeline that ended, from how it ended and what was heard. */
    fun outcomeFor(reason: PipelineEndReason, eventId: Long): String {
        val clip = synchronized(lock) { pending[eventId] } ?: return "unknown"
        return when (reason) {
            PipelineEndReason.ERRORED -> if (clip.transcript.isBlank()) "no_speech" else "error"
            PipelineEndReason.TIMED_OUT -> "timed_out"
            PipelineEndReason.FORCE_STOPPED, PipelineEndReason.DUPLICATE_WAKEUP -> "cancelled"
            else -> if (clip.transcript.isBlank()) "no_speech" else "answered"
        }
    }

    fun clear() {
        synchronized(lock) {
            pending.values.forEach { it.timeout?.cancel() }
            pending.clear()
            ringFilled = 0
        }
    }

    private inline fun update(eventId: Long, block: (Clip) -> Unit) {
        synchronized(lock) { pending[eventId]?.let(block) }
    }

    private fun deliver(clip: Clip) {
        clip.timeout?.cancel()
        val samples = clip.written
        val meta = buildJsonObject {
            put("event_id", clip.eventId)
            put("detection", clip.detection)
            put("outcome", clip.outcome ?: "unknown")
            put("transcript", clip.transcript)
            put("response", clip.response)
            put("error", clip.error)
            put("sample_rate", sampleRate)
            put("pre_seconds", preSamplesOf(clip).toFloat() / sampleRate)
            put("duration", samples.toFloat() / sampleRate)
        }
        try {
            send(meta, toWav(clip.audio, samples))
            Timber.i("Sent wake word clip ${clip.eventId} (${clip.outcome}, ${samples / sampleRate.toFloat()} s)")
        } catch (e: Exception) {
            Timber.e(e, "Unable to send wake word clip ${clip.eventId}")
        }
    }

    // Number of pre-roll samples in a clip (the rest of the array is the audio after).
    private fun preSamplesOf(clip: Clip) = clip.audio.size - postSamples

    private fun toWav(audio: ShortArray, samples: Int): ByteArray {
        val dataBytes = samples * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
        }
        val data = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) data.putShort(audio[i])
        return ByteArrayOutputStream(44 + dataBytes).apply {
            write(header.array()); write(data.array())
        }.toByteArray()
    }
}
