package com.msp1974.vacompanion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class EndOfSpeechDetectorTest {
    // 80 ms frames at 16 kHz.
    private fun frame(amplitude: Int) = ShortArray(1280) { (amplitude * sin(2 * PI * 300 * it / 16000)).toInt().toShort() }
    private val quiet = frame(30)      // about -60 dBFS
    private val speech = frame(6000)   // about -18 dBFS

    private fun feed(d: EndOfSpeechDetector, f: ShortArray, n: Int): List<EndOfSpeechDetector.Result> = List(n) { d.onFrame(f) }

    @Test
    fun shortReplyEndsAfterSilence() {
        val d = EndOfSpeechDetector()
        feed(d, quiet, 3)                      // noise floor
        val start = feed(d, speech, 4)         // "sim", 320 ms
        assertTrue(EndOfSpeechDetector.Result.SPEECH_STARTED in start)
        val tail = feed(d, quiet, 9)           // 720 ms of silence
        assertEquals(EndOfSpeechDetector.Result.SPEECH_ENDED, tail.last())
        assertFalse(EndOfSpeechDetector.Result.SPEECH_ENDED in tail.dropLast(1))
    }

    @Test
    fun silenceAloneNeverEnds() {
        val d = EndOfSpeechDetector()
        val all = feed(d, quiet, 50)
        assertTrue(all.all { it == EndOfSpeechDetector.Result.NONE })
        assertFalse(d.speechSeen)
    }

    @Test
    fun pausesShorterThanTheLimitDoNotEnd() {
        val d = EndOfSpeechDetector()
        feed(d, quiet, 3)
        feed(d, speech, 5)
        feed(d, quiet, 5)                      // 400 ms pause
        feed(d, speech, 5)
        val tail = feed(d, quiet, 8)           // 640 ms: not yet
        assertFalse(EndOfSpeechDetector.Result.SPEECH_ENDED in tail)
        assertEquals(EndOfSpeechDetector.Result.SPEECH_ENDED, d.onFrame(quiet))
    }

    @Test
    fun aSingleClickIsNotSpeech() {
        val d = EndOfSpeechDetector()
        feed(d, quiet, 3)
        d.onFrame(speech)
        feed(d, quiet, 20)
        assertFalse(d.speechSeen)
    }
}
