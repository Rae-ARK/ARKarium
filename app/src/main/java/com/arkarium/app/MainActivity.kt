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
import com.arkarium.app.data.mergeNovelForRescan
import com.arkarium.app.data.resolveLibraryRoot
import com.arkarium.app.data.NovelMetadataCandidate
import com.arkarium.app.data.ReadingProgressEntity
import com.arkarium.app.data.ScannerImpl
import com.arkarium.app.data.NewChapterCheckWorker
import com.arkarium.app.data.NewChapterNotifier
import com.arkarium.app.data.NovelEntity
import com.arkarium.app.data.ChapterEntity
import com.arkarium.app.data.PreferencesManager
import com.arkarium.app.data.SyncManager
import com.arkarium.app.data.FictionLut
import com.arkarium.app.data.relayBaseUrlForSlug
import com.arkarium.app.data.NovelStatus
import com.arkarium.app.data.SyncStatus
import com.arkarium.app.data.SourceGoneException
import com.arkarium.app.data.MissingLocalFolderException
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
import com.arkarium.app.viewmodel.SettingsViewModel
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

class MainActivity : ComponentActivity() {

    // novels/chapters/arcs/recentlyRead/inProgressNovels/overriddenChapterIds/
    // arcStartChapterIds/scanProgress/scanMessage formerly lived here as Activity
    // mutableStateOf/mutableStateListOf fields - all now come from libraryViewModel
    // (Stage 2.3 of Phase 2, see docs/arkarium/REFACTOR_PLAN.md). Every read site
    // below is unchanged except for the `libraryViewModel.` prefix, since the fields
    // kept their names.
    // Author of the fiction currently open in the reader, resolved once when entering
    // Screen.Reader (see the two entry points below) rather than looked up reactively
    // per-recomposition - it doesn't change across a Previous/Next hop within the same
    // fiction, only when a *different* fiction's reader is opened. Null covers both
    // "not resolved yet" and "this fiction has no linked author" (see NovelEntity.authorId).
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
    private val metadataSearchState = mutableStateOf<MetadataSearchState>(MetadataSearchState.Idle)
    private val addFictionState = mutableStateOf<AddFictionState>(AddFictionState.Hidden)
    private val syncAllState = mutableStateOf<SyncAllState>(SyncAllState.Idle)
    private val syncCheckState = mutableStateOf<SyncCheckState>(SyncCheckState.Idle)
    private val syncResolutionState = mutableStateOf<SyncResolutionState>(SyncResolutionState.Idle)
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
    // Stateless, can't throw, needs no Context - constructed eagerly rather than
    // alongside the other services in onCreate's try/catch.
    private val metadataProvider = GoogleBooksMetadataProvider()

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

    // Scoped counterpart to startScan (see docs/arkarium/NEXT_FIXES.md #4): runs the exact same
    // discover -> merge -> upsert -> scanChaptersForNovel sequence startScan's
    // onDiscovered callback runs per-novel, but for exactly one already-known folder,
    // via ScannerImpl.scanSingleNovel instead of a full scanRoot() pass. Used by
    // syncAllRaeArkNovels so syncing fiction M into a library of N-1 other synced
    // novels doesn't re-list and re-fingerprint all N-1 of them on every iteration.
    // Deliberately does NOT do startScan's stale-novel reconciliation (that requires
    // seeing every folder in one pass, which is exactly what this method avoids) - the
    // library's next full scan (e.g. next app launch) still catches anything that
    // needs it.
    private suspend fun scanSingleSyncedNovel(
        libraryRoot: DocumentFile,
        novelFolder: DocumentFile,
        onProgress: suspend (message: String) -> Unit = {}
    ): NovelEntity {
        // Captured by the onAuthorsDiscovered callback below (fired before
        // scanSingleNovel returns) so it's available down here for mergeNovelForRescan
        // - see that callback's own comment and ScannerImpl.scanRoot's doc comment on
        // the same flag.
        var authorsFolderFound = false
        val onAuthorsDiscovered: suspend (List<AuthorEntity>, Boolean) -> Unit = { discoveredAuthors, found ->
            authorsFolderFound = found
            // See startScan's matching callback: only touch the authors table when
            // this pass actually found authors/ - an empty discoveredAuthors list
            // from a not-found folder must never be read as "delete every known
            // author."
            if (found) {
                val seenIds = discoveredAuthors.map { it.id }.toSet()
                discoveredAuthors.forEach { db.authorDao().upsert(it) }
                db.authorDao().all().filter { it.id !in seenIds }.forEach { stale ->
                    db.authorDao().delete(stale.id)
                }
            }
        }
        var scanned = scanner.scanSingleNovel(
            root = libraryRoot,
            novelFolder = novelFolder,
            onAuthorsDiscovered = onAuthorsDiscovered
        )
        val existing = db.novelDao().findById(scanned.id)
        // mergeNovelForRescan's authorsFolderFound fallback only ever runs when
        // `existing` is non-null - it short-circuits with `if (existing == null)
        // return scanned` before ever looking at the flag. So a brand-new novel
        // (this is its very first scan, no row to fall back to) gets zero benefit
        // from that resilience fix: if this pass didn't see authors/ - e.g. the
        // authors/<id>.json this fiction's sync just wrote hasn't surfaced in a SAF
        // listFiles() call yet - authorId is baked in as null the moment this row is
        // inserted, and nothing will ever re-scan just this one novel again to fix
        // it (only the next full startScan() would). One immediate retry gives that
        // transient listing gap a chance to resolve before the row is ever created.
        if (existing == null && !authorsFolderFound) {
            scanned = scanner.scanSingleNovel(
                root = libraryRoot,
                novelFolder = novelFolder,
                onAuthorsDiscovered = onAuthorsDiscovered
            )
        }
        val novel = mergeNovelForRescan(scanned, existing, authorsFolderFound)
        db.novelDao().upsert(novel)
        withContext(Dispatchers.Main) {
            val idx = libraryViewModel.novels.indexOfFirst { it.id == novel.id }
            if (idx >= 0) libraryViewModel.novels[idx] = novel else libraryViewModel.novels.add(novel)
        }
        scanner.scanChaptersForNovel(novelFolder, novel.id, db, onProgress)
        return novel
    }

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

    // Kicks off a "Fetch info" search for one novel. User-triggered only (see
    // NovelDetailScreen's info action) - never called automatically during a scan.
    private fun fetchMetadataFor(novel: NovelEntity) {
        metadataSearchState.value = MetadataSearchState.Loading(novel)
        lifecycleScope.launch {
            try {
                val results = metadataProvider.search(novel.title)
                metadataSearchState.value = MetadataSearchState.Results(novel, results)
            } catch (e: Exception) {
                metadataSearchState.value = MetadataSearchState.Error(novel, "Couldn't reach the metadata source: ${e.message}")
            }
        }
    }

    // Persists a user-confirmed match and refreshes every in-memory copy of this novel
    // (the library list and, if it's the screen currently on screen, NovelDetail) so the
    // new info shows up immediately without navigating away and back.
    private fun applyMetadata(novel: NovelEntity, candidate: NovelMetadataCandidate) {
        lifecycleScope.launch {
            try {
                db.novelDao().updateMetadata(
                    novelId = novel.id,
                    description = candidate.description,
                    genres = candidate.categories.joinToString(", ").ifBlank { null },
                    remoteCoverUrl = candidate.thumbnailUrl,
                    publishedDate = candidate.publishedDate,
                    externalSourceUrl = candidate.infoLink,
                    fetchedAt = System.currentTimeMillis()
                )
                val updated = db.novelDao().findById(novel.id) ?: return@launch
                val idx = libraryViewModel.novels.indexOfFirst { it.id == updated.id }
                if (idx >= 0) libraryViewModel.novels[idx] = updated
                val screen = currentScreen.value
                if (screen is Screen.NovelDetail && screen.novel.id == updated.id) {
                    currentScreen.value = Screen.NovelDetail(updated)
                }
                metadataSearchState.value = MetadataSearchState.Idle
            } catch (e: Exception) {
                metadataSearchState.value = MetadataSearchState.Error(novel, "Couldn't save the fetched info: ${e.message}")
            }
        }
    }

    // Kicks off "Add fiction" end to end (see docs/arkarium/SYNC_MVP.md §4/Stage 3, and the
    // later move to single-origin name lookup): resolves the typed name to a slug via
    // FictionLut, builds the one relay's URL for it, downloads every file the relay's
    // manifest.json lists into a fresh folder under the active library root, then runs
    // the exact same startScan() pass every other novel goes through so the new folder
    // is discovered the normal way - no separate "remote novel" render path. The
    // folder's eventual novel id isn't known until that scan assigns it, so this
    // recomputes ScannerImpl's own id formula
    // (UUID.nameUUIDFromBytes(root.uri + ":" + folder.uri)) rather than guessing, and
    // only attaches the sync bookkeeping (SyncedFileEntity rows, NovelDao.updateSyncState)
    // once the scan has actually run and that id is confirmed to exist.
    private fun addFictionByName(name: String, libraryRoot: DocumentFile) {
        val slug = FictionLut.lookup(this, name)
        if (slug == null) {
            addFictionState.value = AddFictionState.Error("Couldn't find a fiction called \"$name\".")
            return
        }
        val url = relayBaseUrlForSlug(slug)
        addFictionState.value = AddFictionState.InProgress("Fetching manifest...")
        lifecycleScope.launch {
            try {
                // `name` (the user-typed fiction name that resolved to this slug) becomes
                // the actual on-disk folder name - see SyncManager.downloadInitial's doc
                // comment on why that's now safe to do (folder identity for sync no
                // longer comes from this name once the novel exists).
                val (folderName, outcome) = syncManager.downloadInitial(url, libraryRoot, name) { message ->
                    withContext(Dispatchers.Main) {
                        addFictionState.value = AddFictionState.InProgress(message)
                    }
                }
                withContext(Dispatchers.Main) {
                    addFictionState.value = AddFictionState.InProgress("Adding to your library...")
                }
                val folder = libraryRoot.findFile(folderName)
                    ?: throw java.io.IOException("The downloaded fiction folder went missing before it could be scanned")
                val novelId = UUID.nameUUIDFromBytes(
                    (libraryRoot.uri.toString() + ":" + folder.uri.toString()).toByteArray()
                ).toString()
                libraryViewModel.startScan(libraryRoot)
                db.syncedFileDao().upsertAll(outcome.files.map { it.copy(novelId = novelId) })
                db.novelDao().updateSyncState(novelId, url, outcome.newVersion, System.currentTimeMillis(), folderName)
                val updated = db.novelDao().findById(novelId)
                if (updated != null) {
                    val idx = libraryViewModel.novels.indexOfFirst { it.id == updated.id }
                    if (idx >= 0) libraryViewModel.novels[idx] = updated
                }
                addFictionState.value = AddFictionState.Hidden
            } catch (e: Exception) {
                addFictionState.value = AddFictionState.Error("Couldn't add this fiction: ${e.message}")
            }
        }
    }

    // "Sync all Rae ARK's novels" (EmptyLibraryPrompt's primary first-run action, see
    // HomeScreen.kt). Downloads every fiction FictionLut.allEntries lists, one after
    // another, reusing exactly the same per-fiction download/persist steps as
    // addFictionByName above - this is just that same flow run in a loop with one
    // shared progress dialog instead of one dialog per fiction.
    //
    // Two differences from the original version (see docs/arkarium/NEXT_FIXES.md #4 and #2):
    //
    // - Each iteration now calls scanSingleSyncedNovel (scoped to just the one
    //   just-downloaded folder) instead of a full startScan() pass. The old version's
    //   full-rescan-per-iteration was O(M x N) SAF directory listings for M fictions
    //   being synced into a library of N - every already-synced novel got re-listed
    //   and re-fingerprinted on every single loop iteration, not just the one that
    //   actually changed. This keeps the same "show up as it finishes" UX (each
    //   fiction is still discovered and added to `novels` the moment its own download
    //   completes) without paying for a full-library rescan per fiction.
    // - A SourceGoneException for one fiction (the relay 404s on its manifest - e.g. a
    //   stale FictionLut entry) no longer aborts the whole batch. It's reported and
    //   skipped, and the batch continues with the next fiction - a single stale/removed
    //   entry shouldn't block every other fiction from syncing. Any other exception
    //   (a real relay/network problem) still stops the batch, same as before, so a
    //   genuine outage isn't masked by "finished anyway."
    private fun syncAllRaeArkNovels(libraryRoot: DocumentFile) {
        val entries = FictionLut.allEntries(this)
        if (entries.isEmpty()) {
            syncAllState.value = SyncAllState.Error("No fictions are listed to sync.")
            return
        }
        syncAllState.value = SyncAllState.InProgress("Starting...")
        lifecycleScope.launch {
            val skipped = mutableListOf<String>()
            var syncedCount = 0
            try {
                for ((index, entry) in entries.withIndex()) {
                    val (displayName, slug) = entry
                    val url = relayBaseUrlForSlug(slug)
                    withContext(Dispatchers.Main) {
                        syncAllState.value =
                            SyncAllState.InProgress("${index + 1}/${entries.size}: Fetching \"$displayName\"...")
                    }
                    try {
                        // `displayName` (from FictionLut.allEntries) becomes the actual
                        // on-disk folder name, same as addFictionByName above.
                        val (folderName, outcome) = syncManager.downloadInitial(url, libraryRoot, displayName) { message ->
                            withContext(Dispatchers.Main) {
                                syncAllState.value =
                                    SyncAllState.InProgress("${index + 1}/${entries.size}: $displayName - $message")
                            }
                        }
                        val folder = libraryRoot.findFile(folderName)
                            ?: throw java.io.IOException("\"$displayName\" went missing before it could be scanned")
                        val novelId = UUID.nameUUIDFromBytes(
                            (libraryRoot.uri.toString() + ":" + folder.uri.toString()).toByteArray()
                        ).toString()
                        // Scoped scan of just this fiction's folder (see docs/arkarium/NEXT_FIXES.md
                        // #4) - shows up in the library as soon as it's done, same as
                        // before, without re-scanning every other already-synced novel.
                        scanSingleSyncedNovel(libraryRoot, folder) { message ->
                            withContext(Dispatchers.Main) {
                                syncAllState.value =
                                    SyncAllState.InProgress("${index + 1}/${entries.size}: $displayName - $message")
                            }
                        }
                        db.syncedFileDao().upsertAll(outcome.files.map { it.copy(novelId = novelId) })
                        db.novelDao().updateSyncState(novelId, url, outcome.newVersion, System.currentTimeMillis(), folderName)
                        val updated = db.novelDao().findById(novelId)
                        if (updated != null) {
                            withContext(Dispatchers.Main) {
                                val idx = libraryViewModel.novels.indexOfFirst { it.id == updated.id }
                                if (idx >= 0) libraryViewModel.novels[idx] = updated
                            }
                        }
                        syncedCount++
                    } catch (e: SourceGoneException) {
                        // This one fiction isn't served here anymore (stale LUT entry, or
                        // the author removed it) - report and move on rather than blocking
                        // every other fiction in the batch behind it.
                        skipped.add(displayName)
                    }
                }
                val summary = StringBuilder("Synced $syncedCount novel(s).")
                if (skipped.isNotEmpty()) {
                    summary.append(" Skipped (source unavailable): ${skipped.joinToString(", ")}.")
                }
                syncAllState.value = SyncAllState.Done(summary.toString())
            } catch (e: Exception) {
                syncAllState.value = SyncAllState.Error("Sync stopped: ${e.message}")
            }
        }
    }

    // Re-syncs an already-added fiction against its relay (see docs/arkarium/SYNC_MVP.md, Stage
    // 3). SyncManager.sync's SyncOutcome.files is documented as the complete new file
    // set, not a delta (see SyncManager.kt) - so on a real change this replaces
    // SyncedFileEntity wholesale rather than patching it, and only re-runs startScan
    // when something actually changed, so an "already up to date" check stays a single
    // manifest fetch instead of paying for a full rescan every time (docs/arkarium/SYNC_MVP.md
    // "Future considerations" #5).
    // `allowRecreateMissingFolder` is only ever true when the user has explicitly
    // confirmed it via the sync-resolution dialog (see syncResolutionState below) -
    // an automatic/background-triggered call always leaves it false, so a missing
    // local folder surfaces as a NeedsResolution prompt instead of a silent
    // redownload (see docs/arkarium/NEXT_FIXES.md #2 and SyncManager.sync's own doc comment).
    private fun checkForUpdates(
        novel: NovelEntity,
        libraryRoot: DocumentFile,
        allowRecreateMissingFolder: Boolean = false
    ) {
        syncCheckState.value = SyncCheckState.InProgress(novel, "Checking for updates...")
        lifecycleScope.launch {
            try {
                val sourceUrl = novel.syncSourceUrl
                    ?: throw IllegalStateException("This novel has no sync source")
                val knownFiles = db.syncedFileDao().forNovel(novel.id)
                val outcome = syncManager.sync(novel, libraryRoot, knownFiles, allowRecreateMissingFolder) { message ->
                    withContext(Dispatchers.Main) {
                        syncCheckState.value = SyncCheckState.InProgress(novel, message)
                    }
                }
                if (outcome.changed) {
                    db.syncedFileDao().deleteForNovel(novel.id)
                    db.syncedFileDao().upsertAll(outcome.files.map { it.copy(novelId = novel.id) })
                    // outcome.folderName is always set on this branch (see SyncManager.sync -
                    // it's only ever null on the early "already up to date" return, which
                    // can't be true here since outcome.changed is true). The fallback chain
                    // is just defense-in-depth against DocumentFile.name's nullable type.
                    db.novelDao().updateSyncState(
                        novel.id, sourceUrl, outcome.newVersion, System.currentTimeMillis(),
                        outcome.folderName ?: novel.syncFolderName ?: SyncManager.slugForUrl(sourceUrl)
                    )
                    // This resync succeeded, so whatever made the novel MISSING_LOCALLY
                    // (or that this call is a deliberate resolution retry for) no longer
                    // applies - clear it back to ACTIVE rather than leaving a stale status.
                    db.novelDao().updateSyncStatus(novel.id, SyncStatus.ACTIVE.name)
                    libraryViewModel.startScan(libraryRoot)
                    val updated = db.novelDao().findById(novel.id)
                    if (updated != null) {
                        val idx = libraryViewModel.novels.indexOfFirst { it.id == updated.id }
                        if (idx >= 0) libraryViewModel.novels[idx] = updated
                        val screen = currentScreen.value
                        if (screen is Screen.NovelDetail && screen.novel.id == updated.id) {
                            currentScreen.value = Screen.NovelDetail(updated)
                        }
                    }
                }
                syncCheckState.value = SyncCheckState.Done(
                    novel,
                    if (outcome.changed) "Updated to the latest version." else "Already up to date."
                )
            } catch (e: MissingLocalFolderException) {
                // See docs/arkarium/NEXT_FIXES.md #2: don't silently redownload and don't silently
                // drop the novel either - ask the user via syncResolutionState.
                db.novelDao().updateSyncStatus(novel.id, SyncStatus.MISSING_LOCALLY.name)
                val updated = db.novelDao().findById(novel.id)
                if (updated != null) {
                    withContext(Dispatchers.Main) {
                        val idx = libraryViewModel.novels.indexOfFirst { it.id == updated.id }
                        if (idx >= 0) libraryViewModel.novels[idx] = updated
                    }
                }
                syncCheckState.value = SyncCheckState.Idle
                syncResolutionState.value = SyncResolutionState.NeedsResolution(
                    updated ?: novel, SyncResolutionReason.MISSING_LOCALLY
                )
            } catch (e: SourceGoneException) {
                // See docs/arkarium/NEXT_FIXES.md #2: distinguishable from a generic network
                // failure so the prompt can say what's actually true - the source is
                // gone, not just unreachable right now. Local content stays readable.
                db.novelDao().updateSyncStatus(novel.id, SyncStatus.SOURCE_GONE.name)
                val updated = db.novelDao().findById(novel.id)
                if (updated != null) {
                    withContext(Dispatchers.Main) {
                        val idx = libraryViewModel.novels.indexOfFirst { it.id == updated.id }
                        if (idx >= 0) libraryViewModel.novels[idx] = updated
                    }
                }
                syncCheckState.value = SyncCheckState.Idle
                syncResolutionState.value = SyncResolutionState.NeedsResolution(
                    updated ?: novel, SyncResolutionReason.SOURCE_GONE
                )
            } catch (e: Exception) {
                syncCheckState.value = SyncCheckState.Error(novel, "Sync failed: ${e.message}")
            }
        }
    }

    // Backs NovelDetailScreen's "Notify me when new chapters are available" toggle
    // (see docs/arkarium/NEW_CHAPTER_NOTIFICATIONS.md). The actual permission gate lives in the
    // toggle's onToggleNotify callback below (which calls this directly when already
    // granted, or via notificationPermission's result callback otherwise) - this
    // function itself just persists the change and refreshes in-memory state, same
    // "update DB then patch `novels`/currentScreen" pattern checkForUpdates uses.
    private suspend fun setNotifyEnabled(novelId: String, enabled: Boolean) {
        db.novelDao().updateNotifyNewChapters(novelId, enabled)
        val updated = db.novelDao().findById(novelId) ?: return
        withContext(Dispatchers.Main) {
            val idx = libraryViewModel.novels.indexOfFirst { it.id == updated.id }
            if (idx >= 0) libraryViewModel.novels[idx] = updated
            val screen = currentScreen.value
            if (screen is Screen.NovelDetail && screen.novel.id == updated.id) {
                currentScreen.value = Screen.NovelDetail(updated)
            }
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

    // Resolution actions offered from SyncResolutionDialog once checkForUpdates hits a
    // MissingLocalFolderException or SourceGoneException (see above and
    // docs/arkarium/NEXT_FIXES.md #2). All three are explicit, user-triggered choices - none of
    // them ever fire automatically.

    // "Sync again": the user has confirmed they want the folder recreated and the
    // fiction redownloaded from scratch - re-runs checkForUpdates with
    // allowRecreateMissingFolder = true, the one path that's allowed to recreate a
    // missing folder.
    private fun resolveMissingFolderBySyncing(novel: NovelEntity, libraryRoot: DocumentFile) {
        syncResolutionState.value = SyncResolutionState.Idle
        checkForUpdates(novel, libraryRoot, allowRecreateMissingFolder = true)
    }

    // "Remove from library": the user confirms the deletion they'd otherwise have had
    // done to them silently by the old stale-removal cascade - same NovelDao.delete
    // cascade (arcs/chapters/overrides/progress/synced_files all cascade away with it).
    private fun resolveByRemovingFromLibrary(novel: NovelEntity) {
        syncResolutionState.value = SyncResolutionState.Idle
        lifecycleScope.launch {
            db.novelDao().delete(novel.id)
            db.syncedFileDao().deleteForNovel(novel.id)
            withContext(Dispatchers.Main) {
                libraryViewModel.novels.removeAll { it.id == novel.id }
                if (currentScreen.value.let { it is Screen.NovelDetail && it.novel.id == novel.id }) {
                    currentScreen.value = Screen.Home
                }
            }
        }
    }

    // "Unlink": only offered for SOURCE_GONE. Drops the sync relationship (this novel
    // reverts to a plain local one) without touching any local content - there's
    // nothing left to sync against, but everything already downloaded keeps working.
    private fun resolveSourceGoneByUnlinking(novel: NovelEntity) {
        syncResolutionState.value = SyncResolutionState.Idle
        lifecycleScope.launch {
            db.novelDao().unlinkSyncSource(novel.id)
            val updated = db.novelDao().findById(novel.id)
            if (updated != null) {
                withContext(Dispatchers.Main) {
                    val idx = libraryViewModel.novels.indexOfFirst { it.id == updated.id }
                    if (idx >= 0) libraryViewModel.novels[idx] = updated
                    val screen = currentScreen.value
                    if (screen is Screen.NovelDetail && screen.novel.id == updated.id) {
                        currentScreen.value = Screen.NovelDetail(updated)
                    }
                }
            }
        }
    }

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
                    currentScreen.value = Screen.NovelDetail(target)
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
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    when (currentScreen.value) {
                        is Screen.Home -> {
                            HomeScreen(
                                novels = libraryViewModel.novels,
                                inProgressNovels = libraryViewModel.inProgressNovels,
                                onNovelClick = { novel ->
                                    lifecycleScope.launch {
                                        libraryViewModel.loadNovelDetails(novel)
                                        currentScreen.value = Screen.NovelDetail(novel)
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
                                                readerAuthor.value = novel.authorId?.let { db.authorDao().findById(it) }
                                                val chapterContent = contentRepo.getTextContent(chapter.sourcePath)
                                                currentScreen.value = Screen.Reader(novel.id, chapter, chapterContent.body)
                                            }
                                        } else {
                                            libraryViewModel.loadNovelDetails(novel)
                                            currentScreen.value = Screen.NovelDetail(novel)
                                        }
                                    }
                                },
                                onBrowseClick = { currentScreen.value = Screen.FictionBrowse() },
                                onSettingsClick = { currentScreen.value = Screen.Settings },
                                onSearch = { query ->
                                    if (query.isNotEmpty()) {
                                        currentScreen.value = Screen.FictionBrowse(initialQuery = query)
                                    }
                                },
                                // The library now works out of the box against the app's
                                // default private storage folder (see resolveLibraryRoot),
                                // so this no longer needs to be a first-run dead-end fix -
                                // it just routes to Settings, the single place "Use custom
                                // folder" and the SAF picker now live.
                                onSelectFolderClick = { currentScreen.value = Screen.Settings },
                                onAddFictionClick = { addFictionState.value = AddFictionState.EnteringName },
                                onSyncAllClick = {
                                    val root = resolveLibraryRoot(this@MainActivity, useCustomFolder.value, savedUri.value)
                                    if (root != null) {
                                        syncAllRaeArkNovels(root)
                                    } else {
                                        syncAllState.value =
                                            SyncAllState.Error("No library folder is set up yet - pick one in Settings first.")
                                    }
                                }
                            )
                        }

                        is Screen.FictionBrowse -> {
                            val browse = currentScreen.value as Screen.FictionBrowse
                            FictionBrowseScreen(
                                novels = libraryViewModel.novels,
                                initialQuery = browse.initialQuery,
                                onNovelSelected = { novel ->
                                    lifecycleScope.launch {
                                        libraryViewModel.loadNovelDetails(novel)
                                        currentScreen.value = Screen.NovelDetail(novel)
                                    }
                                },
                                onBack = { currentScreen.value = Screen.Home }
                            )
                        }

                        is Screen.NovelDetail -> {
                            val novel = (currentScreen.value as Screen.NovelDetail).novel
                            NovelDetailScreen(
                                novel = novel,
                                chapters = libraryViewModel.chapters,
                                arcs = libraryViewModel.arcs,
                                overriddenChapterIds = libraryViewModel.overriddenChapterIds.value,
                                onBack = { currentScreen.value = Screen.Home },
                                onChapterSelected = { chapter ->
                                    lifecycleScope.launch {
                                        // Mark novel as IN_PROGRESS when starting to read
                                        db.novelDao().updateReadingStatus(novel.id, NovelStatus.IN_PROGRESS.name)
                                        libraryViewModel.refreshRecentlyRead()
                                        readerAuthor.value = novel.authorId?.let { db.authorDao().findById(it) }
                                        val chapterContent = contentRepo.getTextContent(chapter.sourcePath)
                                        currentScreen.value = Screen.Reader(novel.id, chapter, chapterContent.body)
                                    }
                                },
                                onResizePages = { pageSize ->
                                    lifecycleScope.launch {
                                        db.novelDao().updatePageSize(novel.id, pageSize)
                                    }
                                },
                                onEditClick = { currentScreen.value = Screen.ChapterEditor(novel) },
                                onFetchInfoClick = { fetchMetadataFor(novel) },
                                onAuthorClick = {
                                    val authorId = novel.authorId
                                    if (authorId != null) {
                                        currentScreen.value = Screen.Author(authorId, from = currentScreen.value)
                                    }
                                },
                                onSyncClick = if (novel.syncSourceUrl != null) {
                                    {
                                        resolveLibraryRoot(this@MainActivity, useCustomFolder.value, savedUri.value)?.let { root ->
                                            checkForUpdates(novel, root)
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

                        is Screen.Reader -> {
                            val reader = currentScreen.value as Screen.Reader
                            // `chapters`/`arcs` are scoped to whichever novel was last loaded via
                            // loadNovelDetails() - both paths that create Screen.Reader now call
                            // it first (see onContinueReading/onChapterSelected above), so this is
                            // safe to read directly rather than re-querying the DB here.
                            val novel = libraryViewModel.novels.firstOrNull { it.id == reader.novelId }
                            val currentIndex = libraryViewModel.chapters.indexOfFirst { it.id == reader.chapter.id }
                            val previousChapter = libraryViewModel.chapters.getOrNull(currentIndex - 1).takeIf { currentIndex > 0 }
                            val nextChapter = libraryViewModel.chapters.getOrNull(currentIndex + 1).takeIf { currentIndex >= 0 }
                            val arcTitle = reader.chapter.arcId?.let { arcId -> libraryViewModel.arcs.firstOrNull { it.id == arcId }?.name }
                            // Arc cover -> fiction cover -> null (renders the placeholder) - see
                            // bugs.md Bug 3b. Resolved here rather than in ReaderScreen so it stays
                            // decoupled from ArcEntity/NovelEntity, same rationale as novelTitle/arcTitle.
                            val readerCoverUri = reader.chapter.arcId
                                ?.let { arcId -> libraryViewModel.arcs.firstOrNull { it.id == arcId }?.coverUri }
                                ?: novel?.coverUri
                            ReaderScreen(
                                chapter = reader.chapter,
                                content = reader.content,
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
                                        saveReadingProgress(reader.novelId, reader.chapter.id, progress)
                                        currentScreen.value = Screen.Home
                                    }
                                },
                                onBackToFiction = { progress ->
                                    if (novel != null) {
                                        lifecycleScope.launch {
                                            saveReadingProgress(reader.novelId, reader.chapter.id, progress)
                                            currentScreen.value = Screen.NovelDetail(novel)
                                        }
                                    }
                                },
                                onPrevious = previousChapter?.let { prev ->
                                    { progress: Float ->
                                        lifecycleScope.launch {
                                            saveReadingProgress(reader.novelId, reader.chapter.id, progress)
                                            val chapterContent = contentRepo.getTextContent(prev.sourcePath)
                                            currentScreen.value = Screen.Reader(reader.novelId, prev, chapterContent.body)
                                        }
                                    }
                                },
                                onNext = nextChapter?.let { next ->
                                    { progress: Float ->
                                        lifecycleScope.launch {
                                            saveReadingProgress(reader.novelId, reader.chapter.id, progress)
                                            val chapterContent = contentRepo.getTextContent(next.sourcePath)
                                            currentScreen.value = Screen.Reader(reader.novelId, next, chapterContent.body)
                                        }
                                    }
                                },
                                onAuthorClick = {
                                    val authorId = readerAuthor.value?.id
                                    if (authorId != null) {
                                        currentScreen.value = Screen.Author(authorId, from = currentScreen.value)
                                    }
                                }
                            )
                        }

                        is Screen.ChapterEditor -> {
                            val novel = (currentScreen.value as Screen.ChapterEditor).novel
                            ChapterEditorScreen(
                                chapters = libraryViewModel.chapters,
                                initialArcStartIds = libraryViewModel.arcStartChapterIds.value,
                                onSave = { updatedChapters, arcStartIds ->
                                    saveChapterEdits(novel, updatedChapters, arcStartIds)
                                },
                                onBack = { currentScreen.value = Screen.NovelDetail(novel) }
                            )
                        }

                        is Screen.Author -> {
                            val screen = currentScreen.value as Screen.Author
                            // Reload whenever the authorId changes (tapping into a
                            // different author's page while one is already showing isn't
                            // a real path today, but this keeps the screen correct if it
                            // ever is) - not on every recomposition.
                            LaunchedEffect(screen.authorId) {
                                loadAuthorPage(screen.authorId)
                            }
                            val author = authorPageAuthor.value
                            if (author != null) {
                                AuthorPageScreen(
                                    author = author,
                                    novels = authorPageNovels,
                                    onBack = { currentScreen.value = screen.from },
                                    onNovelClick = { novel ->
                                        lifecycleScope.launch {
                                            libraryViewModel.loadNovelDetails(novel)
                                            currentScreen.value = Screen.NovelDetail(novel)
                                        }
                                    }
                                )
                            }
                        }

                        is Screen.Settings -> {
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
                                onPrivacyPolicy = { currentScreen.value = Screen.PrivacyPolicy },
                                onTermsAndConditions = { currentScreen.value = Screen.TermsAndConditions },
                                onAboutMe = { currentScreen.value = Screen.AboutMe },
                                onBack = { currentScreen.value = Screen.Home }
                            )
                        }

                        is Screen.PrivacyPolicy -> {
                            LegalDocumentScreen(
                                title = "Privacy Policy",
                                sections = LegalContent.privacyPolicy,
                                onBack = { currentScreen.value = Screen.Settings }
                            )
                        }

                        is Screen.TermsAndConditions -> {
                            LegalDocumentScreen(
                                title = "Terms & Conditions",
                                sections = LegalContent.termsAndConditions,
                                onBack = { currentScreen.value = Screen.Settings }
                            )
                        }

                        is Screen.AboutMe -> {
                            WebViewScreen(
                                title = "About Me",
                                url = "https://rae-ark.horizonarkstudio.workers.dev/",
                                onBack = { currentScreen.value = Screen.Settings }
                            )
                        }
                    }
                }
                }

                when (val state = metadataSearchState.value) {
                    is MetadataSearchState.Loading -> {
                        MetadataSearchDialog(
                            novelTitle = state.novel.title,
                            isLoading = true,
                            errorMessage = null,
                            candidates = emptyList(),
                            onCandidateSelected = {},
                            onDismiss = { metadataSearchState.value = MetadataSearchState.Idle }
                        )
                    }
                    is MetadataSearchState.Results -> {
                        MetadataSearchDialog(
                            novelTitle = state.novel.title,
                            isLoading = false,
                            errorMessage = null,
                            candidates = state.candidates,
                            onCandidateSelected = { candidate -> applyMetadata(state.novel, candidate) },
                            onDismiss = { metadataSearchState.value = MetadataSearchState.Idle }
                        )
                    }
                    is MetadataSearchState.Error -> {
                        MetadataSearchDialog(
                            novelTitle = state.novel.title,
                            isLoading = false,
                            errorMessage = state.message,
                            candidates = emptyList(),
                            onCandidateSelected = {},
                            onDismiss = { metadataSearchState.value = MetadataSearchState.Idle }
                        )
                    }
                    is MetadataSearchState.Idle -> {}
                }

                // "Add fiction" (home screen icon) and "Check for updates"
                // (NovelDetailScreen) - see docs/arkarium/SYNC_MVP.md, Stage 3, and the later
                // move to single-origin name lookup via FictionLut.
                when (val state = addFictionState.value) {
                    AddFictionState.Hidden -> {}
                    AddFictionState.EnteringName -> {
                        AddFictionByNameDialog(
                            isLoading = false,
                            progressMessage = "",
                            errorMessage = null,
                            onConfirm = { name ->
                                val root = resolveLibraryRoot(this@MainActivity, useCustomFolder.value, savedUri.value)
                                if (root != null) {
                                    addFictionByName(name, root)
                                } else {
                                    addFictionState.value =
                                        AddFictionState.Error("No library folder is set up yet - pick one in Settings first.")
                                }
                            },
                            onDismiss = { addFictionState.value = AddFictionState.Hidden }
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
                                    addFictionByName(name, root)
                                } else {
                                    addFictionState.value =
                                        AddFictionState.Error("No library folder is set up yet - pick one in Settings first.")
                                }
                            },
                            onDismiss = { addFictionState.value = AddFictionState.Hidden }
                        )
                    }
                }

                // "Sync all Rae ARK's novels" (EmptyLibraryPrompt's primary first-run
                // action, see HomeScreen.kt / syncAllRaeArkNovels above). Reuses
                // SyncProgressDialog, same as the per-novel "Check for updates" dialog
                // below - just with "Rae ARK's novels" standing in for a single title.
                when (val state = syncAllState.value) {
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
                            onDismiss = { syncAllState.value = SyncAllState.Idle }
                        )
                    }
                    is SyncAllState.Error -> {
                        SyncProgressDialog(
                            novelTitle = "Rae ARK's novels",
                            isLoading = false,
                            message = "",
                            errorMessage = state.message,
                            onDismiss = { syncAllState.value = SyncAllState.Idle }
                        )
                    }
                }

                when (val state = syncCheckState.value) {
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
                            onDismiss = { syncCheckState.value = SyncCheckState.Idle }
                        )
                    }
                    is SyncCheckState.Error -> {
                        SyncProgressDialog(
                            novelTitle = state.novel.title,
                            isLoading = false,
                            message = "",
                            errorMessage = state.message,
                            onDismiss = { syncCheckState.value = SyncCheckState.Idle }
                        )
                    }
                }

                // See docs/arkarium/NEXT_FIXES.md #2 - offered whenever checkForUpdates hits a
                // missing-local-folder or source-gone situation, in place of silently
                // resolving either one.
                when (val state = syncResolutionState.value) {
                    SyncResolutionState.Idle -> {}
                    is SyncResolutionState.NeedsResolution -> {
                        SyncResolutionDialog(
                            novelTitle = state.novel.title,
                            isMissingLocally = state.reason == SyncResolutionReason.MISSING_LOCALLY,
                            onSyncAgain = {
                                resolveLibraryRoot(this@MainActivity, useCustomFolder.value, savedUri.value)?.let { root ->
                                    resolveMissingFolderBySyncing(state.novel, root)
                                }
                            },
                            onRemoveFromLibrary = { resolveByRemovingFromLibrary(state.novel) },
                            onUnlink = { resolveSourceGoneByUnlinking(state.novel) },
                            onDismiss = { syncResolutionState.value = SyncResolutionState.Idle }
                        )
                    }
                }
            }
        }
    }
}
