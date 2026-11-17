package com.msp1974.vacompanion.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NearMissTrackerTest {
    private fun d(score: Float, detected: Boolean = false, id: String = "hey_jarvis") =
        WakeWordEngineProvider.WakeWordDetection(id, "Hey Jarvis", detected, score)

    @Test
    fun reportsThePeakOfARiseThatNeverDetected() {
        val t = NearMissTracker()
        assertNull(t.onScore(d(0.15f), 0))
        assertNull(t.onScore(d(0.42f), 80))
        assertNull(t.onScore(d(0.30f), 160))
        val miss = t.onScore(d(0.05f), 240)
        assertEquals(0.42f, miss!!.score, 0.0001f)
        assertFalse(miss.detected)
    }

    @Test
    fun ignoresARiseThatDetected() {
        val t = NearMissTracker()
        t.onScore(d(0.5f), 0)
        t.onScore(d(0.8f, detected = true), 80)
        assertNull(t.onScore(d(0.05f), 160))
    }

    @Test
    fun ignoresSmallRises() {
        val t = NearMissTracker()
        t.onScore(d(0.15f), 0)
        assertNull(t.onScore(d(0.05f), 80))
    }

    @Test
    fun reportsAtMostOncePerInterval() {
        val t = NearMissTracker()
        t.onScore(d(0.4f), 0)
        assertEquals(0.4f, t.onScore(d(0.0f), 80)!!.score, 0.0001f)
        t.onScore(d(0.4f), 1_000)
        assertNull(t.onScore(d(0.0f), 1_080))
        t.onScore(d(0.35f), 5_000)
        assertEquals(0.35f, t.onScore(d(0.0f), 5_080)!!.score, 0.0001f)
    }
}
