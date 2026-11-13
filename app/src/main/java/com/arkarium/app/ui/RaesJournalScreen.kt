package com.arkarium.app.ui

// Rae's Journal (docs/proposal/notice-board.md): the author's writing journal, read from
// the RSS feed at RAES_JOURNAL_FEED_URL (see data/JournalFeed.kt for the feed's shape and
// why entries have no per-entry link). Stateless - JournalViewModel owns loading.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkarium.app.data.JournalEntry
import com.arkarium.app.data.JournalFeed
import com.arkarium.app.data.JournalLoadResult
import java.text.DateFormat
import java.util.Date

// Entries are long-ish paragraphs; show a few lines and expand on tap.
private const val COLLAPSED_LINES = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaesJournalScreen(
    result: JournalLoadResult?,
    isLoading: Boolean,
    onRefresh: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Rae's Journal") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = onRefresh, enabled = !isLoading) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0)
        )
        if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        when (result) {
            null -> if (!isLoading) CenteredMessage("Nothing here yet.")
            is JournalLoadResult.Failed -> FailedState(result.reason, onRefresh)
            is JournalLoadResult.Fresh -> JournalList(result.feed, offlineNote = null)
            is JournalLoadResult.Offline -> JournalList(
                result.feed,
                offlineNote = "Couldn't reach the journal - showing the copy saved " +
                    "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(result.savedAtMillis))}."
            )
        }
    }
}

@Composable
private fun JournalList(feed: JournalFeed, offlineNote: String?) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (offlineNote != null) {
            item {
                Text(
                    offlineNote,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (!feed.description.isNullOrBlank()) {
            item {
                Text(
                    feed.description,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (feed.entries.isEmpty()) {
            item { Text("The journal has no entries yet.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(feed.entries, key = { it.guid }) { entry -> JournalEntryCard(entry) }
    }
}

@Composable
private fun JournalEntryCard(entry: JournalEntry) {
    var expanded by remember(entry.guid) { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "Collapse" else "Expand") { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            // Undated is shown as such - never guessed from "now".
            val date = entry.publishedAt?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
            val tags = entry.tags.joinToString(" · ")
            Text(
                listOfNotNull(date ?: "Undated", tags.takeIf { it.isNotEmpty() }).joinToString("  •  "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                entry.description,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun FailedState(reason: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("\uD83D\uDCD3", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Couldn't open the journal",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}
