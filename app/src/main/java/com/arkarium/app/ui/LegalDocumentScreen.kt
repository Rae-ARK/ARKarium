package com.arkarium.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arkarium.app.BuildConfig

// Generic renderer for a static legal document (Privacy Policy, Terms &
// Conditions) - same TopAppBar-with-back pattern the rest of the app's detail
// screens use (see AuthorPageScreen/ChapterEditorScreen), just rendering
// LegalSection data instead of app data. One screen, two content sets, rather
// than near-duplicate PrivacyPolicyScreen/TermsScreen files.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalDocumentScreen(
    title: String,
    sections: List<LegalSection>,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
            item {
                // Tied to BuildConfig.VERSION_NAME (same single source of truth Settings'
                // About line and the crash screens already use) rather than a hand-typed
                // calendar date, so this can't silently go stale relative to the app
                // version it's actually describing.
                Text(
                    "As of ARKarium v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }

            items(sections) { section ->
                Text(
                    section.heading,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                Text(
                    section.body,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                androidx.compose.foundation.layout.Spacer(
                    modifier = Modifier.height(32.dp)
                )
            }
        }
    }
}
