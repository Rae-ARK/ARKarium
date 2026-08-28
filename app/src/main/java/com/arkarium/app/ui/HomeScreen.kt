package com.arkarium.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkarium.app.data.NovelEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    novels: List<NovelEntity>,
    inProgressNovels: List<NovelEntity>,
    // Reading-stats card (see ChapterReadEventEntity's doc comment for why this
    // exists) - both default to 0 rather than being nullable/optional, since
    // LibraryViewModel's backing state itself always starts at 0 rather than
    // "not loaded yet," and a genuinely brand-new reader with zero history should
    // see "0 chapters this week" rather than the card being hidden outright (see
    // ReadingStatsCard below for why it stays visible even at zero).
    streakDays: Int = 0,
    chaptersReadThisWeek: Int = 0,
    onNovelClick: (NovelEntity) -> Unit = {},
    onContinueReading: (NovelEntity) -> Unit = {},
    onBrowseClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onSearch: (String) -> Unit = {},
    // There was previously no reachable screen on first launch that could trigger the SAF
    // folder picker: Home -> "Browse All Novels" led to FictionBrowse (which has no picker
    // either), and the only screen that did have a picker button (Library) was only
    // reachable via "back" from NovelDetail/Reader - which themselves require novels to
    // already exist. New installs had no way in. This callback gives Home a direct entry
    // point to the picker so that dead end can't happen again.
    // Still only reachable from EmptyLibraryPrompt now (see below) - the top bar's own
    // folder icon was repurposed for onAddFictionClick, so this param stays but isn't
    // wired to the top bar anymore.
    onSelectFolderClick: () -> Unit = {},
    // Opens the "Add fiction by name" dialog (see SyncDialogs.kt
    // AddFictionByNameDialog). Lives on the top bar next to Settings - previously that
    // spot was a shortcut to the folder picker (now settings-only, and still reachable
    // via EmptyLibraryPrompt on a truly empty library).
    onAddFictionClick: () -> Unit = {},
    // Primary action on the empty-library first-run screen: syncs every fiction
    // FictionLut knows about in one go (see FictionLut.allEntries / MainActivity's
    // syncAllRaeArkNovels). Distinct from onAddFictionClick, which adds exactly one
    // fiction by typed name.
    onSyncAllClick: () -> Unit = {}
) {
    val searchQuery = remember { mutableStateOf("") }
    // Snapshot `novels` (a SnapshotStateList) into a plain List before using it as a
    // remember() key. SnapshotStateList doesn't override equals()/hashCode() - it's
    // compared by reference, and that reference never changes as items are added to it
    // in place. So `remember(novels) { novels.shuffled().take(6) } ` was keyed on
    // something that looked "the same" on every recomposition even as the list's
    // contents grew from 0 -> N during the startup scan, permanently freezing
    // trendingNovels/newReleases at whatever `novels` happened to contain the first
    // time HomeScreen composed - empty, if that first composition landed before the
    // scan had added anything yet. That's the intermittent "Trending/New Releases show
    // nothing" bug: it only reproduced when composition raced ahead of the scan.
    // `novels.toList()` is a plain Kotlin List, which does have structural equals(), so
    // remember() correctly recomputes only when the actual contents change - while
    // still not reshuffling on every unrelated recomposition (e.g. typing in search).
    val novelsSnapshot = novels.toList()
    val trendingNovels = remember(novelsSnapshot) { novelsSnapshot.shuffled().take(6) }
    val newReleases = remember(novelsSnapshot) { novelsSnapshot.shuffled().take(4) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        TopAppBar(
            title = { Text("ARKarium") },
            actions = {
                IconButton(onClick = onAddFictionClick) {
                    Icon(Icons.Default.LibraryAdd, contentDescription = "Add fiction")
                }
                IconButton(onClick = onSettingsClick) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        )

        if (novels.isEmpty()) {
            // First-run / empty-library state: this is the only guaranteed entry point to
            // the folder picker on a fresh install, so it needs to be obvious and always
            // present rather than tucked away behind navigation that depends on already
            // having novels.
            //
            // NOTE: this branch used to end with `return@Column` to skip the LazyColumn
            // below. `Column` is an inline composable, and a qualified return out of an
            // inline composable's trailing lambda skips the compiler-generated group-close
            // bookkeeping for the rest of the lambda. That corrupts the slot table and
            // crashes on the very first composition with
            // `ArrayIndexOutOfBoundsException: index=-5` deep in
            // `SlotTableKt.key`/`Composer.endRoot` - exactly the crash this app hit on
            // first launch (empty library = novels.isEmpty() = true). Wrapping the rest of
            // the content in `else` instead avoids the early return entirely.
            EmptyLibraryPrompt(onSelectFolderClick = onSelectFolderClick, onSyncAllClick = onSyncAllClick)
        } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 16.dp)
        ) {
            // Search bar
            item {
                SearchBar(
                    query = searchQuery.value,
                    onQueryChange = { searchQuery.value = it },
                    onSearch = { onSearch(it) },
                    active = false,
                    onActiveChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    placeholder = { Text("Search novels...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    // This material3 version's SearchBar requires a `content` lambda for the
                    // active-state suggestions area even though `active` is always false here
                    // (there's no default value for this overload) - nothing to show, so empty.
                    content = {}
                )
            }

            // Reading stats (streak + chapters this week) - see ReadingStatsCard.
            // Placed right under search, above Featured, so it's the first thing a
            // returning reader sees on Home - the moment it's most likely to
            // actually nudge a same-day return visit.
            item {
                ReadingStatsCard(
                    streakDays = streakDays,
                    chaptersReadThisWeek = chaptersReadThisWeek
                )
            }

            // Featured section (hero carousel)
            item {
                FeaturedSection(
                    featured = trendingNovels.take(1),
                    onNovelClick = onNovelClick
                )
            }

            // Continue Reading section
            if (inProgressNovels.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            "Continue Reading",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                        ) {
                            items(inProgressNovels.take(4)) { novel ->
                                NovelContinueCard(
                                    novel = novel,
                                    onContinue = { onContinueReading(novel) },
                                    onClick = { onNovelClick(novel) }
                                )
                            }
                        }
                    }
                }
            }

            // Trending section
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Trending",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "View all",
                            modifier = Modifier.clickable { onBrowseClick() },
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp
                        )
                    }
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        items(trendingNovels) { novel ->
                            NovelCardVertical(
                                novel = novel,
                                onClick = { onNovelClick(novel) },
                                modifier = Modifier.width(140.dp)
                            )
                        }
                    }
                }
            }

            // New releases section
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        "New Releases",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        items(newReleases) { novel ->
                            NovelCardVertical(
                                novel = novel,
                                onClick = { onNovelClick(novel) },
                                modifier = Modifier.width(140.dp)
                            )
                        }
                    }
                }
            }

            // Browse button
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clickable { onBrowseClick() }
                        .height(80.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Browse All Novels",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
fun EmptyLibraryPrompt(
    onSelectFolderClick: () -> Unit = {},
    onSyncAllClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("📚", fontSize = 56.sp)
            Text(
                "No library yet",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Get started with Rae ARK's fictions, synced straight from the relay.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Button(
                onClick = onSyncAllClick,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Icon(Icons.Default.LibraryAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Sync all Rae ARK's novels", modifier = Modifier.padding(start = 8.dp))
            }
            // Secondary, deliberately less prominent than the sync button above - but
            // still present, since this is the only guaranteed entry point into the SAF
            // folder picker on a fresh install for anyone managing their own local
            // folder instead of syncing (see the comment on HomeScreen's
            // onSelectFolderClick param).
            androidx.compose.material3.TextButton(onClick = onSelectFolderClick) {
                Text("or set up your own folder in Settings")
            }
        }
    }
}

@Composable
fun FeaturedSection(
    featured: List<NovelEntity>,
    onNovelClick: (NovelEntity) -> Unit = {}
) {
    if (featured.isEmpty()) return

    val novel = featured.first()
    // Local cover.png from the scanned folder takes priority over a remote metadata
    // cover (only present after "Fetch info" has been run) - the local file is the
    // one the user actually placed there.
    val coverUrl = novel.coverUri ?: novel.remoteCoverUrl
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .height(200.dp)
            .clickable { onNovelClick(novel) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = if (coverUrl != null) {
                                // Cover art present: a bottom-heavy dark scrim so the
                                // title/author text stays legible over the image.
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                            } else {
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                )
                            }
                        )
                    )
            ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Featured",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        novel.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!novel.author.isNullOrBlank()) {
                        Text(
                            "by ${novel.author}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
fun ReadingStatsCard(
    streakDays: Int,
    chaptersReadThisWeek: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ReadingStatItem(
                icon = Icons.Filled.LocalFireDepartment,
                // Always shown, even at 0 - a visible "0 day streak" is what turns
                // this into a habit-forming nudge in the first place ("get this to
                // 1"), whereas hiding the card until some activity exists would
                // only ever reinforce reading for people who were already reading
                // regularly.
                value = streakDays.toString(),
                // "day streak" reads fine ungrammatically-pluralized ("1 day
                // streak", "3 day streak") since "streak" itself isn't the thing
                // being counted - unlike "chapters" below, which does need the
                // singular/plural split.
                label = "day streak"
            )
            ReadingStatDivider()
            ReadingStatItem(
                icon = Icons.Filled.MenuBook,
                value = chaptersReadThisWeek.toString(),
                label = if (chaptersReadThisWeek == 1) "chapter this week" else "chapters this week"
            )
        }
    }
}

@Composable
private fun ReadingStatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// A thin vertical rule between the two stats, the same idea as a divider in a list
// but oriented for a horizontal row - keeps the two numbers from reading as one
// run-on stat when they sit this close together.
@Composable
private fun ReadingStatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(36.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
    )
}

@Composable
fun NovelCardVertical(
    novel: NovelEntity,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            // Same 140dp-wide card as before, just taller to fit a 2:3 (rather than
            // square) cover below - matches the full-size cover proportions used
            // elsewhere (e.g. the novel detail header, the sync dialog), so cover art
            // isn't cropped to a different shape depending on where it's shown.
            .height(270.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Cover art - falls back to the emoji placeholder only when the novel has
            // neither a local cover.png (from the scanned folder) nor a remote cover
            // (from "Fetch info").
            val coverUrl = novel.coverUri ?: novel.remoteCoverUrl
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 2:3 full-size cover aspect ratio (140dp wide -> 210dp tall).
                    .height(210.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                if (coverUrl != null) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("📚", fontSize = 40.sp)
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    novel.title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun NovelContinueCard(
    novel: NovelEntity,
    onContinue: () -> Unit = {},
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .width(160.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row {
                val coverUrl = novel.coverUri ?: novel.remoteCoverUrl
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(56.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (coverUrl != null) {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text("📚", fontSize = 18.sp)
                    }
                }
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        novel.title,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!novel.author.isNullOrBlank()) {
                        Text(
                            novel.author!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            androidx.compose.material3.Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continue", fontSize = 12.sp)
            }
        }
    }
}
