package org.opensdr.sdrcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

class StreamingPcmResamplerTest {
    private fun sample(index: Int): Short = (12000 * sin(index * 0.37)).roundToInt().toShort()

    @Test
    fun unityRatePreservesEverySampleAcrossBlocks() {
        val resampler = StreamingPcmResampler(48000, 1200, 60000)
        var inputIndex = 0
        var outputIndex = 0
        repeat(40) {
            val input = ShortArray(resampler.inputSamplesNeeded(48000)) { sample(inputIndex++) }
            resampler.append(input, input.size)
            val output = ShortArray(1200)
            assertEquals(output.size, resampler.render(output, 48000))
            output.forEach { assertEquals(sample(outputIndex++), it) }
        }
    }

    @Test
    fun fractionalPhaseSurvivesBlocksAndRateChanges() {
        val resampler = StreamingPcmResampler(48000, 1200, 60000)
        var inputIndex = 0
        var position = 0.0
        for (rate in listOf(36000, 44001, 48000, 52999, 60000, 42000)) {
            repeat(20) {
                val input = ShortArray(resampler.inputSamplesNeeded(rate)) { sample(inputIndex++) }
                resampler.append(input, input.size)
                val output = ShortArray(1200)
                assertEquals(output.size, resampler.render(output, rate))
                for (actual in output) {
                    val left = floor(position).toInt()
                    val fraction = position - left
                    val expected = (sample(left) + (sample(left + 1) - sample(left)) * fraction).roundToInt()
                    assertTrue("rate=$rate position=$position expected=$expected actual=$actual", kotlin.math.abs(expected - actual) <= 1)
                    position += rate / 48000.0
                }
            }
        }
    }

    @Test
    fun partialInputIsRetainedWithoutStretching() {
        val resampler = StreamingPcmResampler(48000, 4, 60000)
        val output = ShortArray(4)
        resampler.append(shortArrayOf(10), 1)
        assertEquals(0, resampler.render(output, 48000))
        resampler.append(shortArrayOf(20, 30, 40, 50), 4)
        assertEquals(4, resampler.render(output, 48000))
        assertTrue(output.contentEquals(shortArrayOf(10, 20, 30, 40)))
        resampler.reset()
        assertEquals(5, resampler.inputSamplesNeeded(48000))
    }
}
