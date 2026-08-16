package com.arkarium.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NovelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
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
    // docs/SYNC_MVP.md §4), and again after every subsequent successful re-sync. A
    // plain UPDATE, same rationale as updateMetadata above - this patches an
    // already-existing row rather than upserting a partial entity.
    @Query("""
        UPDATE novels SET
            sync_source_url = :sourceUrl,
            sync_source_version = :sourceVersion,
            last_synced_at = :syncedAt
        WHERE id = :novelId
    """)
    suspend fun updateSyncState(novelId: String, sourceUrl: String, sourceVersion: Int, syncedAt: Long)

    // See docs/NEXT_FIXES.md #2 / Entities.kt SyncStatus. Set to MISSING_LOCALLY when a
    // synced novel's on-disk folder is discovered gone by a scan, or to SOURCE_GONE when
    // its relay 404s on manifest.json - either way this deliberately never deletes the
    // row itself (see startScan's stale-removal in MainActivity.kt).
    @Query("UPDATE novels SET sync_status = :status WHERE id = :novelId")
    suspend fun updateSyncStatus(novelId: String, status: String)

    // "Unlink" resolution for a SOURCE_GONE novel (see docs/NEXT_FIXES.md #2): drops the
    // sync relationship entirely and reverts this novel to a plain local one. Local
    // content is untouched - only the sync bookkeeping columns are cleared. Caller is
    // still responsible for clearing this novel's SyncedFileEntity rows if desired;
    // leaving them behind is harmless (they just become inert once sync_source_url is
    // null, since nothing reads them for a non-synced novel).
    @Query("""
        UPDATE novels SET
            sync_source_url = NULL,
            sync_source_version = NULL,
            sync_status = 'ACTIVE'
        WHERE id = :novelId
    """)
    suspend fun unlinkSyncSource(novelId: String)
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
    @Insert(onConflict = OnConflictStrategy.REPLACE)
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
    @Insert(onConflict = OnConflictStrategy.REPLACE)
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
