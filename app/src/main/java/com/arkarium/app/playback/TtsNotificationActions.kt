package com.arkarium.app.playback


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
