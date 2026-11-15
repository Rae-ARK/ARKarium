package com.arkarium.app.ui.reader

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkarium.app.data.ChapterEntity
import com.arkarium.app.ui.ChapterTtsState
import com.arkarium.app.ui.ReaderBottomPill
import com.arkarium.app.ui.RippleReveal
import com.arkarium.app.ui.WaterRippleWave
import com.arkarium.app.ui.ttsChunkProgressLabel

// The read-aloud controls pill (rungs 1-2 of docs/implementation/
// tts-playback-controls.md) plus the water-ripple wave that plays whenever the bottom
// edge swaps between this and the Reader Preferences pill. Pulled out of ReaderScreen's
// god-composable as a BoxScope extension - called directly inside the root Box both it
// and ReaderPageContent (ReaderContent.kt) now share (see that file's call site) -
// rather than a plain @Composable, since Modifier.align(...) below needs a BoxScope
// receiver. Pure move: no behavior change from the original inline block.
@Composable
internal fun BoxScope.ReaderTtsControlsOverlay(
    bottomPill: ReaderBottomPill,
    tts: ChapterTtsState,
    chapter: ChapterEntity,
    content: String,
    coverUri: String?,
    context: Context,
    onRequestNotificationPermission: () -> Unit,
    onOpenTtsSettings: () -> Unit
) {
    // Immersive mode only, and only while a session exists (playing or paused) -
    // bottomPill == TTS says exactly that, see ReaderTtsLogic.kt. Outside immersive
    // mode the Reader Preferences pill holds this spot, and playback simply carries on
    // without a visible control; entering immersive mode brings the pill back. Also
    // gated on tts.isAvailable inside bottomPill - no engine, nothing to control.
    RippleReveal(
        visible = bottomPill == ReaderBottomPill.TTS,
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        TtsControlsBar(
            isSpeaking = tts.isSpeaking,
            isPaused = tts.isPaused,
            currentChunkIndex = tts.currentChunkIndex,
            totalChunks = tts.totalChunks,
            onPlayPause = {
                when {
                    tts.isPaused -> tts.resume()
                    tts.isSpeaking -> tts.pause()
                    else -> {
                        onRequestNotificationPermission()
                        tts.speak(context, chapter.title, content, chapter.id, coverUri)
                    }
                }
            },
            onSkipPrevious = { tts.skipPrevious() },
            onSkipNext = { tts.skipNext() },
            onSettingsClick = onOpenTtsSettings,
            // Ends the session; with no session left, bottomPill goes to NONE and the
            // pill leaves on its own - nothing else to hide here.
            onClose = { tts.stop() }
        )
    }

    // Rings spreading from the pill whenever the bottom edge swaps between Reader
    // Preferences and read-aloud controls. Not on first composition, and not when the
    // swap is to NONE (nothing arrives to ripple around).
    WaterRippleWave(
        trigger = bottomPill,
        enabled = bottomPill != ReaderBottomPill.NONE,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(240.dp)
    )
}

// The read-aloud controls bar: stop the session, open TTS settings, skip to the
// previous/next chunk, and play/pause. Shown by ReaderTtsControlsOverlay above only in
// immersive mode while a session exists (see readerBottomPill); the speaker icon in the
// top bar is what starts one. Laid out to match the reference screenshot in uiux.md's
// playback-controls entry (close/settings on one side, skip/play/skip on the other),
// with its "n of total" scrub readout filled in at chunk granularity by
// TtsProgressIndicator below (rung 3).
@Composable
private fun TtsControlsBar(
    isSpeaking: Boolean,
    isPaused: Boolean,
    currentChunkIndex: Int,
    totalChunks: Int,
    onPlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSettingsClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(50),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Stops/ends the session outright (ChapterTtsState.stop(), which tears
                // down the service's queue) and hides this bar - distinct from
                // play/pause, which leaves the session intact.
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Stop reading aloud",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                // Opens settings/tts (TtsSettingsScreen) via onOpenTtsSettings -
                // ReaderScreen's own onOpenTtsSettings parameter, wired by MainActivity.
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Read-aloud settings",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            TtsProgressIndicator(currentChunkIndex = currentChunkIndex, totalChunks = totalChunks)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onSkipPrevious) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "Previous chunk",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                // The one control in this bar that isn't an IconButton - sized and
                // filled to stand out as the primary action, same "biggest circle in
                // the row" treatment the reference screenshot gives it.
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClick = onPlayPause),
                    color = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            if (isSpeaking && !isPaused) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isSpeaking && !isPaused) "Pause reading aloud" else "Play reading aloud",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = onSkipNext) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "Next chunk",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

// Rung 3 of docs/implementation/tts-playback-controls.md: the "n of total"
// scrub/progress readout from the uiux.md reference design, filled in at chunk
// granularity - "chunk N of M" rather than a page number, per that rung's own
// "done when" (a chunk isn't a stable reader-facing unit; chunk count depends on
// TTS_MAX_CHUNK_CHARS in TtsPlaybackService, not on anything a reader would
// recognize as a "page" - see that doc for the page-estimate alternative this
// leaves for later). Renders nothing with no session (totalChunks == 0), same
// "nothing to show" gate TtsControlsBar's own visibility already uses one level up.
@Composable
private fun TtsProgressIndicator(currentChunkIndex: Int, totalChunks: Int) {
    val label = ttsChunkProgressLabel(currentChunkIndex, totalChunks) ?: return
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
    )
}
