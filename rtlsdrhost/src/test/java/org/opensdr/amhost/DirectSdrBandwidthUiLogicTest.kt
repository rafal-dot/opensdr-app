package org.opensdr.amhost

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectSdrBandwidthUiLogicTest {
    @Test
    fun parseAmBandwidthHzForUi_clampsToConfiguredRange() {
        assertEquals(7_000, parseAmBandwidthHzForUi("6000"))
        assertEquals(20_000, parseAmBandwidthHzForUi("25000"))
    }

    @Test
    fun parseAmBandwidthHzForUi_roundsToNearestStep() {
        assertEquals(8_500, parseAmBandwidthHzForUi("8460"))
        assertEquals(8_600, parseAmBandwidthHzForUi("8555"))
    }

    @Test
    fun adjustAmBandwidthHzForUi_movesBySingleStep() {
        assertEquals("10100", adjustAmBandwidthHzForUi("10000", 1))
        assertEquals("9900", adjustAmBandwidthHzForUi("10000", -1))
    }
}

