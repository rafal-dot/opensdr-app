package org.opensdr.sdrcore

import java.nio.ByteBuffer

/** Only included in internal APK variants; the native tap follows the same gate. */
object DiagnosticCapture {
    @Volatile private var enabled = false

    fun start(): Boolean {
        if (!DirectRtlSdrNativeBridge.isAvailable) return false
        return nativeStart().also { enabled = it }
    }

    fun stop() {
        enabled = false
        if (DirectRtlSdrNativeBridge.isAvailable) nativeStop()
    }

    fun status(): Int = nativeStatus()
    fun drain(buffer: ByteBuffer): Int = nativeDrain(buffer)

    fun event(json: String) {
        if (enabled) nativeEvent(json.toByteArray(Charsets.UTF_8))
    }

    internal fun audio(samples: ShortArray, offset: Int, count: Int, queuedFrames: Int) {
        if (enabled) nativeAudio(samples, offset, count, queuedFrames.toLong())
    }

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeStatus(): Int
    private external fun nativeDrain(buffer: ByteBuffer): Int
    private external fun nativeEvent(json: ByteArray)
    private external fun nativeAudio(samples: ShortArray, offset: Int, count: Int, queuedFrames: Long)
}

internal object PlaybackCapture {
    fun accepted(samples: ShortArray, offset: Int, count: Int, queuedFrames: Int) =
        DiagnosticCapture.audio(samples, offset, count, queuedFrames)
}
