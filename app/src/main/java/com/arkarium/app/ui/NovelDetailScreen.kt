package com.arkarium.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.arkarium.app.data.ArcEntity
import com.arkarium.app.data.ChapterEntity
import com.arkarium.app.data.NovelEntity

private val PAGE_SIZE_OPTIONS = listOf(10, 20, 50, 100)

// Roughly the point at which a Royal Road-style synopsis stops fitting in a
// collapsed few-line preview and needs a "Show more" toggle.
private const val DESCRIPTION_COLLAPSE_THRESHOLD = 220

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailScreen(
    novel: NovelEntity,
    chapters: List<ChapterEntity>,
    arcs: List<ArcEntity>,
    overriddenChapterIds: Set<String> = emptySet(),
    onBack: () -> Unit,
    onChapterSelected: (ChapterEntity) -> Unit,
    onResizePages: (Int) -> Unit = {},
    onEditClick: () -> Unit = {},
    onFetchInfoClick: () -> Unit = {},
    onAuthorClick: () -> Unit = {},
    // Non-null only offered to the caller when novel.syncSourceUrl is set - a purely
    // local novel (the common case) has nothing to check for updates against, so the
    // action is left off the app bar entirely rather than shown disabled (see
    // docs/arkarium/SYNC_MVP.md, Stage 3).
    onSyncClick: (() -> Unit)? = null,
    // Current value of NovelEntity.notifyNewChapters - passed in rather than read off
    // `novel` directly so the caller can reflect an in-flight permission-request flow
    // (see MainActivity's notificationPermission launcher) before the DB write it
    // triggers actually lands. Same non-null-gates-visibility pattern as onSyncClick.
    notifyEnabled: Boolean = false,
    onToggleNotify: ((Boolean) -> Unit)? = null
) {
    val selectedTabIndex = remember { mutableIntStateOf(0) }
    // Seeded from the novel's persisted page_size so the preference survives navigation
    // away and back, not just within a single composition.
    val pageSize = remember { mutableIntStateOf(novel.pageSize) }
    val currentPage = remember { mutableIntStateOf(0) }
    val tabs = listOf("All Chapters") + arcs.map { it.name }
    var descriptionExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Per-tab counts for the tab strip captions below - computed once per chapters
    // list change rather than re-filtering per tab on every recomposition.
    val chapterCountByArcId = remember(chapters) { chapters.groupingBy { it.arcId }.eachCount() }

    val chaptersInArc = when (selectedTabIndex.intValue) {
        0 -> chapters
        else -> {
            val arcId = arcs.getOrNull(selectedTabIndex.intValue - 1)?.id
            chapters.filter { it.arcId == arcId }
        }
    }
    // Search narrows within whatever's already picked by the arc tab - matching
    // Royal Road's ToC search, which filters the visible list rather than the
    // whole fiction. Title-only match (chapter numbers aren't meaningful search
    // terms here), case-insensitive.
    val chaptersInTab = if (searchQuery.isBlank()) {
        chaptersInArc
    } else {
        chaptersInArc.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    // Switching tabs, resizing, or searching changes the item count under the
    // current page, so reset back to page 1 rather than showing a now-meaningless
    // page index.
    LaunchedEffect(selectedTabIndex.intValue, pageSize.intValue, searchQuery) {
        currentPage.intValue = 0
    }

    val totalPages = if (chaptersInTab.isEmpty()) 1
        else ((chaptersInTab.size - 1) / pageSize.intValue) + 1
    val safePage = currentPage.intValue.coerceIn(0, totalPages - 1)
    val chaptersToShow = chaptersInTab
        .drop(safePage * pageSize.intValue)
        .take(pageSize.intValue)

    Column(modifier = Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text(novel.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (onSyncClick != null) {
                    IconButton(onClick = onSyncClick) {
                        Icon(Icons.Default.Sync, contentDescription = "Check for updates")
                    }
                }
                IconButton(onClick = onFetchInfoClick) {
                    Icon(Icons.Default.Info, contentDescription = "Fetch info")
                }
                IconButton(onClick = onEditClick) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                }
            }
        )

        LazyColumn(modifier = Modifier.weight(1f, fill = true)) {
            // Cover + title/author header, Royal Road-style: big cover on the left,
            // title/author stacked beside it.
            item {
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)) {
                    NovelCoverThumb(
                        // Local cover.png from the scanned folder takes priority over a
                        // remote metadata cover (only present after "Fetch info" has
                        // been run) - the local file is the one the user actually
                        // placed there.
                        coverUrl = novel.coverUri ?: novel.remoteCoverUrl,
                        // 2:3 full-size cover aspect ratio (96dp wide -> 144dp tall).
                        modifier = Modifier
                            .width(96.dp)
                            .height(144.dp)
                    )
                    Column(
                        modifier = Modifier
                            .padding(start = 14.dp)
                            .weight(1f),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                    ) {
                        Text(
                            novel.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!novel.author.isNullOrBlank()) {
                            Text(
                                "by ${novel.author}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    // Only a real tap target when this fiction actually
                                    // resolved to an author/<id>.json - otherwise there's
                                    // nowhere for the tap to go (see
                                    // AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md's "no author
                                    // link" fallback), same as the byline just being
                                    // plain text today.
                                    .let { base ->
                                        if (novel.authorId != null) base.clickable { onAuthorClick() } else base
                                    }
                            )
                        }
                        val statusLabel = when (novel.readingStatus) {
                            "IN_PROGRESS" -> "Reading"
                            "COMPLETED" -> "Completed"
                            else -> null
                        }
                        if (statusLabel != null) {
                            Text(
                                statusLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }

            // Genre/tag chips - Royal Road shows these as a row of pills right under
            // the title. Scrollable since a novel can carry more tags than fit on screen.
            if (!novel.genres.isNullOrBlank()) {
                item {
                    val genreList = remember(novel.genres) {
                        novel.genres.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    }
                    LazyRow(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        items(genreList) { genre ->
                            AssistChip(
                                onClick = {},
                                label = { Text(genre, fontSize = 12.sp) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            )
                        }
                    }
                }
            }

            // Synopsis - collapsed to a short preview with a "Show more" toggle, same
            // pattern Royal Road uses to keep the fold above the fiction's stats/ToC.
            if (!novel.description.isNullOrBlank()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            novel.description,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (descriptionExpanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (novel.description.length > DESCRIPTION_COLLAPSE_THRESHOLD) {
                            Text(
                                if (descriptionExpanded) "Show less" else "Show more",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .clickable { descriptionExpanded = !descriptionExpanded }
                            )
                        }
                    }
                }
            }

            // "Notify me when new chapters are available" - only offered for a synced
            // novel (onToggleNotify null for a purely local one), same gating as
            // onSyncClick in the app bar above: there's nothing to check for updates
            // against, so nothing to notify about either. See docs/arkarium/NEW_CHAPTER_NOTIFICATIONS.md
            // and NewChapterCheckWorker, which is what actually acts on this once it's on.
            if (onToggleNotify != null) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { onToggleNotify(!notifyEnabled) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (notifyEnabled) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
                            contentDescription = null,
                            tint = if (notifyEnabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Notify me when new chapters are available",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .weight(1f)
                        )
                        Switch(checked = notifyEnabled, onCheckedChange = onToggleNotify)
                    }
                }
            }

            // Start Reading - the single, prominent primary action Royal Road leads
            // with. Jumps into the first chapter of the whole novel (not just the
            // currently selected arc tab).
            if (chapters.isNotEmpty()) {
                item {
                    Button(
                        onClick = { onChapterSelected(chapters.first()) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.AutoStories,
                            contentDescription = null,
                            modifier = Modifier.height(18.dp)
                        )
                        Text("Start Reading", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            item { Divider(modifier = Modifier.padding(vertical = 4.dp)) }

            // Table of contents header, mirroring Royal Road's "X Chapters" count.
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.height(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Table of Contents",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    Text(
                        "${chapters.size} Chapters",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Arc tabs, Royal Road-style: a horizontal strip of real cover art per
            // arc (plus the novel's own cover for "All Chapters"), not a plain text
            // TabRow. This replaces both the old text-only tabs *and* the separate
            // 48x68dp "selected arc" header that used to appear below them - that
            // header only showed a cover AFTER you'd already picked a tab blind,
            // which defeats the point of having arc covers at all. Now the cover is
            // the thing you tap to choose, sized large enough to actually recognize
            // (92x130dp, same aspect as the novel header cover), with the arc name
            // and chapter count as a caption underneath, matching RR's ToC strip.
            if (tabs.size > 1) {
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            ArcTabCard(
                                coverUrl = novel.coverUri ?: novel.remoteCoverUrl,
                                label = "All Chapters",
                                count = chapters.size,
                                selected = selectedTabIndex.intValue == 0,
                                onClick = { selectedTabIndex.intValue = 0 }
                            )
                        }
                        itemsIndexed(arcs) { index, arc ->
                            ArcTabCard(
                                // Arc cover first; if this arc's folder had no cover.* file,
                                // fall back to the fiction's own cover rather than dropping
                                // straight to the placeholder - only when neither exists does
                                // ArcTabCard/NovelCoverThumb show the 📚 emoji. Same order as
                                // the reader screen's readerCoverUri resolution in
                                // MainActivity (arc -> fiction -> null, bugs.md Bug 3b).
                                coverUrl = arc.coverUri ?: novel.coverUri ?: novel.remoteCoverUrl,
                                label = arc.name,
                                count = chapterCountByArcId[arc.id] ?: 0,
                                selected = selectedTabIndex.intValue == index + 1,
                                onClick = { selectedTabIndex.intValue = index + 1 }
                            )
                        }
                    }
                }
            }

            // Chapter search - the single biggest ToC gap vs. Royal Road for any
            // fiction with more than a screenful of chapters. Filters the currently
            // selected arc tab's list by title; doesn't touch pageSize/currentPage
            // directly, those reset via the LaunchedEffect above.
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text("Search chapters...") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    }
                )
            }

            // Pagination size controls - kept compact, tucked under the ToC header
            // rather than competing with Start Reading for top-of-page attention.
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Per page:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    PAGE_SIZE_OPTIONS.forEach { size ->
                        val selected = pageSize.intValue == size
                        AssistChip(
                            onClick = {
                                pageSize.intValue = size
                                onResizePages(size)
                            },
                            label = { Text("$size", fontSize = 12.sp) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surface,
                                labelColor = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }

            // Chapter list - flat rows with a hairline divider between them, closer to
            // Royal Road's dense chapter table than the previous per-row Card look.
            // Zebra striping and sortTier-based Bonus/Extra tags make the ordering
            // fix from bugs.md Bug 2 visible/legible instead of just "correct but
            // silent" - a reader can now actually see why an interlude sorts where
            // it does relative to numbered chapters.
            itemsIndexed(chaptersToShow) { index, chapter ->
                Column(
                    modifier = Modifier.background(
                        if (index % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChapterSelected(chapter) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (chapter.number != null) {
                                    Text(
                                        "${chapter.number}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                }
                                when (chapter.sortTier) {
                                    1 -> ChapterTag("Bonus")
                                    2 -> ChapterTag("Extra")
                                }
                            }
                            Text(
                                chapter.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        if (chapter.id in overriddenChapterIds) {
                            Text(
                                "Edited",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                    Divider()
                }
            }

            // Empty state for a search that matched nothing, rather than silently
            // showing zero rows with no explanation.
            if (chaptersToShow.isEmpty() && searchQuery.isNotBlank()) {
                item {
                    Text(
                        "No chapters match \"$searchQuery\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                    )
                }
            }

            // Page navigation
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { if (safePage > 0) currentPage.intValue = safePage - 1 },
                        enabled = safePage > 0
                    ) {
                        Text("Prev")
                    }
                    Text(
                        "Page ${safePage + 1} of $totalPages",
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    OutlinedButton(
                        onClick = { if (safePage < totalPages - 1) currentPage.intValue = safePage + 1 },
                        enabled = safePage < totalPages - 1
                    ) {
                        Text("Next")
                    }
                }
            }
        }
    }
}

// Small pill for a chapter's sortTier - "Bonus" (interludes/omakes/side stories)
// or "Extra" (afterwords/author's notes). Kept visually quiet (surfaceVariant,
// no border) since these appear on nearly every page and shouldn't compete with
// the chapter title for attention.
@Composable
private fun ChapterTag(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontSize = 10.sp
        )
    }
}

// A single tappable cover card in the arc strip - the actual selection surface,
// not just decoration. 92dp wide matches roughly a phone screen fitting ~3.5
// cards, same aspect ratio as the big novel header cover so it reads as "the
// same kind of thing, zoomed out" rather than a differently-shaped thumbnail.
// Selected state is a 2dp primary-color border plus bolded/colored caption -
// deliberately not relying on color alone, since the cover art itself varies
// wildly and a color-only highlight can get lost against a busy image.
@Composable
private fun ArcTabCard(
    coverUrl: String?,
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(92.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        NovelCoverThumb(
            coverUrl = coverUrl,
            // 2:3 full-size cover aspect ratio (92dp wide -> 138dp tall), matching the
            // novel header cover above exactly rather than approximately.
            modifier = Modifier
                .width(92.dp)
                .height(138.dp)
                .let { base ->
                    if (selected) {
                        base.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                    } else {
                        base
                    }
                }
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            "$count Chapters",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun NovelCoverThumb(
    coverUrl: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (coverUrl != null) {
            // Fill whatever size the caller's `modifier` already established on the
            // outer Box, rather than a hardcoded 136dp height - this composable is
            // reused at a smaller size for the per-arc cover header below, and a fixed
            // height here would have clipped/overflowed at that size instead of
            // actually resizing.
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("📚", fontSize = 32.sp)
        }
    }
}
