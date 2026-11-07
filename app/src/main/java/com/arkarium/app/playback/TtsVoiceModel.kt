package com.arkarium.app.playback

// Pure, android.speech.tts.Voice-free model for ARKarium's voice picker - rung 3 of
// docs/arkarium/implementation/tts-settings-rework.md. Voice has no public constructor,
// so code that takes one directly can't be exercised from a plain-JVM test (ARKarium
// deliberately doesn't run Robolectric - see design-notes.md's "Code philosophy" #3/#4).
// Converting a real Voice into TtsVoiceInfo lives in TtsPlaybackService.kt (which
// already imports android.speech.tts.* for other reasons, via Voice.toTtsVoiceInfo());
// everything in this file only ever sees the plain data below, so it's covered by
// TtsVoiceModelTest instead.
data class TtsVoiceInfo(
    val name: String,
    // BCP-47-ish language subtag only (e.g. "en"), not the full locale - keeping
    // language and region apart is what lets languagesOf/rowsForLanguage group
    // "English (India)" and "English (United States)" under one language (U2), rather
    // than the dialog's old one-group-per-full-locale split.
    val language: String,
    // Display region, e.g. "India"; "" if the voice's locale has none.
    val region: String,
    // One of Voice.QUALITY_VERY_LOW(100)..QUALITY_VERY_HIGH(500), passed through as a
    // plain Int so this file doesn't need the Voice class just to compare it.
    val quality: Int,
    val online: Boolean,
    val installed: Boolean
)

// The languages present in `voices`: `deviceLanguage` first if it's among them, then
// the rest alphabetically - the reference screenshots' "device's languages first"
// ordering (see tts-settings-rework.md's "What the screenshots show").
internal fun languagesOf(voices: List<TtsVoiceInfo>, deviceLanguage: String): List<String> {
    val languages = voices.map { it.language }.distinct()
    val (device, rest) = languages.partition { it == deviceLanguage }
    return device + rest.sorted()
}

// Every voice for one `language`, local voices before online ones and then by region,
// then name - stable and scannable (U2) rather than following getVoices()'s own order.
internal fun rowsForLanguage(voices: List<TtsVoiceInfo>, language: String): List<TtsVoiceInfo> =
    voices.filter { it.language == language }
        .sortedWith(compareBy({ it.online }, { it.region }, { it.name }))

// "India · online · high quality" - one line that states region, online/offline and
// quality tier the same way for both cases (U1 in TtsSettingsScreen's old
// `voiceSummary` only ever said "offline", never "online"; that function is left as-is
// here, this is only for the rung-3 picker rework).
internal fun descriptionOf(voice: TtsVoiceInfo): String {
    val parts = mutableListOf<String>()
    if (voice.region.isNotBlank()) parts.add(voice.region)
    parts.add(if (voice.online) "online" else "offline")
    parts.add(qualityLabel(voice.quality))
    return parts.joinToString(" \u00b7 ")
}

// Same five-tier >= ladder as TtsSettingsScreen.kt's own qualityLabel (Voice.quality is
// an int constant, not a bounded enum), duplicated here rather than shared so this file
// stays free of the android.speech.tts.Voice import that sharing it would pull in.
internal fun qualityLabel(quality: Int): String = when {
    quality >= 500 -> "very high quality"
    quality >= 400 -> "high quality"
    quality >= 300 -> "standard quality"
    quality >= 200 -> "low quality"
    else -> "very low quality"
}

// Voices whose data is actually downloaded (D5's static half; rung 4 is what marks or
// hides the rest in the UI - not in this ladder yet).
internal fun installedOnly(voices: List<TtsVoiceInfo>): List<TtsVoiceInfo> =
    voices.filter { it.installed }

// Rung 1's "pure helper": given the stored voice-name preference and the names actually
// available right now, the name setInitialRateAndPitch should hand to setVoice() - or
// null if there's nothing to apply (no stored name, or a stored name that doesn't
// resolve against this engine/device: an uninstalled language pack, or a changed
// engine). Kept Voice-free for the same testability reason as the rest of this file.
internal fun voiceNameToApply(storedName: String?, availableNames: List<String>): String? =
    storedName?.takeIf { it in availableNames }
