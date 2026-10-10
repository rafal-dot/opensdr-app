package org.opensdr.sdrcore

import org.junit.Assert.assertEquals
import org.junit.Test

class AmCarrierTargetsTest {
    @Test
    fun airbandTargetsMatchTheHardwareCenteredIq() {
        assertTargets(128_805_000L, true, 0, -3000f, 2000f)
        assertTargets(128_810_000L, true, 0, -1333f, 334f)
        assertTargets(128_815_000L, true, 0, 334f, -1333f)
    }

    @Test
    fun legacyAndLiteralHostTuningHaveOnlyOneExpectedFrequency() {
        assertTargets(128_800_000L, true, 0, -500f, -500f)
        assertTargets(128_805_000L, false, 0, -500f, -500f)
        assertTargets(145_750_000L, false, 0, -500f, -500f)
    }

    @Test
    fun correctionIsSubtractedOnceFromBothTargets() {
        assertTargets(128_805_000L, true, 1200, -4200f, 800f)
        assertTargets(128_805_000L, true, -900, -2100f, 2900f)
        assertTargets(128_800_000L, true, -900, 400f, 400f)
    }

    private fun assertTargets(
        frequencyHz: Long,
        designator: Boolean,
        correctionHz: Int,
        primaryHz: Float,
        alternateHz: Float,
    ) {
        val centerHz = if (designator) airbandChannelSearchCenterFrequencyHz(frequencyHz) else frequencyHz
        val hardwareHz = zeroIfSafeHardwareCenterFrequencyHz(centerHz) + correctionHz
        assertEquals(
            AmCarrierTargets(primaryHz, alternateHz),
            amCarrierTargetsForTuning(frequencyHz, designator, hardwareHz),
        )
    }
}
