package org.opensdr.amhost

import org.json.JSONObject
import org.opensdr.sdrcore.DirectRtlBackendState
import org.opensdr.sdrcore.amCarrierTargetsForTuning

/** Explicit settings only: do not export USB paths, location or unrelated client data. */
internal fun DirectRtlBackendState.captureSettings(): JSONObject = JSONObject().apply {
    put("type", "receiver_state")
    put("channel_hz", tunedFrequencyHz)
    put("center_hz", tunedCenterFrequencyHz)
    put("airband_channel_designator", tunedAsAirbandChannelDesignator)
    val channel = tunedFrequencyHz
    val center = tunedCenterFrequencyHz
    if (demodMode in 1..2 && channel != null && center != null) {
        val targets = amCarrierTargetsForTuning(channel, tunedAsAirbandChannelDesignator, center)
        put("primary_carrier_offset_hz", targets.primaryOffsetHz)
        put("alternate_carrier_offset_hz", targets.alternateOffsetHz)
    }
    put("sample_rate", sampleRateHz)
    put("frequency_correction_hz", frequencyCorrectionHz)
    put("ppm", ppmCorrection)
    put("bias_tee", biasTeeEnabled)
    put("gain_mode", gainMode)
    put("tuner_gain_tenths_db", tunerGain)
    put("bandwidth_hz", amBandwidthHz)
    put("demodulation", demodMode)
    put("squelch_mode", squelchMode)
    put("squelch_level", squelchLevel)
    put("squelch_open_ms", squelchOpenDebounceMs)
    put("squelch_close_ms", squelchCloseHangMs)
    put("hard_squelch_mute", hardSquelchMuteEnabled)
    put("audio_boost_percent", audioBoostPercent)
    put("voice_focus", voiceFocusEnabled)
    put("post_leveler", postLevelerEnabled)
    put("noise_blanker", noiseBlankerEnabled)
    put("denoise", denoiseMode)
    put("notch", notchMode)
    put("sideband", sidebandMode)
    put("advanced_sync", advancedSyncEnabled)
    put("quality_squelch", qualitySquelchEnabled)
    put("speech_enhancer", speechEnhancerMode)
    put("speech_filter", amSpeechFilterEnabled)
    put("profile_id", selectedAmProfileId)
    put("profile_name", selectedAmProfileLabel)
    put("device", selectedDeviceLabel)
}
