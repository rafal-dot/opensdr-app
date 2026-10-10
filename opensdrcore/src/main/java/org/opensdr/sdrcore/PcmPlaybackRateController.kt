package org.opensdr.sdrcore

import kotlin.math.abs
import kotlin.math.roundToInt

internal data class PcmPlaybackRateConfig(
    val nominalRateHz: Int,
    val minRateHz: Int,
    val maxRateHz: Int,
    val targetBufferedSamples: Int,
    val bufferDeadbandSamples: Int,
    val bufferCorrectionDivisor: Int,
    val maxBufferCorrectionHz: Int,
    val maxRateStepHz: Int,
)

/** Estimates the native producer clock without reacting to individual USB bursts. */
internal class PcmProducerRateEstimator(
    private val config: PcmPlaybackRateConfig,
    private val observationWindowNs: Long = 500_000_000L,
    private val smoothingAlpha: Double = 0.20,
) {
    private var previousTotalProducedSamples: Long? = null
    private var previousObservationNs: Long? = null
    private var hasValidObservation = false
    private var estimatedRateHz = config.nominalRateHz.toDouble()

    fun observe(totalProducedSamples: Long, nowNs: Long): Int {
        val previousTotal = previousTotalProducedSamples
        val previousNs = previousObservationNs
        if (previousTotal == null || previousNs == null || totalProducedSamples < previousTotal || nowNs < previousNs) {
            previousTotalProducedSamples = totalProducedSamples
            previousObservationNs = nowNs
            return estimatedRateHz.roundToInt()
        }

        val elapsedNs = nowNs - previousNs
        if (elapsedNs < observationWindowNs) return estimatedRateHz.roundToInt()

        val producedDelta = totalProducedSamples - previousTotal
        previousTotalProducedSamples = totalProducedSamples
        previousObservationNs = nowNs
        if (elapsedNs <= 0L) return estimatedRateHz.roundToInt()

        val observedRateHz = producedDelta.toDouble() * 1_000_000_000.0 / elapsedNs.toDouble()
        if (observedRateHz !in config.minRateHz.toDouble()..config.maxRateHz.toDouble()) {
            return estimatedRateHz.roundToInt()
        }

        estimatedRateHz = if (hasValidObservation) {
            estimatedRateHz + (observedRateHz - estimatedRateHz) * smoothingAlpha
        } else {
            hasValidObservation = true
            observedRateHz
        }
        return estimatedRateHz.roundToInt()
    }
}

internal fun nextPcmConsumptionRateHz(
    currentRateHz: Int,
    producerRateHz: Int,
    bufferedSamples: Int,
    config: PcmPlaybackRateConfig,
): Int {
    val bufferDelta = bufferedSamples - config.targetBufferedSamples
    val bufferCorrectionHz = if (abs(bufferDelta) <= config.bufferDeadbandSamples) {
        0
    } else {
        (bufferDelta / config.bufferCorrectionDivisor).coerceIn(
            -config.maxBufferCorrectionHz,
            config.maxBufferCorrectionHz,
        )
    }
    val desiredRateHz = (producerRateHz + bufferCorrectionHz).coerceIn(
        config.minRateHz,
        config.maxRateHz,
    )
    val rateStepHz = (desiredRateHz - currentRateHz).coerceIn(
        -config.maxRateStepHz,
        config.maxRateStepHz,
    )
    return (currentRateHz + rateStepHz).coerceIn(config.minRateHz, config.maxRateHz)
}
