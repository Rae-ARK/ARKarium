package com.arkarium.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class NovelStatus {
    NOT_STARTED, IN_PROGRESS, COMPLETED
}

// Author-declared publication status (docs/proposal/ideas.md "Serial-fiction manifest
// metadata", docs/proposal/peer-app-survey.md Suggestion 1) - distinct from
// NovelStatus/NovelEntity.readingStatus above, which tracks *this reader's* progress,
// not whether the author is still writing it. Deliberately a small closed set rather
// than a free-text field: readers want a stable glyph/label (and, eventually, a
// hiatus-ended notification - see Suggestion 1 - keyed on a value actually changing),
// neither of which works against arbitrary publisher text.
enum class PublicationStatus {
    ONGOING, HIATUS, DROPPED, COMPLETE;

    companion object {
        // Case/whitespace-forgiving parse used by both ScannerImpl.readLocalMetadata
        // (metadata.json) and SyncManager.parseManifest (manifest.json) - same
        // "malformed/unrecognized input just means no value" philosophy every other
        // optional field in those two parsers already follows (see
        // ScannerImpl.readLocalMetadata's doc comment): an unrecognized status string
        // is never a parse failure for the whole file, it just leaves this field null,
        // same as if it had been absent.
        fun fromRaw(raw: String?): String? =
            raw?.trim()?.uppercase()?.takeIf { normalized ->
                entries.any { it.name == normalized }
            }
    }
}

@Entity(tableName = "novels")
data class NovelEntity(
    @PrimaryKey val id: String,
    @ColumnInfo val title: String,
    @ColumnInfo val author: String?,
    // Resolved by ScannerImpl against the root-level authors/ folder (see
    // ScannerImpl.scanAuthorsFolder and AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md) - either
    // this fiction's metadata.json "authorId" matched against an authors/<id>.json, or
    // a case-insensitive fallback match of `author` against an author's name. Null
    // when neither resolves, same as `author` itself being null. Deliberately NOT a
    // Room @ForeignKey: this column is added to an already-existing table via ALTER
    // TABLE in MIGRATION_6_7, and SQLite can't attach an enforced FK constraint that
    // way, so it stays a plain soft reference resolved in application code instead.
    @ColumnInfo(name = "author_id") val authorId: String? = null,
    @ColumnInfo(name = "cover_uri") val coverUri: String?,
    @ColumnInfo(name = "page_size") val pageSize: Int = 10,  // per-fiction pagination default
    @ColumnInfo(name = "reading_status") val readingStatus: String = NovelStatus.NOT_STARTED.name,  // NOT_STARTED, IN_PROGRESS, COMPLETED
    // Everything below is optional, user-triggered metadata from an external source
    // (see NovelMetadataProvider) - null until the user explicitly runs "Fetch info"
    // for this novel. Never populated automatically by the scanner.
    @ColumnInfo val description: String? = null,
    @ColumnInfo val genres: String? = null,  // comma-separated; simple enough not to need a TypeConverter
    @ColumnInfo(name = "remote_cover_url") val remoteCoverUrl: String? = null,  // fallback cover when no local cover.jpg was found
    @ColumnInfo(name = "published_date") val publishedDate: String? = null,
    @ColumnInfo(name = "external_source_url") val externalSourceUrl: String? = null,
    @ColumnInfo(name = "metadata_fetched_at") val metadataFetchedAt: Long? = null,  // null = never fetched
    // Sync relay tracking (see docs/foundational/architecture.md, Sync) - deliberately separate from
    // externalSourceUrl/metadataFetchedAt above, which are about optional
    // display-metadata lookups (NovelMetadataProvider), not this novel's content
    // relay. `syncSourceUrl` null means "purely local, not synced from anywhere";
    // non-null is the manifest.json base URL this novel's files sync against - either
    // because they were actually downloaded from there, or because ScannerImpl
    // recognized this folder's title as one of Rae ARK's own (a FictionLut match - see
    // ScannerImpl.buildNovelEntity) even though this particular copy reached the
    // library some other way. Either way, `syncSourceVersion` null means no sync pass
    // has actually run against it yet; once set, it's the last-applied manifest's
    // top-level "version", used to skip a full re-diff when a sync check finds nothing
    // changed.
    @ColumnInfo(name = "sync_source_url") val syncSourceUrl: String? = null,
    @ColumnInfo(name = "sync_source_version") val syncSourceVersion: Int? = null,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long? = null,
    // The exact on-disk folder name this synced novel's content actually lives in
    // (e.g. "Summoned By Mistake"), persisted once at first successful sync and
    // reused as the sole lookup key for every later one. This exists specifically so
    // the folder can be named after the fiction itself (readable when browsing the
    // library folder) without that name also having to double as sync's own identity
    // key - see SyncManager.sync's folder-relocation comment and
    // docs/foundational/architecture.md, Sync invariants S9. Null for a purely local novel (syncSourceUrl ==
    // null, this is simply never set) and, transiently, for a novel synced by a
    // pre-MIGRATION_10_11 build until its next successful sync backfills it.
    @ColumnInfo(name = "sync_folder_name") val syncFolderName: String? = null,
    // Only meaningful when syncSourceUrl != null (see docs/foundational/architecture.md, Sync invariants S6). ACTIVE =
    // normal state. MISSING_LOCALLY = this novel's on-disk folder disappeared out from
    // under a synced novel (user deleted it, SAF re-permission minted a new tree, etc)
    // - the row is deliberately kept rather than cascade-deleted so the user can choose
    // to re-sync or remove it, instead of the scanner silently doing either for them.
    // SOURCE_GONE = the relay 404s on this novel's manifest.json - local content stays
    // readable (offline-first), but there's nothing left to sync against. Never set for
    // a purely-local (syncSourceUrl == null) novel.
    @ColumnInfo(name = "sync_status") val syncStatus: String = SyncStatus.ACTIVE.name,
    // "Notify me when new chapters are available" toggle on NovelDetailScreen (see
    // docs/foundational/architecture.md, Sync: entry points). Only ever meaningful for a synced novel
    // (syncSourceUrl != null) - the toggle is hidden on the fiction page for a purely
    // local one, same gating as onSyncClick - but the column itself carries no
    // constraint against that, so a novel that's later unlinked from its sync source
    // just has an inert flag rather than needing a migration or cleanup step. Read by
    // NewChapterCheckWorker's periodic background pass (NovelDao.notifyEnabledSynced)
    // to decide which novels are even worth checking; never written to by ScannerImpl/
    // mergeNovelForRescan, so a rescan can never silently flip it back off.
    @ColumnInfo(name = "notify_new_chapters") val notifyNewChapters: Boolean = false,
    // PublicationStatus name (ONGOING/HIATUS/DROPPED/COMPLETE) or null = unknown/not
    // declared - see PublicationStatus's doc comment. Sourced from either
    // metadata.json (ScannerImpl.readLocalMetadata, scan time) or the sync manifest's
    // own "status" field (SyncManager.parseManifest, sync time); LibraryScan.kt's
    // mergeNovelForRescan decides which one wins on a rescan, the same way it already
    // arbitrates description/genres/publishedDate against a "Fetch info" result. Null
    // renders as "---" rather than hiding the row - see NovelDetailScreen - so a
    // reader can tell "not declared" apart from a slow load.
    @ColumnInfo(name = "publication_status") val publicationStatus: String? = null,
    // Author-declared release cadence (chapters/week) - see SyncManager.Manifest's
    // chaptersPerWeek doc comment: set at publish time by the author, never computed
    // from real publish timestamps (the manifest format has none). Null renders as
    // "---", same convention as publicationStatus and everything else in this
    // "Serial-fiction manifest metadata" group - see peer-app-survey.md Suggestion 1.
    // Sourced only from a sync manifest today (SyncViewModel's
    // NovelDao.updateChaptersPerWeek call) - unlike publicationStatus, ScannerImpl's
    // local metadata.json parsing doesn't also expose this, since a purely local
    // novel has no publish-time tooling to have declared a cadence in the first
    // place; nothing stops that from being added later the same way.
    @ColumnInfo(name = "chapters_per_week") val chaptersPerWeek: Double? = null
)

enum class SyncStatus {
    ACTIVE, MISSING_LOCALLY, SOURCE_GONE
}

// One row per authors/<id>.json file at the library root (see
// ScannerImpl.scanAuthorsFolder). `id` is the filename by default, or the json's own
// "id" override - never hand-validated for uniqueness, since a filename is already
// unique within the authors/ folder by construction; an override collision is instead
// resolved by "first file seen this scan wins" (see scanAuthorsFolder). Refreshed
// wholesale on every full library rescan the same way `arcs` are diffed against their
// novel, rather than being tied to any single novel itself - one author can be linked
// from many NovelEntity rows via NovelEntity.authorId.
@Entity(tableName = "authors")
data class AuthorEntity(
    @PrimaryKey val id: String,
    @ColumnInfo val name: String,
    @ColumnInfo(name = "avatar_uri") val avatarUri: String? = null,
    @ColumnInfo val bio: String? = null,
    @ColumnInfo val joined: String? = null,
    @ColumnInfo val location: String? = null,
    @ColumnInfo val gender: String? = null,
    // Raw JSON object string (e.g. {"twitter":"...","website":"..."}) - simple enough
    // not to need a TypeConverter/child table, same rationale as NovelEntity.genres
    // being a flat comma-separated string. Parsed back to a Map in the UI layer.
    @ColumnInfo(name = "links_json") val linksJson: String? = null,
    // Manually-authored, display-only numbers (see schema notes in the redesign doc) -
    // not a real follower/review system, purely for visual parity with Royal Road's
    // profile stat strip.
    @ColumnInfo val followers: Int? = null,
    @ColumnInfo val favorites: Int? = null,
    @ColumnInfo(name = "reviews_received") val reviewsReceived: Int? = null,
    @ColumnInfo(name = "ratings_received") val ratingsReceived: Int? = null,
    // authors/<file>.json URI this row was parsed from, kept for parity with
    // ScanFingerprintEntity.folderUri-style provenance and possible future debugging;
    // not currently read back by anything.
    @ColumnInfo(name = "source_path") val sourcePath: String
)

// indices (performance.md F24): novel_id has no index of its own otherwise - `arcs`'
// primary key is `id`, so the per-novel arc query and the novel-delete cascade were
// both full table scans without this.
@Entity(tableName = "arcs",
    foreignKeys = [
        ForeignKey(entity = NovelEntity::class, parentColumns = ["id"], childColumns = ["novel_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("novel_id")])
data class ArcEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "novel_id") val novelId: String,
    @ColumnInfo val name: String,
    @ColumnInfo(name = "cover_uri") val coverUri: String?,
    @ColumnInfo val position: Int  // order within novel
)

// indices (performance.md F24): both foreign-key columns, for the same reason as
// ArcEntity above - `chapters`' primary key is `id`, so neither the per-novel query,
// the chapters-in-arc query, nor either cascade (novel delete = CASCADE, arc delete =
// SET_NULL) had an index to use.
@Entity(tableName = "chapters",
    foreignKeys = [
        ForeignKey(entity = NovelEntity::class, parentColumns = ["id"], childColumns = ["novel_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ArcEntity::class, parentColumns = ["id"], childColumns = ["arc_id"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("novel_id"), Index("arc_id")])
data class ChapterEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "novel_id") val novelId: String,
    @ColumnInfo(name = "arc_id") val arcId: String?,
    @ColumnInfo val number: Int?,
    @ColumnInfo val title: String,
    // 0 = regular chapter, 1 = bonus/side content ("~" filename prefix - interlude,
    // extra chapter, side story, omake, ...), 2 = closing/meta content ("!" filename
    // prefix - afterword, author's note, ...). Regular chapters still sort by `number`
    // within tier 0 exactly as before; see ScannerImpl.parseChapter and bugs.md Bug 2.
    @ColumnInfo(name = "sort_tier") val sortTier: Int = 0,
    @ColumnInfo(name = "source_path") val sourcePath: String,
    // Manifest-relative path ("NNN.txt" at novel root, "arcs/<Arc>/NNN.txt" under an
    // arc - see architecture.md's Content format and ScannerImpl.parseChaptersInFolder)
    // rather than the SAF content:// URI `sourcePath` above holds. This is what lets a
    // sync pass's ManifestFileEntry (keyed by that same relative path, per
    // architecture.md's Manifest section) be matched back to the ChapterEntity row it
    // describes, to carry wordCount/readTimeMinutes below. Recomputed on every scan
    // like title/number/sortTier - safe to do, since (unlike wordCount/
    // readTimeMinutes) it's entirely scan-derived with nothing that needs to survive a
    // rescan.
    @ColumnInfo(name = "relative_path") val relativePath: String? = null
)

// Per-chapter metadata sourced only from the sync manifest (ManifestFileEntry's
// wordCount/readTimeMinutes - "Word count and estimated read time per chapter" in
// peer-app-survey.md Suggestion 1; cheap to compute at publish time, never computed
// on-device). A dedicated table, not columns on ChapterEntity itself, for exactly the
// reason chapter_overrides is one and not columns on ChapterEntity either: see that
// entity's own doc comment above - ScannerImpl.parseChaptersInFolder rebuilds every
// ChapterEntity column from the local file on every rescan (REPLACE upsert), so a
// value that needs to survive a rescan it didn't come from can't live on that row.
// SyncViewModel writes here separately, after a scan has already (re)created the
// chapter row, via ChapterDao.updateManifestMeta keyed on (novel_id, relative_path);
// ScannerImpl never touches this table.
// chapter_id is the primary key directly (not a synthetic id + indexed FK column, the
// shape chapter_overrides uses) since this is a true 1:1 relationship - there's never
// a list of these per chapter, so an indexed lookup column would be redundant with the
// primary key itself.
@Entity(tableName = "chapter_manifest_meta",
    foreignKeys = [
        ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapter_id"], onDelete = ForeignKey.CASCADE)
    ])
data class ChapterManifestMetaEntity(
    @PrimaryKey @ColumnInfo(name = "chapter_id") val chapterId: String,
    // Null for either field individually is a valid, expected state (a manifest can
    // supply one without the other) - renders as "---" per-field, not as the whole row
    // being absent. See NovelDetailScreen's chapter row.
    @ColumnInfo(name = "word_count") val wordCount: Int? = null,
    @ColumnInfo(name = "read_time_minutes") val readTimeMinutes: Int? = null
)

// index (performance.md F24): chapter_id has no index otherwise - this table's
// primary key is its own `id`, so the override lookup and the chapter-delete cascade
// were both full scans.
@Entity(tableName = "chapter_overrides",
    foreignKeys = [
        ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapter_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("chapter_id")])
data class ChapterOverrideEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    @ColumnInfo(name = "title_override") val titleOverride: String?,
    @ColumnInfo(name = "position_override") val positionOverride: Int?,
    @ColumnInfo(name = "is_arc_start") val isArcStart: Boolean = false
)

// positionType interpretation for ReadingProgressEntity.position:
// CHAR_OFFSET, HTML_LOCATION, EPUB_CFI, PAGE_INDEX, PERCENTAGE
// index (performance.md F24): only chapter_id needs one - novel_id is this table's own
// primary key, already indexed by construction, but chapter_id's own cascade (chapter
// delete) was a full scan without this.
@Entity(tableName = "reading_progress",
    foreignKeys = [
        ForeignKey(entity = NovelEntity::class, parentColumns = ["id"], childColumns = ["novel_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapter_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("chapter_id")])
data class ReadingProgressEntity(
    @PrimaryKey @ColumnInfo(name = "novel_id") val novelId: String, // one active progress row per novel
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    @ColumnInfo val position: Float,
    @ColumnInfo(name = "position_type") val positionType: String = "PERCENTAGE",
    // Populated only for positionType == CHAR_OFFSET (rung 14, see ReaderPosition.kt) -
    // a short run of the chapter's own text starting at `position`, the proposal doc's
    // fallback for the offset no longer lining up. Null for every PERCENTAGE row,
    // including every row written before this column existed (MIGRATION_13_14 adds it
    // with no default data to backfill - there's nothing to compute a snippet from for
    // an offset that was never a character offset in the first place).
    @ColumnInfo(name = "position_snippet") val positionSnippet: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

// One row per (chapter, calendar day) a chapter was read to completion (see
// ReadingStats.isCompletionThreshold and MainActivity.saveReadingProgress, the only
// writer). Backs the Home screen's "reading stats" card - streak length and "chapters
// read this week" - added specifically because it's a cheap way to encourage habitual
// return visits to serialized content (readers coming back daily to keep a streak
// alive), without needing any new user-facing setting or opt-in.
//
// primaryKeys is (chapter_id, read_date) rather than a synthetic id: re-reading (or
// re-finishing, via Previous/Next) the same chapter repeatedly in one sitting should
// log one event for that day, not one per navigation action - onConflict=REPLACE on
// the same key just refreshes read_at instead of inserting a duplicate row. Reading
// the *same* chapter again on a *different* day is a new key (different read_date)
// and does get its own row - which is exactly what the streak calculation needs to
// see that day as active.
@Entity(
    tableName = "chapter_read_events",
    primaryKeys = ["chapter_id", "read_date"],
    foreignKeys = [
        ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapter_id"], onDelete = ForeignKey.CASCADE)
    ]
)
data class ChapterReadEventEntity(
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    // Denormalized off ChapterEntity.novelId - kept here too (rather than joining
    // through chapters for every stats query) since the only two queries this table
    // ever needs to answer (ReadingStats: "how many rows since date X", "which
    // distinct days have any row") don't care about novel_id at all yet, but a
    // future "per-novel streak" feature would, and it's a free column to add now
    // versus a schema migration + join rewrite later.
    @ColumnInfo(name = "novel_id") val novelId: String,
    // Local calendar day this chapter was (most recently) finished on, "yyyy-MM-dd"
    // (see ReadingStats.dayKey) - the dedup key alongside chapter_id, and also
    // exactly the granularity streak/weekly-count math needs. Plain string, not a
    // TypeConverter'd LocalDate: minSdk 24 predates java.time and this project has
    // no desugaring configured (see AppDatabase.kt), and ISO yyyy-MM-dd strings
    // already sort/compare correctly with plain `>=`/`ORDER BY`, so a real date type
    // buys nothing here.
    @ColumnInfo(name = "read_date") val readDate: String,
    @ColumnInfo(name = "read_at") val readAt: Long
)

// index (performance.md F24): novel_id has no index otherwise - this table's primary
// key is its own `id`, so the per-novel fingerprint lookup and the novel-delete
// cascade were both full scans.
@Entity(tableName = "scan_fingerprints",
    foreignKeys = [
        ForeignKey(entity = NovelEntity::class, parentColumns = ["id"], childColumns = ["novel_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("novel_id")])
data class ScanFingerprintEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "novel_id") val novelId: String,
    @ColumnInfo(name = "folder_uri") val folderUri: String,
    // These are aggregated over every file under the novel folder (see
    // ScannerImpl.computeFingerprint), not the folder's own metadata - many SAF
    // providers don't update a directory's own lastModified/length when a child
    // file inside it changes, which would otherwise make the "skip rescan" check
    // silently miss additions, edits, and removals.
    @ColumnInfo(name = "last_modified") val lastModified: Long?,
    @ColumnInfo(name = "size") val size: Long?,
    @ColumnInfo(name = "file_count") val fileCount: Int = 0,
    @ColumnInfo(name = "scan_version") val scanVersion: Int = 1  // bump to force full rescan
)

// One row per file the sync client has actually downloaded and written for a synced
// novel (see docs/foundational/architecture.md, Sync invariants S1). This is the diff's source
// of truth on both sides - a sync pass downloads manifest paths missing from this
// table, and deletes rows (and their on-disk files) present here but missing from the
// new manifest. Deliberately its own table rather than reusing ScanFingerprintEntity,
// which fingerprints a whole novel folder for rescan-skip purposes and has no
// per-file provenance to diff against.
//
// primaryKeys is (novel_id, relative_path) rather than a synthetic id - there's
// exactly one row per file-per-novel by construction, and this makes "does this path
// already exist for this novel" a direct key lookup instead of a query.
@Entity(
    tableName = "synced_files",
    primaryKeys = ["novel_id", "relative_path"],
    foreignKeys = [
        ForeignKey(entity = NovelEntity::class, parentColumns = ["id"], childColumns = ["novel_id"], onDelete = ForeignKey.CASCADE)
    ]
)
data class SyncedFileEntity(
    @ColumnInfo(name = "novel_id") val novelId: String,
    // Sanitized, manifest-relative path (e.g. "arcs/arc-1/001.txt", "cover.png") -
    // never trusted as-is from a manifest without validation first (see
    // docs/foundational/architecture.md, Sync invariants S2); validation happens in the sync
    // client before a row like this is ever created, not here.
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo val sha256: String,
    @ColumnInfo val size: Long
)

// Manifest-level announcements (ideas.md "Serial-fiction manifest metadata",
// peer-app-survey.md Suggestion 1) - deliberately separate from the in-chapter
// `<news>` callout (ChapterMarkup.kt/ReaderScreen.kt), which lives inside one specific
// chapter file. An announcement here isn't tied to any chapter, so showing or
// dismissing one can never disturb a reader's bookmark/progress state the way editing
// a `<news>` tag into an already-read chapter file would (see that proposal's
// reasoning). Written wholesale per sync pass - see NovelAnnouncementDao.replaceForNovel
// - the same "files is the complete new set, not a delta" shape SyncedFileEntity
// already uses for exactly the same reason: the manifest is the one source of truth
// for "what's currently announced," not an append-only log this table should
// accumulate forever.
@Entity(
    tableName = "novel_announcements",
    foreignKeys = [
        ForeignKey(entity = NovelEntity::class, parentColumns = ["id"], childColumns = ["novel_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("novel_id")]
)
data class NovelAnnouncementEntity(
    // Deterministic from (novelId, text, publishedAt) rather than a random UUID - see
    // NovelAnnouncementDao.replaceForNovel's doc comment on why that matters for a
    // wholesale per-sync replace.
    @PrimaryKey val id: String,
    @ColumnInfo(name = "novel_id") val novelId: String,
    @ColumnInfo val text: String,
    // Publisher-supplied, free-form - see architecture.md's Manifest section: nothing
    // else in the manifest format carries a real timestamp today (catalogue-browse.md
    // Sort's "a true RECENTLY_UPDATED would need the relay to carry some notion of
    // when did this version actually publish" caveat applies here too), so this is
    // whatever string the publisher put in, not a parsed/validated date. Null renders
    // as "---" rather than a guessed date.
    @ColumnInfo(name = "published_at") val publishedAt: String? = null,
    // When *this client* pulled the announcement down, not when the publisher wrote
    // it - used only to order announcements lacking a publishedAt and as a staleness
    // signal, same cachedAt convention docs/implementation/catalogue-browse-redesign.md's
    // CatalogueEntryEntity proposes (C3: "staleness is visible, not silently trusted").
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long
)

// Notice Board's "Pigeon Rally" page (docs/proposal/notice-board.md) - one row per
// *sync that found new chapters*, not one row per chapter: a sync that pulls in 3
// chapters at once is one entry ("3 new chapters"), matching what
// NewChapterNotifier's existing notification text already says. Written by the same
// two call sites that already detect "did this sync find new chapters" -
// NewChapterCheckWorker.checkOneNovel (background) and SyncViewModel.checkForUpdates
// (the manual "Check for updates" action) - using the same newChapterCount/
// latestChapterTitle each already computes, so this is never a second query over the
// chapter diff, just a second thing done with a result already in hand. Gated by the
// per-novel "Notify me" toggle on NovelDetailScreen the same way those two call sites
// already gate the system notification - an event is only ever logged for a novel
// that toggle is on for; this table deliberately has no mute/filter of its own (see
// the proposal doc's "Explicitly out of scope" section).
//
// No FK to novels: unlike ChapterReadEventEntity/ReadingProgressEntity, an entry here
// is meant to survive the novel being removed and re-followed later (a fresh follow
// gets a new scan-assigned NovelEntity.id - see SyncViewModel.addFiction - so a FK
// with ON DELETE CASCADE would silently erase the log on every re-follow). A
// novel_id with nothing to show for it any more (because the novel was removed and
// never re-followed) just renders as an orphaned row the UI can't resolve a
// title/cover for - NoticeBoardViewModel filters those out rather than crashing or
// showing a blank row; see its doc comment.
@Entity(
    tableName = "chapter_update_events",
    indices = [Index("novel_id")]
)
data class ChapterUpdateEventEntity(
    @PrimaryKey val id: String,  // UUID, same pattern as every other event row
    @ColumnInfo(name = "novel_id") val novelId: String,
    // From the same diff NewChapterNotifier.notify already gets - see its own
    // call sites' doc comments on why a chapter that already existed never counts
    // as "new" just because a rescan touched its row again.
    @ColumnInfo(name = "new_chapter_count") val newChapterCount: Int,
    @ColumnInfo(name = "latest_chapter_title") val latestChapterTitle: String?,
    @ColumnInfo(name = "detected_at") val detectedAt: Long,
    // Unread/read state for the Notice Board badge - see NoticeBoardViewModel.
    // Opening Pigeon Rally itself doesn't flip this (that would make the badge
    // useless the moment you glance at the list without reading anything); only
    // tapping a row, or "Mark all read", does.
    @ColumnInfo val seen: Boolean = false
)

// Rung 1 of docs/implementation/catalogue-browse-redesign.md - a not-yet-followed
// fiction surfaced on the Browse grid from FictionLut.allEntries, not yet a real
// NovelEntity. One row per slug, upserted by the catalogue ingestion pass (Rung 2) from
// that fiction's own manifest.json/metadata.json/cover, same optional-fields-optional
// schema ScannerImpl.readLocalMetadata already parses locally - see the redesign doc's
// "Catalogue ingestion" section for exactly which fields come from where.
//
// Deliberately its own table, not a variant of NovelEntity: a catalogue entry has no
// synced files, no reading progress, no chapters, and (per C2 below) is never meant to
// coexist with a real NovelEntity for the same fiction - merging the two shapes would
// mean every NovelEntity-only column (sync bookkeeping, reading_status, page_size, ...)
// needing a meaningless default for a fiction nobody has followed yet.
@Entity(tableName = "catalogue_entries")
data class CatalogueEntryEntity(
    // FictionLut's own slug - see SyncPaths.sanitize (C1: untrusted input before it's a
    // folder name) applied before this is ever used to build a cache path, not here.
    @PrimaryKey val slug: String,
    @ColumnInfo val title: String,
    @ColumnInfo val author: String?,
    @ColumnInfo val description: String?,
    @ColumnInfo val genres: String?,  // comma-separated, same convention as NovelEntity.genres
    // file:// path under context.filesDir/catalogue/<slug>/ (see the redesign doc's
    // "Storage" section) - deliberately outside the SAF library root ScannerImpl walks,
    // so a not-yet-followed fiction's cached cover can never be misread as a real,
    // locally-added novel.
    @ColumnInfo(name = "cover_path") val coverPath: String?,
    // Count of manifest entries matching arcs/*/*.txt - a count, not a table of
    // contents; the manifest carries no per-chapter titles for an unfollowed fiction.
    @ColumnInfo(name = "chapter_count") val chapterCount: Int,
    @ColumnInfo(name = "published_date") val publishedDate: String?,
    // Sum of ChapterManifestMetaEntity-equivalent wordCount across the manifest's
    // chapter entries; null (never a fabricated 0) when none of them declare one.
    @ColumnInfo(name = "total_word_count") val totalWordCount: Int? = null,
    // PublicationStatus name straight off the manifest's "status" field - same
    // null-renders-as-"---" convention as NovelEntity.publicationStatus.
    @ColumnInfo val status: String? = null,
    // Author-declared cadence (Manifest.chaptersPerWeek) - "what the author says," same
    // trust level as `status`, never computed from real publish timestamps.
    @ColumnInfo(name = "chapters_per_week") val chaptersPerWeek: Double? = null,
    // When this row was last actually re-fetched (C3: staleness is visible, not
    // silently trusted) - nothing here claims to reflect the relay's current state
    // beyond this point.
    @ColumnInfo(name = "cached_at") val cachedAt: Long
)
