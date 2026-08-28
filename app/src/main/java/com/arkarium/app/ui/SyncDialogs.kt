package com.arkarium.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage

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
// novel already has a syncSourceUrl (see NovelDetailScreen.kt), and reused for "Sync
// all Rae ARK's novels" from EmptyLibraryPrompt. Unlike AddFictionByNameDialog there's
// no input here - it's pure progress/result reporting for a sync pass already in
// flight or finished.
//
// Kept deliberately compact - a small centered card, not a full-width panel - so it
// reads as a lightweight status popup rather than a second screen. The cover is a
// small 2:3 thumbnail (matching the novel detail header's full-size cover proportions,
// just at a much smaller footprint) with the spinner centered over it on a scrim while
// isLoading, and the one line of status text underneath names only the arc folder
// currently being synced - see SyncManager.arcLabelForPath, which is what actually
// produces `message` now. There's deliberately no "file 12/40" count anywhere in this
// composable: once a sync is chapter-by-chapter inside one arc, nothing here changes
// until the next arc starts, which is the point - the reader sees "which arc", not
// "how many files are left to churn through."
//
// The confirm action is a filled Button (not TextButton) so it's unambiguous which
// tap ends the dialog - deliberately more prominent than AlertDialog's default
// low-emphasis text buttons elsewhere in this file, since this dialog can be dismissed
// as soon as a result is in and shouldn't require hunting for the right tap target.
// `coverUrl` is optional - a batch sync (SyncAllState, which isn't scoped to one
// novel) has no single cover to show, so that call site passes null and gets the same
// placeholder NovelCardVertical/NovelContinueCard already fall back to elsewhere in
// the app.
@Composable
fun SyncProgressDialog(
    novelTitle: String,
    coverUrl: String? = null,
    isLoading: Boolean,
    message: String,
    errorMessage: String?,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = { if (!isLoading) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(min = 240.dp, max = 300.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Small 2:3 cover thumbnail instead of a full-bleed panel - keeps the
                // dialog's overall footprint small while still showing the cover the
                // reader actually recognizes rather than a bare spinner.
                Box(
                    modifier = Modifier
                        .width(88.dp)
                        .height(132.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (coverUrl != null) {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text("📚", style = MaterialTheme.typography.displayMedium)
                    }
                    if (isLoading) {
                        // Scrim behind the spinner so it stays legible over a bright
                        // cover, without permanently darkening the cover once syncing
                        // finishes (the scrim only composes while isLoading is true).
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    novelTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Exactly one line of status: the arc name (isLoading), the final
                // result message, or the error - never a file path or a count.
                when {
                    errorMessage != null -> Text(
                        errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    else -> Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Filled, full-width primary button - the single obvious tap target,
                // instead of a low-emphasis TextButton easy to miss.
                Button(
                    onClick = onDismiss,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("OK")
                }
            }
        }
    }
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
