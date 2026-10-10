package org.opensdr.amhost

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import org.opensdr.radioapi.AmHostStatus
import org.opensdr.radioapi.HostReceiverSettings
import org.opensdr.radioapi.RtlSdrIpcContract
import org.opensdr.sdrcore.AM_SIDEBAND_MODE_LOWER
import org.opensdr.sdrcore.DEMOD_MODE_NFM
import org.opensdr.sdrcore.DEMOD_MODE_SSB
import org.opensdr.sdrcore.DEMOD_MODE_WFM
import org.opensdr.sdrcore.RTL_SQUELCH_MODE_MANUAL
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.math.roundToInt

internal fun modulationOptionFor(demodMode: Int, sidebandMode: Int): ModulationOption {
    if (demodMode == DEMOD_MODE_SSB) {
        return if (sidebandMode == AM_SIDEBAND_MODE_LOWER) {
            modulationOptions.first { it.label == "LSB" }
        } else {
            modulationOptions.first { it.label == "USB" }
        }
    }
    if (demodMode == DEMOD_MODE_NFM) {
        return modulationOptions.first { it.label == "NFM" }
    }
    if (demodMode == DEMOD_MODE_WFM) {
        return modulationOptions.first { it.label == "WFM" }
    }
    return modulationOptions.firstOrNull { it.demodMode == demodMode } ?: modulationOptions.first()
}

internal fun AmHostStatus.toModulationSettings(option: ModulationOption): HostReceiverSettings {
    return toReceiverSettings(
        demodMode = option.demodMode,
        sidebandMode = option.sidebandMode,
    )
}

internal fun AmHostStatus.toManualSquelchSettings(level: Float): HostReceiverSettings {
    return toReceiverSettings(
        squelchMode = RTL_SQUELCH_MODE_MANUAL,
        squelchLevel = level.roundToInt().coerceIn(0, 100),
    )
}

internal fun AmHostStatus.settingsForHostTune(controls: HostReceiverControls): HostReceiverSettings? {
    val settings = toReceiverSettings(
        squelchMode = controls.squelchMode,
        squelchLevel = controls.squelchLevel.roundToInt().coerceIn(0, 100),
        demodMode = controls.demodMode,
        sidebandMode = controls.sidebandMode,
    )
    return settings.takeUnless { it == toReceiverSettings() }
}

internal fun AmHostStatus.toReceiverSettings(
    squelchMode: Int = this.squelchMode,
    squelchLevel: Int = this.squelchLevel,
    demodMode: Int = this.demodMode,
    sidebandMode: Int = this.sidebandMode,
): HostReceiverSettings {
    return HostReceiverSettings(
        frequencyCorrectionHz = frequencyCorrectionHz,
        ppmCorrection = ppmCorrection,
        biasTeeEnabled = biasTeeEnabled,
        preferredSampleRateHz = preferredSampleRateHz,
        amBandwidthHz = amBandwidthHz,
        squelchMode = squelchMode,
        squelchLevel = squelchLevel,
        squelchOpenDebounceMs = squelchOpenDebounceMs,
        squelchCloseHangMs = squelchCloseHangMs,
        hardSquelchMuteEnabled = hardSquelchMuteEnabled,
        gainMode = gainMode,
        tunerGain = tunerGain,
        audioBoostPercent = audioBoostPercent,
        voiceFocusEnabled = voiceFocusEnabled,
        postLevelerEnabled = postLevelerEnabled,
        noiseBlankerEnabled = noiseBlankerEnabled,
        denoiseMode = denoiseMode,
        demodMode = demodMode,
        notchMode = notchMode,
        sidebandMode = sidebandMode,
        advancedSyncEnabled = advancedSyncEnabled,
        qualitySquelchEnabled = qualitySquelchEnabled,
        speechEnhancerMode = speechEnhancerMode,
        amSpeechFilterEnabled = amSpeechFilterEnabled,
    )
}

internal fun buildPowerPlan(status: AmHostStatus, goal: PowerGoal, targetFrequencyHz: Long): PowerPlan {
    return when (goal) {
        PowerGoal.TURN_ON -> when {
            status.supportedDevices.isEmpty() && !status.connected ->
                PowerPlan(command = null, message = "No RTL-SDR detected", completed = true)
            !status.usbPermissionGranted ->
                PowerPlan(command = RtlSdrIpcContract.MSG_REQUEST_USB_PERMISSION, message = "Requesting USB permission")
            !status.connected ->
                PowerPlan(command = RtlSdrIpcContract.MSG_CONNECT, message = "Opening RTL-SDR device")
            targetFrequencyHz <= 0L ->
                PowerPlan(command = null, message = "Invalid frequency", completed = true)
            status.tunedFrequencyHz != targetFrequencyHz ->
                PowerPlan(
                    command = RtlSdrIpcContract.MSG_TUNE,
                    message = "Tuning to %.3f MHz".format(Locale.US, targetFrequencyHz / 1_000_000.0),
                    commandData = tuneCommandData(targetFrequencyHz),
                )
            !status.streaming ->
                PowerPlan(command = RtlSdrIpcContract.MSG_START_AUDIO, message = "Starting audio")
            else ->
                PowerPlan(command = null, message = "RTL-SDR active", completed = true)
        }

        PowerGoal.TURN_OFF -> when {
            status.streaming ->
                PowerPlan(command = RtlSdrIpcContract.MSG_STOP_AUDIO, message = "Stopping audio")
            status.connected ->
                PowerPlan(command = RtlSdrIpcContract.MSG_DISCONNECT, message = "Disconnecting RTL-SDR")
            else ->
                PowerPlan(command = null, message = "RTL-SDR turned off", completed = true)
        }
    }
}

internal fun sendCommand(
    serviceMessenger: Messenger?,
    replyMessenger: Messenger,
    what: Int,
    data: Bundle? = null,
    requestId: Int = 0,
): Boolean {
    if (serviceMessenger == null) return false
    val message = Message.obtain(null, what).apply {
        replyTo = replyMessenger
        arg1 = requestId
        if (data != null) this.data = data
    }
    return runCatching { serviceMessenger.send(message) }.isSuccess
}

internal fun parseFrequencyMhzToHz(text: String): Long {
    return ((text.toDoubleOrNull() ?: 0.0) * 1_000_000.0).roundToLong()
}

internal fun formatFrequencyHzAsMhzText(frequencyHz: Long): String {
    val raw = "%.6f".format(Locale.US, frequencyHz / 1_000_000.0)
    val trimmed = raw.trimEnd('0').trimEnd('.')
    val decimalIndex = trimmed.indexOf('.')
    return when {
        decimalIndex < 0 -> "$trimmed.000"
        trimmed.length - decimalIndex - 1 >= 3 -> trimmed
        else -> trimmed + "0".repeat(3 - (trimmed.length - decimalIndex - 1))
    }
}

internal fun tuneCommandData(frequencyHz: Long): Bundle {
    return Bundle().apply {
        putLong(RtlSdrIpcContract.KEY_FREQUENCY_HZ, frequencyHz)
    }
}
