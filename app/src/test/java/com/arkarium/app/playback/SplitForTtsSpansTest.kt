package com.arkarium.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

// Coverage for splitForTtsSpans (tts-playback-controls.md rung 5): the chunk-index ->
// text-range mapping the "currently reading" highlight is drawn from. The one property
// that matters is that slicing the ORIGINAL text with a span gives exactly the chunk
// splitForTts returns - if that ever drifts, the highlight lands on the wrong words.
class SplitForTtsSpansTest {

    private fun sliced(text: String, maxChunk: Int, locale: Locale = Locale.US) =
        splitForTtsSpans(text, maxChunk, locale).map { text.substring(it.start, it.end) }

    private fun squash(s: String) = s.filterNot { it.isWhitespace() }

    // Checked against the text itself rather than against splitForTts (which is built on
    // the spans, so comparing the two would prove nothing): every non-whitespace
    // character is covered exactly once, in order, and no chunk is over the limit.
    private fun assertCoversTextExactly(text: String, maxChunk: Int, locale: Locale = Locale.US) {
        val chunks = sliced(text, maxChunk, locale)
        assertEquals(squash(text), squash(chunks.joinToString("")))
        assertTrue(chunks.all { it.length <= maxChunk })
    }

    @Test
    fun emptyAndBlank_returnNoSpans() {
        assertTrue(splitForTtsSpans("").isEmpty())
        assertTrue(splitForTtsSpans("  \n \t ").isEmpty())
    }

    @Test
    fun shortText_spanSkipsLeadingAndTrailingWhitespace() {
        val text = "  \nHello there.  \n"
        val spans = splitForTtsSpans(text)
        assertEquals(listOf(TtsChunkSpan(3, 15)), spans)
        assertEquals("Hello there.", text.substring(spans[0].start, spans[0].end))
    }

    @Test
    fun spans_coverTheTextExactly_underTheLimit() {
        val text = "\n\n  " + (1..400).joinToString(" ") { "This is sentence number $it." } + "  \n"
        assertCoversTextExactly(text, 200)
    }

    @Test
    fun spans_sliceBackToWhatSplitForTtsReturns() {
        // splitForTts is built on the spans, so this only guards the plumbing; the
        // behavior itself is pinned by SplitForTtsTest.
        val text = (1..60).joinToString(" ") { "Sentence $it." }
        assertEquals(splitForTts(text, 100, Locale.US), sliced(text, 100))
    }

    @Test
    fun spans_areInOrder_neverOverlap_andStayInsideTheText() {
        val text = (1..300).joinToString("\n\n") { "Paragraph $it has a sentence. And another one." }
        val spans = splitForTtsSpans(text, 150, Locale.US)
        assertTrue(spans.size > 1)
        assertTrue(spans.all { it.start >= 0 && it.end <= text.length && it.start < it.end })
        spans.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start) }
    }

    @Test
    fun spans_neverStartOrEndOnWhitespace() {
        val text = (1..200).joinToString("   ") { "Sentence $it here." }
        splitForTtsSpans(text, 120, Locale.US).forEach {
            assertTrue(!text[it.start].isWhitespace())
            assertTrue(!text[it.end - 1].isWhitespace())
        }
    }

    @Test
    fun hardCutSingleLongSentence_stillCoversTheTextExactly() {
        assertCoversTextExactly("word ".repeat(500).trim(), 100)
    }

    @Test
    fun cjkWithSurrogatePairs_coversTheTextExactly() {
        assertCoversTextExactly("你好。𠮷野家很好吃！".repeat(80), 40, Locale.JAPAN)
    }
}
