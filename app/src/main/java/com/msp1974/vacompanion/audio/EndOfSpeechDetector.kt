package com.msp1974.vacompanion.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * A simple, local end-of-speech detector for the listening stage, used when Home Assistant's
 * own voice activity detection misses a short reply ("yes") and so never says the speech
 * stopped: without it the device would stream until its listening timeout and the reply would
 * only be transcribed seconds later.
 *
 * Energy based: the quietest of the first [CALIBRATION_FRAMES] frames sets the noise floor;
 * [START_FRAMES] frames in a row [START_MARGIN_DB] above it are speech; after speech,
 * [END_SILENCE_MS] below [END_MARGIN_DB] over the floor ends it. Pure and frame-size agnostic.
 */
class EndOfSpeechDetector(private val sampleRate: Int = 16000) {
    companion object {
        const val CALIBRATION_FRAMES = 3
        const val START_FRAMES = 2
        const val START_MARGIN_DB = 10f
        const val END_MARGIN_DB = 6f
        const val END_SILENCE_MS = 700
        /** Speech is never quieter than this, whatever the floor. */
        const val MIN_SPEECH_DBFS = -50f
    }

    enum class Result { NONE, SPEECH_STARTED, SPEECH_ENDED }

    private var frames = 0
    private var floorDb = Float.MAX_VALUE
    private var loudRun = 0
    private var silentMs = 0
    var speechSeen = false
        private set
    private var ended = false

    fun onFrame(frame: ShortArray): Result {
        if (ended || frame.isEmpty()) return Result.NONE
        val db = dbfs(frame)
        frames++
        if (frames <= CALIBRATION_FRAMES) {
            floorDb = minOf(floorDb, db)
            return Result.NONE
        }
        val frameMs = frame.size * 1000 / sampleRate
        if (!speechSeen) {
            val loud = db >= maxOf(floorDb + START_MARGIN_DB, MIN_SPEECH_DBFS)
            loudRun = if (loud) loudRun + 1 else 0
            if (!loud) floorDb = minOf(floorDb, db)
            if (loudRun >= START_FRAMES) {
                speechSeen = true
                return Result.SPEECH_STARTED
            }
            return Result.NONE
        }
        if (db < floorDb + END_MARGIN_DB) {
            silentMs += frameMs
            if (silentMs >= END_SILENCE_MS) {
                ended = true
                return Result.SPEECH_ENDED
            }
        } else {
            silentMs = 0
        }
        return Result.NONE
    }

    private fun dbfs(frame: ShortArray): Float {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        val rms = sqrt(sum / frame.size)
        return if (rms < 1.0) -100f else (20 * log10(rms / 32768.0)).toFloat()
    }
}
