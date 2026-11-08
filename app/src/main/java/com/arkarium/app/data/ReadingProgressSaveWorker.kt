package com.arkarium.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf

// Durable backstop for MainActivity.saveReadingProgress, closing the gap AppScope.io
// (see its own doc comment) was built to close but can't fully: AppScope.io survives
// Activity recreation (its SupervisorJob isn't tied to any Activity's lifecycleScope),
// but it is still an ordinary in-process coroutine - nothing protects a write launched
// on it from the process being reaped outright by lmkd (the low-memory killer) before
// the write commits. Android's documented guarantee for onPause/onStop is that the
// callback gets a best-effort window to *run*; nothing it kicks off is guaranteed to
// *finish* before the process dies, and there's no signal or grace period to detect
// that happening. A cold shortcut-launch is the worst case for this: Room's cold-open
// (file I/O, WAL recovery) competes with this write for the same IO-dispatcher
// threads, widening the window where the process can be killed mid-write.
//
// WorkManager's expedited one-time work is explicitly designed for exactly this ("this
// must survive even if the process dies mid-flight") - the system persists the work
// request itself before doWork() ever runs, and retries it in a fresh process if the
// one that enqueued it dies first. This is enqueued *alongside* (not instead of) the
// existing AppScope.io path in MainActivity, not as a replacement for it: the in-process
// path is what gives the live UI its immediate haptic + recently-read refresh when the
// process survives (the common case), and this Worker's job is only to guarantee the
// underlying Room row lands even when it doesn't. Both ultimately call the same
// upsert-by-key DAO methods AppScope.io's path does, so running both for the same
// position update is idempotent - whichever finishes writes the same row, and if this
// Worker's write happens to land after the in-process one, it's the same data anyway
// since both were built from the same ReaderPosition snapshot at enqueue time.
//
// Deliberately builds its own AppDatabase rather than reusing MainActivity's, the same
// way NewChapterCheckWorker does and for the same reason: a CoroutineWorker can run
// long after the Activity (or the whole app process) that enqueued it is gone, so it
// can't depend on anything Activity-scoped. No haptics or ViewModel refresh here on
// purpose - both are UI-facing niceties that only make sense when something is alive
// to show them; this Worker's only job is the data durability guarantee.
class ReadingProgressSaveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val novelId = inputData.getString(KEY_NOVEL_ID) ?: return Result.failure()
        val chapterId = inputData.getString(KEY_CHAPTER_ID) ?: return Result.failure()
        val charOffset = inputData.getInt(KEY_CHAR_OFFSET, 0)
        val snippet = inputData.getString(KEY_SNIPPET) ?: ""
        val completionFraction = inputData.getFloat(KEY_COMPLETION_FRACTION, 0f)

        val db = AppDatabase.create(applicationContext)

        // Mirrors MainActivity.saveReadingProgress's IO block exactly (see its own doc
        // comment for why completionFraction, not a charOffset-derived fraction, is
        // what gates the streak/weekly-count write) - this is the same single
        // authority's write, just reachable from a context that outlives the process
        // that originally triggered it.
        db.readingProgressDao().upsert(
            ReadingProgressEntity(
                novelId = novelId,
                chapterId = chapterId,
                position = charOffset.toFloat(),
                positionType = ReaderPosition.POSITION_TYPE,
                positionSnippet = snippet,
                updatedAt = System.currentTimeMillis()
            )
        )
        if (ReadingStats.isCompletionThreshold(completionFraction)) {
            val now = System.currentTimeMillis()
            val today = ReadingStats.dayKey(now)
            db.chapterReadEventDao().upsert(
                ChapterReadEventEntity(
                    chapterId = chapterId,
                    novelId = novelId,
                    readDate = today,
                    readAt = now
                )
            )
        }
        return Result.success()
    }

    companion object {
        private const val KEY_NOVEL_ID = "novel_id"
        private const val KEY_CHAPTER_ID = "chapter_id"
        private const val KEY_CHAR_OFFSET = "char_offset"
        private const val KEY_SNIPPET = "snippet"
        private const val KEY_COMPLETION_FRACTION = "completion_fraction"

        // One unique name for "the most recent pending position save," not one per
        // novel/chapter: ExistingWorkPolicy.REPLACE means a newer checkpoint (ON_PAUSE
        // immediately followed by onDispose, say) supersedes an older still-pending one
        // instead of piling up redundant writes - correct here because
        // onPositionChanged/onSystemBack always fire in chronological order on the main
        // thread, so "most recently enqueued" and "most recent position" are the same
        // thing, and the upsert is keyed by (novelId, chapterId) regardless.
        private const val UNIQUE_WORK_NAME = "reading_progress_save"

        fun enqueue(context: Context, novelId: String, chapterId: String, position: ReaderPosition) {
            val data = workDataOf(
                KEY_NOVEL_ID to novelId,
                KEY_CHAPTER_ID to chapterId,
                KEY_CHAR_OFFSET to position.charOffset,
                KEY_SNIPPET to position.snippet,
                KEY_COMPLETION_FRACTION to position.completionFraction
            )
            val request = OneTimeWorkRequestBuilder<ReadingProgressSaveWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
