package org.opensdr.sdrcore

import org.opensdr.radioapi.ReceiverSettingRules

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlin.math.abs

const val RTL_SQUELCH_MODE_AUTO = 0
const val RTL_SQUELCH_MODE_MANUAL = 1
const val RTL_SQUELCH_MODE_CUSTOM = 2
const val DENOISE_MODE_OFF = 0
const val DENOISE_MODE_MILD = 1
const val DENOISE_MODE_STRONG = 2
const val DENOISE_MODE_AUTO = 3
const val NOTCH_MODE_OFF = 0
const val NOTCH_MODE_AUTO = 1
const val DEMOD_MODE_AM_ENVELOPE = 1
const val DEMOD_MODE_AM_SYNC = 2
const val DEMOD_MODE_SSB = 3
const val DEMOD_MODE_NFM = 4
const val DEMOD_MODE_WFM = 5
const val AM_SIDEBAND_MODE_FULL = 0
const val AM_SIDEBAND_MODE_LOWER = 1
const val AM_SIDEBAND_MODE_UPPER = 2
const val SPEECH_ENHANCER_MODE_OFF = 0
const val SPEECH_ENHANCER_MODE_MILD = 1
const val SPEECH_ENHANCER_MODE_STRONG = 2

data class DirectRtlDeviceInfo(
    val deviceName: String,
    val productName: String,
    val vendorId: Int,
    val productId: Int,
    val interfaceCount: Int,
    val hasPermission: Boolean,
)

/**
 * Host-side receiver settings persisted by the controller and pushed into the
 * native pipeline. Client IPC models are translated into this internal model by
 * `rtlsdrhost`, so `opensdrcore` remains independent from Android Messenger API.
 */
data class DirectRtlReceiverSettings(
    val frequencyCorrectionHz: Int = 0,
    val ppmCorrection: Int = 0,
    val biasTeeEnabled: Boolean = false,
    val preferredSampleRateHz: Int = 240_000,
    val amBandwidthHz: Int = 12_000,
    val squelchMode: Int = RTL_SQUELCH_MODE_AUTO,
    val squelchLevel: Int = 0,
    val squelchOpenDebounceMs: Int = 120,
    val squelchCloseHangMs: Int = 700,
    val hardSquelchMuteEnabled: Boolean = true,
    val gainMode: Int = 0,
    val tunerGain: Int = 0,
    val audioBoostPercent: Int = 100,
    val voiceFocusEnabled: Boolean = true,
    val postLevelerEnabled: Boolean = true,
    val noiseBlankerEnabled: Boolean = false,
    val denoiseMode: Int = DENOISE_MODE_OFF,
    val demodMode: Int = DEMOD_MODE_AM_ENVELOPE,
    val notchMode: Int = NOTCH_MODE_OFF,
    val sidebandMode: Int = AM_SIDEBAND_MODE_FULL,
    val advancedSyncEnabled: Boolean = false,
    val qualitySquelchEnabled: Boolean = false,
    val speechEnhancerMode: Int = SPEECH_ENHANCER_MODE_OFF,
    val amSpeechFilterEnabled: Boolean = true,
)

/**
 * Complete receiver snapshot exposed to the host service.
 *
 * The controller treats this as an observation of the current backend state,
 * not as a command response. UI and IPC layers should render this snapshot and
 * issue explicit commands when they need a state transition.
 */
data class DirectRtlBackendState(
    val supportedDevices: List<DirectRtlDeviceInfo> = emptyList(),
    val availableAmProfiles: List<DirectRtlAmProfileOption> = emptyList(),
    val selectedDeviceName: String? = null,
    val selectedDeviceLabel: String? = null,
    val usbPermissionGranted: Boolean = false,
    val nativeBridgeReady: Boolean = false,
    val connected: Boolean = false,
    val sessionConfigured: Boolean = false,
    val streaming: Boolean = false,
    val claimedInterfaceIndex: Int? = null,
    val bulkInEndpointAddress: Int? = null,
    val sampleRateHz: Int? = null,
    val tunedFrequencyHz: Long? = null,
    val tunedCenterFrequencyHz: Long? = null,
    val tunedAsAirbandChannelDesignator: Boolean = false,
    val frequencyCorrectionHz: Int = 0,
    val ppmCorrection: Int = 0,
    val biasTeeEnabled: Boolean = false,
    val preferredSampleRateHz: Int = 240_000,
    val amBandwidthHz: Int = 12_000,
    val squelchMode: Int = RTL_SQUELCH_MODE_AUTO,
    val squelchLevel: Int = 0,
    val squelchOpenDebounceMs: Int = 120,
    val squelchCloseHangMs: Int = 700,
    val hardSquelchMuteEnabled: Boolean = true,
    val gainMode: Int = 0,
    val tunerGain: Int = 0,
    val audioBoostPercent: Int = 100,
    val voiceFocusEnabled: Boolean = true,
    val postLevelerEnabled: Boolean = true,
    val noiseBlankerEnabled: Boolean = false,
    val denoiseMode: Int = DENOISE_MODE_OFF,
    val demodMode: Int = DEMOD_MODE_AM_ENVELOPE,
    val notchMode: Int = NOTCH_MODE_OFF,
    val sidebandMode: Int = AM_SIDEBAND_MODE_FULL,
    val advancedSyncEnabled: Boolean = false,
    val qualitySquelchEnabled: Boolean = false,
    val speechEnhancerMode: Int = SPEECH_ENHANCER_MODE_OFF,
    val amSpeechFilterEnabled: Boolean = true,
    val selectedAmProfileId: String? = null,
    val selectedAmProfileLabel: String? = null,
    val receiverSettingsError: String? = null,
    val rfLevelPercent: Int = 0,
    val audioLevelPercent: Int = 0,
    val clipPercent: Int = 0,
    val iqClipPercent: Int = 0,
    val estimatedCarrierOffsetHz: Int = 0,
    val squelchPercent: Int = 0,
    val squelchNoiseFloorPercent: Int = 0,
    val squelchThresholdPercent: Int = 0,
    val squelchEffectiveThresholdPercent: Int = 0,
    val squelchOpen: Boolean = true,
    val audioBufferSamples: Int = 0,
    val pcmProducedSamplesPerSecond: Int = 0,
    val pcmConsumedSamplesPerSecond: Int = 0,
    val pcmDriftSamplesPerSecond: Int = 0,
    val driftSnapshotCounter: Int = 0,
    val driftSnapshotText: String = "",
    /** Fixed FFT levels: 0 represents -100 dBFS and 100 represents 0 dBFS. */
    val spectrumBins: IntArray = intArrayOf(),
    val traceLogText: String = "",
    val status: String = "Direct USB/JNI backend idle",
) {
    val tunedFrequencyMHz: Double?
        get() = tunedFrequencyHz?.div(1_000_000.0)
}

/**
 * Owns the direct RTL-SDR lifecycle for the host process.
 *
 * Responsibilities:
 * - discover compatible USB devices and request Android USB permission
 * - open/close the native librtlsdr session
 * - configure/tune the receiver
 * - start/stop native streaming and local AudioTrack playback
 * - maintain diagnostics, metrics, and persisted receiver settings
 *
 * Threading:
 * - public methods are called from the host service IPC thread
 * - native streaming runs in the native worker thread
 * - Kotlin reader/writer threads bridge native PCM to AudioTrack
 *
 * State ownership stays here; UI and IPC layers should not duplicate receiver
 * lifecycle decisions.
 */
class DirectRtlSdrController(
    private val context: Context,
    private val foregroundController: DirectRtlSdrForegroundController = NoOpDirectRtlSdrForegroundController,
    private val requestControllerRefresh: () -> Unit = {},
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val amProfileStore = AmProfileStore(context.applicationContext)
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var connected: Boolean = false
    private var sessionConfigured: Boolean = false
    @Volatile private var streaming: Boolean = false
    private val audioWriterFailures = AudioWriterFailures<AudioTrack>()
    private var audioPlaybackError: String? = null
    @Volatile private var playbackBufferSnapshot = AudioPlaybackBufferSnapshot()
    private var tunedFrequencyHz: Long? = null
    private var tunedCenterFrequencyHz: Long? = null
    private var tunedAsAirbandChannelDesignator: Boolean = false
    private var selectedDeviceName: String? = null
    private var deviceConnection: UsbDeviceConnection? = null
    private var claimedInterfaceIndex: Int? = null
    private var claimedInterface: UsbInterface? = null
    private var bulkInEndpointAddress: Int? = null
    private var bulkInEndpoint: UsbEndpoint? = null
    private var sampleRateHz: Int? = null
    private var audioTrack: AudioTrack? = null
    private var audioTrackWrittenFrames = 0L
    private var audioTrackPlaybackHeadBaseFrames = 0L
    private var audioTrackLastPlaybackHeadFrames = 0L
    private var audioTrackPlaybackHeadWraps = 0L
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private var amPipelineHandle: Long = 0L
    private val audioBufferLock = Object()
    private val audioTrackIoLock = Any()
    private var audioRingBuffer = ShortArray(AUDIO_RING_BUFFER_SAMPLES)
    private var audioRingReadIndex = 0
    private var audioRingWriteIndex = 0
    private var audioRingSize = 0
    private var audioMetricsStartNs = 0L
    private var totalProducedSamples = 0L
    private var totalConsumedSamples = 0L
    private var totalUsbBytes = 0L
    private var totalUsbBlocks = 0L
    private var totalUsbFailures = 0L
    private var totalDroppedSamples = 0L
    private var driftSnapshotCounter = 0
    private var driftSnapshotText = ""
    private var driftSnapshotLastNs = 0L
    private var snapshotWindowStartNs = 0L
    private var snapshotWindowProducedSamples = 0L
    private var snapshotWindowConsumedSamples = 0L
    private var snapshotWindowUsbBytes = 0L
    private var snapshotWindowUsbBlocks = 0L
    private var snapshotWindowUsbFailures = 0L
    private var snapshotWindowDroppedSamples = 0L
    private var snapshotBufWidth = 1
    private var snapshotProducedWidth = 1
    private var snapshotConsumedWidth = 1
    private var snapshotDriftWidth = 2
    private var snapshotUsbBytesWidth = 1
    private var snapshotUsbBlocksWidth = 1
    private var snapshotUsbFailuresWidth = 1
    private var lastAudioShortReadLogNs = 0L
    private var lastLoggedUsbFailureTotal = 0L
    private var lastLoggedDroppedSamplesTotal = 0L
    private var audioReadWindowStartNs = 0L
    private var audioReadLastNs = 0L
    private var audioReadCount = 0L
    private var audioReadSamples = 0L
    private var audioReadIntervalTotalNs = 0L
    private var audioReadIntervalMinNs = Long.MAX_VALUE
    private var audioReadIntervalMaxNs = 0L
    private var audioWriteWindowStartNs = 0L
    private var audioWriteLastNs = 0L
    private var audioWriteCount = 0L
    private var audioWriteSamples = 0L
    private var audioWriteCalls = 0L
    private var audioWriteIntervalTotalNs = 0L
    private var audioWriteIntervalMinNs = Long.MAX_VALUE
    private var audioWriteIntervalMaxNs = 0L
    @Volatile private var forceSilentAudioUntilFreshPcm = false
    @Volatile private var selectedAmProfile: DirectRtlAmProfile = amProfileStore.profileOrDefault(
        preferences.getString(KEY_AM_PROFILE_ID, null)
    )
    @Volatile private var receiverSettings: DirectRtlReceiverSettings = loadPersistedReceiverSettings()
    private val settingsApplication = ReceiverConfigurationApplication<DirectRtlReceiverSettings>()
    @Volatile private var rfLevelPercent: Int = 0
    @Volatile private var audioLevelPercent: Int = 0
    @Volatile private var clipPercent: Int = 0
    @Volatile private var iqClipPercent: Int = 0
    @Volatile private var estimatedCarrierOffsetHz: Int = 0
    @Volatile private var squelchPercent: Int = 0
    @Volatile private var squelchNoiseFloorPercent: Int = 0
    @Volatile private var squelchThresholdPercent: Int = 0
    @Volatile private var squelchEffectiveThresholdPercent: Int = 0
    @Volatile private var squelchOpen: Boolean = true
    @Volatile private var spectrumBins: IntArray = intArrayOf()
    private val traceLogLock = Object()
    private val traceLogEntries = ArrayDeque<String>()

    private fun amLogInfo(message: String) {
        if (AM_LOG_LEVEL >= AM_LOG_INFO) Log.i(AM_LOG_TAG, message)
    }

    private fun amLogDebug(message: String) {
        if (AM_LOG_LEVEL >= AM_LOG_DEBUG) Log.d(AM_LOG_TAG, message)
    }

    private fun amLogWarn(message: String) {
        if (AM_LOG_LEVEL >= AM_LOG_INFO) Log.w(AM_LOG_TAG, message)
    }

    private fun appendTraceEntry(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        synchronized(traceLogLock) {
            traceLogEntries.addFirst("$timestamp  $message")
            while (traceLogEntries.size > 80) {
                traceLogEntries.removeLast()
            }
        }
    }

    private fun traceStep(message: String) {
        amLogWarn(message)
        appendTraceEntry(message)
    }

    private fun traceInfo(message: String) {
        amLogInfo(message)
        appendTraceEntry(message)
    }

    private fun clearTraceLogInternal() {
        synchronized(traceLogLock) {
            traceLogEntries.clear()
        }
    }

    fun clearTraceLog() {
        clearTraceLogInternal()
    }

    private fun failureState(base: DirectRtlBackendState, message: String): DirectRtlBackendState {
        traceStep(message)
        return base.copy(status = message)
    }

    private fun loadPersistedReceiverSettings(): DirectRtlReceiverSettings {
        val defaults = selectedAmProfile.applyDefaults(DirectRtlReceiverSettings())
        return sanitizeReceiverSettings(
            DirectRtlReceiverSettings(
                frequencyCorrectionHz = preferences.getInt(KEY_FREQUENCY_CORRECTION_HZ, 0),
                ppmCorrection = preferences.getInt(KEY_PPM_CORRECTION, 0),
                biasTeeEnabled = preferences.getBoolean(KEY_BIAS_TEE_ENABLED, false),
                preferredSampleRateHz = preferences.getInt(KEY_PREFERRED_SAMPLE_RATE_HZ, defaults.preferredSampleRateHz),
                amBandwidthHz = preferences.getInt(KEY_AM_BANDWIDTH_HZ, defaults.amBandwidthHz),
                squelchMode = preferences.getInt(KEY_SQUELCH_MODE, RTL_SQUELCH_MODE_AUTO),
                squelchLevel = preferences.getInt(KEY_SQUELCH_LEVEL, 0),
                squelchOpenDebounceMs = preferences.getInt(KEY_SQUELCH_OPEN_DEBOUNCE_MS, 120),
                squelchCloseHangMs = preferences.getInt(KEY_SQUELCH_CLOSE_HANG_MS, 700),
                hardSquelchMuteEnabled = preferences.getBoolean(KEY_HARD_SQUELCH_MUTE_ENABLED, true),
                gainMode = preferences.getInt(KEY_GAIN_MODE, GAIN_MODE_AUTO),
                tunerGain = preferences.getInt(KEY_TUNER_GAIN, 197),
                audioBoostPercent = preferences.getInt(KEY_AUDIO_BOOST_PERCENT, defaults.audioBoostPercent),
                voiceFocusEnabled = preferences.getBoolean(KEY_VOICE_FOCUS_ENABLED, defaults.voiceFocusEnabled),
                postLevelerEnabled = preferences.getBoolean(KEY_POST_LEVELER_ENABLED, defaults.postLevelerEnabled),
                noiseBlankerEnabled = preferences.getBoolean(KEY_NOISE_BLANKER_ENABLED, defaults.noiseBlankerEnabled),
                denoiseMode = preferences.getInt(KEY_DENOISE_MODE, defaults.denoiseMode),
                demodMode = preferences.getInt(KEY_DEMOD_MODE, defaults.demodMode),
                notchMode = preferences.getInt(KEY_NOTCH_MODE, defaults.notchMode),
                sidebandMode = preferences.getInt(KEY_SIDEBAND_MODE, defaults.sidebandMode),
                advancedSyncEnabled = preferences.getBoolean(KEY_ADVANCED_SYNC_ENABLED, defaults.advancedSyncEnabled),
                qualitySquelchEnabled = preferences.getBoolean(KEY_QUALITY_SQUELCH_ENABLED, defaults.qualitySquelchEnabled),
                speechEnhancerMode = preferences.getInt(KEY_SPEECH_ENHANCER_MODE, defaults.speechEnhancerMode),
                amSpeechFilterEnabled = preferences.getBoolean(KEY_AM_SPEECH_FILTER_ENABLED, defaults.amSpeechFilterEnabled),
            )
        )
    }

    private fun persistControllerState() {
        preferences.edit()
            .putInt(KEY_FREQUENCY_CORRECTION_HZ, receiverSettings.frequencyCorrectionHz)
            .putInt(KEY_PPM_CORRECTION, receiverSettings.ppmCorrection)
            .putBoolean(KEY_BIAS_TEE_ENABLED, receiverSettings.biasTeeEnabled)
            .putInt(KEY_PREFERRED_SAMPLE_RATE_HZ, receiverSettings.preferredSampleRateHz)
            .putInt(KEY_AM_BANDWIDTH_HZ, receiverSettings.amBandwidthHz)
            .putInt(KEY_SQUELCH_MODE, receiverSettings.squelchMode)
            .putInt(KEY_SQUELCH_LEVEL, receiverSettings.squelchLevel)
            .putInt(KEY_SQUELCH_OPEN_DEBOUNCE_MS, receiverSettings.squelchOpenDebounceMs)
            .putInt(KEY_SQUELCH_CLOSE_HANG_MS, receiverSettings.squelchCloseHangMs)
            .putBoolean(KEY_HARD_SQUELCH_MUTE_ENABLED, receiverSettings.hardSquelchMuteEnabled)
            .putInt(KEY_GAIN_MODE, receiverSettings.gainMode)
            .putInt(KEY_TUNER_GAIN, receiverSettings.tunerGain)
            .putInt(KEY_AUDIO_BOOST_PERCENT, receiverSettings.audioBoostPercent)
            .putBoolean(KEY_VOICE_FOCUS_ENABLED, receiverSettings.voiceFocusEnabled)
            .putBoolean(KEY_POST_LEVELER_ENABLED, receiverSettings.postLevelerEnabled)
            .putBoolean(KEY_NOISE_BLANKER_ENABLED, receiverSettings.noiseBlankerEnabled)
            .putInt(KEY_DENOISE_MODE, receiverSettings.denoiseMode)
            .putInt(KEY_DEMOD_MODE, receiverSettings.demodMode)
            .putInt(KEY_NOTCH_MODE, receiverSettings.notchMode)
            .putInt(KEY_SIDEBAND_MODE, receiverSettings.sidebandMode)
            .putBoolean(KEY_ADVANCED_SYNC_ENABLED, receiverSettings.advancedSyncEnabled)
            .putBoolean(KEY_QUALITY_SQUELCH_ENABLED, receiverSettings.qualitySquelchEnabled)
            .putInt(KEY_SPEECH_ENHANCER_MODE, receiverSettings.speechEnhancerMode)
            .putBoolean(KEY_AM_SPEECH_FILTER_ENABLED, receiverSettings.amSpeechFilterEnabled)
            .putString(KEY_AM_PROFILE_ID, selectedAmProfile.option.id)
            .apply()
    }

    fun refreshState(): DirectRtlBackendState {
        // Lifecycle cleanup belongs to the serialized controller thread. Ignore
        // a late failure from a track that has already been replaced.
        audioWriterFailures.takeFor(audioTrack)?.let { message ->
            disconnectInternal()
            audioPlaybackError = message
        }
        val devices = currentSupportedDevices()
        val selectedDevice = selectedDeviceOrNull(devices)
        val permissionGranted = selectedDevice?.hasPermission == true
        if (selectedDevice == null && devices.isNotEmpty()) {
            selectedDeviceName = devices.first().deviceName
        }
        if (connected && (selectedDevice == null || !permissionGranted)) {
            val disconnectReason =
                when {
                    devices.isEmpty() -> "USB device detached"
                    !permissionGranted -> "USB permission unavailable"
                    else -> "selected RTL-SDR device unavailable"
                }
            traceStep("refreshState(): resetting stale direct session because $disconnectReason")
            disconnectInternal()
            return refreshState().copy(status = "Direct backend disconnected: $disconnectReason")
        }
        maybeUpdateDriftSnapshot()
        val settings = receiverSettings
        val status = when {
            audioPlaybackError != null -> audioPlaybackError.orEmpty()
            settingsApplication.error != null -> settingsApplication.error.orEmpty()
            streaming -> settings.sessionStatusText(true, tunedFrequencyHz, sampleRateHz)
            connected && sessionConfigured -> settings.sessionStatusText(false, tunedFrequencyHz, sampleRateHz)
            connected -> "Direct transport connected, session not configured yet"
            devices.isEmpty() -> "No RTL-SDR-compatible USB device detected for direct backend"
            permissionGranted -> "Direct backend ready: USB device detected and permission available"
            else -> "Direct backend ready: compatible USB device detected, permission still required"
        }
        return DirectRtlBackendState(
            supportedDevices = devices,
            availableAmProfiles = amProfileStore.options,
            selectedDeviceName = selectedDevice?.deviceName ?: selectedDeviceName ?: devices.firstOrNull()?.deviceName,
            selectedDeviceLabel = selectedDevice?.productName,
            usbPermissionGranted = permissionGranted,
            nativeBridgeReady = DirectRtlSdrNativeBridge.isAvailable,
            connected = connected,
            sessionConfigured = sessionConfigured,
            streaming = streaming,
            claimedInterfaceIndex = claimedInterfaceIndex,
            bulkInEndpointAddress = bulkInEndpointAddress,
            sampleRateHz = sampleRateHz,
            tunedFrequencyHz = tunedFrequencyHz,
            tunedCenterFrequencyHz = tunedCenterFrequencyHz,
            tunedAsAirbandChannelDesignator = tunedAsAirbandChannelDesignator,
            frequencyCorrectionHz = settings.frequencyCorrectionHz,
            ppmCorrection = settings.ppmCorrection,
            biasTeeEnabled = settings.biasTeeEnabled,
            preferredSampleRateHz = settings.preferredSampleRateHz,
            amBandwidthHz = settings.amBandwidthHz,
            squelchMode = settings.squelchMode,
            squelchLevel = settings.squelchLevel,
            squelchOpenDebounceMs = settings.squelchOpenDebounceMs,
            squelchCloseHangMs = settings.squelchCloseHangMs,
            hardSquelchMuteEnabled = settings.hardSquelchMuteEnabled,
            gainMode = settings.gainMode,
            tunerGain = settings.tunerGain,
            audioBoostPercent = settings.audioBoostPercent,
            voiceFocusEnabled = settings.voiceFocusEnabled,
            postLevelerEnabled = settings.postLevelerEnabled,
            noiseBlankerEnabled = settings.noiseBlankerEnabled,
            denoiseMode = settings.denoiseMode,
            demodMode = settings.demodMode,
            notchMode = settings.notchMode,
            sidebandMode = settings.sidebandMode,
            advancedSyncEnabled = settings.advancedSyncEnabled,
            qualitySquelchEnabled = settings.qualitySquelchEnabled,
            speechEnhancerMode = settings.speechEnhancerMode,
            amSpeechFilterEnabled = settings.amSpeechFilterEnabled,
            selectedAmProfileId = selectedAmProfile.option.id,
            selectedAmProfileLabel = selectedAmProfile.option.label,
            receiverSettingsError = settingsApplication.error,
            rfLevelPercent = rfLevelPercent,
            audioLevelPercent = audioLevelPercent,
            clipPercent = clipPercent,
            iqClipPercent = iqClipPercent,
            estimatedCarrierOffsetHz = estimatedCarrierOffsetHz,
            squelchPercent = squelchPercent,
            squelchNoiseFloorPercent = squelchNoiseFloorPercent,
            squelchThresholdPercent = squelchThresholdPercent,
            squelchEffectiveThresholdPercent = squelchEffectiveThresholdPercent,
            squelchOpen = squelchOpen,
            audioBufferSamples = currentOutputBufferedSamples(),
            pcmProducedSamplesPerSecond = producedSamplesPerSecond(),
            pcmConsumedSamplesPerSecond = consumedSamplesPerSecond(),
            pcmDriftSamplesPerSecond = driftSamplesPerSecond(),
            driftSnapshotCounter = driftSnapshotCounter,
            driftSnapshotText = driftSnapshotText,
            spectrumBins = spectrumBins.copyOf(),
            traceLogText = synchronized(traceLogLock) { traceLogEntries.joinToString(separator = "\n") },
            status = status,
        )
    }

    fun requestUsbPermission(action: String): DirectRtlBackendState {
        val device = currentSupportedUsbDevices().firstOrNull()
            ?: return refreshState().copy(status = "No RTL-SDR-compatible USB device detected for direct backend")
        selectedDeviceName = device.deviceName
        if (usbManager.hasPermission(device)) {
            return refreshState().copy(status = "USB permission already granted for ${device.productName ?: device.deviceName}")
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(action).setPackage(context.packageName),
            flags,
        )
        usbManager.requestPermission(device, pendingIntent)
        return refreshState().copy(status = "Requested USB permission for ${device.productName ?: device.deviceName}")
    }

    fun handleUsbPermissionResult(intent: Intent?): DirectRtlBackendState {
        val granted = intent?.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) == true
        val device = intent.usbDeviceExtra()
        selectedDeviceName = device?.deviceName ?: selectedDeviceName
        val label = device?.productName ?: device?.deviceName ?: "RTL-SDR device"
        val refreshed = refreshState()
        return if (granted) refreshed.copy(status = "USB permission granted for $label")
        else refreshed.copy(status = "USB permission denied for $label")
    }

    fun handleUsbDeviceAttached(intent: Intent?): DirectRtlBackendState {
        val device = intent.usbDeviceExtra()
        if (device != null && looksLikeRtlSdr(device)) {
            selectedDeviceName = device.deviceName
            return refreshState().copy(status = "RTL-SDR device attached: ${device.productName ?: device.deviceName}")
        }
        return refreshState()
    }

    fun handleUsbDeviceDetached(intent: Intent?): DirectRtlBackendState {
        val device = intent.usbDeviceExtra()
        val wasSelected = device?.deviceName != null && device.deviceName == selectedDeviceName
        if (wasSelected) {
            disconnect()
            selectedDeviceName = null
        }
        val refreshed = refreshState()
        return if (device != null && looksLikeRtlSdr(device)) {
            refreshed.copy(status = "RTL-SDR device detached: ${device.productName ?: device.deviceName}")
        } else {
            refreshed
        }
    }

    fun connect(): DirectRtlBackendState {
        traceStep("connect() called")
        val refreshed = refreshState()
        if (refreshed.supportedDevices.isEmpty()) return failureState(refreshed, "No RTL-SDR-compatible USB device detected for direct backend")
        if (!refreshed.usbPermissionGranted) return failureState(refreshed, "Grant USB permission first")
        if (!refreshed.nativeBridgeReady) return failureState(refreshed, "Native bridge opensdr_rtlsdr is not built yet")

        val device = currentSupportedUsbDevices().firstOrNull {
            usbManager.hasPermission(it) && (selectedDeviceName == null || it.deviceName == selectedDeviceName)
        } ?: currentSupportedUsbDevices().firstOrNull { usbManager.hasPermission(it) }
            ?: return failureState(refreshed, "No USB-permitted RTL-SDR device available")

        selectedDeviceName = device.deviceName
        val connection = usbManager.openDevice(device)
            ?: return failureState(refreshed, "UsbManager could not open ${device.productName ?: device.deviceName}")

        val fd = connection.fileDescriptor
        if (fd < 0) {
            connection.close()
            return failureState(refreshed, "UsbDeviceConnection returned invalid file descriptor")
        }

        val transport = findTransportInterface(device)
            ?: run {
                connection.close()
                return failureState(refreshed, "Could not find vendor-specific bulk IN interface on ${device.productName ?: device.deviceName}")
            }
        if (!connection.claimInterface(transport.usbInterface, true)) {
            connection.close()
            return failureState(refreshed, "Could not claim USB interface ${transport.interfaceIndex} on ${device.productName ?: device.deviceName}")
        }

        traceStep("connect(): opening native bridge fd=" + fd + " vid=" + device.vendorId + " pid=" + device.productId)
        val opened = DirectRtlSdrNativeBridge.open(
            deviceFd = fd,
            vendorId = device.vendorId,
            productId = device.productId,
        )
        if (!opened) {
            val error = DirectRtlSdrNativeBridge.lastError ?: "Native open failed"
            runCatching { connection.releaseInterface(transport.usbInterface) }
            connection.close()
            return failureState(refreshed, error)
        }

        deviceConnection = connection
        connected = true
        claimedInterface = transport.usbInterface
        claimedInterfaceIndex = transport.interfaceIndex
        bulkInEndpoint = transport.bulkInEndpoint
        bulkInEndpointAddress = transport.bulkInEndpoint.address

        val requestedFrequencyHz = tunedFrequencyHz ?: DEFAULT_FREQUENCY_HZ
        val centerFrequencyHz = resolveCenterFrequencyHz(
            requestedFrequencyHz,
            tunedAsAirbandChannelDesignator,
        )
        val targetFrequencyHz = hardwareCenterFrequencyHz(centerFrequencyHz)
        var configured = false
        var configuredSampleRateHz: Int? = null
        var lastConfigError = "Native direct session config failed"
        for (candidateSampleRateHz in supportedSampleRateCandidates()) {
            traceStep("connect(): configuring session freqHz=" + targetFrequencyHz + " sampleRateHz=" + candidateSampleRateHz + " bw=" + receiverSettings.amBandwidthHz)
            configured = DirectRtlSdrNativeBridge.configureSession(
                centerFrequencyHz = targetFrequencyHz,
                sampleRateHz = candidateSampleRateHz,
                gainMode = receiverSettings.gainMode,
                tunerGain = receiverSettings.tunerGain,
                agcMode = receiverSettings.gainMode == GAIN_MODE_AUTO,
                offsetTuning = DEFAULT_OFFSET_TUNING,
                ppmCorrection = receiverSettings.ppmCorrection,
                biasTeeEnabled = receiverSettings.biasTeeEnabled,
                tunerBandwidthHz = receiverRfBandwidthHz(receiverSettings.demodMode, receiverSettings.amBandwidthHz),
            )
            if (configured) {
                configuredSampleRateHz = candidateSampleRateHz
                traceStep("connect(): session configured successfully sampleRateHz=" + candidateSampleRateHz)
                break
            }
            lastConfigError = DirectRtlSdrNativeBridge.lastError ?: "Native direct session config failed"
            traceStep("connect(): sampleRateHz=" + candidateSampleRateHz + " rejected: " + lastConfigError)
        }
        if (!configured || configuredSampleRateHz == null) {
            DirectRtlSdrNativeBridge.close()
            runCatching { connection.releaseInterface(transport.usbInterface) }
            connection.close()
            deviceConnection = null
            connected = false
            claimedInterface = null
            claimedInterfaceIndex = null
            bulkInEndpoint = null
            bulkInEndpointAddress = null
            sampleRateHz = null
            sessionConfigured = false
            return failureState(refreshed, lastConfigError)
        }

        sessionConfigured = true
        audioPlaybackError = null
        settingsApplication.confirm(receiverSettings)
        sampleRateHz = configuredSampleRateHz
        tunedFrequencyHz = requestedFrequencyHz
        tunedCenterFrequencyHz = centerFrequencyHz
        return refreshState().copy(
            status = "Direct backend connected to ${device.productName ?: device.deviceName}; session prepared @ ${DEFAULT_SAMPLE_RATE_HZ / 1000} ksps (${DirectRtlSdrNativeBridge.versionLabel})"
        )
    }

    fun selectAmProfile(
        profileId: String?,
        applyProfileDefaults: Boolean,
        preserveFrequencyCorrection: Boolean = false,
    ): DirectRtlBackendState {
        selectedAmProfile = amProfileStore.profileOrDefault(profileId)
        receiverSettings = sanitizeReceiverSettings(
            if (applyProfileDefaults) selectedAmProfile.applyDefaults(receiverSettings, preserveFrequencyCorrection)
            else receiverSettings,
        )
        persistControllerState()
        if (!connected || !sessionConfigured) {
            return refreshState().copy(status = receiverSettingsSummary("AM profile selected"))
        }
        val applyError = applyCurrentSettingsToConnectedSession()
        if (applyError != null) {
            return refreshState().copy(status = applyError)
        }
        return refreshState().copy(status = receiverSettingsSummary("AM profile applied"))
    }

    fun updateReceiverSettings(
        frequencyCorrectionHz: Int,
        ppmCorrection: Int,
        biasTeeEnabled: Boolean,
        preferredSampleRateHz: Int,
        amBandwidthHz: Int,
        squelchMode: Int,
        squelchLevel: Int,
        squelchOpenDebounceMs: Int,
        squelchCloseHangMs: Int,
        hardSquelchMuteEnabled: Boolean,
        gainMode: Int,
        tunerGain: Int,
        audioBoostPercent: Int,
        voiceFocusEnabled: Boolean,
        postLevelerEnabled: Boolean,
        noiseBlankerEnabled: Boolean,
        denoiseMode: Int,
        demodMode: Int,
        notchMode: Int,
        sidebandMode: Int,
        advancedSyncEnabled: Boolean,
        qualitySquelchEnabled: Boolean,
        speechEnhancerMode: Int,
        amSpeechFilterEnabled: Boolean? = null,
    ): DirectRtlBackendState {
        return updateReceiverSettings(
            DirectRtlReceiverSettings(
                frequencyCorrectionHz = frequencyCorrectionHz,
                ppmCorrection = ppmCorrection,
                biasTeeEnabled = biasTeeEnabled,
                preferredSampleRateHz = preferredSampleRateHz,
                amBandwidthHz = amBandwidthHz,
                squelchMode = squelchMode,
                squelchLevel = squelchLevel,
                squelchOpenDebounceMs = squelchOpenDebounceMs,
                squelchCloseHangMs = squelchCloseHangMs,
                hardSquelchMuteEnabled = hardSquelchMuteEnabled,
                gainMode = gainMode,
                tunerGain = tunerGain,
                audioBoostPercent = audioBoostPercent,
                voiceFocusEnabled = voiceFocusEnabled,
                postLevelerEnabled = postLevelerEnabled,
                noiseBlankerEnabled = noiseBlankerEnabled,
                denoiseMode = denoiseMode,
                demodMode = demodMode,
                notchMode = notchMode,
                sidebandMode = sidebandMode,
                advancedSyncEnabled = advancedSyncEnabled,
                qualitySquelchEnabled = qualitySquelchEnabled,
                speechEnhancerMode = speechEnhancerMode,
                amSpeechFilterEnabled = amSpeechFilterEnabled ?: receiverSettings.amSpeechFilterEnabled,
            ),
        )
    }

    /** Atomic partial update: clients adjusting squelch must not overwrite host DSP settings. */
    fun updateListeningControls(
        ppmCorrection: Int,
        biasTeeEnabled: Boolean,
        gainMode: Int?,
        tunerGain: Int?,
        squelchMode: Int,
        squelchLevel: Int,
        amSpeechFilterEnabled: Boolean? = null,
        frequencyCorrectionHz: Int? = null,
    ): DirectRtlBackendState = updateReceiverSettings(
        receiverSettings.withListeningControls(
            ppmCorrection, biasTeeEnabled, gainMode, tunerGain, squelchMode, squelchLevel, amSpeechFilterEnabled,
            frequencyCorrectionHz,
        ),
    )

    private fun updateReceiverSettings(settings: DirectRtlReceiverSettings): DirectRtlBackendState {
        val previousSettings = receiverSettings
        val sanitizedSettings = sanitizeReceiverSettings(settings)
        if (sanitizedSettings == previousSettings &&
            (!connected || !sessionConfigured || settingsApplication.isApplied(sanitizedSettings))
        ) {
            val prefix = if (!connected || !sessionConfigured) "Receiver settings unchanged" else "Receiver settings already applied"
            return refreshState().copy(status = receiverSettingsSummary(prefix))
        }
        receiverSettings = sanitizedSettings
        persistControllerState()
        if (!connected || !sessionConfigured) {
            return refreshState().copy(status = receiverSettingsSummary("Receiver settings updated"))
        }
        val applyError = applyCurrentSettingsToConnectedSession()
        if (applyError != null) {
            return refreshState().copy(status = applyError)
        }
        return refreshState().copy(status = receiverSettingsSummary("Receiver settings applied"))
    }

    private fun applyCurrentSettingsToConnectedSession(): String? =
        settingsApplication.apply(receiverSettings) { previousSettings ->
            writeSettingsToConnectedSession(previousSettings)
        }

    private fun writeSettingsToConnectedSession(previousSettings: DirectRtlReceiverSettings?): String? {
        val settings = receiverSettings
        val nativeReceiverUpdateRequired = nativeReceiverSettingsChanged(previousSettings, settings)
        if (nativeReceiverUpdateRequired) {
            val applied = DirectRtlSdrNativeBridge.applySettings(
                ppmCorrection = settings.ppmCorrection,
                biasTeeEnabled = settings.biasTeeEnabled,
                tunerBandwidthHz = receiverRfBandwidthHz(settings.demodMode, settings.amBandwidthHz),
                gainMode = settings.gainMode,
                tunerGain = settings.tunerGain,
                agcMode = settings.gainMode == GAIN_MODE_AUTO,
            )
            if (!applied) {
                return DirectRtlSdrNativeBridge.lastError ?: "Native receiver settings update failed"
            }
        }

        if (nativeRetuneRequired(previousSettings, settings)) {
            tunedCenterFrequencyHz?.let { centerFrequencyHz ->
                val targetFrequencyHz = hardwareCenterFrequencyHz(centerFrequencyHz)
                val retuned = DirectRtlSdrNativeBridge.tuneHz(
                    targetFrequencyHz,
                    amCarrierTargetsForTuning(
                        tunedFrequencyHz ?: centerFrequencyHz,
                        tunedAsAirbandChannelDesignator,
                        targetFrequencyHz,
                    ),
                )
                if (!retuned) {
                    return DirectRtlSdrNativeBridge.lastError ?: "Native retune after settings update failed"
                }
            }
        }
        if (amPipelineHandle != 0L) {
            traceStep("startAudio(): updating existing AM pipeline handle=" + amPipelineHandle)
            val updated = DirectRtlSdrNativeBridge.updateAmPipeline(amPipelineHandle, buildNativeAmConfig())
            if (!updated) {
                return DirectRtlSdrNativeBridge.lastError ?: "Native AM pipeline update failed"
            }
        }
        if (realtimeAudioBufferFlushRequired(previousSettings, settings)) {
            flushRealtimeAudioBuffers()
        }
        return null
    }

    private fun realtimeAudioBufferFlushRequired(
        previousSettings: DirectRtlReceiverSettings?,
        currentSettings: DirectRtlReceiverSettings,
    ): Boolean {
        if (!streaming || previousSettings == null) return false
        val modeChanged = previousSettings.squelchMode != currentSettings.squelchMode
        val levelRaised = currentSettings.squelchLevel > previousSettings.squelchLevel
        if (!modeChanged && !levelRaised) {
            return false
        }
        // Avoid audible dropouts when the user nudges manual/custom squelch while the
        // signal still stays above the new threshold. We only flush buffered audio when
        // the tighter squelch would actually close the channel right now.
        return squelchWouldCloseImmediately(currentSettings)
    }

    private fun squelchWouldCloseImmediately(settings: DirectRtlReceiverSettings): Boolean {
        if (settings.squelchMode == RTL_SQUELCH_MODE_AUTO && settings.squelchLevel <= 0) {
            return false
        }
        val targetThresholdPercent = settings.squelchLevel.coerceIn(0, 100)
        return !squelchOpen || squelchPercent < targetThresholdPercent
    }

    private fun flushRealtimeAudioBuffers() {
        forceSilentAudioUntilFreshPcm = true
        DirectRtlSdrNativeBridge.clearAmStreamBuffer()
        synchronized(audioBufferLock) {
            audioRingReadIndex = 0
            audioRingWriteIndex = 0
            audioRingSize = 0
            audioBufferLock.notifyAll()
        }
        audioTrack?.let { track ->
            synchronized(audioTrackIoLock) {
                runCatching { track.pause() }
                runCatching { track.flush() }
                resetAudioTrackBufferMetricsLocked(track)
                if (streaming) {
                    runCatching { track.play() }
                }
            }
        }
        traceStep("Flushed buffered audio after realtime squelch update")
    }

    private fun nativeReceiverSettingsChanged(
        previousSettings: DirectRtlReceiverSettings?,
        currentSettings: DirectRtlReceiverSettings,
    ): Boolean {
        return previousSettings == null ||
            previousSettings.ppmCorrection != currentSettings.ppmCorrection ||
            previousSettings.biasTeeEnabled != currentSettings.biasTeeEnabled ||
            receiverRfBandwidthHz(previousSettings.demodMode, previousSettings.amBandwidthHz) !=
                receiverRfBandwidthHz(currentSettings.demodMode, currentSettings.amBandwidthHz) ||
            previousSettings.gainMode != currentSettings.gainMode ||
            previousSettings.tunerGain != currentSettings.tunerGain
    }

    private fun nativeRetuneRequired(
        previousSettings: DirectRtlReceiverSettings?,
        currentSettings: DirectRtlReceiverSettings,
    ): Boolean {
        return previousSettings == null ||
            previousSettings.ppmCorrection != currentSettings.ppmCorrection ||
            previousSettings.frequencyCorrectionHz != currentSettings.frequencyCorrectionHz
    }

    fun startAudio(): DirectRtlBackendState {
        traceStep("startAudio() called")
        val refreshed = refreshState()
        if (!refreshed.connected) return failureState(refreshed, "Connect direct backend first")
        if (!refreshed.sessionConfigured) return failureState(refreshed, "Direct session is not configured yet")
        if (streaming) return failureState(refreshed, "Direct audio already streaming")

        if (deviceConnection == null) return failureState(refreshed, "USB device connection is not available")
        if (bulkInEndpoint == null) return failureState(refreshed, "Bulk IN endpoint is not available")

        if (!settingsApplication.isApplied(receiverSettings)) {
            applyCurrentSettingsToConnectedSession()?.let { error ->
                return failureState(refreshState(), error)
            }
        }

        // A failed USB stream can leave a finishing writer behind. It must not
        // read from, stop, or release resources belonging to the next session.
        val previousWriter = writerThread
        if (previousWriter != null || audioTrack != null) {
            stopAudioInternal()
            if (previousWriter?.isAlive == true) {
                writerThread = previousWriter
                return failureState(refreshed, "Previous audio writer is still stopping; retry shortly")
            }
        }

        val pipelineHandle = if (amPipelineHandle != 0L) {
            val updated = DirectRtlSdrNativeBridge.updateAmPipeline(amPipelineHandle, buildNativeAmConfig())
            if (!updated) return failureState(refreshed, DirectRtlSdrNativeBridge.lastError ?: "Native AM pipeline update failed")
            traceStep("startAudio(): resetting existing AM pipeline handle=" + amPipelineHandle)
            val reset = DirectRtlSdrNativeBridge.resetAmPipeline(amPipelineHandle)
            if (!reset) return failureState(refreshed, DirectRtlSdrNativeBridge.lastError ?: "Native AM pipeline reset failed")
            amPipelineHandle
        } else {
            traceStep("startAudio(): creating AM pipeline srIn=" + DEFAULT_SAMPLE_RATE_HZ + " srOut=" + AUDIO_SAMPLE_RATE + " bw=" + receiverSettings.amBandwidthHz)
            DirectRtlSdrNativeBridge.createAmPipeline(buildNativeAmConfig()).also { handle ->
                if (handle == 0L) return failureState(refreshed, DirectRtlSdrNativeBridge.lastError ?: "Native AM pipeline creation failed")
                amPipelineHandle = handle
            }
        }

        traceStep("startAudio(): starting native AM stream iqRead=" + IQ_READ_SIZE + " pcmRing=" + AUDIO_RING_BUFFER_SAMPLES)
        val nativeStreamStarted = DirectRtlSdrNativeBridge.startAmStream(
            handle = pipelineHandle,
            iqReadSize = IQ_READ_SIZE,
            pcmRingCapacity = AUDIO_RING_BUFFER_SAMPLES,
        )
        if (!nativeStreamStarted) return failureState(refreshed, DirectRtlSdrNativeBridge.lastError ?: "Native AM stream start failed")

        traceStep("startAudio(): native AM stream started")
        val minBuffer = AudioTrack.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(8192)
        val localTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            localTrack.setVolume(1.0f)
            foregroundController.start(
                context = context.applicationContext,
                frequencyText = tunedFrequencyHz?.let { String.format(java.util.Locale.US, "%.3f", it / 1_000_000.0) },
            )
        } catch (failure: Exception) {
            DirectRtlSdrNativeBridge.stopAmStream()
            runCatching { localTrack.release() }
            runCatching { foregroundController.stop(context.applicationContext) }
            return failureState(refreshed, "Could not keep receiver audio in foreground: ${failure.javaClass.simpleName}: ${failure.message.orEmpty()}")
        }

        resetAudioRingBuffer()
        audioPlaybackError = null
        synchronized(audioTrackIoLock) {
            audioTrack = localTrack
            resetAudioTrackBufferMetricsLocked(localTrack)
        }
        streaming = true
        synchronized(audioBufferLock) {
            audioMetricsStartNs = System.nanoTime()
            totalProducedSamples = 0L
            totalConsumedSamples = 0L
            totalUsbBytes = 0L
            totalUsbBlocks = 0L
            totalUsbFailures = 0L
            totalDroppedSamples = 0L
            driftSnapshotCounter = 0
            driftSnapshotText = ""
            driftSnapshotLastNs = 0L
            snapshotWindowStartNs = audioMetricsStartNs
            snapshotWindowProducedSamples = 0L
            snapshotWindowConsumedSamples = 0L
            snapshotWindowUsbBytes = 0L
            snapshotWindowUsbBlocks = 0L
            snapshotWindowUsbFailures = 0L
            snapshotWindowDroppedSamples = 0L
        }
        writerThread = thread(start = true, isDaemon = true, name = "rtl-direct-am-writer") {
            runCatching {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                audioTrackLoop(localTrack)
            }.onFailure { failure ->
                traceStep("Audio writer stopped: ${failure.javaClass.simpleName}: ${failure.message.orEmpty()}")
                Log.e(AM_LOG_TAG, "Audio writer stopped", failure)
                audioWriterFailures.report(localTrack, "Audio playback stopped: ${failure.message.orEmpty()}")
                runCatching { requestControllerRefresh() }
            }
        }
        amLogInfo("AM stream started: " + receiverSettingsSummary("cfg") + " | targetBuf=" + AUDIO_RING_TARGET_SAMPLES + " | write=" + AUDIO_TRACK_WRITE_SAMPLES + " | resampler=software")
        return refreshState().copy(status = "Direct audio streaming started")
    }

    fun stopAudio(): DirectRtlBackendState {
        traceStep("stopAudio() called | ${debugStateSnapshot()}")
        stopAudioInternal()
        val refreshed = refreshState().copy(status = "Direct audio streaming stopped")
        traceStep("stopAudio() completed | ${debugStateSnapshot(refreshed)}")
        return refreshed
    }

    fun disconnect(): DirectRtlBackendState {
        traceStep("disconnect() called | ${debugStateSnapshot()}")
        disconnectInternal()
        val refreshed = refreshState().copy(status = "Direct backend disconnected")
        traceStep("disconnect() completed | ${debugStateSnapshot(refreshed)}")
        return refreshed
    }

    fun tuneAmHz(
        frequencyHz: Long,
        airbandChannelDesignator: Boolean = false,
    ): DirectRtlBackendState {
        traceStep(
            "tuneAmHz() called frequencyHz=" + frequencyHz +
                " airbandChannelDesignator=" + airbandChannelDesignator,
        )
        val refreshed = refreshState()
        if (!refreshed.connected) return failureState(refreshed, "Connect direct backend first")
        if (!refreshed.sessionConfigured) return failureState(refreshed, "Direct session is not configured yet")

        val normalized = frequencyHz.coerceAtLeast(1L)
        val centerFrequencyHz = resolveCenterFrequencyHz(normalized, airbandChannelDesignator)
        val targetFrequencyHz = hardwareCenterFrequencyHz(centerFrequencyHz)
        val ok = DirectRtlSdrNativeBridge.tuneHz(
            targetFrequencyHz,
            amCarrierTargetsForTuning(normalized, airbandChannelDesignator, targetFrequencyHz),
        )
        if (!ok) return refreshed.copy(status = DirectRtlSdrNativeBridge.lastError ?: "Native tune failed")

        tunedFrequencyHz = normalized
        tunedCenterFrequencyHz = centerFrequencyHz
        tunedAsAirbandChannelDesignator = airbandChannelDesignator
        return refreshState().copy(status = receiverSettings.tunedStatusText(normalized, centerFrequencyHz, airbandChannelDesignator))
    }

    fun currentSupportedDevices(): List<DirectRtlDeviceInfo> {
        return currentSupportedUsbDevices().map { device ->
            DirectRtlDeviceInfo(
                deviceName = device.deviceName,
                productName = device.productName ?: "USB RTL-SDR device",
                vendorId = device.vendorId,
                productId = device.productId,
                interfaceCount = device.interfaceCount,
                hasPermission = usbManager.hasPermission(device),
            )
        }.sortedBy { it.productName }
    }

    private fun audioTrackLoop(track: AudioTrack) {
        val pcmBuffer = ShortArray(AUDIO_TRACK_WRITE_SAMPLES)
        val resampler = StreamingPcmResampler(
            outputRateHz = AUDIO_SAMPLE_RATE,
            outputBlockSamples = AUDIO_TRACK_WRITE_SAMPLES,
            maxInputRateHz = AUDIO_PLAYBACK_RATE_CONFIG.maxRateHz,
        )
        val sourceBuffer = ShortArray(resampler.inputCapacity)
        val producerRateEstimator = PcmProducerRateEstimator(AUDIO_PLAYBACK_RATE_CONFIG)
        var lastSample = 0.toShort()
        var consecutiveStarvedReads = 0
        var softwareResampleRate = AUDIO_SAMPLE_RATE
        waitForBufferedAudio(AUDIO_TRACK_START_SAMPLES)
        if (!streaming) return
        amLogInfo("Audio playback starting: buf=" + currentStreamBufferedSamples())
        track.play()

        while (streaming) {
            softwareResampleRate = updateSoftwareResampleRate(
                currentRate = softwareResampleRate,
                producerRateEstimator = producerRateEstimator,
            )
            if (forceSilentAudioUntilFreshPcm) resampler.reset()
            val requestedInputSamples = resampler.inputSamplesNeeded(softwareResampleRate)
            val copied = dequeueAudioSamples(sourceBuffer, requestedInputSamples)
            if (!streaming) break
            if (copied <= 0) {
                consecutiveStarvedReads += 1
                val fillSample = if (forceSilentAudioUntilFreshPcm) 0 else lastSample.toInt()
                fillShortPcmBlock(pcmBuffer, 0, fillSample.toShort())
                lastSample = fillSample.toShort()
                if (consecutiveStarvedReads == AUDIO_STARVED_READ_LOG_THRESHOLD || consecutiveStarvedReads % AUDIO_STARVED_READ_LOG_REPEAT == 0) {
                    amLogWarn(
                        "PCM starvation: reads=" + consecutiveStarvedReads +
                            " buf=" + currentStreamBufferedSamples() +
                            " usbFail=" + totalUsbFailures +
                            " drop=" + totalDroppedSamples
                    )
                }
            } else {
                consecutiveStarvedReads = 0
                resampler.append(sourceBuffer, copied)
                val rendered = resampler.render(pcmBuffer, softwareResampleRate)
                if (rendered > 0) lastSample = pcmBuffer[rendered - 1]
                fillShortPcmBlock(pcmBuffer, rendered, lastSample)
                if (copied < requestedInputSamples) {
                    amLogWarn("Short PCM block: copied=" + copied + " expected=" + requestedInputSamples + " buf=" + currentStreamBufferedSamples())
                }
                forceSilentAudioUntilFreshPcm = false
            }
            writeBlocking(track, pcmBuffer, pcmBuffer.size)
        }
    }

    private fun updateSoftwareResampleRate(
        currentRate: Int,
        producerRateEstimator: PcmProducerRateEstimator,
    ): Int {
        val bufferedSamples = currentStreamBufferedSamples()
        val producedSamples = synchronized(audioBufferLock) { totalProducedSamples }
        val producerRate = producerRateEstimator.observe(
            totalProducedSamples = producedSamples,
            nowNs = System.nanoTime(),
        )
        return nextPcmConsumptionRateHz(
            currentRateHz = currentRate,
            producerRateHz = producerRate,
            bufferedSamples = bufferedSamples,
            config = AUDIO_PLAYBACK_RATE_CONFIG,
        )
    }

    private fun fillShortPcmBlock(destination: ShortArray, startIndex: Int, fillValue: Short) {
        for (index in startIndex until destination.size) {
            destination[index] = fillValue
        }
    }

    private fun dequeueAudioSamples(destination: ShortArray, requestedSamples: Int): Int {
        val copied = DirectRtlSdrNativeBridge.readAmStreamPcm(
            pcmOut = destination,
            requestedSamples = requestedSamples,
            timeoutMs = AUDIO_TRACK_WRITE_TIMEOUT_MS.toInt(),
        )
        syncNativeStreamMetrics()
        val nowNs = System.nanoTime()
        recordAudioReadCadence(nowNs, copied)
        if (copied <= 1 && nowNs - lastAudioShortReadLogNs >= 1_000_000_000L) {
            lastAudioShortReadLogNs = nowNs
            traceStep(
                "Short AM PCM read: copied=" + copied +
                    " requested=" + requestedSamples +
                    " buf=" + currentStreamBufferedSamples() +
                    " produced=" + totalProducedSamples +
                    " consumed=" + totalConsumedSamples +
                    " usbFail=" + totalUsbFailures +
                    " dropped=" + totalDroppedSamples +
                    " rf=" + rfLevelPercent +
                    " audio=" + audioLevelPercent +
                    " squelch=" + squelchPercent
            )
        }
        return copied
    }

    private fun waitForBufferedAudio(minSamples: Int) {
        var consecutiveReadyChecks = 0
        while (streaming) {
            syncNativeStreamMetrics()
            val ready = synchronized(audioBufferLock) {
                audioRingSize >= minSamples
            }
            if (ready) {
                consecutiveReadyChecks += 1
                if (consecutiveReadyChecks >= AUDIO_TRACK_START_READY_CHECKS) return
            } else {
                consecutiveReadyChecks = 0
            }
            Thread.sleep(AUDIO_TRACK_START_TIMEOUT_MS)
        }
    }

    private fun currentStreamBufferedSamples(): Int {
        syncNativeStreamMetrics()
        synchronized(audioBufferLock) {
            return audioRingSize
        }
    }

    private fun currentOutputBufferedSamples(): Int {
        val streamBufferedSamples = currentStreamBufferedSamples()
        val trackBufferedSamples = playbackBufferSnapshot.remainingSamples(System.nanoTime())
        return (streamBufferedSamples.toLong() + trackBufferedSamples.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun unsignedPlaybackHeadFrames(track: AudioTrack): Long {
        return track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
    }

    private fun resetAudioTrackBufferMetricsLocked(track: AudioTrack?) {
        playbackBufferSnapshot = AudioPlaybackBufferSnapshot()
        val baseHeadFrames = track?.let(::unsignedPlaybackHeadFrames) ?: 0L
        audioTrackWrittenFrames = 0L
        audioTrackPlaybackHeadBaseFrames = baseHeadFrames
        audioTrackLastPlaybackHeadFrames = baseHeadFrames
        audioTrackPlaybackHeadWraps = 0L
    }

    private fun currentAudioTrackQueuedSamplesLocked(): Int {
        val track = audioTrack ?: return 0
        val playbackHeadFrames = unsignedPlaybackHeadFrames(track)
        if (playbackHeadFrames < audioTrackLastPlaybackHeadFrames) {
            audioTrackPlaybackHeadWraps += 1L
        }
        audioTrackLastPlaybackHeadFrames = playbackHeadFrames
        val absolutePlaybackHeadFrames =
            playbackHeadFrames + (audioTrackPlaybackHeadWraps * (1L shl 32))
        val playedSinceBaseFrames =
            (absolutePlaybackHeadFrames - audioTrackPlaybackHeadBaseFrames).coerceAtLeast(0L)
        return (audioTrackWrittenFrames - playedSinceBaseFrames)
            .coerceAtLeast(0L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun syncNativeStreamMetrics() {
        if (!streaming) return
        val metrics = DirectRtlSdrNativeBridge.amStreamMetrics()
        if (!metrics.running && streaming) {
            streaming = false
            traceStep("Native USB stream ended: ${DirectRtlSdrNativeBridge.lastError}")
            foregroundController.stop(context.applicationContext)
            synchronized(audioTrackIoLock) {
                runCatching { audioTrack?.stop() }
                runCatching { audioTrack?.release() }
                audioTrack = null
                resetAudioTrackBufferMetricsLocked(null)
            }
        }
        val latestSpectrumBins = DirectRtlSdrNativeBridge.amStreamSpectrum()
        synchronized(audioBufferLock) {
            val usbFailureDelta = metrics.totalUsbFailures - totalUsbFailures
            val droppedDelta = metrics.totalDroppedSamples - totalDroppedSamples
            audioRingSize = metrics.bufferedSamples
            totalProducedSamples = metrics.totalProducedSamples
            totalConsumedSamples = metrics.totalConsumedSamples
            totalUsbBytes = metrics.totalUsbBytes
            totalUsbBlocks = metrics.totalUsbBlocks
            totalUsbFailures = metrics.totalUsbFailures
            totalDroppedSamples = metrics.totalDroppedSamples
            rfLevelPercent = metrics.rfLevelPercent
            audioLevelPercent = metrics.audioLevelPercent
            clipPercent = metrics.clipPercent
            iqClipPercent = metrics.iqClipPercent
            estimatedCarrierOffsetHz = metrics.estimatedCarrierOffsetHz
            squelchPercent = metrics.squelchPercent
            squelchNoiseFloorPercent = metrics.squelchNoiseFloorPercent
            squelchThresholdPercent = metrics.squelchThresholdPercent
            squelchEffectiveThresholdPercent = metrics.squelchEffectiveThresholdPercent
            squelchOpen = metrics.squelchOpen
            spectrumBins = latestSpectrumBins
            if (usbFailureDelta > 0 && totalUsbFailures != lastLoggedUsbFailureTotal) {
                lastLoggedUsbFailureTotal = totalUsbFailures
                traceStep(
                    "USB failures +" + usbFailureDelta +
                        " total=" + totalUsbFailures +
                        " buf=" + audioRingSize +
                        " produced=" + totalProducedSamples +
                        " consumed=" + totalConsumedSamples +
                        " usbBytes=" + totalUsbBytes +
                        " usbBlocks=" + totalUsbBlocks +
                        " dropped=" + totalDroppedSamples +
                        " rf=" + rfLevelPercent +
                        " audio=" + audioLevelPercent +
                        " clip=" + clipPercent +
                        " iqClip=" + iqClipPercent +
                        " squelch=" + squelchPercent
                )
            }
            if (droppedDelta > 0 && totalDroppedSamples != lastLoggedDroppedSamplesTotal) {
                lastLoggedDroppedSamplesTotal = totalDroppedSamples
                traceStep(
                    "Dropped PCM +" + droppedDelta +
                        " total=" + totalDroppedSamples +
                        " buf=" + audioRingSize +
                        " produced=" + totalProducedSamples +
                        " consumed=" + totalConsumedSamples +
                        " usbFail=" + totalUsbFailures +
                        " rf=" + rfLevelPercent +
                        " audio=" + audioLevelPercent +
                        " clip=" + clipPercent +
                        " iqClip=" + iqClipPercent
                )
            }
        }
    }

    private fun metricsElapsedSeconds(): Double {
        val startNs = audioMetricsStartNs
        if (startNs == 0L) return 0.0
        val elapsedNs = System.nanoTime() - startNs
        if (elapsedNs <= 0L) return 0.0
        return elapsedNs / 1_000_000_000.0
    }

    private fun producedSamplesPerSecond(): Int {
        val elapsedSeconds = metricsElapsedSeconds()
        if (elapsedSeconds <= 0.0) return 0
        return (totalProducedSamples.toDouble() / elapsedSeconds).roundToInt()
    }

    private fun consumedSamplesPerSecond(): Int {
        val elapsedSeconds = metricsElapsedSeconds()
        if (elapsedSeconds <= 0.0) return 0
        return (totalConsumedSamples.toDouble() / elapsedSeconds).roundToInt()
    }

    private fun driftSamplesPerSecond(): Int {
        return producedSamplesPerSecond() - consumedSamplesPerSecond()
    }


    private fun recordAudioReadCadence(nowNs: Long, copied: Int) {
        synchronized(audioBufferLock) {
            if (audioReadWindowStartNs == 0L || audioReadLastNs == 0L) {
                audioReadWindowStartNs = nowNs
                audioReadLastNs = nowNs
            } else {
                val intervalNs = nowNs - audioReadLastNs
                if (intervalNs > 0L) {
                    audioReadIntervalTotalNs += intervalNs
                    if (intervalNs < audioReadIntervalMinNs) audioReadIntervalMinNs = intervalNs
                    if (intervalNs > audioReadIntervalMaxNs) audioReadIntervalMaxNs = intervalNs
                }
                audioReadLastNs = nowNs
            }
            if (copied > 0) {
                audioReadCount += 1
                audioReadSamples += copied.toLong()
            }
        }
    }

    private fun recordAudioWriteCadence(nowNs: Long, written: Int) {
        synchronized(audioBufferLock) {
            if (audioWriteWindowStartNs == 0L || audioWriteLastNs == 0L) {
                audioWriteWindowStartNs = nowNs
                audioWriteLastNs = nowNs
            } else {
                val intervalNs = nowNs - audioWriteLastNs
                if (intervalNs > 0L) {
                    audioWriteIntervalTotalNs += intervalNs
                    if (intervalNs < audioWriteIntervalMinNs) audioWriteIntervalMinNs = intervalNs
                    if (intervalNs > audioWriteIntervalMaxNs) audioWriteIntervalMaxNs = intervalNs
                }
                audioWriteLastNs = nowNs
            }
            if (written > 0) {
                audioWriteCalls += 1
                audioWriteSamples += written.toLong()
                if (written == AUDIO_TRACK_WRITE_SAMPLES) {
                    audioWriteCount += 1
                }
            }
        }
    }

    private fun formatCadenceSummary(
        label: String,
        count: Long,
        samples: Long,
        intervalTotalNs: Long,
        intervalMinNs: Long,
        intervalMaxNs: Long,
    ): String {
        if (count <= 0L) return "$label idle"
        val avgSamples = (samples.toDouble() / count.toDouble()).roundToInt()
        val intervalCount = (count - 1).coerceAtLeast(0)
        val avgIntervalMs = if (intervalCount > 0L) intervalTotalNs.toDouble() / intervalCount.toDouble() / 1_000_000.0 else 0.0
        val minIntervalMs = if (intervalMinNs != Long.MAX_VALUE) intervalMinNs.toDouble() / 1_000_000.0 else 0.0
        val maxIntervalMs = intervalMaxNs.toDouble() / 1_000_000.0
        return "$label n=" + count +
            " avg=" + avgSamples +
            " dt=" + String.format("%.2f", avgIntervalMs) + "ms" +
            " min=" + String.format("%.2f", minIntervalMs) + "ms" +
            " max=" + String.format("%.2f", maxIntervalMs) + "ms"
    }

    private fun formatDemodulationPathSummary(settings: DirectRtlReceiverSettings): String {
        val squelchState = if (squelchOpen) "open" else "closed"
        val offsetText = if (estimatedCarrierOffsetHz >= 0) "+$estimatedCarrierOffsetHz" else estimatedCarrierOffsetHz.toString()
        val syncText = if (settings.advancedSyncEnabled) "adv" else "basic"
        val demodText =
            when (settings.demodMode) {
                DEMOD_MODE_AM_ENVELOPE -> "env"
                DEMOD_MODE_AM_SYNC -> "sync"
                DEMOD_MODE_SSB -> when (settings.sidebandMode) {
                    AM_SIDEBAND_MODE_LOWER -> "lsb"
                    else -> "usb"
                }
                DEMOD_MODE_NFM -> "nfm"
                DEMOD_MODE_WFM -> "wfm"
                else -> settings.demodMode.toString()
            }
        return settings.modulationLabel() + " rf=" + rfLevelPercent +
            " audio=" + audioLevelPercent +
            " clip=" + clipPercent +
            " iqClip=" + iqClipPercent +
            " off=" + offsetText + "Hz" +
            " sq=" + squelchPercent + "/" + squelchThresholdPercent + " " + squelchState +
            " demod=" + demodText +
            " sync=" + syncText
    }

    private fun updateSnapshotWidth(currentWidth: Int, value: Int, signed: Boolean = false): Int {
        val renderedLength = if (signed && value >= 0) value.toString().length + 1 else value.toString().length
        return maxOf(currentWidth, renderedLength)
    }

    private fun formatSnapshotValue(value: Int, width: Int, signed: Boolean = false): String {
        val rendered = if (signed && value >= 0) "+$value" else value.toString()
        return rendered.padStart(width)
    }

    private fun maybeUpdateDriftSnapshot() {
        if (!streaming) return
        syncNativeStreamMetrics()
        val nowNs = System.nanoTime()
        val settings = receiverSettings
        synchronized(audioBufferLock) {
            if (snapshotWindowStartNs == 0L) {
                snapshotWindowStartNs = nowNs
                snapshotWindowProducedSamples = totalProducedSamples
                snapshotWindowConsumedSamples = totalConsumedSamples
                snapshotWindowUsbBytes = totalUsbBytes
                snapshotWindowUsbBlocks = totalUsbBlocks
                snapshotWindowUsbFailures = totalUsbFailures
                snapshotWindowDroppedSamples = totalDroppedSamples
                return
            }
            val windowElapsedNs = nowNs - snapshotWindowStartNs
            if (windowElapsedNs <= 0L) return
            if (windowElapsedNs < 1_000_000_000L && driftSnapshotLastNs != 0L) return

            val elapsedSeconds = windowElapsedNs / 1_000_000_000.0
            val producedPerSecond = ((totalProducedSamples - snapshotWindowProducedSamples).toDouble() / elapsedSeconds).roundToInt()
            val consumedPerSecond = ((totalConsumedSamples - snapshotWindowConsumedSamples).toDouble() / elapsedSeconds).roundToInt()
            val usbBytesPerSecond = ((totalUsbBytes - snapshotWindowUsbBytes).toDouble() / elapsedSeconds).roundToInt()
            val usbBlocksPerSecond = ((totalUsbBlocks - snapshotWindowUsbBlocks).toDouble() / elapsedSeconds).roundToInt()
            val usbFailuresPerSecond = ((totalUsbFailures - snapshotWindowUsbFailures).toDouble() / elapsedSeconds).roundToInt()
            val droppedSamplesPerSecond = ((totalDroppedSamples - snapshotWindowDroppedSamples).toDouble() / elapsedSeconds).roundToInt()
            val driftPerSecond = producedPerSecond - consumedPerSecond

            snapshotBufWidth = updateSnapshotWidth(snapshotBufWidth, audioRingSize)
            snapshotProducedWidth = updateSnapshotWidth(snapshotProducedWidth, producedPerSecond)
            snapshotConsumedWidth = updateSnapshotWidth(snapshotConsumedWidth, consumedPerSecond)
            snapshotDriftWidth = updateSnapshotWidth(snapshotDriftWidth, driftPerSecond, signed = true)
            snapshotUsbBytesWidth = updateSnapshotWidth(snapshotUsbBytesWidth, usbBytesPerSecond)
            snapshotUsbBlocksWidth = updateSnapshotWidth(snapshotUsbBlocksWidth, usbBlocksPerSecond)
            snapshotUsbFailuresWidth = updateSnapshotWidth(snapshotUsbFailuresWidth, usbFailuresPerSecond)

            driftSnapshotLastNs = nowNs
            driftSnapshotCounter += 1
            driftSnapshotText =
                "SDR#" + driftSnapshotCounter +
                " Buf " + formatSnapshotValue(audioRingSize, snapshotBufWidth) +
                "\nP " + formatSnapshotValue(producedPerSecond, snapshotProducedWidth) + "/s" +
                " | C " + formatSnapshotValue(consumedPerSecond, snapshotConsumedWidth) + "/s" +
                " | D " + formatSnapshotValue(driftPerSecond, snapshotDriftWidth, signed = true) + "/s" +
                "\nUB " + formatSnapshotValue(usbBytesPerSecond, snapshotUsbBytesWidth) + "/s" +
                " | UY " + formatSnapshotValue(usbBlocksPerSecond, snapshotUsbBlocksWidth) + "/s" +
                " | UF " + formatSnapshotValue(usbFailuresPerSecond, snapshotUsbFailuresWidth) + "/s" +
                "\nPD " + droppedSamplesPerSecond + "/s" +
                "\n" + formatDemodulationPathSummary(settings)
            val cadenceSummary =
                formatCadenceSummary(
                    label = "PCM read",
                    count = audioReadCount,
                    samples = audioReadSamples,
                    intervalTotalNs = audioReadIntervalTotalNs,
                    intervalMinNs = audioReadIntervalMinNs,
                    intervalMaxNs = audioReadIntervalMaxNs,
                ) + " | " +
                    formatCadenceSummary(
                        label = "Track write",
                        count = audioWriteCalls,
                        samples = audioWriteSamples,
                        intervalTotalNs = audioWriteIntervalTotalNs,
                        intervalMinNs = audioWriteIntervalMinNs,
                        intervalMaxNs = audioWriteIntervalMaxNs,
                    )
            driftSnapshotText += "\n" + cadenceSummary
            traceInfo(driftSnapshotText.replace('\n', ' '))
            traceInfo(cadenceSummary)
            snapshotWindowStartNs = nowNs
            snapshotWindowProducedSamples = totalProducedSamples
            snapshotWindowConsumedSamples = totalConsumedSamples
            snapshotWindowUsbBytes = totalUsbBytes
            snapshotWindowUsbBlocks = totalUsbBlocks
            snapshotWindowUsbFailures = totalUsbFailures
            snapshotWindowDroppedSamples = totalDroppedSamples
            audioReadWindowStartNs = nowNs
            audioReadLastNs = 0L
            audioReadCount = 0L
            audioReadSamples = 0L
            audioReadIntervalTotalNs = 0L
            audioReadIntervalMinNs = Long.MAX_VALUE
            audioReadIntervalMaxNs = 0L
            audioWriteWindowStartNs = nowNs
            audioWriteLastNs = 0L
            audioWriteCount = 0L
            audioWriteSamples = 0L
            audioWriteCalls = 0L
            audioWriteIntervalTotalNs = 0L
            audioWriteIntervalMinNs = Long.MAX_VALUE
            audioWriteIntervalMaxNs = 0L
        }
    }

    private fun resetAudioRingBuffer() {
        synchronized(audioBufferLock) {
            audioRingBuffer = ShortArray(AUDIO_RING_BUFFER_SAMPLES)
            audioRingReadIndex = 0
            audioRingWriteIndex = 0
            audioRingSize = 0
            audioMetricsStartNs = if (streaming) System.nanoTime() else 0L
            totalProducedSamples = 0L
            totalConsumedSamples = 0L
            totalUsbBytes = 0L
            totalUsbBlocks = 0L
            totalUsbFailures = 0L
            totalDroppedSamples = 0L
            lastLoggedUsbFailureTotal = 0L
            lastLoggedDroppedSamplesTotal = 0L
            driftSnapshotCounter = 0
            driftSnapshotText = ""
            driftSnapshotLastNs = 0L
            snapshotWindowStartNs = audioMetricsStartNs
            snapshotWindowProducedSamples = 0L
            snapshotWindowConsumedSamples = 0L
            snapshotWindowUsbBytes = 0L
            snapshotWindowUsbBlocks = 0L
            snapshotWindowUsbFailures = 0L
            snapshotWindowDroppedSamples = 0L
            audioReadWindowStartNs = audioMetricsStartNs
            audioReadLastNs = 0L
            audioReadCount = 0L
            audioReadSamples = 0L
            audioReadIntervalTotalNs = 0L
            audioReadIntervalMinNs = Long.MAX_VALUE
            audioReadIntervalMaxNs = 0L
            audioWriteWindowStartNs = audioMetricsStartNs
            audioWriteLastNs = 0L
            audioWriteCount = 0L
            audioWriteSamples = 0L
            audioWriteCalls = 0L
            audioWriteIntervalTotalNs = 0L
            audioWriteIntervalMinNs = Long.MAX_VALUE
            audioWriteIntervalMaxNs = 0L
            audioBufferLock.notifyAll()
        }
    }

    private fun writeBlocking(track: AudioTrack, buffer: ShortArray, sampleCount: Int) {
        synchronized(audioTrackIoLock) {
            var capturedOffset = 0
            writePcmBlock(
                sampleCount = sampleCount,
                isActive = { streaming },
                write = { offset, count -> track.write(buffer, offset, count) },
                onWritten = { written ->
                    audioTrackWrittenFrames += written
                    val now = System.nanoTime()
                    val queuedFrames = currentAudioTrackQueuedSamplesLocked()
                    PlaybackCapture.accepted(buffer, capturedOffset, written, queuedFrames)
                    capturedOffset += written
                    playbackBufferSnapshot = AudioPlaybackBufferSnapshot(
                        queuedFrames, now, AUDIO_SAMPLE_RATE,
                    )
                    recordAudioWriteCadence(now, written)
                },
            )
        }
    }

    private fun stopAudioInternal() {
        streaming = false
        audioPlaybackError = null
        forceSilentAudioUntilFreshPcm = false
        foregroundController.stop(context.applicationContext)
        DirectRtlSdrNativeBridge.stopAmStream()
        synchronized(audioBufferLock) { audioBufferLock.notifyAll() }
        readerThread?.takeIf { it !== Thread.currentThread() }?.join(500)
        writerThread?.takeIf { it !== Thread.currentThread() }?.join(500)
        readerThread = null
        writerThread = null
        resetAudioRingBuffer()
        synchronized(audioTrackIoLock) {
            runCatching { audioTrack?.stop() }
            runCatching { audioTrack?.release() }
            audioTrack = null
            resetAudioTrackBufferMetricsLocked(null)
        }
        if (amPipelineHandle != 0L) {
            DirectRtlSdrNativeBridge.resetAmPipeline(amPipelineHandle)
        }
        rfLevelPercent = 0
        audioLevelPercent = 0
        clipPercent = 0
        iqClipPercent = 0
        estimatedCarrierOffsetHz = 0
        squelchPercent = 0
        squelchNoiseFloorPercent = 0
        squelchThresholdPercent = 0
        squelchEffectiveThresholdPercent = 0
        squelchOpen = true
        spectrumBins = intArrayOf()
    }

    private fun disconnectInternal() {
        stopAudioInternal()
        DirectRtlSdrNativeBridge.close()
        if (amPipelineHandle != 0L) {
            DirectRtlSdrNativeBridge.destroyAmPipeline(amPipelineHandle)
            amPipelineHandle = 0L
        }
        releaseClaimedInterface()
        runCatching { deviceConnection?.close() }
        deviceConnection = null
        connected = false
        sessionConfigured = false
        claimedInterface = null
        settingsApplication.invalidate()
        claimedInterfaceIndex = null
        bulkInEndpoint = null
        bulkInEndpointAddress = null
        sampleRateHz = null
        tunedFrequencyHz = null
        tunedCenterFrequencyHz = null
        tunedAsAirbandChannelDesignator = false
        rfLevelPercent = 0
        audioLevelPercent = 0
        clipPercent = 0
        iqClipPercent = 0
        estimatedCarrierOffsetHz = 0
        squelchPercent = 0
        squelchNoiseFloorPercent = 0
        squelchThresholdPercent = 0
        squelchEffectiveThresholdPercent = 0
        squelchOpen = true
        spectrumBins = intArrayOf()
    }

    private fun currentSupportedUsbDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.filter(::looksLikeRtlSdr).sortedBy { it.productName ?: it.deviceName }
    }

    private fun selectedDeviceOrNull(devices: List<DirectRtlDeviceInfo>): DirectRtlDeviceInfo? {
        return devices.firstOrNull { it.deviceName == selectedDeviceName } ?: devices.firstOrNull()
    }

    private fun findTransportInterface(device: UsbDevice): DirectTransport? {
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            val endpoint = findBulkInEndpoint(usbInterface) ?: continue
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC || usbInterface.interfaceClass == UsbConstants.USB_CLASS_PER_INTERFACE) {
                return DirectTransport(index, usbInterface, endpoint)
            }
        }
        return null
    }

    private fun findBulkInEndpoint(usbInterface: UsbInterface): UsbEndpoint? {
        for (endpointIndex in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(endpointIndex)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == UsbConstants.USB_DIR_IN) return endpoint
        }
        return null
    }

    private fun releaseClaimedInterface() {
        val connection = deviceConnection ?: return
        val usbInterface = claimedInterface ?: return
        runCatching { connection.releaseInterface(usbInterface) }
    }

    private fun looksLikeRtlSdr(device: UsbDevice): Boolean {
        if (KNOWN_RTL_VID_PID.contains(device.vendorId to device.productId)) return true
        if (device.interfaceCount == 0) return false
        return (0 until device.interfaceCount).any { index ->
            val iface = device.getInterface(index)
            iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC ||
                (iface.interfaceClass == UsbConstants.USB_CLASS_PER_INTERFACE && findBulkInEndpoint(iface) != null)
        }
    }

    private fun hardwareCenterFrequencyHz(searchCenterFrequencyHz: Long): Long {
        return zeroIfSafeHardwareCenterFrequencyHz(searchCenterFrequencyHz) +
            receiverSettings.frequencyCorrectionHz.toLong()
    }

    private fun resolveCenterFrequencyHz(frequencyHz: Long, airbandChannelDesignator: Boolean): Long {
        return if (airbandChannelDesignator) {
            airbandChannelSearchCenterFrequencyHz(frequencyHz)
        } else {
            frequencyHz
        }
    }

    private fun sanitizeReceiverSettings(settings: DirectRtlReceiverSettings): DirectRtlReceiverSettings {
        val squelchMode = when (settings.squelchMode) {
            RTL_SQUELCH_MODE_MANUAL -> RTL_SQUELCH_MODE_MANUAL
            RTL_SQUELCH_MODE_CUSTOM -> RTL_SQUELCH_MODE_CUSTOM
            else -> RTL_SQUELCH_MODE_AUTO
        }
        val squelchLevel = settings.squelchLevel.coerceIn(0, 100)
        return settings.copy(
            frequencyCorrectionHz = settings.frequencyCorrectionHz.coerceIn(ReceiverSettingRules.MIN_CORRECTION_HZ, ReceiverSettingRules.MAX_CORRECTION_HZ),
            ppmCorrection = settings.ppmCorrection.coerceIn(ReceiverSettingRules.MIN_PPM, ReceiverSettingRules.MAX_PPM),
            preferredSampleRateHz = sanitizePreferredSampleRate(settings.preferredSampleRateHz),
            amBandwidthHz = settings.amBandwidthHz.coerceIn(6_000, 20_000),
            squelchMode = squelchMode,
            squelchLevel = squelchLevel,
            squelchOpenDebounceMs = settings.squelchOpenDebounceMs.coerceIn(ReceiverSettingRules.openDelayRange(squelchMode, squelchLevel)),
            squelchCloseHangMs = settings.squelchCloseHangMs.coerceIn(ReceiverSettingRules.closeHangRange(squelchMode, squelchLevel)),
            hardSquelchMuteEnabled = settings.hardSquelchMuteEnabled,
            gainMode = if (settings.gainMode == GAIN_MODE_MANUAL) GAIN_MODE_MANUAL else GAIN_MODE_AUTO,
            tunerGain = nearestSupportedGain(settings.tunerGain),
            audioBoostPercent = settings.audioBoostPercent.coerceIn(100, 320),
            voiceFocusEnabled = settings.voiceFocusEnabled,
            postLevelerEnabled = settings.postLevelerEnabled,
            noiseBlankerEnabled = settings.noiseBlankerEnabled,
            denoiseMode = settings.denoiseMode.coerceIn(DENOISE_MODE_OFF, DENOISE_MODE_AUTO),
            demodMode = settings.demodMode.coerceIn(DEMOD_MODE_AM_ENVELOPE, DEMOD_MODE_WFM),
            notchMode = settings.notchMode.coerceIn(NOTCH_MODE_OFF, NOTCH_MODE_AUTO),
            sidebandMode = settings.sidebandMode.coerceIn(AM_SIDEBAND_MODE_FULL, AM_SIDEBAND_MODE_UPPER),
            advancedSyncEnabled = settings.advancedSyncEnabled,
            qualitySquelchEnabled = settings.qualitySquelchEnabled,
            speechEnhancerMode = settings.speechEnhancerMode.coerceIn(SPEECH_ENHANCER_MODE_OFF, SPEECH_ENHANCER_MODE_STRONG),
        ).let(selectedAmProfile::effectiveSettings)
    }


    private fun nearestSupportedGain(requestedGain: Int): Int {
        var best = SUPPORTED_TUNER_GAINS.first()
        var bestDistance = abs(requestedGain - best)
        for (candidate in SUPPORTED_TUNER_GAINS) {
            val distance = abs(requestedGain - candidate)
            if (distance < bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }

    private fun receiverSettingsSummary(prefix: String): String {
        val settings = receiverSettings
        val squelchModeText = when (settings.squelchMode) {
            RTL_SQUELCH_MODE_MANUAL -> "manual"
            RTL_SQUELCH_MODE_CUSTOM -> "custom"
            else -> "auto"
        }
        val squelchText = when {
            settings.squelchMode == RTL_SQUELCH_MODE_AUTO && settings.squelchLevel == 0 -> "off"
            settings.squelchLevel == 0 -> "open"
            else -> "${settings.squelchLevel}%"
        }
        val openText = "${settings.squelchOpenDebounceMs} ms"
        val closeText = "${settings.squelchCloseHangMs} ms"
        val hardMuteText = if (settings.hardSquelchMuteEnabled) "hard mute on" else "hard mute off"
        val biasText = if (settings.biasTeeEnabled) "on" else "off"
        val gainText = if (settings.gainMode == GAIN_MODE_AUTO) "auto" else "manual ${settings.tunerGain / 10.0} dB"
        val voiceText = if (settings.voiceFocusEnabled) "profile voice" else "profile natural"
        val levelerText = if (settings.postLevelerEnabled) "leveler on" else "leveler off"
        val blankerText = if (settings.noiseBlankerEnabled) "blanker on" else "blanker off"
        val denoiseText = when (settings.denoiseMode) {
            DENOISE_MODE_MILD -> "denoise mild"
            DENOISE_MODE_STRONG -> "denoise strong"
            DENOISE_MODE_AUTO -> "denoise auto"
            else -> "denoise off"
        }
        val simpleAm = selectedAmProfile.tuning.simpleAmEnabled && settings.demodMode == DEMOD_MODE_AM_ENVELOPE
        val profileText = "profile ${selectedAmProfile.option.label}" + if (simpleAm) ", simple AM" else ""
        val demodText = "demod ${demodModeLabel(settings.demodMode)}"
        val notchText = if (settings.notchMode == NOTCH_MODE_AUTO) "notch auto" else "notch off"
        val sidebandText = when (settings.sidebandMode) {
            AM_SIDEBAND_MODE_LOWER -> "sideband lower"
            AM_SIDEBAND_MODE_UPPER -> "sideband upper"
            else -> "sideband full"
        }
        val syncText = if (settings.advancedSyncEnabled) "sync advanced" else "sync basic"
        val qualitySquelchText = if (settings.qualitySquelchEnabled) "quality squelch on" else "quality squelch off"
        val speechText = when (settings.speechEnhancerMode) {
            SPEECH_ENHANCER_MODE_MILD -> "speech mild"
            SPEECH_ENHANCER_MODE_STRONG -> "speech strong"
            else -> "speech off"
        }
        val speechFilterText = if (settings.amSpeechFilterEnabled) "AM speech filter 250-2500 Hz on" else "AM speech filter off"
        return "$prefix: corr ${settings.frequencyCorrectionHz} Hz, ppm ${settings.ppmCorrection}, bias $biasText, bw ${settings.amBandwidthHz} Hz, squelch $squelchModeText $squelchText, open $openText, close $closeText, $hardMuteText, gain $gainText, audio ${settings.audioBoostPercent}%, $voiceText, $levelerText, $blankerText, $denoiseText, $notchText, $sidebandText, $syncText, $qualitySquelchText, $speechText, $speechFilterText, $profileText, $demodText"
    }

    private fun buildNativeAmConfig(): NativeAmConfig {
        val settings = receiverSettings
        return NativeAmConfig(
            inputSampleRateHz = sampleRateHz ?: settings.preferredSampleRateHz,
            outputSampleRateHz = AUDIO_SAMPLE_RATE,
            channelBandwidthHz = receiverRfBandwidthHz(settings.demodMode, settings.amBandwidthHz),
            squelchMode = if (settings.squelchMode == RTL_SQUELCH_MODE_AUTO) RTL_SQUELCH_MODE_AUTO else RTL_SQUELCH_MODE_MANUAL,
            squelchLevel = settings.squelchLevel,
            hardSquelchMuteEnabled = settings.hardSquelchMuteEnabled,
            audioBoostPercent = settings.audioBoostPercent,
            voiceFocusEnabled = settings.voiceFocusEnabled,
            postLevelerEnabled = settings.postLevelerEnabled,
            noiseBlankerEnabled = settings.noiseBlankerEnabled,
            denoiseMode = settings.denoiseMode,
            demodMode = settings.demodMode,
            notchMode = settings.notchMode,
            sidebandMode = settings.sidebandMode,
            advancedSyncEnabled = settings.advancedSyncEnabled,
            qualitySquelchEnabled = settings.qualitySquelchEnabled,
            speechEnhancerMode = settings.speechEnhancerMode,
            amSpeechFilterEnabled = settings.amSpeechFilterEnabled,
            tuning = selectedAmProfile.tuning.copy(
                squelchOpenDebounceMs = settings.squelchOpenDebounceMs,
                squelchCloseHangMs = settings.squelchCloseHangMs,
            ),
            carrierTargets = tunedFrequencyHz?.let { frequencyHz ->
                amCarrierTargetsForTuning(
                    frequencyHz,
                    tunedAsAirbandChannelDesignator,
                    hardwareCenterFrequencyHz(resolveCenterFrequencyHz(frequencyHz, tunedAsAirbandChannelDesignator)),
                )
            },
        )
    }

    private fun demodModeLabel(demodMode: Int): String {
        return when (demodMode) {
            DEMOD_MODE_AM_SYNC -> "sync"
            DEMOD_MODE_SSB -> "ssb"
            DEMOD_MODE_NFM -> "nfm"
            DEMOD_MODE_WFM -> "wfm"
            else -> "envelope"
        }
    }

    private fun debugStateSnapshot(state: DirectRtlBackendState = refreshState()): String {
        return buildString {
            append("connected=").append(state.connected)
            append(" streaming=").append(state.streaming)
            append(" session=").append(state.sessionConfigured)
            append(" usb=").append(state.usbPermissionGranted)
            append(" freqHz=").append(state.tunedFrequencyHz ?: "null")
            append(" centerHz=").append(state.tunedCenterFrequencyHz ?: "null")
            append(" sampleRateHz=").append(state.sampleRateHz ?: "null")
            append(" dev=").append(state.selectedDeviceName ?: "null")
            append(" if=").append(state.claimedInterfaceIndex ?: "null")
            append(" ep=").append(state.bulkInEndpointAddress ?: "null")
            append(" ring=").append(state.audioBufferSamples)
            append(" thread=").append(Thread.currentThread().name)
        }
    }

    private fun supportedSampleRateCandidates(): IntArray {
        val ordered = linkedSetOf(sanitizePreferredSampleRate(receiverSettings.preferredSampleRateHz), 240_000, 480_000, 960_000)
        return ordered.toIntArray()
    }

    private fun sanitizePreferredSampleRate(sampleRateHz: Int): Int {
        return when (sampleRateHz) {
            240_000, 480_000, 960_000 -> sampleRateHz
            else -> 240_000
        }
    }

    private fun Intent?.usbDeviceExtra(): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            this?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    companion object {
        @Volatile private var sharedInstance: DirectRtlSdrController? = null

        fun getInstance(context: Context): DirectRtlSdrController {
            return sharedInstance ?: synchronized(this) {
                sharedInstance ?: DirectRtlSdrController(context.applicationContext).also { sharedInstance = it }
            }
        }

        private const val PREFS_NAME = "opensdr_direct_sdr"
        private const val KEY_FREQUENCY_CORRECTION_HZ = "frequency_correction_hz"
        private const val KEY_PPM_CORRECTION = "ppm_correction"
        private const val KEY_BIAS_TEE_ENABLED = "bias_tee_enabled"
        private const val KEY_PREFERRED_SAMPLE_RATE_HZ = "preferred_sample_rate_hz"
        private const val KEY_AM_BANDWIDTH_HZ = "am_bandwidth_hz"
        private const val KEY_SQUELCH_MODE = "squelch_mode"
        private const val KEY_SQUELCH_LEVEL = "squelch_level"
        private const val KEY_SQUELCH_OPEN_DEBOUNCE_MS = "squelch_open_debounce_ms"
        private const val KEY_SQUELCH_CLOSE_HANG_MS = "squelch_close_hang_ms"
        private const val KEY_HARD_SQUELCH_MUTE_ENABLED = "hard_squelch_mute_enabled"
        private const val KEY_GAIN_MODE = "gain_mode"
        private const val KEY_TUNER_GAIN = "tuner_gain"
        private const val KEY_AUDIO_BOOST_PERCENT = "audio_boost_percent"
        private const val KEY_VOICE_FOCUS_ENABLED = "voice_focus_enabled"
        private const val KEY_POST_LEVELER_ENABLED = "post_leveler_enabled"
        private const val KEY_NOISE_BLANKER_ENABLED = "noise_blanker_enabled"
        private const val KEY_DENOISE_MODE = "denoise_mode"
        private const val KEY_DEMOD_MODE = "demod_mode"
        private const val KEY_NOTCH_MODE = "notch_mode"
        private const val KEY_SIDEBAND_MODE = "sideband_mode"
        private const val KEY_ADVANCED_SYNC_ENABLED = "advanced_sync_enabled"
        private const val KEY_QUALITY_SQUELCH_ENABLED = "quality_squelch_enabled"
        private const val KEY_AM_SPEECH_FILTER_ENABLED = "am_speech_filter_enabled"
        private const val KEY_SPEECH_ENHANCER_MODE = "speech_enhancer_mode"
        private const val KEY_AM_PROFILE_ID = "am_profile_id"
        private const val DEFAULT_FREQUENCY_HZ = 118_000_000L
        private const val DEFAULT_SAMPLE_RATE_HZ = 240_000
        private const val GAIN_MODE_AUTO = 0
        private const val GAIN_MODE_MANUAL = 1
        private const val DEFAULT_OFFSET_TUNING = false
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_TRACK_WRITE_SAMPLES = AUDIO_SAMPLE_RATE / 40
        private const val AUDIO_RING_BUFFER_SAMPLES = AUDIO_SAMPLE_RATE * 8
        private const val AUDIO_RING_TARGET_SAMPLES = AUDIO_SAMPLE_RATE
        private const val AUDIO_RING_CRITICAL_LOW_SAMPLES = (AUDIO_RING_TARGET_SAMPLES * 2) / 3
        private const val AUDIO_RING_CRITICAL_HIGH_SAMPLES = AUDIO_RING_TARGET_SAMPLES + (AUDIO_TRACK_WRITE_SAMPLES * 12)
        private const val AUDIO_TRACK_START_SAMPLES = AUDIO_RING_TARGET_SAMPLES + (AUDIO_TRACK_WRITE_SAMPLES * 12)
        private const val AUDIO_TRACK_START_READY_CHECKS = 4
        private const val AUDIO_STARVED_READ_LOG_THRESHOLD = 3
        private const val AUDIO_STARVED_READ_LOG_REPEAT = 25
        private const val IQ_READ_SIZE = 4096
        private const val USB_READ_TIMEOUT_MS = 250
        // Reader cadence already shows occasional >100 ms scheduling spikes.
        // Keep the PCM read timeout comfortably above that so we do not inject
        // fake starvation gaps into otherwise continuous speech.
        private const val AUDIO_TRACK_WRITE_TIMEOUT_MS = 180L
        private const val AUDIO_TRACK_START_TIMEOUT_MS = 100L
        private const val AM_LOG_TAG = "OpenSdrAm"
        private const val AM_LOG_OFF = 0
        private const val AM_LOG_WARN = 1
        private const val AM_LOG_INFO = 1
        private const val AM_LOG_DEBUG = 2
        private const val AM_LOG_LEVEL = AM_LOG_INFO

        private val AUDIO_PLAYBACK_RATE_CONFIG = PcmPlaybackRateConfig(
            nominalRateHz = AUDIO_SAMPLE_RATE,
            minRateHz = AUDIO_SAMPLE_RATE * 3 / 4,
            maxRateHz = AUDIO_SAMPLE_RATE * 5 / 4,
            targetBufferedSamples = AUDIO_RING_TARGET_SAMPLES,
            bufferDeadbandSamples = AUDIO_TRACK_WRITE_SAMPLES / 5,
            bufferCorrectionDivisor = 24,
            maxBufferCorrectionHz = AUDIO_SAMPLE_RATE / 20,
            maxRateStepHz = AUDIO_TRACK_WRITE_SAMPLES / 10,
        )

        private val SUPPORTED_TUNER_GAINS = intArrayOf(
            0, 90, 140, 197, 254, 280, 297, 328, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496
        )

        private val KNOWN_RTL_VID_PID = setOf(
            0x0BDA to 0x2832,
            0x0BDA to 0x2838,
            0x1D19 to 0x1101,
            0x1D19 to 0x1102,
            0x1D19 to 0x1103,
            0x1D19 to 0x1104,
            0x1F4D to 0xA803,
            0x1F4D to 0xB803,
            0x185B to 0x0620,
        )
    }

    private data class DirectTransport(
        val interfaceIndex: Int,
        val usbInterface: UsbInterface,
        val bulkInEndpoint: UsbEndpoint,
    )
}
