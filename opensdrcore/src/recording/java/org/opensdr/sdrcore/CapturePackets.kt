package org.opensdr.sdrcore

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class CapturePacket(
    val kind: Int,
    val timeNs: Long,
    val iqSample: Long,
    val audioFrame: Long,
    val producedFrame: Long,
    val queuedAudioFrames: Long,
)

/** Decodes arbitrary JNI drain boundaries without allocating a buffer per packet. */
class CapturePacketDecoder(private val consume: (CapturePacket, ByteArray, Int) -> Unit) {
    private val header = ByteArray(48)
    private val payload = ByteArray(65_536)
    private var headerSize = 0
    private var payloadSize = 0
    private var expectedSize = 0
    private var current: CapturePacket? = null

    fun accept(input: ByteBuffer) {
        while (input.hasRemaining()) {
            if (headerSize < header.size) {
                val count = minOf(input.remaining(), header.size - headerSize)
                input.get(header, headerSize, count)
                headerSize += count
                if (headerSize != header.size) continue
                val decoded = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val kind = decoded.int
                expectedSize = decoded.int
                require(kind in 1..3 && expectedSize in 0..payload.size) { "Invalid capture packet" }
                require(kind == 3 || expectedSize % 2 == 0) { "Unaligned capture samples" }
                current = CapturePacket(kind, decoded.long, decoded.long, decoded.long, decoded.long, decoded.long)
            }
            val count = minOf(input.remaining(), expectedSize - payloadSize)
            input.get(payload, payloadSize, count)
            payloadSize += count
            if (payloadSize == expectedSize) {
                consume(checkNotNull(current), payload, payloadSize)
                headerSize = 0
                payloadSize = 0
                current = null
            }
        }
    }

    fun finish() {
        check(headerSize == 0 && payloadSize == 0) { "Incomplete capture packet" }
    }
}

/** WAV sizes are finalized after draining; segments remain far below RIFF's 4 GB limit. */
fun captureWavHeader(dataBytes: Long, sampleRate: Int = 48_000): ByteArray {
    require(dataBytes in 0..(0xffffffffL - 36) && dataBytes % 2 == 0L)
    return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt((dataBytes + 36).toInt())
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(sampleRate)
        putInt(sampleRate * 2)
        putShort(2)
        putShort(16)
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(dataBytes.toInt())
    }.array()
}
