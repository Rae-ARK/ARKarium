package com.arkarium.app.ui

import com.arkarium.app.data.ParagraphOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderTtsLogicTest {

    // --- readerBottomPill -------------------------------------------------------

    @Test
    fun nonImmersive_alwaysShowsPreferencesPill_evenWhileReadingAloud() {
        assertEquals(
            ReaderBottomPill.PREFERENCES,
            readerBottomPill(immersive = false, ttsSessionActive = false, ttsAvailable = true)
        )
        assertEquals(
            ReaderBottomPill.PREFERENCES,
            readerBottomPill(immersive = false, ttsSessionActive = true, ttsAvailable = true)
        )
    }

    @Test
    fun immersive_withActiveSession_showsTtsPill() {
        assertEquals(
            ReaderBottomPill.TTS,
            readerBottomPill(immersive = true, ttsSessionActive = true, ttsAvailable = true)
        )
    }

    @Test
    fun immersive_withoutSession_showsNothing() {
        assertEquals(
            ReaderBottomPill.NONE,
            readerBottomPill(immersive = true, ttsSessionActive = false, ttsAvailable = true)
        )
    }

    @Test
    fun immersive_withoutEngine_showsNothingEvenIfSessionFlagIsSet() {
        assertEquals(
            ReaderBottomPill.NONE,
            readerBottomPill(immersive = true, ttsSessionActive = true, ttsAvailable = false)
        )
    }

    @Test
    fun ttsPillAndPreferencesPillNeverCoexist() {
        for (immersive in listOf(true, false)) {
            for (active in listOf(true, false)) {
                for (available in listOf(true, false)) {
                    val pill = readerBottomPill(immersive, active, available)
                    // Exactly one answer per input by construction; the invariant worth
                    // pinning is the immersive/pill pairing itself.
                    if (pill == ReaderBottomPill.TTS) assertEquals(true, immersive)
                    if (pill == ReaderBottomPill.PREFERENCES) assertEquals(false, immersive)
                }
            }
        }
    }

    // --- reconcileTtsSession ----------------------------------------------------

    @Test
    fun noSession_doesNothing() {
        assertEquals(TtsSessionAction.NONE, reconcileTtsSession(false, null, "ch-1"))
        // A leftover key with no active session is not a session.
        assertEquals(TtsSessionAction.NONE, reconcileTtsSession(false, "ch-1", "ch-1"))
    }

    @Test
    fun sessionForThisChapter_returnsToImmersive() {
        // The "came back from TTS settings while playing" case.
        assertEquals(TtsSessionAction.ENTER_IMMERSIVE, reconcileTtsSession(true, "ch-1", "ch-1"))
    }

    @Test
    fun sessionForAnotherChapter_isStoppedAsStale() {
        // Previous/Next while reading aloud: new composition, old chapter's session.
        assertEquals(TtsSessionAction.STOP_STALE, reconcileTtsSession(true, "ch-1", "ch-2"))
    }

    @Test
    fun sessionWithUnknownKey_isStoppedAsStale() {
        assertEquals(TtsSessionAction.STOP_STALE, reconcileTtsSession(true, null, "ch-1"))
    }

    // --- ttsChunkProgressLabel ---------------------------------------------------

    @Test
    fun noSession_showsNoLabel() {
        // totalChunks <= 0 is the "stopped, or never started" case - nothing to show,
        // same gate readerBottomPill resolves to NONE for.
        assertEquals(null, ttsChunkProgressLabel(currentChunkIndex = 0, totalChunks = 0))
    }

    @Test
    fun firstChunk_isShownAsOneBased() {
        // currentChunkIndex is 0-based (TtsPlaybackService's pendingIndex); the label
        // is 1-based, same convention estimatedTotalPages/currentPage already use
        // elsewhere in ReaderScreen.
        assertEquals("chunk 1 of 11", ttsChunkProgressLabel(currentChunkIndex = 0, totalChunks = 11))
    }

    @Test
    fun middleChunk_isShownAtItsOwnPosition() {
        assertEquals("chunk 4 of 11", ttsChunkProgressLabel(currentChunkIndex = 3, totalChunks = 11))
    }

    @Test
    fun lastChunk_isShownAsTotal() {
        assertEquals("chunk 11 of 11", ttsChunkProgressLabel(currentChunkIndex = 10, totalChunks = 11))
    }

    @Test
    fun singleChunkChapter_isShownAsOneOfOne() {
        assertEquals("chunk 1 of 1", ttsChunkProgressLabel(currentChunkIndex = 0, totalChunks = 1))
    }

    @Test
    fun outOfRangeIndex_isClampedRatherThanShownRaw() {
        // Shouldn't happen (nothing advances pendingIndex past pendingChunks.size - 1
        // except onUtteranceFinished, which only does so transiently before the
        // service stops or auto-continues), but a defensive clamp beats "chunk 0 of
        // 11" or "chunk 13 of 11" if it ever does.
        assertEquals("chunk 11 of 11", ttsChunkProgressLabel(currentChunkIndex = 99, totalChunks = 11))
        assertEquals("chunk 1 of 11", ttsChunkProgressLabel(currentChunkIndex = -1, totalChunks = 11))
    }

    // --- ttsHighlightWithin (rung 5) --------------------------------------------

    @Test
    fun highlight_chunkInsideBlock_isLocalToTheBlock() {
        // Block starts at 100, 50 long; chunk is 110..130 of the chapter -> 10..30 in the block.
        assertEquals(TtsHighlight(10, 30), ttsHighlightWithin(100, 50, 110, 130))
    }

    @Test
    fun highlight_chunkSpillingOutEitherSide_isClippedToTheBlock() {
        assertEquals(TtsHighlight(0, 20), ttsHighlightWithin(100, 50, 80, 120))
        assertEquals(TtsHighlight(30, 50), ttsHighlightWithin(100, 50, 130, 400))
        assertEquals(TtsHighlight(0, 50), ttsHighlightWithin(100, 50, 0, 400))
    }

    @Test
    fun highlight_chunkNotTouchingBlock_isNull() {
        assertNull(ttsHighlightWithin(100, 50, 0, 100))
        assertNull(ttsHighlightWithin(100, 50, 150, 200))
    }

    @Test
    fun highlight_noChunkOrEmptyBlock_isNull() {
        assertNull(ttsHighlightWithin(0, 50, -1, -1))
        assertNull(ttsHighlightWithin(0, 50, 10, 10))
        assertNull(ttsHighlightWithin(0, 0, 0, 10))
    }

    // --- ttsBlockForChunkStart (rung 5) -----------------------------------------

    @Test
    fun blockForChunkStart_findsTheBlockContainingIt() {
        val blocks = listOf(ParagraphOffset(0, "a".repeat(40)), ParagraphOffset(60, "b".repeat(40)))
        assertEquals(blocks[0], ttsBlockForChunkStart(blocks, 0))
        assertEquals(blocks[0], ttsBlockForChunkStart(blocks, 39))
        assertEquals(blocks[1], ttsBlockForChunkStart(blocks, 60))
        assertEquals(blocks[1], ttsBlockForChunkStart(blocks, 99))
    }

    @Test
    fun blockForChunkStart_inAGapOrPastTheEnd_isNull() {
        // 40..59 is a tag the reader draws as a callout, not running text.
        val blocks = listOf(ParagraphOffset(0, "a".repeat(40)), ParagraphOffset(60, "b".repeat(40)))
        assertNull(ttsBlockForChunkStart(blocks, 45))
        assertNull(ttsBlockForChunkStart(blocks, 100))
        assertNull(ttsBlockForChunkStart(emptyList(), 0))
    }

    // --- ttsAutoScrollTarget (rung 5) -------------------------------------------

    @Test
    fun autoScroll_chunkFarBelow_scrollsToLeaveAMarginAboveIt() {
        // Viewport 1000 -> margin 100. Chunk at 5000, viewing 0 -> aim for 4900.
        assertEquals(4900, ttsAutoScrollTarget(5000f, 0, 1000, 20000))
    }

    @Test
    fun autoScroll_chunkAlreadyNearTheTop_leavesScrollAlone() {
        assertNull(ttsAutoScrollTarget(5050f, 4900, 1000, 20000))
        assertNull(ttsAutoScrollTarget(5000f, 4900, 1000, 20000))
    }

    @Test
    fun autoScroll_chunkAboveTheViewport_scrollsBackUp() {
        assertEquals(1900, ttsAutoScrollTarget(2000f, 6000, 1000, 20000))
    }

    @Test
    fun autoScroll_isClampedToTheScrollableRange() {
        assertEquals(0, ttsAutoScrollTarget(30f, 500, 1000, 20000))
        assertEquals(800, ttsAutoScrollTarget(9000f, 0, 1000, 800))
        assertNull(ttsAutoScrollTarget(9000f, 0, 1000, 0))
    }

    @Test
    fun autoScroll_beforeTheViewportIsMeasured_doesNothing() {
        assertNull(ttsAutoScrollTarget(5000f, 0, 0, 20000))
    }
}
