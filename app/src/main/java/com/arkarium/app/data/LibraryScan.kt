package com.arkarium.app.data

import android.content.Context
import androidx.documentfile.provider.DocumentFile

// Extracted from MainActivity.kt as Stage 2.1 of Phase 2 (see
// docs/foundational/design-notes.md). Both functions are plain data-in/data-out logic -
// resolveLibraryRoot takes `context` as an explicit parameter instead of relying on
// an Activity's implicit `this`, and mergeNovelForRescan never touches anything
// beyond its own arguments - so both are usable as free functions from anywhere
// (a ViewModel in a later Phase 2 stage, not just MainActivity), same as
// ui/theme/AppTheme.kt's resolveTheme from Phase 1.

// Resolves the one fixed DocumentFile root the scanner always reads from:
// Android/media/com.arkarium.app/, via getExternalMediaDirs()[0]. No longer
// user-choosable - see "Settled decisions" in
// docs/foundational/design-notes.md:
// a SAF-backed custom folder used to be available as an opt-in, but that path is
// gone, along with the folder-identity and SAF-performance problems it caused.
// Visible to file managers and PCs via USB the same way Android/data used to be
// before Android 13 closed that off, and any app can read/write it, so dragging a
// novel folder in from outside ARKarium needs no in-app picker step.
// getExternalMediaDirs() carries a deprecated annotation in the SDK but has no
// removal timeline and remains in production use (WhatsApp, Telegram, etc.) - see
// the proposal doc for the full tradeoff. Its first element can be null if shared
// storage genuinely isn't available on this device/state; callers already treat a
// null result as "leave the library empty," same as the old SAF-unconfigured case.
fun resolveLibraryRoot(context: Context): DocumentFile? {
    val dir = context.getExternalMediaDirs().firstOrNull() ?: return null
    dir.mkdirs()
    return DocumentFile.fromFile(dir)
}

// Merges a freshly-scanned NovelEntity with whatever row already exists for this
// novel id, carrying over fields the scan itself doesn't own (pageSize,
// readingStatus, remote-metadata fields once a "Fetch info" lookup has run) - see the
// long comment this was pulled out of below for the full field-by-field rationale.
// Shared by MainActivity's startScan (onDiscovered) and scanSingleSyncedNovel (see
// docs/foundational/architecture.md, Sync invariants S8) so both a full-library rescan and a scoped
// single-novel sync scan apply the exact same merge rules.
fun mergeNovelForRescan(
    scanned: NovelEntity,
    existing: NovelEntity?,
    // True when this scan pass actually found an authors/ folder at the library
    // root - see ScannerImpl.scanRoot's onAuthorsDiscovered doc comment. Defaults to
    // true so a caller that doesn't pass it (there are none left after this change,
    // but keeps the signature source-compatible) gets the original
    // "scanned.authorId always wins" behavior.
    authorsFolderFound: Boolean = true
): NovelEntity {
    if (existing == null) return scanned
    val remoteFetched = existing.metadataFetchedAt != null
    return scanned.copy(
        pageSize = existing.pageSize,
        readingStatus = existing.readingStatus,
        author = scanned.author ?: existing.author,
        // authorId is normally NOT carried over from `existing` (see the doc comment
        // below) - a scan that genuinely searched authors/ and found no match for
        // this novel's authorId means the link is really gone. But when
        // authorsFolderFound is false, this scan pass never actually got to look
        // (transient SAF issue, or - for a synced novel - the authors/ files simply
        // haven't been pulled down yet this run) - `scanned.authorId` is null for an
        // unrelated reason in that case, not because the link stopped resolving, so
        // falling back to whatever was already linked avoids silently dropping a
        // previously-working author card/nav button until the next scan that
        // actually reaches authors/.
        authorId = if (authorsFolderFound) scanned.authorId else (scanned.authorId ?: existing.authorId),
        description = if (remoteFetched) existing.description else (scanned.description ?: existing.description),
        genres = if (remoteFetched) existing.genres else (scanned.genres ?: existing.genres),
        remoteCoverUrl = existing.remoteCoverUrl,
        publishedDate = if (remoteFetched) existing.publishedDate else (scanned.publishedDate ?: existing.publishedDate),
        // publicationStatus can come from either a local metadata.json (this scan,
        // `scanned`) or a prior sync pass's manifest (SyncViewModel's
        // NovelDao.updatePublicationStatus call, already sitting in `existing` since
        // that write happens outside this merge entirely). Not gated behind
        // `remoteFetched` the way description/genres/publishedDate are - those three
        // are specifically about NovelMetadataProvider's "Fetch info" result winning
        // over a local file once the user has explicitly run it, which has no
        // equivalent concept here. Simple fill-in-if-present instead: a local
        // metadata.json edit always wins when it declares a status (an author/reader
        // hand-editing the file is a deliberate override), and a plain rescan that
        // finds no metadata.json (or one with no "status" key) never clobbers whatever
        // the last sync already determined.
        publicationStatus = scanned.publicationStatus ?: existing.publicationStatus,
        externalSourceUrl = existing.externalSourceUrl,
        metadataFetchedAt = existing.metadataFetchedAt,
        // Sync bookkeeping columns are never *overwritten* by a scan - always carry
        // the existing row's values forward so a rescan/resync can't accidentally wipe
        // or change them. syncSourceUrl is the one exception, and only in the
        // fill-in direction: ScannerImpl.buildNovelEntity can now set it when a
        // folder's title is a FictionLut match (a Rae ARK fiction that reached this
        // folder by some route other than this app's own sync, so it never got a
        // chance to earn this column the normal way - see that function's comment).
        // `existing.syncSourceUrl` still wins whenever it's already set, so this can
        // never clobber a different source or silently re-link a novel the user
        // deliberately unlinked; it only ever turns a *null* into a value a plain
        // rescan newly recognized.
        syncSourceUrl = existing.syncSourceUrl ?: scanned.syncSourceUrl,
        syncSourceVersion = existing.syncSourceVersion,
        lastSyncedAt = existing.lastSyncedAt,
        syncStatus = existing.syncStatus,
        // Settings-wipe bug: neither of these was listed here before, so the
        // result silently kept `scanned`'s value for them - and ScannerImpl.
        // buildNovelEntity never sets either one, so `scanned` always has them at
        // their NovelEntity default (notifyNewChapters = false, syncFolderName =
        // null). NovelDao.upsert writes every column of the merged row, and
        // startScan runs this merge unconditionally on every cold launch, so the
        // very next scan after a user flips "Notify me when new chapters are
        // available" on, or after a sync's first successful downloadInitial
        // backfills a folder name, was quietly writing it straight back to
        // false/null - the toggle appeared to un-set itself, and a synced novel's
        // folder lookup (SyncManager.sync, NewChapterCheckWorker - see
        // syncFolderName's doc comment above) fell back to a slug guess that can
        // miss a folder whose on-disk name doesn't match the slug.
        notifyNewChapters = existing.notifyNewChapters,
        syncFolderName = existing.syncFolderName ?: scanned.syncFolderName
    )
}
