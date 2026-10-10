package org.opensdr.sdrcore

private const val AIRBAND_MIN_FREQUENCY_HZ = 117_975_000L
private const val AIRBAND_MAX_FREQUENCY_HZ = 137_000_000L
private const val CHANNEL_BLOCK_HZ = 25_000L
private const val CHANNEL_DISPLAY_STEP_HZ = 5_000L
private const val ZERO_IF_AVOIDANCE_OFFSET_HZ = 500L

/**
 * Resolves an ICAO VHF channel designator to its transmitted carrier.
 *
 * Legacy 25 kHz channels already identify their carrier and remain unchanged.
 * For 8.33 kHz channels, the displayed 5 kHz designator is mapped onto one of
 * the three carriers in each 25 kHz block. Frequencies outside the civil
 * airband, or values that are not channel designators, are treated literally.
 */
fun airbandChannelCarrierFrequencyHz(channelFrequencyHz: Long): Long {
    if (channelFrequencyHz !in AIRBAND_MIN_FREQUENCY_HZ..AIRBAND_MAX_FREQUENCY_HZ) {
        return channelFrequencyHz
    }
    if (channelFrequencyHz % CHANNEL_DISPLAY_STEP_HZ != 0L) {
        return channelFrequencyHz
    }

    val blockOffsetHz = Math.floorMod(channelFrequencyHz, CHANNEL_BLOCK_HZ)
    val blockBaseHz = channelFrequencyHz - blockOffsetHz
    return when (blockOffsetHz) {
        5_000L -> blockBaseHz
        10_000L -> blockBaseHz + CHANNEL_BLOCK_HZ / 3L
        15_000L -> blockBaseHz + (CHANNEL_BLOCK_HZ * 2L + 1L) / 3L
        else -> channelFrequencyHz
    }
}

/**
 * Chooses an RF center that can acquire both the ICAO carrier and a transmitter
 * operating on the literal channel value. The midpoint also keeps either
 * candidate away from the tuner's DC spike.
 */
fun airbandChannelSearchCenterFrequencyHz(channelFrequencyHz: Long): Long {
    val carrierFrequencyHz = airbandChannelCarrierFrequencyHz(channelFrequencyHz)
    if (carrierFrequencyHz == channelFrequencyHz) return channelFrequencyHz
    return (carrierFrequencyHz + channelFrequencyHz) / 2L
}

/**
 * Offsets the RTL-SDR hardware center from zero IF without changing the logical
 * channel or search center. A carrier at exactly zero IF is removed together
 * with the receiver's DC component, which breaks envelope AM demodulation.
 */
fun zeroIfSafeHardwareCenterFrequencyHz(searchCenterFrequencyHz: Long): Long {
    return searchCenterFrequencyHz + ZERO_IF_AVOIDANCE_OFFSET_HZ
}
