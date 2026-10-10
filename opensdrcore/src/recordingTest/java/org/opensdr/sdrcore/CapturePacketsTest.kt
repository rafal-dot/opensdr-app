package org.opensdr.sdrcore

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CapturePacketsTest {
    private fun packet(kind: Int = 1, size: Int = 4): ByteArray =
        ByteBuffer.allocate(48 + size.coerceAtLeast(0)).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(kind); putInt(size)
            putLong(100); putLong(200); putLong(300); putLong(400); putLong(500)
            repeat(size.coerceAtLeast(0)) { put(it.toByte()) }
        }.array()

    @Test fun arbitraryDrainBoundariesPreserveCountersAndData() {
        val decoded = mutableListOf<CapturePacket>()
        val payloads = mutableListOf<ByteArray>()
        val decoder = CapturePacketDecoder { frame, bytes, size ->
            decoded += frame
            payloads += bytes.copyOf(size)
        }
        val data = packet() + packet(kind = 2) + packet(kind = 3, size = 0)
        data.forEach { decoder.accept(ByteBuffer.wrap(byteArrayOf(it))) }
        decoder.finish()
        assertEquals(3, decoded.size)
        assertEquals(CapturePacket(1, 100, 200, 300, 400, 500), decoded.first())
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), payloads.first())
        assertEquals(0, payloads.last().size)
    }

    @Test fun rejectsIncompleteData() {
        val decoder = CapturePacketDecoder { _, _, _ -> fail("Incomplete packet emitted") }
        decoder.accept(ByteBuffer.wrap(packet().copyOf(51)))
        assertThrows(IllegalStateException::class.java) { decoder.finish() }
    }

    @Test fun rejectsInvalidKindSizeAndAlignment() {
        for (bytes in listOf(packet(kind = 9), packet(size = -1), packet(size = 3), packet(size = 65_538))) {
            assertThrows(IllegalArgumentException::class.java) {
                CapturePacketDecoder { _, _, _ -> fail("Bad packet emitted") }.accept(ByteBuffer.wrap(bytes))
            }
        }
    }

    @Test fun wavUsesMono16Bit48kAndExactFinalSizes() {
        val header = captureWavHeader(96_000)
        val decoded = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals(96_036, decoded.getInt(4))
        assertEquals(1, decoded.getShort(22).toInt())
        assertEquals(48_000, decoded.getInt(24))
        assertEquals(16, decoded.getShort(34).toInt())
        assertEquals(96_000, decoded.getInt(40))
        assertThrows(IllegalArgumentException::class.java) { captureWavHeader(1) }
        assertThrows(IllegalArgumentException::class.java) { captureWavHeader(0xffffffffL) }
    }
}
