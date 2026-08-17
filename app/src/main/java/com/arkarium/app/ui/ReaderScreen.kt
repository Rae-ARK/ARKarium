package com.arkarium.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.arkarium.app.data.AuthorEntity
import com.arkarium.app.data.ChapterEntity
import com.arkarium.app.data.Theme

enum class ReadingMode {
    LIGHT, SEPIA, DARK
}

// Reader's own LIGHT/SEPIA/DARK palette is intentionally separate from the app-wide
// Theme (it needs a sepia option Theme doesn't have, and users may want a different
// reading background than their nav-screen background) - but it should still *start*
// aligned with the app theme rather than always opening in LIGHT regardless of what
// the user picked in Settings, which read as the app "forgetting" a Dark/Warm Paper
// preference every time a chapter was opened.
private fun readingModeFor(theme: Theme): ReadingMode = when (theme) {
    Theme.LIGHT -> ReadingMode.LIGHT
    Theme.DARK -> ReadingMode.DARK
    Theme.WARM_PAPER -> ReadingMode.SEPIA
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    chapter: ChapterEntity,
    content: String,
    appTheme: Theme = Theme.LIGHT,
    // Everything below is new for the Stage 3 chapter-page redesign. All optional/
    // no-op by default so this stays source-compatible with any other call site.
    // novelTitle/arcTitle are plain strings (not NovelEntity/ArcEntity) so ReaderScreen
    // stays decoupled from the data layer beyond the ChapterEntity it already took.
    novelTitle: String? = null,
    arcTitle: String? = null,
    // Resolution order handled by the caller (arc cover -> fiction cover -> null, see
    // bugs.md Bug 3b): arc's own ArcEntity.coverUri if this chapter belongs to an arc
    // that has one, else the fiction's NovelEntity.coverUri, else null. Null renders
    // the same placeholder ReaderAuthorAvatar/AuthorPageScreen already use elsewhere,
    // rather than leaving a blank gap.
    coverUri: String? = null,
    // Sourced from the same author.json-backed AuthorEntity the Stage 2 AuthorPageScreen
    // reads - see AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md's Stage 3 section. Null renders no
    // "About the author" card at all, same as a fiction with no linked author today.
    author: AuthorEntity? = null,
    onBack: (Float) -> Unit,
    // Distinct from onBack: onBack is the existing top-app-bar arrow (goes to whatever
    // the caller currently sends it, e.g. Home); onBackToFiction is the new Royal
    // Road-style breadcrumb link that always means "this fiction's detail page". Kept
    // separate rather than repurposing onBack so this stage doesn't silently change the
    // existing top-bar arrow's behavior. Same (Float) -> Unit shape as onBack so this
    // exit path saves reading progress too, instead of silently dropping it.
    onBackToFiction: (Float) -> Unit = {},
    // Null (not just "does nothing when clicked") means "there is no previous/next
    // chapter" - the caller passes null at the first/last chapter so the buttons render
    // disabled instead of merely inert. Takes the same (Float) -> Unit progress-on-exit
    // shape as onBack so navigating via Previous/Next still saves reading progress.
    onPrevious: ((Float) -> Unit)? = null,
    onNext: ((Float) -> Unit)? = null,
    onAuthorClick: () -> Unit = {}
) {
    val fontSize = remember { mutableFloatStateOf(18f) }
    val lineHeight = remember { mutableFloatStateOf(1.8f) }
    val readingMode = remember { mutableStateOf(readingModeFor(appTheme)) }
    val showControls = remember { mutableStateOf(true) }
    // Separate from showControls: showControls is the tap-anywhere immersive toggle
    // for the top bar + progress readout, but font/spacing/mode were only reachable
    // by first being in non-immersive mode. Royal Road instead exposes an explicit,
    // always-visible "Reader Preferences" pill so the settings are discoverable
    // without depending on the tap gesture at all.
    val showPreferences = remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // ReaderScreen's call site in MainActivity doesn't change when Previous/Next swaps
    // to a new chapter (it's still the same `is Screen.Reader ->` branch, just
    // re-invoked with a new Screen.Reader instance) - so `remember`-backed state above,
    // including scrollState, survives across chapters rather than being torn down and
    // recreated. That's the right behavior for fontSize/lineHeight/readingMode (a
    // reading preference shouldn't reset every chapter), but scroll position is
    // per-chapter: without this, landing on a new chapter kept whatever scroll offset
    // the previous chapter was left at instead of opening at the top. Reset explicitly
    // whenever the chapter actually changes.
    LaunchedEffect(chapter.id) {
        scrollState.scrollTo(0)
    }

    fun currentProgress(): Float {
        val max = scrollState.maxValue
        return if (max <= 0) 1f else (scrollState.value.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    }

    // Rough page estimate for the progress readout: ~2000 characters per "page",
    // matching a typical screenful of body text at the default font size.
    val estimatedTotalPages = remember(content) { (content.length / 2000).coerceAtLeast(1) }

    val backgroundColor = when (readingMode.value) {
        ReadingMode.LIGHT -> Color(0xFFFAF9F6)
        ReadingMode.SEPIA -> Color(0xFFF5ECD9)
        ReadingMode.DARK -> Color(0xFF1A1A1A)
    }

    val textColor = when (readingMode.value) {
        ReadingMode.LIGHT -> Color(0xFF2C2C2C)
        ReadingMode.SEPIA -> Color(0xFF3E2C1C)
        ReadingMode.DARK -> Color(0xFFF0F0F0)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                // `showControls` already existed and correctly gated both the top bar and
                // the bottom Text/Spacing/Mode panel below - but nothing ever toggled it, so
                // it was permanently stuck at its initial `true` and the controls could never
                // actually be hidden. Tapping the reading area is the standard e-reader
                // gesture for entering/leaving immersive mode, so wire it up here.
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { showControls.value = !showControls.value }
        ) {
            // Fiction title / "back to fiction" breadcrumb + arc name, matching Royal
            // Road's chapter-page top block. Only rendered when the caller has a title to
            // show - existing callers that don't pass novelTitle see no change at all.
            if (novelTitle != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    ReaderCoverThumbnail(coverUri = coverUri, modifier = Modifier.size(width = 40.dp, height = 56.dp))
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Row(
                            modifier = Modifier.clickable { onBackToFiction(currentProgress()) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.ArrowBack,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                novelTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                        if (!arcTitle.isNullOrBlank()) {
                            Text(
                                arcTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            // Chapter title
            Text(
                chapter.title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                textAlign = TextAlign.Center,
                color = textColor
            )

            // Previous/Next above the chapter body, mirroring the row below it - Royal
            // Road shows this pair on both ends of the chapter.
            ChapterNavRow(
                hasPrevious = onPrevious != null,
                hasNext = onNext != null,
                textColor = textColor,
                onPrevious = { onPrevious?.invoke(currentProgress()) },
                onNext = { onNext?.invoke(currentProgress()) }
            )

            // Chapter content with enhanced typography
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                color = backgroundColor,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = content,
                    style = TextStyle(
                        fontSize = fontSize.value.sp,
                        lineHeight = (fontSize.value * lineHeight.value).sp,
                        color = textColor,
                        fontFamily = FontFamily.Serif,
                        textAlign = TextAlign.Justify
                    ),
                    modifier = Modifier.padding(20.dp)
                )
            }

            ChapterNavRow(
                hasPrevious = onPrevious != null,
                hasNext = onNext != null,
                textColor = textColor,
                onPrevious = { onPrevious?.invoke(currentProgress()) },
                onNext = { onNext?.invoke(currentProgress()) }
            )

            // "About the author" card - only rendered when this fiction actually has a
            // linked author (see NovelEntity.authorId / AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md).
            // No author.json means no card, same as today's "no metadata.json" behavior
            // elsewhere in the scanner.
            if (author != null) {
                AboutAuthorCard(
                    author = author,
                    backgroundColor = backgroundColor,
                    textColor = textColor,
                    onClick = onAuthorClick
                )
            }

            // Bottom padding for scrolling
            Box(modifier = Modifier.padding(bottom = 100.dp))
        }

        // Top bar
        if (showControls.value) {
            TopAppBar(
                title = { Text(chapter.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { onBack(currentProgress()) }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                modifier = Modifier.background(backgroundColor)
            )
        }

        // Bottom controls: the reading-progress readout ("0% • page 1 of x") and the
        // Reader Preferences pill.
        //
        // Original bug: the pill sat *below* the progress readout inside a single
        // Column, and the readout was only added to the composition at all when
        // showControls was true. A Column packs its children directly below one
        // another, so removing the readout from composition shrank the Column - which
        // yanked the pill up/down every single time immersive mode was toggled.
        //
        // The pill needs to stay reachable in both modes (people change font/spacing/
        // mode mid-read, immersive or not), so it's the pill that should visibly move -
        // dropping down to reclaim the vertical space the readout was using once
        // immersive mode hides it, rather than sitting still while something else
        // animates around it. The readout itself is only useful in non-immersive mode
        // (nobody needs a page count while immersed), so it can just appear/disappear
        // with the mode - no animation of its own required. This is safe now, unlike
        // before: the pill has its own explicit, independently-animated offset from the
        // bottom edge, so adding/removing the readout from composition can no longer
        // move it.
        val basePillPadding = 16.dp
        val gap = 8.dp
        // Seeded with sane defaults so there's no first-frame jump before
        // onSizeChanged reports the real measured sizes.
        var pillHeight by remember { mutableStateOf(48.dp) }
        var readoutHeight by remember { mutableStateOf(32.dp) }
        val density = LocalDensity.current

        // Pill's distance from the bottom edge: raised, with room left above it for the
        // readout, in non-immersive mode; dropped all the way down to `basePillPadding`
        // once immersive mode hides the readout and that space is free to reclaim.
        val pillBottomPadding by animateDpAsState(
            targetValue = if (showControls.value) basePillPadding + readoutHeight + gap else basePillPadding,
            label = "pillBottomPadding"
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            // Reading progress readout - only relevant in non-immersive mode, so it's
            // simply not composed in immersive mode. Safe to do (unlike before): it no
            // longer shares a Column with the pill, so removing it from the tree can't
            // move anything else - only the pill's own animated offset above does that.
            if (showControls.value) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        // Sits in the gap the pill's own bottom padding (below) reserves
                        // for it - basePillPadding is the true screen-bottom offset, the
                        // same anchor the pill drops to in immersive mode. Do NOT add
                        // pillHeight/gap here: that would stack the readout above the
                        // pill instead of below it, in space nothing reserved for it.
                        .padding(bottom = basePillPadding)
                        .onSizeChanged { readoutHeight = with(density) { it.height.toDp() } }
                        .background(backgroundColor)
                        .padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 12.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        val progressPercent = (currentProgress() * 100).toInt()
                        val currentPage = (currentProgress() * estimatedTotalPages).toInt().coerceIn(1, estimatedTotalPages)
                        Text(
                            "$progressPercent% • page $currentPage of $estimatedTotalPages",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Reader Preferences: the pill + its font/spacing/mode panel. Animates
            // between the two resting offsets computed above - raised in non-immersive
            // mode, dropped down toward the bottom edge in immersive mode.
            // Alignment.BottomCenter anchors this Column's *bottom* edge, so the panel
            // opening above the pill grows the column upward without disturbing the
            // pill's own animated bottom padding.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = pillBottomPadding)
            ) {
            if (showPreferences.value) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(backgroundColor)
                        .padding(16.dp)
                ) {
                // Font size control
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Text:", style = MaterialTheme.typography.labelSmall, color = textColor)
                    IconButton(
                        onClick = { if (fontSize.value > 12) fontSize.value -= 2 },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease font", tint = textColor)
                    }
                    Text(
                        "${fontSize.value.toInt()}",
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = textColor
                    )
                    IconButton(
                        onClick = { if (fontSize.value < 28) fontSize.value += 2 },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Increase font", tint = textColor)
                    }
                }

                // Line height control
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Spacing:", style = MaterialTheme.typography.labelSmall, color = textColor)
                    Slider(
                        value = lineHeight.value,
                        onValueChange = { lineHeight.value = it },
                        valueRange = 1.0f..2.5f,
                        steps = 10,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                    Text(
                        "${String.format("%.1f", lineHeight.value)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                // Reading mode toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Mode:", style = MaterialTheme.typography.labelSmall, color = textColor)
                    ReadingMode.values().forEach { mode ->
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .padding(2.dp)
                                // The mode Surfaces had no onClick/clickable at all before this -
                                // the Light/Sepia/Dark row rendered and highlighted the active
                                // mode correctly, but tapping any of them did nothing, since
                                // nothing ever wrote to `readingMode.value`. This is what made
                                // the reading-mode toggle look broken.
                                .clickable { readingMode.value = mode },
                            color = if (readingMode.value == mode)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                when (mode) {
                                    ReadingMode.LIGHT -> Icon(
                                        Icons.Default.Brightness7,
                                        contentDescription = "Light mode",
                                        modifier = Modifier.size(16.dp),
                                        tint = if (readingMode.value == mode) Color.White else Color.Black
                                    )
                                    ReadingMode.SEPIA -> Text(
                                        "📖",
                                        fontSize = 12.sp
                                    )
                                    ReadingMode.DARK -> Icon(
                                        Icons.Default.Brightness4,
                                        contentDescription = "Dark mode",
                                        modifier = Modifier.size(16.dp),
                                        tint = if (readingMode.value == mode) Color.White else Color.Black
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }

            // The pill itself - Royal Road-style rounded button, centered, on top of
            // the progress readout both positionally and in z-order. Toggles the panel
            // above rather than navigating anywhere, so it stays a single persistent
            // affordance instead of disappearing once tapped. Its own measured height
            // feeds `pillHeight` above, so the progress readout's resting offset always
            // matches the pill's actual size (font scale, locale text length, etc.)
            // instead of a guessed constant.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { pillHeight = with(density) { it.height.toDp() } },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.clickable { showPreferences.value = !showPreferences.value },
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(50),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Aa", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
                        Text(
                            "Reader Preferences",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
            }
        }
    }
}

// Previous/Next row shown both above and below the chapter body (see Stage 3 in
// AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md). A missing neighbor renders its button disabled
// rather than hiding it, so the layout doesn't jump between the top and bottom copies
// of this row depending on which end of the chapter list you're at.
@Composable
private fun ChapterNavRow(
    hasPrevious: Boolean,
    hasNext: Boolean,
    textColor: Color,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OutlinedButton(onClick = onPrevious, enabled = hasPrevious) {
            Text("‹ Previous Chapter")
        }
        OutlinedButton(onClick = onNext, enabled = hasNext) {
            Text("Next Chapter ›")
        }
    }
}

// Truncation point for the bio excerpt shown here, vs the full bio the Stage 2
// AuthorPageScreen shows - matches the doc's "shown both truncated (chapter card) and
// in full (author page)" note on author.json's bio field.
private const val BIO_EXCERPT_LENGTH = 160

@Composable
private fun AboutAuthorCard(
    author: AuthorEntity,
    backgroundColor: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ReaderAuthorAvatar(avatarUrl = author.avatarUri, modifier = Modifier.size(48.dp))
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    "About the Author",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.6f)
                )
                Text(
                    author.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = textColor,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (!author.bio.isNullOrBlank()) {
                    Text(
                        author.bio.take(BIO_EXCERPT_LENGTH).let {
                            if (author.bio.length > BIO_EXCERPT_LENGTH) "$it…" else it
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.85f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderCoverThumbnail(coverUri: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (coverUri != null) {
            AsyncImage(
                model = coverUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Same missing-image placeholder pattern as ReaderAuthorAvatar / the rest of
            // the app - see bugs.md Bug 3b. The caller (MainActivity) is responsible for
            // resolving arc cover -> fiction cover -> null before this is reached, so a
            // null here genuinely means neither exists, not that resolution was skipped.
            Text("📕", fontSize = 20.sp)
        }
    }
}

@Composable
private fun ReaderAuthorAvatar(avatarUrl: String?, modifier: Modifier = Modifier) {
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
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Same emoji-placeholder pattern as AuthorPageScreen's AuthorAvatar / other
            // missing-image fallbacks across the app.
            Text("🖋️", fontSize = 18.sp)
        }
    }
}
