package org.opensdr.amhost.sdr

/**
 * Keeps the receiver's own service started while playing, not merely bound to a
 * client UI. Serializes IPC/audio callbacks with Android's service callbacks.
 */
internal class ReceiverServiceLifetime(
    private val startService: () -> Unit,
    private val showForeground: (String?) -> Unit,
    private val releaseForeground: () -> Unit,
    private val stopService: (Int?) -> Unit,
) {
    private var playing = false
    private var destroyed = false

    @Synchronized
    fun start(frequencyText: String?) {
        check(!destroyed) { "Receiver service is being destroyed" }
        try {
            if (!playing) startService()
            showForeground(frequencyText)
            playing = true
        } catch (failure: Exception) {
            stop()
            throw failure
        }
    }

    @Synchronized
    fun stop() {
        playing = false
        try {
            releaseForeground()
        } finally {
            if (!destroyed) stopService(null)
        }
    }

    @Synchronized
    fun onStartCommand(startId: Int) {
        // Delivery can lag behind Stop or another Start. Never replay a start
        // here, nor let an old idle command stop newer, active playback.
        if (!playing) stopService(startId)
    }

    @Synchronized
    fun destroy() {
        destroyed = true
        stop()
    }
}
