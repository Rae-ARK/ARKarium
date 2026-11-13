package com.arkarium.app.data

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import java.util.concurrent.TimeUnit

// Background half of "Notify me when new chapters are available" (see
// docs/foundational/architecture.md, Sync: entry points and NovelDetailScreen's toggle). Runs
// periodically via WorkManager (scheduled by `schedule()`, called once from
// MainActivity.onCreate) rather than anything tied to the Activity's own lifecycle -
// this needs to keep working whether or not ARKarium is currently open.
//
// Deliberately constructs its own AppDatabase/ScannerImpl/SyncManager instances
// rather than reusing MainActivity's - a CoroutineWorker can run long after (or
// without ever) the Activity existing in this process, so it can't depend on
// anything Activity-scoped. AppDatabase.create() works off nothing but an
// application Context, same as resolveLibraryRoot (data/LibraryScan.kt) below.
//
// Each per-novel check mirrors MainActivity.checkForUpdates + scanSingleSyncedNovel's
// sync-then-rescan sequence, but headless (no UI state to update) and additionally
// diffing the chapter list before/after to know whether to notify - see
// checkOneNovel's doc comment below. `allowRecreateMissingFolder` is never true here,
// same reasoning as checkForUpdates's automatic-call default: a background pass never
// silently resolves a missing folder or gone source, it just leaves sync_status for
// the user to see and resolve next time they open the app.
class NewChapterCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.create(applicationContext)
        val scanner = ScannerImpl(applicationContext)
        val syncManager = SyncManager(applicationContext)

        val libraryRoot = resolveLibraryRoot(applicationContext)
            ?: return Result.success()  // shared storage not available right now - nothing to check

        val candidates = db.novelDao().notifyEnabledSynced()
        if (candidates.isEmpty()) return Result.success()

        // Bounded fan-out instead of one novel at a time - same bottleneck
        // SyncViewModel.syncAllRaeArkNovels fixes for the user-facing batch add: each
        // novel's manifest fetch used to wait for the previous novel's entire
        // check+sync+rescan to finish first, serializing a full network round-trip
        // per novel even though nothing here actually depends on another novel's
        // result. Safe to run concurrently without SyncManager's folder-creation
        // mutex (see downloadInitial) because checkOneNovel always passes
        // allowRecreateMissingFolder = false - this path only ever resyncs a folder
        // that already exists, never creates one, so there's no check-then-create
        // race between novels to guard against.
        coroutineScope {
            val semaphore = Semaphore(NOVEL_CHECK_CONCURRENCY)
            candidates.map { novel ->
                async {
                    semaphore.withPermit {
                        try {
                            checkOneNovel(db, scanner, syncManager, libraryRoot, novel)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // The worker's scope has been cancelled (WorkManager stopping
                            // us, e.g. constraints no longer met) - propagate instead of
                            // swallowing, or every other in-flight novel keeps hitting the
                            // network after the coroutine is supposed to have stopped.
                            throw e
                        } catch (e: Exception) {
                            // One novel's manifest fetch failing (network blip, relay
                            // hiccup) shouldn't block any other novel in this pass from
                            // being checked - same "report and move on" tradeoff
                            // syncAllRaeArkNovels makes for SourceGoneException, just
                            // broadened to any failure since there's no dialog here to
                            // report a per-novel error into.
                        }
                    }
                }
            }.awaitAll()
        }
        return Result.success()
    }

    // Syncs one novel and, only if that sync actually pulled down changes, rescans its
    // chapters and diffs the before/after chapter id sets to find what's genuinely new
    // - chapter ids are deterministic (hashed from novelId + file URI, see
    // ScannerImpl.parseChaptersInFolder), so a chapter that already existed keeps the
    // same id across rescans and never shows up as "new" here even though upsert()
    // touches its row again. A manifest version bump with no net-new chapter (e.g. a
    // metadata.json tweak or a chapter edit) correctly produces zero notifications.
    private suspend fun checkOneNovel(
        db: AppDatabase,
        scanner: ScannerImpl,
        syncManager: SyncManager,
        libraryRoot: DocumentFile,
        novel: NovelEntity
    ) {
        val sourceUrl = novel.syncSourceUrl ?: return
        val knownFiles = db.syncedFileDao().forNovel(novel.id)
        val beforeChapterIds = db.chapterDao().forNovel(novel.id).map { it.id }.toSet()

        val outcome = try {
            syncManager.sync(novel, libraryRoot, knownFiles, allowRecreateMissingFolder = false)
        } catch (e: MissingLocalFolderException) {
            db.novelDao().updateSyncStatus(novel.id, SyncStatus.MISSING_LOCALLY.name)
            return
        } catch (e: SourceGoneException) {
            db.novelDao().updateSyncStatus(novel.id, SyncStatus.SOURCE_GONE.name)
            return
        }
        if (!outcome.changed) return

        db.syncedFileDao().deleteForNovel(novel.id)
        db.syncedFileDao().upsertAll(outcome.files.map { it.copy(novelId = novel.id) })
        db.novelDao().updateSyncState(
            novel.id, sourceUrl, outcome.newVersion, System.currentTimeMillis(),
            outcome.folderName ?: novel.syncFolderName ?: SyncManager.slugForUrl(sourceUrl)
        )
        db.novelDao().updateSyncStatus(novel.id, SyncStatus.ACTIVE.name)

        val novelFolder = SyncManager.findNovelFolder(libraryRoot, novel.id) ?: return
        scanner.scanChaptersForNovel(novelFolder, novel.id, db)

        val afterChapters = db.chapterDao().forNovel(novel.id)
        val newChapters = afterChapters.filterNot { it.id in beforeChapterIds }
        if (newChapters.isEmpty()) return

        // "Latest" for the notification text - highest chapter `number` among the new
        // ones, falling back to the last one found if none carry a parsed number (e.g.
        // a bonus/closing-tier file, see ScannerImpl.parseChapter). Not necessarily
        // reading order, just whichever single title is most worth naming in the
        // notification when there's more than one.
        val latest = newChapters.maxByOrNull { it.number ?: Int.MIN_VALUE } ?: newChapters.last()
        NewChapterNotifier.notify(applicationContext, novel, newChapters.size, latest.title)
        // Notice Board's persistent copy of the same "new chapters arrived" moment
        // (docs/proposal/notice-board.md) - logged right alongside the transient
        // system notification above, from the same newChapters/latest this function
        // already computed. Only reached when notify is already reached (this whole
        // function only runs for `candidates` from notifyEnabledSynced()), so this is
        // already gated on the per-novel "Notify me" toggle the same way the
        // notification itself is - no separate check needed here.
        db.chapterUpdateEventDao().insert(
            ChapterUpdateEventEntity(
                id = UUID.randomUUID().toString(),
                novelId = novel.id,
                newChapterCount = newChapters.size,
                latestChapterTitle = latest.title,
                detectedAt = System.currentTimeMillis()
            )
        )
    }

    companion object {
        // How many novels doWork() checks at once - same reasoning as
        // SyncViewModel.NOVEL_SYNC_CONCURRENCY (see its own doc comment): high enough
        // to overlap several novels' manifest-fetch latency, not so high that this
        // periodic background pass opens dozens of simultaneous connections to the
        // relay the moment it wakes up.
        private const val NOVEL_CHECK_CONCURRENCY = 4

        private const val UNIQUE_WORK_NAME = "new_chapter_check"

        // Called once from MainActivity.onCreate with the current value of
        // PreferencesManager.wifiOnlySync (LibrarySettingsScreen's "Wi-Fi-only sync"
        // toggle), and again every time that toggle changes - MainActivity passes a
        // fresh value either way, this just decides the WorkManager policy to apply it
        // with. ExistingPeriodicWorkPolicy.KEEP for the former call: a build that's
        // already scheduled this (from a previous launch, with whatever constraint was
        // in effect then) doesn't get a duplicate or reset one just because onCreate
        // ran again. ExistingPeriodicWorkPolicy.UPDATE for the latter: a toggle flip
        // should actually take effect on the already-queued request's constraints
        // rather than no-op against it the way KEEP would. Runs at most every 6 hours
        // (WorkManager periodic work is inexact - the real interval can run longer
        // depending on Doze/battery optimization, never shorter) either way.
        fun schedule(context: Context, wifiOnly: Boolean, replaceExisting: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<NewChapterCheckWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                if (replaceExisting) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
