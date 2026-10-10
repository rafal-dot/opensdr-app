package org.opensdr.sdrcore

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class SpectrumHistoryTest {
    private fun packet(count: Int = 1): ByteArray = ByteBuffer.allocate(16 + count * 296)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x53504831).putInt(256).putInt(count).putInt(0)
            repeat(count) { i ->
                putLong(i + 1L).putLong(1000L + i * 100).putLong(2).putLong(128803000)
                putInt(240000).putInt(300)
                repeat(256) { put(42.toByte()) }
            }
        }.array()

    @Test fun readsMetadataAndBins() {
        val frames = decodeSpectrumHistory(packet(2))
        assertEquals(2, frames.size)
        assertEquals(2L, frames.last().sequence)
        assertEquals(1100L, frames.last().timeMs)
        assertEquals(128803000L, frames.last().centerHz)
        assertEquals(240000, frames.last().sampleRateHz)
        assertEquals(300, frames.last().audioDelayMs)
        assertArrayEquals(IntArray(256) { 42 }, frames.last().bins)
    }

    @Test fun acceptsEmptyBatches() {
        assertTrue(decodeSpectrumHistory(byteArrayOf()).isEmpty())
        assertTrue(decodeSpectrumHistory(packet(0)).isEmpty())
    }

    @Test fun rejectsTruncationAndOversizedBatches() {
        assertThrows(IllegalArgumentException::class.java) { decodeSpectrumHistory(packet().copyOf(30)) }
        assertThrows(IllegalArgumentException::class.java) { decodeSpectrumHistory(packet(257)) }
        assertThrows(IllegalArgumentException::class.java) { decodeSpectrumHistory(packet() + byteArrayOf(0)) }
    }

    @Test fun rejectsWrongVersionDuplicateSequencesAndInvalidLevels() {
        val version = packet().apply { this[0] = 0 }
        assertThrows(IllegalArgumentException::class.java) { decodeSpectrumHistory(version) }
        val duplicate = packet(2).apply { this[16 + 296] = 1 }
        assertThrows(IllegalArgumentException::class.java) { decodeSpectrumHistory(duplicate) }
        val level = packet().apply { this[56] = 101 }
        assertThrows(IllegalArgumentException::class.java) { decodeSpectrumHistory(level) }
    }
}
