package org.opensdr.sdrcore

import org.junit.Assert.assertEquals
import org.junit.Test

class AmProfileDefaultsTest {
    @Test
    fun explicitProfileDefaultsReplaceAllDspOptionsButNotSquelchOrCalibration() {
        val base = DirectRtlReceiverSettings(
            frequencyCorrectionHz = 300,
            ppmCorrection = 12,
            biasTeeEnabled = true,
            preferredSampleRateHz = 960_000,
            amBandwidthHz = 16_000,
            squelchMode = RTL_SQUELCH_MODE_CUSTOM,
            squelchLevel = 23,
            squelchOpenDebounceMs = 150,
            squelchCloseHangMs = 800,
            hardSquelchMuteEnabled = false,
            gainMode = 1,
            tunerGain = 297,
            audioBoostPercent = 180,
            voiceFocusEnabled = false,
            postLevelerEnabled = false,
            noiseBlankerEnabled = true,
            denoiseMode = DENOISE_MODE_AUTO,
            demodMode = DEMOD_MODE_AM_SYNC,
            notchMode = NOTCH_MODE_AUTO,
            sidebandMode = AM_SIDEBAND_MODE_UPPER,
            advancedSyncEnabled = true,
            qualitySquelchEnabled = true,
            speechEnhancerMode = SPEECH_ENHANCER_MODE_STRONG,
        )
        val profile = profile(DirectRtlAmProfileDefaults(
            preferredSampleRateHz = 240_000,
            amBandwidthHz = 10_000,
            audioBoostPercent = 130,
            voiceFocusEnabled = true,
            postLevelerEnabled = true,
            noiseBlankerEnabled = false,
            denoiseMode = DENOISE_MODE_OFF,
            demodMode = DEMOD_MODE_AM_ENVELOPE,
            notchMode = NOTCH_MODE_OFF,
            sidebandMode = AM_SIDEBAND_MODE_FULL,
            advancedSyncEnabled = false,
            qualitySquelchEnabled = false,
            speechEnhancerMode = SPEECH_ENHANCER_MODE_OFF,
        ))

        assertEquals(
            base.copy(
                preferredSampleRateHz = 240_000,
                amBandwidthHz = 10_000,
                audioBoostPercent = 130,
                voiceFocusEnabled = true,
                postLevelerEnabled = true,
                noiseBlankerEnabled = false,
                denoiseMode = DENOISE_MODE_OFF,
                demodMode = DEMOD_MODE_AM_ENVELOPE,
                notchMode = NOTCH_MODE_OFF,
                sidebandMode = AM_SIDEBAND_MODE_FULL,
                advancedSyncEnabled = false,
                qualitySquelchEnabled = false,
                speechEnhancerMode = SPEECH_ENHANCER_MODE_OFF,
            ),
            profile.applyDefaults(base),
        )
    }

    @Test
    fun missingOptionalDefaultsPreserveBaseSettings() {
        val base = DirectRtlReceiverSettings(amBandwidthHz = 11_000, demodMode = DEMOD_MODE_NFM)
        assertEquals(base, profile(DirectRtlAmProfileDefaults()).applyDefaults(base))
    }

    private fun profile(defaults: DirectRtlAmProfileDefaults) = DirectRtlAmProfile(
        option = DirectRtlAmProfileOption("test", "Test", ""),
        isDefault = true,
        defaults = defaults,
    )
}
