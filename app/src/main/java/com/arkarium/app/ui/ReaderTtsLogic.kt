package com.arkarium.app.ui

import com.arkarium.app.data.ParagraphOffset
import kotlin.math.abs

// Plain-Kotlin decisions behind ReaderScreen's bottom pill and its read-aloud
// re-entry handling, kept free of Compose and Android types so they can be unit-tested
// on the JVM (ReaderTtsLogicTest). ReaderScreen only feeds these its state and acts on
// the answer.

// Which pill occupies the reader's bottom edge. Exactly one at a time:
//  - PREFERENCES: the "Reader Preferences" pill. Non-immersive mode only.
//  - TTS: the read-aloud controls pill. Immersive mode only, and only while a
//    read-aloud session exists (playing or paused).
//  - NONE: immersive with nothing playing, or no usable speech engine.
enum class ReaderBottomPill { PREFERENCES, TTS, NONE }

fun readerBottomPill(
    immersive: Boolean,
    ttsSessionActive: Boolean,
    ttsAvailable: Boolean
): ReaderBottomPill = when {
    !immersive -> ReaderBottomPill.PREFERENCES
    ttsSessionActive && ttsAvailable -> ReaderBottomPill.TTS
    else -> ReaderBottomPill.NONE
}

// What ReaderScreen does about a read-aloud session it finds already running when it
// (re)attaches to TtsPlaybackService - after returning from the TTS settings page or
// any other screen, after a rotation, or after reopening the reader while a session
// kept playing in the background.
enum class TtsSessionAction {
    // No session, nothing to do.
    NONE,

    // The session is reading this very chapter: go (back) to immersive mode so its
    // controls pill is showing, and leave playback alone.
    ENTER_IMMERSIVE,

    // The session is reading some other chapter (Previous/Next, or a different
    // chapter opened while one played on). The reader is on a fresh chapter, so the
    // stale session is stopped rather than left controlling audio for text that is
    // no longer on screen.
    STOP_STALE
}

fun reconcileTtsSession(
    sessionActive: Boolean,
    sessionKey: String?,
    chapterId: String
): TtsSessionAction = when {
    !sessionActive -> TtsSessionAction.NONE
    sessionKey == chapterId -> TtsSessionAction.ENTER_IMMERSIVE
    else -> TtsSessionAction.STOP_STALE
}

// Rung 3 (docs/implementation/tts-playback-controls.md): the label
// TtsProgressIndicator (ReaderScreen.kt) shows in TtsControlsBar, at chunk
// granularity ("chunk N of M") per that rung's "done when" - not a page number; see
// the doc for why a chunk isn't a stable reader-facing unit yet. currentChunkIndex is
// 0-based (TtsPlaybackService.currentChunkIndex/ChapterTtsState.currentChunkIndex),
// shown 1-based. Null with no session (totalChunks <= 0, the same "nothing to show"
// case readerBottomPill already resolves to NONE for) - the composable renders
// nothing rather than "chunk 0 of 0".
fun ttsChunkProgressLabel(currentChunkIndex: Int, totalChunks: Int): String? {
    if (totalChunks <= 0) return null
    val current = (currentChunkIndex + 1).coerceIn(1, totalChunks)
    return "chunk $current of $totalChunks"
}

// Rung 5 (docs/implementation/tts-playback-controls.md): the "currently
// reading" highlight and its auto-scroll. A chunk's range is in the coordinates of the
// whole chapter body (TtsPlaybackService.chunkSpan), while the reader draws each stretch
// of text between markup tags as its own Text - usually one for the whole chapter, but
// not always - so these map between the two.

// A highlight inside one rendered text block, in that block's own character offsets.
data class TtsHighlight(val start: Int, val endExclusive: Int)

// The part of chunk [chunkStart, chunkEnd) that falls inside the text block starting at
// `blockStart` in the chapter body and `blockLength` characters long, as offsets local to
// that block; null when the chunk doesn't touch it (or there's no chunk: start < 0).
// Despite the parameter names, this is just a generic "clip [start, end) to this
// block" - ttsHighlightRange below reuses it unchanged for a paragraph range too.
fun ttsHighlightWithin(
    blockStart: Int,
    blockLength: Int,
    chunkStart: Int,
    chunkEnd: Int
): TtsHighlight? {
    if (chunkStart < 0 || chunkEnd <= chunkStart || blockLength <= 0) return null
    val from = maxOf(chunkStart, blockStart) - blockStart
    val to = minOf(chunkEnd, blockStart + blockLength) - blockStart
    return if (to > from) TtsHighlight(from, to) else null
}

// Rung 6 (paragraph-highlight variant, docs/implementation/
// tts-playback-controls.md): which [start, end) range the "currently reading"
// highlight should actually shade - the narrower paragraph range whenever there is one
// (paragraphStart >= 0), falling back to rung 5's whole-chunk range otherwise, which
// covers both "no session" (both ranges are -1, so this is too) and "mid-session but
// the current position isn't inside any paragraph" (an <author>/<news> callout, a
// break line - see TtsPlaybackService.currentParagraphStart's own doc comment). A
// reader never sees *less* highlight than rung 5 already gave them, only a more
// precise one when it's available.
fun ttsHighlightRange(
    paragraphStart: Int,
    paragraphEnd: Int,
    chunkStart: Int,
    chunkEnd: Int
): Pair<Int, Int> = if (paragraphStart >= 0) paragraphStart to paragraphEnd else chunkStart to chunkEnd

// The text block the chunk's first character is in - where auto-scroll should aim - or
// null when it isn't inside any (it starts in a tag the reader draws as a callout or
// divider rather than as running text), in which case the reader just doesn't scroll.
fun ttsBlockForChunkStart(blocks: List<ParagraphOffset>, chunkStart: Int): ParagraphOffset? =
    blocks.firstOrNull { chunkStart >= it.rawStart && chunkStart < it.rawStart + it.text.length }

// Where to scroll to when a new chunk starts, or null to leave the scroll alone.
// `chunkTop` is the chunk's first line, as a scroll offset (the value scrollState would
// need for that line to sit at the very top). The target leaves a tenth of the viewport
// above it, and a chunk already starting within that tenth of where it'd land isn't
// worth a nudge. Chunk-level on purpose: it follows the narration chunk by chunk and
// never fights the reader mid-chunk.
fun ttsAutoScrollTarget(
    chunkTop: Float,
    currentScroll: Int,
    viewportHeight: Int,
    maxScroll: Int
): Int? {
    if (viewportHeight <= 0) return null
    val margin = viewportHeight * 0.1f
    val desired = (chunkTop - margin).toInt().coerceIn(0, maxScroll.coerceAtLeast(0))
    return if (abs(desired - currentScroll) <= margin) null else desired
}
