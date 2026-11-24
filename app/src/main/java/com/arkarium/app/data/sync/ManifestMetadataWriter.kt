package com.arkarium.app.data.sync

import com.arkarium.app.data.AppDatabase
import com.arkarium.app.data.ChapterManifestMetaEntity
import com.arkarium.app.data.NovelAnnouncementEntity
import java.util.UUID

// Persists SyncOutcome's manifest-metadata fields (status, cadence, announcements and
// per-chapter word count / read time). Shared by every sync path - SyncViewModel's three
// call sites and NewChapterCheckWorker - so none of them can drift into skipping a
// field, which is exactly how the background check used to leave the chapter list
// showing "---".
//
// Call it AFTER the rescan that (re)creates this novel's chapter rows:
// findByNovelAndRelativePath needs those rows to exist with a current relative_path. A
// chapter that was only just downloaded has no row until that rescan, so applying
// earlier silently skips it.
//
// status and chaptersPerWeek are only ever written when the manifest declares a value.
// A null means "this manifest doesn't say", not "clear it": the author's own source for
// both is metadata.json (read by ScannerImpl), and clearing here made a status that
// came from metadata.json vanish on every sync until the next cold-start scan. The
// manifest value stays as a fallback for manifests that still carry one. If both
// sources declare a value and disagree, the one written last wins, so declare each in
// one place only.
//
// announcements: null (field absent from this manifest) leaves novel_announcements
// untouched; an explicit empty list wholesale-replaces down to zero rows, which is the
// publisher actually clearing them.
suspend fun applyManifestMetadata(db: AppDatabase, novelId: String, outcome: SyncOutcome) {
    outcome.publicationStatus?.let { db.novelDao().updatePublicationStatus(novelId, it) }
    outcome.chaptersPerWeek?.let { db.novelDao().updateChaptersPerWeek(novelId, it) }

    outcome.announcements?.let { announcements ->
        val fetchedAt = System.currentTimeMillis()
        db.novelAnnouncementDao().replaceForNovel(
            novelId,
            announcements.map { announcement ->
                NovelAnnouncementEntity(
                    // Deterministic so re-syncing an unchanged manifest yields the same ids.
                    id = UUID.nameUUIDFromBytes(
                        "$novelId:${announcement.text}:${announcement.publishedAt}".toByteArray()
                    ).toString(),
                    novelId = novelId,
                    text = announcement.text,
                    publishedAt = announcement.publishedAt,
                    fetchedAt = fetchedAt
                )
            }
        )
    }

    outcome.chapterManifestMeta.forEach { (relativePath, meta) ->
        val (wordCount, readTimeMinutes) = meta
        // A manifest entry with no matching chapter row (cover, metadata.json, wrong
        // extension) is not an error - there is just nothing to attach counts to.
        val chapter = db.chapterDao().findByNovelAndRelativePath(novelId, relativePath) ?: return@forEach
        db.chapterManifestMetaDao().upsert(
            ChapterManifestMetaEntity(
                chapterId = chapter.id,
                wordCount = wordCount,
                readTimeMinutes = readTimeMinutes
            )
        )
    }
}
