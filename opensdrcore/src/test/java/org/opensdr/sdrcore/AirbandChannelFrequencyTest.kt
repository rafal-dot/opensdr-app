package org.opensdr.sdrcore

import org.junit.Assert.assertEquals
import org.junit.Test

class AirbandChannelFrequencyTest {
    @Test
    fun mapsEightPointThreeThreeChannelDesignatorsToCarriers() {
        assertEquals(128_800_000L, airbandChannelCarrierFrequencyHz(128_805_000L))
        assertEquals(128_808_333L, airbandChannelCarrierFrequencyHz(128_810_000L))
        assertEquals(128_816_667L, airbandChannelCarrierFrequencyHz(128_815_000L))
    }

    @Test
    fun centersSearchBetweenNominalAndLiteralCarrier() {
        assertEquals(128_802_500L, airbandChannelSearchCenterFrequencyHz(128_805_000L))
        assertEquals(128_809_166L, airbandChannelSearchCenterFrequencyHz(128_810_000L))
        assertEquals(128_815_833L, airbandChannelSearchCenterFrequencyHz(128_815_000L))
    }

    @Test
    fun preservesLegacyTwentyFiveKilohertzChannels() {
        assertEquals(128_800_000L, airbandChannelCarrierFrequencyHz(128_800_000L))
        assertEquals(128_825_000L, airbandChannelCarrierFrequencyHz(128_825_000L))
        assertEquals(128_850_000L, airbandChannelCarrierFrequencyHz(128_850_000L))
        assertEquals(128_825_000L, airbandChannelSearchCenterFrequencyHz(128_825_000L))
    }

    @Test
    fun preservesLiteralAndOutOfBandFrequencies() {
        assertEquals(128_805_123L, airbandChannelCarrierFrequencyHz(128_805_123L))
        assertEquals(145_805_000L, airbandChannelCarrierFrequencyHz(145_805_000L))
    }

    @Test
    fun offsetsHardwareCenterAwayFromZeroIf() {
        assertEquals(128_825_500L, zeroIfSafeHardwareCenterFrequencyHz(128_825_000L))
        assertEquals(128_803_000L, zeroIfSafeHardwareCenterFrequencyHz(128_802_500L))
    }

    @Test
    fun keepsBothEightPointThreeThreeCarrierCandidatesInsideSearchRange() {
        val hardwareCenterHz = zeroIfSafeHardwareCenterFrequencyHz(
            airbandChannelSearchCenterFrequencyHz(128_805_000L),
        )

        assertEquals(-3_000L, 128_800_000L - hardwareCenterHz)
        assertEquals(2_000L, 128_805_000L - hardwareCenterHz)
    }
}
