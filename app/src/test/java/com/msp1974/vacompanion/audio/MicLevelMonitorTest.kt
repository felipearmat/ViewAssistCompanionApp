package com.msp1974.vacompanion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicLevelMonitorTest {
    private fun frame(amplitude: Short) = ShortArray(1600) { if (it % 2 == 0) amplitude else (-amplitude).toShort() }

    @Test
    fun floorIgnoresShortLoudSounds() {
        val m = MicLevelMonitor()
        assertNull(m.floorDbfs())
        repeat(25) { m.update(frame(100)) }    // 2.5 s of quiet room, about -50 dBFS
        repeat(5) { m.update(frame(10000)) }   // 0.5 s of speech, about -10 dBFS
        val floor = m.floorDbfs()!!
        assertEquals(-50.3f, floor, 0.5f)
        assertEquals(-10.3f, m.peakDbfs(MicLevelMonitor.WAKE_WORD_WINDOW_MS)!!, 0.5f)
    }
}
