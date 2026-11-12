package com.arkarium.app.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Backs the Home screen's "reading stats" card (streak + chapters read this week -
// see ChapterReadEventEntity's doc comment for why this feature exists). Kept as a
// plain, dependency-free object rather than living inline in LibraryViewModel so the
// streak-walking logic - the one part of this that's actually easy to get subtly
// wrong - can be reasoned about (and unit-tested, if this project adds a test source
// set later) in isolation from Room/Compose.
//
// All date math here uses java.util.Calendar/SimpleDateFormat, not java.time: minSdk
// is 24 (see app/build.gradle.kts) and java.time.LocalDate needs API 26 without core
// library desugaring, which this project doesn't have configured. Calendar's
// DAY_OF_YEAR arithmetic already handles month/year rollover correctly (Jan 1 minus
// one day correctly lands on Dec 31 of the previous year), so there's no real
// downside to sticking with it here.
object ReadingStats {
    // How far into a chapter (0f..1f) the user needs to have scrolled when leaving it
    // for that to count as "read" for streak/weekly-count purposes - see
    // MainActivity.saveReadingProgress, the only caller that checks this. Deliberately
    // not 1.0f: readers routinely leave a chapter's last line or two unscrolled (short
    // final paragraph, author's note they skip, etc.) without that meaning they didn't
    // actually finish it.
    //
    // NOT the same scale as ReadingProgressEntity.position since rung 14 (F9): that
    // column stores a CHAR_OFFSET resume anchor now, not a 0f..1f fraction. This is
    // compared against ReaderPosition.completionFraction instead - the real scroll
    // fraction, computed separately in ReaderScreen (see that field's doc comment for
    // the regression this split fixed: the two used to be conflated).
    const val COMPLETION_THRESHOLD = 0.9f

    // True once `progress` (0f..1f, ReaderPosition.completionFraction) has crossed
    // COMPLETION_THRESHOLD. A tiny wrapper, but it's the one place that fraction is
    // compared against the threshold, so a caller never has to know the comparison is
    // `>=` rather than `>`, or re-derive the fraction itself - see
    // MainActivity.saveReadingProgress, the only caller.
    fun isCompletionThreshold(progress: Float): Boolean = progress >= COMPLETION_THRESHOLD

    private const val DATE_PATTERN = "yyyy-MM-dd"

    // A fresh formatter per call rather than a shared companion-object instance:
    // SimpleDateFormat isn't thread-safe, and this is cheap enough (a handful of
    // calls per chapter-leave/Home-screen-load, never in a hot loop) that sharing
    // one across threads isn't worth the synchronization it would need.
    private fun formatter() = SimpleDateFormat(DATE_PATTERN, Locale.US)

    // "yyyy-MM-dd" for the given instant in the device's current default time zone -
    // i.e. the user's own local calendar day, not UTC. Two chapters finished at
    // 11:58pm and 12:03am local time should land on different days here even though
    // they're 5 minutes apart, exactly matching what "read something every day"
    // means to the person keeping the streak.
    fun dayKey(atMillis: Long = System.currentTimeMillis()): String = formatter().format(Date(atMillis))

    // One "yyyy-MM-dd" key per calendar day, walking backward from today:
    // index 0 = today, index 1 = yesterday, etc. Generating keys this way (instead
    // of subtracting `offset` days' worth of milliseconds from now) is what makes
    // this correct across DST transitions and variable month lengths for free -
    // Calendar.add(DAY_OF_YEAR, -1) always means "the previous calendar day," not
    // "24 hours ago."
    private fun dayKeysBackward(count: Int): List<String> = dayKeysBackwardFrom(System.currentTimeMillis(), count)

    // The "yyyy-MM-dd" key from `days - 1` days ago, inclusive of today - e.g.
    // days=7 gives the oldest day in "the last 7 days including today," suitable as
    // the `sinceDate` bound for ChapterReadEventDao.countSince (a plain `>=`
    // comparison, so this only needs the one boundary key, not the whole list).
    fun windowStartKey(days: Int): String = dayKeysBackward(days).last()

    // Length of the current read-every-day streak, in days, given the set of
    // distinct "yyyy-MM-dd" days that have at least one completed chapter (see
    // ChapterReadEventDao.distinctReadDates).
    //
    // Grace period: if today isn't in the set yet, the streak isn't necessarily
    // broken - today just hasn't happened yet from the user's perspective (they
    // might still open the app and read something before midnight). So a streak
    // that reaches yesterday still counts as "alive," and today itself simply
    // isn't included in the returned count until it's actually been read. Only a
    // *missing yesterday* (with today also missing) actually breaks it.
    // F28 (performance.md): this used to call dayKeysBackwardFrom(today, 3650), which
    // eagerly builds a 3,650-element List<String> (a Calendar.add and a date format
    // each) before the loop below ever runs - O(3650) time and space to answer an
    // O(streak length) question, paid on every Home-screen stats refresh regardless
    // of how short the actual streak is. This walks the same "yyyy-MM-dd" keys
    // backward one calendar day at a time instead, generating (and discarding) a key
    // only once the loop actually needs it, so the real cost is proportional to the
    // streak length - still capped at the same 3650-day (10-year) lookback as a hard
    // safety bound, just no longer spent up front on every call. The rule this
    // preserves exactly (see the doc comment above): a streak that reaches yesterday
    // still counts as alive even if today hasn't been read yet; only a missing
    // yesterday (with today also missing) breaks it.
    fun currentStreakDays(readDates: Collection<String>, today: Long = System.currentTimeMillis()): Int {
        if (readDates.isEmpty()) return 0
        val readSet = readDates.toHashSet()

        val calendar = Calendar.getInstance().apply { timeInMillis = today }
        val fmt = formatter()
        var daysWalked = 0
        fun nextKey(): String {
            if (daysWalked > 0) calendar.add(Calendar.DAY_OF_YEAR, -1)
            daysWalked++
            return fmt.format(calendar.time)
        }

        var key = nextKey() // today
        if (key !in readSet) {
            key = nextKey() // yesterday
            if (key !in readSet) return 0
        }

        var streak = 0
        // Same 3650-day (10-year) cap as before - see its original rationale: far
        // more lookback than any real streak will ever need, just large enough that
        // this loop always finds its own break condition first.
        while (daysWalked <= 3650) {
            if (key !in readSet) break
            streak++
            key = nextKey()
        }
        return streak
    }

    // Same walk-backward key generation as dayKeysBackward, just anchored at an
    // explicit instant instead of "now" - dayKeysBackward stays the "now" shorthand
    // most callers want, this is what currentStreakDays uses so it can be exercised
    // deterministically (a fixed `today`) if this project ever adds tests.
    private fun dayKeysBackwardFrom(atMillis: Long, count: Int): List<String> {
        val calendar = Calendar.getInstance().apply { timeInMillis = atMillis }
        val fmt = formatter()
        return List(count) { i ->
            if (i > 0) calendar.add(Calendar.DAY_OF_YEAR, -1)
            fmt.format(calendar.time)
        }
    }
}
