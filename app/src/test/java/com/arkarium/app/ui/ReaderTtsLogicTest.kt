package com.arkarium.app.ui

import org.junit.Assert.assertEquals
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
}
