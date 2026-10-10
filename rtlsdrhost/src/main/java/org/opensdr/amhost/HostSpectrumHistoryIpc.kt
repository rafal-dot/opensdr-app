package org.opensdr.amhost

import android.os.Bundle
import android.os.Message
import android.os.Process
import org.opensdr.sdrcore.DirectRtlSdrNativeBridge

// Private viewer protocol. Do not attach history to routine/public status replies.
internal const val SPECTRUM_HISTORY_REQUEST = 0x535048
internal const val SPECTRUM_HISTORY_REPLY = 0x535049

internal fun handleSpectrumHistoryRequest(request: Message): Boolean {
    if (request.what != SPECTRUM_HISTORY_REQUEST) return false
    if (request.sendingUid != Process.myUid()) return true
    runCatching {
        val bytes = DirectRtlSdrNativeBridge.spectrumHistory(request.data.getLong("after"))
        request.replyTo?.send(Message.obtain(null, SPECTRUM_HISTORY_REPLY, request.arg1, 0).apply {
            data = Bundle().apply { putByteArray("frames", bytes) }
        })
    }
    return true
}
