package org.opensdr.amhost

import org.opensdr.sdrcore.receiverRfBandwidthHz

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.opensdr.radioapi.AmHostStatus
import org.opensdr.radioapi.HostReceiverSettings
import org.opensdr.radioapi.putHostReceiverSettings
import org.opensdr.radioapi.readHostStatus
import org.opensdr.radioapi.RtlSdrIpcContract
import org.opensdr.sdrcore.RTL_SQUELCH_MODE_MANUAL
import kotlin.math.roundToInt

/**
 * Local control surface for OpenSDR Radio Host.
 *
 * This standalone UI uses the exported host service through the public IPC
 * surface, without importing client-specific workflows or duplicating client
 * business logic.
 */
@Composable
internal fun HostAppScreen(appContext: Context, showDiagnosticsInitially: Boolean = false) {
    val hostPreferences = remember(appContext) {
        appContext.getSharedPreferences(HOST_PREFS_NAME, Context.MODE_PRIVATE)
    }
    val initialModulationOption = remember(hostPreferences) {
        hostPreferences.loadHostModulationOptionOrNull() ?: modulationOptions.first()
    }
    val initialManualSquelchLevel = remember(hostPreferences) {
        hostPreferences.loadHostManualSquelchLevel()
    }
    var showWalkthrough by remember {
        mutableStateOf(!showDiagnosticsInitially && !hostPreferences.getBoolean(PREF_WALKTHROUGH_SEEN, false))
    }
    var walkthroughStepIndex by remember { mutableIntStateOf(0) }
    var status by remember {
        mutableStateOf(
            AmHostStatus(
                statusText = "Binding to host service",
                demodMode = initialModulationOption.demodMode,
                sidebandMode = initialModulationOption.sidebandMode,
                squelchMode = RTL_SQUELCH_MODE_MANUAL,
                squelchLevel = initialManualSquelchLevel.roundToInt(),
                squelchThresholdPercent = initialManualSquelchLevel.roundToInt(),
            ),
        )
    }
    var receiverControls by remember {
        mutableStateOf(
            HostReceiverControls(
                frequencyText = hostPreferences.loadHostFrequencyText(),
                demodMode = initialModulationOption.demodMode,
                sidebandMode = initialModulationOption.sidebandMode,
                squelchLevel = initialManualSquelchLevel,
            ),
        )
    }
    var frequencyHistory by remember { mutableStateOf(hostPreferences.loadHostFrequencyHistory()) }
    var selectedTuningStep by remember {
        mutableStateOf(hostPreferences.loadHostTuningStep())
    }
    var connected by remember { mutableStateOf(false) }
    var receivedHostStatus by remember { mutableStateOf(false) }
    var serviceMessenger by remember { mutableStateOf<Messenger?>(null) }
    var powerGoal by remember { mutableStateOf<PowerGoal?>(null) }
    var lastIssuedCommandKey by remember { mutableStateOf<String?>(null) }
    var powerProgressText by remember { mutableStateOf("Waiting for RTL-SDR receiver service") }
    val waterfallFrames = rememberHostSpectrumFrames(serviceMessenger, status.streaming)
    var waterfallDisplaySettings by remember {
        mutableStateOf(hostPreferences.loadHostWaterfallDisplaySettings())
    }
    val frequencyText = receiverControls.frequencyText
    val selectedModulation = receiverControls.modulation
    val manualSquelchLevel = receiverControls.squelchLevel

    val latestStatusSetter by rememberUpdatedState<(AmHostStatus) -> Unit> { updated ->
        receiverControls = synchronizeHostReceiverControls(
            controls = receiverControls,
            previous = status.takeIf { receivedHostStatus },
            current = updated,
            startingLocally = powerGoal == PowerGoal.TURN_ON,
        )
        status = updated
        receivedHostStatus = true
    }
    val latestStreamingState by rememberUpdatedState(status.streaming)
    val statusPollGate = remember { HostStatusPollGate() }

    val replyMessenger = remember {
        Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what != RtlSdrIpcContract.MSG_STATUS) return
                if (msg.arg1 != 0 && !statusPollGate.complete(msg.arg1)) return
                runCatching {
                    val updated = msg.data.readHostStatus() ?: return
                    latestStatusSetter(updated)
                }
            }
        })
    }

    fun requestStatus() {
        if (serviceMessenger == null) return
        val requestId = statusPollGate.begin() ?: return
        if (!sendCommand(serviceMessenger, replyMessenger, RtlSdrIpcContract.MSG_GET_STATUS, requestId = requestId)) {
            statusPollGate.complete(requestId)
        }
    }

    DisposableEffect(appContext) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val target = binder?.let(::Messenger)
                serviceMessenger = target
                statusPollGate.reset()
                connected = true
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connected = false
                serviceMessenger = null
                statusPollGate.reset()
                receivedHostStatus = false
                powerGoal = null
                lastIssuedCommandKey = null
                status = status.copy(connected = false, streaming = false, statusText = "Host service disconnected")
                powerProgressText = "Host service disconnected"
            }
        }
        val intent = Intent(RtlSdrIpcContract.SERVICE_ACTION).apply {
            setClass(appContext, AmHostService::class.java)
        }
        appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose {
            statusPollGate.reset()
            runCatching { appContext.unbindService(connection) }
        }
    }

    LaunchedEffect(connected, serviceMessenger) {
        if (!connected || serviceMessenger == null) return@LaunchedEffect
        while (isActive) {
            requestStatus()
            delay(if (latestStreamingState) HOST_STATUS_POLL_STREAMING_MS else HOST_STATUS_POLL_IDLE_MS)
        }
    }

    fun sendReceiverSettings(
        settings: HostReceiverSettings?,
        expected: HostReceiverSettings? = null,
        expectedProfileId: String? = null,
    ) {
        if (!connected || !receivedHostStatus || settings == null) return
        sendCommand(
            serviceMessenger,
            replyMessenger,
            RtlSdrIpcContract.MSG_UPDATE_SETTINGS,
            Bundle().apply {
                putHostReceiverSettings(RtlSdrIpcContract.KEY_SETTINGS, settings)
                expected?.let {
                    putHostReceiverSettings(RtlSdrIpcContract.KEY_EXPECTED_SETTINGS, it)
                    putString(RtlSdrIpcContract.KEY_EXPECTED_PROFILE_ID, expectedProfileId)
                }
            },
        )
    }

    LaunchedEffect(powerGoal, status, serviceMessenger, frequencyText, selectedModulation, manualSquelchLevel) {
        val goal = powerGoal ?: return@LaunchedEffect
        val messenger = serviceMessenger ?: return@LaunchedEffect
        val targetHz = parseFrequencyMhzToHz(frequencyText)
        val plan = buildPowerPlan(status, goal, targetHz)
        powerProgressText = plan.message
        if (plan.completed) {
            if (goal == PowerGoal.TURN_ON) {
                receiverControls = synchronizeHostReceiverControls(receiverControls, null, status, startingLocally = false)
            }
            powerGoal = null
            lastIssuedCommandKey = null
            return@LaunchedEffect
        }
        val command = plan.command ?: return@LaunchedEffect
        val commandKey = if (command == RtlSdrIpcContract.MSG_TUNE) {
            "$command:${plan.commandData?.getLong(RtlSdrIpcContract.KEY_FREQUENCY_HZ)}"
        } else {
            command.toString()
        }
        if (commandKey == lastIssuedCommandKey) return@LaunchedEffect
        lastIssuedCommandKey = commandKey
        sendCommand(messenger, replyMessenger, command, plan.commandData)
    }

    val powerButtonEnabled = connected && receivedHostStatus && (status.supportedDevices.isNotEmpty() || status.connected || status.streaming || powerGoal != null)
    val parsedFrequencyHz = parseFrequencyMhzToHz(frequencyText)
    val tuneButtonEnabled = connected && status.connected && parsedFrequencyHz > 0L
    val stepButtonEnabled = parsedFrequencyHz > 0L || (status.tunedFrequencyHz ?: 0L) > 0L
    val powerButtonLabel = when {
        powerGoal == PowerGoal.TURN_ON -> "Starting..."
        powerGoal == PowerGoal.TURN_OFF -> "Stopping..."
        status.streaming || status.connected -> "Power Off"
        status.supportedDevices.isNotEmpty() -> "Power On"
        else -> "Waiting"
    }

    fun dismissWalkthrough() {
        hostPreferences.edit().putBoolean(PREF_WALKTHROUGH_SEEN, true).apply()
        showWalkthrough = false
    }

    fun currentFrequencyHz(): Long {
        val typedFrequencyHz = parseFrequencyMhzToHz(receiverControls.frequencyText)
        return if (typedFrequencyHz > 0L) typedFrequencyHz else status.tunedFrequencyHz ?: 0L
    }

    fun retuneTo(frequencyHz: Long, addToHistory: Boolean = false) {
        val targetHz = frequencyHz.coerceAtLeast(0L)
        if (targetHz <= 0L) return
        receiverControls = receiverControls.copy(frequencyText = formatFrequencyHzAsMhzText(targetHz))
        hostPreferences.persistHostFrequencyHz(targetHz)
        if (addToHistory) {
            frequencyHistory = hostPreferences.recordHostFrequencyHistory(
                frequencyHz = targetHz,
                modulation = receiverControls.modulation,
                squelchLevel = receiverControls.squelchLevel.roundToInt(),
            )
        }
        lastIssuedCommandKey = null
        if (connected && status.connected) {
            sendReceiverSettings(status.settingsForHostTune(receiverControls))
            sendCommand(
                serviceMessenger,
                replyMessenger,
                RtlSdrIpcContract.MSG_TUNE,
                tuneCommandData(targetHz),
            )
        }
    }

    fun retuneByStep(direction: Int) {
        val currentHz = currentFrequencyHz()
        if (currentHz <= 0L) return
        retuneTo(currentHz + selectedTuningStep.stepHz * direction.toLong())
    }

    LazyColumn(
        state = androidx.compose.foundation.lazy.rememberLazyListState(
            initialFirstVisibleItemIndex = if (showDiagnosticsInitially) 4 else 0,
        ),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FrequencyControls(
                        frequencyText = frequencyText,
                        frequencyHistory = frequencyHistory,
                        stepEnabled = stepButtonEnabled,
                        onFrequencyTextChange = {
                            val filtered = it.filter { ch -> ch.isDigit() || ch == '.' }
                            receiverControls = receiverControls.copy(frequencyText = filtered)
                            hostPreferences.persistHostFrequencyTextIfValid(filtered)
                            lastIssuedCommandKey = null
                        },
                        onHistorySelected = { entry ->
                            receiverControls = receiverControls.withHistory(entry)
                            hostPreferences.persistHostFrequencyHz(entry.frequencyHz)
                            hostPreferences.persistHostModulationOption(entry.modulation)
                            hostPreferences.persistHostManualSquelchLevel(entry.squelchLevel.toFloat())
                            lastIssuedCommandKey = null
                        },
                        onStepDown = { retuneByStep(direction = -1) },
                        onStepUp = { retuneByStep(direction = 1) },
                    )
                    CompactManualSquelchControl(
                        squelchLevel = manualSquelchLevel,
                        currentMode = status.squelchMode,
                        currentThresholdPercent = status.squelchThresholdPercent,
                        enabled = connected && status.connected,
                        onSquelchLevelChange = {
                            receiverControls = receiverControls.withManualSquelch(it)
                            hostPreferences.persistHostManualSquelchLevel(receiverControls.squelchLevel)
                            sendReceiverSettings(status.toManualSquelchSettings(receiverControls.squelchLevel))
                        },
                    )
                    ReceiverActionRow(
                        powerButtonLabel = powerButtonLabel,
                        powerButtonEnabled = powerButtonEnabled,
                        tuneEnabled = tuneButtonEnabled,
                        onPowerClick = {
                            val goal = if (status.streaming || status.connected) PowerGoal.TURN_OFF else PowerGoal.TURN_ON
                            if (goal == PowerGoal.TURN_ON) {
                                // Apply standalone preferences only on an explicit
                                // local start, before Connect's provisional tune.
                                sendReceiverSettings(status.settingsForHostTune(receiverControls))
                            }
                            powerGoal = goal
                            lastIssuedCommandKey = null
                        },
                        onTuneClick = { retuneTo(parseFrequencyMhzToHz(receiverControls.frequencyText), addToHistory = true) },
                        onRefreshClick = {
                            powerGoal = null
                            lastIssuedCommandKey = null
                            receiverControls = synchronizeHostReceiverControls(receiverControls, null, status, startingLocally = false)
                            requestStatus()
                        },
                    )
                    Text(if (powerGoal == null) status.statusText else powerProgressText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            WaterfallCard(
                waterfallFrames = waterfallFrames,
                centerFrequencyHz = hostSpectrumCenterFrequencyHz(status),
                sampleRateHz = status.sampleRateHz,
                channelBandwidthHz = receiverRfBandwidthHz(status.demodMode, status.amBandwidthHz),
                displaySettings = waterfallDisplaySettings,
                onDisplaySettingsChange = { updatedSettings ->
                    waterfallDisplaySettings = updatedSettings
                    hostPreferences.persistHostWaterfallDisplaySettings(updatedSettings)
                },
                showDisplayControls = false,
            )
        }

        item {
            RadioSettingsCard(
                selectedModulation = selectedModulation,
                selectedStep = selectedTuningStep,
                stepEnabled = stepButtonEnabled,
                settingsEnabled = connected && receivedHostStatus,
                waterfallDisplaySettings = waterfallDisplaySettings,
                onModulationSelected = { option ->
                    receiverControls = receiverControls.withModulation(option)
                    hostPreferences.persistHostModulationOption(option)
                    sendReceiverSettings(status.toModulationSettings(option))
                },
                onStepSelected = { step ->
                    selectedTuningStep = step
                    hostPreferences.persistHostTuningStep(step)
                },
                onWaterfallDisplaySettingsChange = { updatedSettings ->
                    waterfallDisplaySettings = updatedSettings
                    hostPreferences.persistHostWaterfallDisplaySettings(updatedSettings)
                },
            )
        }

        item {
            ExpertSettingsCard(
                status = status,
                enabled = connected && receivedHostStatus && powerGoal == null,
                onSelectProfile = { profileId ->
                    sendCommand(serviceMessenger, replyMessenger, RtlSdrIpcContract.MSG_SELECT_AM_PROFILE,
                        Bundle().apply {
                            putString(RtlSdrIpcContract.KEY_PROFILE_ID, profileId)
                            putBoolean(RtlSdrIpcContract.KEY_APPLY_PROFILE_DEFAULTS, true)
                        })
                },
                onApplySettings = { settings, baseline, profileId -> sendReceiverSettings(settings, baseline, profileId) },
            )
        }

        item {
            DiagnosticsCard(
                status = status,
                serviceBound = connected,
                progressText = if (powerGoal == null) status.statusText else powerProgressText,
                initiallyExpanded = showDiagnosticsInitially,
                onShare = { appContext.shareHostReport(hostDiagnosticReport(status)) },
                onClear = { sendCommand(serviceMessenger, replyMessenger, RtlSdrIpcContract.MSG_CLEAR_TRACE_LOG) },
            )
        }

        item { RecordingCard(serviceMessenger, status.streaming) }
    }

    if (showWalkthrough) {
        HostWalkthroughDialog(
            status = status,
            serviceBound = connected,
            frequencyText = frequencyText,
            selectedModulation = selectedModulation,
            currentStepIndex = walkthroughStepIndex,
            onStepIndexChange = { walkthroughStepIndex = it },
            onDismiss = { dismissWalkthrough() },
        )
    }
}
