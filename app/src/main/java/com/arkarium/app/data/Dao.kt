package com.arkarium.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface NovelDao {
    // Cold-start data-loss bug: this used to be @Insert(onConflict = REPLACE).
    // OnConflictStrategy.REPLACE isn't a real UPDATE - on a primary-key conflict
    // SQLite performs a DELETE of the existing row followed by an INSERT of the new
    // one (see https://sqlite.org/lang_conflict.html). A DELETE, even one immediately
    // followed by a same-keyed INSERT, fires every ON DELETE CASCADE foreign key that
    // points at that row. arcs/chapters/chapter_overrides/reading_progress/
    // scan_fingerprints (Entities.kt) all cascade off novels.id, and
    // MainActivity.onCreate runs a full library rescan on every cold launch
    // (LibraryViewModel.startScan -> ScannerImpl.onDiscovered -> db.novelDao().upsert)
    // unconditionally, for every novel folder found, whether or not anything about it
    // actually changed. So every cold start was deleting and recreating every known
    // novel row, cascading away that novel's reading_progress (continue-reading
    // position) and chapter_read_events (streak/weekly-count) before the scan ever
    // got a chance to read them back - this is also what made
    // ChapterDao.upsert below independently finish the job for any row the novel-level
    // cascade hadn't already caught, chapter by chapter.
    //
    // @Upsert (Room 2.5+) is a real upsert: it attempts an INSERT, and only performs
    // an UPDATE of the existing row's columns on a primary-key conflict - no DELETE is
    // issued, so no ON DELETE CASCADE child row is ever touched by an ordinary
    // "nothing changed" rescan. Functionally this is still exactly what every caller
    // below already expects from a method named "upsert": insert if new, overwrite
    // fields if existing.
    @Upsert
    suspend fun upsert(novel: NovelEntity)

    @Query("SELECT * FROM novels ORDER BY title")
    suspend fun all(): List<NovelEntity>

    // Cascades to arcs/chapters/chapter_overrides/reading_progress/scan_fingerprints
    // via their onDelete=CASCADE foreign keys (see Entities.kt) - a single call here
    // cleans up everything belonging to this novel, no separate per-table deletes needed.
    @Query("DELETE FROM novels WHERE id = :novelId")
    suspend fun delete(novelId: String)

    @Query("SELECT * FROM novels WHERE id = :novelId")
    suspend fun findById(novelId: String): NovelEntity?

    @Query("UPDATE novels SET page_size = :pageSize WHERE id = :novelId")
    suspend fun updatePageSize(novelId: String, pageSize: Int)

    @Query("UPDATE novels SET reading_status = :status WHERE id = :novelId")
    suspend fun updateReadingStatus(novelId: String, status: String)

    // Author-declared publication status (NovelEntity.publicationStatus) - written by
    // either a scan (metadata.json) or a sync pass (the manifest's own "status"
    // field); see LibraryScan.kt's mergeNovelForRescan for how the two are arbitrated.
    // `status` is nullable here (unlike updateReadingStatus above) since a manifest
    // can legitimately stop declaring a status it previously had.
    @Query("UPDATE novels SET publication_status = :status WHERE id = :novelId")
    suspend fun updatePublicationStatus(novelId: String, status: String?)

    // Author-declared cadence (NovelEntity.chaptersPerWeek) - same nullable,
    // fill-in-or-clear contract as updatePublicationStatus above, written from the
    // same sync outcome at the same call sites (see SyncViewModel).
    @Query("UPDATE novels SET chapters_per_week = :chaptersPerWeek WHERE id = :novelId")
    suspend fun updateChaptersPerWeek(novelId: String, chaptersPerWeek: Double?)

    @Query("SELECT * FROM novels WHERE reading_status = :status ORDER BY title")
    suspend fun byStatus(status: String): List<NovelEntity>

    // Backs the Stage 2 author page's fiction list.
    @Query("SELECT * FROM novels WHERE author_id = :authorId ORDER BY title")
    suspend fun byAuthor(authorId: String): List<NovelEntity>

    // Writes a confirmed metadata match (see NovelMetadataProvider) onto an existing
    // novel row. A plain UPDATE rather than upsert, since we're patching fields on a
    // row that must already exist - upserting a partial entity here would null out
    // every column not listed.
    @Query("""
        UPDATE novels SET
            description = :description,
            genres = :genres,
            remote_cover_url = :remoteCoverUrl,
            published_date = :publishedDate,
            external_source_url = :externalSourceUrl,
            metadata_fetched_at = :fetchedAt
        WHERE id = :novelId
    """)
    suspend fun updateMetadata(
        novelId: String,
        description: String?,
        genres: String?,
        remoteCoverUrl: String?,
        publishedDate: String?,
        externalSourceUrl: String?,
        fetchedAt: Long
    )

    // Written once after "Add fiction from URL"'s first sync (once the library scan
    // has discovered the synced folder and assigned it a real novel id - see
    // docs/foundational/architecture.md, Sync: scan reuse), and again after every subsequent successful re-sync. A
    // plain UPDATE, same rationale as updateMetadata above - this patches an
    // already-existing row rather than upserting a partial entity.
    //
    // `folderName` is the exact on-disk folder name this sync pass actually resolved
    // and wrote into (see SyncManager.sync/downloadInitial and Entities.kt's doc
    // comment on sync_folder_name) - written on every successful sync, not just the
    // first, so a pre-MIGRATION_10_11 row (folder name still null) opportunistically
    // backfills it the next time it syncs rather than staying null forever.
    @Query("""
        UPDATE novels SET
            sync_source_url = :sourceUrl,
            sync_source_version = :sourceVersion,
            last_synced_at = :syncedAt,
            sync_folder_name = :folderName
        WHERE id = :novelId
    """)
    suspend fun updateSyncState(novelId: String, sourceUrl: String, sourceVersion: Int, syncedAt: Long, folderName: String)

    // See docs/foundational/architecture.md, Sync invariants S6 / Entities.kt SyncStatus. Set to MISSING_LOCALLY when a
    // synced novel's on-disk folder is discovered gone by a scan, or to SOURCE_GONE when
    // its relay 404s on manifest.json - either way this deliberately never deletes the
    // row itself (see startScan's stale-removal in MainActivity.kt).
    @Query("UPDATE novels SET sync_status = :status WHERE id = :novelId")
    suspend fun updateSyncStatus(novelId: String, status: String)

    // "Unlink" resolution for a SOURCE_GONE novel (see docs/foundational/architecture.md, Sync invariants S6): drops the
    // sync relationship entirely and reverts this novel to a plain local one. Local
    // content is untouched - only the sync bookkeeping columns are cleared. Caller is
    // still responsible for clearing this novel's SyncedFileEntity rows if desired;
    // leaving them behind is harmless (they just become inert once sync_source_url is
    // null, since nothing reads them for a non-synced novel).
    // Known gap: ScannerImpl.buildNovelEntity now re-recognizes a FictionLut title
    // match on every rescan (see its comment), and mergeNovelForRescan only ever
    // keeps an existing non-null syncSourceUrl, never a "this was deliberately
    // unlinked" fact - there's no column for that. So if the bundled fiction_lut.dat
    // still lists this exact title (it's a point-in-time snapshot of the relay's
    // catalog, not refreshed live), the very next rescan can re-link a novel someone
    // just unlinked here. In practice this only matters for a SOURCE_GONE unlink
    // whose title is *also* still in an already-installed build's bundled LUT, which
    // should be rare and self-corrects on the LUT's own next regeneration - flagged
    // here rather than solved with a new column until it actually bites someone.

    @Query("""
        UPDATE novels SET
            sync_source_url = NULL,
            sync_source_version = NULL,
            sync_status = 'ACTIVE'
        WHERE id = :novelId
    """)
    suspend fun unlinkSyncSource(novelId: String)

    // Backs NovelDetailScreen's "Notify me when new chapters are available" toggle
    // (see docs/foundational/architecture.md, Sync: entry points). A plain UPDATE, same rationale as
    // updatePageSize/updateReadingStatus above.
    @Query("UPDATE novels SET notify_new_chapters = :enabled WHERE id = :novelId")
    suspend fun updateNotifyNewChapters(novelId: String, enabled: Boolean)

    // Every novel NewChapterCheckWorker's periodic background pass needs to check:
    // opted into notifications, actually synced from somewhere, and not already known
    // to be missing/gone (MISSING_LOCALLY and SOURCE_GONE both need a user decision
    // via SyncResolutionDialog first - a background worker never resolves either one
    // on its own, same as checkForUpdates's allowRecreateMissingFolder always being
    // false for an automatic call).
    @Query("""
        SELECT * FROM novels
        WHERE notify_new_chapters = 1
          AND sync_source_url IS NOT NULL
          AND sync_status = 'ACTIVE'
    """)
    suspend fun notifyEnabledSynced(): List<NovelEntity>
}

@Dao
interface AuthorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(author: AuthorEntity)

    @Query("SELECT * FROM authors ORDER BY name")
    suspend fun all(): List<AuthorEntity>

    @Query("SELECT * FROM authors WHERE id = :authorId")
    suspend fun findById(authorId: String): AuthorEntity?

    @Query("DELETE FROM authors WHERE id = :authorId")
    suspend fun delete(authorId: String)
}

@Dao
interface ArcDao {
    // Same family of bug as NovelDao.upsert/ChapterDao.upsert above. arcs is the
    // parent of chapters.arc_id (Entities.kt, onDelete = SET_NULL), and
    // ScannerImpl.scanNovelFolder upserts every already-known arc unconditionally
    // on every rescan, before it gets to (re)upserting that arc's chapters in a
    // separate pass right after. With REPLACE, every such upsert on an existing
    // arc issued a DELETE+INSERT of that arc row, which fired the SET_NULL action
    // on every one of its chapters immediately - so for the whole rest of that
    // scan pass, those chapters sat in the DB with arc_id = NULL, only getting
    // their real arc_id back once the later chapter-upsert pass reached them. If
    // the scan was ever interrupted (process killed, an exception, a permission
    // revoke mid-scan) between the two passes, those chapters were left
    // permanently detached from their arc - falling back to the novel's
    // root/"All Chapters" grouping instead of their actual arc. @Upsert never
    // deletes the row on conflict, so it never fires the SET_NULL in the first
    // place.
    @Upsert
    suspend fun upsert(arc: ArcEntity)

    @Query("SELECT * FROM arcs WHERE novel_id = :novelId ORDER BY position")
    suspend fun forNovel(novelId: String): List<ArcEntity>

    @Query("DELETE FROM arcs WHERE novel_id = :novelId")
    suspend fun deleteForNovel(novelId: String)

    @Query("DELETE FROM arcs WHERE id = :arcId")
    suspend fun delete(arcId: String)
}

@Dao
interface ChapterDao {
    // Same cold-start data-loss bug as NovelDao.upsert above (see its doc comment for
    // the full mechanism), and the bigger half of it in practice: ScannerImpl.
    // parseChaptersInFolder calls this once per chapter *file*, unconditionally, on
    // every rescan - so even after NovelDao.upsert stopped the novel-level cascade,
    // this REPLACE was still independently DELETE+INSERTing every already-known
    // chapter row on every cold start, which cascades (Entities.kt) to
    // reading_progress.chapter_id, chapter_read_events.chapter_id, and
    // chapter_overrides.chapter_id - wiping continue-reading position, streak/
    // weekly-count history, and manual chapter overrides for every chapter the scan
    // touched, not just the one currently being read.
    //
    // @Upsert fixes it the same way: insert if new, UPDATE in place (no DELETE, so no
    // cascade) if the row already exists - still exactly what every caller expects
    // from "upsert".
    @Upsert
    suspend fun upsert(chapter: ChapterEntity)

    // Bug 2 follow-up: the original fix (`ORDER BY sort_tier, number, title`, no arc
    // grouping) sorted sort_tier *globally across the whole novel* rather than within
    // each arc/folder. That meant a closing/bonus chapter (tier 1/2) in an early arc
    // sorted after every regular (tier 0) chapter in every *later* arc too - e.g. Arc
    // 1's Afterword landed after all of Arc 2's and Arc 3's regular chapters, at the
    // very bottom of the novel, instead of right after Arc 1's own last chapter. The
    // per-arc detail tab happened to look correct (it filters this same list down to
    // one arc, which preserves relative order within that arc), but "All Chapters"
    // and chapter-to-chapter Previous/Next navigation (both driven by this flat list -
    // see MainActivity.loadNovelDetails) showed the bug.
    //
    // Fix: group by arc first - root-level chapters (arc_id IS NULL, i.e. the novel
    // folder itself, always scanned before any arc subfolder - see
    // ScannerImpl.scanChaptersForNovel) come first, then each arc in its own
    // ArcEntity.position order - and only *within* that grouping do sort_tier/number/
    // title decide order, so bonus/closing content still lands after every regular
    // chapter but only within its own arc, never spilling into a later arc's block.
    @Query("""
        SELECT chapters.* FROM chapters
        LEFT JOIN arcs ON chapters.arc_id = arcs.id
        WHERE chapters.novel_id = :novelId
        ORDER BY
            CASE WHEN chapters.arc_id IS NULL THEN 0 ELSE 1 END,
            arcs.position,
            chapters.sort_tier,
            chapters.number,
            chapters.title
    """)
    suspend fun forNovel(novelId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE arc_id = :arcId ORDER BY sort_tier, number, title")
    suspend fun forArc(arcId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :chapterId")
    suspend fun findById(chapterId: String): ChapterEntity?

    // Resolves a manifest's relative path (ManifestFileEntry.relativePath, already
    // SyncPaths.sanitize'd) back to the chapter row ScannerImpl's rescan just
    // (re)created for it - see ChapterEntity.relativePath's doc comment. The caller
    // (SyncViewModel) uses this chapter's id to write into chapter_manifest_meta;
    // nothing here writes word_count/read_time_minutes directly.
    @Query("SELECT * FROM chapters WHERE novel_id = :novelId AND relative_path = :relativePath")
    suspend fun findByNovelAndRelativePath(novelId: String, relativePath: String): ChapterEntity?

    @Query("DELETE FROM chapters WHERE novel_id = :novelId")
    suspend fun deleteForNovel(novelId: String)

    @Query("DELETE FROM chapters WHERE id = :chapterId")
    suspend fun delete(chapterId: String)
}

@Dao
interface ChapterOverrideDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: ChapterOverrideEntity)

    @Query("SELECT * FROM chapter_overrides WHERE chapter_id = :chapterId")
    suspend fun forChapter(chapterId: String): ChapterOverrideEntity?

    // Joins through chapters so we can fetch every override for a novel in one query,
    // used to apply overrides on top of the scanned chapter list at read time.
    @Query("""
        SELECT chapter_overrides.* FROM chapter_overrides
        INNER JOIN chapters ON chapter_overrides.chapter_id = chapters.id
        WHERE chapters.novel_id = :novelId
    """)
    suspend fun forNovel(novelId: String): List<ChapterOverrideEntity>

    @Query("DELETE FROM chapter_overrides WHERE chapter_id = :chapterId")
    suspend fun delete(chapterId: String)
}

@Dao
interface ReadingProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ReadingProgressEntity)

    @Query("SELECT * FROM reading_progress WHERE novel_id = :novelId")
    suspend fun forNovel(novelId: String): ReadingProgressEntity?

    // Backs the "Continue Reading" row: most recently read novels first.
    @Query("SELECT novel_id FROM reading_progress ORDER BY updated_at DESC LIMIT :limit")
    suspend fun recentNovelIds(limit: Int = 10): List<String>
}

@Dao
interface ChapterReadEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: ChapterReadEventEntity)

    // Lets the caller tell "this chapter was just finished today" from "an already-logged
    // finished chapter was saved again" (see MainActivity.saveReadingProgress). The
    // table's key is (chapter_id, read_date), so "logged" means logged for that day.
    @Query("SELECT EXISTS(SELECT 1 FROM chapter_read_events WHERE chapter_id = :chapterId AND read_date = :readDate)")
    suspend fun isLogged(chapterId: String, readDate: String): Boolean

    // "Chapters read this week" - `sinceDate` is a "yyyy-MM-dd" key (see
    // ReadingStats.dayKey); plain string >= works because ISO dates sort
    // lexicographically the same as chronologically.
    @Query("SELECT COUNT(*) FROM chapter_read_events WHERE read_date >= :sinceDate")
    suspend fun countSince(sinceDate: String): Int

    // Every distinct day with at least one completed chapter - the raw input
    // ReadingStats.currentStreakDays walks backward from today over. Order doesn't
    // matter to that function (it does its own set lookup), so this doesn't bother
    // sorting.
    @Query("SELECT DISTINCT read_date FROM chapter_read_events")
    suspend fun distinctReadDates(): List<String>
}

@Dao
interface ScanFingerprintDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fingerprint: ScanFingerprintEntity)

    @Query("SELECT * FROM scan_fingerprints WHERE novel_id = :novelId")
    suspend fun forNovel(novelId: String): ScanFingerprintEntity?

    @Query("DELETE FROM scan_fingerprints WHERE novel_id = :novelId")
    suspend fun delete(novelId: String)
}

@Dao
interface SyncedFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: SyncedFileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(files: List<SyncedFileEntity>)

    @Query("SELECT * FROM synced_files WHERE novel_id = :novelId")
    suspend fun forNovel(novelId: String): List<SyncedFileEntity>

    @Query("DELETE FROM synced_files WHERE novel_id = :novelId AND relative_path = :relativePath")
    suspend fun delete(novelId: String, relativePath: String)

    @Query("DELETE FROM synced_files WHERE novel_id = :novelId")
    suspend fun deleteForNovel(novelId: String)
}

@Dao
interface ChapterManifestMetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: ChapterManifestMetaEntity)

    @Query("SELECT * FROM chapter_manifest_meta WHERE chapter_id = :chapterId")
    suspend fun forChapter(chapterId: String): ChapterManifestMetaEntity?

    // Batch read for a chapter list (NovelDetailScreen) - one query for the whole
    // visible list instead of one per row, same reasoning as every other F26-flagged
    // per-row query in this codebase (see ScannerImpl's listFiles() sharing).
    @Query("SELECT * FROM chapter_manifest_meta WHERE chapter_id IN (:chapterIds)")
    suspend fun forChapters(chapterIds: List<String>): List<ChapterManifestMetaEntity>
}

@Dao
interface CatalogueEntryDao {
    // No cascading children point at catalogue_entries (unlike NovelDao/ArcDao/
    // ChapterDao's @Upsert fix - see their doc comments), so a plain REPLACE upsert
    // carries none of that risk here: a re-ingested slug just overwrites its row.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CatalogueEntryEntity)

    // Per-slug ingestion pass (Rung 2) upserts one at a time (C4: one slug's failure
    // never aborts the rest), but a batch helper is cheap to have for a caller that
    // ends up with several already-fetched rows at once.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<CatalogueEntryEntity>)

    @Query("SELECT * FROM catalogue_entries ORDER BY title")
    suspend fun all(): List<CatalogueEntryEntity>

    @Query("SELECT * FROM catalogue_entries WHERE slug = :slug")
    suspend fun findBySlug(slug: String): CatalogueEntryEntity?

    // C2: a catalogue entry disappears the moment its fiction is followed - called by
    // SyncViewModel.dropCatalogueEntry (Rung 4) on the addFictionByName / Sync-all
    // success paths.
    @Query("DELETE FROM catalogue_entries WHERE slug = :slug")
    suspend fun delete(slug: String)
}

@Dao
interface NovelAnnouncementDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(announcements: List<NovelAnnouncementEntity>)

    @Query("SELECT * FROM novel_announcements WHERE novel_id = :novelId ORDER BY fetched_at DESC")
    suspend fun forNovel(novelId: String): List<NovelAnnouncementEntity>

    @Query("DELETE FROM novel_announcements WHERE novel_id = :novelId")
    suspend fun deleteForNovel(novelId: String)

    // Wholesale replace, mirroring SyncedFileDao's contract: SyncOutcome documents its
    // `files` as the manifest's complete current set, not a delta, and the same is
    // true of a manifest's announcements list - this table should reflect exactly
    // what the most recent manifest declared, never accumulate every announcement
    // ever seen. @Transaction makes the delete+insert pair atomic, so a reader querying
    // mid-sync never observes a transient "zero announcements" gap.
    @Transaction
    suspend fun replaceForNovel(novelId: String, announcements: List<NovelAnnouncementEntity>) {
        deleteForNovel(novelId)
        upsertAll(announcements)
    }
}
