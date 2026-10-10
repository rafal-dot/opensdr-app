package org.opensdr.sdrcore

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverBandwidthTest {
    @Test
    fun wfmGetsWideRfBandwidthWithoutReplacingTheAmPreference() {
        val amPreference = 10000
        assertEquals(10000, receiverRfBandwidthHz(DEMOD_MODE_AM_ENVELOPE, amPreference))
        assertEquals(200000, receiverRfBandwidthHz(DEMOD_MODE_WFM, amPreference))
        assertEquals(10000, receiverRfBandwidthHz(DEMOD_MODE_AM_SYNC, amPreference))
    }
}
