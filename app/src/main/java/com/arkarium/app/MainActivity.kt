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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.arkarium.app.ui.ChapterEditorScreen
import com.arkarium.app.ui.AuthorPageScreen
import com.arkarium.app.ui.HomeScreen
import com.arkarium.app.ui.LegalContent
import com.arkarium.app.ui.LegalDocumentScreen
import com.arkarium.app.ui.AddFictionByNameDialog
import com.arkarium.app.ui.MetadataSearchDialog
import com.arkarium.app.ui.NovelDetailScreen
import com.arkarium.app.ui.ReaderScreen
import com.arkarium.app.ui.SettingsScreen
import com.arkarium.app.ui.SplashScreen
import com.arkarium.app.ui.WebViewScreen
import com.arkarium.app.ui.SyncProgressDialog
import com.arkarium.app.ui.SyncResolutionDialog
import com.arkarium.app.ui.FictionBrowseScreen
import com.arkarium.app.data.AppDatabase
import com.arkarium.app.data.AuthorEntity
import com.arkarium.app.data.ChapterOverrideEntity
import com.arkarium.app.data.GoogleBooksMetadataProvider
import com.arkarium.app.data.resolveLibraryRoot
import com.arkarium.app.data.ReadingProgressEntity
import com.arkarium.app.data.ScannerImpl
import com.arkarium.app.data.NewChapterCheckWorker
import com.arkarium.app.data.NewChapterNotifier
import com.arkarium.app.data.NovelEntity
import com.arkarium.app.data.ChapterEntity
import com.arkarium.app.data.PreferencesManager
import com.arkarium.app.data.SyncManager
import com.arkarium.app.data.NovelStatus
import com.arkarium.app.data.TextChapterContentRepository
import com.arkarium.app.navigation.Screen
import com.arkarium.app.navigation.MetadataSearchState
import com.arkarium.app.navigation.AddFictionState
import com.arkarium.app.navigation.SyncAllState
import com.arkarium.app.navigation.SyncCheckState
import com.arkarium.app.navigation.SyncResolutionReason
import com.arkarium.app.navigation.SyncResolutionState
import com.arkarium.app.ui.theme.colorSchemeFor
import com.arkarium.app.ui.theme.resolveTheme
import com.arkarium.app.viewmodel.LibraryViewModel
import com.arkarium.app.viewmodel.MetadataViewModel
import com.arkarium.app.viewmodel.SettingsViewModel
import com.arkarium.app.viewmodel.SyncViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// Screen (navigation destinations) and the *SearchState/*State dialog-driving sealed
// classes formerly declared here now live in navigation/AppState.kt; the color-scheme
// helpers (warmPaperColorScheme, colorSchemeFor, resolveTheme) formerly declared here
// now live in ui/theme/AppTheme.kt - see docs/arkarium/REFACTOR_PLAN.md. Only the
// imports above changed; every call site below is unchanged since the types/functions
// kept their names.
//
// currentTheme/currentSystemDefaultLightVariant/useCustomFolder/savedUri formerly
// lived here as an Activity mutableStateOf field (the first two) or a per-composition
// collectAsState() local (the last two) - both now come from settingsViewModel
// (Stage 2.2 of Phase 2, see docs/arkarium/REFACTOR_PLAN.md). Every read site below
// (`currentTheme.value`, `useCustomFolder.value`, etc.) is unchanged, since
// settingsViewModel's StateFlows are collected under those same local names.
//
// metadataSearchState/addFictionState and fetchMetadataFor/applyMetadata formerly
// lived here too - all four now come from metadataViewModel (Stage 2.4 of Phase 2,
// see docs/arkarium/REFACTOR_PLAN.md).
//
// syncAllState/syncCheckState/syncResolutionState and the sync/resolution logic
// against SyncManager - scanSingleSyncedNovel, addFictionByName, syncAllRaeArkNovels,
// checkForUpdates, and the three resolveXxx actions - formerly lived here too. All of
// it now comes from syncViewModel (Stage 2.5 of Phase 2, the last of the four
// ViewModels the plan calls for - see docs/arkarium/REFACTOR_PLAN.md and
// SyncViewModel's own doc comment for why addFictionByName moved here rather than
// staying with the addFictionState it writes). Every place this file used to patch
// currentScreen directly inside one of those functions used to do it via an
// onApplied/onUpdated callback passed to metadataViewModel/syncViewModel instead;
// Stage 3.3 of Phase 3 (see docs/arkarium/REFACTOR_PLAN.md) removed all of those
// callbacks once NovelDetail became a "novelDetail/{novelId}" route that resolves its
// own NovelEntity from libraryViewModel.novels, making the currentScreen patch each one
// existed for unnecessary.

class MainActivity : ComponentActivity() {

    // novels/chapters/arcs/recentlyRead/inProgressNovels/overriddenChapterIds/
    // arcStartChapterIds/scanProgress/scanMessage formerly lived here as Activity
    // mutableStateOf/mutableStateListOf fields - all now come from libraryViewModel
    // (Stage 2.3 of Phase 2, see docs/arkarium/REFACTOR_PLAN.md). Every read site
    // below is unchanged except for the `libraryViewModel.` prefix, since the fields
    // kept their names.
    // Author of the fiction currently open in the reader. Stage 3.4 (see
    // docs/arkarium/REFACTOR_PLAN.md) moved resolution from each entry point setting
    // this manually into a LaunchedEffect(novelId) inside the "reader/{novelId}/
    // {chapterId}" composable itself, keyed off the route argument - same pattern
    // Screen.Author's own LaunchedEffect(authorId) already used. Still looked up once
    // per novel rather than reactively per-recomposition - it doesn't change across a
    // Previous/Next hop within the same fiction, only when a *different* fiction's
    // reader is opened. Null covers both "not resolved yet" and "this fiction has no
    // linked author" (see NovelEntity.authorId).
    private val readerAuthor = mutableStateOf<AuthorEntity?>(null)
    // Backs Screen.Author - reloaded via LaunchedEffect whenever the authorId changes,
    // same "state lives in the Activity, screen just renders it" pattern as chapters/arcs.
    private val authorPageAuthor = mutableStateOf<AuthorEntity?>(null)
    private val authorPageNovels = mutableStateListOf<NovelEntity>()
    private val currentScreen = mutableStateOf<Screen>(Screen.Home)
    // Gates the branded splash (see SplashScreen.kt) shown for a moment on every
    // cold launch before the real UI (Home, or wherever currentScreen already
    // points) becomes visible. Lives here rather than as a Screen case since it's
    // not a navigable destination - nothing ever sets currentScreen back to it.
    private val showSplash = mutableStateOf(true)
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
    private var pendingNotifyToggleNovelId: String? = null
    private lateinit var db: AppDatabase
    private lateinit var scanner: ScannerImpl
    private lateinit var prefsManager: PreferencesManager
    private lateinit var contentRepo: TextChapterContentRepository
    private lateinit var syncManager: SyncManager
    // Stage 2.2 of Phase 2 (docs/arkarium/REFACTOR_PLAN.md) - the first ViewModel
    // pulled out of MainActivity. Constructed in onCreate right after prefsManager
    // (its only dependency), same lateinit-set-in-onCreate pattern as the services
    // above, rather than as a `by viewModels { }` property delegate - prefsManager
    // isn't constructed yet at property-initializer time, before onCreate runs.
    private lateinit var settingsViewModel: SettingsViewModel
    // Stage 2.3 of Phase 2 (docs/arkarium/REFACTOR_PLAN.md) - the second ViewModel
    // pulled out of MainActivity. Constructed in onCreate right after db/scanner
    // (its two dependencies), same pattern as settingsViewModel above.
    private lateinit var libraryViewModel: LibraryViewModel
    // Stage 2.4 of Phase 2 (docs/arkarium/REFACTOR_PLAN.md) - the third ViewModel
    // pulled out of MainActivity. Constructed in onCreate right after libraryViewModel
    // (one of its two dependencies, alongside db), same pattern as libraryViewModel
    // itself.
    private lateinit var metadataViewModel: MetadataViewModel
    // Stage 2.5 of Phase 2 (docs/arkarium/REFACTOR_PLAN.md) - the fourth and last
    // ViewModel pulled out of MainActivity. Constructed in onCreate right after
    // metadataViewModel (one of its dependencies, alongside db/scanner/syncManager/
    // libraryViewModel), same pattern as the other three.
    private lateinit var syncViewModel: SyncViewModel

    // Only ever launched from a "Notify me when new chapters are available" toggle
    // being switched on (see NovelDetailScreen's onToggleNotify wiring below) - API <
    // 33 has no such runtime permission to request in the first place (notifications
    // there are granted at install time), so this is never triggered on those versions;
    // the toggle's callback checks Build.VERSION.SDK_INT before ever calling launch().
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val novelId = pendingNotifyToggleNovelId
        pendingNotifyToggleNovelId = null
        // Denied: the toggle simply stays off (setNotifyEnabled is never called, so
        // notify_new_chapters keeps whatever value it already had - false, since this
        // path only runs when the user just tried to turn it on). They can grant the
        // permission from system settings later and flip the toggle again.
        if (granted && novelId != null) {
            lifecycleScope.launch { setNotifyEnabled(novelId, true) }
        }
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { selectedUri ->
            contentResolver.takePersistableUriPermission(selectedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            lifecycleScope.launch {
                // Picking a folder here only ever happens once "Use custom folder" is on
                // (see SettingsScreen) - persisting it true here too is just defensive:
                // it keeps this the single source of truth for "which folder did the user
                // pick" even if some future caller ever launches pickFolder before the
                // toggle has been flipped. Kept as direct, awaited prefsManager suspend
                // calls (not settingsViewModel.setUseCustomFolder/setLibraryUri, which
                // fire-and-forget into their own viewModelScope launch) so both writes
                // are guaranteed to land, in order, before novels.clear()/startScan below
                // run in this same coroutine - unchanged from pre-Stage-2.2 behavior.
                prefsManager.setUseCustomFolder(true)
                prefsManager.setLibraryUri(selectedUri.toString())
                // Switching to a newly-picked folder mints entirely different novel IDs
                // (see ScannerImpl's id hash, keyed off root.uri) - clear first so the
                // reconciliation in startScan's onScanCompleted doesn't have to wait for a
                // second scan pass to drop the old source's now-stale novels.
                libraryViewModel.novels.clear()
                libraryViewModel.startScan(DocumentFile.fromTreeUri(this@MainActivity, selectedUri) ?: return@launch)
            }
        }
    }

    // resolveLibraryRoot and mergeNovelForRescan formerly declared here now live in
    // data/LibraryScan.kt as plain top-level functions (Stage 2.1 of Phase 2 - see
    // docs/arkarium/REFACTOR_PLAN.md). Every call site below is unchanged except
    // resolveLibraryRoot's, which now passes `this@MainActivity` as the new explicit
    // `context` parameter (several call sites are inside `lifecycleScope.launch { }`/
    // Composable lambdas, where a bare `this` would resolve to the wrong receiver -
    // see e.g. pickFolder's existing `DocumentFile.fromTreeUri(this@MainActivity,
    // ...)` above for the same pattern already in use).

    // scanSingleSyncedNovel formerly lived here too (see docs/arkarium/NEXT_FIXES.md
    // #4) - it now lives on syncViewModel alongside syncAllRaeArkNovels, its only
    // caller (Stage 2.5 of Phase 2, see docs/arkarium/REFACTOR_PLAN.md).

    // startScan, loadNovelDetails, and refreshRecentlyRead formerly lived here as
    // Activity-private methods - all three now live on libraryViewModel (Stage 2.3 of
    // Phase 2, see docs/arkarium/REFACTOR_PLAN.md), unchanged in body except for the
    // fields they read/write resolving to that class's own novels/chapters/arcs/
    // recentlyRead/inProgressNovels/scanProgress/scanMessage instead of the
    // Activity's. Every call site below (`startScan(...)`, `loadNovelDetails(...)`,
    // `refreshRecentlyRead()`) now calls `libraryViewModel.` + the same name.

    // Loads the AuthorEntity + every novel linked to it (NovelDao.byAuthor) for
    // Screen.Author. A missing/unknown authorId (author.json removed since the fiction
    // page or reader last resolved it) just leaves authorPageAuthor null - AuthorPage's
    // caller below treats that as "nothing to show" rather than crashing, same
    // never-fail-on-missing-optional-metadata guarantee the rest of the app follows.
    private suspend fun loadAuthorPage(authorId: String) {
        authorPageAuthor.value = db.authorDao().findById(authorId)
        authorPageNovels.clear()
        authorPageNovels.addAll(db.novelDao().byAuthor(authorId))
    }

    // Diffs the editor's edited chapter list against the raw scanned chapters and
    // persists per-chapter overrides only where something actually changed. If an edit
    // matches the scanned default again, any stale override for that chapter is removed
    // instead of being kept around with stale values.
    private suspend fun saveChapterEdits(novel: NovelEntity, edited: List<ChapterEntity>, arcStartIds: Set<String>) {
        val raw = db.chapterDao().forNovel(novel.id) // natural scan order, no overrides
        val rawById = raw.associateBy { it.id }
        val rawIndexById = raw.withIndex().associate { (i, c) -> c.id to i }

        edited.forEachIndexed { index, chapter ->
            val original = rawById[chapter.id] ?: return@forEachIndexed
            val titleOverride = if (chapter.title != original.title) chapter.title else null
            val positionOverride = if (index != rawIndexById[chapter.id]) index else null
            val isArcStart = chapter.id in arcStartIds

            if (titleOverride != null || positionOverride != null || isArcStart) {
                db.chapterOverrideDao().upsert(
                    ChapterOverrideEntity(
                        id = UUID.nameUUIDFromBytes("override:${chapter.id}".toByteArray()).toString(),
                        chapterId = chapter.id,
                        titleOverride = titleOverride,
                        positionOverride = positionOverride,
                        isArcStart = isArcStart
                    )
                )
            } else {
                db.chapterOverrideDao().delete(chapter.id)
            }
        }
        libraryViewModel.loadNovelDetails(novel)
    }

    private suspend fun saveReadingProgress(novelId: String, chapterId: String, progress: Float) {
        db.readingProgressDao().upsert(
            ReadingProgressEntity(
                novelId = novelId,
                chapterId = chapterId,
                position = progress,
                positionType = "PERCENTAGE",
                updatedAt = System.currentTimeMillis()
            )
        )
        libraryViewModel.refreshRecentlyRead()
    }

    // addFictionByName, syncAllRaeArkNovels, and checkForUpdates formerly lived here
    // too - all three now live on syncViewModel (Stage 2.5 of Phase 2, see
    // docs/arkarium/REFACTOR_PLAN.md and SyncViewModel's own doc comment), unchanged
    // in body except for the fields/services they read/write resolving to that
    // class's own state/constructor dependencies instead of the Activity's, and
    // checkForUpdates' currentScreen patch on a successful resync now happening via
    // an `onUpdated` callback passed in from each call site below instead of inline.

    // Backs NovelDetailScreen's "Notify me when new chapters are available" toggle
    // (see docs/arkarium/NEW_CHAPTER_NOTIFICATIONS.md). The actual permission gate lives in the
    // toggle's onToggleNotify callback below (which calls this directly when already
    // granted, or via notificationPermission's result callback otherwise) - this
    // function itself just persists the change and refreshes in-memory state, same
    // "update DB then patch `novels`" pattern checkForUpdates uses. Used to also patch
    // currentScreen when NovelDetail was showing the toggled novel; Stage 3.3 (see
    // docs/arkarium/REFACTOR_PLAN.md) made that unnecessary - the "novelDetail/{novelId}"
    // route now resolves its novel from libraryViewModel.novels on every recomposition,
    // so patching that list here is enough on its own.
    private suspend fun setNotifyEnabled(novelId: String, enabled: Boolean) {
        db.novelDao().updateNotifyNewChapters(novelId, enabled)
        val updated = db.novelDao().findById(novelId) ?: return
        withContext(Dispatchers.Main) {
            val idx = libraryViewModel.novels.indexOfFirst { it.id == updated.id }
            if (idx >= 0) libraryViewModel.novels[idx] = updated
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    // resolveMissingFolderBySyncing/resolveByRemovingFromLibrary/
    // resolveSourceGoneByUnlinking - the three resolution actions SyncResolutionDialog
    // offers once checkForUpdates hits a MissingLocalFolderException or
    // SourceGoneException (see docs/arkarium/NEXT_FIXES.md #2) - formerly lived here
    // too. All three now live on syncViewModel alongside the syncResolutionState they
    // drive (Stage 2.5 of Phase 2, see docs/arkarium/REFACTOR_PLAN.md), each now
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

    private fun renderCrashScreen(e: Throwable) {
        renderCrashScreen(
            "ARKarium failed to start",
            (e::class.java.name + ": " + e.message) + "\n\n" +
                e.stackTrace.take(30).joinToString("\n") { "  at $it" }
        )
    }

    companion object {
        private const val CRASH_PREFS = "arkarium_crash_info"
        private const val CRASH_KEY = "last_crash"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Safety net of last resort. The try/catch blocks below only catch exceptions
        // thrown synchronously on THIS call stack - they cannot catch anything thrown
        // inside a lifecycleScope.launch { } coroutine body (theme.collect, libraryUri.
        // collect, etc.), since those run on their own dispatcher and throw well after
        // the launch{} call that started them has already returned. An uncaught
        // exception there crashes the process instantly with nothing on screen and
        // nothing catchable here - which is exactly what "opens and immediately exits,
        // no error screen" looks like. Installing a global handler is the only way to
        // observe it: it can't stop the crash (the process still has to die), but it
        // persists the trace first so the NEXT launch can show it.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                getSharedPreferences(CRASH_PREFS, MODE_PRIVATE).edit()
                    .putString(
                        CRASH_KEY,
                        "ARKarium v${BuildConfig.VERSION_NAME}\n\n" +
                            (throwable::class.java.name + ": " + throwable.message) + "\n\n" +
                            throwable.stackTrace.take(30).joinToString("\n") { "  at $it" }
                    )
                    .commit() // commit(), not apply() - must be on disk before the process dies
            } catch (_: Throwable) {
                // Never let the crash handler itself throw and mask the original crash.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }

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
            scanner = ScannerImpl(this)
            prefsManager = PreferencesManager(this)
            contentRepo = TextChapterContentRepository(this)
            syncManager = SyncManager(this)
            settingsViewModel = ViewModelProvider(this, SettingsViewModel.factory(prefsManager))
                .get(SettingsViewModel::class.java)
            libraryViewModel = ViewModelProvider(this, LibraryViewModel.factory(db, scanner))
                .get(LibraryViewModel::class.java)
            metadataViewModel = ViewModelProvider(
                this,
                MetadataViewModel.factory(db, GoogleBooksMetadataProvider(), libraryViewModel)
            ).get(MetadataViewModel::class.java)
            syncViewModel = ViewModelProvider(
                this,
                SyncViewModel.factory(application, db, scanner, syncManager, libraryViewModel, metadataViewModel)
            ).get(SyncViewModel::class.java)
        } catch (e: Throwable) {
            renderCrashScreen(e)
            return
        }

        // Schedules (or, on every launch after the first, no-ops against - see
        // ExistingPeriodicWorkPolicy.KEEP) the periodic background "new chapter" check
        // (see docs/arkarium/NEW_CHAPTER_NOTIFICATIONS.md). Safe to call unconditionally on every
        // cold launch regardless of whether any novel currently has notifications
        // turned on - the worker itself queries notifyEnabledSynced() and returns
        // immediately if that's empty, so scheduling it eagerly here just means it's
        // already running by the time the user first flips a toggle on, rather than
        // needing this call moved to wherever the very first toggle happens.
        NewChapterCheckWorker.schedule(this)

        // A cold start from a "new chapter" notification tap delivers its extras via
        // this initial intent, not onNewIntent (that only fires for an already-running
        // instance - see the manifest's launchMode="singleTop"). Safe to call before
        // `novels` has loaded anything: this only ever stashes the novel id for
        // renderMainContent's LaunchedEffect to act on once startScan (kicked off
        // below) actually populates it.
        handleNotificationIntent(intent)

        // Auto-scan on every cold launch, before the user has touched anything.
        // Custom folder OFF (the default): always resolves to the app's private storage
        // folder, so this runs and populates the library with zero user interaction ever
        // required. Custom folder ON: resolves to the saved SAF tree, or null if the
        // toggle is on but nothing's been picked yet - in which case this deliberately
        // does nothing and leaves EmptyLibraryPrompt/Settings to prompt for a folder.
        // startScan() already fails soft internally, but this outer catch is the last
        // line of defense so that literally nothing thrown on this path (an unexpected DB
        // error, a revoked SAF grant surfacing oddly, etc) can crash the app on startup.
        // An uncaught exception here previously did exactly that.
        lifecycleScope.launch {
            combine(prefsManager.useCustomFolder, prefsManager.libraryUri) { useCustom, uri -> useCustom to uri }
                .collect { (useCustom, uri) ->
                    if (libraryViewModel.novels.isEmpty()) {
                        try {
                            resolveLibraryRoot(this@MainActivity, useCustom, uri)?.let { libraryViewModel.startScan(it) }
                        } catch (e: Exception) {
                            libraryViewModel.reportScanSetupError("Couldn't load your library: ${e.message}")
                        }
                    }
                }
        }

        // Theme and its System Default sub-preference are no longer watched here -
        // settingsViewModel's currentTheme/currentSystemDefaultLightVariant StateFlows
        // (Stage 2.2 of Phase 2, see docs/arkarium/REFACTOR_PLAN.md) already collect
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
            // Read here (rather than down with savedUri/useCustomFolder below) since
            // resolvedTheme, computed immediately below, needs them before
            // MaterialTheme{} even opens.
            val currentTheme = settingsViewModel.currentTheme.collectAsState()
            val currentSystemDefaultLightVariant = settingsViewModel.currentSystemDefaultLightVariant.collectAsState()
            val resolvedTheme = resolveTheme(
                currentTheme.value,
                currentSystemDefaultLightVariant.value,
                systemInDarkTheme
            )
            val colorScheme = colorSchemeFor(resolvedTheme)
            MaterialTheme(colorScheme = colorScheme) {
                // The activity's manifest theme is static (always light), so without this
                // the system status bar icons stay dark-on-dark whenever the user picks
                // the Dark theme in Settings, and dark-on-light for Warm Paper's lower
                // contrast background - both unreadable. Recompute on every theme change
                // instead of once, since currentTheme can change at runtime.
                val view = LocalView.current
                // Stage 3.1 (see docs/arkarium/REFACTOR_PLAN.md): a single NavController
                // hoisted here, at the top of the composable tree, same placement
                // "Migrate Jetpack Navigation to Navigation Compose" recommends for the
                // top-level App composable. Only Settings/PrivacyPolicy/TermsAndConditions/
                // AboutMe route through it for now (see the "legacy" NavHost destination
                // below) - every other Screen case still routes through currentScreen's
                // manual `when` block until later Stage 3.x's migrate them too.
                val navController = rememberNavController()
                // Hoisted above the splash/Surface split (rather than declared inside the
                // Column below, where they used to live) so the sync dialogs further down -
                // which are siblings of Surface{}, not descendants of that Column - can also
                // resolve a library root for "Add fiction from URL" / "Check for updates"
                // without re-collecting these flows a second time.
                val savedUri = settingsViewModel.savedUri.collectAsState()
                val useCustomFolder = settingsViewModel.useCustomFolder.collectAsState()
                // Splash-screen behavior toggles (see SettingsScreen's "Splash Screen"
                // section) - collected here too, same reasoning as savedUri/useCustomFolder
                // above, and both default to true to match PreferencesManager's own default.
                val splashAnimationEnabled = prefsManager.splashAnimationEnabled.collectAsState(initial = true)
                val splashMusicEnabled = prefsManager.splashMusicEnabled.collectAsState(initial = true)

                // Navigates to whichever novel a "new chapter" notification was tapped
                // for (see handleNotificationIntent/onNewIntent and
                // pendingNotificationNovelId's own doc comment), once that novel
                // actually shows up in `novels`. Keyed on both values: a cold start
                // from a notification tap sets pendingNotificationNovelId before
                // startScan has populated `novels` at all, so this needs to re-run as
                // novels.size grows too, not just once when the pending id itself is
                // first set.
                LaunchedEffect(pendingNotificationNovelId.value, libraryViewModel.novels.size) {
                    val novelId = pendingNotificationNovelId.value ?: return@LaunchedEffect
                    val target = libraryViewModel.novels.firstOrNull { it.id == novelId } ?: return@LaunchedEffect
                    // Stage 3.3 (see docs/arkarium/REFACTOR_PLAN.md): NovelDetail is now
                    // its own "novelDetail/{novelId}" route rather than "legacy" - a cold
                    // start from a notification tap begins on "home" (the NavHost's
                    // startDestination), so without this the NavHost would stay parked on
                    // "home", showing the library instead of the tapped novel.
                    // launchSingleTop avoids stacking a duplicate entry if this ever fires
                    // while already there.
                    navController.navigate("novelDetail/${target.id}") { launchSingleTop = true }
                    pendingNotificationNovelId.value = null
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
                        onFinished = { showSplash.value = false }
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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = colorScheme.background,
                    contentColor = colorScheme.onBackground
                ) {
                // Stage 3.1-3.2 (see docs/arkarium/REFACTOR_PLAN.md): NavHost scaffolding.
                // "legacy" is every Screen case that hasn't been migrated off manual
                // currentScreen routing yet - it just wraps the old when-block verbatim,
                // still switching on currentScreen.value exactly as before. "home" and
                // "fictionBrowse" (Stage 3.2) and Settings/PrivacyPolicy/
                // TermsAndConditions/AboutMe (Stage 3.1) get their own fixed routes
                // below; only NovelDetail/Reader/ChapterEditor/Author still route
                // through "legacy" now.
                NavHost(
                    navController = navController,
                    // Stage 3.2 (see docs/arkarium/REFACTOR_PLAN.md): Home becomes the
                    // NavHost's own startDestination, replacing currentScreen's
                    // mutableStateOf<Screen>(Screen.Home) default as the thing that
                    // actually decides what's on screen first. currentScreen still
                    // defaults to Screen.Home (unchanged) and stays in sync below purely
                    // for bookkeeping - nothing reads it to decide whether "home" is
                    // showing anymore.
                    startDestination = "home",
                    modifier = Modifier.fillMaxSize()
                ) {
                composable("home") {
                    Column(modifier = Modifier.fillMaxSize()) {
                        HomeScreen(
                            novels = libraryViewModel.novels,
                            inProgressNovels = libraryViewModel.inProgressNovels,
                            onNovelClick = { novel ->
                                lifecycleScope.launch {
                                    libraryViewModel.loadNovelDetails(novel)
                                    // Stage 3.3 (see docs/arkarium/REFACTOR_PLAN.md):
                                    // NovelDetail is now its own route, keyed by novelId -
                                    // launchSingleTop avoids piling up a new back-stack
                                    // entry every time Home is revisited and a novel is
                                    // tapped again.
                                    navController.navigate("novelDetail/${novel.id}") { launchSingleTop = true }
                                }
                            },
                            onContinueReading = { novel ->
                                lifecycleScope.launch {
                                    val lastProgress = db.readingProgressDao().forNovel(novel.id)
                                    if (lastProgress != null) {
                                        val chapter = db.chapterDao().findById(lastProgress.chapterId)
                                        if (chapter != null) {
                                            // "Continue Reading" used to jump straight into
                                            // Screen.Reader without ever calling
                                            // loadNovelDetails(), unlike every other path into
                                            // the reader - so `chapters`/`arcs` could still hold
                                            // a *different* novel's data (or be empty) here.
                                            // That was harmless before Stage 3, since ReaderScreen
                                            // didn't read them; now Previous/Next need the
                                            // correctly-scoped chapter list to compute neighbors.
                                            libraryViewModel.loadNovelDetails(novel)
                                            // Stage 3.4 (see docs/arkarium/REFACTOR_PLAN.md):
                                            // Reader is now its own "reader/{novelId}/
                                            // {chapterId}" route - readerAuthor resolution and
                                            // the chapter body fetch both move into that
                                            // composable's own LaunchedEffects, keyed off the
                                            // route arguments, instead of happening here at
                                            // every entry point.
                                            navController.navigate("reader/${novel.id}/${chapter.id}") {
                                                launchSingleTop = true
                                            }
                                        }
                                    } else {
                                        libraryViewModel.loadNovelDetails(novel)
                                        navController.navigate("novelDetail/${novel.id}") { launchSingleTop = true }
                                    }
                                }
                            },
                            onBrowseClick = {
                                currentScreen.value = Screen.FictionBrowse()
                                navController.navigate("fictionBrowse")
                            },
                            onSettingsClick = {
                                currentScreen.value = Screen.Settings
                                navController.navigate("settings")
                            },
                            onSearch = { query ->
                                if (query.isNotEmpty()) {
                                    currentScreen.value = Screen.FictionBrowse(initialQuery = query)
                                    navController.navigate("fictionBrowse?initialQuery=${Uri.encode(query)}")
                                }
                            },
                            // The library now works out of the box against the app's
                            // default private storage folder (see resolveLibraryRoot),
                            // so this no longer needs to be a first-run dead-end fix -
                            // it just routes to Settings, the single place "Use custom
                            // folder" and the SAF picker now live.
                            onSelectFolderClick = {
                                currentScreen.value = Screen.Settings
                                navController.navigate("settings")
                            },
                            onAddFictionClick = { metadataViewModel.addFictionState.value = AddFictionState.EnteringName },
                            onSyncAllClick = {
                                val root = resolveLibraryRoot(this@MainActivity, useCustomFolder.value, savedUri.value)
                                if (root != null) {
                                    syncViewModel.syncAllRaeArkNovels(root)
                                } else {
                                    syncViewModel.syncAllState.value =
                                        SyncAllState.Error("No library folder is set up yet - pick one in Settings first.")
                                }
                            }
                        )
                    }
                }

                composable(
                    "fictionBrowse?initialQuery={initialQuery}",
                    arguments = listOf(navArgument("initialQuery") {
                        type = NavType.StringType
                        defaultValue = ""
                    })
                ) { backStackEntry ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        FictionBrowseScreen(
                            novels = libraryViewModel.novels,
                            initialQuery = backStackEntry.arguments?.getString("initialQuery") ?: "",
                            onNovelSelected = { novel ->
                                lifecycleScope.launch {
                                    libraryViewModel.loadNovelDetails(novel)
                                    navController.navigate("novelDetail/${novel.id}") { launchSingleTop = true }
                                }
                            },
                            onBack = {
                                currentScreen.value = Screen.Home
                                navController.popBackStack()
                            }
                        )
                    }
                }

                composable("legacy") {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Stage 3.4 (see docs/arkarium/REFACTOR_PLAN.md) migrated the last two
                    // real cases this `when` still handled - Reader and Author - to their
                    // own "reader/{novelId}/{chapterId}"/"author/{authorId}" NavHost routes
                    // below, the same way Stage 3.1-3.3 migrated every other Screen case
                    // before them. Every entry point into what used to be "legacy" now
                    // navigates the NavController directly instead of writing
                    // currentScreen.value, so this branch should never actually compose for
                    // any case anymore - "legacy" itself, and this now-fully-dead `when`,
                    // are left in place only because removing them is Stage 3.5's job
                    // (deleting Screen entirely, currentScreen, and every branch that only
                    // existed to route through it), not this stage's.
                    when (currentScreen.value) {
                        else -> {}
                    }
                }
                }

                // Stage 3.3 (see docs/arkarium/REFACTOR_PLAN.md): the first stage that
                // changes a Screen case's shape instead of just relocating it - NovelDetail
                // and ChapterEditor now take a novelId route argument (same
                // "fictionBrowse?initialQuery={initialQuery}" pattern Stage 3.2 already
                // proved for a scalar argument) and resolve the NovelEntity themselves via
                // libraryViewModel.novels.firstOrNull { it.id == novelId }, the same lookup
                // Screen.Reader's composable already used for readerAuthor/chapter
                // neighbors - rather than having a full NovelEntity handed in as Screen
                // payload. That's what makes the onApplied/onUpdated callbacks Stages
                // 2.4/2.5 added to MetadataViewModel.applyMetadata and
                // SyncViewModel.checkForUpdates/resolveMissingFolderBySyncing/
                // resolveSourceGoneByUnlinking unnecessary (see those call sites below and
                // in the sync-dialog handlers further down) - libraryViewModel patching its
                // own `novels` SnapshotStateList, which every one of those functions already
                // does, is now enough on its own for this composable to recompose with the
                // new data.
                composable(
                    "novelDetail/{novelId}",
                    arguments = listOf(navArgument("novelId") { type = NavType.StringType })
                ) { backStackEntry ->
                    val novelId = backStackEntry.arguments?.getString("novelId") ?: ""
                    val novel = libraryViewModel.novels.firstOrNull { it.id == novelId }
                    if (novel == null) {
                        // The novel this route pointed to is no longer in the library -
                        // most likely SyncResolutionDialog's onRemoveFromLibrary below just
                        // deleted it via syncViewModel.resolveByRemovingFromLibrary. That
                        // used to need an explicit onRemoved callback checking whether
                        // currentScreen was showing the just-removed novel and patching it
                        // back to Home; now that this destination resolves its own novel
                        // from libraryViewModel.novels instead of carrying one as Screen
                        // payload, the removal is enough on its own - the next
                        // recomposition sees a null novel here, and this LaunchedEffect just
                        // leaves for Home instead of trying to render nothing.
                        LaunchedEffect(novelId) {
                            navController.navigate("home") {
                                popUpTo("home") { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            NovelDetailScreen(
                                novel = novel,
                                chapters = libraryViewModel.chapters,
                                arcs = libraryViewModel.arcs,
                                overriddenChapterIds = libraryViewModel.overriddenChapterIds.value,
                                onBack = {
                                    // NovelDetail always went straight back to Home
                                    // regardless of how it was reached (direct from Home,
                                    // FictionBrowse, or an author's byline) - popUpTo("home",
                                    // inclusive = false) preserves that exact behavior by
                                    // collapsing whatever's on top of "home" back down to
                                    // it, rather than a plain popBackStack() which would
                                    // only undo the single most recent hop.
                                    navController.navigate("home") {
                                        popUpTo("home") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                },
                                onChapterSelected = { chapter ->
                                    lifecycleScope.launch {
                                        // Mark novel as IN_PROGRESS when starting to read
                                        db.novelDao().updateReadingStatus(novel.id, NovelStatus.IN_PROGRESS.name)
                                        libraryViewModel.refreshRecentlyRead()
                                        // Stage 3.4 (see docs/arkarium/REFACTOR_PLAN.md):
                                        // Reader is now its own "reader/{novelId}/
                                        // {chapterId}" route - readerAuthor resolution and
                                        // the chapter body fetch both move into that
                                        // composable's own LaunchedEffects, keyed off the
                                        // route arguments, instead of happening here.
                                        navController.navigate("reader/${novel.id}/${chapter.id}") {
                                            launchSingleTop = true
                                        }
                                    }
                                },
                                onResizePages = { pageSize ->
                                    lifecycleScope.launch {
                                        db.novelDao().updatePageSize(novel.id, pageSize)
                                    }
                                },
                                onEditClick = { navController.navigate("chapterEditor/${novel.id}") },
                                onFetchInfoClick = { metadataViewModel.fetchMetadataFor(novel) },
                                onAuthorClick = {
                                    val authorId = novel.authorId
                                    if (authorId != null) {
                                        // Stage 3.4 (see docs/arkarium/REFACTOR_PLAN.md):
                                        // Author is now its own "author/{authorId}" route -
                                        // no more `from` payload to carry, since
                                        // NavController's own back stack already knows to
                                        // pop back to "novelDetail/${novel.id}" from here.
                                        navController.navigate("author/$authorId")
                                    }
                                },
                                onSyncClick = if (novel.syncSourceUrl != null) {
                                    {
                                        resolveLibraryRoot(this@MainActivity, useCustomFolder.value, savedUri.value)?.let { root ->
                                            syncViewModel.checkForUpdates(novel, root)
                                        }
                                    }
                                } else null,
                                notifyEnabled = novel.notifyNewChapters,
                                onToggleNotify = if (novel.syncSourceUrl != null) {
                                    { enabled ->
                                        // Only turning it ON ever needs the runtime permission -
                                        // turning it off just persists false regardless, same as
                                        // any other permission-gated toggle. API < 33 doesn't have
                                        // a POST_NOTIFICATIONS runtime prompt to show at all
                                        // (granted at install time there), so this only branches
                                        // on Tiramisu+.
                                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            ContextCompat.checkSelfPermission(
                                                this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                                            ) != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            pendingNotifyToggleNovelId = novel.id
                                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            lifecycleScope.launch { setNotifyEnabled(novel.id, enabled) }
                                        }
                                    }
                                } else null
                            )
                        }
                    }
                }

                composable(
                    "chapterEditor/{novelId}",
                    arguments = listOf(navArgument("novelId") { type = NavType.StringType })
                ) { backStackEntry ->
                    val novelId = backStackEntry.arguments?.getString("novelId") ?: ""
                    val novel = libraryViewModel.novels.firstOrNull { it.id == novelId }
                    if (novel != null) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            ChapterEditorScreen(
                                chapters = libraryViewModel.chapters,
                                initialArcStartIds = libraryViewModel.arcStartChapterIds.value,
                                onSave = { updatedChapters, arcStartIds ->
                                    saveChapterEdits(novel, updatedChapters, arcStartIds)
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }

                // Stage 3.4 (see docs/arkarium/REFACTOR_PLAN.md): the last stage of the
                // Screen-case migration, and the only one that actually needs
                // NavController's own back-stack handling rather than just its routing -
                // see this route's onPrevious/onNext (replace, not push) and the
                // "author/{authorId}" route below (onBack is now a plain popBackStack()).
                // Like NovelDetail/ChapterEditor (Stage 3.3), this destination resolves
                // its own ChapterEntity from libraryViewModel.chapters via the chapterId
                // route argument rather than having one handed in as Screen payload; the
                // chapter's body text - never part of Screen even before this stage, since
                // it's loaded async from contentRepo - now loads via a LaunchedEffect keyed
                // on chapterId instead of being fetched once at each entry point above.
                composable(
                    "reader/{novelId}/{chapterId}",
                    arguments = listOf(
                        navArgument("novelId") { type = NavType.StringType },
                        navArgument("chapterId") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val novelId = backStackEntry.arguments?.getString("novelId") ?: ""
                    val chapterId = backStackEntry.arguments?.getString("chapterId") ?: ""
                    val novel = libraryViewModel.novels.firstOrNull { it.id == novelId }
                    val chapter = libraryViewModel.chapters.firstOrNull { it.id == chapterId }
                    if (chapter == null) {
                        // Mirrors novelDetail/{novelId}'s own null-novel handling above -
                        // the chapter this route pointed to is no longer in
                        // libraryViewModel.chapters (e.g. a rescan dropped it), so there's
                        // nothing left to render. Leave for the novel's detail page if we
                        // at least still know the novel, otherwise Home.
                        LaunchedEffect(novelId, chapterId) {
                            if (novel != null) {
                                navController.navigate("novelDetail/${novel.id}") {
                                    popUpTo("home") { inclusive = false }
                                    launchSingleTop = true
                                }
                            } else {
                                navController.navigate("home") {
                                    popUpTo("home") { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        }
                    } else {
                        // Resolved once per novel, not per chapter - a Previous/Next hop
                        // (same novelId, different chapterId) shouldn't re-fetch it. Same
                        // "Activity mutableStateOf set via LaunchedEffect" pattern
                        // Screen.Author's own author lookup below already used.
                        LaunchedEffect(novelId) {
                            readerAuthor.value = novel?.authorId?.let { db.authorDao().findById(it) }
                        }
                        // Keyed on chapterId (via `remember(chapterId)`) so a Previous/Next
                        // hop's `navigate(...) { popUpTo(...) { inclusive = true } }` below -
                        // which replaces this backStackEntry with a fresh one rather than
                        // recomposing the existing one in place - starts each new chapter's
                        // content back at "not loaded yet" instead of briefly showing the
                        // previous chapter's text.
                        val chapterContentState = remember(chapterId) { mutableStateOf<String?>(null) }
                        LaunchedEffect(chapterId) {
                            chapterContentState.value = contentRepo.getTextContent(chapter.sourcePath).body
                        }
                        val content = chapterContentState.value
                        if (content != null) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                val currentIndex = libraryViewModel.chapters.indexOfFirst { it.id == chapterId }
                                val previousChapter = libraryViewModel.chapters.getOrNull(currentIndex - 1).takeIf { currentIndex > 0 }
                                val nextChapter = libraryViewModel.chapters.getOrNull(currentIndex + 1).takeIf { currentIndex >= 0 }
                                val arcTitle = chapter.arcId?.let { arcId -> libraryViewModel.arcs.firstOrNull { it.id == arcId }?.name }
                                // Arc cover -> fiction cover -> null (renders the placeholder) - see
                                // bugs.md Bug 3b. Resolved here rather than in ReaderScreen so it stays
                                // decoupled from ArcEntity/NovelEntity, same rationale as novelTitle/arcTitle.
                                val readerCoverUri = chapter.arcId
                                    ?.let { arcId -> libraryViewModel.arcs.firstOrNull { it.id == arcId }?.coverUri }
                                    ?: novel?.coverUri
                                ReaderScreen(
                                    chapter = chapter,
                                    content = content,
                                    // resolvedTheme, not currentTheme.value directly -
                                    // ReaderScreen's readingModeFor only maps the three
                                    // concrete themes (see its own `when`), and SYSTEM_DEFAULT
                                    // isn't one of them.
                                    appTheme = resolvedTheme,
                                    novelTitle = novel?.title,
                                    arcTitle = arcTitle,
                                    coverUri = readerCoverUri,
                                    author = readerAuthor.value,
                                    onBack = { progress ->
                                        lifecycleScope.launch {
                                            saveReadingProgress(novelId, chapterId, progress)
                                            // Same popUpTo("home") reasoning as NovelDetail's
                                            // onBack - Reader's Back always went straight to
                                            // Home regardless of entry path.
                                            navController.navigate("home") {
                                                popUpTo("home") { inclusive = false }
                                                launchSingleTop = true
                                            }
                                        }
                                    },
                                    onBackToFiction = { progress ->
                                        if (novel != null) {
                                            lifecycleScope.launch {
                                                saveReadingProgress(novelId, chapterId, progress)
                                                navController.navigate("novelDetail/${novel.id}") {
                                                    launchSingleTop = true
                                                }
                                            }
                                        }
                                    },
                                    onPrevious = previousChapter?.let { prev ->
                                        { progress: Float ->
                                            lifecycleScope.launch {
                                                saveReadingProgress(novelId, chapterId, progress)
                                                // Replace, not push - Back from chapter 5
                                                // returns to NovelDetail, not chapter 4,
                                                // matching pre-NavHost behavior where
                                                // onPrevious/onNext never touched a back
                                                // stack because there wasn't one.
                                                navController.navigate("reader/$novelId/${prev.id}") {
                                                    popUpTo("reader/{novelId}/{chapterId}") { inclusive = true }
                                                }
                                            }
                                        }
                                    },
                                    onNext = nextChapter?.let { next ->
                                        { progress: Float ->
                                            lifecycleScope.launch {
                                                saveReadingProgress(novelId, chapterId, progress)
                                                navController.navigate("reader/$novelId/${next.id}") {
                                                    popUpTo("reader/{novelId}/{chapterId}") { inclusive = true }
                                                }
                                            }
                                        }
                                    },
                                    onAuthorClick = {
                                        val authorId = readerAuthor.value?.id
                                        if (authorId != null) {
                                            navController.navigate("author/$authorId")
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Stage 3.4 (see docs/arkarium/REFACTOR_PLAN.md): `from` is dropped from
                // what used to be Screen.Author - onBack is now a plain
                // navController.popBackStack(), since NavController's own back stack
                // already knows whether the previous entry was "novelDetail/{novelId}"
                // (tapped from a fiction page byline) or "reader/{novelId}/{chapterId}"
                // (tapped from the reader's "About the author" card) without this
                // destination needing to carry that itself.
                composable(
                    "author/{authorId}",
                    arguments = listOf(navArgument("authorId") { type = NavType.StringType })
                ) { backStackEntry ->
                    val authorId = backStackEntry.arguments?.getString("authorId") ?: ""
                    // Reload whenever the authorId changes (tapping into a different
                    // author's page while one is already showing isn't a real path today,
                    // but this keeps the screen correct if it ever is) - not on every
                    // recomposition.
                    LaunchedEffect(authorId) {
                        loadAuthorPage(authorId)
                    }
                    val author = authorPageAuthor.value
                    if (author != null) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            AuthorPageScreen(
                                author = author,
                                novels = authorPageNovels,
                                onBack = { navController.popBackStack() },
                                onNovelClick = { novel ->
                                    lifecycleScope.launch {
                                        libraryViewModel.loadNovelDetails(novel)
                                        navController.navigate("novelDetail/${novel.id}") { launchSingleTop = true }
                                    }
                                }
                            )
                        }
                    }
                }

                composable("settings") {
                    Column(modifier = Modifier.fillMaxSize()) {
                        SettingsScreen(
                            currentTheme = currentTheme.value,
                            useCustomFolder = useCustomFolder.value,
                            hasCustomFolderSelected = savedUri.value != null,
                            systemDefaultLightVariant = currentSystemDefaultLightVariant.value,
                            // Routed through settingsViewModel (Stage 2.2, see
                            // docs/arkarium/REFACTOR_PLAN.md) rather than calling
                            // prefsManager.setTheme/setSystemDefaultLightVariant/
                            // setUseCustomFolder directly - settingsViewModel is now the
                            // single owner of writes to this state, matching the
                            // Activity -> ViewModel -> service call chain the stage exists
                            // to prove. Fire-and-forget (settingsViewModel's setters launch
                            // their own viewModelScope coroutine) is safe for these three:
                            // nothing downstream reads the write back before it lands -
                            // onThemeSelected/onSystemDefaultLightVariantSelected have no
                            // downstream at all, and onUseCustomFolderToggle's
                            // resolveLibraryRoot call below already takes `enabled`
                            // directly rather than re-reading useCustomFolder.value.
                            onThemeSelected = { theme -> settingsViewModel.setTheme(theme) },
                            onSystemDefaultLightVariantSelected = { variant ->
                                settingsViewModel.setSystemDefaultLightVariant(variant)
                            },
                            onUseCustomFolderToggle = { enabled ->
                                settingsViewModel.setUseCustomFolder(enabled)
                                // Switching sources mints different novel IDs (see
                                // ScannerImpl's id hash, keyed off root.uri) - clear
                                // first so the old source's novels don't linger
                                // alongside the new source's until the next scan's
                                // reconciliation pass catches up. Kept on lifecycleScope
                                // (not settingsViewModel, which owns no novel state) -
                                // novels/startScan now live on libraryViewModel (Stage
                                // 2.3, see docs/arkarium/REFACTOR_PLAN.md).
                                lifecycleScope.launch {
                                    libraryViewModel.novels.clear()
                                    // Turning custom folder ON with nothing picked yet
                                    // resolves to null here by design - leave the
                                    // library empty and let the "Select Folder" button
                                    // below (or EmptyLibraryPrompt on Home) start the
                                    // picker instead of scanning anything.
                                    resolveLibraryRoot(this@MainActivity, enabled, savedUri.value)?.let { libraryViewModel.startScan(it) }
                                }
                            },
                            onSelectFolderClick = { pickFolder.launch(null) },
                            onRescan = {
                                lifecycleScope.launch {
                                    // No novels.clear() here - see bugs.md Bug 4.
                                    // startScan's onScanCompleted now reconciles
                                    // stale novels against the DB once the scan
                                    // actually finishes, instead of blanking the
                                    // visible library up front and hoping the scan
                                    // fully repopulates it.
                                    val root = resolveLibraryRoot(this@MainActivity, useCustomFolder.value, savedUri.value)
                                    if (root != null) {
                                        libraryViewModel.startScan(root)
                                    } else {
                                        // Custom folder is on but nothing's been picked
                                        // yet - "Rescan" would otherwise silently do
                                        // nothing here. Send the user to the picker.
                                        pickFolder.launch(null)
                                    }
                                }
                            },
                            splashAnimationEnabled = splashAnimationEnabled.value,
                            splashMusicEnabled = splashMusicEnabled.value,
                            onSplashAnimationToggle = { enabled ->
                                lifecycleScope.launch {
                                    prefsManager.setSplashAnimationEnabled(enabled)
                                }
                            },
                            onSplashMusicToggle = { enabled ->
                                lifecycleScope.launch {
                                    prefsManager.setSplashMusicEnabled(enabled)
                                }
                            },
                            onPrivacyPolicy = { navController.navigate("privacy_policy") },
                            onTermsAndConditions = { navController.navigate("terms") },
                            onAboutMe = { navController.navigate("about_me") },
                            onBack = {
                                navController.popBackStack()
                                currentScreen.value = Screen.Home
                            }
                        )
                    }
                }

                composable("privacy_policy") {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LegalDocumentScreen(
                            title = "Privacy Policy",
                            sections = LegalContent.privacyPolicy,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }

                composable("terms") {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LegalDocumentScreen(
                            title = "Terms & Conditions",
                            sections = LegalContent.termsAndConditions,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }

                composable("about_me") {
                    Column(modifier = Modifier.fillMaxSize()) {
                        WebViewScreen(
                            title = "About Me",
                            url = "https://rae-ark.horizonarkstudio.workers.dev/",
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
                } // closes NavHost
                } // closes Surface

                when (val state = metadataViewModel.metadataSearchState.value) {
                    is MetadataSearchState.Loading -> {
                        MetadataSearchDialog(
                            novelTitle = state.novel.title,
                            isLoading = true,
                            errorMessage = null,
                            candidates = emptyList(),
                            onCandidateSelected = {},
                            onDismiss = { metadataViewModel.metadataSearchState.value = MetadataSearchState.Idle }
                        )
                    }
                    is MetadataSearchState.Results -> {
                        MetadataSearchDialog(
                            novelTitle = state.novel.title,
                            isLoading = false,
                            errorMessage = null,
                            candidates = state.candidates,
                            // The currently-open "novelDetail/{novelId}" route (if this is
                            // the novel it's showing) picks up the new info on its own,
                            // via the libraryViewModel.novels patch applyMetadata already
                            // does - Stage 3.3 (see docs/arkarium/REFACTOR_PLAN.md) dropped
                            // the onApplied callback this call site used to pass to also
                            // patch currentScreen for the same reason.
                            onCandidateSelected = { candidate ->
                                metadataViewModel.applyMetadata(state.novel, candidate)
                            },
                            onDismiss = { metadataViewModel.metadataSearchState.value = MetadataSearchState.Idle }
                        )
                    }
                    is MetadataSearchState.Error -> {
                        MetadataSearchDialog(
                            novelTitle = state.novel.title,
                            isLoading = false,
                            errorMessage = state.message,
                            candidates = emptyList(),
                            onCandidateSelected = {},
                            onDismiss = { metadataViewModel.metadataSearchState.value = MetadataSearchState.Idle }
                        )
                    }
                    is MetadataSearchState.Idle -> {}
                }

                // "Add fiction" (home screen icon) and "Check for updates"
                // (NovelDetailScreen) - see docs/arkarium/SYNC_MVP.md, Stage 3, and the later
                // move to single-origin name lookup via FictionLut.
                when (val state = metadataViewModel.addFictionState.value) {
                    AddFictionState.Hidden -> {}
                    AddFictionState.EnteringName -> {
                        AddFictionByNameDialog(
                            isLoading = false,
                            progressMessage = "",
                            errorMessage = null,
                            onConfirm = { name ->
                                val root = resolveLibraryRoot(this@MainActivity, useCustomFolder.value, savedUri.value)
                                if (root != null) {
                                    syncViewModel.addFictionByName(name, root)
                                } else {
                                    metadataViewModel.addFictionState.value =
                                        AddFictionState.Error("No library folder is set up yet - pick one in Settings first.")
                                }
                            },
                            onDismiss = { metadataViewModel.addFictionState.value = AddFictionState.Hidden }
                        )
                    }
                    is AddFictionState.InProgress -> {
                        AddFictionByNameDialog(
                            isLoading = true,
                            progressMessage = state.message,
                            errorMessage = null,
                            onConfirm = {},
                            onDismiss = {}
                        )
                    }
                    is AddFictionState.Error -> {
                        AddFictionByNameDialog(
                            isLoading = false,
                            progressMessage = "",
                            errorMessage = state.message,
                            onConfirm = { name ->
                                val root = resolveLibraryRoot(this@MainActivity, useCustomFolder.value, savedUri.value)
                                if (root != null) {
                                    syncViewModel.addFictionByName(name, root)
                                } else {
                                    metadataViewModel.addFictionState.value =
                                        AddFictionState.Error("No library folder is set up yet - pick one in Settings first.")
                                }
                            },
                            onDismiss = { metadataViewModel.addFictionState.value = AddFictionState.Hidden }
                        )
                    }
                }

                // "Sync all Rae ARK's novels" (EmptyLibraryPrompt's primary first-run
                // action, see HomeScreen.kt / syncAllRaeArkNovels above). Reuses
                // SyncProgressDialog, same as the per-novel "Check for updates" dialog
                // below - just with "Rae ARK's novels" standing in for a single title.
                when (val state = syncViewModel.syncAllState.value) {
                    SyncAllState.Idle -> {}
                    is SyncAllState.InProgress -> {
                        SyncProgressDialog(
                            novelTitle = "Rae ARK's novels",
                            isLoading = true,
                            message = state.message,
                            errorMessage = null,
                            onDismiss = {}
                        )
                    }
                    is SyncAllState.Done -> {
                        SyncProgressDialog(
                            novelTitle = "Rae ARK's novels",
                            isLoading = false,
                            message = state.message,
                            errorMessage = null,
                            onDismiss = { syncViewModel.syncAllState.value = SyncAllState.Idle }
                        )
                    }
                    is SyncAllState.Error -> {
                        SyncProgressDialog(
                            novelTitle = "Rae ARK's novels",
                            isLoading = false,
                            message = "",
                            errorMessage = state.message,
                            onDismiss = { syncViewModel.syncAllState.value = SyncAllState.Idle }
                        )
                    }
                }

                when (val state = syncViewModel.syncCheckState.value) {
                    SyncCheckState.Idle -> {}
                    is SyncCheckState.InProgress -> {
                        SyncProgressDialog(
                            novelTitle = state.novel.title,
                            isLoading = true,
                            message = state.message,
                            errorMessage = null,
                            onDismiss = {}
                        )
                    }
                    is SyncCheckState.Done -> {
                        SyncProgressDialog(
                            novelTitle = state.novel.title,
                            isLoading = false,
                            message = state.message,
                            errorMessage = null,
                            onDismiss = { syncViewModel.syncCheckState.value = SyncCheckState.Idle }
                        )
                    }
                    is SyncCheckState.Error -> {
                        SyncProgressDialog(
                            novelTitle = state.novel.title,
                            isLoading = false,
                            message = "",
                            errorMessage = state.message,
                            onDismiss = { syncViewModel.syncCheckState.value = SyncCheckState.Idle }
                        )
                    }
                }

                // See docs/arkarium/NEXT_FIXES.md #2 - offered whenever checkForUpdates hits a
                // missing-local-folder or source-gone situation, in place of silently
                // resolving either one.
                when (val state = syncViewModel.syncResolutionState.value) {
                    SyncResolutionState.Idle -> {}
                    is SyncResolutionState.NeedsResolution -> {
                        // Stage 3.3 (see docs/arkarium/REFACTOR_PLAN.md) dropped all three
                        // onUpdated/onRemoved callbacks these three resolve* calls used to
                        // take: the "novelDetail/{novelId}" route this dialog can fire on
                        // top of resolves its novel from libraryViewModel.novels on every
                        // recomposition, so a resync/unlink's `novels` patch is picked up
                        // on its own, and a removal resolves to a null novel there, which
                        // that route's own LaunchedEffect already sends back to Home for.
                        SyncResolutionDialog(
                            novelTitle = state.novel.title,
                            isMissingLocally = state.reason == SyncResolutionReason.MISSING_LOCALLY,
                            onSyncAgain = {
                                resolveLibraryRoot(this@MainActivity, useCustomFolder.value, savedUri.value)?.let { root ->
                                    syncViewModel.resolveMissingFolderBySyncing(state.novel, root)
                                }
                            },
                            onRemoveFromLibrary = {
                                syncViewModel.resolveByRemovingFromLibrary(state.novel)
                            },
                            onUnlink = {
                                syncViewModel.resolveSourceGoneByUnlinking(state.novel)
                            },
                            onDismiss = { syncViewModel.syncResolutionState.value = SyncResolutionState.Idle }
                        )
                    }
                }
            }
        }
    }
}
