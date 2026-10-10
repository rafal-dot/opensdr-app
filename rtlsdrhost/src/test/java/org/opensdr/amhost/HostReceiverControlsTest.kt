package org.opensdr.amhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.opensdr.radioapi.AmHostStatus
import org.opensdr.sdrcore.AM_SIDEBAND_MODE_LOWER
import org.opensdr.sdrcore.AM_SIDEBAND_MODE_UPPER
import org.opensdr.sdrcore.DEMOD_MODE_AM_ENVELOPE
import org.opensdr.sdrcore.DEMOD_MODE_AM_SYNC
import org.opensdr.sdrcore.DEMOD_MODE_SSB
import org.opensdr.sdrcore.DEMOD_MODE_WFM
import org.opensdr.sdrcore.RTL_SQUELCH_MODE_AUTO
import org.opensdr.sdrcore.RTL_SQUELCH_MODE_CUSTOM
import org.opensdr.sdrcore.RTL_SQUELCH_MODE_MANUAL

class HostReceiverControlsTest {
    private val saved = HostReceiverControls(
        frequencyText = "100.000",
        demodMode = DEMOD_MODE_WFM,
        sidebandMode = 0,
        squelchLevel = 20f,
    )
    private val active = AmHostStatus(
        connected = true,
        streaming = true,
        tunedFrequencyHz = 128_805_000L,
        tunedCenterFrequencyHz = 128_802_500L,
        demodMode = DEMOD_MODE_AM_ENVELOPE,
        squelchMode = RTL_SQUELCH_MODE_CUSTOM,
        squelchLevel = 37,
        amBandwidthHz = 10_000,
        ppmCorrection = 14,
        audioBoostPercent = 130,
        postLevelerEnabled = true,
        selectedProfileLabel = "Low CPU Clean Voice",
    )

    private fun sync(
        current: AmHostStatus = active,
        previous: AmHostStatus? = null,
        controls: HostReceiverControls = saved,
        startingLocally: Boolean = false,
    ) = synchronizeHostReceiverControls(controls, previous, current, startingLocally)

    @Test
    fun `failed receiver configuration does not replace controls with unconfirmed settings`() {
        val controls = sync()
        val failed = active.copy(demodMode = DEMOD_MODE_WFM, squelchLevel = 90, receiverSettingsError = "USB failed")
        assertEquals(controls, sync(current = failed, previous = active, controls = controls))
    }

    @Test
    fun `opening host during external playback replaces saved fields without settings to send`() {
        val controls = sync()

        assertEquals("128.805", controls.frequencyText)
        assertEquals("AM", controls.modulation.label)
        assertEquals(37f, controls.squelchLevel)
        assertEquals(RTL_SQUELCH_MODE_CUSTOM, controls.squelchMode)
        assertNull(active.settingsForHostTune(controls))
    }

    @Test
    fun `connected external session wins even before audio starts`() {
        val ready = active.copy(streaming = false)
        val controls = sync(current = ready)

        assertEquals("128.805", controls.frequencyText)
        assertNull(ready.settingsForHostTune(controls))
    }

    @Test
    fun `external tuning and modulation changes follow live status`() {
        val next = active.copy(tunedFrequencyHz = 118_305_000L, demodMode = DEMOD_MODE_AM_SYNC, squelchLevel = 12)
        val controls = sync(current = next, previous = active, controls = sync())

        assertEquals("118.305", controls.frequencyText)
        assertEquals("AM Sync", controls.modulation.label)
        assertEquals(12f, controls.squelchLevel)
        assertNull(next.settingsForHostTune(controls))
    }

    @Test
    fun `sideband changes update modulation without changing frequency`() {
        val lower = active.copy(demodMode = DEMOD_MODE_SSB, sidebandMode = AM_SIDEBAND_MODE_LOWER)
        val upper = lower.copy(sidebandMode = AM_SIDEBAND_MODE_UPPER)

        assertEquals("LSB", sync(current = lower).modulation.label)
        assertEquals("USB", sync(current = upper, previous = lower, controls = sync(current = lower)).modulation.label)
    }

    @Test
    fun `auto and off modes are never coerced to manual by observation or plain tune`() {
        for (level in listOf(0, 28)) {
            val current = active.copy(squelchMode = RTL_SQUELCH_MODE_AUTO, squelchLevel = level)
            val controls = sync(current = current)

            assertEquals(RTL_SQUELCH_MODE_AUTO, controls.squelchMode)
            assertEquals(level.toFloat(), controls.squelchLevel)
            assertNull(current.settingsForHostTune(controls.copy(frequencyText = "118.305")))
        }
    }

    @Test
    fun `AM sideband parameter is preserved even though selector label is AM`() {
        val current = active.copy(sidebandMode = AM_SIDEBAND_MODE_LOWER)
        val controls = sync(current = current)

        assertEquals("AM", controls.modulation.label)
        assertEquals(AM_SIDEBAND_MODE_LOWER, controls.sidebandMode)
        assertNull(current.settingsForHostTune(controls))
    }

    @Test
    fun `squelch mode changes at the same level update controls`() {
        val current = active.copy(squelchMode = RTL_SQUELCH_MODE_AUTO)
        val controls = sync(current = current, previous = active, controls = sync())

        assertEquals(RTL_SQUELCH_MODE_AUTO, controls.squelchMode)
        assertNull(current.settingsForHostTune(controls))
    }

    @Test
    fun `metric-only polling does not erase typed frequency or pending history`() {
        val draft = saved.copy(frequencyText = "100.")
        val polled = active.copy(rfLevelPercent = 90, squelchPercent = 72, squelchThresholdPercent = 40)

        assertEquals(draft, sync(current = polled, previous = active, controls = draft))
    }

    @Test
    fun `explicit refresh discards history draft without sending settings`() {
        val draft = sync().withHistory(FrequencyHistoryEntry(100_000_000L, saved.modulation, 21))
        val refreshed = sync(controls = draft)

        assertEquals("128.805", refreshed.frequencyText)
        assertEquals("AM", refreshed.modulation.label)
        assertNull(active.settingsForHostTune(refreshed))
    }

    @Test
    fun `external retune supersedes old history draft including unchanged backend settings`() {
        val next = active.copy(tunedFrequencyHz = 118_305_000L)
        val controls = sync(current = next, previous = active, controls = saved)

        assertEquals("118.305", controls.frequencyText)
        assertEquals("AM", controls.modulation.label)
        assertEquals(37f, controls.squelchLevel)
    }

    @Test
    fun `idle startup keeps standalone preferences as a draft`() {
        val idle = AmHostStatus()

        assertEquals(saved, sync(current = idle))
        val settings = idle.settingsForHostTune(saved)!!
        assertEquals(DEMOD_MODE_WFM, settings.demodMode)
        assertEquals(RTL_SQUELCH_MODE_MANUAL, settings.squelchMode)
        assertEquals(20, settings.squelchLevel)
    }

    @Test
    fun `local power on protects target from provisional connect frequency`() {
        val provisional = active.copy(streaming = false, tunedFrequencyHz = 118_000_000L)

        assertEquals(saved, sync(current = provisional, startingLocally = true))
        val completed = active.copy(tunedFrequencyHz = 100_000_000L, demodMode = DEMOD_MODE_WFM)
        assertEquals("100.000", sync(current = completed).frequencyText)
    }

    @Test
    fun `disconnect retains last displayed frequency and reconnect replaces drafts`() {
        val controls = sync()
        val idle = AmHostStatus()
        assertEquals(controls, sync(current = idle, previous = active, controls = controls))
        assertEquals(controls, sync(previous = idle, controls = saved))
    }

    @Test
    fun `selecting history prepares settings without modifying observed status`() {
        val entry = FrequencyHistoryEntry(100_000_000L, saved.modulation, 21)
        val draft = sync().withHistory(entry)

        assertEquals("100.000", draft.frequencyText)
        assertEquals(DEMOD_MODE_AM_ENVELOPE, active.demodMode)
        assertEquals(37, active.squelchLevel)
        val onExplicitTune = active.settingsForHostTune(draft)!!
        assertEquals(DEMOD_MODE_WFM, onExplicitTune.demodMode)
        assertEquals(21, onExplicitTune.squelchLevel)
        assertEquals(RTL_SQUELCH_MODE_MANUAL, onExplicitTune.squelchMode)
        assertEquals(active.ppmCorrection, onExplicitTune.ppmCorrection)
        assertEquals(active.amBandwidthHz, onExplicitTune.amBandwidthHz)
        assertEquals(active.audioBoostPercent, onExplicitTune.audioBoostPercent)
        assertEquals(active.postLevelerEnabled, onExplicitTune.postLevelerEnabled)
    }

    @Test
    fun `explicit modulation edit preserves active squelch and profile parameters`() {
        val settings = active.toModulationSettings(saved.modulation)

        assertEquals(DEMOD_MODE_WFM, settings.demodMode)
        assertEquals(active.squelchMode, settings.squelchMode)
        assertEquals(active.squelchLevel, settings.squelchLevel)
        assertEquals(active.ppmCorrection, settings.ppmCorrection)
        assertEquals(active.amBandwidthHz, settings.amBandwidthHz)
        assertEquals(active.audioBoostPercent, settings.audioBoostPercent)
    }

    @Test
    fun `explicit manual squelch edit changes only squelch fields`() {
        val current = active.copy(demodMode = DEMOD_MODE_AM_SYNC, sidebandMode = AM_SIDEBAND_MODE_LOWER)
        val settings = current.toManualSquelchSettings(24f)

        assertEquals(24, settings.squelchLevel)
        assertEquals(RTL_SQUELCH_MODE_MANUAL, settings.squelchMode)
        assertEquals(current.demodMode, settings.demodMode)
        assertEquals(current.sidebandMode, settings.sidebandMode)
        assertEquals(current.ppmCorrection, settings.ppmCorrection)
        assertEquals(current.amBandwidthHz, settings.amBandwidthHz)
    }

    @Test
    fun `squelch labels identify custom and off without changing mode`() {
        assertEquals("custom", formatHostSquelchMode(RTL_SQUELCH_MODE_CUSTOM, 37))
        assertEquals("auto", formatHostSquelchMode(RTL_SQUELCH_MODE_AUTO, 28))
        assertEquals("off", formatHostSquelchMode(RTL_SQUELCH_MODE_AUTO, 0))
        assertEquals("manual", formatHostSquelchMode(RTL_SQUELCH_MODE_MANUAL, 20))
    }
}
