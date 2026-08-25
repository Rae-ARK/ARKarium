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
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

// Background half of "Notify me when new chapters are available" (see
// docs/arkarium/NEW_CHAPTER_NOTIFICATIONS.md and NovelDetailScreen's toggle). Runs
// periodically via WorkManager (scheduled by `schedule()`, called once from
// MainActivity.onCreate) rather than anything tied to the Activity's own lifecycle -
// this needs to keep working whether or not ARKarium is currently open.
//
// Deliberately constructs its own AppDatabase/PreferencesManager/ScannerImpl/
// SyncManager instances rather than reusing MainActivity's - a CoroutineWorker can run
// long after (or without ever) the Activity existing in this process, so it can't
// depend on anything Activity-scoped. AppDatabase.create() and PreferencesManager both
// already work off nothing but an application Context, same as any other caller.
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
        val prefsManager = PreferencesManager(applicationContext)
        val scanner = ScannerImpl(applicationContext)
        val syncManager = SyncManager(applicationContext)

        val libraryRoot = resolveLibraryRoot(
            useCustomFolder = prefsManager.useCustomFolder.first(),
            savedUri = prefsManager.libraryUri.first()
        ) ?: return Result.success()  // no folder picked yet - nothing to check

        val candidates = db.novelDao().notifyEnabledSynced()
        if (candidates.isEmpty()) return Result.success()

        for (novel in candidates) {
            try {
                checkOneNovel(db, scanner, syncManager, libraryRoot, novel)
            } catch (e: Exception) {
                // One novel's manifest fetch failing (network blip, relay hiccup) shouldn't
                // block every other novel in this pass from being checked - same
                // "report and move on" tradeoff syncAllRaeArkNovels makes for
                // SourceGoneException, just broadened to any failure since there's no
                // dialog here to report a per-novel error into.
            }
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
    }

    // Mirrors MainActivity.resolveLibraryRoot exactly (see its own doc comment) - kept
    // as a private duplicate rather than a shared extracted function since the two
    // callers need genuinely different Context types (Activity vs. applicationContext)
    // and pulling this into a shared file for two three-line call sites isn't worth
    // the indirection.
    private fun resolveLibraryRoot(useCustomFolder: Boolean, savedUri: String?): DocumentFile? {
        if (useCustomFolder) {
            val uri = savedUri?.let { android.net.Uri.parse(it) } ?: return null
            return DocumentFile.fromTreeUri(applicationContext, uri)
        }
        val defaultDir = (applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir).also { it.mkdirs() }
        return DocumentFile.fromFile(defaultDir)
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "new_chapter_check"

        // Called once from MainActivity.onCreate. ExistingPeriodicWorkPolicy.KEEP means
        // a build that's already scheduled this (from a previous launch) doesn't get a
        // duplicate or reset one - only the very first call after install actually
        // enqueues anything. Runs at most every 6 hours (WorkManager periodic work is
        // inexact - the real interval can run longer depending on Doze/battery
        // optimization, never shorter), and only when a network is available, since a
        // manifest fetch with none would just fail immediately anyway.
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<NewChapterCheckWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
