package com.arkster.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkster.app.data.NovelMetadataCandidate

// Shown after the user taps "Fetch info" on a novel. Deliberately requires an explicit
// tap to confirm a match rather than auto-applying the top result - title-only search
// against an external source will occasionally surface the wrong book/cover, and
// silently overwriting a user's folder with wrong info is worse than making them pick.
@Composable
fun MetadataSearchDialog(
    novelTitle: String,
    isLoading: Boolean,
    errorMessage: String?,
    candidates: List<NovelMetadataCandidate>,
    onCandidateSelected: (NovelMetadataCandidate) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fetch info for \"$novelTitle\"") },
        text = {
            when {
                isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "Searching...",
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                errorMessage != null -> {
                    Text(errorMessage, style = MaterialTheme.typography.bodyMedium)
                }
                candidates.isEmpty() -> {
                    Text(
                        "No matches found. This source only covers published books, so " +
                            "web-novel/fan-translation titles often won't turn up here.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                else -> {
                    Column {
                        Text(
                            "Pick the closest match. This overwrites this novel's saved info.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LazyColumn(modifier = Modifier.height(320.dp)) {
                            items(candidates) { candidate ->
                                MetadataCandidateRow(
                                    candidate = candidate,
                                    onClick = { onCandidateSelected(candidate) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun MetadataCandidateRow(
    candidate: NovelMetadataCandidate,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        AsyncImage(
            model = candidate.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier
                .width(48.dp)
                .height(64.dp)
                .clip(RoundedCornerShape(4.dp))
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                candidate.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (candidate.authors.isNotEmpty()) {
                Text(
                    candidate.authors.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!candidate.publishedDate.isNullOrBlank()) {
                Text(
                    candidate.publishedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
