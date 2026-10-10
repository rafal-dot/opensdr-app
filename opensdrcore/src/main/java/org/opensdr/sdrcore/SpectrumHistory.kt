package org.opensdr.sdrcore

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SpectrumHistoryFrame(
    val sequence: Long,
    val timeMs: Long,
    val epoch: Long,
    val centerHz: Long,
    val sampleRateHz: Int,
    val audioDelayMs: Int,
    val bins: IntArray,
)

/** Strict decoder for the small, versioned native history payload; no Parcelable ABI changes. */
fun decodeSpectrumHistory(bytes: ByteArray): List<SpectrumHistoryFrame> {
    if (bytes.isEmpty()) return emptyList()
    require(bytes.size >= 16) { "Truncated spectrum history" }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(buffer.int == 0x53504831) { "Unknown spectrum history version" }
    val bins = buffer.int
    val count = buffer.int
    require(bins == 256 && count in 0..256 && buffer.int == 0 && buffer.remaining() == count * (40 + bins)) {
        "Invalid spectrum history size"
    }
    var previous = 0L
    return List(count) {
        val sequence = buffer.long
        val timeMs = buffer.long
        val epoch = buffer.long
        val center = buffer.long
        val rate = buffer.int
        val delay = buffer.int
        require(sequence > previous && timeMs >= 0 && epoch > 0 && center > 0 && rate > 0 && delay in 0..1000)
        previous = sequence
        SpectrumHistoryFrame(sequence, timeMs, epoch, center, rate, delay,
            IntArray(bins) { (buffer.get().toInt() and 255).also { require(it <= 100) } })
    }
}
