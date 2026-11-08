package com.arkarium.app.data

// Rung 14 (F9, docs/arkarium/proposal/reader-playback.md): the anchor ReaderScreen
// saves/restores a reading position with, instead of a scroll-pixel fraction.
// `charOffset` is an index into the chapter's raw text (always the start of some
// paragraph - see ChapterMarkup.kt's paragraphOffsets/paragraphForCharOffset, which
// ReaderScreen uses on both the capture and the restore side). `snippet` is a short
// run of the paragraph's own text starting at that offset - not currently checked
// against the live chapter body before restoring (see ReaderScreen's
// restoreReaderPosition), but kept on the row (ReadingProgressEntity.positionSnippet)
// as the fallback the proposal doc calls for, and as a human-readable sanity check
// of what a given offset actually pointed at if this ever needs debugging.
//
// Granularity note: this anchors to a *paragraph*, not a line/character within it the
// way the proposal doc's direction (TextLayoutResult.getLineForOffset/getLineTop)
// describes - each paragraph is its own Text composable (see ReaderScreen's segments
// loop), not one TextLayoutResult spanning the whole chapter body, so the line-level
// version needs per-paragraph line math this rung doesn't add. Restoring to the start
// of the right paragraph is still anchored to the text rather than a layout-dependent
// pixel fraction, which is the actual problem F9 describes (drift across font size/
// width); it just doesn't re-place the reader mid-paragraph the way scrolling to a
// literal pixel offset would have for better or worse.
// completionFraction: how far through the chapter the reader has actually scrolled
// (0f..1f, scrollState.value / scrollState.maxValue - see ReaderScreen.currentProgress()),
// NOT derived from charOffset. This is a separate axis from charOffset on purpose.
//
// Regression fixed here (reported: "continue reading bug still exists... streak and
// chapters-this-week counter didn't update", traced to the v14 schema update - the
// rung-14 CHAR_OFFSET change above): MainActivity.saveReadingProgress used to compute
// its own completion fraction as `position.charOffset.toFloat() / chapterLength` and
// compare that against ReadingStats.COMPLETION_THRESHOLD (0.9f). charOffset is
// deliberately the paragraph at the TOP of the viewport (see currentAnchor()'s doc
// comment - that's the right anchor to resume into), not how far the reader has
// scrolled through the chapter. Once the reader is scrolled all the way to the
// bottom, the paragraph sitting at the top of that final screenful is typically only
// 60-85% of the way through the chapter's raw text, not >=90%: a 5-screen chapter
// scrolled to the very bottom has its top-of-viewport paragraph around the 80% mark,
// and a short chapter can land far lower still. So the charOffset-based fraction
// almost never crossed 0.9 for an ordinarily-scrolled chapter, even when the reader
// had genuinely read to the end - silently breaking both the "chapter finished"
// haptic and the chapter_read_events row the Home screen's streak/weekly-count cards
// read. Before rung 14, `position` WAS the scroll fraction (PERCENTAGE), so this
// comparison was correct by construction; the charOffset/chapterLength substitution
// at the call site was the bug. completionFraction carries the real scroll fraction
// through independently of charOffset, so the two can each be right for what they
// measure instead of one standing in for the other.
data class ReaderPosition(val charOffset: Int, val snippet: String, val completionFraction: Float = 0f) {
    companion object {
        const val POSITION_TYPE = "CHAR_OFFSET"

        // Longer than any plausible display use, short enough to stay a "snippet"
        // rather than re-storing large chunks of the chapter per novel.
        const val SNIPPET_LENGTH = 48

        fun snippetFrom(raw: String, charOffset: Int): String =
            raw.substring(charOffset.coerceIn(0, raw.length), (charOffset + SNIPPET_LENGTH).coerceIn(0, raw.length))
    }
}
