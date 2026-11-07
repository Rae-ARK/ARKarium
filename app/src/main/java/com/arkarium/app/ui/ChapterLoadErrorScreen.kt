package com.arkarium.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// Shown in place of ReaderScreen when a chapter's file can't be read at all (see
// ChapterLoadResult.Failed in data/ChapterContentRepository.kt). Replaces the old
// behavior of passing an error string to ReaderScreen as if it were the chapter's
// text, which meant read-aloud would read the error out loud and reading progress
// could be saved against it. Retry re-runs the load in place; Back leaves the
// reader the same way its own back button would.
@Composable
fun ChapterLoadErrorScreen(
    chapterTitle: String,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Couldn't load this chapter",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            "\"$chapterTitle\" couldn't be read. The file may have moved, or ARKarium may " +
                "have lost access to your library folder. You can try again, or go back.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Button(onClick = onRetry) { Text("Try again") }
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}
