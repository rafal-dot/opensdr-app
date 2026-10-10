package org.opensdr.amhost

import org.opensdr.radioapi.AmHostStatus
import org.opensdr.sdrcore.RTL_SQUELCH_MODE_MANUAL

/** Local edits are drafts; receiving telemetry never sends settings back to the radio. */
internal data class HostReceiverControls(
    val frequencyText: String,
    val demodMode: Int,
    val sidebandMode: Int,
    val squelchLevel: Float,
    val squelchMode: Int = RTL_SQUELCH_MODE_MANUAL,
) {
    val modulation: ModulationOption
        get() = modulationOptionFor(demodMode, sidebandMode)

    fun withModulation(option: ModulationOption) = copy(
        demodMode = option.demodMode,
        sidebandMode = option.sidebandMode,
    )

    fun withManualSquelch(level: Float) = copy(
        squelchMode = RTL_SQUELCH_MODE_MANUAL,
        squelchLevel = level.coerceIn(0f, 100f),
    )

    fun withHistory(entry: FrequencyHistoryEntry) = copy(
        frequencyText = formatFrequencyHzAsMhzText(entry.frequencyHz),
        demodMode = entry.modulation.demodMode,
        sidebandMode = entry.modulation.sidebandMode,
        squelchMode = RTL_SQUELCH_MODE_MANUAL,
        squelchLevel = entry.squelchLevel.coerceIn(0, 100).toFloat(),
    )
}

internal fun synchronizeHostReceiverControls(
    controls: HostReceiverControls,
    previous: AmHostStatus?,
    current: AmHostStatus,
    startingLocally: Boolean,
): HostReceiverControls {
    if (startingLocally || current.receiverSettingsError != null || (!current.connected && !current.streaming)) return controls

    // Attaching to an existing session (even connected but not yet streaming)
    // must supersede saved preferences. A new frequency supersedes old drafts.
    val newTarget = previous == null ||
        (!previous.connected && !previous.streaming) ||
        previous.selectedDeviceName != current.selectedDeviceName ||
        previous.tunedFrequencyHz != current.tunedFrequencyHz
    val modulationChanged = newTarget || previous?.demodMode != current.demodMode ||
        previous?.sidebandMode != current.sidebandMode
    val squelchChanged = newTarget || previous?.squelchMode != current.squelchMode ||
        previous?.squelchLevel != current.squelchLevel
    val frequencyHz = current.tunedFrequencyHz?.takeIf { it > 0L }

    return controls.copy(
        frequencyText = if (newTarget && frequencyHz != null) {
            formatFrequencyHzAsMhzText(frequencyHz)
        } else {
            controls.frequencyText
        },
        demodMode = if (modulationChanged) current.demodMode else controls.demodMode,
        sidebandMode = if (modulationChanged) current.sidebandMode else controls.sidebandMode,
        squelchMode = if (squelchChanged) current.squelchMode else controls.squelchMode,
        squelchLevel = if (squelchChanged) current.squelchLevel.coerceIn(0, 100).toFloat() else controls.squelchLevel,
    )
}
