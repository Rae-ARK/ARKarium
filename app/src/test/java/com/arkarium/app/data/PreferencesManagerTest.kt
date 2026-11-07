package com.arkarium.app.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test

// Covers the "Rung 4: remaining work" gap docs/arkarium/foundational/design-notes.md
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
}
