package org.opensdr.sdrcore

/** Expected carrier offsets in the IQ stream, relative to the corrected hardware center. */
data class AmCarrierTargets(
    val primaryOffsetHz: Float,
    val alternateOffsetHz: Float,
)

/** Shared by startup/config updates and live retunes; literal Host tuning stays literal. */
fun amCarrierTargetsForTuning(
    frequencyHz: Long,
    airbandChannelDesignator: Boolean,
    hardwareCenterHz: Long,
): AmCarrierTargets {
    val primaryHz = if (airbandChannelDesignator) {
        airbandChannelCarrierFrequencyHz(frequencyHz)
    } else {
        frequencyHz
    }
    return AmCarrierTargets(
        primaryOffsetHz = (primaryHz - hardwareCenterHz).toFloat(),
        alternateOffsetHz = (frequencyHz - hardwareCenterHz).toFloat(),
    )
}
