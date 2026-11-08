package com.arkarium.app.ui

// New, as part of the home-screen rework: a fourth bottom-bar tab the owner asked for
// explicitly as "just a stub... it's a separate work for a later time." This is
// deliberately a placeholder, not a cut-down real feature - no data model, no
// PreferencesManager keys, nothing for a later pass to migrate away from. It exists so
// the tab is reachable and honest about its own state rather than a dead or hidden
// bottom-bar entry.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticeBoardScreen() {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Notice Board") })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Filled.Campaign,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Text(
                "Notice Board is coming soon",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                "This is just a placeholder for now - updates, announcements " +
                    "and the like will land here in a later pass.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
