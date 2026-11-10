package com.arkarium.app.viewmodel

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.arkarium.app.data.AppDatabase
import com.arkarium.app.data.ArcEntity
import com.arkarium.app.data.AuthorEntity
import com.arkarium.app.data.ChapterEntity
import com.arkarium.app.data.NovelEntity
import com.arkarium.app.data.ReadingStats
import com.arkarium.app.data.ScannerImpl
import com.arkarium.app.data.SyncStatus
import com.arkarium.app.data.mergeNovelForRescan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Stage 2.3 of Phase 2 (docs/arkarium/foundational/design-notes.md) - the second of the plan's
// four feature-scoped ViewModels pulled out of MainActivity. Owns the library's core
// in-memory state - novels, the currently-loaded novel's chapters/arcs,
// recentlyRead/inProgressNovels, and scan progress/message - plus startScan, the one
// piece of scan/library logic the plan's Stage 2.3 bullet scopes this stage to.
// loadNovelDetails and refreshRecentlyRead aren't named in that bullet, but they're
// the only functions that ever write chapters/arcs/overriddenChapterIds/
// arcStartChapterIds and recentlyRead/inProgressNovels respectively - once those
// fields moved here, so did the functions that populate them; leaving the fields here
// but the writers on MainActivity would mean reaching back into private ViewModel
// state from outside it, exactly what rule 2 (separation of concerns) rules out.
//
// Everything else that still reads/writes `novels` - addFictionByName,
// syncAllRaeArkNovels/checkForUpdates/scanSingleSyncedNovel, setNotifyEnabled, the
// sync-resolution actions, saveChapterEdits (which only calls this class's
// loadNovelDetails to refresh, rather than owning chapters itself) - stays on
// MainActivity until Stage 2.5 gives it a ViewModel; until then it reaches into
// libraryViewModel.novels directly, exactly as the plan's Stage 2.5 note anticipates:
// "[SyncViewModel's] scanSingleSyncedNovel path... reads/updates the novel list Stage
// 2.3 already moved." fetchMetadataFor/applyMetadata made the same move a stage early
// (Stage 2.4, MetadataViewModel) - applyMetadata takes this class as a constructor
// dependency and reaches into `novels` the same way MainActivity's other callers do.
//
// mutableStateListOf/mutableStateOf, not StateFlow<List<...>> - every one of these is
// mutated incrementally in place (index-set, add, removeAll), which is exactly the
// shape Compose's snapshot state system is built for; wrapping each mutation in a
// copy-the-whole-list StateFlow.update{} would be strictly more boilerplate for the
// same behavior (rule 4). SettingsViewModel's StateFlow-of-scalars is a different
// shape of state entirely (independent single values sourced from DataStore Flows),
// not a template that fits a shared, incrementally-updated list.
class LibraryViewModel(
    private val db: AppDatabase,
    private val scanner: ScannerImpl
) : ViewModel() {

    // Local (non-synced) novel ids that were missing from the most recently completed
    // scan's seenNovelIds, but not yet cascade-deleted - see onScanCompleted below and
    // docs/arkarium/proposal/bugs/other/uiux-bugs.md Bug 3. A SAF provider is allowed to
    // return a partial listing (DocumentsProvider not fully warmed up, a removable/
    // network-backed DocumentFile tree still populating, ordinary I/O jitter right after
    // process start), so one pass not seeing a novel that's still genuinely on disk isn't
    // proof it was deleted. Requiring the novel to be missing on two *consecutive* scans
    // before actually cascading the delete (which wipes chapters/reading_progress/
    // chapter_read_events via the FK cascade in Entities.kt) turns one flaky pass from a
    // silent, unrecoverable data-loss event into a no-op; the novel just needs to show up
    // seen once more. Deliberately kept in-memory rather than persisted: a fresh process
    // restart losing this set only ever costs one extra "benefit of the doubt" scan before
    // a genuinely-deleted novel is cleaned up - it can never make deletion happen sooner or
    // on less evidence than before this fix.
    private val missingLocalOnce = mutableSetOf<String>()

    val novels: SnapshotStateList<NovelEntity> = mutableStateListOf()
    // Overrides already applied - see loadNovelDetails.
    val chapters: SnapshotStateList<ChapterEntity> = mutableStateListOf()
    val arcs: SnapshotStateList<ArcEntity> = mutableStateListOf()
    // The only correct source for "resume reading this novel at its last chapter":
    // built from reading_progress.recentNovelIds(), so every entry is guaranteed to
    // have an actual saved position (see refreshRecentlyRead). Home's "Continue
    // Reading" row reads this one (HomeScreen's continueReadingNovels param, wired
    // up in MainActivity) - it used to read inProgressNovels just below, which is
    // not the same thing (see that val's own doc comment) and was the cause of
    // "Continue Reading opens the novel page, not the chapter".
    val recentlyRead: SnapshotStateList<NovelEntity> = mutableStateListOf()
    // NovelEntity.status == "IN_PROGRESS" - a shelf label the scanner/user sets on
    // the novel itself, independent of whether any chapter has ever actually been
    // opened (unlike recentlyRead above, which is keyed off reading_progress and
    // therefore always resumable). Currently unused in the UI - HomeScreen's
    // "Continue Reading" row was wired to this by mistake (fixed, see recentlyRead's
    // doc comment) and nothing else in the app reads it. Left in rather than
    // deleted: it's still correct, populated data that a future "Currently Reading"
    // shelf (distinct from "has progress to resume") could use.
    val inProgressNovels: SnapshotStateList<NovelEntity> = mutableStateListOf()

    // Backs the Home screen's reading-stats card (see ChapterReadEventEntity's doc
    // comment). Refreshed alongside recentlyRead/inProgressNovels in
    // refreshRecentlyRead - same "call this after anything that could have added a
    // new event" reasoning MainActivity.saveReadingProgress already follows for
    // that function - plus once more from startScan's own refreshRecentlyRead call,
    // so a cold launch shows the current streak immediately rather than waiting for
    // the first chapter read of the session.
    private val _readingStreakDays = mutableStateOf(0)
    val readingStreakDays: State<Int> = _readingStreakDays
    private val _chaptersReadThisWeek = mutableStateOf(0)
    val chaptersReadThisWeek: State<Int> = _chaptersReadThisWeek

    private val _overriddenChapterIds = mutableStateOf<Set<String>>(emptySet())
    val overriddenChapterIds: State<Set<String>> = _overriddenChapterIds
    private val _arcStartChapterIds = mutableStateOf<Set<String>>(emptySet())
    val arcStartChapterIds: State<Set<String>> = _arcStartChapterIds

    // (current, total) or null if not scanning.
    private val _scanProgress = mutableStateOf<Pair<Int, Int>?>(null)
    val scanProgress: State<Pair<Int, Int>?> = _scanProgress
    private val _scanMessage = mutableStateOf("")
    val scanMessage: State<String> = _scanMessage

    // Moved verbatim from MainActivity (Stage 2.3). Every reference to `novels`/
    // `scanProgress`/`scanMessage` below now resolves to this class's own fields
    // instead of MainActivity's, and mergeNovelForRescan/refreshRecentlyRead are
    // called the same way they were before the move. scanSingleSyncedNovel - the
    // other caller of mergeNovelForRescan - is deliberately NOT moved here; per the
    // plan it stays on MainActivity until Stage 2.5 (SyncViewModel).
    // The three scanner callbacks below used to be inline lambdas passed directly
    // to scanner.scanRoot. They're pure business logic - no UI, no Compose -
    // closing only over db/novels/_scanMessage/missingLocalOnce plus seenNovelIds
    // and authorsFolderFound, which are threaded in as parameters instead of
    // captured. Extracted to named functions so each is directly callable from a
    // test in isolation - notably handleScanCompleted's stale-reconcile logic,
    // which couldn't otherwise be exercised without running the whole scan
    // pipeline through scanRoot.

    // Fired for every novel folder scanRoot finds, before it attempts to
    // build/merge/upsert/chapter-scan that novel - see ScannerImpl's doc comment
    // on onDiscovered. authorsFolderFound is passed as a snapshot of the value
    // onAuthorsDiscovered already set: scanRoot always fires onAuthorsDiscovered
    // once before the per-novel loop that calls this, so it won't change between
    // calls here.
    private suspend fun handleNovelDiscovered(
        scanned: NovelEntity,
        novelFolder: DocumentFile,
        authorsFolderFound: Boolean,
        seenNovelIds: MutableSet<String>
    ) {
        try {
            val existing = db.novelDao().findById(scanned.id)
            val novel = mergeNovelForRescan(scanned, existing, authorsFolderFound)
            db.novelDao().upsert(novel)
            seenNovelIds.add(novel.id)
            withContext(Dispatchers.Main) {
                val idx = novels.indexOfFirst { it.id == novel.id }
                if (idx >= 0) novels[idx] = novel else novels.add(novel)
            }
            scanner.scanChaptersForNovel(novelFolder, novel.id, db) { message ->
                withContext(Dispatchers.Main) {
                    _scanMessage.value = "Scanning ${novel.title}: $message"
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                _scanMessage.value = "Skipped ${scanned.title}: ${e.message}"
            }
        }
    }

    // Returns the new authorsFolderFound value; the caller assigns it to its own
    // local var, since that value also needs to be threaded into
    // handleNovelDiscovered above.
    private suspend fun handleAuthorsDiscovered(discoveredAuthors: List<AuthorEntity>, found: Boolean): Boolean {
        // Only touch the authors table when this pass actually found authors/ -
        // an empty discoveredAuthors list from a not-found folder must never be
        // read as "delete every known author."
        if (found) {
            val seenIds = discoveredAuthors.map { it.id }.toSet()
            discoveredAuthors.forEach { db.authorDao().upsert(it) }
            db.authorDao().all().filter { it.id !in seenIds }.forEach { stale ->
                db.authorDao().delete(stale.id)
            }
        }
        return found
    }

    private suspend fun handleScanCompleted(sawAnyNovelFolders: Boolean, seenNovelIds: Set<String>) {
        val allNovels = db.novelDao().all()
        // See docs/arkarium/implementation/engineering-backlog.md Rung 1
        // (Mechanism 2): a SAF provider is allowed to hand back a partial or
        // empty listing (still fetching results, a removable volume dropping
        // mid-enumeration) with nothing to tell that apart from a folder that
        // genuinely has zero children. Finding zero novel folders while the DB
        // already has rows is treated as "this listing likely failed," not "the
        // library is now empty" - skip the stale-reconcile pass entirely rather
        // than reading every existing local novel as deleted.
        if (!sawAnyNovelFolders && allNovels.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                _scanMessage.value = "Scan found no novel folders, but the " +
                    "library isn't empty - skipped cleanup this pass to avoid " +
                    "losing data. Rescan to retry."
            }
        } else {
            // See bugs.md Bug 4 / docs/arkarium/foundational/architecture.md, Sync invariants S6 - a purely-local
            // novel this pass didn't rediscover is cascade-deleted; a synced
            // novel is marked MISSING_LOCALLY instead, since its folder can
            // always be re-fetched from its relay.
            val stale = allNovels.filter { it.id !in seenNovelIds }
            val staleLocal = stale.filter { it.syncSourceUrl == null }
            val staleSynced = stale.filter { it.syncSourceUrl != null && it.syncStatus != SyncStatus.MISSING_LOCALLY.name }

            // Bug 3 fix: a local novel only gets cascade-deleted once it's been
            // missing on two *consecutive* scans - see missingLocalOnce's doc
            // comment. First time it's missing, just remember it and move on.
            val confirmedMissingLocal = staleLocal.filter { it.id in missingLocalOnce }
            val firstTimeMissingLocal = staleLocal.filter { it.id !in missingLocalOnce }
            // Anything seen this pass that was previously flagged was a false
            // alarm (the earlier miss was transient) - clear it.
            missingLocalOnce.removeAll { it in seenNovelIds }
            missingLocalOnce.addAll(firstTimeMissingLocal.map { it.id })
            missingLocalOnce.removeAll(confirmedMissingLocal.map { it.id })

            confirmedMissingLocal.forEach { db.novelDao().delete(it.id) }
            staleSynced.forEach { db.novelDao().updateSyncStatus(it.id, SyncStatus.MISSING_LOCALLY.name) }
            if (confirmedMissingLocal.isNotEmpty() || staleSynced.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    val confirmedMissingLocalIds = confirmedMissingLocal.map { it.id }.toSet()
                    novels.removeAll { it.id in confirmedMissingLocalIds }
                    staleSynced.forEach { s ->
                        val idx = novels.indexOfFirst { it.id == s.id }
                        if (idx >= 0) novels[idx] = novels[idx].copy(syncStatus = SyncStatus.MISSING_LOCALLY.name)
                    }
                }
            }
            if (firstTimeMissingLocal.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    _scanMessage.value = "${firstTimeMissingLocal.size} novel(s) not found " +
                        "this scan - will be removed if still missing next scan."
                }
            }
        }
    }

    suspend fun startScan(root: DocumentFile) {
        // Tracks which novel IDs this scan pass actually found, so
        // handleScanCompleted can remove DB rows (and their cascaded
        // arcs/chapters/overrides/progress) for novels whose folder is genuinely
        // gone - see bugs.md Bug 4.
        val seenNovelIds = mutableSetOf<String>()
        // Set by handleAuthorsDiscovered below, which ScannerImpl.scanRoot fires
        // exactly once before the per-novel onDiscovered loop starts.
        var authorsFolderFound = false
        try {
            scanner.scanRoot(root,
                // Adding to seenNovelIds here, not only inside
                // handleNovelDiscovered's own try block, is the actual fix: a
                // novel that throws anywhere in that downstream pipeline is
                // still marked seen, so handleScanCompleted's stale-reconcile
                // can no longer treat "something about reading it failed" as
                // "this novel doesn't exist" - see
                // docs/arkarium/implementation/engineering-backlog.md Rung 1 (Mechanism 1).
                onNovelFolderSeen = { novelId -> seenNovelIds.add(novelId) },
                onDiscovered = { scanned, novelFolder ->
                    handleNovelDiscovered(scanned, novelFolder, authorsFolderFound, seenNovelIds)
                },
                onAuthorsDiscovered = { discoveredAuthors, found ->
                    authorsFolderFound = handleAuthorsDiscovered(discoveredAuthors, found)
                },
                onScanCompleted = { sawAnyNovelFolders ->
                    handleScanCompleted(sawAnyNovelFolders, seenNovelIds)
                },
                onProgress = { current, total, message ->
                    withContext(Dispatchers.Main) {
                        _scanProgress.value = Pair(current, total)
                        _scanMessage.value = message
                    }
                }
            )
            refreshRecentlyRead()
            withContext(Dispatchers.Main) {
                _scanProgress.value = null
                _scanMessage.value = ""
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                _scanProgress.value = null
                _scanMessage.value = "Library scan failed: ${e.message}"
            }
        }
    }

    // Lets a caller whose own scan setup failed before ever calling startScan (e.g.
    // MainActivity.onCreate's resolveLibraryRoot call) report that failure through
    // the same scanMessage a failed startScan itself would show - startScan already
    // catches everything thrown inside it, so this is only ever needed for a failure
    // that happens before startScan is even reached.
    fun reportScanSetupError(message: String) {
        _scanProgress.value = null
        _scanMessage.value = message
    }

    // Loads the raw scanned chapters for a novel and applies any saved
    // chapter_overrides (title/position) on top, so every screen downstream sees the
    // "effective" chapter list rather than raw scan data. Moved from MainActivity
    // alongside chapters/arcs/overriddenChapterIds/arcStartChapterIds themselves -
    // see the class doc comment above.
    suspend fun loadNovelDetails(novel: NovelEntity) {
        val raw = db.chapterDao().forNovel(novel.id)
        val overrides = db.chapterOverrideDao().forNovel(novel.id)
        val overridesByChapterId = overrides.associateBy { it.chapterId }
        val rawIndexById = raw.withIndex().associate { (i, c) -> c.id to i }

        val effective = raw
            .map { chapter ->
                val override = overridesByChapterId[chapter.id]
                if (override?.titleOverride != null) chapter.copy(title = override.titleOverride) else chapter
            }
            .sortedBy { chapter -> overridesByChapterId[chapter.id]?.positionOverride ?: rawIndexById[chapter.id] ?: 0 }

        chapters.clear()
        chapters.addAll(effective)
        arcs.clear()
        arcs.addAll(db.arcDao().forNovel(novel.id))
        _overriddenChapterIds.value = overridesByChapterId.keys
        _arcStartChapterIds.value = overrides.filter { it.isArcStart }.map { it.chapterId }.toSet()
    }

    // Moved from MainActivity alongside recentlyRead/inProgressNovels themselves.
    // Reads `novels` (this class's own field now) to resolve ids to entities.
    suspend fun refreshRecentlyRead() {
        val ids = db.readingProgressDao().recentNovelIds()
        val byId = novels.associateBy { it.id }
        recentlyRead.clear()
        recentlyRead.addAll(ids.mapNotNull { byId[it] })

        val inProgress = db.novelDao().byStatus("IN_PROGRESS")
        inProgressNovels.clear()
        inProgressNovels.addAll(inProgress)

        refreshReadingStats()
    }

    // Recomputes the Home screen's streak/weekly-count from chapter_read_events -
    // see ChapterReadEventEntity and ReadingStats. Kept as its own function (rather
    // than inlined into refreshRecentlyRead) so it stays independently callable if
    // a future caller ever needs to refresh just the stats without also re-running
    // refreshRecentlyRead's recentNovelIds/byStatus queries - today's only caller
    // is refreshRecentlyRead itself, immediately below.
    suspend fun refreshReadingStats() {
        val readDates = db.chapterReadEventDao().distinctReadDates()
        _readingStreakDays.value = ReadingStats.currentStreakDays(readDates)
        _chaptersReadThisWeek.value = db.chapterReadEventDao().countSince(ReadingStats.windowStartKey(7))
    }

    companion object {
        // Same manual-Factory shape as SettingsViewModel.factory (rule 4 - no DSL
        // needed for one Factory method). Takes the Activity's existing AppDatabase/
        // ScannerImpl instances rather than constructing its own, same "share the
        // Activity's one instance of each service" pattern SettingsViewModel.factory
        // uses for PreferencesManager.
        fun factory(db: AppDatabase, scanner: ScannerImpl): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LibraryViewModel(db, scanner) as T
                }
            }
    }
}
