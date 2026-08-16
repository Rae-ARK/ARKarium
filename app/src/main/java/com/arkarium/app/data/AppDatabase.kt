package com.arkarium.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NovelEntity::class,
        AuthorEntity::class,
        ArcEntity::class,
        ChapterEntity::class,
        ChapterOverrideEntity::class,
        ScanFingerprintEntity::class,
        ReadingProgressEntity::class,
        SyncedFileEntity::class
    ],
    version = 11
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

        // Migration from v8 to v9: adds sync relay tracking (see docs/arkarium/SYNC_MVP.md).
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

        // Migration from v9 to v10: adds sync_status to novels (see docs/arkarium/NEXT_FIXES.md
        // #2 - "no graceful handling when a synced novel's folder disappears"). Backfills
        // to 'ACTIVE' for every existing row, synced or not; the column is simply unused
        // for a purely-local novel (syncSourceUrl == null).
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE novels ADD COLUMN sync_status TEXT NOT NULL DEFAULT 'ACTIVE'")
            }
        }

        // Migration from v10 to v11: adds sync_folder_name to novels (see
        // docs/arkarium/NEXT_FIXES.md #5 and Entities.kt's doc comment on the column). Null-
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

        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "arkarium.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
            .fallbackToDestructiveMigration()
            .build()
    }
}
