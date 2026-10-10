package org.opensdr.sdrcore

internal class PcmOutputException(val resultCode: Int) :
    IllegalStateException("Audio output write failed: $resultCode")

/** A failed block must stop playback, not silently discard subsequent PCM. */
internal inline fun writePcmBlock(
    sampleCount: Int,
    isActive: () -> Boolean,
    write: (offset: Int, count: Int) -> Int,
    onWritten: (Int) -> Unit,
) {
    var offset = 0
    while (offset < sampleCount && isActive()) {
        val written = write(offset, sampleCount - offset)
        if (written <= 0) {
            if (isActive()) throw PcmOutputException(written)
            return
        }
        offset += written
        onWritten(written)
    }
}
