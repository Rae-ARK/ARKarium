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
}
