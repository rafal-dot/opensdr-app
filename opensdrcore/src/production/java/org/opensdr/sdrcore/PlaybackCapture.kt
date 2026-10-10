@file:Suppress("UNUSED_PARAMETER", "NOTHING_TO_INLINE")

package org.opensdr.sdrcore

// The release source set has no recorder, JNI entry points or storage access.
internal object PlaybackCapture {
    inline fun accepted(samples: ShortArray, offset: Int, count: Int, queuedFrames: Int) = Unit
}
