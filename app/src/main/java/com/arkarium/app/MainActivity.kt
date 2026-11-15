package com.arkarium.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.arkarium.app.BuildConfig
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.arkarium.app.navigation.AppBottomBar
import com.arkarium.app.navigation.AppDestination
import com.arkarium.app.navigation.AppDialogHost
import com.arkarium.app.navigation.AppNavGraph
import com.arkarium.app.navigation.isBottomBarRoute
import com.arkarium.app.ui.NoticeBoardScreen
import com.arkarium.app.ui.PigeonRallyScreen
import com.arkarium.app.ui.RaesJournalScreen
import com.arkarium.app.viewmodel.JournalViewModel
import com.arkarium.app.viewmodel.NoticeBoardViewModel
import com.arkarium.app.ui.ChapterEditorScreen
import com.arkarium.app.ui.AuthorPageScreen
import com.arkarium.app.ui.HomeScreen
import com.arkarium.app.ui.LegalContent
import com.arkarium.app.ui.LegalDocumentScreen
import com.arkarium.app.ui.LibrarySettingsScreen
import com.arkarium.app.ui.NovelDetailScreen
import com.arkarium.app.data.ChapterLoadResult
import com.arkarium.app.ui.ChapterLoadErrorScreen
import com.arkarium.app.ui.reader.ReaderFontFamily
import com.arkarium.app.ui.reader.ReaderScreen
import com.arkarium.app.ui.reader.applyWindowLayout
import com.arkarium.app.ui.ReaderSettingsScreen
import com.arkarium.app.ui.SettingsScreen
import com.arkarium.app.ui.SplashScreen
import com.arkarium.app.ui.SplashSettingsScreen
import com.arkarium.app.ui.ThemeSettingsScreen
import com.arkarium.app.ui.TtsSettingsScreen
import com.arkarium.app.ui.TtsVoicePickerScreen
import com.arkarium.app.ui.rememberChapterTts
import com.arkarium.app.ui.WebViewScreen
import com.arkarium.app.ui.FictionBrowseScreen
import com.arkarium.app.data.AppDatabase
import com.arkarium.app.data.AppScope
import com.arkarium.app.data.DatabaseDowngradeException
import com.arkarium.app.data.GoogleBooksMetadataProvider
import com.arkarium.app.data.resolveLibraryRoot
import com.arkarium.app.data.ReaderPosition
import com.arkarium.app.data.ScannerImpl
import com.arkarium.app.data.NewChapterCheckWorker
import com.arkarium.app.data.LibraryFileWatcher
import com.arkarium.app.data.LibraryScanWorker
import com.arkarium.app.data.clearImageCaches
import com.arkarium.app.data.computeStorageBreakdown
import com.arkarium.app.data.libraryRootFile
import com.arkarium.app.data.ReadingProgressSaveWorker
import com.arkarium.app.data.NewChapterNotifier
import com.arkarium.app.data.NovelEntity
import com.arkarium.app.data.sync.relayBaseUrlForSlug
import com.arkarium.app.data.PreferencesManager
import com.arkarium.app.data.Theme
import com.arkarium.app.data.LastScanInfo
import com.arkarium.app.data.StorageBreakdown
import com.arkarium.app.data.sync.SyncManager
import com.arkarium.app.data.TextChapterContentRepository
import com.arkarium.app.ui.formatBytes
import androidx.core.content.FileProvider
import com.arkarium.app.navigation.NavMotion
import com.arkarium.app.navigation.AddFictionState
import com.arkarium.app.navigation.SyncAllState
import com.arkarium.app.ui.theme.applyAmoledMode
import com.arkarium.app.ui.theme.colorSchemeFor
import com.arkarium.app.ui.theme.resolveTheme
import com.arkarium.app.viewmodel.ChapterRestore
import com.arkarium.app.viewmodel.LibraryViewModel
import com.arkarium.app.viewmodel.MetadataViewModel
import com.arkarium.app.viewmodel.ReaderViewModel
import com.arkarium.app.viewmodel.SettingsViewModel
import com.arkarium.app.viewmodel.SyncViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The *SearchState/*State dialog-driving sealed classes formerly declared here now live
// in navigation/AppState.kt; the color-scheme helpers (warmPaperColorScheme,
// colorSchemeFor, resolveTheme) formerly declared here now live in ui/theme/AppTheme.kt -
// see docs/foundational/design-notes.md. Only the imports above changed; every call site
// below is unchanged since the types/functions kept their names.
//
// Screen (navigation destinations) and this Activity's own currentScreen field - along
// with the "legacy" NavHost destination and the now-fully-dead `when (currentScreen.value)`
// branch inside it - were deleted in Stage 3.5 (see docs/foundational/design-notes.md).
// Stages 3.1-3.4 had already migrated every real destination onto its own NavController
// route ("home", "novelDetail/{novelId}", "reader/{novelId}/{chapterId}", etc.); by the
// end of Stage 3.4 nothing constructed a Screen or read currentScreen.value to decide
// what to show anymore, so both were pure dead weight kept alive only for a `when`
// exhaustiveness check on a branch that could never compose. Every entry point that used
// to write `currentScreen.value = Screen.X` alongside a `navController.navigate(...)` call
// now just does the navigate - NavController's own back stack and current-destination
// tracking are the single source of truth for "what's showing" from Stage 3.1 onward, the
// same conclusion Stage 3.4's `from`-field removal already reached for Screen.Author.
//
// currentTheme/currentSystemDefaultLightVariant formerly lived here as an Activity
// mutableStateOf field, now come from settingsViewModel (Stage 2.2 of Phase 2, see
// docs/foundational/design-notes.md). Every read site below (`currentTheme.value`,
// etc.) is unchanged, since settingsViewModel's StateFlows are collected under those
// same local names. The old useCustomFolder/savedUri pair (once a per-composition
// collectAsState() local) is gone with SAF support itself - see "Settled
// decisions" in docs/foundational/design-notes.md - replaced by
// the SAF-removal notice, which has since been dropped entirely (see
// PreferencesManager's doc comment on the key pair it used to back).
//
// metadataSearchState/addFictionState and fetchMetadataFor/applyMetadata formerly
// lived here too - all four now come from metadataViewModel (Stage 2.4 of Phase 2,
// see docs/foundational/design-notes.md).
//
// syncAllState/syncCheckState/syncResolutionState and the sync/resolution logic
// against SyncManager - scanSingleSyncedNovel, addFictionByName, syncAllRaeArkNovels,
// checkForUpdates, and the three resolveXxx actions - formerly lived here too. All of
// it now comes from syncViewModel (Stage 2.5 of Phase 2, the last of the four
// ViewModels the plan calls for - see docs/foundational/design-notes.md and
// SyncViewModel's own doc comment for why addFictionByName moved here rather than
// staying with the addFictionState it writes). Every place this file used to patch
// currentScreen directly inside one of those functions used to do it via an
// onApplied/onUpdated callback passed to metadataViewModel/syncViewModel instead;
// Stage 3.3 of Phase 3 (see docs/foundational/design-notes.md) removed all of those
// callbacks once NovelDetail became a "novelDetail/{novelId}" route that resolves its
// own NovelEntity from libraryViewModel.novels, making the currentScreen patch each one
// existed for unnecessary.

class MainActivity : ComponentActivity() {

    // novels/chapters/arcs/recentlyRead/inProgressNovels/overriddenChapterIds/
    // arcStartChapterIds/scanProgress/scanMessage formerly lived here as Activity
    // mutableStateOf/mutableStateListOf fields - all now come from libraryViewModel
    // (Stage 2.3 of Phase 2, see docs/foundational/design-notes.md). Every read site
    // below is unchanged except for the `libraryViewModel.` prefix, since the fields
    // kept their names.
    // readerAuthor - the author of the fiction open in the reader - moved to
    // readerViewModel alongside loadReaderAuthor (see that class's own doc comment).
    // authorPageAuthor/authorPageNovels - backing Screen.Author, reloaded via
    // LaunchedEffect whenever the authorId changes - moved to libraryViewModel
    // alongside loadAuthorPage (see that class's own doc comment).
    // showSplash itself moved into renderMainContent()'s composable scope as a
    // rememberSaveable (see bugs.md, "Rotating the device restarts the app") - it used
    // to live here as a plain mutableStateOf field, which reset to true on every
    // Activity recreation (any config change, not just rotation), replaying the
    // splash and its audio every time. rememberSaveable survives recreation the same
    // way the rest of the Activity's saved instance state does.
    // Set when the "Continue Reading" dynamic app shortcut (long-press the launcher
    // icon - see ArkariumApp.publishContinueReadingShortcut) is tapped. Mirrors
    // pendingNotificationNovelId immediately below: resolution is deferred to
    // renderMainContent's LaunchedEffect rather than done here, because this can
    // arrive as the cold-start intent before startScan (kicked off later in onCreate)
    // has populated `novels`, and because which novel to resume isn't known yet
    // either - unlike a notification tap, the shortcut doesn't carry a novel id, it
    // means "whichever novel reading_progress says was read most recently".
    private val pendingContinueReadingShortcut = mutableStateOf(false)

    // Set when a "new chapter" notification is tapped (see onNewIntent/handleNotificationIntent
    // below) and read by the LaunchedEffect in renderMainContent that navigates to it -
    // null the rest of the time. Kept as Activity state rather than acted on directly
    // in handleNotificationIntent because `novels` may not have finished loading yet
    // (a cold start from a notification tap runs onCreate's own startScan concurrently)
    // - the effect re-checks every time `novels` changes until the target novel shows up.
    private val pendingNotificationNovelId = mutableStateOf<String?>(null)
    // Set immediately before launching notificationPermission below, read back in its
    // callback - carries which novel's toggle triggered the permission prompt across
    // that async round-trip, since RequestPermission's callback only gets a Boolean.
    // mutableStateOf, not a plain var (see onSaveInstanceState/onCreate's
    // savedInstanceState branch below) - the system permission dialog runs in its own
    // window/process, so this Activity is only STOPPED, not destroyed, while it's up,
    // but that's exactly the state lmkd can reclaim a backgrounded process from. A
    // plain var reset to null on recreation; ActivityResultRegistry itself persists
    // the *pending request* across that (the callback still fires), but without this
    // being saved too the callback would fire with granted=true and novelId null -
    // the permission silently granted with nothing left to apply it to, so the toggle
    // stays off with no error and no retry. Same class of bug pendingContinueReadingShortcut/
    // pendingNotificationNovelId were already fixed for below.
    internal val pendingNotifyToggleNovelId = mutableStateOf<String?>(null)
    private lateinit var db: AppDatabase
    private lateinit var scanner: ScannerImpl
    // internal, not private: AppNavGraph.kt (navigation/AppNavGraph.kt) reads/writes
    // this directly through the `activity` it's handed, same reasoning as the
    // ViewModels and the three Activity-owned helpers below - the NavHost that used to
    // close over this as an Activity field now lives in its own file.
    internal lateinit var prefsManager: PreferencesManager
    private lateinit var syncManager: SyncManager
    // "Scan on file change" (LibrarySettingsScreen) - null whenever the preference is
    // off or onStop has torn it down; see LibraryFileWatcher's own doc comment for why
    // this is only ever live between onStart and onStop, not onCreate/onDestroy.
    // Rebuilt from scratch in onStart rather than paused/resumed, since a
    // FileObserver's watch can't be suspended and resumed anyway - starting a fresh
    // one is exactly as cheap.
    private var libraryFileWatcher: LibraryFileWatcher? = null
    // Debounces LibraryFileWatcher's onChanged callback: a drag-and-drop of many files,
    // or a sync writing a chapter at a time, fires that callback once per file, and
    // launching a full rescan on every single one would make the "instant" feel of
    // this feature actively worse than the periodic/manual alternatives. Cancelling
    // and relaunching this job on every callback collapses any burst within
    // FILE_CHANGE_DEBOUNCE_MS of the last one into a single rescan, fired once things
    // go quiet.
    private var fileChangeRescanJob: Job? = null
    // Live value of PreferencesManager.scanOnFileChangeEnabled, kept in sync by the
    // collector started in onCreate below. Read by onStart/onStop (which can't
    // themselves collect a Flow's current value synchronously) to decide whether a
    // freshly-foregrounded Activity should start the watcher, and by that same
    // collector to decide whether a toggle flipped on mid-foreground should start it
    // immediately rather than waiting for the next onStart.
    private var scanOnFileChangeEnabled = false
    // True between onStart and onStop - see LibraryFileWatcher's own "process-lifetime
    // only" doc comment for why the watcher follows this window rather than
    // onCreate/onDestroy. Used alongside scanOnFileChangeEnabled above so the
    // preference collector never starts a watcher for an Activity that's currently
    // stopped (it would just be immediately torn down again, or worse, outlive onStop
    // if the collector raced it).
    private var isActivityStarted = false
    // Stage 2.2 of Phase 2 (docs/foundational/design-notes.md) - the first ViewModel
    // pulled out of MainActivity. Constructed in onCreate right after prefsManager
    // (its only dependency), same lateinit-set-in-onCreate pattern as the services
    // above, rather than as a `by viewModels { }` property delegate - prefsManager
    // isn't constructed yet at property-initializer time, before onCreate runs.
    internal lateinit var settingsViewModel: SettingsViewModel
    // Stage 2.3 of Phase 2 (docs/foundational/design-notes.md) - the second ViewModel
    // pulled out of MainActivity. Constructed in onCreate right after db/scanner
    // (its two dependencies), same pattern as settingsViewModel above.
    internal lateinit var libraryViewModel: LibraryViewModel
    // Stage 2.4 of Phase 2 (docs/foundational/design-notes.md) - the third ViewModel
    // pulled out of MainActivity. Constructed in onCreate right after libraryViewModel
    // (one of its two dependencies, alongside db), same pattern as libraryViewModel
    // itself.
    internal lateinit var metadataViewModel: MetadataViewModel
    // Stage 2.5 of Phase 2 (docs/foundational/design-notes.md) - the fourth and last
    // ViewModel pulled out of MainActivity. Constructed in onCreate right after
    // metadataViewModel (one of its dependencies, alongside db/scanner/syncManager/
    // libraryViewModel), same pattern as the other three.
    internal lateinit var syncViewModel: SyncViewModel
    // Finishes the extraction: owns the reader route's own data access (author lookup,
    // chapter text load, saved-position resolve/migrate) so the NavHost destination no
    // longer touches db/contentRepo. Constructed in onCreate after db/contentRepo.
    internal lateinit var readerViewModel: ReaderViewModel
    // Rae's Journal (Notice Board) - see JournalViewModel.
    internal lateinit var journalViewModel: JournalViewModel
    // Pigeon Rally (Notice Board) - see NoticeBoardViewModel.
    internal lateinit var noticeBoardViewModel: NoticeBoardViewModel

    // Originally launched only from a "Notify me when new chapters are available"
    // toggle being switched on (see NovelDetailScreen's onToggleNotify wiring below);
    // now also launched, with no pendingNotifyToggleNovelId payload, by
    // requestNotificationPermissionIfNeeded() below (see its own doc comment - the
    // rung 4 TTS-notification fix). API < 33 has no such runtime permission to request
    // in the first place (notifications there are granted at install time), so this is
    // never triggered on those versions; every caller checks Build.VERSION.SDK_INT
    // before ever calling launch().
    internal val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val novelId = pendingNotifyToggleNovelId.value
        pendingNotifyToggleNovelId.value = null
        // Denied: the toggle simply stays off (setNotifyEnabled is never called, so
        // notify_new_chapters keeps whatever value it already had - false, since this
        // path only runs when the user just tried to turn it on). They can grant the
        // permission from system settings later and flip the toggle again.
        if (granted && novelId != null) {
            // AppScope.io, not lifecycleScope (see AppScope.kt's doc comment) - this
            // callback can fire right as the Activity instance that launched the
            // permission request is being torn down for recreation, same race
            // saveReadingProgress/saveChapterEdits were already fixed for.
            AppScope.io.launch { libraryViewModel.setNotifyEnabled(novelId, true) }
        }
    }

    // Rung 4 fix (docs/implementation/tts-playback-controls.md): wired to
    // ReaderScreen's onRequestNotificationPermission, called right alongside
    // tts.speak() whenever read-aloud actually starts a new session. Without this, the
    // only runtime request for POST_NOTIFICATIONS was the "Notify new chapters" toggle
    // above - a reader who never touched that toggle would start TtsPlaybackService's
    // foreground service just fine (no crash, no exception: startForeground() itself
    // doesn't require the permission), but on API 33+ NotificationManager silently
    // drops every notification without it, so the media-style transport notification
    // (previous/play-pause/next/stop) simply never appeared. In-app playback and its
    // own TtsControlsBar pill (rungs 1-3) were unaffected, which is why this only
    // surfaced as "the notification-bar controls specifically don't work".
    //
    // Deliberately does not set pendingNotifyToggleNovelId: this path has no per-novel
    // follow-up action to take once the permission is granted (unlike the toggle) -
    // TtsPlaybackService already calls startForeground()/notify() on its own schedule,
    // and the system will simply start showing those notifications from then on. Safe
    // to call every time read-aloud starts, already-granted or not: checkSelfPermission
    // short-circuits before ever touching the launcher, and the launcher itself is a
    // no-op while a request from it is already in flight.
    internal fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // pickFolder (the SAF ACTION_OPEN_DOCUMENT_TREE launcher, plus the persisted
    // URI-permission grant/release logic around it) was removed per the
    // SAF-removal decision (see "Settled decisions" in
    // docs/foundational/design-notes.md) - there is no folder
    // left to pick, since the library root is now the one fixed
    // Android/media/com.arkarium.app/ folder resolveLibraryRoot always returns.

    // resolveLibraryRoot and mergeNovelForRescan formerly declared here now live in
    // data/LibraryScan.kt as plain top-level functions (Stage 2.1 of Phase 2 - see
    // docs/foundational/design-notes.md). Every call site below passes
    // `this@MainActivity` as resolveLibraryRoot's explicit `context` parameter
    // (several call sites are inside `lifecycleScope.launch { }`/Composable lambdas,
    // where a bare `this` would resolve to the wrong receiver). It no longer takes
    // useCustomFolder/savedUri parameters - see the proposal doc above.

    // scanSingleSyncedNovel formerly lived here too (see docs/foundational/architecture.md,
    // Sync invariants S8) - it now lives on syncViewModel alongside syncAllRaeArkNovels, its only
    // caller (Stage 2.5 of Phase 2, see docs/foundational/design-notes.md).

    // startScan, loadNovelDetails, and refreshRecentlyRead formerly lived here as
    // Activity-private methods - all three now live on libraryViewModel (Stage 2.3 of
    // Phase 2, see docs/foundational/design-notes.md), unchanged in body except for the
    // fields they read/write resolving to that class's own novels/chapters/arcs/
    // recentlyRead/inProgressNovels/scanProgress/scanMessage instead of the
    // Activity's. Every call site below (`startScan(...)`, `loadNovelDetails(...)`,
    // `refreshRecentlyRead()`) now calls `libraryViewModel.` + the same name.

    // loadAuthorPage, navigateToLastReadChapterOrDetail's db/loadNovelDetails half
    // (now resolveLastReadChapterId), saveChapterEdits, saveReadingProgress, and
    // setNotifyEnabled formerly lived here too - all five now live on libraryViewModel
    // (see that class's own doc comment for why they moved and why LibraryViewModel
    // rather than SyncViewModel). navigateToLastReadChapterOrDetail's actual
    // NavController.navigate call stays below as navigateToChapterOrDetail, a
    // navigation-only helper with no db access of its own ("Navigation is NavController
    // only", design-notes.md). addFictionByName, syncAllRaeArkNovels, and
    // checkForUpdates live on syncViewModel (Stage 2.5 of Phase 2, see
    // docs/foundational/design-notes.md and SyncViewModel's own doc comment).

    // Pure navigation, no db access - the other half of what used to be
    // navigateToLastReadChapterOrDetail (see libraryViewModel.resolveLastReadChapterId's
    // doc comment). Shared by the Continue Reading shortcut and HomeScreen's
    // onContinueReading, which used to each implement this sequencing independently and
    // had drifted apart - onContinueReading fell back to NovelDetail when there was no
    // resolvable chapter, the shortcut's effect silently did nothing instead (and kept
    // retrying). One shared implementation means a future fix to this sequencing can't
    // land in only one of the two call sites again.
    internal fun navigateToChapterOrDetail(novel: NovelEntity, chapterId: String?, navController: NavHostController) {
        if (chapterId != null) {
            navController.navigate("reader/${novel.id}/$chapterId") { launchSingleTop = true }
        } else {
            navController.navigate("novelDetail/${novel.id}") { launchSingleTop = true }
        }
    }

    // Reads the novel id a "new chapter" notification's PendingIntent carries (see
    // NewChapterNotifier.EXTRA_NOVEL_ID) and stashes it for renderMainContent's
    // LaunchedEffect to navigate to once that novel shows up in `novels` - see
    // pendingNotificationNovelId's own doc comment. A no-op for any intent without
    // that extra (a plain launcher tap, or an onNewIntent delivery from something
    // else entirely).
    private fun handleNotificationIntent(intent: Intent?) {
        val novelId = intent?.getStringExtra(NewChapterNotifier.EXTRA_NOVEL_ID) ?: return
        pendingNotificationNovelId.value = novelId
    }

    // Reads the "Continue Reading" dynamic shortcut's launch extra (see
    // EXTRA_CONTINUE_READING_SHORTCUT below and ArkariumApp.publishContinueReadingShortcut)
    // and stashes it for renderMainContent's LaunchedEffect to resolve - see
    // pendingContinueReadingShortcut's own doc comment. A no-op for any intent
    // without that extra (a plain launcher tap, a notification tap, or an
    // onNewIntent delivery from something else entirely).
    private fun handleShortcutIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CONTINUE_READING_SHORTCUT, false) != true) return
        pendingContinueReadingShortcut.value = true
    }

    // The pending one-shot intents are Activity state that only exists until the
    // LaunchedEffects in renderMainContent resolve them (which can take a while on a
    // cold start - they wait for the scan to surface the novel). Without this, a
    // recreation in that window silently dropped them.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PENDING_CONTINUE_READING, pendingContinueReadingShortcut.value)
        outState.putString(STATE_PENDING_NOTIFICATION_NOVEL, pendingNotificationNovelId.value)
        outState.putString(STATE_PENDING_NOTIFY_TOGGLE_NOVEL, pendingNotifyToggleNovelId.value)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
        handleShortcutIntent(intent)
    }

    // "Scan on file change" (LibrarySettingsScreen) - start/stop the FileObserver-
    // backed watcher across exactly the window ARKarium is in the foreground or
    // visible, per LibraryFileWatcher's own doc comment. scanOnFileChangeEnabled is
    // kept current by the preference collector in onCreate; onStart/onStop can't
    // collect a Flow's latest value synchronously themselves, so they just read it.
    override fun onStart() {
        super.onStart()
        isActivityStarted = true
        if (scanOnFileChangeEnabled) startLibraryFileWatcher()
    }

    override fun onStop() {
        super.onStop()
        isActivityStarted = false
        stopLibraryFileWatcher()
    }

    // Builds the watcher over the current library root and starts it. A null
    // libraryRootFile (shared storage genuinely unavailable right now - see
    // resolveLibraryRoot's own doc comment) just means there's nothing to watch yet;
    // the next onStart or preference flip tries again. No-ops if one is already
    // running, so onStart and the preference collector can both call this safely.
    private fun startLibraryFileWatcher() {
        if (libraryFileWatcher != null) return
        val root = libraryRootFile(this) ?: return
        libraryFileWatcher = LibraryFileWatcher(root) {
            // Debounced - see fileChangeRescanJob's own doc comment above. Cancel
            // whatever's still pending and start a fresh countdown on every
            // callback, so a burst of file events (a multi-file drag-and-drop, a
            // sync writing a chapter at a time) collapses into exactly one rescan
            // once things go quiet.
            fileChangeRescanJob?.cancel()
            fileChangeRescanJob = lifecycleScope.launch {
                delay(FILE_CHANGE_DEBOUNCE_MS)
                resolveLibraryRoot(this@MainActivity)?.let { libraryViewModel.startScan(it) }
            }
        }.also { it.start() }
    }

    private fun stopLibraryFileWatcher() {
        libraryFileWatcher?.stop()
        libraryFileWatcher = null
        fileChangeRescanJob?.cancel()
        fileChangeRescanJob = null
    }

    // "Open library folder in file manager" (LibrarySettingsScreen's "Storage &
    // organization" section) - a direct intent/shortcut rather than making the user
    // navigate there by hand, since the whole model is "you manage files yourself"
    // (see data/LibraryScan.kt's resolveLibraryRoot doc comment). The library root is
    // a plain java.io.File under getExternalMediaDirs(), not a content:// Uri, so it
    // has to go through a FileProvider (see the <provider> entry in
    // AndroidManifest.xml and res/xml/file_paths.xml) rather than a bare file:// Uri,
    // which Android refuses to hand to another app's Intent on API 24+
    // (StrictMode.FileUriExposedException). resolveActivity()/queryIntentActivities
    // need the matching <queries> block in the manifest to see any file manager at
    // all on API 30+ - without it this always finds "no activity" even when a
    // perfectly good file manager is installed, due to package-visibility
    // restrictions.
    internal fun openLibraryFolderInFileManager() {
        val dir = libraryRootFile(this)
        if (dir == null) {
            libraryViewModel.reportScanSetupError("Couldn't reach the library folder right now.")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", dir)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                libraryViewModel.reportScanSetupError("No file manager app found that can open this folder.")
            }
        } catch (e: Exception) {
            libraryViewModel.reportScanSetupError("Couldn't open the library folder: ${e.message}")
        }
    }

    // resolveMissingFolderBySyncing/resolveByRemovingFromLibrary/
    // resolveSourceGoneByUnlinking - the three resolution actions SyncResolutionDialog
    // offers once checkForUpdates hits a MissingLocalFolderException or
    // SourceGoneException (see docs/foundational/architecture.md, Sync invariants S6) - formerly lived here
    // too. All three now live on syncViewModel alongside the syncResolutionState they
    // drive (Stage 2.5 of Phase 2, see docs/foundational/design-notes.md), each now
    // taking an `onUpdated`/`onRemoved` callback where it used to patch currentScreen
    // directly - see SyncResolutionDialog's call site below for how each is wired.

    // Shared fallback UI for anything that throws before/while the real screen renders.
    // Pulled out so BOTH the service-construction guard, the setContent guard, and the
    // saved-crash check below can show the same "here's exactly what broke" screen
    // instead of a silent process death - a stack trace on-screen beats needing
    // adb/logcat to debug a phone-only crash report.
    private fun renderCrashScreen(title: String, details: String) {
        setContent {
            MaterialTheme {
                // Surface, not a bare Column: the window background is solid black
                // (themes.xml) and a bare Column leaves LocalContentColor at black, so
                // the report was black text on a black window - an apparent "black screen".
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    // Same BuildConfig.VERSION_NAME used in Settings' About line -
                    // worth having on a crash report screen specifically, since "what
                    // version were they on" is the first thing a bug report needs.
                    Text(
                        "ARKarium v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
                        item {
                            Text(details, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                }
            }
        }
    }

    private fun renderCrashScreen(e: Throwable) {
        renderCrashScreen(
            "ARKarium failed to start",
            (e::class.java.name + ": " + e.message) + "\n\n" +
                e.stackTrace.take(30).joinToString("\n") { "  at $it" }
        )
    }

    companion object {
        // internal, not private: ArkariumApp (the process-level owner of the crash
        // recorder) writes this record, onCreate below reads and clears it.
        internal const val CRASH_PREFS = "arkarium_crash_info"
        internal const val CRASH_KEY = "last_crash"
        private const val STATE_PENDING_CONTINUE_READING = "pending_continue_reading"
        private const val STATE_PENDING_NOTIFICATION_NOVEL = "pending_notification_novel"
        private const val STATE_PENDING_NOTIFY_TOGGLE_NOVEL = "pending_notify_toggle_novel"
        // Extra carried by the "Continue Reading" static app shortcut's launch intent
        // (see res/xml/shortcuts.xml's <extra>) - true marks "this launch/onNewIntent
        // came from that shortcut", read back by handleShortcutIntent. Not private:
        // referenced from res/xml/shortcuts.xml indirectly via its literal string
        // value, so the two must be kept in sync by hand - there's no way for a
        // manifest-declared shortcut's <extra> to reference a Kotlin constant.
        const val EXTRA_CONTINUE_READING_SHORTCUT = "com.arkarium.app.EXTRA_CONTINUE_READING_SHORTCUT"
        // "Scan on file-change" (LibrarySettingsScreen) - how long
        // startLibraryFileWatcher's debounce job waits after the last file-system
        // event before actually starting a rescan; see fileChangeRescanJob's own
        // doc comment.
        private const val FILE_CHANGE_DEBOUNCE_MS = 1_500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The global uncaught-exception handler that records a crash for the next launch
        // is installed once per process by ArkariumApp - not here, since onCreate runs
        // again on every recreation (see ArkariumApp's doc comment). The try/catch blocks
        // below only catch exceptions thrown synchronously on THIS call stack; anything
        // thrown inside a lifecycleScope coroutine is only observable through that handler.

        // If the previous launch crashed, show that trace now instead of trying to
        // start normally again (which would likely just crash the same way a second
        // time with nothing new learned).
        val crashPrefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
        val lastCrash = crashPrefs.getString(CRASH_KEY, null)
        if (lastCrash != null) {
            crashPrefs.edit().remove(CRASH_KEY).apply()
            renderCrashScreen("ARKarium crashed last time it opened", lastCrash)
            return
        }

        // AppDatabase.create() (and the other service objects below) are the very first
        // things onCreate does, before setContent ever runs. Room.databaseBuilder(...).build()
        // in particular locates its generated *_Impl class via reflection at *runtime*, not
        // a compile-time link - a stale/corrupted incremental kapt cache (common after a CI
        // rebuild) can compile clean and still throw the instant this line runs, taking the
        // whole app down before a single frame is drawn - "installs fine, crashes on open,
        // never even shows a screen". Wrapping this means a failure here shows an error
        // screen with the real exception instead of a silent, undebuggable instant crash.
        try {
            db = AppDatabase.create(this)
            // scanner/prefsManager/syncManager are all stored as fields on ViewModels
            // (LibraryViewModel, SettingsViewModel, SyncViewModel) below, and ViewModels
            // are explicitly designed to outlive a single Activity *instance* across
            // rotation. Building these with `this` (the Activity) instead of
            // applicationContext pins the destroyed Activity - its View hierarchy,
            // Window, everything reachable from it - in memory for as long as the
            // surviving ViewModel holds the reference, i.e. for the rest of the
            // process's life. applicationContext has exactly one instance for the
            // whole process, so it can never leak an Activity. None of these three
            // classes does anything Activity-specific (no startActivity, no theming,
            // no window access), so there's no behavioral downside.
            scanner = ScannerImpl(applicationContext)
            prefsManager = PreferencesManager(applicationContext)
            syncManager = SyncManager(applicationContext)
            // contentRepo used to be the control case - an Activity-owned field built with
            // `this` that died with the Activity on every rotation. It's handed to
            // ReaderViewModel now, and a ViewModel outlives the Activity instance, so it
            // is built with applicationContext for the same leak reason as the three
            // services above. TextChapterContentRepository only uses contentResolver.
            val contentRepo = TextChapterContentRepository(applicationContext)
            settingsViewModel = ViewModelProvider(this, SettingsViewModel.factory(prefsManager))
                .get(SettingsViewModel::class.java)
            libraryViewModel = ViewModelProvider(this, LibraryViewModel.factory(db, scanner, prefsManager))
                .get(LibraryViewModel::class.java)
            metadataViewModel = ViewModelProvider(
                this,
                MetadataViewModel.factory(db, GoogleBooksMetadataProvider(), libraryViewModel)
            ).get(MetadataViewModel::class.java)
            syncViewModel = ViewModelProvider(
                this,
                SyncViewModel.factory(application, db, scanner, syncManager, libraryViewModel, metadataViewModel)
            ).get(SyncViewModel::class.java)
            readerViewModel = ViewModelProvider(this, ReaderViewModel.factory(db, contentRepo))
                .get(ReaderViewModel::class.java)
            journalViewModel = ViewModelProvider(this, JournalViewModel.factory(applicationContext))
                .get(JournalViewModel::class.java)
            noticeBoardViewModel = ViewModelProvider(this, NoticeBoardViewModel.factory(db))
                .get(NoticeBoardViewModel::class.java)
        } catch (e: DatabaseDowngradeException) {
            // See F23 in docs/proposal/data-safety.md - this is Room
            // refusing to open a database written by a newer version of the app, not
            // the "crashed before drawing a single frame" failure the catch-all below
            // is for. The message is already user-facing (AppDatabase.create()), so
            // show it directly instead of the class-name-plus-stack-trace screen.
            renderCrashScreen("Can't open your library", e.message ?: "")
            return
        } catch (e: Throwable) {
            renderCrashScreen(e)
            return
        }

        // Schedules (or, on every launch after the first, no-ops against - see
        // ExistingPeriodicWorkPolicy.KEEP) the periodic background "new chapter" check
        // (see docs/foundational/architecture.md, Sync: entry points). Safe to call unconditionally on every
        // cold launch regardless of whether any novel currently has notifications
        // turned on - the worker itself queries notifyEnabledSynced() and returns
        // immediately if that's empty, so scheduling it eagerly here just means it's
        // already running by the time the user first flips a toggle on, rather than
        // needing this call moved to wherever the very first toggle happens.
        // Three Library Settings preferences (see ui/LibrarySettingsScreen.kt) that
        // need to take effect immediately when toggled, not just on the next cold
        // launch - each collector below runs for the life of the process
        // (lifecycleScope survives config changes, only cancelled on real
        // destruction) and distinctUntilChanged() so a value that hasn't actually
        // changed (e.g. DataStore re-emitting after an unrelated write) never
        // re-issues the same WorkManager/watcher call for nothing.

        // Background scan (LibraryScanWorker) - schedule while enabled, cancel the
        // moment it's turned off. Safe to call unconditionally on every emission:
        // schedule()'s own ExistingPeriodicWorkPolicy.UPDATE and cancel() are both
        // idempotent.
        lifecycleScope.launch {
            prefsManager.backgroundScanEnabled.distinctUntilChanged().collect { enabled ->
                if (enabled) LibraryScanWorker.schedule(this@MainActivity) else LibraryScanWorker.cancel(this@MainActivity)
            }
        }

        // Scan on file change (LibraryFileWatcher) - keeps scanOnFileChangeEnabled
        // current for onStart/onStop above, and additionally starts/stops the
        // watcher immediately for a toggle flipped while already in the foreground
        // (onStart won't fire again on its own until the next backgrounding).
        lifecycleScope.launch {
            prefsManager.scanOnFileChangeEnabled.distinctUntilChanged().collect { enabled ->
                scanOnFileChangeEnabled = enabled
                if (enabled && isActivityStarted) {
                    startLibraryFileWatcher()
                } else if (!enabled) {
                    stopLibraryFileWatcher()
                }
            }
        }

        // Wi-Fi-only sync - gates NewChapterCheckWorker's periodic "new chapter"
        // check (see docs/foundational/architecture.md, Sync: entry points).
        // collectIndexed rather than plain collect: the very first emission (index
        // 0, the value read back at cold launch) schedules with
        // ExistingPeriodicWorkPolicy.KEEP (replaceExisting = false) so a build
        // that's already scheduled this from a previous launch isn't reset just
        // because onCreate ran again; every emission after that is a live toggle
        // flip and should actually take effect (replaceExisting = true) - see
        // NewChapterCheckWorker.schedule's own doc comment for the same KEEP-vs-
        // UPDATE split.
        lifecycleScope.launch {
            prefsManager.wifiOnlySync.distinctUntilChanged().collectIndexed { index, wifiOnly ->
                NewChapterCheckWorker.schedule(this@MainActivity, wifiOnly, replaceExisting = index > 0)
            }
        }

        // A cold start from a "new chapter" notification tap delivers its extras via
        // this initial intent, not onNewIntent (that only fires for an already-running
        // instance - see the manifest's launchMode="singleTop"). Safe to call before
        // `novels` has loaded anything: this only ever stashes the novel id for
        // renderMainContent's LaunchedEffect to act on once startScan (kicked off
        // below) actually populates it.
        //
        // Only on a genuinely fresh launch (savedInstanceState == null). getIntent() on
        // a *recreated* instance (rotation, multi-window resize, memory reclaim, process
        // restore) returns the very intent that originally launched the Activity, extras
        // and all - so handling it unconditionally re-fired the shortcut/notification on
        // every recreation, yanking the reader from wherever they were back to the
        // shortcut's target. A one-shot intent is consumed once; whatever was still
        // unresolved when the previous instance went away comes back through
        // savedInstanceState below instead.
        if (savedInstanceState == null) {
            handleNotificationIntent(intent)

            // Same deal as handleNotificationIntent immediately above, for a cold
            // start from the "Continue Reading" dynamic shortcut instead of a
            // notification tap - see pendingContinueReadingShortcut's own doc comment.
            handleShortcutIntent(intent)
        } else {
            pendingContinueReadingShortcut.value =
                savedInstanceState.getBoolean(STATE_PENDING_CONTINUE_READING, false)
            pendingNotificationNovelId.value =
                savedInstanceState.getString(STATE_PENDING_NOTIFICATION_NOVEL)
            pendingNotifyToggleNovelId.value =
                savedInstanceState.getString(STATE_PENDING_NOTIFY_TOGGLE_NOVEL)
        }

        // Auto-scan on every cold launch, before the user has touched anything. The
        // library root is now the one fixed Android/media/com.arkarium.app/ folder (see
        // "Settled decisions" in docs/foundational/design-notes.md), so this always
        // resolves and runs, populating the library with zero user interaction ever
        // required - there's no longer an "unconfigured" state to wait on, so this is a
        // one-shot call rather than collecting a Flow of preference changes.
        // startScan() already fails soft internally, but this outer catch is the last
        // line of defense so that literally nothing thrown on this path (an unexpected DB
        // error, shared storage not being ready yet, etc) can crash the app on startup.
        // An uncaught exception here previously did exactly that.
        lifecycleScope.launch {
            if (libraryViewModel.novels.isEmpty()) {
                try {
                    resolveLibraryRoot(this@MainActivity)?.let { libraryViewModel.startScan(it) }
                } catch (e: Exception) {
                    libraryViewModel.reportScanSetupError("Couldn't load your library: ${e.message}")
                }
            }
        }

        // Theme and its System Default sub-preference are no longer watched here -
        // settingsViewModel's currentTheme/currentSystemDefaultLightVariant StateFlows
        // (Stage 2.2 of Phase 2, see docs/foundational/design-notes.md) already collect
        // prefsManager.theme/systemDefaultLightVariant themselves, scoped to
        // viewModelScope instead of lifecycleScope.

        // Everything from here down (status-bar theming, HomeScreen and its new
        // empty-library state, etc.) is new UI code that runs on literally every cold
        // launch, before the user touches anything - and unlike the service construction
        // above, it was NOT guarded. The first composition pass of setContent() runs
        // synchronously on this call stack, so any exception thrown while building this
        // tree (a bad Icon reference, a null somewhere in HomeScreen, etc.) was previously
        // propagating straight past this function and crashing the process outright, with
        // no on-screen trace to debug from. Wrapping it surfaces the same diagnostic
        // screen as the guard above instead of a silent crash.
        try {
            renderMainContent()
        } catch (e: Throwable) {
            renderCrashScreen(e)
        }
    }

    private fun renderMainContent() {
        setContent {
            // isSystemInDarkTheme() is only meaningful as a live composable read (it's
            // backed by Configuration's uiMode, which recomposes this on a system
            // day/night switch same as any other Compose state) - resolving it once in
            // onCreate the way currentTheme/currentSystemDefaultLightVariant are
            // collected would miss the system flipping while the app stays open.
            val systemInDarkTheme = isSystemInDarkTheme()
            // Gates the branded splash (see SplashScreen.kt) shown for a moment on
            // every cold launch before the real UI becomes visible. rememberSaveable,
            // not plain remember/mutableStateOf - this needs to survive the Activity
            // being destroyed and recreated on a config change (rotation, dark-mode
            // switch, locale, font scale), or it resets to true on every one of those
            // and replays the splash - see bugs.md, "Rotating the device restarts the
            // app". Still resets on process death same as before; that's fine, a cold
            // process restart showing the splash once is the intended case.
            val showSplash = rememberSaveable { mutableStateOf(true) }
            // Read here (rather than down with the splash toggles below) since
            // resolvedTheme, computed immediately below, needs them before
            // MaterialTheme{} even opens.
            val currentTheme = settingsViewModel.currentTheme.collectAsState()
            val currentSystemDefaultLightVariant = settingsViewModel.currentSystemDefaultLightVariant.collectAsState()
            val amoledModeEnabled = settingsViewModel.amoledModeEnabled.collectAsState()
            val resolvedTheme = resolveTheme(
                currentTheme.value,
                currentSystemDefaultLightVariant.value,
                systemInDarkTheme
            )
            // AMOLED only ever modifies Dark's own resolved palette (see
            // ui/theme/AppTheme.kt's applyAmoledMode) - System Default resolving to
            // DARK at night picks it up too, same as any other Dark-only behavior,
            // since resolvedTheme is already the concrete theme by this point.
            val colorScheme = colorSchemeFor(resolvedTheme).let { scheme ->
                if (resolvedTheme == Theme.DARK && amoledModeEnabled.value) {
                    applyAmoledMode(scheme)
                } else {
                    scheme
                }
            }
            MaterialTheme(colorScheme = colorScheme) {
                // The activity's manifest theme is static (always light), so without this
                // the system status bar icons stay dark-on-dark whenever the user picks
                // the Dark theme in Settings, and dark-on-light for Warm Paper's lower
                // contrast background - both unreadable. Recompute on every theme change
                // instead of once, since currentTheme can change at runtime.
                val view = LocalView.current
                // Stage 3.1 (see docs/foundational/design-notes.md): a single NavController
                // hoisted here, at the top of the composable tree, same placement
                // "Migrate Jetpack Navigation to Navigation Compose" recommends for the
                // top-level App composable. Every destination below routes through it as
                // of Stage 3.4; Stage 3.5 removed the manual currentScreen/Screen fallback
                // this comment used to describe.
                val navController = rememberNavController()
                // Splash-screen behavior toggles (see SettingsScreen's "Splash Screen"
                // section) - both default to true to match PreferencesManager's own default.
                val splashAnimationEnabled = prefsManager.splashAnimationEnabled.collectAsState(initial = true)
                val splashMusicEnabled = prefsManager.splashMusicEnabled.collectAsState(initial = true)
                // D6 (docs/proposal/performance.md, F13): whether this launch is the
                // first one since an install/update, in which case the full
                // whoosh-and-cross sequence plays; every other launch collapses to a
                // short fade regardless of splashAnimationEnabled's own value. Initial
                // true matches "a fresh process always assumes full until proven
                // otherwise" - the same direction PreferencesManager's -1 sentinel
                // resolves on an unset key, so there's no first-frame flash from true
                // to false before this collects its real value either.
                val fullSplashSequence =
                    prefsManager.shouldShowFullSplash(BuildConfig.VERSION_CODE).collectAsState(initial = true)
                // TTS default-settings state (see docs/foundational/design-notes.md, Stage
                // 3.2) - collected here too, same "avoid a first-frame flash" reasoning
                // above. Initial values match PreferencesManager's own fallbacks (see
                // ttsDefaultRate/ttsPitch/ttsAutoContinue/ttsKeepScreenOn) so the
                // settings/tts screen doesn't flash a different value on first
                // composition while the real read is still in flight.
                val ttsDefaultRate = prefsManager.ttsDefaultRate.collectAsState(initial = 1.0f)
                val ttsPitch = prefsManager.ttsPitch.collectAsState(initial = 1.0f)
                val ttsAutoContinue = prefsManager.ttsAutoContinue.collectAsState(initial = false)
                val ttsKeepScreenOn = prefsManager.ttsKeepScreenOn.collectAsState(initial = false)
                // Voice and favorites, per design-notes.md's "TTS settings split" - collected
                // here too, same reasoning as the four tts_* flows above, so the "Voice" row's
                // current-selection text doesn't flash null->real-value on first
                // composition. The voice *list* itself (availableVoices) isn't a
                // PreferencesManager flow - it comes from TtsPlaybackService via a
                // ChapterTtsState bound inside the settings/tts composable below, same
                // service-owned shape the proposal specifies.
                val ttsVoiceName = prefsManager.ttsVoiceName.collectAsState(initial = null)
                // reader-settings.md's "cheap" batch (suggested-order items
                // 1-5) - collected here alongside the tts_* state above,
                // same hoisted-at-the-Activity shape, no ReaderSettingsViewModel.
                val readerFontSize = prefsManager.readerFontSize.collectAsState(initial = 18f)
                val readerLineHeight = prefsManager.readerLineHeight.collectAsState(initial = 1.8f)
                val readerReadingMode = prefsManager.readerReadingMode.collectAsState(initial = null)
                val readerFontFamily = prefsManager.readerFontFamily.collectAsState(initial = "SERIF")
                val readerKeepScreenOn = prefsManager.readerKeepScreenOn.collectAsState(initial = false)
                val readerFullscreen = prefsManager.readerFullscreen.collectAsState(initial = false)
                val readerBreakLines = prefsManager.readerBreakLines.collectAsState(initial = true)
                val readerBrightnessSliderEnabled = prefsManager.readerBrightnessSliderEnabled.collectAsState(initial = false)
                val readerBrightness = prefsManager.readerBrightness.collectAsState(initial = -1f)
                // Favorite voices (design-notes.md's "TTS settings split") -
                // collected here too, same reasoning as ttsVoiceName above, so
                // settings/tts/voice's stars don't flash empty before the real
                // read lands.
                val ttsFavoriteVoiceNames = prefsManager.ttsFavoriteVoiceNames.collectAsState(initial = emptySet())
                // uiux.md's "Arc cover enlarger is undiscoverable" - collected here
                // too, same reasoning as the flows above. null means "DataStore hasn't
                // answered yet", which is different from false ("answered: not seen
                // yet"). Only a real false plays the one-time hint, so a fresh install
                // is greeted by it as soon as the read lands, and a returning user who
                // already dismissed it never gets a frame of it while loading.
                val hasSeenCoverEnlargeHint = prefsManager.hasSeenCoverEnlargeHint.collectAsState(initial = null)

                // Navigates to whichever novel a "new chapter" notification was tapped
                // for (see handleNotificationIntent/onNewIntent and
                // pendingNotificationNovelId's own doc comment), once that novel
                // actually shows up in `novels`. Keyed on both values: a cold start
                // from a notification tap sets pendingNotificationNovelId before
                // startScan has populated `novels` at all, so this needs to re-run as
                // novels.size grows too, not just once when the pending id itself is
                // first set.
                LaunchedEffect(pendingNotificationNovelId.value, libraryViewModel.novels.size, showSplash.value) {
                    // NavHost isn't composed while the splash is up (the splash branch below
                    // returns before it), so navController has no graph yet and navigate()
                    // throws. Wait; showSplash is a key, so this re-runs once it flips false.
                    if (showSplash.value) return@LaunchedEffect
                    val novelId = pendingNotificationNovelId.value ?: return@LaunchedEffect
                    val target = libraryViewModel.novels.firstOrNull { it.id == novelId } ?: return@LaunchedEffect
                    // Stage 3.3 (see docs/foundational/design-notes.md): NovelDetail is now
                    // its own "novelDetail/{novelId}" route rather than "legacy" - a cold
                    // start from a notification tap begins on "home" (the NavHost's
                    // startDestination), so without this the NavHost would stay parked on
                    // "home", showing the library instead of the tapped novel.
                    // launchSingleTop avoids stacking a duplicate entry if this ever fires
                    // while already there.
                    navController.navigate("novelDetail/${target.id}") { launchSingleTop = true }
                    pendingNotificationNovelId.value = null
                }

                // Resolves and jumps straight into wherever the user last left off,
                // once the "Continue Reading" static shortcut sets
                // pendingContinueReadingShortcut (see handleShortcutIntent) AND
                // `novels` has actually loaded - same two-key gating as the
                // notification effect above, for the same reason (a cold start from
                // the shortcut races startScan). recentNovelIds(1) is
                // reading_progress's own "most recently updated" ordering (see
                // ReadingProgressDao) - unlike Home's onContinueReading row below,
                // which already knows which novel it's resuming, this entry point
                // isn't scoped to any particular novel, so it resumes whichever one
                // was read last, library-wide. Falls through to the Home screen (no
                // navigation) when there's no reading history yet at all, same as the
                // "forNovel is null" branch of onContinueReading further down.
                //
                // Was (bug: "shortcut opens Home and that's it"): this cleared
                // pendingContinueReadingShortcut unconditionally at the end, whether
                // or not `novel` actually resolved. `libraryViewModel.novels.isEmpty()`
                // above only guards the very first instant (nothing scanned yet) - a
                // cold start past that point still has `novels` filling in
                // incrementally as startScan works through the folder, same as
                // pendingNotificationNovelId's own comment above already describes.
                // If the last-read novel's scan entry simply hadn't landed in
                // `novels` yet the one time this ran, `novel` came back null, nothing
                // navigated, and the unconditional clear meant it would never retry -
                // even once that novel *did* show up moments later and re-triggered
                // this effect via the novels.size key, the very first line
                // (`!pendingContinueReadingShortcut.value`) now short-circuited it.
                // Landing on Home with no error and no second chance is exactly that.
                // Fixed the same way pendingNotificationNovelId's effect already
                // does it: only clear the flag on an outcome that's actually final -
                // success, or no reading history at all (recentNovelIds empty, which
                // more scanning can never change) - and leave it set, retrying on the
                // next `novels.size` change, for "novel not found (yet)".
                LaunchedEffect(pendingContinueReadingShortcut.value, libraryViewModel.novels.size, showSplash.value) {
                    if (!pendingContinueReadingShortcut.value) return@LaunchedEffect
                    // Same reason as the notification effect above: navigating before
                    // NavHost has set the graph (i.e. during the splash) throws, and
                    // nothing catches an exception thrown inside a LaunchedEffect. This is
                    // what crashed the second launch once reading_progress survived a
                    // rescan (@Upsert) - the effect used to bail out earlier with no history.
                    if (showSplash.value) return@LaunchedEffect
                    // Still waiting on startScan to populate `novels` - leave the flag
                    // set so this re-runs once libraryViewModel.novels.size changes,
                    // instead of giving up on a race it can still win.
                    if (libraryViewModel.novels.isEmpty()) return@LaunchedEffect
                    val lastNovelId = libraryViewModel.lastReadNovelId()
                    if (lastNovelId == null) {
                        // No reading history at all, ever - not a race, nothing for
                        // more scanning to surface. Fall through to Home and don't
                        // keep re-querying this on every subsequent scan update.
                        pendingContinueReadingShortcut.value = false
                        return@LaunchedEffect
                    }
                    // Not yet in `novels` - unlike the empty-history case above, this
                    // can resolve once the scan catches up, so leave the flag set and
                    // let the next novels.size change retry instead of giving up here.
                    val novel = libraryViewModel.novels.firstOrNull { it.id == lastNovelId }
                        ?: return@LaunchedEffect
                    // Shared with HomeScreen's onContinueReading - see
                    // navigateToChapterOrDetail's own doc comment.
                    val chapterId = libraryViewModel.resolveLastReadChapterId(novel)
                    navigateToChapterOrDetail(novel, chapterId, navController)
                    pendingContinueReadingShortcut.value = false
                }

                if (showSplash.value) {
                    // Splash always renders on its own solid-black canvas (see
                    // SplashScreen.kt) regardless of the selected reader theme, so force
                    // light/white status bar icons here rather than deriving them from
                    // colorScheme.background like the real content below does, and paint
                    // the status bar itself black to match that canvas (see the SideEffect
                    // below for why statusBarColor needs to be set explicitly at all).
                    SideEffect {
                        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                        window.statusBarColor = Color.Black.toArgb()
                    }
                    SplashScreen(
                        animationEnabled = splashAnimationEnabled.value,
                        musicEnabled = splashMusicEnabled.value,
                        fullSequence = fullSplashSequence.value,
                        onFinished = {
                            showSplash.value = false
                            // Record this version as having shown the full sequence
                            // (a no-op write if it already had) so the next cold
                            // start - this version or not - re-evaluates
                            // shouldShowFullSplash freshly rather than assuming.
                            lifecycleScope.launch {
                                prefsManager.markFullSplashShown(BuildConfig.VERSION_CODE)
                            }
                        }
                    )
                    return@MaterialTheme
                }

                // The manifest's android:theme is a fixed system light theme, so its
                // statusBarColor default never tracks Light/Dark/Warm Paper - the status
                // bar stayed one fixed color while everything below it changed, sticking
                // out against every theme but the one that happened to match the default.
                // Painting it with the resolved background on every theme change (not just
                // fixing the icon color above) makes the status bar read as part of the
                // app's surface instead of a leftover system chrome strip.
                SideEffect {
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                        colorScheme.background.luminance() > 0.5f
                    window.statusBarColor = colorScheme.background.toArgb()
                }

                // The manifest's android:theme is a fixed system light theme (needed before
                // Compose ever runs), and MaterialTheme's colorScheme only styles the widgets
                // that explicitly read it - it does NOT paint the window/root background for
                // you. Without an explicit background here, any area not covered by a themed
                // widget (status bar edge-to-edge gaps, screen transition frames, etc.) shows
                // the underlying light window background regardless of Dark/Warm Paper being
                // selected - the theme "not applying evenly" that users see when switching
                // themes. Painting the root with the resolved background fixes that.
                //
                // Surface (not a plain Column + .background()) is required here, not just
                // for the paint: Surface is what sets LocalContentColor to `contentColor`
                // for everything composed inside it. A plain Column().background(...) paints
                // the background fine but never touches LocalContentColor, which is left at
                // its hardcoded default (black) - so every Text()/Icon() below that doesn't
                // pass an explicit `color` renders black regardless of theme. That was
                // invisible in Light/Warm Paper (black-on-light already looks intentional)
                // but unreadable in Dark theme (black-on-near-black). Routing the whole tree
                // through Surface fixes it for every screen at once instead of patching each
                // Text() call individually.
                // Home-screen rework (see navigation/AppBottomBar.kt's own doc comment):
                // Home, Browse All Novels, Notice Board and Settings are now one
                // persistent bottom bar instead of four separately-reached screens.
                // currentBackStackEntryAsState is the standard Navigation-Compose way to
                // know "what route is actually on screen right now" reactively - it's
                // what both drives which tab is highlighted and decides whether the bar
                // shows at all (isBottomBarRoute: only on the four tab destinations
                // themselves, never NovelDetail/Reader/Author/any settings/* sub-page -
                // see that function's own doc comment).
                // .value, not `by` - this file never imports androidx.compose.runtime.getValue
                // and uses the explicit-.value style for every other collectAsState()/
                // mutableStateOf() read above (splashAnimationEnabled.value, etc.);
                // matching that here instead of introducing the only `by` delegate in the
                // file is what the build log from the previous patch's attempted build
                // caught - `by` needs that import's getValue/setValue operators and this
                // file doesn't have it.
                val currentBackStackEntry = navController.currentBackStackEntryAsState()
                val currentRoute = currentBackStackEntry.value?.destination?.route
                // Bug 4 fix (docs/proposal/bugs/other/uiux-bugs.md): metadataSearchState
                // is a single app-wide ViewModel field, not scoped to whichever
                // "novelDetail/{novelId}" route was on screen when fetchMetadataFor was
                // called - without this, a "Fetch info" result that resolves after the user
                // has already navigated to a different novel (or away entirely) pops up
                // over whatever's currently on screen and, if acted on, applies the fetched
                // metadata to the wrong novel with no indication anything happened. Null
                // when the current route isn't novelDetail/{novelId} at all.
                val currentNovelDetailId = if (currentRoute == "novelDetail/{novelId}") {
                    currentBackStackEntry.value?.arguments?.getString("novelId")
                } else null

                // landscape-tablet.md's cutout fix: every route except Reader stays
                // non-edge-to-edge today, so in landscape the display cutout is
                // letterboxed solid black by the platform (see applyWindowLayout's own
                // doc comment for why DEFAULT does that in landscape specifically).
                // Reader already opts into edge-to-edge + SHORT_EDGES for itself and
                // paints its own background under the cutout; this does the same for
                // every other screen, letting Scaffold's own containerColor (already
                // the theme background) show through the cutout instead of the
                // platform's black letterbox - colour-matched, nothing drawn on it
                // (Scaffold's default contentWindowInsets still keeps content itself
                // clear of the cutout/status bar). Reader's own onDispose resets to
                // edgeToEdge=false when a reader route is left (see ReaderScreen.kt);
                // this effect re-applies the true/true default the moment currentRoute
                // changes away from a reader route, so the two hand off without a
                // lingering black frame either way.
                LaunchedEffect(currentRoute) {
                    if (currentRoute?.startsWith("reader/") != true) {
                        applyWindowLayout(window, edgeToEdge = true, drawIntoCutout = true)
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = colorScheme.background,
                    contentColor = colorScheme.onBackground,
                    // The full-screen/cutout bug (see ReaderScreen.kt's applyWindowLayout
                    // doc comment) was fixed there and *stayed* fixed in isolation, but the
                    // home-screen rework below wraps every route - Reader included - in this
                    // one shared Scaffold, and Scaffold's own default contentWindowInsets is
                    // WindowInsets.safeDrawing: status bars + nav bars + the display cutout.
                    // That's correct for every other screen (they don't manage insets
                    // themselves), but for Reader it reserves the exact same cutout-sized gap
                    // a second time at this outer level - and that gap isn't painted by the
                    // Surface below, since `.fillMaxSize().padding(innerPadding)` shrinks the
                    // Surface's own bounds by it rather than letting the Surface's background
                    // reach into it. What shows through is the *window's* own background
                    // (themes.xml's Theme.ARKarium, solid black) - the reported "notch area
                    // rendered as a black block," present in both orientations because
                    // WindowInsets.displayCutout never goes to zero just because the status
                    // bar is hidden. Reader already re-pads its own content by
                    // WindowInsets.displayCutout and paints its own background under it (see
                    // its root Column) - it needs this outer reservation to be zero, not
                    // doubled. Every other route keeps the normal default.
                    contentWindowInsets = if (currentRoute?.startsWith("reader/") == true)
                        WindowInsets(0, 0, 0, 0)
                    else
                        ScaffoldDefaults.contentWindowInsets,
                    bottomBar = {
                        if (isBottomBarRoute(currentRoute)) {
                            AppBottomBar(
                                currentRoute = currentRoute,
                                onTabSelected = { destination ->
                                    // Standard "switch tabs, don't push a new one every
                                    // time" pattern: popUpTo the start destination with
                                    // saveState so each tab's own back stack/scroll
                                    // position is kept rather than torn down, restoreState
                                    // brings that state back when the tab is revisited,
                                    // and launchSingleTop stops repeated taps on the same
                                    // tab from piling up duplicate entries.
                                    navController.navigate(destination.route) {
                                        popUpTo("home") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    }
                ) { innerPadding ->
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    color = colorScheme.background,
                    contentColor = colorScheme.onBackground
                ) {
                // The NavHost and all 19 route destinations used to be written out
                // inline right here (~1500 lines) - they now live in their own file,
                // navigation/AppNavGraph.kt, as the AppNavGraph composable. This call
                // site just hands it the NavController and the handful of hoisted
                // preference State the settings/* destinations render (see
                // AppNavGraph's own doc comment for why those are threaded through
                // rather than re-collected per-destination).
                AppNavGraph(
                    activity = this@MainActivity,
                    navController = navController,
                    resolvedTheme = resolvedTheme,
                    currentTheme = currentTheme,
                    currentSystemDefaultLightVariant = currentSystemDefaultLightVariant,
                    amoledModeEnabled = amoledModeEnabled,
                    splashAnimationEnabled = splashAnimationEnabled,
                    splashMusicEnabled = splashMusicEnabled,
                    ttsDefaultRate = ttsDefaultRate,
                    ttsPitch = ttsPitch,
                    ttsAutoContinue = ttsAutoContinue,
                    ttsKeepScreenOn = ttsKeepScreenOn,
                    ttsVoiceName = ttsVoiceName,
                    readerFontSize = readerFontSize,
                    readerLineHeight = readerLineHeight,
                    readerReadingMode = readerReadingMode,
                    readerFontFamily = readerFontFamily,
                    readerKeepScreenOn = readerKeepScreenOn,
                    readerFullscreen = readerFullscreen,
                    readerBreakLines = readerBreakLines,
                    readerBrightnessSliderEnabled = readerBrightnessSliderEnabled,
                    readerBrightness = readerBrightness,
                    ttsFavoriteVoiceNames = ttsFavoriteVoiceNames,
                    hasSeenCoverEnlargeHint = hasSeenCoverEnlargeHint
                )
                } // closes Surface
                } // closes Scaffold

                // Phase A, rung A1 of docs/implementation/ui-layer-reorg.md: the
                // five metadata/sync dialog `when` blocks that used to be written out
                // inline here now live in AppDialogHost, called once as the Scaffold's
                // sibling - same position, same five dialogs, same state/callbacks.
                AppDialogHost(
                    metadataViewModel = metadataViewModel,
                    syncViewModel = syncViewModel,
                    currentNovelDetailId = currentNovelDetailId,
                    context = this@MainActivity
                )
            }
        }
    }
}
