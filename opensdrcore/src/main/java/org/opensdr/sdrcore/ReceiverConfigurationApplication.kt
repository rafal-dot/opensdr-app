package org.opensdr.sdrcore

/** Tracks confirmed hardware state, not merely the last requested preferences. */
internal class ReceiverConfigurationApplication<T> {
    private var confirmed: T? = null
    var error: String? = null
        private set

    fun isApplied(settings: T): Boolean = confirmed == settings && error == null

    fun confirm(settings: T) {
        confirmed = settings
        error = null
    }

    fun invalidate() {
        confirmed = null
        error = null
    }

    fun apply(settings: T, write: (T?) -> String?): String? {
        val previous = confirmed
        // Even a partial failure invalidates the baseline for incremental updates.
        confirmed = null
        error = "Receiver configuration update did not complete"
        error = write(previous)
        if (error == null) confirmed = settings
        return error
    }
}
