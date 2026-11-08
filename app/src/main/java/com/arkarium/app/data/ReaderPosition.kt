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
data class ReaderPosition(val charOffset: Int, val snippet: String) {
    companion object {
        const val POSITION_TYPE = "CHAR_OFFSET"

        // Longer than any plausible display use, short enough to stay a "snippet"
        // rather than re-storing large chunks of the chapter per novel.
        const val SNIPPET_LENGTH = 48

        fun snippetFrom(raw: String, charOffset: Int): String =
            raw.substring(charOffset.coerceIn(0, raw.length), (charOffset + SNIPPET_LENGTH).coerceIn(0, raw.length))
    }
}
