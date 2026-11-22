package com.msp1974.vacompanion.broadcasts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArbitrationKeysTest {
    private fun ids(text: String) = VacaUdpBroadcast.parseKeys(text).map { it.first to String(it.second) }

    @Test
    fun parsesKeysLikeVacaLinux() {
        assertTrue(VacaUdpBroadcast.parseKeys("").isEmpty())
        assertEquals(listOf("0" to "sozinha"), ids("sozinha"))
        assertEquals(listOf("k2" to "nova", "k1" to "velha"), ids("k2:nova, k1:velha\n"))
    }
}
