package com.arkarium.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Rung 9 (data-safety.md F1/F2). Runs on a device/emulator (./gradlew
// connectedDebugAndroidTest): it builds a real database at each committed schema
// version, runs the real migrations up to APP_DATABASE_VERSION, and lets Room compare
// the result against the current schema - so a migration whose SQL doesn't produce
// exactly what the entities declare fails here instead of on a user's phone.
//
// It reads app/schemas/<AppDatabase>/<version>.json, exported by KSP on every build
// (room.schemaLocation in app/build.gradle.kts). Those files must be committed; one is
// added per schema bump, and only versions that have a file can be tested. Versions
// 1-12 predate schema export, so nothing older than the first committed file can be
// replayed - the JVM-side MigrationChainTest still guards that part of the chain.
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    // Every N.json that was shipped into this test APK's assets.
    private fun committedVersions(): List<Int> =
        InstrumentationRegistry.getInstrumentation().context.assets
            .list(AppDatabase::class.java.canonicalName!!)
            .orEmpty()
            .mapNotNull { it.removeSuffix(".json").toIntOrNull() }
            .sorted()

    @Test
    fun currentVersionSchemaIsCommitted() {
        // Fails loudly (instead of the replay test below silently testing nothing) when
        // the schema for the current version was never committed.
        assertTrue(
            "app/schemas has no $APP_DATABASE_VERSION.json - run a build and commit it",
            APP_DATABASE_VERSION in committedVersions()
        )
    }

    @Test
    fun currentSchemaOpensAndValidatesWithNoMigration() {
        helper.createDatabase(dbName, APP_DATABASE_VERSION).close()
        helper.runMigrationsAndValidate(dbName, APP_DATABASE_VERSION, true)
    }

    @Test
    fun everyCommittedOlderVersionMigratesToCurrent() {
        committedVersions().filter { it < APP_DATABASE_VERSION }.forEach { from ->
            helper.createDatabase("$dbName-$from", from).close()
            helper.runMigrationsAndValidate(
                "$dbName-$from",
                APP_DATABASE_VERSION,
                true,
                *AppDatabase.ALL_MIGRATIONS
            )
        }
    }
}
