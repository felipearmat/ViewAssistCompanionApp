package com.msp1974.vacompanion.satellite

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * Records the room for wake word training, when Home Assistant asks for it (action
 * `capture_ambient`, stopped by `capture_stop`). Mode "ambient": 30 s blocks for up to `minutes`.
 * Mode "phrase": a beep, then [PHRASE_MS] of speech, `count` times. Each block or phrase goes to HA
 * as an `ambient_chunk` custom event with a 16 kHz mono 16-bit WAV payload; the last one says
 * `last: true`. Wake words heard meanwhile are listed in their block and never start a pipeline.
 */
class AmbientCapture(
    private val scope: CoroutineScope,
    private val audio: Flow<ShortArray>,
    private val sampleRate: Int,
    private val beep: suspend () -> Unit,
    private val info: () -> JsonObject,
    private val send: (data: JsonObject, wav: ByteArray) -> Unit,
) {
    companion object {
        const val BLOCK_SECONDS = 30
        const val PHRASE_MS = 2_500L
        const val PHRASE_GAP_MS = 1_500L
        const val BEEP_SETTLE_MS = 400L
        const val MAX_MINUTES = 120
    }

    private var job: Job? = null
    @Volatile var sessionId: String? = null
        private set
    private var blockStartedAt = 0L
    private val detections = mutableListOf<Pair<Float, Float>>() // score, offset s

    val active: Boolean get() = job?.isActive == true

    fun start(session: String, minutes: Int, mode: String, count: Int) {
        stop()
        sessionId = session
        Timber.i("Ambient capture $session started: $mode, ${minutes} min, count $count")
        job = scope.launch {
            try {
                if (mode == "phrase") phrases(session, count.coerceIn(1, 100))
                else ambient(session, minutes.coerceIn(1, MAX_MINUTES))
            } finally {
                if (sessionId == session) sessionId = null
                Timber.i("Ambient capture $session ended")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** A wake word heard during the capture: noted in the current block. */
    @Synchronized
    fun onDetection(score: Float) {
        detections.add(score to (System.currentTimeMillis() - blockStartedAt) / 1000f)
    }

    private suspend fun ambient(session: String, minutes: Int) {
        val end = System.currentTimeMillis() + minutes * 60_000L
        val blockSamples = BLOCK_SECONDS * sampleRate
        var n = 0
        val block = ShortArrayBuilder()
        resetBlock()
        try {
            withTimeoutOrNull((end - System.currentTimeMillis()).milliseconds) {
                audio.collect { frame ->
                    block.add(frame)
                    if (block.size >= blockSamples) {
                        sendBlock(session, "ambient", n++, block.take(), last = false)
                    }
                }
            }
        } finally {
            sendBlock(session, "ambient", n, block.take(), last = true)
        }
    }

    private suspend fun phrases(session: String, count: Int) {
        val samples = (PHRASE_MS * sampleRate / 1000).toInt()
        var sent = 0
        try {
            for (n in 0 until count) {
                beep()
                delay(BEEP_SETTLE_MS)
                resetBlock()
                val block = ShortArrayBuilder()
                audio.takeWhile { block.size < samples }.collect { block.add(it) }
                sendBlock(session, "phrase", n, block.take(), last = n == count - 1)
                sent = n + 1
                if (n < count - 1) delay(PHRASE_GAP_MS)
            }
        } finally {
            // Cancelled part way: tell HA the session is over.
            if (sent < count) sendBlock(session, "phrase", sent, ShortArray(0), last = true)
        }
    }

    @Synchronized
    private fun resetBlock() {
        blockStartedAt = System.currentTimeMillis()
        detections.clear()
    }

    private fun sendBlock(session: String, mode: String, n: Int, pcm: ShortArray, last: Boolean) {
        val heard = synchronized(this) { detections.toList() }
        val started = blockStartedAt
        resetBlock()
        val data = buildJsonObject {
            put("session_id", session)
            put("mode", mode)
            put("n", n)
            put("started_at", Instant.ofEpochMilli(started).toString())
            put("duration_s", pcm.size.toFloat() / sampleRate)
            put("last", last)
            info().forEach { (k, v) -> put(k, v) }
            putJsonArray("detections") {
                heard.forEach { (score, offset) -> addJsonObject { put("score", score); put("offset_s", offset) } }
            }
        }
        try {
            send(data, toWav(pcm))
        } catch (e: Exception) {
            Timber.e(e, "Unable to send ambient chunk $n of $session")
        }
    }

    private fun toWav(pcm: ShortArray): ByteArray {
        val dataBytes = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
        }
        val data = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort(it) }
        return ByteArrayOutputStream(44 + dataBytes).apply { write(header.array()); write(data.array()) }.toByteArray()
    }

    /** Growing PCM buffer; take() returns its content and empties it. */
    private class ShortArrayBuilder {
        private var data = ShortArray(16_000)
        var size = 0
            private set

        fun add(frame: ShortArray) {
            if (size + frame.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + frame.size))
            System.arraycopy(frame, 0, data, size, frame.size)
            size += frame.size
        }

        fun take(): ShortArray = data.copyOf(size).also { size = 0 }
    }
}
