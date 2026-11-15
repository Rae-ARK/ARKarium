package com.arkarium.app.data.sync

import com.arkarium.app.data.SyncedFileEntity

// See docs/foundational/architecture.md, Sync for the full design. This file holds the data
// models a sync pass fetches, parses or produces - manifest.json's own shape
// (ManifestFileEntry/ManifestAnnouncement/Manifest) and the result SyncManager hands
// back to its caller (SyncOutcome). No network or file IO lives here - see
// SyncClient.kt and SyncManager.kt for that.

// One entry from a manifest.json's "files" map - see docs/foundational/architecture.md, Sync: manifest. relativePath
// here has already been through SyncPaths.sanitize by the time a Manifest exists; a
// Manifest is never constructed with an unsafe path in it.
//
// wordCount/readTimeMinutes (ideas.md "Serial-fiction manifest metadata",
// peer-app-survey.md Suggestion 1) are both optional, backward-compatible additions -
// a manifest.json predating this field, or a file entry that simply omits it, parses
// exactly as before with both left null (see parseManifest's optIntOrNull below).
// Meaningful only for a chapter-file entry; a publisher putting them on cover.*/
// metadata.json/etc. is harmless but ignored - nothing ever reads them off anything
// but a path SyncViewModel resolves to a ChapterEntity.
data class ManifestFileEntry(
    val relativePath: String,
    val size: Long,
    val sha256: String,
    val wordCount: Int? = null,
    val readTimeMinutes: Int? = null
)

// One manifest-level announcement entry - see NovelAnnouncementEntity's doc comment
// for why this is deliberately not the in-chapter `<news>` callout. `text` is the only
// required field; a bare JSON string in the manifest's "announcements" array (instead
// of an {"text":...} object) is accepted too, with publishedAt left null - see
// parseManifest, same string-or-object forgiveness ScannerImpl.readLocalMetadata
// already extends to "genres".
data class ManifestAnnouncement(
    val text: String,
    val publishedAt: String? = null
)

// `status`/`announcements` are both optional, root-level additions to manifest.json
// (ideas.md "Serial-fiction manifest metadata") - absent entirely in a manifest
// predating this field, same backward-compatibility contract as ManifestFileEntry's
// new fields above. `status` is already normalized/validated (PublicationStatus.fromRaw)
// by the time a Manifest exists - an unrecognized value is null here, not the raw
// publisher string, so every caller downstream can treat it as a closed enum name.
// chaptersPerWeek is the author's own declared release cadence (set at publish time,
// e.g. in whatever tool writes manifest.json) - not a telemetry-derived figure and
// never computed on-device from actual publish timestamps, since nothing in the
// manifest format carries real per-chapter publish times (see the "Chapters/Week"
// row in docs/implementation/catalogue-browse-redesign.md - a true computed cadence would need
// that, this is deliberately simpler). A plain author-supplied number, same
// "trust it, show '---' if absent" contract as `status`. Double rather than Int:
// an author publishing slower than one chapter/week (e.g. one every two weeks)
// still needs to say so as a cadence, not round down to 0.
data class Manifest(
    val version: Int,
    val files: List<ManifestFileEntry>,
    val status: String? = null,
    val announcements: List<ManifestAnnouncement>? = null,
    val chaptersPerWeek: Double? = null
)

// Result of one sync pass against a relay - callers persist this into
// NovelDao.updateSyncState + SyncedFileDao themselves; SyncManager never writes to
// Room. `files` is the *complete* new set of synced files (unchanged entries carried
// over, not just deltas), so a caller can replace its SyncedFileEntity rows wholesale
// rather than patch them.
data class SyncOutcome(
    val newVersion: Int,
    val files: List<SyncedFileEntity>,
    val changed: Boolean,  // false when the manifest version already matched - nothing was downloaded or deleted
    // The on-disk folder name this sync pass actually resolved/wrote into - null only
    // for the early "already up to date" return, which never needs it (see
    // NovelDao.updateSyncState, only ever called when `changed` is true). Callers
    // persist this into NovelEntity.syncFolderName so the *next* sync doesn't have to
    // re-derive the folder by name/id-hash guessing - see SyncManager.sync()'s
    // folder-relocation comment.
    val folderName: String? = null,
    // Everything below is straight off the manifest this pass just fetched (Manifest.
    // status/announcements/ManifestFileEntry.wordCount+readTimeMinutes) - see
    // "Serial-fiction manifest metadata" in ideas.md. Populated even on the early
    // "already up to date" return (unlike `files`/`folderName`, which have nothing new
    // to report there): the manifest was still fetched and parsed on that path, so
    // there's no reason to make the caller re-fetch it just to read these. SyncManager
    // still never writes to Room itself (see the class doc comment) - the caller
    // (SyncViewModel) is what turns these into NovelDao.updatePublicationStatus,
    // NovelAnnouncementDao.replaceForNovel and ChapterDao.findByNovelAndRelativePath +
    // ChapterManifestMetaDao.upsert calls, same division of labor as files/folderName.
    val publicationStatus: String? = null,
    val announcements: List<ManifestAnnouncement>? = null,
    // Manifest.chaptersPerWeek, carried straight through - see that field's doc
    // comment. Same "populated even on the unchanged-version branch" reasoning as
    // publicationStatus/announcements above: an author can update their declared
    // cadence without bumping `version`.
    val chaptersPerWeek: Double? = null,
    // relativePath -> (wordCount, readTimeMinutes), chapter-file entries only (a
    // cover.*/metadata.json entry simply never has either field set, so it's harmless
    // but pointless to include - callers only ever look this up by a ChapterEntity's
    // own relativePath anyway).
    val chapterManifestMeta: Map<String, Pair<Int?, Int?>> = emptyMap()
)
