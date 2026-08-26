package com.arkarium.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore by preferencesDataStore(name = "arkarium_prefs")

enum class Theme {
    LIGHT, DARK, WARM_PAPER,
    // Follows the system's day/night setting: DARK at night, and whichever of
    // LIGHT/WARM_PAPER the user picked as their preferred daytime look during the
    // day (see SYSTEM_DEFAULT_LIGHT_VARIANT_KEY below) - there's no equivalent
    // choice for the dark side since WARM_PAPER doesn't have a night counterpart.
    SYSTEM_DEFAULT
}

// The slice of PreferencesManager's surface that SettingsViewModel (Stage 2.2 of
// docs/arkarium/REFACTOR_PLAN.md's Phase 2) wraps as StateFlows. Pulled out as an
// interface - rather than SettingsViewModel depending on the concrete
// PreferencesManager class directly - purely so a JVM unit test can hand it a fake
// backed by plain MutableStateFlows instead of a real DataStore/Context, matching
// the plan's testing strategy for Phase 2 ViewModels ("using kotlinx-coroutines-test
// ... without a real dispatcher or Android framework"). PreferencesManager's other
// preferences (defaultPageSize, the splash toggles) aren't part of this interface -
// they don't belong to SettingsViewModel per the plan and stay accessed directly
// against PreferencesManager, same as before this stage.
interface SettingsPreferences {
    val theme: Flow<Theme>
    val systemDefaultLightVariant: Flow<Theme>
    val useCustomFolder: Flow<Boolean>
    val libraryUri: Flow<String?>
    suspend fun setTheme(theme: Theme)
    suspend fun setSystemDefaultLightVariant(variant: Theme)
    suspend fun setUseCustomFolder(enabled: Boolean)
    suspend fun setLibraryUri(uri: String)
}

class PreferencesManager(private val context: Context) : SettingsPreferences {
    companion object {
        private val LIBRARY_URI_KEY = stringPreferencesKey("library_uri")
        private val THEME_KEY = stringPreferencesKey("theme")
        // Only consulted while THEME_KEY resolves to SYSTEM_DEFAULT and the system is
        // currently in its light/day state - picks which of the two non-dark themes
        // (LIGHT or WARM_PAPER) stands in for "light" during the day. Stored
        // separately from THEME_KEY so switching in and out of System Default doesn't
        // lose whichever daytime look the user last chose.
        private val SYSTEM_DEFAULT_LIGHT_VARIANT_KEY = stringPreferencesKey("system_default_light_variant")
        private val DEFAULT_PAGE_SIZE_KEY = intPreferencesKey("default_page_size")
        // Opt-in flag for pointing the scanner at a user-picked SAF folder instead of the
        // app's own private storage. Defaults to false (off) so a fresh install has a
        // working library with zero permission prompts - see MainActivity.resolveLibraryRoot.
        private val USE_CUSTOM_FOLDER_KEY = booleanPreferencesKey("use_custom_folder")
        // Splash-screen behavior toggles (see SettingsScreen's "Splash Screen"
        // section). Both default to true - the line-reconstruction animation and
        // its guitar-chord score are the intended experience out of the box; these
        // exist purely as an opt-out for people who find them slow or unwanted
        // (repeat launches, sound in a quiet/public setting, etc.).
        private val SPLASH_ANIMATION_ENABLED_KEY = booleanPreferencesKey("splash_animation_enabled")
        private val SPLASH_MUSIC_ENABLED_KEY = booleanPreferencesKey("splash_music_enabled")

        // TTS default-settings keys (see docs/arkarium/SETTINGS_REDESIGN.md, Stage 3.1).
        // Added ahead of TtsSettingsScreen.kt (Stage 3.2) and ahead of anything in
        // Tts.kt actually reading them (Stages 3.3-3.5) - purely additive, nothing
        // downstream consults these yet. Same flat-key shape as the splash toggles
        // above rather than a wrapped ViewModel: see SETTINGS_REDESIGN.md's "Open
        // questions" section for why TTS defaults don't get their own ViewModel.
        private val TTS_DEFAULT_RATE_KEY = floatPreferencesKey("tts_default_rate")
        private val TTS_PITCH_KEY = floatPreferencesKey("tts_pitch")
        private val TTS_AUTO_CONTINUE_KEY = booleanPreferencesKey("tts_auto_continue")
        private val TTS_KEEP_SCREEN_ON_KEY = booleanPreferencesKey("tts_keep_screen_on")
    }

    // libraryUri.collect() and theme.collect() run unattended in MainActivity.onCreate,
    // before the user has touched anything - same situation as startScan(). DataStore's
    // .data Flow throws IOException if the prefs file on disk can't be read (corrupted
    // file, first-run race, low storage, some OEM storage quirks); without this .catch,
    // that exception propagates out of collect() and crashes the whole app on launch
    // since nothing downstream of dataStore.data was previously guarding for it. Falling
    // back to emptyPreferences() just means defaults are used for that read, matching
    // DataStore's own recommended pattern.
    private val safePrefs = context.dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    override val libraryUri: Flow<String?> = safePrefs.map { prefs ->
        prefs[LIBRARY_URI_KEY]
    }

    override val theme: Flow<Theme> = safePrefs.map { prefs ->
        when (prefs[THEME_KEY]) {
            "DARK" -> Theme.DARK
            "WARM_PAPER" -> Theme.WARM_PAPER
            "SYSTEM_DEFAULT" -> Theme.SYSTEM_DEFAULT
            else -> Theme.LIGHT
        }
    }

    // Defaults to LIGHT, same as the top-level `theme` flow's own default - a user who
    // picks System Default without ever touching this sub-choice gets plain Light
    // during the day, not Warm Paper by surprise. WARM_PAPER is the only other
    // meaningful value; anything else stored here (including DARK, which isn't a
    // valid daytime choice) also falls back to LIGHT rather than propagating a bad
    // value into colorSchemeFor.
    override val systemDefaultLightVariant: Flow<Theme> = safePrefs.map { prefs ->
        when (prefs[SYSTEM_DEFAULT_LIGHT_VARIANT_KEY]) {
            "WARM_PAPER" -> Theme.WARM_PAPER
            else -> Theme.LIGHT
        }
    }

    val defaultPageSize: Flow<Int> = safePrefs.map { prefs ->
        prefs[DEFAULT_PAGE_SIZE_KEY] ?: 10
    }

    override val useCustomFolder: Flow<Boolean> = safePrefs.map { prefs ->
        prefs[USE_CUSTOM_FOLDER_KEY] ?: false
    }

    // Default true - see SPLASH_ANIMATION_ENABLED_KEY's doc comment above.
    val splashAnimationEnabled: Flow<Boolean> = safePrefs.map { prefs ->
        prefs[SPLASH_ANIMATION_ENABLED_KEY] ?: true
    }

    // Default true - see SPLASH_MUSIC_ENABLED_KEY's doc comment above. Independent
    // of splashAnimationEnabled: a user can keep the visual reconstruction but turn
    // off sound, or vice versa.
    val splashMusicEnabled: Flow<Boolean> = safePrefs.map { prefs ->
        prefs[SPLASH_MUSIC_ENABLED_KEY] ?: true
    }

    // Seeds ChapterTtsState's initial speechRate once rememberChapterTts() is wired
    // to it (Stage 3.3) - not read anywhere yet. Default 1.0f matches the hardcoded
    // rate ChapterTtsState uses today, so wiring this up later is a no-op for anyone
    // who hasn't visited settings/tts. The pill's live setRate() during a session
    // never writes back here - see SETTINGS_REDESIGN.md's last "Open question".
    val ttsDefaultRate: Flow<Float> = safePrefs.map { prefs ->
        prefs[TTS_DEFAULT_RATE_KEY] ?: 1.0f
    }

    // Applied once at TTS engine init (Stage 3.3), no pill exposure - see
    // SETTINGS_REDESIGN.md §2 for why pitch doesn't get a mid-session control the
    // way rate does. Default 1.0f is TextToSpeech's own neutral pitch.
    val ttsPitch: Flow<Float> = safePrefs.map { prefs ->
        prefs[TTS_PITCH_KEY] ?: 1.0f
    }

    // Off by default - auto-advancing to the next chapter on read-aloud completion
    // is a behavior change from today's "stop at chapter end", so this stays opt-in
    // until a reader turns it on at settings/tts (Stage 3.4 wires the actual
    // navigation call).
    val ttsAutoContinue: Flow<Boolean> = safePrefs.map { prefs ->
        prefs[TTS_AUTO_CONTINUE_KEY] ?: false
    }

    // Off by default, same reasoning as ttsAutoContinue above - keeping the screen
    // on while TTS speaks (Stage 3.5) is a battery-life tradeoff a reader should opt
    // into rather than get silently.
    val ttsKeepScreenOn: Flow<Boolean> = safePrefs.map { prefs ->
        prefs[TTS_KEEP_SCREEN_ON_KEY] ?: false
    }

    override suspend fun setLibraryUri(uri: String) {
        context.dataStore.edit { prefs ->
            prefs[LIBRARY_URI_KEY] = uri
        }
    }

    override suspend fun setUseCustomFolder(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[USE_CUSTOM_FOLDER_KEY] = enabled
        }
    }

    override suspend fun setTheme(theme: Theme) {
        context.dataStore.edit { prefs ->
            prefs[THEME_KEY] = theme.name
        }
    }

    // `variant` is expected to be LIGHT or WARM_PAPER - DARK/SYSTEM_DEFAULT are
    // meaningless here (there's no dark or recursive-system daytime look), but this
    // deliberately doesn't validate/throw: `theme` above reads back to LIGHT for
    // any unrecognized value, so a bad write here just self-corrects on next read
    // rather than needing to be guarded against at the call site too.
    override suspend fun setSystemDefaultLightVariant(variant: Theme) {
        context.dataStore.edit { prefs ->
            prefs[SYSTEM_DEFAULT_LIGHT_VARIANT_KEY] = variant.name
        }
    }

    suspend fun setDefaultPageSize(size: Int) {
        context.dataStore.edit { prefs ->
            prefs[DEFAULT_PAGE_SIZE_KEY] = size
        }
    }

    suspend fun setSplashAnimationEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[SPLASH_ANIMATION_ENABLED_KEY] = enabled
        }
    }

    suspend fun setSplashMusicEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[SPLASH_MUSIC_ENABLED_KEY] = enabled
        }
    }

    suspend fun setTtsDefaultRate(rate: Float) {
        context.dataStore.edit { prefs ->
            prefs[TTS_DEFAULT_RATE_KEY] = rate
        }
    }

    suspend fun setTtsPitch(pitch: Float) {
        context.dataStore.edit { prefs ->
            prefs[TTS_PITCH_KEY] = pitch
        }
    }

    suspend fun setTtsAutoContinue(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[TTS_AUTO_CONTINUE_KEY] = enabled
        }
    }

    suspend fun setTtsKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[TTS_KEEP_SCREEN_ON_KEY] = enabled
        }
    }
}
