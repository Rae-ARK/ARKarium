package com.arkarium.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkarium.app.BuildConfig

// A round, tinted badge behind a section's leading icon - the same
// "SettingsIconBadge" idiom SettingsScreen uses for its rows, reused here so
// a legal section reads as a distinct, scannable card instead of a floating
// bold heading sitting directly above a paragraph.
@Composable
private fun LegalIconBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(
                MaterialTheme.colorScheme.primaryContainer,
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(18.dp)
        )
    }
}

// One legal section rendered as its own rounded, tinted card - icon badge +
// heading up top, body below - rather than a bare bold-text-then-paragraph
// pair. Breaking the document into visually separate cards is what actually
// fixes the "wall of text" problem: each card gives the eye a resting point
// and a section boundary, on top of just formatting the text nicer.
@Composable
private fun LegalSectionCard(section: LegalSection) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegalIconBadge(section.icon)
                Text(
                    section.heading,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
            Text(
                section.body,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp, start = 48.dp)
            )
        }
    }
}

// Generic renderer for a static legal document (Privacy Policy, Terms &
// Conditions) - same TopAppBar-with-back pattern the rest of the app's detail
// screens use (see AuthorPageScreen/ChapterEditorScreen), just rendering
// LegalSection data instead of app data. One screen, two content sets, rather
// than near-duplicate PrivacyPolicyScreen/TermsScreen files.
//
// Beautification pass: sections now render as icon-badged cards
// (LegalSectionCard above) with breathing room between them, and the
// "as of vX" line has been promoted from a small caption into its own
// pill-shaped chip under the title, so the page reads as a structured,
// designed document instead of one continuous block of paragraphs.
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

        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                // Tied to BuildConfig.VERSION_NAME (same single source of truth Settings'
                // About line and the crash screens already use) rather than a hand-typed
                // calendar date, so this can't silently go stale relative to the app
                // version it's actually describing. Rendered as a small rounded chip
                // instead of a plain caption line so it reads as metadata, not prose.
                Box(
                    modifier = Modifier
                        .padding(top = 16.dp, bottom = 4.dp)
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        "As of ARKarium v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            items(sections) { section ->
                LegalSectionCard(section)
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
