package org.opensdr.sdrcore

import org.junit.Assert.assertEquals
import org.junit.Test

class SimpleAmProfileTest {
    private val profile = DirectRtlAmProfile(
        option = DirectRtlAmProfileOption("simple", "Simple AM", ""),
        isDefault = false,
        tuning = NativeAmTuning(simpleAmEnabled = true),
    )
    private val experimental = DirectRtlReceiverSettings(
        demodMode = DEMOD_MODE_AM_SYNC,
        audioBoostPercent = 320,
        voiceFocusEnabled = true,
        postLevelerEnabled = true,
        noiseBlankerEnabled = true,
        denoiseMode = DENOISE_MODE_AUTO,
        notchMode = NOTCH_MODE_AUTO,
        sidebandMode = AM_SIDEBAND_MODE_UPPER,
        advancedSyncEnabled = true,
        qualitySquelchEnabled = true,
        speechEnhancerMode = SPEECH_ENHANCER_MODE_STRONG,
        amSpeechFilterEnabled = true,
        amBandwidthHz = 12_000,
        squelchMode = RTL_SQUELCH_MODE_CUSTOM,
        squelchLevel = 23,
        ppmCorrection = 12,
        frequencyCorrectionHz = 300,
        gainMode = 1,
        tunerGain = 297,
        biasTeeEnabled = true,
    )

    @Test
    fun profileApplicationAndRestoredOrIpcSettingsUseTheSameBypassPolicy() {
        val expected = experimental.copy(
            demodMode = DEMOD_MODE_AM_ENVELOPE,
            audioBoostPercent = 100,
            voiceFocusEnabled = false,
            postLevelerEnabled = false,
            noiseBlankerEnabled = false,
            denoiseMode = DENOISE_MODE_OFF,
            notchMode = NOTCH_MODE_OFF,
            sidebandMode = AM_SIDEBAND_MODE_FULL,
            advancedSyncEnabled = false,
            qualitySquelchEnabled = false,
            speechEnhancerMode = SPEECH_ENHANCER_MODE_OFF,
        )
        assertEquals(expected, profile.effectiveSettings(experimental))
        assertEquals(expected, profile.applyDefaults(experimental))
        assertEquals(expected, profile.effectiveSettings(expected))
    }

    @Test
    fun legacyAndNonAmModesKeepTheirSettings() {
        val legacy = profile.copy(tuning = profile.tuning.copy(simpleAmEnabled = false))
        assertEquals(experimental, legacy.effectiveSettings(experimental))
        for (mode in listOf(DEMOD_MODE_SSB, DEMOD_MODE_NFM, DEMOD_MODE_WFM)) {
            val otherMode = experimental.copy(demodMode = mode)
            assertEquals(otherMode, profile.effectiveSettings(otherMode))
        }
    }

    @Test
    fun speechFilterRemainsAnIndependentListeningControl() {
        val enabled = profile.effectiveSettings(experimental)
        val disabled = experimental.copy(amSpeechFilterEnabled = false)
        assertEquals(enabled.copy(amSpeechFilterEnabled = false), profile.effectiveSettings(disabled))
    }
}
