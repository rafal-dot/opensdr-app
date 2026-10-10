package org.opensdr.amhost

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

@Composable
internal fun RecordingFolderClearDialog(folder: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var finalConfirmation by remember(folder) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (finalConfirmation) "Final confirmation" else "Clear recording folder?",
                color = MaterialTheme.colorScheme.error)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(Uri.parse(folder).lastPathSegment ?: folder)
                Text(if (finalConfirmation)
                    "Permanently delete ALL files and subfolders here, including anything unrelated to recordings? This cannot be undone."
                else
                    "This will delete all IQ, WAV, metadata and other files in this folder, including every subfolder. The selected folder itself will be kept.")
            }
        },
        confirmButton = {
            Button(
                onClick = { if (finalConfirmation) onConfirm() else finalConfirmation = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError),
            ) { Text(if (finalConfirmation) "Delete everything" else "Continue") }
        },
        dismissButton = {
            TextButton(onClick = { if (finalConfirmation) finalConfirmation = false else onDismiss() }) {
                Text(if (finalConfirmation) "Back" else "Cancel")
            }
        },
    )
}
