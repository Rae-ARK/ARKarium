package com.arkarium.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Rung 14 (F9, docs/arkarium/proposal/reader-playback.md): paragraphOffsets() has to
// stay in lockstep with parseChapterSegments() - same Paragraph entries, same order -
// since ReaderScreen zips the two by position rather than by any shared key. These
// tests check that invariant directly (not just paragraphOffsets in isolation), plus
// paragraphForCharOffset's own restore-target rule.
class ChapterMarkupOffsetsTest {

    private fun paragraphTexts(raw: String) =
        parseChapterSegments(raw).filterIsInstance<ChapterSegment.Paragraph>().map { it.text }

    @Test
    fun matchesParseChapterSegmentsOneToOne_plainText() {
        val raw = "Just one paragraph, no tags at all."
        val offsets = paragraphOffsets(raw)
        assertEquals(paragraphTexts(raw), offsets.map { it.text })
    }

    @Test
    fun matchesParseChapterSegmentsOneToOne_withTags() {
        val raw = "First paragraph.\n\n<chapter break></chapter break>\n\n" +
            "<author>A note from the author.</author>\n\nSecond paragraph after the note."
        val offsets = paragraphOffsets(raw)
        assertEquals(paragraphTexts(raw), offsets.map { it.text })
        // Every reported start actually points at the start of that paragraph's own
        // text inside raw - not just "some" matching substring elsewhere in it.
        offsets.forEach { (start, text) ->
            assertEquals(text, raw.substring(start, start + text.length))
        }
    }

    @Test
    fun unterminatedTagKeptAsLiteralText_stillTracksOffsetCorrectly() {
        // Mirrors parseChapterSegments' own "unterminated tag" test case: the stray
        // "<author>" has no closing tag, so it's literal text folded into the single
        // paragraph that follows - paragraphOffsets must report that paragraph's
        // offset as the start of "Stray", not of the literal "<author>" text pasted
        // in front of it, since both functions build the same one Paragraph segment.
        val raw = "Stray <author> tag with no close."
        val offsets = paragraphOffsets(raw)
        assertEquals(1, offsets.size)
        assertEquals(0, offsets.first().rawStart)
        assertEquals(raw, offsets.first().text)
    }

    @Test
    fun paragraphForCharOffset_picksParagraphContainingOffset() {
        val raw = "AAAA\n\n<chapter break></chapter break>\n\nBBBB"
        val offsets = paragraphOffsets(raw)
        assertEquals(2, offsets.size)
        val secondStart = offsets[1].rawStart

        // Exactly at the second paragraph's start, and a few chars into it, both
        // resolve to the second paragraph - never falling back to the first just
        // because the offset is past its own end.
        assertEquals(offsets[1], paragraphForCharOffset(offsets, secondStart))
        assertEquals(offsets[1], paragraphForCharOffset(offsets, secondStart + 2))
        // Still inside the first paragraph's own span.
        assertEquals(offsets[0], paragraphForCharOffset(offsets, 1))
    }

    @Test
    fun paragraphForCharOffset_fallsBackToFirstWhenOffsetIsBeforeEverything() {
        val offsets = paragraphOffsets("Hello\n\n<author>note</author>\n\nWorld")
        // A stale offset from a chapter that's since been edited shorter - this
        // should never return null just because nothing starts exactly at/under 0
        // in some edge case; here it legitimately resolves to the first paragraph.
        assertTrue(paragraphForCharOffset(offsets, -5) == offsets.first())
    }

    @Test
    fun paragraphForCharOffset_nullForNoParagraphsAtAll() {
        // All markup, no plain text left over anywhere.
        val offsets = paragraphOffsets("<chapter break></chapter break>")
        assertTrue(offsets.isEmpty())
        assertNull(paragraphForCharOffset(offsets, 0))
    }
}
