package com.arkarium.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

// Pure-function coverage for splitForTts (F11, engineering-review.md): no chunk over the
// limit, no text lost or duplicated, no cut inside a surrogate pair, and a CJK-only
// chapter actually gets split.
class SplitForTtsTest {

    private fun squash(s: String) = s.filterNot { it.isWhitespace() }

    private fun assertLossless(original: String, chunks: List<String>) {
        assertEquals(squash(original), squash(chunks.joinToString("")))
    }

    @Test
    fun emptyAndBlank_returnNoChunks() {
        assertTrue(splitForTts("").isEmpty())
        assertTrue(splitForTts("   \n  ").isEmpty())
    }

    @Test
    fun shortText_isOneTrimmedChunk() {
        assertEquals(listOf("Hello there."), splitForTts("  Hello there.  "))
    }

    @Test
    fun longEnglish_noChunkExceedsLimit_andNothingIsLost() {
        val text = (1..400).joinToString(" ") { "This is sentence number $it." }
        val chunks = splitForTts(text, maxChunk = 200, locale = Locale.US)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 200 })
        assertLossless(text, chunks)
    }

    @Test
    fun longEnglish_chunksEndOnSentenceBoundaries() {
        val text = (1..400).joinToString(" ") { "This is sentence number $it." }
        val chunks = splitForTts(text, maxChunk = 200, locale = Locale.US)
        assertTrue(chunks.all { it.endsWith(".") })
    }

    @Test
    fun decimalPoint_isNotTreatedAsSentenceEnd() {
        val text = (1..300).joinToString(" ") { "The value is 3.14 here and again $it." }
        val chunks = splitForTts(text, maxChunk = 120, locale = Locale.US)
        // Every chunk ends at the real sentence end ("... again 7."), never at the
        // decimal point inside "3.14".
        assertTrue(chunks.all { Regex("again \\d+\\.$").containsMatchIn(it) })
        assertLossless(text, chunks)
    }

    @Test
    fun cjkOnly_withIdeographicPunctuation_producesMultipleChunks() {
        val text = (1..300).joinToString("") { "今日は天気がいいです。明日は雨でしょう！本当ですか？" }
        val chunks = splitForTts(text, maxChunk = 100, locale = Locale.JAPAN)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 100 })
        assertLossless(text, chunks)
        // Broken at the CJK terminators, not mid-sentence.
        assertTrue(chunks.all { it.last() in "。！？" })
    }

    @Test
    fun singleOverlongSentence_isHardCut_withoutSplittingSurrogatePairs() {
        // One "sentence" with no terminator at all, made of supplementary-plane emoji
        // (two UTF-16 chars each) - the old String-index cut could land between halves.
        val text = "\uD83D\uDE00".repeat(500)
        val chunks = splitForTts(text, maxChunk = 101, locale = Locale.US)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 101 })
        for (chunk in chunks) {
            assertFalse("chunk starts with a lone low surrogate", Character.isLowSurrogate(chunk.first()))
            assertFalse("chunk ends with a lone high surrogate", Character.isHighSurrogate(chunk.last()))
        }
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun singleOverlongSentence_prefersWhitespaceForTheCut() {
        val text = "word ".repeat(200).trim()
        val chunks = splitForTts(text, maxChunk = 50, locale = Locale.US)
        assertTrue(chunks.all { it.length <= 50 })
        // Cutting at spaces means no chunk ends mid-"word".
        assertTrue(chunks.all { it.split(" ").all { w -> w == "word" } })
    }
}
