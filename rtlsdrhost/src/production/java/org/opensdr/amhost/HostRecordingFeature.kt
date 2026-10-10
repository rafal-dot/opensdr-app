@file:Suppress("UNUSED_PARAMETER")

package org.opensdr.amhost

import android.content.Context
import android.os.Message
import android.os.Messenger
import androidx.compose.runtime.Composable
import org.opensdr.sdrcore.DirectRtlBackendState
import org.opensdr.sdrcore.DirectRtlSdrController

// Production builds compile only this inert integration boundary, not capture code.
internal class HostRecordingFeature(context: Context) {
    fun observe(state: DirectRtlBackendState) = Unit
    fun beforeCommand(message: Message) = Unit
    fun handle(request: Message, controller: DirectRtlSdrController): Boolean = false
    fun close() = Unit
}

@Composable
internal fun RecordingCard(service: Messenger?, streaming: Boolean) = Unit
