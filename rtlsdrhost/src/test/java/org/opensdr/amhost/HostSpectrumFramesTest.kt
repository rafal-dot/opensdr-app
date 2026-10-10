package org.opensdr.amhost

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opensdr.radioapi.AmHostStatus
import org.opensdr.sdrcore.SpectrumHistoryFrame

class HostSpectrumFramesTest {
    @Test
    fun `spectrum follows actual IQ center rather than airband channel label`() {
        val status = AmHostStatus(tunedFrequencyHz = 128_805_000L, tunedCenterFrequencyHz = 128_802_500L)

        assertEquals(128_803_000L, hostSpectrumCenterFrequencyHz(status))
    }

    @Test
    fun `literal tuning includes hardware offset and frequency correction`() {
        val status = AmHostStatus(tunedFrequencyHz = 118_300_000L, frequencyCorrectionHz = -200)

        assertEquals(118_300_300L, hostSpectrumCenterFrequencyHz(status))
        assertNull(hostSpectrumCenterFrequencyHz(AmHostStatus()))
    }

    @Test
    fun `duplicate polls do not create extra history`() {
        val store = HostSpectrumFrameStore()
        val frame = frame(1, 1000)
        store.accept(listOf(frame), 1100)
        store.accept(listOf(frame), 1200)
        assertEquals(1, store.frames.size)
        assertEquals(1L, store.lastSequence)
    }

    @Test fun `recreated viewer restores the same history as incremental polling`() {
        val source = (1L..30).map { frame(it, it * 100, level = (it * 3).toInt()) }
        val live = HostSpectrumFrameStore()
        source.forEach { live.accept(listOf(it), it.timeMs) }
        val recreated = HostSpectrumFrameStore()
        recreated.accept(source, 3000)
        assertEquals(live.frames.map { it.presentationTimeMs }, recreated.frames.map { it.presentationTimeMs })
        live.frames.zip(recreated.frames).forEach { (a, b) -> assertArrayEquals(a.levels, b.levels) }
    }

    @Test fun `retune preserves past sections and does not smooth different bands together`() {
        val store = HostSpectrumFrameStore()
        store.accept(listOf(frame(1, 1000, level = 100), frame(2, 2000, epoch = 2, level = 0)), 2300)
        assertEquals(2, store.frames.size)
        assertArrayEquals(IntArray(256), store.frames.last().levels)
        val labels = waterfallBandLabels(waterfallRowsAt(store.frames, 2250))
        assertEquals(2, labels.size)
        assertTrue(labels[0].text.contains("118.300"))
        assertTrue(labels[1].text.contains("128.803"))
    }

    @Test fun `retune drops only the unheard old audio tail`() {
        val store = HostSpectrumFrameStore()
        store.accept(listOf(frame(1, 1000), frame(2, 2100, delay = 800)), 2200)
        store.accept(listOf(frame(3, 2250, epoch = 2)), 2500)
        assertEquals(listOf(1L, 3L), store.frames.map { it.source.sequence })
    }

    @Test fun `presentation waits for audio and timeline keeps advancing without IPC`() {
        val store = HostSpectrumFrameStore()
        store.accept(listOf(frame(1, 1000, delay = 300)), 1000)
        assertNull(waterfallRowsAt(store.frames, 1500)[0])
        assertEquals(1L, waterfallRowsAt(store.frames, 1625)[0]?.source?.sequence)
        assertNull(waterfallRowsAt(store.frames, 2250)[0])
        assertEquals(1L, waterfallRowsAt(store.frames, 2250)[5]?.source?.sequence)
    }

    @Test fun `buffer delay changes cannot reverse the frame order`() {
        val store = HostSpectrumFrameStore()
        store.accept(listOf(frame(1, 1000, delay = 800), frame(2, 1100)), 1200)
        assertTrue(store.frames[1].presentationTimeMs > store.frames[0].presentationTimeMs)
    }

    @Test fun `old frames expire even if the receiver has stopped`() {
        val store = HostSpectrumFrameStore()
        store.accept(listOf(frame(1, 1000)), 1000)
        store.accept(emptyList(), 24000)
        assertTrue(store.frames.isEmpty())
        assertEquals(1L, store.lastSequence)
    }

    @Test fun `time tick shifts past rows by exactly one without new IPC data`() {
        val store = HostSpectrumFrameStore()
        store.accept((1L..15).map { frame(it, it * 125) }, 1875)
        val before = waterfallRowsAt(store.frames, 1750)
        val after = waterfallRowsAt(store.frames, 1875)
        assertEquals(before.dropLast(1), after.drop(1))
    }

    @Test fun `sample rate change retains separate sections with their original spans`() {
        val store = HostSpectrumFrameStore()
        val oldBand = frame(1, 1000)
        val newBand = frame(2, 1250).copy(epoch = 2, sampleRateHz = 960000)
        store.accept(listOf(oldBand, newBand), 1500)
        val labels = waterfallBandLabels(waterfallRowsAt(store.frames, 1500))
        assertEquals(listOf("IQ 128.803 MHz / 960 kHz", "IQ 128.803 MHz / 240 kHz"), labels.map { it.text })
    }

    private fun frame(sequence: Long, time: Long, epoch: Long = 1, level: Int = 42, delay: Int = 0) =
        SpectrumHistoryFrame(sequence, time, epoch, if (epoch == 1L) 128803000 else 118300000,
            240000, delay, IntArray(256) { level })
}
