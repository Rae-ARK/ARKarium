package com.arkarium.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Coverage for the notification transport decisions (tts-playback-controls.md rung 4).
class TtsNotificationActionsTest {

    @Test
    fun whileSpeaking_middleSlotIsPause() {
        assertEquals(
            listOf(
                TtsTransportAction.PREVIOUS,
                TtsTransportAction.PAUSE,
                TtsTransportAction.NEXT,
                TtsTransportAction.STOP
            ),
            ttsTransportActions(isSpeaking = true)
        )
    }

    @Test
    fun whileNotSpeaking_middleSlotIsPlay() {
        assertEquals(TtsTransportAction.PLAY, ttsTransportActions(isSpeaking = false)[1])
    }

    @Test
    fun compactIndices_pointAtPreviousMiddleNext_andStayInRange() {
        val actions = ttsTransportActions(isSpeaking = true)
        assertTrue(TTS_COMPACT_ACTION_INDICES.all { it in actions.indices })
        assertEquals(
            listOf(TtsTransportAction.PREVIOUS, TtsTransportAction.PAUSE, TtsTransportAction.NEXT),
            TTS_COMPACT_ACTION_INDICES.map { actions[it] }
        )
    }

    @Test
    fun intentActions_roundTrip_andAreDistinct() {
        TtsTransportAction.values().forEach {
            assertEquals(it, ttsTransportActionFor(it.intentAction))
        }
        assertEquals(
            TtsTransportAction.values().size,
            TtsTransportAction.values().map { it.intentAction }.toSet().size
        )
    }

    @Test
    fun unknownOrMissingIntentAction_isNull() {
        assertNull(ttsTransportActionFor(null))
        assertNull(ttsTransportActionFor("android.intent.action.MAIN"))
    }
}
