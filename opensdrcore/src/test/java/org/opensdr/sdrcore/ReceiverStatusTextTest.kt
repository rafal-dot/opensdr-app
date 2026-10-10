package org.opensdr.sdrcore

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverStatusTextTest {
    private val nfm = DirectRtlReceiverSettings(demodMode = DEMOD_MODE_NFM)

    @Test
    fun nfmSessionAndTuneDoNotClaimAm() {
        assertEquals(
            "Direct audio streaming on 145.750 MHz NFM @ 240 ksps",
            nfm.sessionStatusText(true, 145_750_000L, 240_000, Locale.US),
        )
        assertEquals(
            "Direct session ready on 145.750 MHz NFM @ 240 ksps",
            nfm.sessionStatusText(false, 145_750_000L, 240_000, Locale.US),
        )
        assertEquals(
            "Direct backend tuned to 145.750 MHz NFM",
            nfm.tunedStatusText(145_750_000L, 145_750_000L, false, Locale.US),
        )
    }

    @Test
    fun allSupportedDemodulatorsHaveConsistentLabels() {
        val cases = listOf(
            DirectRtlReceiverSettings(demodMode = DEMOD_MODE_AM_ENVELOPE) to "AM",
            DirectRtlReceiverSettings(demodMode = DEMOD_MODE_AM_SYNC) to "AM Sync",
            DirectRtlReceiverSettings(demodMode = DEMOD_MODE_SSB, sidebandMode = AM_SIDEBAND_MODE_LOWER) to "LSB",
            DirectRtlReceiverSettings(demodMode = DEMOD_MODE_SSB, sidebandMode = AM_SIDEBAND_MODE_UPPER) to "USB",
            nfm to "NFM",
            DirectRtlReceiverSettings(demodMode = DEMOD_MODE_WFM) to "WFM",
        )
        for ((settings, label) in cases) {
            assertEquals(label, settings.modulationLabel())
            assertEquals(
                "Direct audio streaming on 100.000 MHz $label @ 480 ksps",
                settings.sessionStatusText(true, 100_000_000L, 480_000, Locale.US),
            )
            assertEquals(
                "Direct backend tuned to 100.000 MHz $label",
                settings.tunedStatusText(100_000_000L, 100_000_000L, false, Locale.US),
            )
        }
    }

    @Test
    fun changingModeOnSameFrequencyUpdatesNextStatus() {
        val am = nfm.copy(demodMode = DEMOD_MODE_AM_ENVELOPE)
        assertEquals(
            "Direct audio streaming on 145.750 MHz AM @ 240 ksps",
            am.sessionStatusText(true, 145_750_000L, 240_000, Locale.US),
        )
        assertEquals(
            "Direct audio streaming on 145.750 MHz NFM @ 240 ksps",
            am.copy(demodMode = DEMOD_MODE_NFM).sessionStatusText(true, 145_750_000L, 240_000, Locale.US),
        )
    }

    @Test
    fun airbandSearchDescriptionIsPreserved() {
        val settings = DirectRtlReceiverSettings()
        assertEquals(
            "Direct backend tuned to channel 128.805 MHz AM (search 128.800/128.805 MHz)",
            settings.tunedStatusText(128_805_000L, 128_802_500L, true, Locale.US),
        )
    }

    @Test
    fun missingTelemetryDoesNotInventFrequencyOrRate() {
        assertEquals("Direct session ready", nfm.sessionStatusText(false, null, null, Locale.US))
        assertEquals("Direct audio streaming @ 240 ksps", nfm.sessionStatusText(true, null, 240_000, Locale.US))
    }

    @Test
    fun localizedFrequencyKeepsActualModulation() {
        assertEquals(
            "Direct audio streaming on 145,750 MHz NFM @ 240 ksps",
            nfm.sessionStatusText(true, 145_750_000L, 240_000, Locale.forLanguageTag("pl-PL")),
        )
    }
}
