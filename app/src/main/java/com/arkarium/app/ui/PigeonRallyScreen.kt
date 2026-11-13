package com.arkarium.app.ui

// Notice Board's "Pigeon Rally" page (docs/proposal/notice-board.md): a persistent,
// reverse-chronological log of "this fiction got new chapters" events, styled as
// scrolls delivered by pigeon. Reads NoticeBoardViewModel's `events` (backed by the
// chapter_update_events table) - the real list in `PigeonRallyEventList` below reuses
// `ScrollPaper`, the same shared look the earlier stub was built to prove out, one
// scroll per event. The empty state below (no events at all) is exactly the original
// stub's copy, now reached only when NoticeBoardViewModel.events is genuinely empty
// rather than unconditionally.

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkarium.app.data.ChapterUpdateEventEntity
import com.arkarium.app.data.NovelEntity

private val ScrollPaperColor = Color(0xFFF6EBD0)
private val ScrollRollColor = Color(0xFFD9C49A)
private val ScrollInk = Color(0xFF3B2A1A)
private val ScrollInkSoft = Color(0xFF6B543C)
private val WaxSeal = Color(0xFFC62828)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PigeonRallyScreen(
    events: List<ChapterUpdateEventEntity> = emptyList(),
    // Resolved in-memory against each event's novelId, same join-at-the-UI-layer
    // convention LibraryViewModel.refreshRecentlyRead uses for recentlyRead - a
    // ChapterUpdateEventEntity deliberately carries no denormalized title/cover of its
    // own (see its doc comment on why there's no FK to novels either).
    novels: List<NovelEntity> = emptyList(),
    onBack: () -> Unit = {},
    onEventClick: (ChapterUpdateEventEntity) -> Unit = {},
    onMarkAllRead: () -> Unit = {}
) {
    val novelsById = remember(novels) { novels.associateBy { it.id } }
    val hasUnseen = remember(events) { events.any { !it.seen } }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Pigeon Rally") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                // Only shown once there's something to clear - a disabled action that
                // does nothing is worse than no action at all.
                if (hasUnseen) {
                    IconButton(onClick = onMarkAllRead) {
                        Icon(Icons.Filled.DoneAll, contentDescription = "Mark all read")
                    }
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0)
        )
        if (events.isEmpty()) {
            PigeonRallyEmptyState()
        } else {
            PigeonRallyEventList(
                events = events,
                novelsById = novelsById,
                onEventClick = onEventClick
            )
        }
    }
}

@Composable
private fun PigeonRallyEmptyState() {
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
                "Each time a fiction you follow gets new chapters, a scroll will " +
                    "arrive here.",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Serif,
                color = ScrollInk.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun PigeonRallyEventList(
    events: List<ChapterUpdateEventEntity>,
    novelsById: Map<String, NovelEntity>,
    onEventClick: (ChapterUpdateEventEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(events, key = { it.id }) { event ->
            // An event whose novel is no longer resolvable (removed from the library
            // and never re-followed - see ChapterUpdateEventEntity's doc comment on why
            // there's no FK to cascade this away automatically) is skipped rather than
            // rendered with a blank title/cover.
            val novel = novelsById[event.novelId] ?: return@items
            ScrollRow(event = event, novel = novel, onClick = { onEventClick(event) })
        }
    }
}

// One sealed/opened scroll per event - "sealed" (red wax dot, full-strength ink) while
// unseen, "opened" (no seal, softened ink) once tapped. Reuses ScrollPaper for the
// parchment-with-rolled-ends look the original stub established.
@Composable
private fun ScrollRow(
    event: ChapterUpdateEventEntity,
    novel: NovelEntity,
    onClick: () -> Unit
) {
    val ink = if (event.seen) ScrollInkSoft else ScrollInk
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open ${novel.title}'s update", role = Role.Button, onClick = onClick)
    ) {
        ScrollPaper {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ReaderCoverThumbnail(
                    coverUri = novel.coverUri ?: novel.remoteCoverUrl,
                    modifier = Modifier.size(width = 40.dp, height = 56.dp)
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp)
                ) {
                    Text(
                        novel.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        color = ink,
                        maxLines = 1
                    )
                    Text(
                        chapterUpdateSummary(event),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Serif,
                        color = ink,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        relativeDetectedAt(event.detectedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = ink.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (!event.seen) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(WaxSeal, CircleShape)
                    )
                }
            }
        }
    }
}

// "3 new chapters" for more than one, the chapter's own title when there's exactly
// one and it has one - same "N new chapters" phrasing NewChapterNotifier's system
// notification already uses, so Pigeon Rally never says something different from the
// notification that pointed here in the first place.
private fun chapterUpdateSummary(event: ChapterUpdateEventEntity): String {
    val title = event.latestChapterTitle
    return when {
        event.newChapterCount > 1 -> "${event.newChapterCount} new chapters"
        title != null -> title
        else -> "1 new chapter"
    }
}

private fun relativeDetectedAt(detectedAt: Long): String {
    return DateUtils.getRelativeTimeSpanString(
        detectedAt,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()
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
