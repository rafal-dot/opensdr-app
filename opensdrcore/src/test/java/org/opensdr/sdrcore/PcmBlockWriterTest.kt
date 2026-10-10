package org.opensdr.sdrcore

import org.junit.Assert.*
import org.junit.Test

class PcmBlockWriterTest {
    @Test fun partialWritesPreserveEverySample() {
        val requests = mutableListOf<Pair<Int, Int>>()
        var delivered = 0
        writePcmBlock(10, { true }, { offset, count ->
            requests += offset to count
            minOf(4, count)
        }, { delivered += it })
        assertEquals(listOf(0 to 10, 4 to 6, 8 to 2), requests)
        assertEquals(10, delivered)
    }

    @Test fun failedAndZeroWritesStopInsteadOfDiscardingAudio() {
        for (error in listOf(-6, -3, -2, -1, 0)) {
            var calls = 0
            var delivered = 0
            val failure = assertThrows(PcmOutputException::class.java) {
                writePcmBlock(10, { true }, { _, _ ->
                    calls++
                    if (calls == 1) 4 else error
                }, { delivered += it })
            }
            assertEquals(error, failure.resultCode)
            assertEquals(2, calls)
            assertEquals(4, delivered)
        }
    }

    @Test fun explicitStopIsNotReportedAsAnOutputFailure() {
        var active = true
        writePcmBlock(10, { active }, { _, _ -> active = false; -3 }, { fail("No PCM was written") })
        writePcmBlock(10, { false }, { _, _ -> fail("Already stopped"); 0 }, {})
    }

    @Test fun bufferSnapshotDecaysWithoutTouchingAudioTrack() {
        val snapshot = AudioPlaybackBufferSnapshot(4800, 1_000_000_000)
        assertEquals(4800, snapshot.remainingSamples(1_000_000_000))
        assertEquals(2400, snapshot.remainingSamples(1_050_000_000))
        assertEquals(0, snapshot.remainingSamples(1_200_000_000))
        assertEquals(4800, snapshot.remainingSamples(900_000_000))
    }

    @Test fun lateFailureFromReplacedTrackCannotStopNewSessionOrHideItsFailure() {
        val failures = AudioWriterFailures<Any>()
        val old = Any()
        val current = Any()
        failures.report(old, "old")
        assertNull(failures.takeFor(current))
        failures.report(current, "current output failed")
        failures.report(old, "late old failure")
        assertEquals("current output failed", failures.takeFor(current))
        assertNull(failures.takeFor(current))
    }
}
