package com.arkarium.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

// Extracted from MainActivity.kt as Stage 2.1 of Phase 2 (see
// docs/arkarium/REFACTOR_PLAN.md). Both functions are plain data-in/data-out logic -
// resolveLibraryRoot takes `context` as an explicit parameter instead of relying on
// an Activity's implicit `this`, and mergeNovelForRescan never touches anything
// beyond its own arguments - so both are usable as free functions from anywhere
// (a ViewModel in a later Phase 2 stage, not just MainActivity), same as
// ui/theme/AppTheme.kt's resolveTheme from Phase 1.

// Resolves whichever DocumentFile root the scanner should currently read from.
// - Custom folder OFF (the default): the app's own private external-storage folder,
//   e.g. Android/data/com.arkarium.app/files - readable/writable with zero permission
//   prompts on every Android version, so a fresh install has a working library the
//   moment novel folders are dropped in there, no SAF picker interaction required.
// - Custom folder ON: the SAF tree the user picked in Settings, or null if they've
//   turned the toggle on but haven't picked a folder yet (caller should leave the
//   library empty and let EmptyLibraryPrompt/Settings prompt them to pick one).
fun resolveLibraryRoot(context: Context, useCustomFolder: Boolean, savedUri: String?): DocumentFile? {
    if (useCustomFolder) {
        val uri = savedUri?.let { Uri.parse(it) } ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }
    val defaultDir = (context.getExternalFilesDir(null) ?: context.filesDir).also { it.mkdirs() }
    return DocumentFile.fromFile(defaultDir)
}

// Merges a freshly-scanned NovelEntity with whatever row already exists for this
// novel id, carrying over fields the scan itself doesn't own (pageSize,
// readingStatus, remote-metadata fields once a "Fetch info" lookup has run) - see the
// long comment this was pulled out of below for the full field-by-field rationale.
// Shared by MainActivity's startScan (onDiscovered) and scanSingleSyncedNovel (see
// docs/arkarium/NEXT_FIXES.md #4) so both a full-library rescan and a scoped
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
        externalSourceUrl = existing.externalSourceUrl,
        metadataFetchedAt = existing.metadataFetchedAt,
        // Sync bookkeeping columns aren't touched by ScannerImpl at all (scanned's
        // are always the NovelEntity defaults) - always carry the existing row's
        // values forward so a rescan/resync can never accidentally wipe them.
        syncSourceUrl = existing.syncSourceUrl,
        syncSourceVersion = existing.syncSourceVersion,
        lastSyncedAt = existing.lastSyncedAt,
        syncStatus = existing.syncStatus
    )
}
