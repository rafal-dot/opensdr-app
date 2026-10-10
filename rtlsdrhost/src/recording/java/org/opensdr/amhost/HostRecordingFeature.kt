package org.opensdr.amhost

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.Process
import org.json.JSONObject
import org.opensdr.radioapi.RtlSdrIpcContract
import org.opensdr.sdrcore.CapturePacketDecoder
import org.opensdr.sdrcore.DiagnosticCapture
import org.opensdr.sdrcore.DirectRtlBackendState
import org.opensdr.sdrcore.DirectRtlSdrController
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal const val RECORDING_COMMAND = 0x534452
internal const val RECORDING_STATUS = 0x534453

/** Owned by the receiver service, not the Activity; no public radioapi extension. */
internal class HostRecordingFeature(private val context: Context) {
    @Volatile private var latest = DirectRtlBackendState()
    @Volatile private var busy = false
    @Volatile private var clearing = false
    @Volatile private var message = "Choose a local folder, then start IQ + audio recording."
    @Volatile private var bytesWritten = 0L
    @Volatile private var startedNs = 0L
    @Volatile private var elapsedSeconds = 0L
    private val stopRequested = AtomicBoolean(false)
    private var lastSettings = ""
    private var lastTelemetryNs = 0L

    fun observe(state: DirectRtlBackendState) {
        latest = state
        if (!busy) return
        if (!state.streaming) stopRequested.set(true)
        val settings = state.captureSettings().toString()
        if (settings != lastSettings) {
            DiagnosticCapture.event(settings)
            lastSettings = settings
        }
        val now = System.nanoTime()
        if (now - lastTelemetryNs >= 1_000_000_000L) {
            DiagnosticCapture.event(JSONObject().apply {
                put("type", "playback_status")
                put("buffer_samples", state.audioBufferSamples)
                put("produced_per_second", state.pcmProducedSamplesPerSecond)
                put("consumed_per_second", state.pcmConsumedSamplesPerSecond)
                put("drift", state.driftSnapshotText)
            }.toString())
            lastTelemetryNs = now
        }
    }

    fun beforeCommand(message: Message) {
        if (busy && message.what != RtlSdrIpcContract.MSG_GET_STATUS) {
            DiagnosticCapture.event(JSONObject().apply {
                put("type", "control_begin")
                put("command", message.what)
                if (message.data.containsKey(RtlSdrIpcContract.KEY_FREQUENCY_HZ))
                    put("channel_hz", message.data.getLong(RtlSdrIpcContract.KEY_FREQUENCY_HZ))
            }.toString())
        }
    }

    fun handle(request: Message, controller: DirectRtlSdrController): Boolean {
        if (request.what != RECORDING_COMMAND) return false
        // Even a signature-authorized external client must not control file capture.
        if (request.sendingUid != Process.myUid()) return true
        runCatching {
            when (request.data.getString("action")) {
                "start" -> if (!busy && !clearing) {
                    observe(controller.refreshState())
                    val uri = request.data.getString("tree")
                    if (!latest.streaming || uri == null) message = "Start the receiver and choose a local folder first."
                    else start(Uri.parse(uri))
                }
                "stop" -> if (busy) { stopRequested.set(true); message = "Stopping and saving..." }
                "mark" -> if (busy) DiagnosticCapture.event("{\"type\":\"user_problem_marker\"}")
                "clear" -> {
                    check(!busy && !clearing) { "Stop recording and wait until saving finishes before clearing the folder." }
                    val tree = checkNotNull(request.data.getString("tree")) { "Choose a recordings folder first." }
                    clearFolder(Uri.parse(tree))
                }
            }
        }.onFailure { message = "Recording request failed: ${it.message ?: it.javaClass.simpleName}" }
        val reply = Message.obtain(null, RECORDING_STATUS).apply {
            data = Bundle().apply {
                putBoolean("busy", busy)
                putBoolean("clearing", clearing)
                putBoolean("storageBusy", storageOwner.get())
                putString("action", request.data.getString("action"))
                putString("message", message)
                putLong("bytes", bytesWritten)
                putLong("seconds", if (busy && startedNs > 0) (System.nanoTime() - startedNs) / 1_000_000_000 else elapsedSeconds)
            }
        }
        runCatching { request.replyTo?.send(reply) }
        return true
    }

    private fun start(tree: Uri) {
        if (!acquireStorage()) return
        stopRequested.set(false)
        busy = true
        bytesWritten = 0
        elapsedSeconds = 0
        startedNs = 0
        message = "Preparing recording..."
        lastSettings = ""
        try {
            thread(name = "OpenSdrCaptureWriter") { record(tree) }
        } catch (error: Exception) {
            busy = false
            storageOwner.set(false)
            throw error
        }
    }

    private fun acquireStorage(): Boolean {
        if (storageOwner.compareAndSet(false, true)) return true
        message = "Recording or folder cleanup is still in progress. Try again shortly."
        return false
    }

    private fun clearFolder(tree: Uri) {
        if (!acquireStorage()) return
        clearing = true
        message = "Clearing recording folder..."
        try {
            thread(name = "OpenSdrCaptureCleanup") {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    val deleted = clearRecordingDocuments(context.contentResolver, tree)
                    bytesWritten = 0
                    elapsedSeconds = 0
                    message = "Folder cleared: deleted ${deleted.files} files and ${deleted.folders} subfolders."
                } catch (error: Exception) {
                    val deleted = (error as? RecordingFolderClearFailure)?.deleted ?: RecordingFolderClearResult()
                    message = "Cleanup failed after deleting ${deleted.files} files and ${deleted.folders} subfolders: ${error.message}. Some data may remain."
                } finally {
                    clearing = false
                    storageOwner.set(false)
                }
            }
        } catch (error: Exception) {
            clearing = false
            storageOwner.set(false)
            throw error
        }
    }

    private fun record(tree: Uri) {
        var files: CaptureSessionFiles? = null
        val buffer = ByteBuffer.allocateDirect(262_144)
        var started = false
        var errorText: String? = null
        var sessionName = ""
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val name = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT).format(Date()) + "_" + UUID.randomUUID().toString().take(8)
            sessionName = name
            val storage = CaptureStorage(context.contentResolver, tree, name)
            check(latest.streaming && !stopRequested.get()) { "Recording cancelled before start" }
            files = CaptureSessionFiles(storage, latest.captureSettings(), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
            check(DiagnosticCapture.start()) { "Cannot start native recording buffer" }
            started = true
            startedNs = System.nanoTime()
            DiagnosticCapture.event(latest.captureSettings().toString())
            message = "Recording: $name"
            val decoder = CapturePacketDecoder { packet, data, size -> files.packet(packet, data, size) }
            var lastFlushNs = startedNs
            while (true) {
                if (stopRequested.get()) DiagnosticCapture.stop()
                val count = DiagnosticCapture.drain(buffer)
                if (count > 0) {
                    buffer.position(0).limit(count)
                    decoder.accept(buffer)
                    bytesWritten = files.bytesWritten
                } else if (DiagnosticCapture.status() != 1) {
                    // The producer may stop between the first drain and status read.
                    // Drain again after the terminal status before declaring EOF.
                    val tail = DiagnosticCapture.drain(buffer)
                    if (tail == 0) break
                    buffer.position(0).limit(tail)
                    decoder.accept(buffer)
                } else Thread.sleep(40)
                val now = System.nanoTime()
                if (now - lastFlushNs >= 3_000_000_000L) {
                    files.flush()
                    lastFlushNs = now
                }
            }
            decoder.finish()
            if (DiagnosticCapture.status() == 2) errorText = "Recording buffer overflow; capture ended at the last complete packet. Radio was not blocked."
        } catch (error: Exception) {
            errorText = "Recording failed: ${error.message ?: error.javaClass.simpleName}"
        } finally {
            if (started) {
                DiagnosticCapture.stop()
                // A failed provider must not leave stale packets in the next session.
                while (DiagnosticCapture.drain(buffer) > 0) Unit
            }
            try { files?.finish(errorText) } catch (error: Exception) {
                errorText = "Recording finalization failed: ${error.message}. Files may be incomplete."
            }
            bytesWritten = files?.bytesWritten ?: 0
            elapsedSeconds = if (startedNs > 0) (System.nanoTime() - startedNs) / 1_000_000_000 else 0
            message = errorText ?: "Recording saved: $sessionName. IQ, WAV and metadata are in the selected folder."
            busy = false
            storageOwner.set(false)
        }
    }

    fun close() { stopRequested.set(true) }

    companion object {
        // Both writing/finalizing and destructive cleanup share this process-wide
        // ownership, including across service recreation. UI checks are not enough.
        private val storageOwner = AtomicBoolean(false)
    }
}
