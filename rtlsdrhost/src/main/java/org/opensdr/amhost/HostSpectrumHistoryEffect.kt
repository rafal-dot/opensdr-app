package org.opensdr.amhost

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.opensdr.sdrcore.decodeSpectrumHistory

@Composable
internal fun rememberHostSpectrumFrames(service: Messenger?, streaming: Boolean): List<HostSpectrumFrame> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember(service) { SpectrumViewerState() }
    var frames by remember(service) { mutableStateOf(emptyList<HostSpectrumFrame>()) }
    val isStreaming by rememberUpdatedState(streaming)
    val reply = remember(service) {
        Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.what != SPECTRUM_HISTORY_REPLY || message.arg1 != state.pendingId) return
                state.pendingId = 0
                runCatching {
                    state.store.accept(decodeSpectrumHistory(message.data.getByteArray("frames") ?: byteArrayOf()), spectrumNowMs())
                    frames = state.store.frames
                }
            }
        })
    }
    LaunchedEffect(service, lifecycle) {
        if (service == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            state.pendingId = 0
            while (isActive) {
                val now = spectrumNowMs()
                if (state.pendingId == 0 || now - state.sentAtMs >= 1000) {
                    state.pendingId = ++state.nextId
                    state.sentAtMs = now
                    runCatching {
                        service.send(Message.obtain(null, SPECTRUM_HISTORY_REQUEST, state.pendingId, 0).apply {
                            replyTo = reply
                            data = Bundle().apply { putLong("after", state.store.lastSequence) }
                        })
                    }.onFailure { state.pendingId = 0 }
                }
                delay(if (isStreaming) HOST_STATUS_POLL_STREAMING_MS else HOST_STATUS_POLL_IDLE_MS)
            }
        }
    }
    return frames
}

internal fun spectrumNowMs(): Long = System.nanoTime() / 1_000_000L

private class SpectrumViewerState {
    val store = HostSpectrumFrameStore()
    var nextId = 0
    var pendingId = 0
    var sentAtMs = 0L
}
