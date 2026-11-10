package com.arkarium.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// readerBlocks() and its index-lookup helpers are deliberately plain Kotlin (see
// ReaderBlocks.kt's own doc comment) so the model behind ReaderScreen's LazyColumn body
// can be tested here on the JVM - no Compose runtime, no Robolectric, no emulator.
class ReaderBlocksTest {

    // --- readerBlocks(): structure -----------------------------------------------

    @Test
    fun `untagged chapter is a single prose block with no leading blank lines`() {
        val raw = "Just one paragraph, no tags at all."
        val blocks = readerBlocks(raw)
        assertEquals(1, blocks.size)
        val prose = blocks.single() as ReaderBlock.Prose
        assertEquals(raw, prose.text)
        assertEquals(0, prose.rawStart)
        assertEquals(0, prose.blankLinesBefore)
    }

    @Test
    fun `a blank-line-separated chapter becomes one prose block per paragraph`() {
        val raw = "First paragraph.\n\nSecond paragraph."
        val blocks = readerBlocks(raw)
        assertEquals(2, blocks.size)
        val first = blocks[0] as ReaderBlock.Prose
        val second = blocks[1] as ReaderBlock.Prose
        assertEquals("First paragraph.", first.text)
        assertEquals("Second paragraph.", second.text)
        assertEquals(0, first.blankLinesBefore)
        // One ordinary blank line between paragraphs (the minimum splitBlankLines
        // requires in order to split at all) reports as 1, not 0 - see the next test
        // for what happens with an extra blank line on top of that.
        assertEquals(1, second.blankLinesBefore)
        // Every block's own substring of raw matches its text - rawStart/rawEnd stay
        // addressable into the original chapter text, same invariant ReaderBlock.Prose's
        // own doc comment promises.
        assertEquals(first.text, raw.substring(first.rawStart, first.rawEnd))
        assertEquals(second.text, raw.substring(second.rawStart, second.rawEnd))
    }

    @Test
    fun `extra blank lines between paragraphs are counted`() {
        // Two blank lines (three newlines) between the paragraphs, not one.
        val raw = "First.\n\n\nSecond."
        val blocks = readerBlocks(raw)
        assertEquals(2, blocks.size)
        assertEquals(0, (blocks[0] as ReaderBlock.Prose).blankLinesBefore)
        assertEquals(2, (blocks[1] as ReaderBlock.Prose).blankLinesBefore)
    }

    @Test
    fun `tags and break lines produce their own non-prose blocks`() {
        val raw = "First paragraph.\n\n<chapter break></chapter break>\n\n" +
            "<author>A note from the author.</author>\n\nSecond paragraph after the note."
        val blocks = readerBlocks(raw)
        assertEquals(
            listOf(
                ReaderBlock.Prose::class,
                ReaderBlock.Break::class,
                ReaderBlock.AuthorNote::class,
                ReaderBlock.Prose::class
            ),
            blocks.map { it::class }
        )
        assertEquals("A note from the author.", (blocks[2] as ReaderBlock.AuthorNote).text)
        assertEquals("Second paragraph after the note.", (blocks[3] as ReaderBlock.Prose).text)
    }

    @Test
    fun `a line of three repeated characters becomes a Break block when break-line detection is on`() {
        val raw = "Before.\n\n***\n\nAfter."
        val blocks = readerBlocks(raw, detectBreakLines = true)
        assertEquals(
            listOf(ReaderBlock.Prose::class, ReaderBlock.Break::class, ReaderBlock.Prose::class),
            blocks.map { it::class }
        )
    }

    @Test
    fun `break-line detection off leaves the marker as ordinary prose text`() {
        val raw = "Before.\n\n***\n\nAfter."
        val blocks = readerBlocks(raw, detectBreakLines = false)
        // No ChapterBreak segment at all, so this is however many prose paragraphs
        // the blank lines alone produce - "***" is just another line of text.
        assertTrue(blocks.none { it is ReaderBlock.Break })
    }

    @Test
    fun `all-markup chapter produces no prose blocks`() {
        val blocks = readerBlocks("<chapter break></chapter break>")
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is ReaderBlock.Break)
    }

    // --- readerBlocks(): CRLF (BLANK_LINE_SPLIT's "\r?", see ChapterMarkup.kt) ---

    @Test
    fun `a CRLF chapter still splits into one prose block per paragraph`() {
        // This is the actual symptom BLANK_LINE_SPLIT's "\r?" fix (ChapterMarkup.kt)
        // was for: before it, the \r sitting between a CRLF blank line's two \n's
        // broke the match, so splitBlankLines never found a break at all and the
        // whole chapter came back as one giant Prose block instead of two.
        val raw = "First paragraph.\r\n\r\nSecond paragraph."
        val blocks = readerBlocks(raw)
        assertEquals(2, blocks.size)
        val first = blocks[0] as ReaderBlock.Prose
        val second = blocks[1] as ReaderBlock.Prose
        assertEquals("First paragraph.", first.text)
        assertEquals("Second paragraph.", second.text)
        assertEquals(first.text, raw.substring(first.rawStart, first.rawEnd))
        assertEquals(second.text, raw.substring(second.rawStart, second.rawEnd))
    }

    @Test
    fun `a CRLF chapter with tags still produces one block per paragraph and tag`() {
        val raw = "First.\r\n\r\n<chapter break></chapter break>\r\n\r\nSecond."
        val blocks = readerBlocks(raw)
        assertEquals(
            listOf(ReaderBlock.Prose::class, ReaderBlock.Break::class, ReaderBlock.Prose::class),
            blocks.map { it::class }
        )
    }

    // --- readerBlockIndexForCharOffset --------------------------------------------

    @Test
    fun `readerBlockIndexForCharOffset picks the last prose block starting at or before the offset`() {
        val raw = "AAAA\n\n<chapter break></chapter break>\n\nBBBB"
        val blocks = readerBlocks(raw)
        val secondProseIndex = blocks.indexOfLast { it is ReaderBlock.Prose }
        val secondStart = (blocks[secondProseIndex] as ReaderBlock.Prose).rawStart

        assertEquals(secondProseIndex, readerBlockIndexForCharOffset(blocks, secondStart))
        assertEquals(secondProseIndex, readerBlockIndexForCharOffset(blocks, secondStart + 2))
        assertEquals(0, readerBlockIndexForCharOffset(blocks, 1))
    }

    @Test
    fun `readerBlockIndexForCharOffset falls back to the first prose block for a stale offset`() {
        val raw = "AAAA\n\nBBBB"
        val blocks = readerBlocks(raw)
        assertEquals(0, readerBlockIndexForCharOffset(blocks, -5))
    }

    @Test
    fun `readerBlockIndexForCharOffset is null when there is no prose at all`() {
        val blocks = readerBlocks("<chapter break></chapter break>")
        assertNull(readerBlockIndexForCharOffset(blocks, 0))
    }

    // --- readerAnchorBlock ---------------------------------------------------------

    @Test
    fun `readerAnchorBlock returns the block itself when it is prose`() {
        val raw = "AAAA\n\nBBBB"
        val blocks = readerBlocks(raw)
        assertEquals(blocks[1], readerAnchorBlock(blocks, 1))
    }

    @Test
    fun `readerAnchorBlock walks back to the nearest prose above a divider or callout`() {
        val raw = "AAAA\n\n<chapter break></chapter break>\n\nBBBB"
        val blocks = readerBlocks(raw)
        val breakIndex = blocks.indexOfFirst { it is ReaderBlock.Break }
        assertEquals(blocks[0], readerAnchorBlock(blocks, breakIndex))
    }

    @Test
    fun `readerAnchorBlock is null for a negative index or no prose above`() {
        val raw = "<chapter break></chapter break>\n\nAAAA"
        val blocks = readerBlocks(raw)
        assertNull(readerAnchorBlock(blocks, -1))
        assertNull(readerAnchorBlock(blocks, 0))
    }

    // --- readerBlockIndexForChunkStart ---------------------------------------------

    @Test
    fun `readerBlockIndexForChunkStart finds the paragraph a chunk starts inside`() {
        val raw = "First paragraph.\n\nSecond paragraph."
        val blocks = readerBlocks(raw)
        val secondStart = (blocks[1] as ReaderBlock.Prose).rawStart
        assertEquals(1, readerBlockIndexForChunkStart(blocks, raw, secondStart))
        assertEquals(1, readerBlockIndexForChunkStart(blocks, raw, secondStart + 3))
        assertEquals(0, readerBlockIndexForChunkStart(blocks, raw, 0))
    }

    @Test
    fun `readerBlockIndexForChunkStart attributes a start in pure whitespace to the following paragraph`() {
        val raw = "First.\n\nSecond."
        val blocks = readerBlocks(raw)
        val gapStart = raw.indexOf("\n\n")
        assertEquals(1, readerBlockIndexForChunkStart(blocks, raw, gapStart))
    }

    @Test
    fun `readerBlockIndexForChunkStart is null for a start inside markup text`() {
        val raw = "First.\n\n<author>A note.</author>\n\nSecond."
        val blocks = readerBlocks(raw)
        val markupStart = raw.indexOf("A note.")
        assertNull(readerBlockIndexForChunkStart(blocks, raw, markupStart))
    }

    @Test
    fun `readerBlockIndexForChunkStart is null for a negative start`() {
        val blocks = readerBlocks("First.\n\nSecond.")
        assertNull(readerBlockIndexForChunkStart(blocks, "First.\n\nSecond.", -1))
    }

    // --- ReaderScrollModel -----------------------------------------------------

    @Test
    fun `ReaderScrollModel reports 0 progress at the very top and 1 at the very bottom`() {
        val blocks = readerBlocks("First.\n\nSecond.\n\nThird.")
        val model = ReaderScrollModel(blocks)

        val top = ReaderItemEdge(index = 0, offset = 0, size = 100)
        val progressAtTop = model.progress(top, top, viewportStart = 0, viewportEnd = 50)
        assertEquals(0f, progressAtTop, 0.001f)

        val lastIndex = blocks.size + READER_LEADING_ITEMS
        val bottom = ReaderItemEdge(index = lastIndex, offset = 0, size = 100)
        val progressAtBottom = model.progress(bottom, bottom, viewportStart = 0, viewportEnd = 100)
        assertEquals(1f, progressAtBottom, 0.001f)
    }

    @Test
    fun `ReaderScrollModel returns 0 for an out-of-range item index`() {
        val blocks = readerBlocks("Only paragraph.")
        val model = ReaderScrollModel(blocks)
        val bogus = ReaderItemEdge(index = 999, offset = 0, size = 100)
        assertEquals(0f, model.progress(bogus, bogus, viewportStart = 0, viewportEnd = 50), 0.001f)
    }
}
