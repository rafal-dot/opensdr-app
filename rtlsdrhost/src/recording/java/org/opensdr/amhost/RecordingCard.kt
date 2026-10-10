package org.opensdr.amhost

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

@Composable
internal fun RecordingCard(service: Messenger?, streaming: Boolean) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("diagnostic_recording", Context.MODE_PRIVATE) }
    var folder by remember { mutableStateOf(preferences.getString("tree", null)) }
    var busy by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var storageBusy by remember { mutableStateOf(false) }
    var statusReady by remember(service) { mutableStateOf(false) }
    var pendingAction by remember(service) { mutableStateOf<String?>(null) }
    var confirmFolder by remember(service) { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("Choose a local phone folder for IQ + WAV files.") }
    var error by remember { mutableStateOf<String?>(null) }
    var seconds by remember { mutableStateOf(0L) }
    var bytes by remember { mutableStateOf(0L) }
    val reply = remember(service) {
        Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what != RECORDING_STATUS) return
                busy = msg.data.getBoolean("busy")
                clearing = msg.data.getBoolean("clearing")
                storageBusy = msg.data.getBoolean("storageBusy")
                statusReady = true
                if (msg.data.getString("action") == pendingAction) pendingAction = null
                message = msg.data.getString("message").orEmpty()
                seconds = msg.data.getLong("seconds")
                bytes = msg.data.getLong("bytes")
            }
        })
    }
    fun command(action: String, targetFolder: String? = folder) {
        runCatching {
            val target = checkNotNull(service) { "Host service is not connected" }
            if (action in listOf("start", "stop", "clear")) pendingAction = action
            target.send(Message.obtain(null, RECORDING_COMMAND).apply {
                replyTo = reply
                data = Bundle().apply { putString("action", action); putString("tree", targetFolder) }
            })
        }.onFailure {
            if (pendingAction == action) pendingAction = null
            statusReady = false
            error = "Host service unavailable: ${it.message}"
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                require(uri.authority == "com.android.externalstorage.documents") { "Choose a local folder, not cloud storage." }
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                folder = uri.toString()
                preferences.edit().putString("tree", folder).apply()
                error = null
            }.onFailure { error = "Cannot use this folder: ${it.message}" }
        }
    }
    LaunchedEffect(service) {
        if (service == null) return@LaunchedEffect
        while (isActive) {
            command("status")
            delay(500)
        }
    }
    LaunchedEffect(storageBusy, service) {
        if (storageBusy || service == null) confirmFolder = null
    }
    val idle = service != null && statusReady && !busy && !clearing && !storageBusy && pendingAction == null
    confirmFolder?.let { selectedFolder ->
        RecordingFolderClearDialog(
            folder = selectedFolder,
            onDismiss = { confirmFolder = null },
            onConfirm = {
                confirmFolder = null
                if (idle) {
                    error = null
                    command("clear", selectedFolder)
                }
            },
        )
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Diagnostic recording", style = MaterialTheme.typography.titleMedium)
            Text("Internal APK only. Raw IQ + playback WAV, split every 10 minutes. No microphone.", style = MaterialTheme.typography.bodySmall)
            Text(folder?.let { Uri.parse(it).lastPathSegment } ?: "No folder selected", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { picker.launch(folder?.let(Uri::parse)) }, enabled = idle && confirmFolder == null) { Text("Choose folder") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { error = null; command(if (busy) "stop" else "start") },
                    enabled = service != null && statusReady && pendingAction == null && !clearing &&
                        (busy || (idle && streaming && folder != null)),
                ) { Text(if (busy) "Stop recording" else "Start recording") }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { command("mark") }, enabled = busy && service != null && statusReady && pendingAction == null) { Text("Mark problem") }
            }
            OutlinedButton(
                onClick = { confirmFolder = folder },
                enabled = idle && folder != null,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(if (clearing) "Clearing folder..." else "Clear recording folder")
            }
            Text("%d:%02d | %.1f MB".format(Locale.ROOT, seconds / 60, seconds % 60, bytes / 1_000_000.0))
            Text(error ?: message, style = MaterialTheme.typography.bodySmall)
            Text("Recording follows the active receiver without changing its settings. Check local rules before recording radio communications.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
