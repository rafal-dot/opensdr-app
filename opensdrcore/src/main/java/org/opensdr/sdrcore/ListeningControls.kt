package org.opensdr.sdrcore

/** Only these controls belong to a listening client; profile/expert settings stay with the host. */
internal fun DirectRtlReceiverSettings.withListeningControls(
    ppmCorrection: Int,
    biasTeeEnabled: Boolean,
    gainMode: Int?,
    tunerGain: Int?,
    squelchMode: Int,
    squelchLevel: Int,
    amSpeechFilterEnabled: Boolean? = null,
    frequencyCorrectionHz: Int? = null,
): DirectRtlReceiverSettings = copy(
    frequencyCorrectionHz = frequencyCorrectionHz ?: this.frequencyCorrectionHz,
    ppmCorrection = ppmCorrection,
    biasTeeEnabled = biasTeeEnabled,
    gainMode = gainMode ?: this.gainMode,
    tunerGain = tunerGain ?: this.tunerGain,
    squelchMode = squelchMode,
    squelchLevel = squelchLevel,
    amSpeechFilterEnabled = amSpeechFilterEnabled ?: this.amSpeechFilterEnabled,
)
