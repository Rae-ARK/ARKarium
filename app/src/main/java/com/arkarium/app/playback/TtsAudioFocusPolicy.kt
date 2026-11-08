package com.arkarium.app.playback

import android.media.AudioManager

// What TtsPlaybackService should do when Android reports an audio-focus change
// (docs/arkarium/proposal/reader-playback.md F22). Kept as a plain function over the
// raw AudioManager.AUDIOFOCUS_* ints, free of the Service, so the decision table is
// unit-testable without an emulator - same split as splitForTts / ttsErrorMessage.
internal enum class FocusAction {
    // Nothing to do (a gain with nothing paused on its account, or an unknown code).
    NONE,

    // Another app took focus for a moment (a call, a navigation prompt, a notification
    // sound that asked for exclusive focus). Pause, hold on to the focus request so the
    // matching GAIN is delivered, and resume on it.
    PAUSE_UNTIL_GAIN,

    // Another app took focus for good (music started, another player opened). Pause and
    // let go of focus; do not resume on our own - the reader presses play when ready.
    PAUSE_AND_RELEASE,

    // Focus is back after a PAUSE_UNTIL_GAIN: pick up where we left off.
    RESUME
}

// Speech is paused, never ducked, on LOSS_TRANSIENT_CAN_DUCK too. Ducking is for music
// under a short prompt; ducked narration under another app's audio is just two
// overlapping voices, and Android's own guidance for spoken-word apps is to pause.
internal fun focusActionFor(focusChange: Int, pausedForTransientLoss: Boolean): FocusAction =
    when (focusChange) {
        AudioManager.AUDIOFOCUS_LOSS -> FocusAction.PAUSE_AND_RELEASE
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusAction.PAUSE_UNTIL_GAIN
        AudioManager.AUDIOFOCUS_GAIN ->
            if (pausedForTransientLoss) FocusAction.RESUME else FocusAction.NONE
        else -> FocusAction.NONE
    }

// Passed to ChapterTtsState's error path when the session can't start because Android
// refused audio focus (typically: a phone call is active). Not a TextToSpeech.ERROR_*
// value; chosen well outside that range (all small and negative) so it can never collide.
internal const val ERROR_AUDIO_FOCUS_DENIED = -1000
