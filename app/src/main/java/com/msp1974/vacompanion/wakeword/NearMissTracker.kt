package com.msp1974.vacompanion.wakeword

/**
 * Spots near misses: a wake word whose score rose clearly ([MIN_SCORE] or more) but never reached
 * the threshold. Typically the user calling over music or from too far away - the cases to tune
 * the threshold for and to retrain the model on.
 *
 * Feed it every score the engine reports (the engine reports a word while its score is above
 * 0.1, plus the frame where it drops back). A rise is over when the score falls under
 * [END_SCORE]; if nothing in it was a detection, its peak is returned as the near miss. At most
 * one near miss is returned per [MIN_INTERVAL_MS]. Pure: the clock is passed in.
 */
class NearMissTracker {
    companion object {
        const val MIN_SCORE = 0.2f
        const val END_SCORE = 0.1f
        const val MIN_INTERVAL_MS = 3_000L
    }

    private class Rise(var peak: WakeWordEngineProvider.WakeWordDetection, var detected: Boolean)

    private val rises = mutableMapOf<String, Rise>()
    private var lastReportAt = Long.MIN_VALUE / 2

    @Synchronized
    fun onScore(detection: WakeWordEngineProvider.WakeWordDetection, now: Long): WakeWordEngineProvider.WakeWordDetection? {
        val id = detection.wakeWordId
        if (detection.score >= END_SCORE) {
            val rise = rises[id]
            if (rise == null) {
                rises[id] = Rise(detection, detection.detected)
            } else {
                if (detection.score > rise.peak.score) rise.peak = detection
                if (detection.detected) rise.detected = true
            }
            return null
        }
        val rise = rises.remove(id) ?: return null
        if (rise.detected || rise.peak.score < MIN_SCORE) return null
        if (now - lastReportAt < MIN_INTERVAL_MS) return null
        lastReportAt = now
        return rise.peak.copy(detected = false)
    }
}
