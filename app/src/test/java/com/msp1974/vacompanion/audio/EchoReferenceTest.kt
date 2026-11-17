package com.msp1974.vacompanion.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class EchoReferenceTest {
    private val frames = mutableListOf<ShortArray>()
    private val sink = EchoReference.Sink { frames.add(it) }

    @After
    fun tearDown() {
        EchoReference.removeSink(sink)
    }

    private fun stereoSine(rate: Int, seconds: Double, hz: Double, amplitude: Int): ByteBuffer {
        val n = (rate * seconds).toInt()
        val buf = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val v = (amplitude * sin(2 * PI * hz * i / rate)).toInt().toShort()
            buf.putShort(v).putShort(v)
        }
        buf.flip()
        return buf
    }

    @Test
    fun nothingIsDeliveredWithoutASink() {
        val buf = stereoSine(48000, 0.1, 440.0, 8000)
        EchoReference.queue(buf, 0, buf.limit(), 48000, 2, 1f)
        EchoReference.release(4800)
        assertTrue(frames.isEmpty())
    }

    @Test
    fun audioIsHeldUntilPresented() {
        EchoReference.addSink(sink)
        val buf = stereoSine(48000, 0.5, 440.0, 8000)
        EchoReference.queue(buf, 0, buf.limit(), 48000, 2, 1f)
        assertTrue(frames.isEmpty())

        // 100 ms presented at 48 kHz = 1600 samples at 16 kHz = 10 frames.
        EchoReference.release(4800)
        assertEquals(10, frames.size)
        assertTrue(frames.all { it.size == EchoReference.FRAME_SAMPLES })
    }

    @Test
    fun resampledLevelFollowsSourceAndGain() {
        EchoReference.addSink(sink)
        val buf = stereoSine(48000, 1.0, 440.0, 10000)
        EchoReference.queue(buf, 0, buf.limit(), 48000, 2, 0.5f)
        EchoReference.release(48000)
        val peak = frames.drop(2).flatMap { it.toList() }.maxOf { abs(it.toInt()) }
        // 10000 * 0.5, slightly reduced by the moving average at 440 Hz.
        assertTrue("peak $peak", peak in 4800..5000)
    }

    @Test
    fun resetDropsHeldAudio() {
        EchoReference.addSink(sink)
        val buf = stereoSine(44100, 0.2, 440.0, 8000)
        EchoReference.queue(buf, 0, buf.limit(), 44100, 2, 1f)
        EchoReference.reset()
        EchoReference.release(44100)
        assertTrue(frames.isEmpty())
    }
}
