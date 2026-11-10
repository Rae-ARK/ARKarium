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

    // --- paragraphContaining (rung 6, tts-playback-controls.md) -----------------

    @Test
    fun paragraphContaining_findsTheParagraphThatActuallyHoldsTheOffset() {
        val raw = "AAAA\n\n<chapter break></chapter break>\n\nBBBB"
        val offsets = paragraphOffsets(raw)
        assertEquals(2, offsets.size)
        val secondStart = offsets[1].rawStart

        assertEquals(offsets[0], paragraphContaining(offsets, 0))
        assertEquals(offsets[0], paragraphContaining(offsets, 3))
        assertEquals(offsets[1], paragraphContaining(offsets, secondStart))
        assertEquals(offsets[1], paragraphContaining(offsets, secondStart + 3))
    }

    @Test
    fun paragraphContaining_nullOneCharPastTheEnd() {
        // One character past a paragraph's own end is outside it (rawStart +
        // text.length is exclusive) - unlike paragraphForCharOffset, this never
        // falls back to "the nearest paragraph anyway".
        val offsets = paragraphOffsets("AAAA\n\n<chapter break></chapter break>\n\nBBBB")
        val firstEnd = offsets[0].rawStart + offsets[0].text.length
        assertNull(paragraphContaining(offsets, firstEnd))
    }

    @Test
    fun paragraphContaining_nullInAGapBetweenParagraphs() {
        // The markup-tagged stretch between the two paragraphs isn't covered by
        // either's range - this is the "mid-callout" case rung 6's highlight falls
        // back to the chunk range for.
        val raw = "AAAA\n\n<chapter break></chapter break>\n\nBBBB"
        val offsets = paragraphOffsets(raw)
        val gapOffset = raw.indexOf("<chapter break>")
        assertNull(paragraphContaining(offsets, gapOffset))
    }

    @Test
    fun paragraphContaining_nullForEmptyOffsets() {
        assertNull(paragraphContaining(emptyList(), 0))
    }

    // --- proseParagraphOffsets (rung 6, tts-playback-controls.md) ---------------

    @Test
    fun proseParagraphOffsets_noBlankLines_matchesParagraphOffsets() {
        // A markup-delimited block with no internal blank line is already a single
        // prose paragraph - same result as paragraphOffsets itself.
        val raw = "Just one paragraph, no tags at all."
        assertEquals(paragraphOffsets(raw), proseParagraphOffsets(raw))
    }

    @Test
    fun proseParagraphOffsets_splitsABlockOnBlankLines() {
        // paragraphOffsets sees this as a single block (no markup tags anywhere),
        // but it's two prose paragraphs separated by a blank line.
        val raw = "First paragraph.\n\nSecond paragraph."
        val block = paragraphOffsets(raw)
        assertEquals(1, block.size)

        val offsets = proseParagraphOffsets(raw)
        assertEquals(listOf("First paragraph.", "Second paragraph."), offsets.map { it.text })
        // Offsets stay in raw's own coordinate space, same as paragraphOffsets.
        offsets.forEach { (start, text) -> assertEquals(text, raw.substring(start, start + text.length)) }
    }

    @Test
    fun proseParagraphOffsets_splitsEveryMarkupBlockIndependently() {
        // paragraphOffsets() doesn't cover a tag's own inner text at all (that's
        // AuthorNote/News territory, not plain-text blocks - see paragraphContaining's
        // doc comment), so the <author> content here contributes no paragraph; the
        // plain-text block on either side of it is still split on its own blank line.
        val raw = "Para one.\n\nPara two.\n\n<author>A note.\n\nSecond note line.</author>\n\nPara three."
        val offsets = proseParagraphOffsets(raw)
        assertEquals(listOf("Para one.", "Para two.", "Para three."), offsets.map { it.text })
        offsets.forEach { (start, text) -> assertEquals(text, raw.substring(start, start + text.length)) }
    }

    @Test
    fun proseParagraphOffsets_softWrapWithinAParagraphIsNotASplit() {
        // A lone newline (no blank line) is soft-wrapped prose, not a paragraph break.
        val raw = "Line one\nstill the same paragraph.\n\nA new paragraph."
        val offsets = proseParagraphOffsets(raw)
        assertEquals(listOf("Line one\nstill the same paragraph.", "A new paragraph."), offsets.map { it.text })
    }

    @Test
    fun proseParagraphOffsets_toleratesWhitespaceOnlyBlankLines() {
        // Trailing spaces on an otherwise-blank line still count as blank.
        val raw = "First.\n   \t \nSecond."
        val offsets = proseParagraphOffsets(raw)
        assertEquals(listOf("First.", "Second."), offsets.map { it.text })
    }

    @Test
    fun proseParagraphOffsets_emptyForNoProseAtAll() {
        assertTrue(proseParagraphOffsets("<chapter break></chapter break>").isEmpty())
        assertTrue(proseParagraphOffsets("").isEmpty())
    }

    // --- CRLF handling (BLANK_LINE_SPLIT's "\r?" - see its own doc comment) -----

    @Test
    fun proseParagraphOffsets_splitsOnCrlfBlankLines() {
        // Before BLANK_LINE_SPLIT accounted for "\r", the lone \r sitting between the
        // two \n's of a CRLF blank line ("...line1\r\n\r\npara2...") broke the match
        // entirely, so a CRLF chapter file never split into separate paragraphs at
        // all - this whole raw string came back as one block. Matches the plain-\n
        // case in proseParagraphOffsets_splitsABlockOnBlankLines above, just with
        // every newline in the source as \r\n instead of \n.
        val raw = "First paragraph.\r\n\r\nSecond paragraph."
        val block = paragraphOffsets(raw)
        assertEquals(1, block.size)

        val offsets = proseParagraphOffsets(raw)
        assertEquals(listOf("First paragraph.", "Second paragraph."), offsets.map { it.text })
        offsets.forEach { (start, text) -> assertEquals(text, raw.substring(start, start + text.length)) }
    }

    @Test
    fun proseParagraphOffsets_crlfWithTrailingWhitespaceOnTheBlankLine() {
        // The CRLF equivalent of toleratesWhitespaceOnlyBlankLines above: a blank
        // line that's "\r\n   \t \r\n" (trailing spaces before the second \r\n)
        // still counts as blank and still splits.
        val raw = "First.\r\n   \t \r\nSecond."
        assertEquals(listOf("First.", "Second."), proseParagraphOffsets(raw).map { it.text })
    }

    @Test
    fun proseParagraphOffsets_crlfSoftWrapWithinAParagraphIsNotASplit() {
        // A lone \r\n (no blank line) is still just a soft-wrapped line within one
        // paragraph, same as a lone \n is for LF text.
        val raw = "Line one\r\nstill the same paragraph.\r\n\r\nA new paragraph."
        val offsets = proseParagraphOffsets(raw)
        assertEquals(listOf("Line one\r\nstill the same paragraph.", "A new paragraph."), offsets.map { it.text })
    }
}
