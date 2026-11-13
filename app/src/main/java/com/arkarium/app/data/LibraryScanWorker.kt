package com.arkarium.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

// "Background/periodic scan" (LibrarySettingsScreen's "Scanning behavior" section):
// rescans the library on a schedule even if ARKarium isn't open, so chapters dropped
// in overnight show up without a manual launch. Opt-in (PreferencesManager.
// backgroundScanEnabled, default false) - see schedule()/cancel() below, called from
// MainActivity whenever that preference changes, same "apply the new constraint
// immediately, not just on the next cold launch" pattern NewChapterCheckWorker.
// schedule's wifiOnly parameter now follows too.
//
// Deliberately a *lighter* pass than LibraryViewModel.startScan, not a reuse of it:
// startScan's handleScanCompleted does a stale-reconcile pass that can cascade-delete
// novels whose folder wasn't seen this run (see its own doc comment on
// missingLocalOnce). That two-consecutive-misses safety net lives in
// LibraryViewModel's in-memory state, which this headless worker has no access to and
// shouldn't try to reconstruct - a background pass silently deleting a novel because a
// removable volume happened to be slow to mount when WorkManager woke the process up
// would be a much worse failure mode than just leaving deletion to the next time the
// user actually opens the app (or taps "Remove missing novels" - see
// LibraryViewModel.removeMissingNovels). So this worker only discovers and
// upserts/merges novels and their chapters; onScanCompleted is left at scanRoot's
// default no-op.
class LibraryScanWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.create(applicationContext)
        val scanner = ScannerImpl(applicationContext)
        val prefsManager = PreferencesManager(applicationContext)

        val root = resolveLibraryRoot(applicationContext)
            ?: return Result.success() // shared storage not available right now

        val startedAt = System.currentTimeMillis()
        var authorsFolderFound = false
        try {
            scanner.scanRoot(
                root,
                onDiscovered = { scanned, novelFolder ->
                    val existing = db.novelDao().findById(scanned.id)
                    val novel = mergeNovelForRescan(scanned, existing, authorsFolderFound)
                    db.novelDao().upsert(novel)
                    scanner.scanChaptersForNovel(novelFolder, novel.id, db)
                },
                onAuthorsDiscovered = { discoveredAuthors, found ->
                    authorsFolderFound = found
                    // Same "only touch authors/ when this pass actually found it" rule
                    // as LibraryViewModel.handleAuthorsDiscovered - see that function's
                    // doc comment for why an empty-but-not-found list must never read
                    // as "delete every known author."
                    if (found) {
                        val seenIds = discoveredAuthors.map { it.id }.toSet()
                        discoveredAuthors.forEach { db.authorDao().upsert(it) }
                        db.authorDao().all().filter { it.id !in seenIds }.forEach { stale ->
                            db.authorDao().delete(stale.id)
                        }
                    }
                }
            )
        } catch (e: Exception) {
            // A transient SAF/IO failure - retry on WorkManager's own backoff rather
            // than waiting a full period for the next scheduled run, same as any other
            // CoroutineWorker in this codebase that can hit a recoverable I/O error.
            return Result.retry()
        }

        prefsManager.recordScanCompleted(System.currentTimeMillis(), System.currentTimeMillis() - startedAt)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "library_scan"

        // Called from MainActivity whenever backgroundScanEnabled changes (and once at
        // startup if it's already on). Runs at most every 12 hours - longer than
        // NewChapterCheckWorker's 6, since a full folder walk is more I/O than a
        // handful of manifest fetches and this is explicitly the "I'm fine waiting a
        // while, I just don't want to forget" tier of freshness per the proposal.
        // ExistingPeriodicWorkPolicy.UPDATE (not KEEP) - unlike NewChapterCheckWorker's
        // one-time unconditional schedule call, this is only ever called in response to
        // the user just having flipped the toggle on, so the new request should
        // actually take effect rather than no-op against whatever was already queued
        // from a previous enable/disable cycle.
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LibraryScanWorker>(12, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        // Called when backgroundScanEnabled is turned off - WorkManager has no
        // "pause", only cancel-and-later-reschedule, which is fine here since
        // schedule() above is idempotent to call again.
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
