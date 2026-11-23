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
import com.arkarium.app.data.sync.MissingLocalFolderException
import com.arkarium.app.data.sync.SourceGoneException
import com.arkarium.app.data.sync.SyncManager
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

        // F3/R3 fix (urgent-review.md): a novel whose checkOneNovel attempt throws a
        // genuine (non-cancellation) exception used to be silently swallowed here -
        // no notification, no Pigeon Rally entry, no error, no timestamp, and the
        // worker still reported Result.success() regardless. checkOneNovel itself now
        // always records last_notify_check_at/last_notify_check_error before
        // returning or rethrowing (see its own doc comment), so the UI can already
        // show *that* something failed and why; this flag additionally tells
        // WorkManager to actually retry the pass (with backoff) instead of waiting
        // out the full periodic interval again, same as any other transient
        // failure (network blip, relay hiccup) deserves.
        val anyTransientFailure = java.util.concurrent.atomic.AtomicBoolean(false)

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
                            // report a per-novel error into. checkOneNovel has already
                            // persisted the per-novel error before rethrowing; this just
                            // decides the worker-level Result below.
                            anyTransientFailure.set(true)
                        }
                    }
                }
            }.awaitAll()
        }
        return if (anyTransientFailure.get()) Result.retry() else Result.success()
    }

    // Syncs one novel and, only if that sync actually pulled down changes, rescans its
    // chapters and diffs the before/after chapter id sets to find what's genuinely new
    // - chapter ids are deterministic (hashed from novelId + file URI, see
    // ScannerImpl.parseChaptersInFolder), so a chapter that already existed keeps the
    // same id across rescans and never shows up as "new" here even though upsert()
    // touches its row again. A manifest version bump with no net-new chapter (e.g. a
    // metadata.json tweak or a chapter edit) correctly produces zero notifications.
    //
    // F3/R3 fix (urgent-review.md), two changes from the original version:
    //
    // 1. Observability: every exit path now calls
    //    db.novelDao().updateNotifyCheckResult(...) before returning, recording
    //    `now` and either null (success/no-op) or a message (failure) - surfaced by
    //    NovelDetailScreen under the "Notify me" toggle so a reader can tell the
    //    feature is actually running, instead of a silent background pass that
    //    either works or doesn't with no visible difference.
    //
    // 2. Lost-notification fix: the sync-state commit (updateSyncState/
    //    updateSyncStatus/syncedFileDao writes) now happens AFTER the rescan and
    //    diff succeed, not before. Previously the new sync version was committed as
    //    soon as the manifest download finished; if the novel-folder lookup then
    //    returned null, or scanChaptersForNovel threw, the function exited with that
    //    version already saved - so the next run saw an unchanged version, skipped
    //    the diff entirely, and the new chapters were never announced or logged,
    //    permanently. Reordering means a mid-flight failure here leaves the old
    //    version in place, so the next run's sync sees the same "changed" outcome
    //    again and retries the rescan+diff from scratch instead of silently giving up.
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
        val now = System.currentTimeMillis()

        val outcome = try {
            syncManager.sync(novel, libraryRoot, knownFiles, allowRecreateMissingFolder = false)
        } catch (e: MissingLocalFolderException) {
            // Not a transient failure - this needs the user's own resolution via
            // SyncResolutionDialog (see docs/foundational/architecture.md, Sync
            // invariants S6), same as before. Still recorded as a successful *check*
            // (no error) since the worker correctly detected and handled this state;
            // sync_status itself is what surfaces the missing-folder condition.
            db.novelDao().updateSyncStatus(novel.id, SyncStatus.MISSING_LOCALLY.name)
            db.novelDao().updateNotifyCheckResult(novel.id, now, null)
            return
        } catch (e: SourceGoneException) {
            db.novelDao().updateSyncStatus(novel.id, SyncStatus.SOURCE_GONE.name)
            db.novelDao().updateNotifyCheckResult(novel.id, now, null)
            return
        } catch (e: Exception) {
            // Transient: network failure, malformed manifest, hash mismatch, etc.
            // Recorded so the toggle can show *why* the feature looks broken, and
            // rethrown so doWork()'s caller marks this pass for Result.retry()
            // instead of silently reporting success.
            db.novelDao().updateNotifyCheckResult(novel.id, now, e.message ?: "Check failed: ${e::class.simpleName}")
            throw e
        }
        if (!outcome.changed) {
            db.novelDao().updateNotifyCheckResult(novel.id, now, null)
            return
        }

        try {
            val novelFolder = SyncManager.findNovelFolder(libraryRoot, novel.id)
                ?: throw IllegalStateException("Synced novel folder not found after sync")
            scanner.scanChaptersForNovel(novelFolder, novel.id, db)

            val afterChapters = db.chapterDao().forNovel(novel.id)
            val newChapters = afterChapters.filterNot { it.id in beforeChapterIds }

            // Only committed once the rescan+diff above have actually succeeded -
            // see this function's doc comment for why that ordering matters.
            db.syncedFileDao().deleteForNovel(novel.id)
            db.syncedFileDao().upsertAll(outcome.files.map { it.copy(novelId = novel.id) })
            db.novelDao().updateSyncState(
                novel.id, sourceUrl, outcome.newVersion, now,
                outcome.folderName ?: novel.syncFolderName ?: SyncManager.slugForUrl(sourceUrl)
            )
            db.novelDao().updateSyncStatus(novel.id, SyncStatus.ACTIVE.name)
            db.novelDao().updateNotifyCheckResult(novel.id, now, null)

            if (newChapters.isEmpty()) return

            // "Latest" for the notification text - highest chapter `number` among the
            // new ones, falling back to the last one found if none carry a parsed
            // number (e.g. a bonus/closing-tier file, see ScannerImpl.parseChapter).
            // Not necessarily reading order, just whichever single title is most
            // worth naming in the notification when there's more than one.
            val latest = newChapters.maxByOrNull { it.number ?: Int.MIN_VALUE } ?: newChapters.last()
            NewChapterNotifier.notify(applicationContext, novel, newChapters.size, latest.title)
            // Notice Board's persistent copy of the same "new chapters arrived" moment
            // (docs/proposal/notice-board.md) - logged right alongside the transient
            // system notification above, from the same newChapters/latest this
            // function already computed. Only reached when notify is already reached
            // (this whole function only runs for `candidates` from
            // notifyEnabledSynced()), so this is already gated on the per-novel
            // "Notify me" toggle the same way the notification itself is - no
            // separate check needed here.
            db.chapterUpdateEventDao().insert(
                ChapterUpdateEventEntity(
                    id = UUID.randomUUID().toString(),
                    novelId = novel.id,
                    newChapterCount = newChapters.size,
                    latestChapterTitle = latest.title,
                    detectedAt = now
                )
            )
        } catch (e: Exception) {
            // The sync itself succeeded (outcome.changed == true) but the rescan,
            // diff, or notify/log step failed - record it as this check's error. The
            // sync-state commit above is still gated behind this same try block, so
            // nothing has been marked "up to date" yet; the next run's sync will see
            // the same changed manifest and retry the rescan+diff from scratch.
            db.novelDao().updateNotifyCheckResult(novel.id, now, e.message ?: "Check failed: ${e::class.simpleName}")
            throw e
        }
    }

    companion object {
        // How many novels doWork() checks at once - same reasoning as
        // SyncViewModel.NOVEL_SYNC_CONCURRENCY (see its own doc comment): high enough
        // to overlap several novels' manifest-fetch latency, not so high that this
        // periodic background pass opens dozens of simultaneous connections to the
        // relay the moment it wakes up.
        private const val NOVEL_CHECK_CONCURRENCY = 4

        private const val UNIQUE_WORK_NAME = "new_chapter_check"

        // F4/R4 fix (urgent-review.md): a one-off pass has its own unique name so it
        // never collides with (or gets silently deduped against) the periodic work
        // above - WorkManager's ExistingWorkPolicy is keyed per unique-work-name, not
        // globally, so running both under the same name would make one replace the
        // other instead of both existing independently.
        private const val ONE_TIME_WORK_NAME = "new_chapter_check_one_time"

        // Called once from MainActivity.onCreate with the current value of
        // PreferencesManager.wifiOnlySync (LibrarySettingsScreen's "Wi-Fi-only sync"
        // toggle), and again every time that toggle changes - MainActivity passes a
        // fresh value either way, this just decides the WorkManager policy to apply it
        // with. ExistingPeriodicWorkPolicy.KEEP for the former call: a build that's
        // already scheduled this (from a previous launch, with whatever constraint was
        // in effect then) doesn't get a duplicate or reset one just because onCreate
        // ran again. ExistingPeriodicWorkPolicy.UPDATE for the latter: a toggle flip
        // should actually take effect on the already-queued request's constraints
        // rather than no-op against it the way KEEP would.
        //
        // F4/R4 fix (urgent-review.md): lowered from 6 hours to 2 - the proposal's
        // suggested interval. WorkManager periodic work is still inexact (the real
        // interval can run longer depending on Doze/battery optimization, never
        // shorter), and this alone still can't notify "within minutes" of a chapter
        // going up - see enqueueOneTimeCheck below and MainActivity's toggle-on/
        // foreground callers, which cover the two cases where a reader is actually
        // watching for it to happen right now.
        fun schedule(context: Context, wifiOnly: Boolean, replaceExisting: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<NewChapterCheckWorker>(2, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                if (replaceExisting) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        // F4/R4 fix (urgent-review.md): "the check runs about every 6 (now 2) hours
        // at best, never when the reader turns the feature on, and never when the
        // app is opened." Fires one immediate, one-shot pass against every
        // notify-enabled novel - called from MainActivity right after a "Notify me"
        // toggle flips on (NovelDetailScreen's onToggleNotify) and every time the app
        // comes to the foreground (onStart). ExistingWorkPolicy.REPLACE: if one of
        // these is already pending/running (e.g. the reader backgrounds and
        // foregrounds the app twice in quick succession), the newer request simply
        // supersedes it rather than queuing up a second redundant pass - there's
        // nothing about an in-flight check that a brand new one needs to wait for.
        // Same network constraint (wifiOnly) as the periodic request, so a one-time
        // nudge can't bypass the user's Wi-Fi-only sync choice.
        fun enqueueOneTimeCheck(context: Context, wifiOnly: Boolean) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val request = androidx.work.OneTimeWorkRequestBuilder<NewChapterCheckWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
