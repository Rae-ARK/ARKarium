package com.arkarium.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

// Pulled out after NovelCoverThumb (private to NovelDetailScreen.kt),
// ReaderCoverThumbnail (internal to ReaderScreen.kt, also reused directly by
// PigeonRallyScreen.kt) and NovelCardVertical's own inline Box (HomeScreen.kt) turned
// out to be three independently-written copies of the same "cover art, or a
// placeholder emoji if there is none" structure - same Box + clip + background +
// AsyncImage-or-Text shape in all three, differing only in corner radius, placeholder
// emoji/size, and (NovelCardVertical only) background tint.
//
// Deliberately takes a single, already-resolved `url` rather than a novel/arc and its
// own fallback chain - NovelCardVertical's inline version was the only one of the
// three that fell back through `novel.coverUri ?: novel.remoteCoverUrl`, which is
// exactly the kind of drift three separate copies invite (see this component's own
// extraction notes). Every caller now resolves its own fallback chain before calling
// this, so there's one less place for that logic to be duplicated or missed.
@Composable
fun CoverThumbnail(
    url: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 4.dp,
    placeholderEmoji: String = "📕",
    placeholderFontSize: TextUnit = 20.sp,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        if (url != null) {
            // Fill whatever size the caller's `modifier` already established on the
            // outer Box, rather than a fixed height - this is reused at several
            // different sizes (a full novel header cover, a per-arc cover, a 40dp
            // reader-chrome thumbnail), and a fixed height would clip/overflow at
            // every size but one.
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(placeholderEmoji, fontSize = placeholderFontSize)
        }
    }
}
