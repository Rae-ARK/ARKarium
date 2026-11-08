package com.arkarium.app.playback

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Rung 10 coverage (F22, reader-playback.md) for the decision table behind
// TtsPlaybackService's audio-focus listener. AudioManager.AUDIOFOCUS_* are compile-time
// constants, so this runs as a plain JVM test with no Android framework.
class TtsAudioFocusPolicyTest {

    @Test
    fun permanentLoss_pausesAndReleases_andNeverAutoResumes() {
        assertEquals(
            FocusAction.PAUSE_AND_RELEASE,
            focusActionFor(AudioManager.AUDIOFOCUS_LOSS, pausedForTransientLoss = false)
        )
        // Even if a transient pause was pending, a permanent loss supersedes it.
        assertEquals(
            FocusAction.PAUSE_AND_RELEASE,
            focusActionFor(AudioManager.AUDIOFOCUS_LOSS, pausedForTransientLoss = true)
        )
    }

    @Test
    fun transientLoss_pausesUntilGain() {
        assertEquals(
            FocusAction.PAUSE_UNTIL_GAIN,
            focusActionFor(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, pausedForTransientLoss = false)
        )
    }

    @Test
    fun canDuckLoss_pausesInsteadOfDucking_becauseSpeechShouldNotOverlap() {
        assertEquals(
            FocusAction.PAUSE_UNTIL_GAIN,
            focusActionFor(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                pausedForTransientLoss = false
            )
        )
    }

    @Test
    fun gain_resumesOnlyWhatTheTransientLossPaused() {
        assertEquals(
            FocusAction.RESUME,
            focusActionFor(AudioManager.AUDIOFOCUS_GAIN, pausedForTransientLoss = true)
        )
        // A deliberate pause by the reader clears the flag; a later gain must not
        // start talking again on its own.
        assertEquals(
            FocusAction.NONE,
            focusActionFor(AudioManager.AUDIOFOCUS_GAIN, pausedForTransientLoss = false)
        )
    }

    @Test
    fun unknownCode_isIgnored() {
        assertEquals(FocusAction.NONE, focusActionFor(12345, pausedForTransientLoss = true))
    }

    @Test
    fun focusDeniedMessage_mentionsAnotherApp_andCannotCollideWithEngineErrors() {
        assertTrue(ttsErrorMessage(ERROR_AUDIO_FOCUS_DENIED).contains("another app"))
        // TextToSpeech.ERROR_* are small negative ints (-1..-9); ours sits far outside.
        assertTrue(ERROR_AUDIO_FOCUS_DENIED < -100)
    }
}
