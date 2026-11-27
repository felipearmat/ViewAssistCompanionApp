package com.msp1974.vacompanion.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * A simple, local end-of-speech detector for the listening stage, used when Home Assistant's
 * own voice activity detection misses a short reply ("yes") and so never says the speech
 * stopped: without it the device would stream until its listening timeout and the reply would
 * only be transcribed seconds later.
 *
 * Energy based. The noise floor is the 30th percentile of the frames heard before speech,
 * leaving out digital silence (below [SUPPRESSED_DBFS]). (The quietest frame alone was fooled by
 * the near silence noise suppression gives right after the microphone opens: in a room at
 * -45 dBFS the floor came out at -90 and the reply never "ended".) [START_FRAMES] frames in a
 * row [START_MARGIN_DB] above the floor are speech; after speech, [endSilenceMs] (700 ms by default) below
 * max(floor + [END_MARGIN_DB], speech level - [SPEECH_DROP_DB]) ends it, so a floor misjudged
 * too low still ends on a clear drop. Same rules as vaca-linux. Pure and frame-size agnostic.
 */
class EndOfSpeechDetector(
    private val sampleRate: Int = 16000,
    // Silence after speech that ends it (HA's finished speaking detection, see [silenceMsFor]).
    private val endSilenceMs: Int = END_SILENCE_MS,
) {
    companion object {
        const val CALIBRATION_FRAMES = 3
        const val START_FRAMES = 2
        const val START_MARGIN_DB = 10f
        const val END_MARGIN_DB = 6f
        const val SPEECH_DROP_DB = 16f
        const val END_SILENCE_MS = 700

        /**
         * Silence that ends speech for HA's "finished speaking detection" of this satellite
         * (default / relaxed / aggressive), matching the seconds HA's own voice detection uses.
         */
        fun silenceMsFor(sensitivity: String): Int = when (sensitivity) {
            "relaxed" -> 1250
            "aggressive" -> 400
            else -> END_SILENCE_MS
        }
        /** Speech is never quieter than this, whatever the floor. */
        const val MIN_SPEECH_DBFS = -50f
        /** Quieter than this is noise suppression or digital silence, not the room. */
        const val SUPPRESSED_DBFS = -80f
        private const val FLOOR_PERCENTILE = 0.3f
        private const val MAX_FLOOR_FRAMES = 100
        private const val MAX_CALIBRATION_FRAMES = 10
    }

    enum class Result { NONE, SPEECH_STARTED, SPEECH_ENDED }

    private val quiet = ArrayDeque<Float>()
    private var frames = 0
    private var floorDb = Float.MAX_VALUE
    private var speechDb: Float? = null
    private var loudRun = 0
    private var silentMs = 0
    var speechSeen = false
        private set
    private var ended = false

    private fun updateFloor(db: Float) {
        if (db < SUPPRESSED_DBFS) return
        quiet.addLast(db)
        if (quiet.size > MAX_FLOOR_FRAMES) quiet.removeFirst()
        val levels = quiet.sorted()
        floorDb = levels[((levels.size - 1) * FLOOR_PERCENTILE).toInt()]
    }

    fun onFrame(frame: ShortArray): Result {
        if (ended || frame.isEmpty()) return Result.NONE
        val db = dbfs(frame)
        frames++
        if (quiet.size < CALIBRATION_FRAMES && !speechSeen) {
            updateFloor(db)
            if (frames < MAX_CALIBRATION_FRAMES) return Result.NONE
            // Only silence so far: speech is anything clearly above it.
            if (quiet.isEmpty()) floorDb = SUPPRESSED_DBFS
        }
        val frameMs = frame.size * 1000 / sampleRate
        val startLevel = maxOf(floorDb + START_MARGIN_DB, MIN_SPEECH_DBFS)
        if (!speechSeen) {
            val loud = db >= startLevel
            loudRun = if (loud) loudRun + 1 else 0
            if (!loud) updateFloor(db)
            if (loudRun >= START_FRAMES) {
                speechSeen = true
                speechDb = db
                return Result.SPEECH_STARTED
            }
            return Result.NONE
        }
        // Speech level: a slow average of the loud frames.
        if (db >= startLevel) speechDb = speechDb?.let { 0.9f * it + 0.1f * db } ?: db
        var endLevel = floorDb + END_MARGIN_DB
        speechDb?.let { endLevel = maxOf(endLevel, it - SPEECH_DROP_DB) }
        if (db < endLevel) {
            silentMs += frameMs
            if (silentMs >= endSilenceMs) {
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
