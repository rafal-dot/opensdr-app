package org.opensdr.sdrcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmPlaybackRateControllerTest {
    private val config = PcmPlaybackRateConfig(
        nominalRateHz = 48_000,
        minRateHz = 36_000,
        maxRateHz = 60_000,
        targetBufferedSamples = 48_000,
        bufferDeadbandSamples = 240,
        bufferCorrectionDivisor = 24,
        maxBufferCorrectionHz = 2_400,
        maxRateStepHz = 120,
    )

    @Test
    fun estimatesSustainedProducerRateFromTimedSampleCounts() {
        val estimator = PcmProducerRateEstimator(config)

        assertEquals(48_000, estimator.observe(0L, 0L))
        assertEquals(44_000, estimator.observe(22_000L, 500_000_000L))
        assertEquals(44_000, estimator.observe(44_000L, 1_000_000_000L))
    }

    @Test
    fun ignoresImplausibleStartupMeasurement() {
        val estimator = PcmProducerRateEstimator(config)

        estimator.observe(0L, 0L)
        assertEquals(48_000, estimator.observe(100_000L, 500_000_000L))
    }

    @Test
    fun convergesToProducerRateAtTargetBufferLevel() {
        var rateHz = 48_000
        repeat(40) {
            rateHz = nextPcmConsumptionRateHz(
                currentRateHz = rateHz,
                producerRateHz = 44_000,
                bufferedSamples = 48_000,
                config = config,
            )
        }

        assertEquals(44_000, rateHz)
    }

    @Test
    fun consumesMoreSlowlyWhenBufferFallsBelowTarget() {
        var rateHz = 44_000
        repeat(20) {
            rateHz = nextPcmConsumptionRateHz(
                currentRateHz = rateHz,
                producerRateHz = 44_000,
                bufferedSamples = 0,
                config = config,
            )
        }

        assertTrue(rateHz < 44_000)
        assertEquals(42_000, rateHz)
    }
}
