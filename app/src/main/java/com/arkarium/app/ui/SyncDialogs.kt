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

// Shown from the home screen's "add fiction" icon (see docs/arkarium/SYNC_MVP.md §5/Stage 3,
// and the later change to single-origin-by-name lookup). The name field stays visible
// and editable through every state (including Error, so the user can just fix a typo
// and retry without reopening the dialog) - only isLoading gates whether it and the
// buttons are interactive. Mirrors MetadataSearchDialog's state-driven-but-single-
// composable shape in MetadataMatchDialog.kt.
//
// Note there's no URL field anymore - ARKarium only ever syncs from its own relay now
// (see FictionLut.kt / SyncManager.RELAY_HOST). What the caller passes to onConfirm is
// the raw typed name; resolving it to a slug (and handling a no-match) happens in
// MainActivity, not here - this dialog doesn't know anything about the lookup table.
@Composable
fun AddFictionByNameDialog(
    isLoading: Boolean,
    progressMessage: String,
    errorMessage: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Add fiction") },
        text = {
            Column {
                Text(
                    "Type a fiction's name and ARKarium will pull it from the relay " +
                        "and keep it up to date.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Fiction name") },
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
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = !isLoading && name.isNotBlank()) {
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
// AddFictionByNameDialog there's no input here - it's pure progress/result reporting for
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

// Shown when a "Check for updates" pass hits something that shouldn't be resolved
// silently in either direction (see docs/arkarium/NEXT_FIXES.md #2 and
// MainActivity.SyncResolutionState): either this novel's local folder is gone, or its
// relay no longer serves it. Which buttons are offered depends on `isMissingLocally` -
// "Sync again" / "Remove from library" for a missing folder (there's still a live
// source to re-fetch from, or the user can confirm the removal that used to happen to
// them automatically), just "Unlink" for a gone source (nothing left to sync against,
// but local content keeps working either way).
@Composable
fun SyncResolutionDialog(
    novelTitle: String,
    isMissingLocally: Boolean,
    onSyncAgain: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onUnlink: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isMissingLocally) "\"$novelTitle\" is missing" else "\"$novelTitle\" is no longer available") },
        text = {
            Text(
                if (isMissingLocally) {
                    "This fiction's folder was removed from your library. You can sync " +
                        "it again to re-download it, or remove it from ARKarium entirely."
                } else {
                    "This fiction is no longer being served by the relay it was synced " +
                        "from - the source may have been taken down. What's already " +
                        "downloaded will keep working, but you won't get further " +
                        "updates unless you unlink it and add it again later."
                },
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            if (isMissingLocally) {
                TextButton(onClick = onSyncAgain) { Text("Sync again") }
            } else {
                TextButton(onClick = onUnlink) { Text("Unlink") }
            }
        },
        dismissButton = {
            if (isMissingLocally) {
                TextButton(onClick = onRemoveFromLibrary) { Text("Remove from library") }
            } else {
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    )
}
