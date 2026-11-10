package com.arkarium.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkarium.app.BuildConfig

// A round, tinted badge behind a section's leading icon - the same
// "SettingsIconBadge" idiom SettingsScreen uses for its rows, reused here so
// a legal section reads as a distinct, scannable card instead of a floating
// bold heading sitting directly above a paragraph.
//
// `container`/`onContainer` are passed in (rather than hardcoded to
// primaryContainer) so callers can cycle the badge through the theme's
// container tones - see sectionAccent below - instead of every single
// section in a long document wearing the exact same tint.
@Composable
private fun LegalIconBadge(
    icon: ImageVector,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    onContainer: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(container, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = onContainer,
            modifier = Modifier.size(18.dp)
        )
    }
}

// Cycles a section's badge through the theme's three "container" tones
// (primary/secondary/tertiary) by index, rather than every card in a
// seven-section document wearing the same one tint. All three are Material3
// theme tokens, so this still adapts correctly in light and dark mode - it's
// not introducing any hardcoded color of its own, just varying which
// existing token a given card uses.
private data class SectionAccent(
    val container: Color,
    val onContainer: Color
)

@Composable
private fun sectionAccent(index: Int): SectionAccent {
    val scheme = MaterialTheme.colorScheme
    return when (index % 3) {
        0 -> SectionAccent(scheme.primaryContainer, scheme.onPrimaryContainer)
        1 -> SectionAccent(scheme.secondaryContainer, scheme.onSecondaryContainer)
        else -> SectionAccent(scheme.tertiaryContainer, scheme.onTertiaryContainer)
    }
}

// One legal section rendered as its own rounded, tinted card - icon badge +
// heading up top, a plain-language tl;dr (when the section has one), then the
// actual body below - rather than a bare bold-text-then-paragraph pair.
// Breaking the document into visually separate cards, varying their badge
// color (see sectionAccent), and leading with a one-line human translation
// of the formal text is what actually fixes the "wall of text" problem: each
// card gives the eye a resting point and a section boundary, and a reader
// who only wants the gist can stop after the tl;dr line instead of parsing
// the paragraph beneath it. The tl;dr is a reading aid only - `body` is
// still the actual policy/terms text, unedited and unabridged.
@Composable
private fun LegalSectionCard(section: LegalSection, accent: SectionAccent) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegalIconBadge(section.icon, accent.container, accent.onContainer)
                Text(
                    section.heading,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
            if (section.tldr.isNotBlank()) {
                Text(
                    section.tldr,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 10.dp, start = 48.dp)
                )
            }
            Text(
                section.body,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    top = if (section.tldr.isNotBlank()) 6.dp else 10.dp,
                    start = 48.dp
                )
            )
        }
    }
}

// A single quick-fact pill - same rounded-chip idiom as the version badge
// below, just smaller and laid out in a horizontally-scrolling row (see
// QuickFactsRow) so the document opens with a four-second "here's the gist"
// skim before the reader ever hits a full paragraph.
@Composable
private fun QuickFactChip(text: String) {
    Box(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.secondaryContainer,
                RoundedCornerShape(50)
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

// Row of quick-fact chips shown once at the top of the document, before any
// section card. `horizontalScroll` (rather than a wrapping layout) keeps this
// safe to build against this project's current Compose BOM, which predates
// stable FlowRow - and a short, swipeable strip of chips is exactly the kind
// of "good/bad at a glance" summary sites like ToS;DR use, just inline.
@Composable
private fun QuickFactsRow(facts: List<String>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        facts.forEach { QuickFactChip(it) }
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
//
// Second pass ("make it fun, not a wall of text" - see the UI patterns real
// privacy-summary redesigns and ToS;DR-style overviews use): an optional
// `quickFacts` row up top gives a four-second skim of the whole document
// before any paragraph, each card now leads with a one-line plain-language
// tl;dr above its formal body (LegalSectionCard), and the badges cycle
// through the theme's primary/secondary/tertiary container tones
// (sectionAccent) instead of every card wearing an identical tint - variety
// that's still fully theme-driven, so light/dark mode isn't fought.
// `quickFacts` defaults to empty so this stays source-compatible with any
// caller that hasn't been updated to pass one.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalDocumentScreen(
    title: String,
    sections: List<LegalSection>,
    onBack: () -> Unit,
    quickFacts: List<String> = emptyList()
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
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

            if (quickFacts.isNotEmpty()) {
                item {
                    Column {
                        Text(
                            "The short version",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        QuickFactsRow(quickFacts)
                    }
                }
            }

            itemsIndexed(sections) { index, section ->
                LegalSectionCard(section, sectionAccent(index))
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
