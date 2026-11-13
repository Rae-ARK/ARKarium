package com.arkarium.app.ui

// Stub for the "Pigeon Rally" page of the Notice Board (docs/proposal/notice-board.md):
// the persistent new-chapters log, styled as scrolls delivered by pigeon. Nothing is
// built behind it yet - no chapter_update_events table, no DAO - so this only proves the
// route, the back button and the scroll look. The real list replaces `PigeonRallyStub`'s
// body; `ScrollPaper` is the visual the real rows (one sealed/opened scroll per event)
// are meant to reuse.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val ScrollPaperColor = Color(0xFFF6EBD0)
private val ScrollRollColor = Color(0xFFD9C49A)
private val ScrollInk = Color(0xFF3B2A1A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PigeonRallyScreen(onBack: () -> Unit = {}) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Pigeon Rally") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0)
        )
        PigeonRallyStub()
    }
}

@Composable
private fun PigeonRallyStub() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        ScrollPaper {
            Text("\uD83D\uDD4A\uFE0F", style = MaterialTheme.typography.headlineMedium)
            Text(
                "No pigeons have landed yet",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                color = ScrollInk,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                "Each time a fiction you follow gets new chapters, a scroll will arrive " +
                    "here. This page is just a placeholder for now.",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Serif,
                color = ScrollInk.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

// A sheet of parchment with a rolled end top and bottom. Drawn from shapes (no bitmaps)
// so it scales with the content and stays cheap inside a long LazyColumn later.
@Composable
internal fun ScrollPaper(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ScrollRoll()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .background(ScrollPaperColor)
                .padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            content()
        }
        ScrollRoll()
    }
}

@Composable
private fun ScrollRoll() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .shadow(3.dp, RoundedCornerShape(50))
            .background(ScrollRollColor, RoundedCornerShape(50))
    )
}
