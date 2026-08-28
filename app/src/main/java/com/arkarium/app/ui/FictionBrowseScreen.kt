package com.arkarium.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import coil.compose.AsyncImage
import com.arkarium.app.data.NovelEntity

enum class SortBy {
    RECENTLY_UPDATED,
    MOST_POPULAR,
    HIGHEST_RATED,
    NEWEST
}

enum class StatusFilter {
    ALL, IN_PROGRESS, COMPLETED, NOT_STARTED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FictionBrowseScreen(
    novels: List<NovelEntity>,
    // Seeds the search field from Home's search bar (see MainActivity's
    // Screen.FictionBrowse). remember(initialQuery) rather than a bare remember {} so a
    // fresh query from Home always takes effect: this composable's instance is torn down
    // and rebuilt on every Home -> Browse navigation (it lives under a `when` on
    // currentScreen), so in practice a bare remember {} would already re-seed correctly
    // too - keying on initialQuery here is defensive documentation of that requirement,
    // not a fix for an observed bug.
    initialQuery: String = "",
    onNovelSelected: (NovelEntity) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val searchQuery = remember(initialQuery) { mutableStateOf(initialQuery) }
    val selectedSort = remember { mutableStateOf(SortBy.RECENTLY_UPDATED) }
    val selectedStatus = remember { mutableStateOf(StatusFilter.ALL) }
    val showSortSheet = remember { mutableStateOf(false) }
    val showFilterSheet = remember { mutableStateOf(false) }
    val sortSheetState = rememberModalBottomSheetState()
    val filterSheetState = rememberModalBottomSheetState()

    // Apply filters and sorting. Recomputed only when an actual input changes (rather
    // than on every recomposition) so MOST_POPULAR/HIGHEST_RATED - which currently
    // stand in for real ranking with a shuffle - don't visibly re-shuffle the grid
    // every time the screen recomposes for an unrelated reason.
    val filtered = remember(novels, searchQuery.value, selectedStatus.value, selectedSort.value) {
        var result = novels.filter { novel ->
            novel.title.contains(searchQuery.value, ignoreCase = true) ||
            (novel.author?.contains(searchQuery.value, ignoreCase = true) ?: false)
        }

        result = when (selectedStatus.value) {
            StatusFilter.IN_PROGRESS -> result.filter { it.readingStatus == "IN_PROGRESS" }
            StatusFilter.COMPLETED -> result.filter { it.readingStatus == "COMPLETED" }
            StatusFilter.NOT_STARTED -> result.filter { it.readingStatus == "NOT_STARTED" }
            StatusFilter.ALL -> result
        }

        when (selectedSort.value) {
            SortBy.RECENTLY_UPDATED -> result.sortedByDescending { it.title }  // Would use timestamp in production
            SortBy.MOST_POPULAR -> result.shuffled()  // Would use view count
            SortBy.HIGHEST_RATED -> result.shuffled()  // Would use rating
            SortBy.NEWEST -> result.reversed()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top app bar
        TopAppBar(
            title = { Text("Browse Novels") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            }
        )

        // Filter and sort controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Sort button
            FilterChip(
                selected = false,
                onClick = { showSortSheet.value = true },
                label = { Text("Sort") },
                leadingIcon = { Icon(Icons.Default.Sort, contentDescription = "Sort") },
                modifier = Modifier.weight(1f)
            )

            // Filter button
            FilterChip(
                selected = selectedStatus.value != StatusFilter.ALL,
                onClick = { showFilterSheet.value = true },
                label = { Text("Filter") },
                leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = "Filter") },
                modifier = Modifier.weight(1f)
            )
        }

        // Active filters display
        if (selectedStatus.value != StatusFilter.ALL) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = true,
                    onClick = { selectedStatus.value = StatusFilter.ALL },
                    label = { Text(selectedStatus.value.name.replace("_", " ")) },
                    trailingIcon = { Icon(Icons.Default.Check, contentDescription = "Active") }
                )
            }
        }

        // Novel grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filtered) { novel ->
                EnhancedNovelCard(
                    novel = novel,
                    onClick = { onNovelSelected(novel) }
                )
            }
        }
    }

    // Sort bottom sheet
    if (showSortSheet.value) {
        ModalBottomSheet(
            onDismissRequest = { showSortSheet.value = false },
            sheetState = sortSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    "Sort by",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                SortBy.values().forEach { sort ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedSort.value = sort
                                showSortSheet.value = false
                            }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(sort.name.replace("_", " "))
                        if (selectedSort.value == sort) {
                            Icon(Icons.Default.Check, contentDescription = "Selected")
                        }
                    }
                }
            }
        }
    }

    // Filter bottom sheet
    if (showFilterSheet.value) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet.value = false },
            sheetState = filterSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    "Filter by Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                StatusFilter.values().forEach { status ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedStatus.value = status
                                showFilterSheet.value = false
                            }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(status.name.replace("_", " "))
                        if (selectedStatus.value == status) {
                            Icon(Icons.Default.Check, contentDescription = "Selected")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EnhancedNovelCard(
    novel: NovelEntity,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            // Cover area - local cover.png from the scanned folder takes priority over
            // a remote metadata cover (only present after "Fetch info" has been run).
            val coverUrl = novel.coverUri ?: novel.remoteCoverUrl
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 2:3 full-size cover aspect ratio - matches the sync dialog and
                    // novel detail header instead of the old fixed 200dp crop, so the
                    // grid card's cover shape stays consistent across grid-cell widths.
                    .aspectRatio(2f / 3f)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            )
                        )
                    ),
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
                    Text("📖", fontSize = 60.sp)
                }

                        // Status badge
                        if (novel.readingStatus != "NOT_STARTED") {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .background(
                                        color = when (novel.readingStatus) {
                                            "IN_PROGRESS" -> Color(0xFFFF9800)  // Orange for In Progress
                                            "COMPLETED" -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                                            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                        },
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    when (novel.readingStatus) {
                                        "IN_PROGRESS" -> "Reading"
                                        "COMPLETED" -> "Done"
                                        else -> "New"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.surface,
                                    fontSize = 10.sp
                                )
                            }
                        }
            }

            // Info section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    novel.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (!novel.author.isNullOrBlank()) {
                    Text(
                        novel.author!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                // Rating placeholder
                Row(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    repeat(5) { index ->
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Star",
                            modifier = Modifier.size(14.dp),
                            tint = if (index < 4) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }
                    Text(
                        "4.0",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}
