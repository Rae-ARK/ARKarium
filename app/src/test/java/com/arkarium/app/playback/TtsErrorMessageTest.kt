package com.arkarium.app.playback

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Coverage for ttsErrorMessage (design-notes.md's "A failed chunk stops the session"): every
// TextToSpeech.ERROR_* code this app can see maps to a message, and the two network
// codes specifically mention trying a local voice.
class TtsErrorMessageTest {

    @Test
    fun networkErrors_mentionLocalVoice() {
        assertTrue(ttsErrorMessage(TextToSpeech.ERROR_NETWORK).contains("local voice"))
        assertTrue(ttsErrorMessage(TextToSpeech.ERROR_NETWORK_TIMEOUT).contains("local voice"))
    }

    @Test
    fun everyKnownCode_getsADistinctNonBlankMessage() {
        val codes = listOf(
            TextToSpeech.ERROR,
            TextToSpeech.ERROR_SYNTHESIS,
            TextToSpeech.ERROR_SERVICE,
            TextToSpeech.ERROR_OUTPUT,
            TextToSpeech.ERROR_NETWORK,
            TextToSpeech.ERROR_NETWORK_TIMEOUT,
            TextToSpeech.ERROR_INVALID_REQUEST,
            TextToSpeech.ERROR_NOT_INSTALLED_YET
        )
        for (code in codes) {
            assertTrue(ttsErrorMessage(code).isNotBlank())
        }
    }

    @Test
    fun unknownCode_getsTheGenericFallback() {
        assertEquals(
            "Read-aloud stopped: a speech engine error occurred.",
            ttsErrorMessage(-12345)
        )
    }
}
