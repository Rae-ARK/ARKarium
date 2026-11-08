package com.arkarium.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Rung 9 (data-safety.md F1/F2). Needs no device or schema files: it checks the one
// thing that decides whether a user's database survives an update - that
// AppDatabase.ALL_MIGRATIONS is an unbroken 1 -> 2 -> ... -> APP_DATABASE_VERSION chain.
// With the global destructive fallback gone, a missing or mis-numbered step makes Room
// throw at startup instead of wiping data, so a gap here would lock users out; this
// catches it in CI instead. What the SQL inside each step actually does is covered by
// the instrumented MigrationTest.
class MigrationChainTest {

    private val migrations = AppDatabase.ALL_MIGRATIONS.toList()

    @Test
    fun everyStepMovesExactlyOneVersionUp() {
        migrations.forEach {
            assertEquals(
                "Migration ${it.startVersion}->${it.endVersion} must be a single step",
                it.startVersion + 1,
                it.endVersion
            )
        }
    }

    @Test
    fun chainIsContiguousFromVersionOne() {
        assertEquals(1, migrations.first().startVersion)
        migrations.zipWithNext().forEach { (a, b) ->
            assertEquals(
                "Gap or overlap between ${a.startVersion}->${a.endVersion} and " +
                    "${b.startVersion}->${b.endVersion}",
                a.endVersion,
                b.startVersion
            )
        }
    }

    @Test
    fun chainEndsAtTheCurrentDatabaseVersion() {
        assertEquals(APP_DATABASE_VERSION, migrations.last().endVersion)
    }

    @Test
    fun noStepIsListedTwice() {
        val starts = migrations.map { it.startVersion }
        assertEquals(starts.size, starts.toSet().size)
        assertTrue(migrations.isNotEmpty())
    }
}
