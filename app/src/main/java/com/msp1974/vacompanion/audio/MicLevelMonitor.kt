package com.msp1974.vacompanion.audio

import kotlin.math.log10

/**
 * How loud the microphone has been lately, measured on the raw signal before any processing.
 * That matters: WebRTC's gain control brings whatever it is given up to speech loudness, so only
 * the raw level says how loud (and so how near) the speaker was.
 *
 * Levels are the RMS of each frame in dBFS (0 is full scale, so they are negative).
 *
 * [update] is called from the microphone's read thread and the rest from elsewhere.
 */
class MicLevelMonitor {

    private class Frame(val durationMs: Float, val levelDb: Float)

    private val frames = ArrayDeque<Frame>()
    private var framesMs = 0f
    private var lastUpdate = 0L

    @Synchronized
    fun update(frame: ShortArray, sampleRate: Int = VACAAudioFormat.SAMPLE_RATE_HZ) {
        if (frame.isEmpty()) return
        var sum = 0.0
        for (s in frame) {
            val v = s / 32768.0
            sum += v * v
        }
        val levelDb = (10 * log10(sum / frame.size + 1e-12)).toFloat().coerceAtLeast(FLOOR_DBFS)
        val durationMs = frame.size * 1000f / sampleRate

        lastUpdate = android.os.SystemClock.elapsedRealtime()
        frames.addLast(Frame(durationMs, levelDb))
        framesMs += durationMs
        while (framesMs > HISTORY_MS && frames.size > 1) framesMs -= frames.removeFirst().durationMs
    }

    /**
     * The loudest frame, in dBFS, over the last [windowMs] of audio. Null if nothing has been
     * heard yet, so callers can tell "unknown" from "very quiet".
     */
    @Synchronized
    fun peakDbfs(windowMs: Long): Float? {
        if (frames.isEmpty()) return null
        var peak = Float.NEGATIVE_INFINITY
        var covered = 0f
        for (i in frames.indices.reversed()) {
            val f = frames[i]
            peak = maxOf(peak, f.levelDb)
            covered += f.durationMs
            if (covered >= windowMs) break
        }
        return peak
    }

    /**
     * Background noise: the [percentile] (default 10th) of the frame levels over the last
     * [windowMs], so speech and short sounds in the window barely move it. Null without audio.
     */
    @Synchronized
    fun floorDbfs(windowMs: Long = NOISE_FLOOR_WINDOW_MS, percentile: Float = 0.1f): Float? {
        if (frames.isEmpty()) return null
        val levels = ArrayList<Float>()
        var covered = 0f
        for (i in frames.indices.reversed()) {
            levels.add(frames[i].levelDb)
            covered += frames[i].durationMs
            if (covered >= windowMs) break
        }
        levels.sort()
        return levels[((levels.size - 1) * percentile.coerceIn(0f, 1f)).toInt()]
    }

    /**
     * Median frame level over the last [windowMs], or null unless that much audio was heard and
     * the microphone is still delivering (a frame within the last 2 s).
     */
    @Synchronized
    fun medianDbfs(windowMs: Long): Float? {
        if (frames.isEmpty() || framesMs < windowMs * 0.9f) return null
        if (android.os.SystemClock.elapsedRealtime() - lastUpdate > 2_000L) return null
        return floorDbfs(windowMs, 0.5f)
    }

    @Synchronized
    fun reset() {
        frames.clear()
        framesMs = 0f
    }

    companion object {
        /**
         * How much of the most recent audio is looked at for how loud a wake word was said. Covers
         * the phrase itself plus the time the engine takes to report it.
         */
        const val WAKE_WORD_WINDOW_MS = 2000L
        const val NOISE_FLOOR_WINDOW_MS = 3000L

        /** [rawDbfs] with this device's calibration [offsetDb] added; null if the level is unknown. */
        fun calibrated(rawDbfs: Float?, offsetDb: Float): Float? = rawDbfs?.plus(offsetDb)

        /** Digital silence reads as this rather than minus infinity. */
        private const val FLOOR_DBFS = -100f

        // Long enough for the 10 s ambient_noise median.
        private const val HISTORY_MS = 10_500f
        const val AMBIENT_WINDOW_MS = 10_000L

    }
}
