package org.opensdr.amhost

import org.json.JSONArray
import org.json.JSONObject
import org.opensdr.sdrcore.CapturePacket
import java.io.Closeable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One session, independent lossless 10-minute IQ/audio chunks, and indexed events. */
internal class CaptureSessionFiles(
    private val storage: CaptureStorage,
    initialSettings: JSONObject,
    private val version: String,
    private val startMs: Long = System.currentTimeMillis(),
    private val startNs: Long = System.nanoTime(),
) : Closeable {
    private var settings = initialSettings
    private val rate = initialSettings.getInt("sample_rate")
    private val iqLimit = rate.toLong() * 2 * 600
    private val audioLimit = 48_000L * 2 * 600
    private val opened = mutableListOf<CaptureFile>()
    private val events: CaptureFile
    private val session: CaptureFile
    private var iqIndex = 0
    private var audioIndex = 0
    private var iq: CaptureFile? = null
    private var audio: CaptureFile? = null
    private var metadata: CaptureFile? = null
    private var iqStart = 0L
    private var iqStartNs = startNs
    private var captures = JSONArray()
    var bytesWritten = 0L
        private set

    init {
        try {
            require(rate in listOf(240_000, 480_000, 960_000))
            events = open("events.jsonl", "application/x-ndjson")
            session = open("session.json", "application/json")
            updateSession("recording", null)
        } catch (error: Exception) {
            runCatching { close() }
            throw error
        }
    }

    private fun open(name: String, mime: String, wav: Boolean = false): CaptureFile =
        storage.create(name, mime, wav).also { opened += it }

    private fun timestamp(ns: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(startMs + (ns - startNs) / 1_000_000))

    private fun settingsCapture(sample: Long): JSONObject = JSONObject().apply {
        put("core:sample_start", sample)
        put("core:frequency", settings.getLong("center_hz"))
        put("opensdr:settings", settings)
    }

    private fun openIq(sample: Long, ns: Long) {
        finishIq()
        iqIndex += 1
        iqStart = sample
        iqStartNs = ns
        val stem = "input-%03d".format(Locale.ROOT, iqIndex)
        iq = open("$stem.sigmf-data", "application/octet-stream")
        // A JSON MIME type can make the system provider append .json, breaking
        // the SigMF basename/extension pairing. Preserve the requested filename.
        metadata = open("$stem.sigmf-meta", "application/octet-stream")
        captures = JSONArray().put(settingsCapture(0).apply {
            put("core:global_index", iqStart)
            put("core:datetime", timestamp(ns))
        })
        updateMetadata(false)
    }

    private fun updateMetadata(complete: Boolean) {
        metadata?.replaceText(JSONObject().apply {
            put("global", JSONObject().apply {
                put("core:datatype", "cu8")
                put("core:sample_rate", rate)
                put("core:version", "1.2.6")
                put("core:recorder", "OpenSDR $version diagnostic")
                put("core:description", "Raw RTL-SDR USB IQ before software DSP; see events.jsonl for settings transitions and timing.")
                put("core:hw", settings.optString("device", "RTL-SDR"))
                put("opensdr:complete", complete)
                put("opensdr:sample_count", (iq?.dataBytes ?: 0) / 2)
                put("opensdr:first_packet_monotonic_ns", iqStartNs)
            })
            put("captures", captures)
            put("annotations", JSONArray())
        }.toString(2) + "\n")
    }

    private fun finishIq() {
        val current = iq ?: return
        current.close()
        updateMetadata(true)
        metadata?.close()
        opened.remove(current)
        opened.remove(metadata)
        iq = null
        metadata = null
    }

    fun packet(packet: CapturePacket, bytes: ByteArray, size: Int) {
        val details = if (packet.kind == 3) JSONObject(String(bytes, 0, size, Charsets.UTF_8)) else JSONObject().apply {
            put("type", if (packet.kind == 1) "iq_block" else "playback_block")
            put("samples", size / 2)
        }
        details.put("monotonic_ns", packet.timeNs)
        details.put("iq_sample", packet.iqSample)
        details.put("audio_frame", packet.audioFrame)
        details.put("produced_frame", packet.producedFrame)
        if (packet.kind == 2) details.put("audiotrack_queued_frames", packet.queuedAudioFrames)
        if (packet.kind == 3 && details.optString("type") == "receiver_state") {
            check(details.getInt("sample_rate") == rate) { "Sample rate changed; start a new recording" }
            settings = JSONObject(String(bytes, 0, size, Charsets.UTF_8))
            iq?.let { captures.put(settingsCapture(packet.iqSample - iqStart)) }
        }
        when (packet.kind) {
            1 -> writeIq(packet, bytes, size)
            2 -> writeAudio(packet, bytes, size)
        }
        events.text(details.toString() + "\n")
    }

    private fun writeIq(packet: CapturePacket, bytes: ByteArray, size: Int) {
        var offset = 0
        while (offset < size) {
            if (iq == null || iq!!.dataBytes == iqLimit) openIq(packet.iqSample + offset / 2, packet.timeNs)
            val current = checkNotNull(iq)
            val count = minOf((size - offset).toLong(), iqLimit - current.dataBytes).toInt()
            current.write(bytes, offset, count)
            offset += count
            bytesWritten += count
        }
    }

    private fun writeAudio(packet: CapturePacket, bytes: ByteArray, size: Int) {
        var offset = 0
        while (offset < size) {
            if (audio == null || audio!!.dataBytes == audioLimit) {
                audio?.let { it.close(); opened.remove(it) }
                audioIndex += 1
                audio = open("audio-%03d.wav".format(Locale.ROOT, audioIndex), "audio/wav", wav = true)
                events.text(JSONObject().apply {
                    put("type", "audio_segment")
                    put("segment", audioIndex)
                    put("audio_frame", packet.audioFrame + offset / 2)
                    put("monotonic_ns", packet.timeNs)
                }.toString() + "\n")
            }
            val current = checkNotNull(audio)
            val count = minOf((size - offset).toLong(), audioLimit - current.dataBytes).toInt()
            current.write(bytes, offset, count)
            offset += count
            bytesWritten += count
        }
    }

    fun flush() {
        opened.forEach { it.flush() }
        updateMetadata(false)
        updateSession("recording", null)
    }

    private fun updateSession(state: String, message: String?) {
        session.replaceText(JSONObject().apply {
            put("format_version", 1)
            put("app_version", version)
            put("start_utc", timestamp(startNs))
            put("start_monotonic_ns", startNs)
            put("state", state)
            put("message", message)
            put("iq_segments", iqIndex)
            put("audio_segments", audioIndex)
            put("data_bytes", bytesWritten)
            put("audio_rate", 48_000)
            put("audio_format", "PCM signed 16-bit little-endian mono")
            put("audio_tap", "Samples accepted by AudioTrack, after playback resampling; not microphone or system mixer output")
            put("timing", "Counters start at capture activation. Playback initially contains older buffered audio. Packet times are monotonic observations, not RF or speaker timestamps. Settings apply within control_begin/receiver_state transitions; USB buffering can straddle tuning. IQ and audio chunks are split independently by sample count. DSP state is not serialized; allow replay warm-up.")
        }.toString(2) + "\n")
    }

    fun finish(error: String?) {
        try {
            finishIq()
            audio?.close()
            events.close()
            updateSession(if (error == null) "complete" else "incomplete", error)
        } finally {
            close()
        }
    }

    override fun close() {
        var failure: Exception? = null
        opened.asReversed().forEach { file ->
            try { file.close() } catch (error: Exception) { failure = failure ?: error }
        }
        opened.clear()
        failure?.let { throw it }
    }
}
