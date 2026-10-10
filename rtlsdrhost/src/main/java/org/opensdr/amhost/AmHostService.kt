package org.opensdr.amhost

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import org.opensdr.amhost.sdr.ReceiverForegroundNotification
import org.opensdr.amhost.sdr.ReceiverServiceLifetime
import org.opensdr.sdrcore.DirectRtlAmProfileOption
import org.opensdr.sdrcore.DirectRtlBackendState
import org.opensdr.sdrcore.DirectRtlSdrController
import org.opensdr.sdrcore.DirectRtlSdrForegroundController
import org.opensdr.radioapi.AmHostStatus
import org.opensdr.radioapi.HostAmProfileOption
import org.opensdr.radioapi.HostDeviceInfo
import org.opensdr.radioapi.readHostReceiverSettings
import org.opensdr.radioapi.putHostStatus
import org.opensdr.radioapi.RtlSdrIpcContract
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bound/started foreground service exposing the OpenSDR receiver through `radioapi`.
 *
 * The service is intentionally a translation layer: it maps Messenger commands
 * to [DirectRtlSdrController], converts controller state into parcelable API
 * models, and replies with the latest full [AmHostStatus]. SDR lifecycle and
 * audio processing remain in `opensdrcore`. This same service owns foreground
 * playback so a client unbinding cannot destroy an active receiver session.
 */
class AmHostService : Service() {
    private lateinit var controller: DirectRtlSdrController
    private lateinit var messenger: Messenger
    private lateinit var serviceThread: HandlerThread
    private lateinit var controllerHandler: Handler
    private lateinit var playbackLifetime: ReceiverServiceLifetime
    private lateinit var recording: HostRecordingFeature
    @Volatile private var latestStatus: AmHostStatus = AmHostStatus(statusText = "Service starting")

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // USB broadcasts must not disconnect/free the native pipeline while
            // the IPC thread is connecting, tuning, or updating that pipeline.
            controllerHandler.post {
                val state = when (intent?.action) {
                    ACTION_USB_PERMISSION -> controller.handleUsbPermissionResult(intent)
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> controller.handleUsbDeviceAttached(intent)
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> controller.handleUsbDeviceDetached(intent)
                    else -> controller.refreshState()
                }
                latestStatus = state.toApiStatus()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        recording = HostRecordingFeature(applicationContext)
        val notification = ReceiverForegroundNotification(this)
        playbackLifetime = ReceiverServiceLifetime(
            startService = {
                val intent = Intent(this, AmHostService::class.java).setAction(ACTION_KEEP_PLAYBACK_ALIVE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            },
            showForeground = notification::show,
            releaseForeground = notification::release,
            stopService = { startId ->
                if (startId == null) stopSelf() else stopSelf(startId)
            },
        )
        controller = DirectRtlSdrController(
            context = applicationContext,
            requestControllerRefresh = {
                controllerHandler.post {
                    runCatching { controller.refreshState().toApiStatus() }
                        .onSuccess { latestStatus = it }
                        .onFailure { Log.e(TAG, "Audio failure cleanup failed", it) }
                }
            },
            foregroundController = object : DirectRtlSdrForegroundController {
                override fun start(context: Context, frequencyText: String?) {
                    playbackLifetime.start(frequencyText)
                }

                override fun stop(context: Context) {
                    playbackLifetime.stop()
                }
            },
        )
        serviceThread = HandlerThread("OpenSdrAmHostIpc").also { it.start() }
        controllerHandler = IncomingHandler(serviceThread.looper)
        messenger = Messenger(controllerHandler)
        registerUsbReceiver()
        latestStatus = controller.refreshState().toApiStatus()
        Log.w(TAG, "onCreate latest=${latestStatus.statusText} | ${latestStatus.debugSnapshot()}")
    }

    override fun onDestroy() {
        Log.w(TAG, "onDestroy disconnecting controller | ${latestStatus.debugSnapshot()}")
        playbackLifetime.destroy()
        recording.close()
        runCatching { unregisterReceiver(usbReceiver) }
        controllerHandler.post {
            try {
                controller.disconnect()
            } finally {
                serviceThread.quitSafely()
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != RtlSdrIpcContract.SERVICE_ACTION) return null
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "Last client unbound; playback lifetime unchanged | ${latestStatus.debugSnapshot()}")
        return super.onUnbind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_AUDIO_FROM_NOTIFICATION) {
            controllerHandler.post {
                latestStatus = controller.stopAudio().toApiStatus()
                Log.w(TAG, "Notification stop action completed | ${latestStatus.debugSnapshot()}")
            }
        } else {
            playbackLifetime.onStartCommand(startId)
        }
        // Do not resurrect a notification/wake lock without a live USB/audio
        // session after process death. A new listening session is user initiated.
        return START_NOT_STICKY
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private inner class IncomingHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            if (handleSpectrumHistoryRequest(msg)) return
            if (recording.handle(msg, controller)) return
            recording.beforeCommand(msg)
            val requestId = REQUEST_COUNTER.incrementAndGet()
            val requestName = msg.what.messageName()
            Log.w(TAG, "IPC #$requestId $requestName received | before=${latestStatus.debugSnapshot()}")
            val state = runCatching {
                when (msg.what) {
                    RtlSdrIpcContract.MSG_GET_STATUS -> controller.refreshState()
                    RtlSdrIpcContract.MSG_REQUEST_USB_PERMISSION -> controller.requestUsbPermission(ACTION_USB_PERMISSION)
                    RtlSdrIpcContract.MSG_CONNECT -> controller.connect()
                    RtlSdrIpcContract.MSG_DISCONNECT -> controller.disconnect()
                    RtlSdrIpcContract.MSG_START_AUDIO -> controller.startAudio()
                    RtlSdrIpcContract.MSG_STOP_AUDIO -> controller.stopAudio()
                    RtlSdrIpcContract.MSG_TUNE -> {
                        val frequencyHz = msg.data?.getLong(RtlSdrIpcContract.KEY_FREQUENCY_HZ)?.takeIf { it > 0L }
                        val airbandChannelDesignator = msg.data?.getBoolean(
                            RtlSdrIpcContract.KEY_AIRBAND_CHANNEL_DESIGNATOR,
                            false,
                        ) == true
                        if (frequencyHz != null) {
                            controller.tuneAmHz(frequencyHz, airbandChannelDesignator)
                        } else {
                            controller.refreshState().copy(status = "Missing frequency")
                        }
                    }
                    RtlSdrIpcContract.MSG_UPDATE_LISTENING_CONTROLS,
                    RtlSdrIpcContract.MSG_UPDATE_CALIBRATED_LISTENING_CONTROLS -> {
                        val calibrated = msg.what == RtlSdrIpcContract.MSG_UPDATE_CALIBRATED_LISTENING_CONTROLS
                        val settings = msg.data.readHostReceiverSettings(RtlSdrIpcContract.KEY_SETTINGS)
                        if (settings != null) {
                            controller.updateListeningControls(
                                ppmCorrection = settings.ppmCorrection,
                                biasTeeEnabled = settings.biasTeeEnabled,
                                gainMode = if (calibrated) null else settings.gainMode,
                                tunerGain = if (calibrated) null else settings.tunerGain,
                                squelchMode = settings.squelchMode,
                                squelchLevel = settings.squelchLevel,
                                amSpeechFilterEnabled = settings.amSpeechFilterEnabled,
                                frequencyCorrectionHz = if (calibrated) settings.frequencyCorrectionHz else null,
                            )
                        } else {
                            controller.refreshState().copy(
                                status = "Missing listening controls",
                                receiverSettingsError = "Missing listening controls",
                            )
                        }
                    }
                    RtlSdrIpcContract.MSG_UPDATE_SETTINGS -> {
                        val settings = msg.data.readHostReceiverSettings(RtlSdrIpcContract.KEY_SETTINGS)
                        val expected = msg.data.readHostReceiverSettings(RtlSdrIpcContract.KEY_EXPECTED_SETTINGS)
                        if (expertSettingsConflict(
                                current = latestStatus,
                                expected = expected,
                                expectedProfileId = msg.data.getString(RtlSdrIpcContract.KEY_EXPECTED_PROFILE_ID),
                            )
                        ) {
                            controller.refreshState().copy(
                                status = "Receiver changed during editing. Reload active settings.",
                                receiverSettingsError = "Receiver changed during editing. Reload active settings.",
                            )
                        } else if (settings != null) {
                            controller.updateReceiverSettings(
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
                            )
                        } else {
                            controller.refreshState().copy(status = "Missing receiver settings")
                        }
                    }
                    RtlSdrIpcContract.MSG_SELECT_AM_PROFILE -> {
                        val profileId = msg.data?.getString(RtlSdrIpcContract.KEY_PROFILE_ID)
                        val applyDefaults = msg.data?.getBoolean(RtlSdrIpcContract.KEY_APPLY_PROFILE_DEFAULTS, false) == true
                        val preserveCorrection = msg.data.getBoolean(RtlSdrIpcContract.KEY_PRESERVE_FREQUENCY_CORRECTION, false)
                        controller.selectAmProfile(profileId, applyDefaults, preserveCorrection)
                    }
                    RtlSdrIpcContract.MSG_CLEAR_TRACE_LOG -> {
                        controller.clearTraceLog()
                        controller.refreshState()
                    }

                    else -> {
                        super.handleMessage(msg)
                        return
                    }
                }
            }.getOrElse { throwable ->
                Log.e(TAG, "IPC #$requestId $requestName failed", throwable)
                // A failing status read must not kill the controller handler
                // by immediately repeating the same failing operation.
                latestStatus = latestStatus.copy(
                    statusText = "IPC request failed in host: ${throwable.javaClass.simpleName}",
                )
                replyWithStatus(msg.replyTo, latestStatus, msg.arg1)
                return
            }
            latestStatus = state.toApiStatus()
            Log.w(TAG, "IPC #$requestId $requestName completed | after=${latestStatus.debugSnapshot()} status=${latestStatus.statusText}")
            replyWithStatus(msg.replyTo, latestStatus, msg.arg1)
        }
    }

    private fun replyWithStatus(target: Messenger?, status: AmHostStatus, requestId: Int) {
        if (target == null) return
        runCatching {
            val message = Message.obtain(null, RtlSdrIpcContract.MSG_STATUS)
            message.arg1 = requestId
            message.data = Bundle().apply {
                putHostStatus(status)
                putBoolean(RtlSdrIpcContract.KEY_LISTENING_CONTROLS_SUPPORTED, true)
                putBoolean(RtlSdrIpcContract.KEY_CALIBRATED_LISTENING_CONTROLS_SUPPORTED, true)
            }
            target.send(message)
        }
    }

    private fun DirectRtlBackendState.toApiStatus(): AmHostStatus {
        recording.observe(this)
        return AmHostStatus(
            supportedDevices = supportedDevices.map { device ->
                HostDeviceInfo(
                    deviceName = device.deviceName,
                    productName = device.productName,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    interfaceCount = device.interfaceCount,
                    hasPermission = device.hasPermission,
                )
            },
            availableAmProfiles = availableAmProfiles.map { profile -> profile.toApiProfileModel() },
            selectedDeviceName = selectedDeviceName,
            selectedDeviceLabel = selectedDeviceLabel,
            usbPermissionGranted = usbPermissionGranted,
            nativeBridgeReady = nativeBridgeReady,
            connected = connected,
            sessionConfigured = sessionConfigured,
            streaming = streaming,
            claimedInterfaceIndex = claimedInterfaceIndex,
            bulkInEndpointAddress = bulkInEndpointAddress,
            sampleRateHz = sampleRateHz,
            tunedFrequencyHz = tunedFrequencyHz,
            tunedCenterFrequencyHz = tunedCenterFrequencyHz,
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
            amSpeechFilterEnabled = amSpeechFilterEnabled,
            selectedProfileId = selectedAmProfileId,
            receiverSettingsError = receiverSettingsError,
            selectedProfileLabel = selectedAmProfileLabel,
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
            audioBufferSamples = audioBufferSamples,
            pcmProducedSamplesPerSecond = pcmProducedSamplesPerSecond,
            pcmConsumedSamplesPerSecond = pcmConsumedSamplesPerSecond,
            pcmDriftSamplesPerSecond = pcmDriftSamplesPerSecond,
            driftSnapshotCounter = driftSnapshotCounter,
            driftSnapshotText = driftSnapshotText,
            spectrumBins = spectrumBins,
            traceLogText = traceLogText,
            statusText = status,
        )
    }

    private fun AmHostStatus.debugSnapshot(): String {
        return "connected=$connected streaming=$streaming usb=$usbPermissionGranted devices=${supportedDevices.size} freqHz=${tunedFrequencyHz ?: "null"} sampleRateHz=${sampleRateHz ?: "null"}"
    }

    private fun Int.messageName(): String =
        when (this) {
            RtlSdrIpcContract.MSG_GET_STATUS -> "GET_STATUS"
            RtlSdrIpcContract.MSG_REQUEST_USB_PERMISSION -> "REQUEST_USB_PERMISSION"
            RtlSdrIpcContract.MSG_CONNECT -> "CONNECT"
            RtlSdrIpcContract.MSG_DISCONNECT -> "DISCONNECT"
            RtlSdrIpcContract.MSG_START_AUDIO -> "START_AUDIO"
            RtlSdrIpcContract.MSG_STOP_AUDIO -> "STOP_AUDIO"
            RtlSdrIpcContract.MSG_TUNE -> "TUNE"
            RtlSdrIpcContract.MSG_UPDATE_SETTINGS -> "UPDATE_SETTINGS"
            RtlSdrIpcContract.MSG_SELECT_AM_PROFILE -> "SELECT_AM_PROFILE"
            RtlSdrIpcContract.MSG_CLEAR_TRACE_LOG -> "CLEAR_TRACE_LOG"
            RtlSdrIpcContract.MSG_UPDATE_LISTENING_CONTROLS -> "UPDATE_LISTENING_CONTROLS"
            RtlSdrIpcContract.MSG_UPDATE_CALIBRATED_LISTENING_CONTROLS -> "UPDATE_CALIBRATED_LISTENING_CONTROLS"
            RtlSdrIpcContract.MSG_STATUS -> "STATUS"
            else -> "UNKNOWN($this)"
        }

    private fun DirectRtlAmProfileOption.toApiProfileModel(): HostAmProfileOption =
        HostAmProfileOption(
            id = id,
            label = label,
            description = description,
        )

    companion object {
        private const val TAG = "OpenSdrAmHostSvc"
        private val REQUEST_COUNTER = AtomicInteger(0)
        private const val ACTION_USB_PERMISSION = "org.opensdr.amhost.action.USB_PERMISSION"
        const val ACTION_STOP_AUDIO_FROM_NOTIFICATION = "org.opensdr.amhost.action.STOP_AUDIO_FROM_NOTIFICATION"
        private const val ACTION_KEEP_PLAYBACK_ALIVE = "org.opensdr.amhost.action.KEEP_PLAYBACK_ALIVE"
    }
}
