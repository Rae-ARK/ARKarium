package com.arkarium.app.ui.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.arkarium.app.data.AuthorEntity
import com.arkarium.app.data.ReaderPosition

// The end-of-chapter block: the bottom copy of the Previous/Next row, the "About the
// Author" card (only when this fiction has a linked author), the "Send Feedback" card,
// and a trailing spacer so the last line of the chapter doesn't sit flush against the
// footer. Pulled out of ReaderScreen's god-composable as its own LazyListScope
// extension - called directly as the last item in ReaderPageContent's LazyColumn (see
// that file) - rather than a plain @Composable, since it only ever renders as a single
// LazyColumn item and has no reason to exist outside that scope. This is a pure move:
// no behavior change from the original inline `item { ... }` block.
internal fun LazyListScope.readerChapterEndItem(
    novelTitle: String?,
    author: AuthorEntity?,
    backgroundColor: Color,
    textColor: Color,
    onPrevious: ((ReaderPosition) -> Unit)?,
    onNext: ((ReaderPosition) -> Unit)?,
    onAuthorClick: () -> Unit,
    currentAnchor: () -> ReaderPosition
) {
    item {
        Column(modifier = Modifier.fillMaxWidth()) {
            ChapterNavRow(
                hasPrevious = onPrevious != null,
                hasNext = onNext != null,
                textColor = textColor,
                onPrevious = { onPrevious?.invoke(currentAnchor()) },
                onNext = { onNext?.invoke(currentAnchor()) },
                // Only offered where there's actually somewhere to send the
                // reader - the top copy of this row (above the chapter body) is
                // left as a plain disabled button at the end of a fiction, since
                // "About the Author" as the very first thing on the page would
                // read oddly before they've even reached the content.
                onAuthorClick = author?.let { { onAuthorClick() } }
            )

            // "About the author" card - only rendered when this fiction actually
            // has a linked author (see NovelEntity.authorId /
            // AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md). No author.json means no
            // card, same as today's "no metadata.json" behavior elsewhere in
            // the scanner.
            if (author != null) {
                AboutAuthorCard(
                    author = author,
                    backgroundColor = backgroundColor,
                    textColor = textColor,
                    onClick = onAuthorClick
                )
            }

            // "Send Feedback" card - the very last thing on the chapter page,
            // below the author segment above. Unconditional (unlike
            // AboutAuthorCard, which needs a linked AuthorEntity): feedback has
            // nowhere else to come from when a fiction has no author.json, so it
            // isn't gated on `author` the way the card above it is.
            FeedbackCard(
                novelTitle = novelTitle,
                backgroundColor = backgroundColor,
                textColor = textColor
            )

            // Small trailing gap so the last line of the chapter doesn't sit
            // flush against the footer below it.
            Box(modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

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

// mailto: card shown below the author segment on every chapter page (see its call
// site above). ACTION_SENDTO + a "mailto:" Uri is deliberate over ACTION_SEND: the
// latter opens the general share sheet (any app that accepts text, not just mail),
// while ACTION_SENDTO with this scheme restricts the chooser to apps that actually
// handle email - the "opens in their default mail client" behavior that was asked
// for - and nothing is sent until the reader reviews and taps send themselves inside
// it. The subject is built, not hardcoded, so each fiction's chapters prefill a
// subject naming that fiction rather than all pointing to the same generic one.
@Composable
private fun FeedbackCard(
    novelTitle: String?,
    backgroundColor: Color,
    textColor: Color
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clickable {
                // Falls back to a fiction-less subject rather than the literal string
                // "null" on the (today impossible, but not enforced by this screen's
                // own signature) chance novelTitle is missing - same "render something
                // sensible instead of the raw missing value" rule AboutAuthorCard's
                // bio-less branch above already follows.
                val subject = if (novelTitle.isNullOrBlank()) {
                    "Feedback"
                } else {
                    "Feedback [$novelTitle]"
                }
                val mailUri = Uri.parse("mailto:$FEEDBACK_EMAIL?subject=${Uri.encode(subject)}")
                val intent = Intent(Intent.ACTION_SENDTO, mailUri)
                try {
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    // No mail app installed - nothing else to fall back to here.
                    Toast.makeText(context, "No email app found", Toast.LENGTH_SHORT).show()
                }
            },
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Feedback",
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.6f)
            )
            Text(
                "Send Feedback",
                style = MaterialTheme.typography.titleSmall,
                color = textColor,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                "Spotted a typo or have thoughts on this chapter? Let us know by email.",
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// Shared avatar used by both AboutAuthorCard above and AuthorNoteCallout
// (ReaderContent.kt) - internal rather than private since it's called from that
// sibling file too.
@Composable
internal fun ReaderAuthorAvatar(avatarUrl: String?, modifier: Modifier = Modifier) {
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

// Truncation point for the bio excerpt shown here, vs the full bio the Stage 2
// AuthorPageScreen shows - matches the doc's "shown both truncated (chapter card) and
// in full (author page)" note on author.json's bio field.
private const val BIO_EXCERPT_LENGTH = 160

// Destination for the "Send Feedback" card above. A mailto: this app never sends
// anything itself for - ACTION_SENDTO just hands the reader's own mail client a
// pre-addressed, pre-subject-lined draft to review and send (or not) themselves.
private const val FEEDBACK_EMAIL = "horizonarkstudio@gmail.com"
