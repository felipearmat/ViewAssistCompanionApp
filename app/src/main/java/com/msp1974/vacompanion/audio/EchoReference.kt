package com.msp1974.vacompanion.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet

/**
 * What this device is playing, as the echo canceller's reference ("far end"): 16 kHz mono,
 * in 10 ms frames, delivered when it is actually presented by the speaker.
 *
 * A player calls [queue] with the PCM it hands to its AudioTrack, then [release] with the number
 * of frames the track reports as presented. Audio is held in between, so the reference lines up
 * with what the microphone hears (AudioTrack buffers can be 250 ms or more) and the canceller's
 * own delay estimation only has to cover the speaker-to-microphone path. [reset] drops what is
 * held, for a flush or pause.
 *
 * Only one stream is tracked (Sendspin). Nothing is converted or kept while no one listens.
 */
object EchoReference {
    const val SAMPLE_RATE = 16000
    const val FRAME_SAMPLES = 160

    /** Receives 10 ms reference frames at 16 kHz. Called on the player's threads. */
    fun interface Sink {
        fun onRender(frame: ShortArray)
    }

    private val sinks = CopyOnWriteArraySet<Sink>()
    private val lock = Any()

    // Converted audio waiting to be presented, in source frames and in 16 kHz samples.
    private val held = ArrayDeque<ShortArray>()
    private var heldOffset = 0
    private var heldSamples = 0

    // Resampler state.
    private var sourceRate = 0
    private var position = 0.0 // next output sample position, in source frames
    private var last = 0f      // previous (filtered) source sample, for interpolation
    // Moving average over about one output sample's worth of source samples: a crude low-pass
    // so music above 8 kHz doesn't fold back into the reference when downsampling.
    private var avg = FloatArray(1)
    private var avgPos = 0
    private var avgSum = 0f
    private var pendingRelease = 0.0

    // Partial output frame waiting to reach FRAME_SAMPLES.
    private val frame = ShortArray(FRAME_SAMPLES)
    private var frameFill = 0

    /** Hold at most this much unpresented audio (16 kHz samples); more means it isn't playing. */
    private const val MAX_HELD_SAMPLES = SAMPLE_RATE * 2

    val isActive: Boolean get() = sinks.isNotEmpty()

    fun addSink(sink: Sink) { sinks.add(sink) }

    fun removeSink(sink: Sink) {
        sinks.remove(sink)
        if (sinks.isEmpty()) reset()
    }

    /**
     * PCM 16-bit little-endian, [channels] interleaved, at [rate] Hz: [length] bytes of [buffer]
     * from [offset] (absolute; the buffer's position is not changed). [gain] is the volume the
     * player applies to it.
     */
    fun queue(buffer: ByteBuffer, offset: Int, length: Int, rate: Int, channels: Int, gain: Float) {
        if (!isActive || length <= 0 || channels <= 0 || rate <= 0) return
        val src = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frames = length / (2 * channels)
        synchronized(lock) {
            if (rate != sourceRate) {
                resetLocked()
                sourceRate = rate
                avg = FloatArray(maxOf(1, Math.round(rate.toDouble() / SAMPLE_RATE).toInt()))
            }
            val step = rate.toDouble() / SAMPLE_RATE
            // Outputs this block yields: positions position, position+step, ... below frames.
            val out = ShortArray(maxOf(0, kotlin.math.ceil((frames - position) / step).toInt()))
            var n = 0
            var prev = last
            var p = position
            for (i in 0 until frames) {
                var sum = 0
                val base = offset + i * 2 * channels
                for (c in 0 until channels) sum += src.getShort(base + c * 2).toInt()
                val raw = sum.toFloat() / channels * gain
                avgSum += raw - avg[avgPos]
                avg[avgPos] = raw
                avgPos = (avgPos + 1) % avg.size
                val cur = avgSum / avg.size
                // Linear interpolation between the previous and the current source sample.
                while (p <= i && n < out.size) {
                    val t = (p - (i - 1)).toFloat()
                    val v = prev + (cur - prev) * t
                    out[n++] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    p += step
                }
                prev = cur
            }
            last = prev
            position = p - frames
            if (n > 0) {
                held.addLast(if (n == out.size) out else out.copyOf(n))
                heldSamples += n
            }
            while (heldSamples > MAX_HELD_SAMPLES) dropHeadLocked(heldSamples - MAX_HELD_SAMPLES)
        }
    }

    /** [frames] source frames were presented: pass the matching audio to the sinks. */
    fun release(frames: Long) {
        if (!isActive || frames <= 0) return
        val ready = ArrayList<ShortArray>()
        synchronized(lock) {
            if (sourceRate == 0) return
            pendingRelease += frames.toDouble() * SAMPLE_RATE / sourceRate
            var count = pendingRelease.toInt()
            pendingRelease -= count
            while (count > 0 && held.isNotEmpty()) {
                val head = held.first()
                val take = minOf(count, head.size - heldOffset)
                for (i in 0 until take) {
                    frame[frameFill++] = head[heldOffset + i]
                    if (frameFill == FRAME_SAMPLES) {
                        ready.add(frame.copyOf())
                        frameFill = 0
                    }
                }
                count -= take
                heldSamples -= take
                heldOffset += take
                if (heldOffset == head.size) {
                    held.removeFirst()
                    heldOffset = 0
                }
            }
        }
        for (f in ready) sinks.forEach { it.onRender(f) }
    }

    fun reset() = synchronized(lock) { resetLocked() }

    private fun resetLocked() {
        held.clear()
        heldOffset = 0
        heldSamples = 0
        position = 0.0
        last = 0f
        pendingRelease = 0.0
        frameFill = 0
        avg.fill(0f)
        avgPos = 0
        avgSum = 0f
    }

    private fun dropHeadLocked(samples: Int) {
        var left = samples
        while (left > 0 && held.isNotEmpty()) {
            val head = held.first()
            val take = minOf(left, head.size - heldOffset)
            left -= take
            heldSamples -= take
            heldOffset += take
            if (heldOffset == head.size) {
                held.removeFirst()
                heldOffset = 0
            }
        }
    }
}
