package org.opensdr.sdrcore

import kotlin.math.floor
import kotlin.math.roundToInt

/** Linear, allocation-free PCM resampling with fractional phase and lookahead across blocks. */
internal class StreamingPcmResampler(
    private val outputRateHz: Int,
    private val outputBlockSamples: Int,
    maxInputRateHz: Int,
) {
    val inputCapacity = (outputBlockSamples.toLong() * maxInputRateHz / outputRateHz).toInt() + 3
    private val samples = ShortArray(inputCapacity)
    private var size = 0
    private var phase = 0.0

    fun reset() {
        size = 0
        phase = 0.0
    }

    fun inputSamplesNeeded(inputRateHz: Int): Int {
        val step = inputRateHz.toDouble() / outputRateHz
        val needed = floor(phase + (outputBlockSamples - 1) * step).toInt() + 2
        return (needed - size).coerceAtLeast(0)
    }

    fun append(source: ShortArray, count: Int) {
        require(count in 0..source.size && size + count <= samples.size)
        source.copyInto(samples, size, 0, count)
        size += count
    }

    /** Short input yields fewer samples, never stretches a partial block to fill the output. */
    fun render(destination: ShortArray, inputRateHz: Int): Int {
        require(inputRateHz > 0 && inputRateHz <= outputRateHz * 2)
        val step = inputRateHz.toDouble() / outputRateHz
        var written = 0
        while (written < destination.size) {
            val left = phase.toInt()
            if (left + 1 >= size) break
            val fraction = phase - left
            destination[written++] = (samples[left] + (samples[left + 1] - samples[left]) * fraction)
                .roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            phase += step
        }
        val consumed = phase.toInt().coerceAtMost(size)
        samples.copyInto(samples, 0, consumed, size)
        size -= consumed
        phase -= consumed
        return written
    }
}
