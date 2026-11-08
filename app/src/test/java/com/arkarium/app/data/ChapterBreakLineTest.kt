package com.arkarium.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Coverage for the break-line rule in ChapterMarkup.kt: a line that is exactly one
// character repeated three times is a chapter break, with no tag needed.
class ChapterBreakLineTest {

    private fun paragraph(text: String) = ChapterSegment.Paragraph(text)
    private val divider = ChapterSegment.ChapterBreak("")

    // --- isBreakLine -----------------------------------------------------------

    @Test
    fun threeOfTheSameCharacter_isABreak() {
        listOf("***", "---", "###", "~~~", "===", "___", "+++", "•••", "⁕⁕⁕", "ooo")
            .forEach { assertTrue(it, isBreakLine(it)) }
    }

    @Test
    fun surroundingWhitespaceAndLineEndings_areIgnored() {
        assertTrue(isBreakLine("  ***  "))
        assertTrue(isBreakLine("***\r"))
        assertTrue(isBreakLine("\t---\t"))
        assertTrue(isBreakLine("\uFEFF***"))
        assertTrue(isBreakLine("\u00A0###\u00A0"))
    }

    @Test
    fun anythingElse_isNotABreak() {
        assertFalse(isBreakLine(""))
        assertFalse(isBreakLine("   "))
        assertFalse(isBreakLine("**"))
        assertFalse(isBreakLine("****"))
        assertFalse(isBreakLine("*-*"))
        assertFalse(isBreakLine("**-"))
        assertFalse(isBreakLine("* * *"))
        assertFalse(isBreakLine("a***"))
        assertFalse(isBreakLine("***a"))
        assertFalse(isBreakLine("*** *"))
    }

    @Test
    fun charactersOutsideTheBasicPlane_countAsOneCharacterEach() {
        assertTrue(isBreakLine("✨✨✨"))
        assertTrue(isBreakLine("𠮷𠮷𠮷"))
        assertFalse(isBreakLine("✨✨"))
        assertFalse(isBreakLine("✨✨✨✨"))
        assertFalse(isBreakLine("✨✨🔥"))
    }

    // --- parseChapterSegments --------------------------------------------------

    @Test
    fun breakLineBetweenParagraphs_becomesADividerSegment() {
        assertEquals(
            listOf(paragraph("Before."), divider, paragraph("After.")),
            parseChapterSegments("Before.\n\n***\n\nAfter.")
        )
        assertEquals(
            listOf(paragraph("Before."), divider, paragraph("After.")),
            parseChapterSegments("Before.\n***\nAfter.")
        )
    }

    @Test
    fun crlfLineEndings_work() {
        assertEquals(
            listOf(paragraph("A"), divider, paragraph("B")),
            parseChapterSegments("A\r\n***\r\nB")
        )
    }

    @Test
    fun breakLineAfterSeveralLinesOfText_onlySplitsAtTheBreak() {
        assertEquals(
            listOf(paragraph("one\ntwo"), divider, paragraph("three")),
            parseChapterSegments("one\ntwo\n---\nthree")
        )
    }

    @Test
    fun breakAtTheVeryStartOrEnd_isStillADivider() {
        assertEquals(listOf(divider, paragraph("Text")), parseChapterSegments("***\nText"))
        assertEquals(listOf(paragraph("Text"), divider), parseChapterSegments("Text\n***"))
        assertEquals(listOf(divider), parseChapterSegments("***"))
    }

    @Test
    fun severalBreaks_eachBecomeADivider() {
        assertEquals(
            listOf(paragraph("A"), divider, paragraph("B"), divider, paragraph("C")),
            parseChapterSegments("A\n***\nB\n---\nC")
        )
    }

    @Test
    fun threeCharactersInsideAProseLine_areNotABreak() {
        val raw = "He said *** and left.\nThen ---\n"
        assertEquals(listOf(paragraph(raw)), parseChapterSegments(raw))
    }

    @Test
    fun textWithNoBreakLine_isReturnedExactlyAsBefore() {
        val raw = "\n  Leading and trailing whitespace stays.\n\nTwo paragraphs.\n\n"
        assertEquals(listOf(paragraph(raw)), parseChapterSegments(raw))
    }

    @Test
    fun breakLinesAndTags_coexist() {
        assertEquals(
            listOf(
                paragraph("A"), divider, paragraph("B"),
                ChapterSegment.ChapterBreak(""),
                paragraph("C")
            ),
            parseChapterSegments("A\n***\nB\n<chapter break></chapter break>\nC")
        )
    }

    @Test
    fun breakLineInsideATagBody_isLeftAlone() {
        assertEquals(
            listOf(ChapterSegment.AuthorNote("Thanks!\n***\nSee you.")),
            parseChapterSegments("<author>Thanks!\n***\nSee you.</author>")
        )
    }

    // --- paragraphOffsets stay in lockstep -------------------------------------

    private fun paragraphTexts(raw: String) =
        parseChapterSegments(raw).filterIsInstance<ChapterSegment.Paragraph>().map { it.text }

    private val samples = listOf(
        "Before.\n\n***\n\nAfter.",
        "A\r\n---\r\nB\r\n###\r\nC",
        "***\nOnly after.",
        "Only before.\n~~~",
        "***",
        "One.\n***\nTwo.\n<chapter break></chapter break>\nThree.\n---\nFour.",
        "One.\n<author>note</author>\n***\nTwo.",
        "Stray <author> tag, then\n***\nmore text.",
        "No markers at all.",
        ""
    )

    @Test
    fun offsets_matchParagraphSegmentsOneToOne_withBreakLines() {
        samples.forEach { raw ->
            assertEquals(raw, paragraphTexts(raw), paragraphOffsets(raw).map { it.text })
        }
    }

    @Test
    fun offsets_addressTheSameCharactersInTheChapter() {
        samples.forEach { raw ->
            paragraphOffsets(raw).forEach {
                assertEquals(it.text, raw.substring(it.rawStart, it.rawStart + it.text.length))
            }
        }
    }

    @Test
    fun offsets_pointPastTheBreakLine() {
        val raw = "Before.\n\n***\n\nAfter."
        assertEquals(
            listOf(0, raw.indexOf("After.")),
            paragraphOffsets(raw).map { it.rawStart }
        )
    }

    // --- the Reader settings switch (detectBreakLines = false) -------------------

    @Test
    fun detectionOff_breakLinesAreOrdinaryText() {
        val raw = "Before.\n\n***\n\nAfter."
        assertEquals(listOf(paragraph(raw)), parseChapterSegments(raw, detectBreakLines = false))
    }

    @Test
    fun detectionOff_tagsStillBecomeDividers() {
        assertEquals(
            listOf(paragraph("A\n***\nB\n"), divider, paragraph("\nC")),
            parseChapterSegments("A\n***\nB\n<chapter break></chapter break>\nC", detectBreakLines = false)
        )
    }

    @Test
    fun detectionOff_offsetsStayInLockstepWithSegments() {
        samples.forEach { raw ->
            val texts = parseChapterSegments(raw, detectBreakLines = false)
                .filterIsInstance<ChapterSegment.Paragraph>().map { it.text }
            val offsets = paragraphOffsets(raw, detectBreakLines = false)
            assertEquals(raw, texts, offsets.map { it.text })
            offsets.forEach {
                assertEquals(it.text, raw.substring(it.rawStart, it.rawStart + it.text.length))
            }
        }
    }

    @Test
    fun detectionDefaultsToOn() {
        val raw = "A\n***\nB"
        assertEquals(parseChapterSegments(raw, detectBreakLines = true), parseChapterSegments(raw))
        assertEquals(paragraphOffsets(raw, detectBreakLines = true), paragraphOffsets(raw))
    }
}
