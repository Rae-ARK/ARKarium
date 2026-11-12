package com.arkarium.app.playback

// Rung 4 of docs/implementation/tts-playback-controls.md: the transport buttons
// on the read-aloud notification. Kept free of android.* types (the icons and
// PendingIntents are attached in TtsPlaybackService) so the "which buttons, in what
// order, and what does a tap mean" decisions are plain-Kotlin and unit-tested, same
// shape as TtsAudioFocusPolicy.kt.

internal enum class TtsTransportAction(val intentAction: String) {
    PREVIOUS("com.arkarium.app.tts.PREVIOUS"),
    PAUSE("com.arkarium.app.tts.PAUSE"),
    PLAY("com.arkarium.app.tts.PLAY"),
    NEXT("com.arkarium.app.tts.NEXT"),
    STOP("com.arkarium.app.tts.STOP")
}

// Buttons shown, left to right. The middle one is a single slot that swaps between
// pause (while speaking) and play (paused, or otherwise not speaking), matching the
// in-app TtsControlsBar.
internal fun ttsTransportActions(isSpeaking: Boolean): List<TtsTransportAction> = listOf(
    TtsTransportAction.PREVIOUS,
    if (isSpeaking) TtsTransportAction.PAUSE else TtsTransportAction.PLAY,
    TtsTransportAction.NEXT,
    TtsTransportAction.STOP
)

// Indices into ttsTransportActions() kept visible when the notification is collapsed:
// previous, play/pause, next. Stop only appears once expanded.
internal val TTS_COMPACT_ACTION_INDICES = intArrayOf(0, 1, 2)

// Maps the Intent action of a tapped notification button back to what it means;
// null for anything that isn't one of ours (including a plain start with no action).
internal fun ttsTransportActionFor(intentAction: String?): TtsTransportAction? =
    TtsTransportAction.values().firstOrNull { it.intentAction == intentAction }
