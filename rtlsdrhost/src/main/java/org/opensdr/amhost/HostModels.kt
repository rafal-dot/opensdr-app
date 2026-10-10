package org.opensdr.amhost

import android.os.Bundle
import org.opensdr.sdrcore.AM_SIDEBAND_MODE_LOWER
import org.opensdr.sdrcore.AM_SIDEBAND_MODE_UPPER
import org.opensdr.sdrcore.DEMOD_MODE_AM_ENVELOPE
import org.opensdr.sdrcore.DEMOD_MODE_AM_SYNC
import org.opensdr.sdrcore.DEMOD_MODE_NFM
import org.opensdr.sdrcore.DEMOD_MODE_SSB
import org.opensdr.sdrcore.DEMOD_MODE_WFM

internal enum class PowerGoal {
    TURN_ON,
    TURN_OFF,
}

internal data class PowerPlan(
    val command: Int?,
    val message: String,
    val completed: Boolean = false,
    val commandData: Bundle? = null,
)

internal data class ModulationOption(
    val label: String,
    val demodMode: Int,
    val sidebandMode: Int,
)

internal data class TuningStep(
    val label: String,
    val stepHz: Long,
)

internal data class FrequencyHistoryEntry(
    val frequencyHz: Long,
    val modulation: ModulationOption,
    val squelchLevel: Int,
)

internal data class WalkthroughStep(
    val title: String,
    val body: String,
    val liveStatus: String,
)

internal val modulationOptions = listOf(
    ModulationOption(label = "AM", demodMode = DEMOD_MODE_AM_ENVELOPE, sidebandMode = 0),
    ModulationOption(label = "AM Sync", demodMode = DEMOD_MODE_AM_SYNC, sidebandMode = 0),
    ModulationOption(label = "LSB", demodMode = DEMOD_MODE_SSB, sidebandMode = AM_SIDEBAND_MODE_LOWER),
    ModulationOption(label = "USB", demodMode = DEMOD_MODE_SSB, sidebandMode = AM_SIDEBAND_MODE_UPPER),
    ModulationOption(label = "NFM", demodMode = DEMOD_MODE_NFM, sidebandMode = 0),
    ModulationOption(label = "WFM", demodMode = DEMOD_MODE_WFM, sidebandMode = 0),
)

internal val tuningSteps = listOf(
    TuningStep(label = "10 Hz", stepHz = 10L),
    TuningStep(label = "100 Hz", stepHz = 100L),
    TuningStep(label = "1 kHz", stepHz = 1_000L),
    TuningStep(label = "5 kHz", stepHz = 5_000L),
    TuningStep(label = "7.5 kHz", stepHz = 7_500L),
    TuningStep(label = "8.33 kHz", stepHz = 8_333L),
    TuningStep(label = "12.5 kHz", stepHz = 12_500L),
    TuningStep(label = "25 kHz", stepHz = 25_000L),
    TuningStep(label = "50 kHz", stepHz = 50_000L),
    TuningStep(label = "1 MHz", stepHz = 1_000_000L),
)

internal val defaultTuningStep = tuningSteps.first { it.stepHz == DEFAULT_TUNING_STEP_HZ }
