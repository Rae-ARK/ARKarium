package com.arkarium.app.ui

// Stub for the "Rae's Journal" page of the Notice Board (docs/proposal/notice-board.md):
// the author's writing journal, to be read from the RSS feed below. Nothing fetches or
// parses it yet - this only proves the route and the back button.
//
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// Notes from the live feed, for whoever builds the real list:
// - RSS 2.0, ~10 items, newest first. Every item's <link> is the same journal page
//   (".../journal"), so an item's identity is its <guid> (isPermaLink="false"), never
//   its link - and there is no per-entry URL to open.
// - <pubDate> is optional: some entries only have a season or month in their guid
//   ("spring-2026", "may-2026"), so a missing date must render as "undated", not as
//   "now", and ordering should trust the feed's own order.
// - <description> is plain text (entities like &quot; escaped), and <category> carries
//   the fiction tags ("SBM", "EPMC", "SBM · EPMC") as one string.
// - It's a different host from the relay RELAY_HOST serves fictions from, so this is a
//   second origin the app would talk to.
const val RAES_JOURNAL_FEED_URL = "https://rae-ark.horizonarkstudio.workers.dev/feed.xml"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaesJournalScreen(onBack: () -> Unit = {}) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Rae's Journal") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("\uD83D\uDCD3", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Rae's Journal is coming soon",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                "Entries from the writing journal will be read here. " +
                    "This page is just a placeholder for now.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
