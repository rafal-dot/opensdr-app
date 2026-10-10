package org.opensdr.sdrcore

import java.util.concurrent.ConcurrentLinkedQueue

/** Transfers failures from writers to the serialized owner of the active track. */
internal class AudioWriterFailures<T : Any> {
    private data class Failure<T>(val track: T, val message: String)
    private val pending = ConcurrentLinkedQueue<Failure<T>>()

    fun report(track: T, message: String) {
        pending.add(Failure(track, message))
    }

    fun takeFor(currentTrack: T?): String? {
        var message: String? = null
        while (true) {
            val failure = pending.poll() ?: return message
            if (failure.track === currentTrack) message = failure.message
        }
    }
}
