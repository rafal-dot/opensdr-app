package org.opensdr.sdrcore

/** Writer-owned snapshot; telemetry never waits for a blocking AudioTrack write. */
internal data class AudioPlaybackBufferSnapshot(
    val queuedSamples: Int = 0,
    val capturedAtNs: Long = 0,
    val sampleRateHz: Int = 48_000,
) {
    fun remainingSamples(nowNs: Long): Int {
        val elapsedNs = (nowNs - capturedAtNs).coerceAtLeast(0)
        val playedSamples = elapsedNs / 1_000_000 * sampleRateHz / 1000
        return (queuedSamples - playedSamples).coerceIn(0, queuedSamples.toLong()).toInt()
    }
}
