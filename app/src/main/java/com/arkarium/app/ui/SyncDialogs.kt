package com.arkarium.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Shown from Settings' "Add fiction from URL" action (see docs/SYNC_MVP.md §5/Stage 3).
// The URL field stays visible and editable through every state (including Error, so the
// user can just fix a typo and retry without reopening the dialog) - only isLoading gates
// whether it and the buttons are interactive. Mirrors MetadataSearchDialog's
// state-driven-but-single-composable shape in MetadataMatchDialog.kt.
@Composable
fun AddFictionFromUrlDialog(
    isLoading: Boolean,
    progressMessage: String,
    errorMessage: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Add fiction from URL") },
        text = {
            Column {
                Text(
                    "Paste the base URL of a relay hosting a fiction's manifest.json. " +
                        "ARKarium will download its files into your library.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Relay URL") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            progressMessage,
                            modifier = Modifier.padding(start = 12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url.trim()) }, enabled = !isLoading && url.isNotBlank()) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Cancel") }
        }
    )
}

// Shown from NovelDetailScreen's "Check for updates" action, only ever offered once a
// novel already has a syncSourceUrl (see NovelDetailScreen.kt). Unlike
// AddFictionFromUrlDialog there's no input here - it's pure progress/result reporting for
// a sync pass already in flight or finished.
@Composable
fun SyncProgressDialog(
    novelTitle: String,
    isLoading: Boolean,
    message: String,
    errorMessage: String?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Syncing \"$novelTitle\"") },
        text = {
            when {
                errorMessage != null -> Text(errorMessage, style = MaterialTheme.typography.bodyMedium)
                isLoading -> Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Text(
                        message,
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                else -> Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("OK") }
        }
    )
}
