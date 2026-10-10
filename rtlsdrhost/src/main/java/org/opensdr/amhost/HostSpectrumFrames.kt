package org.opensdr.amhost

import org.opensdr.radioapi.AmHostStatus
import org.opensdr.sdrcore.SpectrumHistoryFrame
import org.opensdr.sdrcore.zeroIfSafeHardwareCenterFrequencyHz
import kotlin.math.pow
import kotlin.math.roundToInt

internal fun hostSpectrumCenterFrequencyHz(status: AmHostStatus): Long? {
    val searchCenter = status.tunedCenterFrequencyHz ?: status.tunedFrequencyHz ?: return null
    // FFT bins are raw tuner IQ, before the demodulator's carrier tracking.
    return zeroIfSafeHardwareCenterFrequencyHz(searchCenter) + status.frequencyCorrectionHz
}

internal data class HostSpectrumFrame(
    val source: SpectrumHistoryFrame,
    val presentationTimeMs: Long,
    val levels: IntArray,
)

/** A viewer cache, not the owner of history. Can be rebuilt after Activity recreation. */
internal class HostSpectrumFrameStore {
    var lastSequence = 0L
        private set
    var frames = emptyList<HostSpectrumFrame>()
        private set

    fun accept(incoming: List<SpectrumHistoryFrame>, nowMs: Long) {
        val updated = frames.filter { nowMs - it.source.timeMs <= 22_000 }.toMutableList()
        for (frame in incoming) {
            if (frame.sequence <= lastSequence) continue
            lastSequence = frame.sequence
            if (nowMs - frame.timeMs > 22_000) continue
            val expectedTime = frame.timeMs + (frame.audioDelayMs + HOST_SPECTRUM_AUDIO_OUTPUT_DELAY_MS).coerceAtMost(1000)
            if (updated.lastOrNull()?.source?.epoch != frame.epoch) {
                // Retuning flushes queued audio. Do not later display the old,
                // not-yet-heard tail over the new band's presentation timeline.
                updated.removeAll { it.presentationTimeMs >= expectedTime }
            }
            val previous = updated.lastOrNull()
            val presentation = maxOf(expectedTime, (previous?.presentationTimeMs ?: 0) + 1)
            val sameBand = previous?.takeIf { it.source.epoch == frame.epoch &&
                frame.timeMs - it.source.timeMs <= HOST_WATERFALL_MAX_GAP_MS }
            updated.add(HostSpectrumFrame(frame, presentation, smoothSpectrumFrame(sameBand, frame)))
        }
        frames = updated.takeLast(256)
    }
}

/** Fixed time rows avoid tying scroll speed to USB bursts, IPC arrivals or UI polling. */
internal fun waterfallRowsAt(frames: List<HostSpectrumFrame>, timeMs: Long): List<HostSpectrumFrame?> {
    var index = frames.lastIndex
    return List(WATERFALL_ROW_COUNT + 1) { row ->
        val rowTime = timeMs - row * HOST_WATERFALL_ROW_MS
        while (index >= 0 && frames[index].presentationTimeMs > rowTime) index--
        frames.getOrNull(index)?.takeIf { rowTime - it.presentationTimeMs <= HOST_WATERFALL_MAX_GAP_MS }
    }
}

private fun smoothSpectrumFrame(previous: HostSpectrumFrame?, frame: SpectrumHistoryFrame): IntArray {
    val previousWeight = if (previous == null) 0f else
        0.84.pow((frame.timeMs - previous.source.timeMs).coerceAtLeast(1) / 125.0).toFloat()
    val temporal = IntArray(frame.bins.size) { i ->
        ((previous?.levels?.getOrNull(i) ?: 0) * previousWeight + frame.bins[i] * (1f - previousWeight))
            .roundToInt().coerceIn(0, 100)
    }
    return IntArray(temporal.size) { i ->
        val left = temporal.getOrNull(i - 1) ?: temporal[i]
        val right = temporal.getOrNull(i + 1) ?: temporal[i]
        (left * 0.21f + temporal[i] * 0.58f + right * 0.21f).roundToInt().coerceIn(0, 100)
    }
}
