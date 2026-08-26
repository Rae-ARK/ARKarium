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
import com.arkarium.app.data.ChapterEntity
import com.arkarium.app.data.NovelEntity
import com.arkarium.app.data.ReadingStats
import com.arkarium.app.data.ScannerImpl
import com.arkarium.app.data.SyncStatus
import com.arkarium.app.data.mergeNovelForRescan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Stage 2.3 of Phase 2 (docs/arkarium/REFACTOR_PLAN.md) - the second of the plan's
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

    val novels: SnapshotStateList<NovelEntity> = mutableStateListOf()
    // Overrides already applied - see loadNovelDetails.
    val chapters: SnapshotStateList<ChapterEntity> = mutableStateListOf()
    val arcs: SnapshotStateList<ArcEntity> = mutableStateListOf()
    val recentlyRead: SnapshotStateList<NovelEntity> = mutableStateListOf()
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
    suspend fun startScan(root: DocumentFile) {
        // Tracks which novel IDs this scan pass actually found, so onScanCompleted
        // below can remove DB rows (and their cascaded arcs/chapters/overrides/
        // progress) for novels whose folder is genuinely gone - see bugs.md Bug 4.
        val seenNovelIds = mutableSetOf<String>()
        // Set by onAuthorsDiscovered below, which ScannerImpl.scanRoot fires exactly
        // once before the per-novel onDiscovered loop starts.
        var authorsFolderFound = false
        try {
            scanner.scanRoot(root,
                onDiscovered = { scanned, novelFolder ->
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
                },
                onAuthorsDiscovered = { discoveredAuthors, found ->
                    authorsFolderFound = found
                    // Only touch the authors table when this pass actually found
                    // authors/ - an empty discoveredAuthors list from a not-found
                    // folder must never be read as "delete every known author."
                    if (found) {
                        val seenIds = discoveredAuthors.map { it.id }.toSet()
                        discoveredAuthors.forEach { db.authorDao().upsert(it) }
                        db.authorDao().all().filter { it.id !in seenIds }.forEach { stale ->
                            db.authorDao().delete(stale.id)
                        }
                    }
                },
                onScanCompleted = {
                    // See bugs.md Bug 4 / docs/arkarium/NEXT_FIXES.md #2 - a purely-local
                    // novel this pass didn't rediscover is cascade-deleted; a synced
                    // novel is marked MISSING_LOCALLY instead, since its folder can
                    // always be re-fetched from its relay.
                    val stale = db.novelDao().all().filter { it.id !in seenNovelIds }
                    val staleLocal = stale.filter { it.syncSourceUrl == null }
                    val staleSynced = stale.filter { it.syncSourceUrl != null && it.syncStatus != SyncStatus.MISSING_LOCALLY.name }
                    staleLocal.forEach { db.novelDao().delete(it.id) }
                    staleSynced.forEach { db.novelDao().updateSyncStatus(it.id, SyncStatus.MISSING_LOCALLY.name) }
                    if (staleLocal.isNotEmpty() || staleSynced.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            val staleLocalIds = staleLocal.map { it.id }.toSet()
                            novels.removeAll { it.id in staleLocalIds }
                            staleSynced.forEach { s ->
                                val idx = novels.indexOfFirst { it.id == s.id }
                                if (idx >= 0) novels[idx] = novels[idx].copy(syncStatus = SyncStatus.MISSING_LOCALLY.name)
                            }
                        }
                    }
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
