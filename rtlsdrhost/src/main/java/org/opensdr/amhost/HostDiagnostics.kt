package org.opensdr.amhost

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.opensdr.radioapi.AmHostStatus
import kotlinx.coroutines.delay

internal fun hostDiagnosticReport(status: AmHostStatus): String = buildString {
    appendLine("OpenSDR Radio Host")
    appendLine(status.statusText)
    appendLine("Connected: ${status.connected}; streaming: ${status.streaming}; USB permission: ${status.usbPermissionGranted}")
    appendLine("Frequency: ${status.tunedFrequencyHz} Hz; center: ${status.tunedCenterFrequencyHz} Hz")
    appendLine("Sample rate: ${status.sampleRateHz} Hz; carrier offset: ${status.estimatedCarrierOffsetHz} Hz")
    appendLine("Profile: ${status.selectedProfileLabel} (${status.selectedProfileId})")
    appendLine("Settings: ${status.toReceiverSettings()}")
    appendLine("Settings error: ${status.receiverSettingsError ?: "none"}")
    appendLine("RF: ${status.rfLevelPercent}%; AF: ${status.audioLevelPercent}%; clipping: ${status.clipPercent}%; IQ clip: ${status.iqClipPercent}%")
    appendLine("Squelch: ${status.squelchPercent}/${status.squelchThresholdPercent}%; open: ${status.squelchOpen}")
    appendLine("PCM: ${status.pcmProducedSamplesPerSecond}/s -> ${status.pcmConsumedSamplesPerSecond}/s; FIFO: ${status.audioBufferSamples}")
    status.supportedDevices.forEach { appendLine("Device: ${it.productName} (${it.deviceName}); permission: ${it.hasPermission}") }
    appendLine(status.driftSnapshotText)
    appendLine("Receiver trace / USB / PCM cadence:")
    append(status.traceLogText)
}

internal fun Context.shareHostReport(text: String) {
    val intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "OpenSDR diagnostic report")
            .putExtra(Intent.EXTRA_TEXT, text),
        "Share radio diagnostics",
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { startActivity(intent) }.isFailure) {
        Toast.makeText(this, "No app available to share this report", Toast.LENGTH_LONG).show()
    }
}

@Composable
internal fun DiagnosticsCard(
    status: AmHostStatus,
    serviceBound: Boolean,
    progressText: String,
    initiallyExpanded: Boolean,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show") }
            }
            Text(progressText, style = MaterialTheme.typography.bodySmall)
            if (expanded) {
                Text("Service bound: $serviceBound")
                Text("Reports include tuned frequencies and device details. Review before sharing.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onShare) { Text("Share report") }
                    TextButton(enabled = serviceBound, onClick = onClear) { Text("Clear trace") }
                }
                SelectionContainer {
                    DiagnosticReportView(status)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticReportView(status: AmHostStatus) {
    // Detailed text does not need the spectrum's refresh rate. No timer while collapsed.
    val latest by rememberUpdatedState(status)
    val initialReport = remember { hostDiagnosticReport(status) }
    val report by produceState(initialValue = initialReport) {
        while (true) {
            delay(1_000)
            value = hostDiagnosticReport(latest)
        }
    }
    Text(report, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
}
