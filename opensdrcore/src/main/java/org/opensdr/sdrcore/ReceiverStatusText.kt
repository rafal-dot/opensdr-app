package org.opensdr.sdrcore

import java.util.Locale

/** Status text follows controller settings, never a UI draft or a saved history entry. */
internal fun DirectRtlReceiverSettings.modulationLabel(): String = when (demodMode) {
    DEMOD_MODE_AM_ENVELOPE -> "AM"
    DEMOD_MODE_AM_SYNC -> "AM Sync"
    DEMOD_MODE_SSB -> if (sidebandMode == AM_SIDEBAND_MODE_LOWER) "LSB" else "USB"
    DEMOD_MODE_NFM -> "NFM"
    DEMOD_MODE_WFM -> "WFM"
    else -> "Unknown ($demodMode)"
}

internal fun DirectRtlReceiverSettings.sessionStatusText(
    streaming: Boolean,
    frequencyHz: Long?,
    sampleRateHz: Int?,
    locale: Locale = Locale.getDefault(),
): String {
    val prefix = if (streaming) "Direct audio streaming" else "Direct session ready"
    val tuned = frequencyHz?.let {
        " on %.3f MHz %s".format(locale, it / 1_000_000.0, modulationLabel())
    }.orEmpty()
    val sampleRate = sampleRateHz?.let { " @ ${it / 1000} ksps" }.orEmpty()
    return "$prefix$tuned$sampleRate"
}

internal fun DirectRtlReceiverSettings.tunedStatusText(
    channelFrequencyHz: Long,
    centerFrequencyHz: Long,
    airbandChannelDesignator: Boolean,
    locale: Locale = Locale.getDefault(),
): String {
    val channelMhz = channelFrequencyHz / 1_000_000.0
    if (channelFrequencyHz == centerFrequencyHz) {
        return "Direct backend tuned to %.3f MHz %s".format(locale, channelMhz, modulationLabel())
    }
    val nominalCarrierFrequencyHz = if (airbandChannelDesignator) {
        airbandChannelCarrierFrequencyHz(channelFrequencyHz)
    } else {
        channelFrequencyHz
    }
    return "Direct backend tuned to channel %.3f MHz %s (search %.3f/%.3f MHz)".format(
        locale,
        channelMhz,
        modulationLabel(),
        nominalCarrierFrequencyHz / 1_000_000.0,
        channelMhz,
    )
}
