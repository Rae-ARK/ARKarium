package com.arkarium.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Thrown by AppDatabase.create() instead of Room's own IllegalStateException
// specifically when the unhandled version Room hit is a downgrade (the running app's
// migration list only goes up to some version lower than what's already on disk) -
// see F23 in docs/proposal/data-safety.md. Kept as an IllegalStateException
// subtype so anything that already catches IllegalStateException around
// AppDatabase.create() still catches this; callers that want the friendlier message
// specifically (MainActivity.onCreate) catch this subtype first.
class DatabaseDowngradeException(message: String) : IllegalStateException(message)

// The schema version, as a constant so MigrationChainTest can check that the migration
// list actually reaches it. @Database below reads it from here - bump this (and add the
// matching MIGRATION_n_m + a committed app/schemas/.../n.json) together, never the
// annotation alone.
const val APP_DATABASE_VERSION = 15

// @Database must sit directly above the RoomDatabase subclass it annotates - an
// annotation binds to the very next declaration, and with DatabaseDowngradeException's
// class declaration (not just its doc comment) between this annotation and
// `abstract class AppDatabase` below, @Database was annotating DatabaseDowngradeException
// instead. Room's kapt pass then failed exactly as that implies: "Classes annotated
// with @Database should extend androidx.room.RoomDatabase" (DatabaseDowngradeException
// extends IllegalStateException, not RoomDatabase) and a second error trying to
// generate DatabaseDowngradeException_Impl as a subclass of a class Kotlin makes
// final by default. Moving the annotation back down here - after
// DatabaseDowngradeException, not before it - is the fix; AppDatabase itself, its
// entities list, and its version number are unchanged.
@Database(
    entities = [
        NovelEntity::class,
        AuthorEntity::class,
        ArcEntity::class,
        ChapterEntity::class,
        ChapterOverrideEntity::class,
        ScanFingerprintEntity::class,
        ReadingProgressEntity::class,
        SyncedFileEntity::class,
        ChapterReadEventEntity::class
    ],
    version = APP_DATABASE_VERSION
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun novelDao(): NovelDao
    abstract fun authorDao(): AuthorDao
    abstract fun arcDao(): ArcDao
    abstract fun chapterDao(): ChapterDao
    abstract fun chapterOverrideDao(): ChapterOverrideDao
    abstract fun scanFingerprintDao(): ScanFingerprintDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun syncedFileDao(): SyncedFileDao
    abstract fun chapterReadEventDao(): ChapterReadEventDao

    companion object {
        // Migration from v1 to v2: add new tables
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add page_size column to novels
                database.execSQL("ALTER TABLE novels ADD COLUMN page_size INTEGER NOT NULL DEFAULT 10")

                // Create arcs table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS arcs (
                        id TEXT PRIMARY KEY,
                        novel_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        cover_uri TEXT,
                        position INTEGER NOT NULL,
                        FOREIGN KEY(novel_id) REFERENCES novels(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                // Create chapter_overrides table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS chapter_overrides (
                        id TEXT PRIMARY KEY,
                        chapter_id TEXT NOT NULL,
                        title_override TEXT,
                        position_override INTEGER,
                        is_arc_start INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(chapter_id) REFERENCES chapters(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                // Create scan_fingerprints table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS scan_fingerprints (
                        id TEXT PRIMARY KEY,
                        novel_id TEXT NOT NULL,
                        folder_uri TEXT NOT NULL,
                        last_modified INTEGER,
                        size INTEGER,
                        scan_version INTEGER NOT NULL DEFAULT 1,
                        FOREIGN KEY(novel_id) REFERENCES novels(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                // Add arc_id column to chapters (nullable)
                database.execSQL("ALTER TABLE chapters ADD COLUMN arc_id TEXT")
            }
        }

        // Migration from v2 to v3: add reading_progress table.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS reading_progress (
                        novel_id TEXT PRIMARY KEY NOT NULL,
                        chapter_id TEXT NOT NULL,
                        position REAL NOT NULL,
                        position_type TEXT NOT NULL DEFAULT 'PERCENTAGE',
                        updated_at INTEGER NOT NULL,
                        FOREIGN KEY(novel_id) REFERENCES novels(id) ON DELETE CASCADE,
                        FOREIGN KEY(chapter_id) REFERENCES chapters(id) ON DELETE CASCADE
                    )
                """.trimIndent())
            }
        }

        // Migration from v3 to v4: add reading_status column to novels
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE novels ADD COLUMN reading_status TEXT NOT NULL DEFAULT 'NOT_STARTED'")
            }
        }

        // Migration from v4 to v5: add file_count to scan_fingerprints. Fingerprints are
        // now computed by walking the novel folder's actual files rather than reading the
        // folder's own metadata, so old fingerprint rows are no longer comparable; scan
        // logic forces a rescan of stale rows via CURRENT_SCAN_VERSION regardless of the
        // default value backfilled here.
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE scan_fingerprints ADD COLUMN file_count INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Migration from v5 to v6: add optional external-metadata columns to novels
        // (description, genres, remote cover, published date, source link, fetch
        // timestamp). All nullable/defaulted, so existing rows just get NULLs and
        // nothing downstream needs to change to keep working.
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE novels ADD COLUMN description TEXT")
                database.execSQL("ALTER TABLE novels ADD COLUMN genres TEXT")
                database.execSQL("ALTER TABLE novels ADD COLUMN remote_cover_url TEXT")
                database.execSQL("ALTER TABLE novels ADD COLUMN published_date TEXT")
                database.execSQL("ALTER TABLE novels ADD COLUMN external_source_url TEXT")
                database.execSQL("ALTER TABLE novels ADD COLUMN metadata_fetched_at INTEGER")
            }
        }

        // Migration from v6 to v7: add the `authors` table (see AuthorEntity /
        // AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md Stage 1) and a plain, unenforced
        // `author_id` column on novels pointing at it. No FK constraint is declared
        // here - SQLite's ALTER TABLE can't retroactively attach one to an existing
        // table, so NovelEntity.authorId deliberately isn't annotated with
        // @ForeignKey either; the link is resolved in application code (ScannerImpl,
        // NovelDao.byAuthor) instead of at the DB layer.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS authors (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        avatar_uri TEXT,
                        bio TEXT,
                        joined TEXT,
                        location TEXT,
                        gender TEXT,
                        links_json TEXT,
                        followers INTEGER,
                        favorites INTEGER,
                        reviews_received INTEGER,
                        ratings_received INTEGER,
                        source_path TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("ALTER TABLE novels ADD COLUMN author_id TEXT")
            }
        }

        // Migration from v7 to v8: add sort_tier to chapters (see bugs.md Bug 2 -
        // ScannerImpl now classifies each chapter as regular/bonus/closing via a
        // filename marker, instead of relying solely on `number`/`title` ordering).
        // Existing rows backfill to 0 (regular); ScannerImpl.CURRENT_SCAN_VERSION is
        // bumped alongside this so every novel gets rescanned once and its chapters
        // reclassified, rather than sitting at the default tier until the next
        // unrelated rescan happens to touch them.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chapters ADD COLUMN sort_tier INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Migration from v8 to v9: adds sync relay tracking (see docs/foundational/architecture.md, Sync).
        // Three new nullable columns on novels (sync_source_url/sync_source_version/
        // last_synced_at - all null = "purely local", same backfill-friendly pattern
        // as MIGRATION_5_6's metadata columns) plus the new synced_files table, which
        // is the diff source-of-truth a sync pass reads/writes against (see
        // SyncedFileEntity). No existing table's data is touched.
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE novels ADD COLUMN sync_source_url TEXT")
                database.execSQL("ALTER TABLE novels ADD COLUMN sync_source_version INTEGER")
                database.execSQL("ALTER TABLE novels ADD COLUMN last_synced_at INTEGER")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS synced_files (
                        novel_id TEXT NOT NULL,
                        relative_path TEXT NOT NULL,
                        sha256 TEXT NOT NULL,
                        size INTEGER NOT NULL,
                        PRIMARY KEY(novel_id, relative_path),
                        FOREIGN KEY(novel_id) REFERENCES novels(id) ON DELETE CASCADE
                    )
                """.trimIndent())
            }
        }

        // Migration from v9 to v10: adds sync_status to novels (see docs/foundational/architecture.md,
        // Sync invariants S6 - "no graceful handling when a synced novel's folder disappears"). Backfills
        // to 'ACTIVE' for every existing row, synced or not; the column is simply unused
        // for a purely-local novel (syncSourceUrl == null).
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE novels ADD COLUMN sync_status TEXT NOT NULL DEFAULT 'ACTIVE'")
            }
        }

        // Migration from v10 to v11: adds sync_folder_name to novels (see
        // docs/foundational/architecture.md, Sync invariants S9 and Entities.kt's doc comment on the column). Null-
        // backfilled for every existing row, synced or not; a synced novel created by an
        // older build picks up a real value the next time it successfully syncs (see
        // SyncManager.sync's folder-relocation fallback chain), a purely-local novel
        // never sets it at all.
        //
        // This migration intentionally does NOT rename any already-existing "synced-
        // <hash>" folder on disk to match its fiction's title - only newly-added
        // fictions get a human-readable folder name going forward. Renaming a folder
        // that's already sitting in someone's SAF tree is a separate, riskier operation
        // (it would need to happen while nothing else is reading from it, and papers
        // over the fact that sync's own lookup no longer requires the on-disk name to
        // mean anything in particular) - deliberately left alone here to keep this
        // migration a pure schema change with no filesystem side effects.
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE novels ADD COLUMN sync_folder_name TEXT")
            }
        }

        // Migration from v11 to v12: adds notify_new_chapters to novels (see
        // Entities.kt's doc comment on the column and docs/foundational/architecture.md, Sync: entry points).
        // Backfills to 0 (off) for every existing row, synced or not - nobody is
        // opted into background notifications by a version bump they didn't ask for.
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE novels ADD COLUMN notify_new_chapters INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Migration from v12 to v13: adds the chapter_read_events table backing the
        // Home screen's reading-stats card (streak + chapters read this week - see
        // ChapterReadEventEntity's doc comment). Purely additive - no existing table
        // is touched, and the new table starts empty for every existing install, so
        // streak/weekly-count both start at 0 rather than being backfilled from
        // reading_progress's history (that table only ever keeps one row per novel -
        // the *current* position - so there's no historical "which days did I read
        // on" data to reconstruct from it).
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS chapter_read_events (
                        chapter_id TEXT NOT NULL,
                        novel_id TEXT NOT NULL,
                        read_date TEXT NOT NULL,
                        read_at INTEGER NOT NULL,
                        PRIMARY KEY(chapter_id, read_date),
                        FOREIGN KEY(chapter_id) REFERENCES chapters(id) ON DELETE CASCADE
                    )
                """.trimIndent())
            }
        }

        // Migration from v13 to v14: adds the position_snippet column reading_progress
        // needs for rung 14 (F9, docs/proposal/reader-playback.md -
        // ReaderPosition.kt). Purely additive, nullable, no default to backfill - every
        // existing row is positionType PERCENTAGE and stays that way; it just gets a
        // null snippet, same as any future PERCENTAGE row would. Nothing re-anchors an
        // existing saved position onto a paragraph offset retroactively; a novel with a
        // pre-rung-14 PERCENTAGE row simply isn't restored into mid-chapter on its next
        // open (same as today - see ReaderScreen's restoreReaderPosition) until the
        // reader leaves that chapter at least once more and a CHAR_OFFSET row is
        // written in its place.
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE reading_progress ADD COLUMN position_snippet TEXT")
            }
        }

        // Migration from v14 to v15: adds the six foreign-key indices performance.md
        // F24 found missing (see Entities.kt's `indices = [...]` on each entity below -
        // SQLite indexes a declared primary key automatically but never a
        // @ForeignKey's child column, so Room only warns, it doesn't create one).
        // reading_progress, chapter_read_events and synced_files are untouched: each is
        // already covered by its own primary key (see F24's "Correction" in
        // performance.md), so adding an index there would be redundant. Purely
        // additive and non-destructive - CREATE INDEX never touches existing rows -
        // and index names match Room's own default convention
        // (`index_<table>_<column>`) so a committed schema export agrees with what a
        // real build regenerates.
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_arcs_novel_id` ON `arcs` (`novel_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_chapters_novel_id` ON `chapters` (`novel_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_chapters_arc_id` ON `chapters` (`arc_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_chapter_overrides_chapter_id` ON `chapter_overrides` (`chapter_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_reading_progress_chapter_id` ON `reading_progress` (`chapter_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_scan_fingerprints_novel_id` ON `scan_fingerprints` (`novel_id`)")
            }
        }

        // Every migration, in order - the one list create() hands to Room, and the one
        // MigrationChainTest (JVM) and MigrationTest (instrumented) check. A new
        // MIGRATION_n_m only counts once it is added here.
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
            MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15
        )

        // NOTE: deliberately no fallbackToDestructiveMigration() here. That fallback
        // would silently drop and recreate the whole database - including
        // chapter_overrides (manual edits a rescan can't rebuild), reading_progress
        // and chapter_read_events (streaks) - on any version Room can't find an
        // explicit migration path for. An unhandled version bump should surface as a
        // crash during development, not as silent data loss for a real user. If a
        // destructive reset is ever genuinely needed for a specific old version (e.g.
        // an unsupported pre-release install), add a scoped
        // fallbackToDestructiveMigrationFrom(startVersion = ...) rather than reaching
        // for the global fallback again.
        // Room's own exception text for an unhandled version, both directions, names
        // the stored and requested versions as "... migration from X to Y was
        // required but not found ...". Not part of Room's public API (no dedicated
        // exception subtype or accessor exists to check this without string-matching
        // - confirmed by reading the thrown IllegalStateException's message), so this
        // is deliberately narrow: anything that doesn't match passes through as the
        // original exception rather than being guessed at.
        private val UNHANDLED_VERSION_MESSAGE = Regex("migration from (\\d+) to (\\d+)", RegexOption.IGNORE_CASE)

        // See DatabaseDowngradeException's doc comment and F23 in data-safety.md. A
        // downgrade is "from" > "to": the version already on disk (written by a newer
        // APK) is higher than the version this build's migration list tops out at.
        // Anything else - including a genuine missing upgrade migration, which is a
        // real bug to catch in development, not something to soften - is rethrown
        // unchanged.
        internal fun asDowngradeExceptionIfApplicable(e: IllegalStateException): IllegalStateException {
            val (from, to) = UNHANDLED_VERSION_MESSAGE.find(e.message ?: "")
                ?.destructured
                ?: return e
            return if (from.toInt() > to.toInt()) {
                DatabaseDowngradeException(
                    "This library was last opened by a newer version of ARKarium - " +
                        "update the app rather than reinstalling an older version."
                )
            } else {
                e
            }
        }

        // F8 (engineering-backlog.md rung 3): MainActivity and
        // NewChapterCheckWorker each used to call create() independently and get
        // back two live RoomDatabase instances in the same process - against
        // Room's own per-process-singleton guidance, and meaning the worker's
        // writes weren't visible to an already-open Activity (or vice versa)
        // until the underlying SQLite file was re-read. @Volatile plus the
        // double-checked lock below makes create() build the real database at
        // most once per process and hand back that same instance on every later
        // call, from either caller, without either caller needing to hold or
        // pass a reference to the other.
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // NOTE: deliberately catching only IllegalStateException here, not Throwable -
        // that's the type Room.databaseBuilder(...).build() throws for an unhandled
        // version (the no-destructive-fallback case above) and the only case this
        // downgrade check knows how to recognize. Anything else (a stale kapt cache,
        // a genuinely corrupt .db file, ...) should keep surfacing as whatever it
        // actually is, caught by MainActivity's own catch-all around this call.
        fun create(context: Context): AppDatabase {
            INSTANCE?.let { return it }
            synchronized(this) {
                INSTANCE?.let { return it }
                val built = try {
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "arkarium.db"
                    ).addMigrations(*ALL_MIGRATIONS)
                        .build()
                } catch (e: IllegalStateException) {
                    throw asDowngradeExceptionIfApplicable(e)
                }
                INSTANCE = built
                return built
            }
        }
    }
}
