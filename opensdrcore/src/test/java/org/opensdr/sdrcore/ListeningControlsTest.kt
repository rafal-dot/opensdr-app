package org.opensdr.sdrcore

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningControlsTest {
    @Test
    fun calibratedControlsPreserveHostGainAndEveryExpertSetting() {
        val original = DirectRtlReceiverSettings(
            gainMode = 1, tunerGain = 371, amBandwidthHz = 9000,
            demodMode = 2, noiseBlankerEnabled = true, audioBoostPercent = 140,
            squelchOpenDebounceMs = 250, squelchCloseHangMs = 600,
            postLevelerEnabled = true, denoiseMode = 2,
        )
        for (mode in 0..2) {
            for (correction in listOf(-500, 0, 750)) {
                assertEquals(
                    original.copy(frequencyCorrectionHz = correction, ppmCorrection = 15,
                        biasTeeEnabled = true, squelchMode = mode, squelchLevel = 40,
                        amSpeechFilterEnabled = false),
                    original.withListeningControls(15, true, null, null, mode, 40, false, correction),
                )
            }
        }
    }

    @Test
    fun speechFilterDefaultsOnAndIsIndependentOfProfilesAndLegacyClients() {
        val initial = DirectRtlReceiverSettings()
        assertEquals(true, initial.amSpeechFilterEnabled)
        val disabled = initial.withListeningControls(12, true, 1, 297, 2, 23, false)
        assertEquals(false, disabled.amSpeechFilterEnabled)
        assertEquals(disabled, disabled.withListeningControls(12, true, 1, 297, 2, 23))
        val profile = DirectRtlAmProfile(
            option = DirectRtlAmProfileOption("test", "Test", ""), isDefault = false,
            defaults = DirectRtlAmProfileDefaults(voiceFocusEnabled = false, demodMode = 1),
        )
        assertEquals(false, profile.applyDefaults(disabled).amSpeechFilterEnabled)
        assertEquals(true, profile.applyDefaults(initial).amSpeechFilterEnabled)
    }

    @Test
    fun partialControlsPreserveEveryProfileAndExpertSetting() {
        val original = DirectRtlReceiverSettings(
            frequencyCorrectionHz = 230, preferredSampleRateHz = 480_000,
            amBandwidthHz = 9_000, squelchOpenDebounceMs = 150, squelchCloseHangMs = 800,
            hardSquelchMuteEnabled = false, audioBoostPercent = 175, voiceFocusEnabled = false,
            postLevelerEnabled = false, noiseBlankerEnabled = true, denoiseMode = 2,
            demodMode = 2, notchMode = 1, sidebandMode = 2, advancedSyncEnabled = true,
            qualitySquelchEnabled = true, speechEnhancerMode = 2,
        )
        for (mode in 0..2) {
            val updated = original.withListeningControls(12, true, 1, 297, mode, 23)
            assertEquals(
                original.copy(ppmCorrection = 12, biasTeeEnabled = true, gainMode = 1,
                    tunerGain = 297, squelchMode = mode, squelchLevel = 23),
                updated,
            )
            assertEquals(updated, updated.withListeningControls(12, true, 1, 297, mode, 23))
        }
    }

    @Test
    fun profileCanResetLegacyTimingWithoutResettingManualLevelOrPpm() {
        val original = DirectRtlReceiverSettings(
            ppmCorrection = 12, squelchMode = 2, squelchLevel = 23,
            squelchOpenDebounceMs = 450, squelchCloseHangMs = 0, hardSquelchMuteEnabled = true,
            frequencyCorrectionHz = 500,
        )
        val profile = DirectRtlAmProfile(
            option = DirectRtlAmProfileOption("test", "Test", ""), isDefault = false,
            defaults = DirectRtlAmProfileDefaults(
                frequencyCorrectionHz = 0, squelchOpenDebounceMs = 120,
                squelchCloseHangMs = 700, hardSquelchMuteEnabled = false,
            ),
        )
        assertEquals(
            original.copy(frequencyCorrectionHz = 0, squelchOpenDebounceMs = 120,
                squelchCloseHangMs = 700, hardSquelchMuteEnabled = false),
            profile.applyDefaults(original),
        )
        assertEquals(
            original.copy(squelchOpenDebounceMs = 120, squelchCloseHangMs = 700, hardSquelchMuteEnabled = false),
            profile.applyDefaults(original, preserveFrequencyCorrection = true),
        )
    }
}
