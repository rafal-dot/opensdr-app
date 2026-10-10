package org.opensdr.amhost

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import org.opensdr.radioapi.AmHostStatus
import java.util.Locale

@Composable
internal fun HostWalkthroughDialog(
    status: AmHostStatus,
    serviceBound: Boolean,
    frequencyText: String,
    selectedModulation: ModulationOption,
    currentStepIndex: Int,
    onStepIndexChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val steps = remember(status, serviceBound, frequencyText, selectedModulation) {
        buildWalkthroughSteps(
            status = status,
            serviceBound = serviceBound,
            frequencyText = frequencyText,
            selectedModulation = selectedModulation,
        )
    }
    val safeStepIndex = currentStepIndex.coerceIn(0, steps.lastIndex)
    val step = steps[safeStepIndex]
    val isLastStep = safeStepIndex == steps.lastIndex

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${safeStepIndex + 1}/${steps.size} ${step.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(step.body)
                Text("Current state: ${step.liveStatus}")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isLastStep) {
                        onDismiss()
                    } else {
                        onStepIndexChange(safeStepIndex + 1)
                    }
                },
            ) {
                Text(if (isLastStep) "Start using app" else "Next")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (safeStepIndex > 0) {
                    TextButton(onClick = { onStepIndexChange(safeStepIndex - 1) }) {
                        Text("Back")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Skip")
                }
            }
        },
    )
}

private fun buildWalkthroughSteps(
    status: AmHostStatus,
    serviceBound: Boolean,
    frequencyText: String,
    selectedModulation: ModulationOption,
): List<WalkthroughStep> {
    return listOf(
        WalkthroughStep(
            title = "Overview",
            body = "OpenSDR Radio Host turns a compatible USB RTL-SDR into a standalone receiver. Tune, listen and explore the spectrum here, or connect a compatible client app to the local receiver service.",
            liveStatus = if (serviceBound) "Host service is bound." else "Waiting for the host service to bind.",
        ),
        WalkthroughStep(
            title = "Connect",
            body = "Attach an RTL-SDR dongle and grant USB permission when Android asks. The Power button then opens the device, tunes it and starts audio.",
            liveStatus = deviceWalkthroughStatus(status),
        ),
        WalkthroughStep(
            title = "Tune",
            body = "Set Frequency and choose Modulation in Radio settings. Supported modes include AM, AM Sync, LSB, USB, NFM and WFM.",
            liveStatus = "Target $frequencyText MHz, ${selectedModulation.label}.",
        ),
        WalkthroughStep(
            title = "Monitor",
            body = "Use Status, Manual squelch and Waterfall to verify the receiver path. Check local laws before receiving, recording or sharing any radio communications or signals. Rules depend on your location, the radio service and your authorization.",
            liveStatus = receiverWalkthroughStatus(status),
        ),
    )
}

private fun deviceWalkthroughStatus(status: AmHostStatus): String {
    return when {
        status.streaming -> "Audio is streaming."
        status.connected -> "RTL-SDR session is ready."
        status.supportedDevices.isEmpty() -> "No RTL-SDR device detected yet."
        status.usbPermissionGranted -> "USB device detected and permission is granted."
        else -> "USB device detected; permission is still needed."
    }
}

private fun receiverWalkthroughStatus(status: AmHostStatus): String {
    val tuneText = status.tunedFrequencyHz?.let { hz ->
        String.format(Locale.US, "%.3f MHz", hz / 1_000_000.0)
    } ?: "not tuned"
    return when {
        status.streaming -> "Streaming on $tuneText, RF ${status.rfLevelPercent}%, audio ${status.audioLevelPercent}%."
        status.connected -> "Connected, $tuneText, waiting for audio start."
        else -> "Receiver is idle."
    }
}
