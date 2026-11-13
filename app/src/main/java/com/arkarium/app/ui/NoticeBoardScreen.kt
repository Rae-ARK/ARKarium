package com.arkarium.app.ui

// The Notice Board tab (AppDestination.NOTICE_BOARD) - now an *index*: a skeuomorphic
// cork board with one pinned note per section, each opening its own page
// (noticeBoard/pigeonRally, noticeBoard/journal). See docs/proposal/notice-board.md.
//
// Skeuomorphic on purpose, so the board and its notes are drawn in code with fixed
// "physical" colors (cork, parchment, ink) rather than theme tokens - a pinned paper
// note doesn't change color with the app theme. Only the cork's *darkness* follows the
// theme (a dim room, not a recolored board), so the screen doesn't glare in dark mode.
// Everything is drawn with Compose primitives - no bitmap textures to bloat the APK or
// hurt scroll performance.

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

private val Parchment = Color(0xFFF6EBD0)
private val Ink = Color(0xFF3B2A1A)
private val InkSoft = Color(0xFF6B543C)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticeBoardScreen(
    // Unseen chapter_update_events count (NoticeBoardViewModel.unseenCount) - the
    // glanceable "something arrived" cue notice-board.md's "Entry point" bullet calls
    // for. 0 draws no badge at all rather than a badge reading "0".
    pigeonRallyUnseenCount: Int = 0,
    onOpenPigeonRally: () -> Unit = {},
    onOpenJournal: () -> Unit = {}
) {
    val dimRoom = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cork = if (dimRoom) Color(0xFF4A3524) else Color(0xFFC49A6C)
    val speck = if (dimRoom) Color(0xFF392819) else Color(0xFFA97F55)

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Notice Board") }, windowInsets = WindowInsets(0, 0, 0, 0))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(cork)
                .drawBehind {
                    // Cork grain: a fixed-seed scatter of small dots, so it never shimmers
                    // between redraws.
                    val rng = Random(7)
                    repeat(220) {
                        drawCircle(
                            color = speck,
                            radius = 1.2f + rng.nextFloat() * 3f,
                            center = Offset(rng.nextFloat() * size.width, rng.nextFloat() * size.height),
                            alpha = 0.35f + rng.nextFloat() * 0.4f
                        )
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(36.dp)
            ) {
                PinnedNote(
                    emoji = "\uD83D\uDD4A\uFE0F",
                    title = "Pigeon Rally",
                    subtitle = "New chapters, delivered by wing",
                    tilt = -1.8f,
                    unseenCount = pigeonRallyUnseenCount,
                    onClick = onOpenPigeonRally
                )
                PinnedNote(
                    emoji = "\uD83D\uDCD3",
                    title = "Rae's Journal",
                    subtitle = "Notes from the writing desk",
                    tilt = 1.4f,
                    onClick = onOpenJournal
                )
            }
        }
    }
}

// One paper note held to the board by a red pin. The whole note is the button.
@Composable
private fun PinnedNote(
    emoji: String,
    title: String,
    subtitle: String,
    tilt: Float,
    onClick: () -> Unit,
    unseenCount: Int = 0
) {
    Box(modifier = Modifier.fillMaxWidth().rotate(tilt)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 11.dp)
                .shadow(6.dp, RoundedCornerShape(3.dp))
                .background(Parchment, RoundedCornerShape(3.dp))
                .clickable(onClickLabel = "Open $title", role = Role.Button, onClick = onClick)
                .padding(horizontal = 22.dp, vertical = 30.dp)
        ) {
            Text(emoji, fontSize = 30.sp)
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                color = Ink,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Serif,
                color = InkSoft,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        // The pin: a glossy red head with a soft shadow, centered on the note's top edge.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(22.dp)
                .shadow(4.dp, CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFFFF8A80), Color(0xFFC62828)),
                        center = Offset(14f, 12f),
                        radius = 40f
                    ),
                    CircleShape
                )
        )
        // Same "something arrived" cue as any other badge-on-icon in the app - a
        // small red circle in the note's corner, drawn only once there's something
        // unseen to flag (see pigeonRallyUnseenCount's doc comment above).
        if (unseenCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
                    .shadow(3.dp, CircleShape)
                    .background(Color(0xFFC62828), CircleShape)
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    if (unseenCount > 99) "99+" else unseenCount.toString(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }
    }
}
