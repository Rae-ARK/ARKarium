package com.arkarium.app.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

// Coverage for ReadingStats.currentStreakDays - added alongside F28 (performance.md),
// which rewrote this to walk days lazily instead of eagerly building a 3650-entry
// list up front. No tests existed for this before (see F28's "Direction": keep the
// "today may be unread, yesterday counts" rule exactly, add a test for it first).
class ReadingStatsTest {

    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun dayKey(offsetFromToday: Int, today: Calendar): String {
        val c = today.clone() as Calendar
        c.add(Calendar.DAY_OF_YEAR, -offsetFromToday)
        return fmt.format(c.time)
    }

    private fun today(): Calendar = Calendar.getInstance()

    @Test
    fun noReadDates_streakIsZero() {
        assertEquals(0, ReadingStats.currentStreakDays(emptyList(), today().timeInMillis))
    }

    @Test
    fun onlyTodayRead_streakIsOne() {
        val t = today()
        val dates = setOf(dayKey(0, t))
        assertEquals(1, ReadingStats.currentStreakDays(dates, t.timeInMillis))
    }

    @Test
    fun todayNotReadButYesterdayWas_stillCountsTheStreak() {
        // The core rule this file exists to pin down: today not having happened yet
        // (from the user's perspective) must not zero out a streak that's otherwise
        // alive through yesterday.
        val t = today()
        val dates = setOf(dayKey(1, t), dayKey(2, t), dayKey(3, t))
        assertEquals(3, ReadingStats.currentStreakDays(dates, t.timeInMillis))
    }

    @Test
    fun todayAndYesterdayBothMissing_streakIsBroken() {
        val t = today()
        val dates = setOf(dayKey(2, t), dayKey(3, t), dayKey(4, t))
        assertEquals(0, ReadingStats.currentStreakDays(dates, t.timeInMillis))
    }

    @Test
    fun gapInTheMiddleOfTheStreak_stopsAtTheGap() {
        val t = today()
        val dates = setOf(dayKey(0, t), dayKey(1, t), dayKey(3, t), dayKey(4, t))
        assertEquals(2, ReadingStats.currentStreakDays(dates, t.timeInMillis))
    }

    @Test
    fun longStreak_countsPastASingleDigitOfDays() {
        val t = today()
        val dates = (0..20).map { dayKey(it, t) }.toSet()
        assertEquals(21, ReadingStats.currentStreakDays(dates, t.timeInMillis))
    }

    @Test
    fun unrelatedFutureAndFarPastDates_dontAffectTheStreak() {
        val t = today()
        val dates = setOf(dayKey(0, t), dayKey(1, t), dayKey(-5, t), dayKey(500, t))
        assertEquals(2, ReadingStats.currentStreakDays(dates, t.timeInMillis))
    }

    @Test
    fun streakCrossesAMonthAndYearBoundary() {
        // Jan 2, 2026 00:00 local - a short streak back through New Year's Eve and
        // into December exercises Calendar's month/year rollover, same rationale as
        // this file's own doc comment on why Calendar is used over raw millis math.
        val anchor = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 2, 12, 0, 0)
        }
        val dates = setOf(
            dayKey(0, anchor), // 2026-01-02
            dayKey(1, anchor), // 2026-01-01
            dayKey(2, anchor), // 2025-12-31
            dayKey(3, anchor)  // 2025-12-30
        )
        assertEquals(4, ReadingStats.currentStreakDays(dates, anchor.timeInMillis))
    }

    @Test
    fun streakLongerThanTenYears_isCappedAtTheSafetyBound() {
        // Matches the original implementation's own documented cap: 3650 days is a
        // hard safety bound, not a tuned expectation of real streak length.
        val t = today()
        val dates = (0..4000).map { dayKey(it, t) }.toSet()
        assertEquals(3650, ReadingStats.currentStreakDays(dates, t.timeInMillis))
    }
}
