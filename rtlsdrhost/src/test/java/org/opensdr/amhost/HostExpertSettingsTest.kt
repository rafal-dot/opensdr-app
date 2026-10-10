package org.opensdr.amhost

import org.junit.Assert.*
import org.junit.Test
import org.opensdr.radioapi.AmHostStatus
import org.opensdr.radioapi.HostReceiverSettings

class HostExpertSettingsTest {
    @Test
    fun speechFilterIsPreservedByTuneAndCheckedWhenApplyingExpertEdits() {
        val current = AmHostStatus(amSpeechFilterEnabled = false)
        val expected = current.toReceiverSettings()
        assertEquals(false, expected.amSpeechFilterEnabled)
        assertEquals(false, current.toManualSquelchSettings(30f).amSpeechFilterEnabled)
        assertEquals(false, current.toModulationSettings(modulationOptions.first()).amSpeechFilterEnabled)
        assertFalse(expertSettingsConflict(current, expected, null))
        assertTrue(expertSettingsConflict(current.copy(amSpeechFilterEnabled = true), expected, null))
        assertFalse(expertSettingsConflict(current, expected.copy(amSpeechFilterEnabled = null), null))
    }

    @Test
    fun serviceGuardRejectsQueuedChangesIncludingProfileWithIdenticalDefaults() {
        val status = AmHostStatus(selectedProfileId = "natural", squelchLevel = 20)
        val expected = status.toReceiverSettings()
        assertFalse(expertSettingsConflict(status, expected, "natural"))
        assertTrue(expertSettingsConflict(status.copy(squelchLevel = 30), expected, "natural"))
        assertTrue(expertSettingsConflict(status.copy(selectedProfileId = "rich_voice"), expected, "natural"))
        assertFalse(expertSettingsConflict(status, null, null))
    }

    @Test
    fun openingDraftDoesNotStageDefaultsAndExternalChangesRequireReload() {
        val current = HostReceiverSettings(amBandwidthHz = 9000, audioBoostPercent = 145, squelchLevel = 27)
        val draft = HostExpertDraft(current)
        assertEquals(current, draft.settings)
        assertFalse(draft.canApply(current))
        draft.settings = current.copy(amBandwidthHz = 11000)
        assertTrue(draft.canApply(current))
        assertFalse(draft.canApply(current.copy(squelchLevel = 40)))
        assertFalse(draft.canApply(current.copy(demodMode = 4)))
    }

    @Test
    fun reportIncludesReceiverDspAndBufferDiagnosticsButNotSpectrumArrays() {
        val text = hostDiagnosticReport(AmHostStatus(
            tunedFrequencyHz = 128805000, tunedCenterFrequencyHz = 128802500,
            selectedProfileId = "low_cpu_clean_voice", estimatedCarrierOffsetHz = -2500,
            traceLogText = "PCM read cadence / USB", driftSnapshotText = "UF 0 PD 0",
            spectrumBins = intArrayOf(987654321),
        ))
        assertTrue(text.contains("128805000"))
        assertTrue(text.contains("-2500"))
        assertTrue(text.contains("low_cpu_clean_voice"))
        assertTrue(text.contains("PCM read cadence / USB"))
        assertTrue(text.contains("UF 0 PD 0"))
        assertFalse(text.contains("987654321"))
    }
}
