package com.arkarium.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

enum class NovelStatus {
    NOT_STARTED, IN_PROGRESS, COMPLETED
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
    @ColumnInfo(name = "metadata_fetched_at") val metadataFetchedAt: Long? = null  // null = never fetched
)

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

@Entity(tableName = "arcs",
    foreignKeys = [
        ForeignKey(entity = NovelEntity::class, parentColumns = ["id"], childColumns = ["novel_id"], onDelete = ForeignKey.CASCADE)
    ])
data class ArcEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "novel_id") val novelId: String,
    @ColumnInfo val name: String,
    @ColumnInfo(name = "cover_uri") val coverUri: String?,
    @ColumnInfo val position: Int  // order within novel
)

@Entity(tableName = "chapters",
    foreignKeys = [
        ForeignKey(entity = NovelEntity::class, parentColumns = ["id"], childColumns = ["novel_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ArcEntity::class, parentColumns = ["id"], childColumns = ["arc_id"], onDelete = ForeignKey.SET_NULL)
    ])
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
    @ColumnInfo(name = "source_path") val sourcePath: String
)

@Entity(tableName = "chapter_overrides",
    foreignKeys = [
        ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapter_id"], onDelete = ForeignKey.CASCADE)
    ])
data class ChapterOverrideEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    @ColumnInfo(name = "title_override") val titleOverride: String?,
    @ColumnInfo(name = "position_override") val positionOverride: Int?,
    @ColumnInfo(name = "is_arc_start") val isArcStart: Boolean = false
)

// positionType interpretation for ReadingProgressEntity.position:
// CHAR_OFFSET, HTML_LOCATION, EPUB_CFI, PAGE_INDEX, PERCENTAGE
@Entity(tableName = "reading_progress",
    foreignKeys = [
        ForeignKey(entity = NovelEntity::class, parentColumns = ["id"], childColumns = ["novel_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapter_id"], onDelete = ForeignKey.CASCADE)
    ])
data class ReadingProgressEntity(
    @PrimaryKey @ColumnInfo(name = "novel_id") val novelId: String, // one active progress row per novel
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    @ColumnInfo val position: Float,
    @ColumnInfo(name = "position_type") val positionType: String = "PERCENTAGE",
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(tableName = "scan_fingerprints",
    foreignKeys = [
        ForeignKey(entity = NovelEntity::class, parentColumns = ["id"], childColumns = ["novel_id"], onDelete = ForeignKey.CASCADE)
    ])
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
