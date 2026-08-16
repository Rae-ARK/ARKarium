package com.arkarium.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.arkarium.app.data.AuthorEntity
import com.arkarium.app.data.NovelEntity
import org.json.JSONObject

// Royal Road's profile page also has Follow/Block buttons and a live "Activity" feed
// (new chapter posted, review left, etc). Both are deliberately omitted here rather
// than kept as inert styling: ARKarium is offline and account-free (see README), so
// there is no real follow relationship to act on, and an "Activity" card would have
// nothing genuine to show without inventing data - see the Stage 2 kickoff decision
// referenced in AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorPageScreen(
    author: AuthorEntity,
    novels: List<NovelEntity> = emptyList(),
    onBack: () -> Unit,
    onNovelClick: (NovelEntity) -> Unit = {}
) {
    val links = remember(author.linksJson) { parseAuthorLinks(author.linksJson) }
    val uriHandler = LocalUriHandler.current

    Column(modifier = Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text(author.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        LazyColumn(modifier = Modifier.weight(1f, fill = true)) {
            // Banner + overlapping avatar, Royal Road profile-header style. Combined
            // into a single LazyColumn item (rather than two) so the avatar's downward
            // offset can't visually collide with whatever item comes after it.
            item {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(88.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary
                                    )
                                )
                            )
                    )
                    AuthorAvatar(
                        avatarUrl = author.avatarUri,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = 36.dp)
                            .size(88.dp)
                    )
                }
                // Reserves room for the avatar's downward bleed past the banner (36.dp
                // offset + roughly half its own 88.dp height) so following items don't
                // start underneath it.
                Spacer(modifier = Modifier.height(44.dp))
                Text(
                    author.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Stat strip - Follows / Favorites / Reviews / Fictions. The first three are
            // the manually-authored, display-only numbers from author.json's "stats"
            // (never a real count - this is an offline app); a missing value renders as
            // "—" rather than 0, so an author who simply didn't set that stat isn't shown
            // with a misleading zero. Fiction count is the one number that IS computed
            // rather than hand-maintained, from the novels actually linked to this author.
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AuthorStat("Follows", author.followers)
                    AuthorStat("Favorites", author.favorites)
                    AuthorStat("Reviews", author.reviewsReceived)
                    AuthorStat("Fictions", novels.size)
                }
            }

            item { Divider(modifier = Modifier.padding(vertical = 4.dp)) }

            // Personal Information card - only rendered rows are the ones the author.json
            // actually set; an author.json with none of these fields simply skips the
            // whole card rather than showing an empty shell.
            val hasPersonalInfo = !author.location.isNullOrBlank() ||
                !author.gender.isNullOrBlank() ||
                !author.joined.isNullOrBlank() ||
                links.isNotEmpty()
            if (hasPersonalInfo) {
                item {
                    AuthorInfoCard(title = "Personal Information") {
                        if (!author.joined.isNullOrBlank()) {
                            AuthorInfoRow("Joined", author.joined)
                        }
                        if (!author.location.isNullOrBlank()) {
                            AuthorInfoRow("Location", author.location)
                        }
                        if (!author.gender.isNullOrBlank()) {
                            AuthorInfoRow("Gender", author.gender)
                        }
                        links.forEach { (label, url) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { uriHandler.openUri(url) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Link,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    label.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            // About / bio - shown in full here, unlike the truncated excerpt the Stage 3
            // chapter-page "About the author" card will use.
            if (!author.bio.isNullOrBlank()) {
                item {
                    AuthorInfoCard(title = "About") {
                        Text(author.bio, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Author Information card. "Total Words" from Stage 0/1's open question 1 is
            // deliberately still not shown - it isn't stored anywhere yet (no wordCount
            // column exists on NovelEntity/ChapterEntity), so showing it here would mean
            // inventing a number rather than reading one.
            item {
                AuthorInfoCard(title = "Author Information") {
                    AuthorInfoRow("Fictions", novels.size.toString())
                }
            }

            // Fictions by this author
            if (novels.isNotEmpty()) {
                item {
                    Text(
                        "Fictions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
                items(novels) { novel ->
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNovelClick(novel) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AuthorFictionCoverThumb(
                                coverUrl = novel.coverUri ?: novel.remoteCoverUrl,
                                modifier = Modifier
                                    .width(40.dp)
                                    .height(56.dp)
                            )
                            Text(
                                novel.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp)
                            )
                        }
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthorStat(label: String, value: Int?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value?.toString() ?: "—",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AuthorInfoCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            content()
        }
    }
}

@Composable
private fun AuthorInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AuthorAvatar(avatarUrl: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            // Same "emoji placeholder when there's no local image" pattern
            // NovelCoverThumb (NovelDetailScreen.kt) already uses for missing covers.
            Text("🖋️", fontSize = 28.sp)
        }
    }
}

@Composable
private fun AuthorFictionCoverThumb(coverUrl: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (coverUrl != null) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            )
        } else {
            Text("📚", fontSize = 16.sp)
        }
    }
}

// Parses author.json's optional "links" object (e.g. {"twitter": "...", "website":
// "..."}) back out of the flat string AuthorEntity.linksJson was stored as - see the
// comment on AuthorEntity.linksJson for why it's a raw JSON string instead of a Room
// TypeConverter/child table. Malformed/absent JSON just yields no links, same fail-soft
// treatment every other piece of author/novel metadata already gets.
private fun parseAuthorLinks(linksJson: String?): List<Pair<String, String>> {
    if (linksJson.isNullOrBlank()) return emptyList()
    return try {
        val json = JSONObject(linksJson)
        json.keys().asSequence()
            .mapNotNull { key -> json.optString(key).takeIf { it.isNotBlank() }?.let { key to it } }
            .toList()
    } catch (e: Exception) {
        emptyList()
    }
}
