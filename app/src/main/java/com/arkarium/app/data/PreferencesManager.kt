package com.arkarium.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore by preferencesDataStore(name = "arkarium_prefs")

// TTS default-settings keys (see docs/foundational/design-notes.md, Stage 3.1).
// File-scoped (internal, not inside PreferencesManager's companion object) so the four
// pure functions below - and PreferencesManagerTest - can share them without needing a
// Context or a real DataStore. Same flat-key shape as the splash toggles rather than a
// wrapped ViewModel: see design-notes.md's "Settled decisions" for why TTS defaults
// don't get their own ViewModel.
internal val TTS_DEFAULT_RATE_KEY = floatPreferencesKey("tts_default_rate")
internal val TTS_PITCH_KEY = floatPreferencesKey("tts_pitch")
internal val TTS_AUTO_CONTINUE_KEY = booleanPreferencesKey("tts_auto_continue")
internal val TTS_KEEP_SCREEN_ON_KEY = booleanPreferencesKey("tts_keep_screen_on")
// The chosen voice (design-notes.md's "TTS settings split").
// Stores Voice.getName() (not a Locale) since the name alone is what
// TtsPlaybackService.setVoice() needs to re-resolve a Voice out of
// TextToSpeech.getVoices() - see that proposal's "New preference key"
// section. File-scoped alongside the four keys above (not inside
// PreferencesManager's companion object, where it originally lived) so
// ttsVoiceNameOf below and PreferencesManagerTest can both reach it without
// a Context, same reasoning as those four.
internal val TTS_VOICE_NAME_KEY = stringPreferencesKey("tts_voice_name")

// Favorite voices (design-notes.md's "TTS settings split"). A plain
// Set<String> of Voice.getName() values - same name shape TTS_VOICE_NAME_KEY
// already stores one of, so no new "how do I identify a voice" format is
// introduced. File-scoped alongside the other tts_* keys for the same
// JVM-testable, Context-free reason as those.
internal val TTS_FAVORITE_VOICES_KEY = stringSetPreferencesKey("tts_favorite_voices")

// docs/proposal/reader-settings.md, suggested-order items 1-5 ("the
// cheap ones"): persisted reader defaults + two reader-level switches + one
// pill-visibility opt-in. File-scoped alongside the tts_* keys above for the
// same Context-free, JVM-testable reason. Multi-document, the day/night
// button, the volume slider and column count are intentionally NOT here -
// the doc's suggested order puts them after this batch, so they stay
// documented-only for a later pass (see reader-settings.md's "Open
// questions" and its own order list).
//
// READER_READING_MODE_KEY is a nullable String rather than a ReadingMode
// with a hardcoded fallback: "unset" has to keep meaning "follow the app
// theme" (the owner's answer to the doc's first open question), which only
// a genuinely-absent key can represent cleanly - a non-null default would
// collapse "never chose" and "chose to pin Light" into the same state.
internal val READER_FONT_SIZE_KEY = floatPreferencesKey("reader_font_size")
internal val READER_LINE_HEIGHT_KEY = floatPreferencesKey("reader_line_height")
internal val READER_READING_MODE_KEY = stringPreferencesKey("reader_reading_mode")
// "Set and forget" per the doc's Font family section - moved out of the
// Reader Preferences pill entirely, so unlike the three keys above this
// isn't a *default* the pill seeds from and can drift away from live; it's
// the only place the choice is made at all now.
internal val READER_FONT_FAMILY_KEY = stringPreferencesKey("reader_font_family")
// Reader-level keep-screen-on, separate from TTS_KEEP_SCREEN_ON_KEY above -
// the two combine (either one true keeps the flag set) rather than one
// overriding the other, see ReaderScreen's DisposableEffect. Plain
// FLAG_KEEP_SCREEN_ON, not the idle-timer variant the doc flagged as the
// better-but-bigger option - the owner picked the plain flag for this pass.
internal val READER_KEEP_SCREEN_ON_KEY = booleanPreferencesKey("reader_keep_screen_on")
internal val READER_FULLSCREEN_KEY = booleanPreferencesKey("reader_fullscreen")
// Whether a line of one character three times over ("***", "---", "###") is drawn as a
// chapter-break divider (data/ChapterMarkup.kt's isBreakLine). On by default - the one
// switch here that is: it is the owner's own writing convention, and the <chapter break>
// tag keeps working either way. Off makes those lines ordinary text again.
internal val READER_BREAK_LINES_KEY = booleanPreferencesKey("reader_break_lines")
// Not in the doc's own "Brightness" section (which argued no setting is
// needed) - added per the owner's explicit instruction that brightness (and
// later, volume) are opt-in and only show as a pill slider once turned on
// here. Off by default, same "no behavior change until opted into" posture
// as every other switch in this file.
internal val READER_BRIGHTNESS_SLIDER_ENABLED_KEY = booleanPreferencesKey("reader_brightness_slider_enabled")
// The level that override applies while the reader is open. The in-reader pill panel
// that used to hold a session-only brightness slider was removed (every reader setting
// now lives on the Reader settings page), so the level has to be stored somewhere: here.
// Meaningful only while the switch above is on; -1f = "follow the system".
internal val READER_BRIGHTNESS_KEY = floatPreferencesKey("reader_brightness")

internal fun readerFontSizeOf(prefs: Preferences): Float =
    prefs[READER_FONT_SIZE_KEY] ?: 18f

internal fun readerLineHeightOf(prefs: Preferences): Float =
    prefs[READER_LINE_HEIGHT_KEY] ?: 1.8f

// Null means "follow the app theme", resolved by the caller (ReaderScreen,
// via readingModeFor(appTheme)) exactly as it is today for a never-visited
// Settings page - this function only reports what's stored, not the
// resolved mode, so it stays Context/Theme-free like the rest of this file.
internal fun readerReadingModeOf(prefs: Preferences): String? =
    prefs[READER_READING_MODE_KEY]

internal fun readerFontFamilyOf(prefs: Preferences): String =
    prefs[READER_FONT_FAMILY_KEY] ?: "SERIF"

internal fun readerKeepScreenOnOf(prefs: Preferences): Boolean =
    prefs[READER_KEEP_SCREEN_ON_KEY] ?: false

internal fun readerFullscreenOf(prefs: Preferences): Boolean =
    prefs[READER_FULLSCREEN_KEY] ?: false

internal fun readerBreakLinesOf(prefs: Preferences): Boolean =
    prefs[READER_BREAK_LINES_KEY] ?: true

internal fun readerBrightnessSliderEnabledOf(prefs: Preferences): Boolean =
    prefs[READER_BRIGHTNESS_SLIDER_ENABLED_KEY] ?: false

internal fun readerBrightnessOf(prefs: Preferences): Float =
    prefs[READER_BRIGHTNESS_KEY] ?: -1f

// Rung 4 of docs/foundational/design-notes.md: the default-fallback logic for
// the four tts_* keys, pulled out of PreferencesManager's property getters into plain
// top-level functions over a raw Preferences snapshot - same "kept free of Context so it
// can be unit-tested on the JVM" move as ui/ReaderTtsLogic.kt, just one layer further
// down the stack. PreferencesManager's flows below call these directly, so there is one
// source of truth for each default; PreferencesManagerTest exercises them (and the
// matching setters' write shape) against a plain mutablePreferencesOf(...)/emptyPreferences(),
// no Context or DataStore instance involved.
internal fun ttsDefaultRateOf(prefs: Preferences): Float =
    prefs[TTS_DEFAULT_RATE_KEY] ?: 1.0f

internal fun ttsPitchOf(prefs: Preferences): Float =
    prefs[TTS_PITCH_KEY] ?: 1.0f

internal fun ttsAutoContinueOf(prefs: Preferences): Boolean =
    prefs[TTS_AUTO_CONTINUE_KEY] ?: false

internal fun ttsKeepScreenOnOf(prefs: Preferences): Boolean =
    prefs[TTS_KEEP_SCREEN_ON_KEY] ?: false

// The voice and favorites readers, added alongside the
// four tts_* functions above rather than left as an inline prefs[KEY] read
// on the ttsVoiceName flow below - same reason as those four: a
// Context/DataStore-free function PreferencesManagerTest can exercise
// directly against mutablePreferencesOf()/emptyPreferences(). Default null
// ("engine/system default") is TTS_VOICE_NAME_KEY's own documented fallback
// on the flow this backs; there's no Float/Boolean default to fall back to
// here the way the other three have.
internal fun ttsVoiceNameOf(prefs: Preferences): String? =
    prefs[TTS_VOICE_NAME_KEY]

// Rung 5: empty set (no favorites) is the unset default, same "absence means
// the harmless case" posture as the rest of this file. DataStore returns an
// immutable Set from a stringSetPreferencesKey read; toggleTtsFavoriteVoice
// below never mutates it in place, only writes a fresh `current - name` or
// `current + name` set back.
internal fun ttsFavoriteVoiceNamesOf(prefs: Preferences): Set<String> =
    prefs[TTS_FAVORITE_VOICES_KEY] ?: emptySet()

enum class Theme {
    LIGHT, DARK, WARM_PAPER,
    // Follows the system's day/night setting: DARK at night, and whichever of
    // LIGHT/WARM_PAPER the user picked as their preferred daytime look during the
    // day (see SYSTEM_DEFAULT_LIGHT_VARIANT_KEY below) - there's no equivalent
    // choice for the dark side since WARM_PAPER doesn't have a night counterpart.
    SYSTEM_DEFAULT
}

// The slice of PreferencesManager's surface that SettingsViewModel (Stage 2.2 of
// docs/foundational/design-notes.md's Phase 2) wraps as StateFlows. Pulled out as an
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
    // Only meaningful while the resolved theme is Theme.DARK (see AppTheme.applyAmoledMode)
    // - stored independently of `theme` itself so switching away from and back to Dark
    // doesn't lose the choice, same reasoning as systemDefaultLightVariant above.
    val amoledModeEnabled: Flow<Boolean>
    suspend fun setTheme(theme: Theme)
    suspend fun setSystemDefaultLightVariant(variant: Theme)
    suspend fun setAmoledModeEnabled(enabled: Boolean)
}

// Library Settings screen state the UI reads in one shot rather than as four separate
// StateFlows - see PreferencesManager.lastScanInfo below. Null (rather than a sentinel
// Long) for "no scan has completed yet this install", which is the one case the
// screen's "Last scanned ..." row actually needs to render differently (no row at all)
// rather than a fabricated "0 seconds ago".
data class LastScanInfo(val completedAtMillis: Long, val durationMillis: Long)

class PreferencesManager(private val context: Context) : SettingsPreferences {
    companion object {
        private val THEME_KEY = stringPreferencesKey("theme")
        // Only consulted while THEME_KEY resolves to SYSTEM_DEFAULT and the system is
        // currently in its light/day state - picks which of the two non-dark themes
        // (LIGHT or WARM_PAPER) stands in for "light" during the day. Stored
        // separately from THEME_KEY so switching in and out of System Default doesn't
        // lose whichever daytime look the user last chose.
        private val SYSTEM_DEFAULT_LIGHT_VARIANT_KEY = stringPreferencesKey("system_default_light_variant")
        // Dark-theme-only "true black" mode (AppTheme.applyAmoledMode) - off by default,
        // same "no behavior change until opted into" posture as the rest of this file.
        private val AMOLED_MODE_KEY = booleanPreferencesKey("amoled_mode_enabled")
        private val DEFAULT_PAGE_SIZE_KEY = intPreferencesKey("default_page_size")
        // Library Settings screen (see ui/LibrarySettingsScreen.kt). The old
        // USE_CUSTOM_FOLDER_KEY/HAS_SHOWN_SAF_REMOVED_NOTICE_KEY pair - the opt-in flag
        // for a user-picked SAF folder, and the one-time "that was removed" notice it
        // drove - has been dropped entirely now that the notice has shipped for a
        // while: the library root is permanently the fixed Android/media/
        // com.arkarium.app/ folder (data/LibraryScan.kt's resolveLibraryRoot), so
        // there's nothing left to migrate away from.
        //
        // Default true: scanning on every cold launch is today's actual behavior
        // (MainActivity.onCreate), so an upgrading install must keep seeing it happen
        // without needing to opt back in - only a fresh switch to `false` changes
        // anything.
        private val AUTO_SCAN_ON_LAUNCH_KEY = booleanPreferencesKey("auto_scan_on_launch")
        // Gates LibraryScanWorker's periodic background rescan (data/LibraryScanWorker.kt)
        // - off by default, same "opt into a new background behavior" posture as
        // ttsAutoContinue/ttsKeepScreenOn above. Unlike auto-scan-on-launch, a periodic
        // background scan is new work WorkManager wakes the process for, so it shouldn't
        // start happening to an existing install just because this key rolled out.
        private val BACKGROUND_SCAN_ENABLED_KEY = booleanPreferencesKey("background_scan_enabled")
        // Gates the FileObserver-backed watcher (data/LibraryFileWatcher.kt) that
        // triggers a rescan as soon as a file changes under the library root, instead of
        // waiting for the next launch/periodic pass. Off by default - same reasoning as
        // BACKGROUND_SCAN_ENABLED_KEY, plus it only ever runs while the process is alive
        // (see that file's doc comment), so it's an opt-in "keep ARKarium open and it'll
        // notice" convenience rather than something to turn on silently for everyone.
        private val SCAN_ON_FILE_CHANGE_ENABLED_KEY = booleanPreferencesKey("scan_on_file_change_enabled")
        // Restricts NewChapterCheckWorker's periodic "new chapter" check to an unmetered
        // (Wi-Fi) network instead of any connected one - see that worker's schedule().
        // Off by default: the check is a small manifest fetch per followed novel, not
        // bulk data, so defaulting to "any network" matches today's actual behavior for
        // an upgrading install.
        private val WIFI_ONLY_SYNC_KEY = booleanPreferencesKey("wifi_only_sync")
        // "Last scanned ..." row (LastScanInfo above) - written by both the interactive
        // scan path (LibraryViewModel.startScan) and the background one
        // (LibraryScanWorker), so either one keeps this current regardless of which
        // triggered the most recent scan.
        private val LAST_SCAN_COMPLETED_AT_KEY = longPreferencesKey("last_scan_completed_at")
        private val LAST_SCAN_DURATION_MS_KEY = longPreferencesKey("last_scan_duration_ms")
        // Splash-screen behavior toggles (see SettingsScreen's "Splash Screen"
        // section). Both default to true - the line-reconstruction animation
        // and its guitar-chord score are the intended out-of-the-box
        // experience (the chord is scored specifically to the whoosh-and-cross
        // animation; muting it by default would undercut the thing it's
        // syncing with). D6 (docs/proposal/performance.md, F13) originally
        // called for the chord off by default to sidestep audio-focus work -
        // revised back to on-by-default, since it only plays once per
        // install/update anyway (see LAST_FULL_SPLASH_VERSION_CODE_KEY below)
        // and "no audio focus" is a narrower, acceptable gap for a one-off
        // decorative sound than losing the music entirely. Both remain
        // independent opt-outs for repeat launches / a quiet or public
        // setting.
        private val SPLASH_ANIMATION_ENABLED_KEY = booleanPreferencesKey("splash_animation_enabled")
        private val SPLASH_MUSIC_ENABLED_KEY = booleanPreferencesKey("splash_music_enabled")
        // D6 (performance.md, F13): the full ~2.75s whoosh-and-cross sequence
        // plays once per install/update, a short ~300ms fade every other
        // launch. Stores the app's BuildConfig.VERSION_CODE the full sequence
        // was last shown for; unset (-1) never matches a real version code, so
        // a fresh install always gets the full sequence exactly once, same as
        // the first launch after an update that bumps VERSION_CODE.
        private val LAST_FULL_SPLASH_VERSION_CODE_KEY = intPreferencesKey("last_full_splash_version_code")
        // uiux.md's "Arc cover enlarger is undiscoverable", replacing the old
        // magnifier badge with a one-time onboarding ripple (see
        // NovelDetailScreen.kt's CoverEnlargeDiscoveryHint): the first time someone
        // opens a fiction that has arcs, its cover ripples with a short
        // "Double-tap a cover to see it full-size" tip underneath. Default false
        // ("not yet seen") so that plays exactly once per install; dismissing the
        // tip (tapping it, or double-tapping a cover) sets this true and it never
        // shows again.
        private val HAS_SEEN_COVER_ENLARGE_HINT_KEY = booleanPreferencesKey("has_seen_cover_enlarge_hint")
    }

    // theme.collect() runs unattended in MainActivity.onCreate, before the user has
    // touched anything - same situation as startScan(). DataStore's
    // .data Flow throws IOException if the prefs file on disk can't be read (corrupted
    // file, first-run race, low storage, some OEM storage quirks); without this .catch,
    // that exception propagates out of collect() and crashes the whole app on launch
    // since nothing downstream of dataStore.data was previously guarding for it. Falling
    // back to emptyPreferences() just means defaults are used for that read, matching
    // DataStore's own recommended pattern.
    private val safePrefs = context.dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    // See AUTO_SCAN_ON_LAUNCH_KEY's doc comment above - default true.
    val autoScanOnLaunch: Flow<Boolean> = safePrefs.map { prefs ->
        prefs[AUTO_SCAN_ON_LAUNCH_KEY] ?: true
    }

    // See BACKGROUND_SCAN_ENABLED_KEY's doc comment above - default false.
    val backgroundScanEnabled: Flow<Boolean> = safePrefs.map { prefs ->
        prefs[BACKGROUND_SCAN_ENABLED_KEY] ?: false
    }

    // See SCAN_ON_FILE_CHANGE_ENABLED_KEY's doc comment above - default false.
    val scanOnFileChangeEnabled: Flow<Boolean> = safePrefs.map { prefs ->
        prefs[SCAN_ON_FILE_CHANGE_ENABLED_KEY] ?: false
    }

    // See WIFI_ONLY_SYNC_KEY's doc comment above - default false.
    val wifiOnlySync: Flow<Boolean> = safePrefs.map { prefs ->
        prefs[WIFI_ONLY_SYNC_KEY] ?: false
    }

    // Null until the very first scan (interactive or background) completes and calls
    // recordScanCompleted below - see LastScanInfo's own doc comment for why that's a
    // real, distinct state rather than a fabricated default duration/timestamp.
    val lastScanInfo: Flow<LastScanInfo?> = safePrefs.map { prefs ->
        val completedAt = prefs[LAST_SCAN_COMPLETED_AT_KEY] ?: return@map null
        val duration = prefs[LAST_SCAN_DURATION_MS_KEY] ?: return@map null
        LastScanInfo(completedAt, duration)
    }

    // Defaults to SYSTEM_DEFAULT (follow the device's day/night setting) on a fresh
    // install/unset key, rather than hard-coding Light. "LIGHT" and "DARK" are only
    // reached once the user has actually chosen one explicitly and it got persisted
    // verbatim by setTheme(); an unset or unrecognized stored value - including the
    // empty-prefs fallback from safePrefs' IOException guard above - now resolves the
    // same way as an explicit System Default choice would, via resolveTheme() in
    // AppTheme.kt.
    override val theme: Flow<Theme> = safePrefs.map { prefs ->
        when (prefs[THEME_KEY]) {
            "LIGHT" -> Theme.LIGHT
            "DARK" -> Theme.DARK
            "WARM_PAPER" -> Theme.WARM_PAPER
            "SYSTEM_DEFAULT" -> Theme.SYSTEM_DEFAULT
            else -> Theme.SYSTEM_DEFAULT
        }
    }

    // Defaults to LIGHT - the daytime look System Default falls back to when the user
    // hasn't separately chosen Warm Paper for daytime. This is independent of the
    // `theme` flow above now defaulting to SYSTEM_DEFAULT itself: a fresh install
    // follows the system's day/night state, and during the day it shows plain Light
    // until the user opts into Warm Paper for daytime via this sub-choice. WARM_PAPER
    // is the only other meaningful value; anything else stored here (including DARK,
    // which isn't a valid daytime choice) also falls back to LIGHT rather than
    // propagating a bad value into colorSchemeFor.
    override val systemDefaultLightVariant: Flow<Theme> = safePrefs.map { prefs ->
        when (prefs[SYSTEM_DEFAULT_LIGHT_VARIANT_KEY]) {
            "WARM_PAPER" -> Theme.WARM_PAPER
            else -> Theme.LIGHT
        }
    }

    // Off by default - see AMOLED_MODE_KEY's doc comment above.
    override val amoledModeEnabled: Flow<Boolean> = safePrefs.map { prefs ->
        prefs[AMOLED_MODE_KEY] ?: false
    }

    val defaultPageSize: Flow<Int> = safePrefs.map { prefs ->
        prefs[DEFAULT_PAGE_SIZE_KEY] ?: 10
    }

    // Default true - see SPLASH_ANIMATION_ENABLED_KEY's doc comment above.
    val splashAnimationEnabled: Flow<Boolean> = safePrefs.map { prefs ->
        prefs[SPLASH_ANIMATION_ENABLED_KEY] ?: true
    }

    // Default true - see SPLASH_MUSIC_ENABLED_KEY's doc comment above (D6,
    // revised). Independent of splashAnimationEnabled: a user can keep the
    // visual reconstruction but turn off sound, or vice versa.
    val splashMusicEnabled: Flow<Boolean> = safePrefs.map { prefs ->
        prefs[SPLASH_MUSIC_ENABLED_KEY] ?: true
    }

    // D6 (performance.md, F13) - see LAST_FULL_SPLASH_VERSION_CODE_KEY's doc
    // comment above. `currentVersionCode` is BuildConfig.VERSION_CODE, passed in
    // rather than read here so this stays testable without a real BuildConfig.
    fun shouldShowFullSplash(currentVersionCode: Int): Flow<Boolean> = safePrefs.map { prefs ->
        (prefs[LAST_FULL_SPLASH_VERSION_CODE_KEY] ?: -1) != currentVersionCode
    }

    // Seeds ChapterTtsState's initial speechRate once rememberChapterTts() is wired
    // to it (Stage 3.3) - not read anywhere yet. Default 1.0f matches the hardcoded
    // rate ChapterTtsState uses today, so wiring this up later is a no-op for anyone
    // who hasn't visited settings/tts. The pill's live setRate() during a session
    // never writes back here - see docs/foundational/design-notes.md's "TTS settings split".
    val ttsDefaultRate: Flow<Float> = safePrefs.map(::ttsDefaultRateOf)

    // Applied once at TTS engine init (Stage 3.3), no pill exposure - see
    // docs/foundational/design-notes.md ("TTS settings split") for why pitch doesn't get a mid-session control the
    // way rate does. Default 1.0f is TextToSpeech's own neutral pitch.
    val ttsPitch: Flow<Float> = safePrefs.map(::ttsPitchOf)

    // Off by default - auto-advancing to the next chapter on read-aloud completion
    // is a behavior change from today's "stop at chapter end", so this stays opt-in
    // until a reader turns it on at settings/tts (Stage 3.4 wires the actual
    // navigation call).
    val ttsAutoContinue: Flow<Boolean> = safePrefs.map(::ttsAutoContinueOf)

    // Off by default, same reasoning as ttsAutoContinue above - keeping the screen
    // on while TTS speaks (Stage 3.5) is a battery-life tradeoff a reader should opt
    // into rather than get silently.
    val ttsKeepScreenOn: Flow<Boolean> = safePrefs.map(::ttsKeepScreenOnOf)

    // null means "engine/system default" - same "unset means default" posture as
    // ttsAutoContinue/ttsKeepScreenOn above, not a third false-ish sentinel. Seeded
    // once into the engine by setInitialRateAndPitch alongside rate/pitch (see
    // TtsPlaybackService) and never written back by a live, mid-session control -
    // only TtsSettingsScreen's voice picker calls setTtsVoiceName.
    val ttsVoiceName: Flow<String?> = safePrefs.map(::ttsVoiceNameOf)

    // Favorite voices (design-notes.md's "TTS settings split"): the
    // voice page's star toggles membership in this set; favorites sort first
    // within a language there (TtsVoiceModel.kt's withFavoritesFirst). Empty
    // by default - see TTS_FAVORITE_VOICES_KEY's doc comment.
    val ttsFavoriteVoiceNames: Flow<Set<String>> = safePrefs.map(::ttsFavoriteVoiceNamesOf)

    // Default false - see HAS_SEEN_COVER_ENLARGE_HINT_KEY's doc comment above.
    val hasSeenCoverEnlargeHint: Flow<Boolean> = safePrefs.map { prefs ->
        prefs[HAS_SEEN_COVER_ENLARGE_HINT_KEY] ?: false
    }

    // reader-settings.md's "cheap" batch - see the READER_* keys' doc
    // comments above for each one's reasoning. ReaderScreen seeds its
    // in-session `remember` state from these once (fontSize, lineHeight,
    // readingMode) rather than binding live - the pill still adjusts for
    // the current session only and never writes back here, same split the
    // TTS rate/pitch defaults above already use.
    val readerFontSize: Flow<Float> = safePrefs.map(::readerFontSizeOf)
    val readerLineHeight: Flow<Float> = safePrefs.map(::readerLineHeightOf)
    val readerReadingMode: Flow<String?> = safePrefs.map(::readerReadingModeOf)
    val readerFontFamily: Flow<String> = safePrefs.map(::readerFontFamilyOf)
    val readerKeepScreenOn: Flow<Boolean> = safePrefs.map(::readerKeepScreenOnOf)
    val readerFullscreen: Flow<Boolean> = safePrefs.map(::readerFullscreenOf)
    val readerBreakLines: Flow<Boolean> = safePrefs.map(::readerBreakLinesOf)
    val readerBrightnessSliderEnabled: Flow<Boolean> = safePrefs.map(::readerBrightnessSliderEnabledOf)
    val readerBrightness: Flow<Float> = safePrefs.map(::readerBrightnessOf)

    suspend fun setAutoScanOnLaunch(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[AUTO_SCAN_ON_LAUNCH_KEY] = enabled
        }
    }

    suspend fun setBackgroundScanEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[BACKGROUND_SCAN_ENABLED_KEY] = enabled
        }
    }

    suspend fun setScanOnFileChangeEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[SCAN_ON_FILE_CHANGE_ENABLED_KEY] = enabled
        }
    }

    suspend fun setWifiOnlySync(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[WIFI_ONLY_SYNC_KEY] = enabled
        }
    }

    // Called by both startScan (LibraryViewModel) and LibraryScanWorker right after a
    // scan pass finishes - see LAST_SCAN_COMPLETED_AT_KEY's doc comment above.
    suspend fun recordScanCompleted(completedAtMillis: Long, durationMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[LAST_SCAN_COMPLETED_AT_KEY] = completedAtMillis
            prefs[LAST_SCAN_DURATION_MS_KEY] = durationMillis
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

    override suspend fun setAmoledModeEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[AMOLED_MODE_KEY] = enabled
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

    // Called from SplashScreen's onFinished once the splash has actually played
    // out (full or short) for `versionCode` - see shouldShowFullSplash above.
    suspend fun markFullSplashShown(versionCode: Int) {
        context.dataStore.edit { prefs ->
            prefs[LAST_FULL_SPLASH_VERSION_CODE_KEY] = versionCode
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

    // `name` null clears back to "engine/system default" by removing the key
    // entirely, rather than storing an empty string - keeps ttsVoiceName's
    // null-means-default read above a single, unambiguous case.
    suspend fun setTtsVoiceName(name: String?) {
        context.dataStore.edit { prefs ->
            if (name == null) {
                prefs.remove(TTS_VOICE_NAME_KEY)
            } else {
                prefs[TTS_VOICE_NAME_KEY] = name
            }
        }
    }

    // Rung 5: flips `name`'s membership in the favorites set and writes the
    // result back in the same `edit {}` transaction, so two taps in quick
    // succession (the star is easy to double-tap) can't race each other the
    // way a separate read-then-write pair could. `name` is a Voice.getName(),
    // same identity TTS_VOICE_NAME_KEY already uses.
    suspend fun toggleTtsFavoriteVoice(name: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[TTS_FAVORITE_VOICES_KEY] ?: emptySet()
            prefs[TTS_FAVORITE_VOICES_KEY] = if (name in current) {
                current - name
            } else {
                current + name
            }
        }
    }

    // Called once, when CoverEnlargeDiscoveryHint is dismissed - see
    // HAS_SEEN_COVER_ENLARGE_HINT_KEY's doc comment. There's deliberately no
    // setter to flip this back to false.
    suspend fun setHasSeenCoverEnlargeHint() {
        context.dataStore.edit { prefs ->
            prefs[HAS_SEEN_COVER_ENLARGE_HINT_KEY] = true
        }
    }

    suspend fun setReaderFontSize(size: Float) {
        context.dataStore.edit { prefs ->
            prefs[READER_FONT_SIZE_KEY] = size
        }
    }

    suspend fun setReaderLineHeight(height: Float) {
        context.dataStore.edit { prefs ->
            prefs[READER_LINE_HEIGHT_KEY] = height
        }
    }

    // `mode` null clears back to "follow the app theme" by removing the key,
    // same "absence is the meaningful default" shape as setTtsVoiceName.
    suspend fun setReaderReadingMode(mode: ReadingModeDefault?) {
        context.dataStore.edit { prefs ->
            if (mode == null) {
                prefs.remove(READER_READING_MODE_KEY)
            } else {
                prefs[READER_READING_MODE_KEY] = mode.name
            }
        }
    }

    suspend fun setReaderFontFamily(family: String) {
        context.dataStore.edit { prefs ->
            prefs[READER_FONT_FAMILY_KEY] = family
        }
    }

    suspend fun setReaderKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[READER_KEEP_SCREEN_ON_KEY] = enabled
        }
    }

    suspend fun setReaderFullscreen(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[READER_FULLSCREEN_KEY] = enabled
        }
    }

    suspend fun setReaderBreakLines(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[READER_BREAK_LINES_KEY] = enabled
        }
    }

    suspend fun setReaderBrightnessSliderEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[READER_BRIGHTNESS_SLIDER_ENABLED_KEY] = enabled
        }
    }

    suspend fun setReaderBrightness(level: Float) {
        context.dataStore.edit { prefs ->
            prefs[READER_BRIGHTNESS_KEY] = level.coerceIn(0.02f, 1f)
        }
    }
}

// Mirrors ui/ReaderScreen.kt's ReadingMode (LIGHT/SEPIA/DARK) rather than
// importing it directly - PreferencesManager (data layer) staying free of a
// ui.* import, same layering the rest of this file already keeps (Theme is
// defined here, not imported from ui). ReaderSettingsScreen/MainActivity
// map between the two one-for-one; see ReaderScreen.kt's readingModeFor.
enum class ReadingModeDefault {
    LIGHT, SEPIA, DARK
}
