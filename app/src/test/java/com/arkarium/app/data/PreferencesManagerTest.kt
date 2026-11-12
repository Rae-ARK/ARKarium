package com.arkarium.app.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test

// Covers the "Rung 4: remaining work" gap docs/foundational/design-notes.md
// flagged for the tts_* preference reads/writes: PreferencesManager itself still takes
// a real Context, and this project deliberately has no Robolectric/instrumented test
// infra (see design-notes.md's testing strategy), so these keys can't be exercised
// through PreferencesManager directly. Instead this targets the pure, Context-free
// functions PreferencesManager.kt pulled the default-fallback logic into
// (ttsDefaultRateOf/ttsPitchOf/ttsAutoContinueOf/ttsKeepScreenOnOf), which are the exact
// functions the real ttsDefaultRate/ttsPitch/ttsAutoContinue/ttsKeepScreenOn flows map
// through - so this is coverage of the real default/key logic, not a parallel copy of
// it. androidx.datastore.preferences.core's Preferences/MutablePreferences are plain
// Kotlin types (no Context, no Android framework), so mutablePreferencesOf(...) stands
// in for "whatever DataStore.edit {} would hand the real setters" without needing a
// real DataStore instance.
class PreferencesManagerTest {

    // --- defaults, from an empty snapshot (matches a fresh install / unset keys) ------

    @Test
    fun ttsDefaultRate_withNoStoredValue_fallsBackTo1x() {
        assertEquals(1.0f, ttsDefaultRateOf(emptyPreferences()), 0.0001f)
    }

    @Test
    fun ttsPitch_withNoStoredValue_fallsBackTo1x() {
        assertEquals(1.0f, ttsPitchOf(emptyPreferences()), 0.0001f)
    }

    @Test
    fun ttsAutoContinue_withNoStoredValue_fallsBackToFalse() {
        assertEquals(false, ttsAutoContinueOf(emptyPreferences()))
    }

    @Test
    fun ttsKeepScreenOn_withNoStoredValue_fallsBackToFalse() {
        assertEquals(false, ttsKeepScreenOnOf(emptyPreferences()))
    }

    // Unlike the four above, ttsVoiceName has no Float/Boolean fallback to
    // assert against - "unset" and "engine/system default" are the same
    // null, per TTS_VOICE_NAME_KEY's doc comment - so this just confirms an
    // empty snapshot reads back as that null rather than throwing or
    // returning an empty string.
    @Test
    fun ttsVoiceName_withNoStoredValue_fallsBackToNull() {
        assertEquals(null, ttsVoiceNameOf(emptyPreferences()))
    }

    // --- write/read round-trip, using the same `prefs[KEY] = value` shape the real ----
    // --- setTts*() functions call inside their context.dataStore.edit { } block -------

    @Test
    fun ttsDefaultRate_roundTripsAStoredValue() {
        val prefs = mutablePreferencesOf()
        prefs[TTS_DEFAULT_RATE_KEY] = 1.75f

        assertEquals(1.75f, ttsDefaultRateOf(prefs), 0.0001f)
    }

    @Test
    fun ttsPitch_roundTripsAStoredValue() {
        val prefs = mutablePreferencesOf()
        prefs[TTS_PITCH_KEY] = 0.6f

        assertEquals(0.6f, ttsPitchOf(prefs), 0.0001f)
    }

    @Test
    fun ttsAutoContinue_roundTripsAStoredValue() {
        val prefs = mutablePreferencesOf()
        prefs[TTS_AUTO_CONTINUE_KEY] = true

        assertEquals(true, ttsAutoContinueOf(prefs))
    }

    @Test
    fun ttsKeepScreenOn_roundTripsAStoredValue() {
        val prefs = mutablePreferencesOf()
        prefs[TTS_KEEP_SCREEN_ON_KEY] = true

        assertEquals(true, ttsKeepScreenOnOf(prefs))
    }

    // Same round-trip shape as the four above, against setTtsVoiceName's own
    // `prefs[TTS_VOICE_NAME_KEY] = name` write (see PreferencesManager.kt) -
    // a voice name is a plain Voice.getName() string, nothing locale-shaped
    // needs parsing on the way back out.
    @Test
    fun ttsVoiceName_roundTripsAStoredValue() {
        val prefs = mutablePreferencesOf()
        prefs[TTS_VOICE_NAME_KEY] = "en-us-x-sfg#female_1-local"

        assertEquals("en-us-x-sfg#female_1-local", ttsVoiceNameOf(prefs))
    }

    // A value written and then overwritten should read back as the latest write, not
    // the default and not the first write - the shape setTtsDefaultRate's callers rely
    // on when a user drags the rate slider more than once per session.
    @Test
    fun ttsDefaultRate_secondWriteOverwritesTheFirst() {
        val prefs = mutablePreferencesOf()
        prefs[TTS_DEFAULT_RATE_KEY] = 1.75f
        prefs[TTS_DEFAULT_RATE_KEY] = 0.5f

        assertEquals(0.5f, ttsDefaultRateOf(prefs), 0.0001f)
    }

    // setTtsVoiceName(null) removes the key entirely rather than storing an
    // empty string (see its doc comment in PreferencesManager.kt) - this is
    // the pure-function side of that: a key that was present and then
    // removed must read back exactly like one that was never stored,
    // matching the "clear to System default" case VoicePickerDialog's
    // leading row triggers in TtsSettingsScreen.
    @Test
    fun ttsVoiceName_afterRemoval_fallsBackToNull() {
        val prefs = mutablePreferencesOf()
        prefs[TTS_VOICE_NAME_KEY] = "en-us-x-sfg#female_1-local"
        prefs.remove(TTS_VOICE_NAME_KEY)

        assertEquals(null, ttsVoiceNameOf(prefs))
    }

    @Test
    fun ttsVoiceName_secondWriteOverwritesTheFirst() {
        val prefs = mutablePreferencesOf()
        prefs[TTS_VOICE_NAME_KEY] = "en-us-x-sfg#female_1-local"
        prefs[TTS_VOICE_NAME_KEY] = "en-gb-x-gbb#male_1-local"

        assertEquals("en-gb-x-gbb#male_1-local", ttsVoiceNameOf(prefs))
    }

    // --- favorite voices -----------------------------

    @Test
    fun ttsFavoriteVoiceNames_withNoStoredValue_fallsBackToEmptySet() {
        assertEquals(emptySet<String>(), ttsFavoriteVoiceNamesOf(emptyPreferences()))
    }

    @Test
    fun ttsFavoriteVoiceNames_roundTripsAStoredSet() {
        val prefs = mutablePreferencesOf()
        prefs[TTS_FAVORITE_VOICES_KEY] = setOf("en-us-x-sfg#female_1-local", "en-gb-x-gbb#male_1-local")

        assertEquals(
            setOf("en-us-x-sfg#female_1-local", "en-gb-x-gbb#male_1-local"),
            ttsFavoriteVoiceNamesOf(prefs)
        )
    }

    // Mirrors toggleTtsFavoriteVoice's own read-current/flip/write-back shape
    // (PreferencesManager.kt) against the pure reader, so the "add" half of
    // that logic has JVM coverage without a real DataStore.
    @Test
    fun ttsFavoriteVoiceNames_toggleAddsAnAbsentName() {
        val prefs = mutablePreferencesOf()
        prefs[TTS_FAVORITE_VOICES_KEY] = setOf("voice-a")

        val current = ttsFavoriteVoiceNamesOf(prefs)
        prefs[TTS_FAVORITE_VOICES_KEY] = if ("voice-b" in current) current - "voice-b" else current + "voice-b"

        assertEquals(setOf("voice-a", "voice-b"), ttsFavoriteVoiceNamesOf(prefs))
    }

    @Test
    fun ttsFavoriteVoiceNames_toggleRemovesAPresentName() {
        val prefs = mutablePreferencesOf()
        prefs[TTS_FAVORITE_VOICES_KEY] = setOf("voice-a", "voice-b")

        val current = ttsFavoriteVoiceNamesOf(prefs)
        prefs[TTS_FAVORITE_VOICES_KEY] = if ("voice-a" in current) current - "voice-a" else current + "voice-a"

        assertEquals(setOf("voice-b"), ttsFavoriteVoiceNamesOf(prefs))
    }

    // --- reader-settings.md's "cheap" batch: same Context-free functions/mutablePreferencesOf ---
    // --- shape as the tts_* coverage above, against the reader_* keys PreferencesManager.kt ----
    // --- added alongside them. -------------------------------------------------------------

    @Test
    fun readerFontSize_withNoStoredValue_fallsBackTo18() {
        assertEquals(18f, readerFontSizeOf(emptyPreferences()), 0.0001f)
    }

    @Test
    fun readerLineHeight_withNoStoredValue_fallsBackTo1_8() {
        assertEquals(1.8f, readerLineHeightOf(emptyPreferences()), 0.0001f)
    }

    // Null here means "follow the app theme" (the owner's answer to
    // reader-settings.md's first open question) - same "absence is the
    // meaningful default" shape as ttsVoiceName_withNoStoredValue_fallsBackToNull.
    @Test
    fun readerReadingMode_withNoStoredValue_fallsBackToNull() {
        assertEquals(null, readerReadingModeOf(emptyPreferences()))
    }

    @Test
    fun readerFontFamily_withNoStoredValue_fallsBackToSerif() {
        assertEquals("SERIF", readerFontFamilyOf(emptyPreferences()))
    }

    @Test
    fun readerKeepScreenOn_withNoStoredValue_fallsBackToFalse() {
        assertEquals(false, readerKeepScreenOnOf(emptyPreferences()))
    }

    @Test
    fun readerFullscreen_withNoStoredValue_fallsBackToFalse() {
        assertEquals(false, readerFullscreenOf(emptyPreferences()))
    }

    @Test
    fun readerBreakLines_withNoStoredValue_fallsBackToTrue() {
        // The one reader switch that is on by default.
        assertEquals(true, readerBreakLinesOf(emptyPreferences()))
    }

    @Test
    fun readerBreakLines_roundTripsAStoredFalse() {
        val prefs = mutablePreferencesOf()
        prefs[READER_BREAK_LINES_KEY] = false

        assertEquals(false, readerBreakLinesOf(prefs))
    }

    @Test
    fun readerBrightnessSliderEnabled_withNoStoredValue_fallsBackToFalse() {
        assertEquals(false, readerBrightnessSliderEnabledOf(emptyPreferences()))
    }

    @Test
    fun readerFontSize_roundTripsAStoredValue() {
        val prefs = mutablePreferencesOf()
        prefs[READER_FONT_SIZE_KEY] = 22f

        assertEquals(22f, readerFontSizeOf(prefs), 0.0001f)
    }

    @Test
    fun readerLineHeight_roundTripsAStoredValue() {
        val prefs = mutablePreferencesOf()
        prefs[READER_LINE_HEIGHT_KEY] = 2.2f

        assertEquals(2.2f, readerLineHeightOf(prefs), 0.0001f)
    }

    // Same round-trip shape as ttsVoiceName_roundTripsAStoredValue, against
    // setReaderReadingMode's own `prefs[READER_READING_MODE_KEY] = mode.name`
    // write - the stored shape is a plain ReadingModeDefault.name string,
    // resolved back to a concrete override by the caller (ReaderScreen),
    // same "this function only reports what's stored" split as
    // ttsVoiceNameOf's own doc comment.
    @Test
    fun readerReadingMode_roundTripsAStoredValue() {
        val prefs = mutablePreferencesOf()
        prefs[READER_READING_MODE_KEY] = ReadingModeDefault.DARK.name

        assertEquals("DARK", readerReadingModeOf(prefs))
    }

    @Test
    fun readerReadingMode_afterRemoval_fallsBackToNull() {
        val prefs = mutablePreferencesOf()
        prefs[READER_READING_MODE_KEY] = ReadingModeDefault.SEPIA.name
        prefs.remove(READER_READING_MODE_KEY)

        assertEquals(null, readerReadingModeOf(prefs))
    }

    @Test
    fun readerFontFamily_roundTripsAStoredValue() {
        val prefs = mutablePreferencesOf()
        prefs[READER_FONT_FAMILY_KEY] = "DYSLEXIC"

        assertEquals("DYSLEXIC", readerFontFamilyOf(prefs))
    }

    @Test
    fun readerKeepScreenOn_roundTripsAStoredValue() {
        val prefs = mutablePreferencesOf()
        prefs[READER_KEEP_SCREEN_ON_KEY] = true

        assertEquals(true, readerKeepScreenOnOf(prefs))
    }

    @Test
    fun readerFullscreen_roundTripsAStoredValue() {
        val prefs = mutablePreferencesOf()
        prefs[READER_FULLSCREEN_KEY] = true

        assertEquals(true, readerFullscreenOf(prefs))
    }

    @Test
    fun readerBrightnessSliderEnabled_roundTripsAStoredValue() {
        val prefs = mutablePreferencesOf()
        prefs[READER_BRIGHTNESS_SLIDER_ENABLED_KEY] = true

        assertEquals(true, readerBrightnessSliderEnabledOf(prefs))
    }

    @Test
    fun readerFontSize_secondWriteOverwritesTheFirst() {
        val prefs = mutablePreferencesOf()
        prefs[READER_FONT_SIZE_KEY] = 22f
        prefs[READER_FONT_SIZE_KEY] = 14f

        assertEquals(14f, readerFontSizeOf(prefs), 0.0001f)
    }

    @Test
    fun readerBrightness_withNoStoredValue_fallsBackToFollowSystem() {
        assertEquals(-1f, readerBrightnessOf(emptyPreferences()), 0f)
    }

    @Test
    fun readerBrightness_roundTripsAStoredValue() {
        val prefs = mutablePreferencesOf()
        prefs[READER_BRIGHTNESS_KEY] = 0.4f
        assertEquals(0.4f, readerBrightnessOf(prefs), 0f)
    }
}
