package com.arkarium.app.viewmodel

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.arkarium.app.data.AppDatabase
import com.arkarium.app.data.AuthorEntity
import com.arkarium.app.data.CatalogueStorage
import com.arkarium.app.data.ChapterManifestMetaEntity
import com.arkarium.app.data.FictionLut
import com.arkarium.app.data.MissingLocalFolderException
import com.arkarium.app.data.NovelAnnouncementEntity
import com.arkarium.app.data.NovelEntity
import com.arkarium.app.data.ScannerImpl
import com.arkarium.app.data.SourceGoneException
import com.arkarium.app.data.SyncManager
import com.arkarium.app.data.SyncOutcome
import com.arkarium.app.data.SyncStatus
import com.arkarium.app.data.mergeNovelForRescan
import com.arkarium.app.data.relayBaseUrlForSlug
import com.arkarium.app.navigation.AddFictionState
import com.arkarium.app.navigation.SyncAllState
import com.arkarium.app.navigation.SyncCheckState
import com.arkarium.app.navigation.SyncResolutionReason
import com.arkarium.app.navigation.SyncResolutionState
import com.arkarium.app.util.Haptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

// Result of one refreshCatalogue() pass (Rung 2, docs/implementation/
// catalogue-browse-redesign.md) - `skipped` names which display names didn't refresh
// this pass (C4), not why each one failed; a per-slug reason is logged at the call site
// (fetchCatalogueItem) but isn't surfaced further than that yet, same as
// syncAllRaeArkNovels' own SyncAllItemResult.Skipped not carrying a reason either.
data class CatalogueRefreshSummary(val fetchedCount: Int, val skipped: List<String>)

// Stage 2.5 of Phase 2 (docs/foundational/design-notes.md) - the fourth and last of the
// plan's feature-scoped ViewModels pulled out of MainActivity. Owns syncAllState,
// syncCheckState, and syncResolutionState (the three dialog-driving sealed states this
// stage is scoped to) plus the sync/resolution logic that drives them against
// SyncManager: scanSingleSyncedNovel, syncAllRaeArkNovels, checkForUpdates, and the
// three resolveXxx actions offered from SyncResolutionDialog. Last of the four stages,
// both because it's the largest remaining slice of Activity state/logic and because
// its scanSingleSyncedNovel path calls into the library-scan functions Stage 2.1
// extracted (mergeNovelForRescan) and reads/updates the novel list Stage 2.3 already
// moved (libraryViewModel.novels/startScan).
//
// addFictionByName also moves here, even though it writes metadataViewModel's
// addFictionState (Stage 2.4), not one of this stage's three states - see
// MetadataViewModel's own doc comment: unlike fetchMetadataFor/applyMetadata it never
// calls GoogleBooksMetadataProvider, it calls SyncManager/FictionLut and
// libraryViewModel.startScan, i.e. exactly the same download-then-scan-then-persist
// shape syncAllRaeArkNovels below already has - so it's Sync business logic, not
// Metadata business logic, and belongs behind the Sync-named door (rule 1). It keeps
// writing metadataViewModel.addFictionState.value directly, the same "state lives on
// the ViewModel, an external writer sets it via its public property" shape
// MetadataViewModel's own doc comment describes MainActivity's `currentScreen` using -
// SyncViewModel is just that external writer now instead of MainActivity.
//
// AndroidViewModel, not plain ViewModel: FictionLut.lookup/allEntries both take a
// Context (they read a bundled asset), so this is the one remaining ViewModel that
// needs one - same "AndroidViewModel/viewModelFactory for the pieces that still need
// Context" the plan calls out for exactly this reason. `db`, `scanner`, and
// `syncManager` stay concrete types (AppDatabase, ScannerImpl, SyncManager), matching
// LibraryViewModel/MetadataViewModel's own precedent of not abstracting Room/SAF/
// network away - this class is consequently untested at the ViewModel tier for the
// same reason MetadataViewModel's applyMetadata is (no in-memory AppDatabase in a
// plain JVM test without Robolectric, which this codebase's testing strategy
// deliberately doesn't reach for yet).
//
// Takes LibraryViewModel and MetadataViewModel themselves (not just the fields it
// reaches into on each), matching MetadataViewModel's own precedent of taking
// LibraryViewModel as a constructor dependency rather than inventing a narrower
// interface for one field.
//
// currentScreen is Activity navigation state (Phase 3 - NavHost migration - hasn't
// happened yet), so none of the functions below touch it directly - every place the
// pre-Stage-2.5 code patched currentScreen after a sync/resolution action now reports
// the result back via a plain callback instead, the same onApplied shape
// MetadataViewModel.applyMetadata already uses for the identical reason. MainActivity's
// call sites are the ones that still touch currentScreen for these flows.
class SyncViewModel(
    application: Application,
    private val db: AppDatabase,
    private val scanner: ScannerImpl,
    private val syncManager: SyncManager,
    private val libraryViewModel: LibraryViewModel,
    private val metadataViewModel: MetadataViewModel,
    // Rung 3 of docs/implementation/catalogue-browse-redesign.md - defaulted so the
    // Factory below (and any test) doesn't have to know about it.
    private val catalogueStorage: CatalogueStorage = CatalogueStorage(application)
) : AndroidViewModel(application) {

    val syncAllState = mutableStateOf<SyncAllState>(SyncAllState.Idle)
    val syncCheckState = mutableStateOf<SyncCheckState>(SyncCheckState.Idle)
    val syncResolutionState = mutableStateOf<SyncResolutionState>(SyncResolutionState.Idle)

    // Moved verbatim from MainActivity (Stage 2.5). Scoped counterpart to
    // LibraryViewModel.startScan (see docs/foundational/architecture.md, Sync invariants S8): runs the exact
    // same discover -> merge -> upsert -> scanChaptersForNovel sequence startScan's
    // onDiscovered callback runs per-novel, but for exactly one already-known folder,
    // via ScannerImpl.scanSingleNovel instead of a full scanRoot() pass. Used by
    // syncAllRaeArkNovels below so syncing fiction M into a library of N-1 other
    // synced novels doesn't re-list and re-fingerprint all N-1 of them on every
    // iteration. Deliberately does NOT do startScan's stale-novel reconciliation (that
    // requires seeing every folder in one pass, which is exactly what this method
    // avoids) - the library's next full scan (e.g. next app launch) still catches
    // anything that needs it.
    private suspend fun scanSingleSyncedNovel(
        libraryRoot: DocumentFile,
        novelFolder: DocumentFile,
        onProgress: suspend (message: String) -> Unit = {}
    ): NovelEntity {
        // Captured by the onAuthorsDiscovered callback below (fired before
        // scanSingleNovel returns) so it's available down here for mergeNovelForRescan
        // - see that callback's own comment and ScannerImpl.scanRoot's doc comment on
        // the same flag.
        var authorsFolderFound = false
        val onAuthorsDiscovered: suspend (List<AuthorEntity>, Boolean) -> Unit = { discoveredAuthors, found ->
            authorsFolderFound = found
            // See startScan's matching callback: only touch the authors table when
            // this pass actually found authors/ - an empty discoveredAuthors list
            // from a not-found folder must never be read as "delete every known
            // author."
            if (found) {
                val seenIds = discoveredAuthors.map { it.id }.toSet()
                discoveredAuthors.forEach { db.authorDao().upsert(it) }
                db.authorDao().all().filter { it.id !in seenIds }.forEach { stale ->
                    db.authorDao().delete(stale.id)
                }
            }
        }
        var scanned = scanner.scanSingleNovel(
            root = libraryRoot,
            novelFolder = novelFolder,
            onAuthorsDiscovered = onAuthorsDiscovered
        )
        val existing = db.novelDao().findById(scanned.id)
        // mergeNovelForRescan's authorsFolderFound fallback only ever runs when
        // `existing` is non-null - it short-circuits with `if (existing == null)
        // return scanned` before ever looking at the flag. So a brand-new novel
        // (this is its very first scan, no row to fall back to) gets zero benefit
        // from that resilience fix: if this pass didn't see authors/ - e.g. the
        // authors/<id>.json this fiction's sync just wrote hasn't surfaced in a SAF
        // listFiles() call yet - authorId is baked in as null the moment this row is
        // inserted, and nothing will ever re-scan just this one novel again to fix
        // it (only the next full startScan() would). One immediate retry gives that
        // transient listing gap a chance to resolve before the row is ever created.
        if (existing == null && !authorsFolderFound) {
            scanned = scanner.scanSingleNovel(
                root = libraryRoot,
                novelFolder = novelFolder,
                onAuthorsDiscovered = onAuthorsDiscovered
            )
        }
        val novel = mergeNovelForRescan(scanned, existing, authorsFolderFound)
        db.novelDao().upsert(novel)
        withContext(Dispatchers.Main) {
            val idx = libraryViewModel.novels.indexOfFirst { it.id == novel.id }
            if (idx >= 0) libraryViewModel.novels[idx] = novel else libraryViewModel.novels.add(novel)
        }
        scanner.scanChaptersForNovel(novelFolder, novel.id, db, onProgress)
        return novel
    }

    // Moved from MainActivity (Stage 2.5) - see the class doc comment above for why
    // this stage, not Stage 2.4, is where it ends up. Kicks off "Add fiction" end to
    // end (see docs/foundational/architecture.md, Sync: entry points, and the later move to
    // single-origin name lookup): resolves the typed name to a slug via FictionLut,
    // builds the one relay's URL for it, downloads every file the relay's
    // manifest.json lists into a fresh folder under the active library root, then runs
    // the exact same startScan() pass every other novel goes through so the new folder
    // is discovered the normal way - no separate "remote novel" render path. The
    // folder's eventual novel id isn't known until that scan assigns it, so this
    // recomputes ScannerImpl's own id formula
    // (UUID.nameUUIDFromBytes(root.uri + ":" + folder.uri)) rather than guessing, and
    // only attaches the sync bookkeeping (SyncedFileEntity rows, NovelDao.updateSyncState)
    // once the scan has actually run and that id is confirmed to exist.
    // Persists SyncOutcome's manifest-metadata fields ("Serial-fiction manifest
    // metadata", ideas.md/peer-app-survey.md Suggestion 1) - shared by all three sync
    // call sites below (addFictionByName, the "sync all" per-item path, and
    // checkForUpdates) so the three can't drift into writing this three different
    // ways. Always called after the scan that (re)creates/updates this novel's chapter
    // rows has already finished - findByNovelAndRelativePath below needs those rows to
    // exist with a current relative_path to match against (see ChapterEntity.
    // relativePath's doc comment).
    //
    // publicationStatus is written unconditionally, including null - see
    // NovelDao.updatePublicationStatus's doc comment: a manifest that stops declaring
    // a status it used to have is a real, meaningful change, not something to ignore.
    // announcements is the opposite: null (the field was simply absent from this
    // manifest, as every manifest predating this feature will be) leaves whatever
    // novel_announcements already has untouched, rather than wiping a real,
    // previously-synced announcement list just because an old manifest doesn't know
    // about the field yet. An explicit empty array, which parses to an empty (non-null)
    // list, does still wholesale-replace down to zero rows - that's the publisher
    // actually clearing their announcements, a different thing from the field not
    // existing.
    private suspend fun applyManifestMetadata(novelId: String, outcome: SyncOutcome) {
        db.novelDao().updatePublicationStatus(novelId, outcome.publicationStatus)
        // Same unconditional-write contract as publicationStatus just above, not
        // announcements' "null means leave alone" one - chaptersPerWeek has no
        // "explicit empty means clear, absent means don't touch" distinction to make
        // (it's a single nullable value, not a list), so a manifest that stops
        // declaring a cadence really does clear it, same as publicationStatus would.
        db.novelDao().updateChaptersPerWeek(novelId, outcome.chaptersPerWeek)

        outcome.announcements?.let { announcements ->
            val fetchedAt = System.currentTimeMillis()
            db.novelAnnouncementDao().replaceForNovel(
                novelId,
                announcements.map { announcement ->
                    NovelAnnouncementEntity(
                        // Deterministic, not a random UUID, so re-syncing the same
                        // manifest twice in a row (nothing actually changed) produces
                        // the same ids rather than new rows each time - harmless either
                        // way given replaceForNovel deletes first, but keeps the id
                        // meaningful rather than throwaway.
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
            val chapter = db.chapterDao().findByNovelAndRelativePath(novelId, relativePath)
            // A manifest entry with no matching local chapter row isn't an error worth
            // surfacing - same "one missing thing doesn't abort the batch" posture as
            // SourceGoneException handling elsewhere in this file. It just means this
            // particular file hasn't been scanned as a chapter yet (wrong extension,
            // sits outside arcs/, scan hasn't run for some other reason) - nothing to
            // attach the word count/read time to until it has.
            if (chapter != null) {
                db.chapterManifestMetaDao().upsert(
                    ChapterManifestMetaEntity(
                        chapterId = chapter.id,
                        wordCount = wordCount,
                        readTimeMinutes = readTimeMinutes
                    )
                )
            }
        }
    }

    // Rung 4 of docs/implementation/catalogue-browse-redesign.md - C2: a catalogue entry
    // disappears the moment its fiction is followed. Called only after the real
    // NovelEntity + synced files are in place, so a failed add leaves the entry
    // browsable. Deliberately never throws: by this point the follow itself has
    // succeeded, and a leftover catalogue row (or cache folder) must not turn that
    // into an "Couldn't add this fiction" error - the worst case is a stale preview
    // that the next refreshCatalogue() won't re-ingest, since the fiction is now
    // followed. Row first, files second: a surviving folder with no row is inert
    // (nothing reads it), whereas a surviving row would point at a missing cover.
    private suspend fun dropCatalogueEntry(slug: String) {
        try {
            db.catalogueEntryDao().delete(slug)
            withContext(Dispatchers.IO) { catalogueStorage.delete(slug) }
        } catch (e: Exception) {
            // Intentionally swallowed - see above.
        }
    }

    fun addFictionByName(name: String, libraryRoot: DocumentFile) {
        val slug = FictionLut.lookup(getApplication(), name)
        if (slug == null) {
            metadataViewModel.addFictionState.value = AddFictionState.Error("Couldn't find a fiction called \"$name\".")
            return
        }
        val url = relayBaseUrlForSlug(slug)
        metadataViewModel.addFictionState.value = AddFictionState.InProgress("Fetching manifest...")
        viewModelScope.launch {
            try {
                // `name` (the user-typed fiction name that resolved to this slug) becomes
                // the actual on-disk folder name - see SyncManager.downloadInitial's doc
                // comment on why that's now safe to do (folder identity for sync no
                // longer comes from this name once the novel exists).
                val (folderName, outcome) = syncManager.downloadInitial(url, libraryRoot, name) { message ->
                    withContext(Dispatchers.Main) {
                        metadataViewModel.addFictionState.value = AddFictionState.InProgress(message)
                    }
                }
                withContext(Dispatchers.Main) {
                    metadataViewModel.addFictionState.value = AddFictionState.InProgress("Adding to your library...")
                }
                val folder = libraryRoot.findFile(folderName)
                    ?: throw IOException("The downloaded fiction folder went missing before it could be scanned")
                val novelId = UUID.nameUUIDFromBytes(
                    (libraryRoot.uri.toString() + ":" + folder.uri.toString()).toByteArray()
                ).toString()
                libraryViewModel.startScan(libraryRoot)
                db.syncedFileDao().upsertAll(outcome.files.map { it.copy(novelId = novelId) })
                db.novelDao().updateSyncState(novelId, url, outcome.newVersion, System.currentTimeMillis(), folderName)
                // After startScan (chapter rows for this novel now exist) and after
                // updateSyncState - matches every other post-sync write's ordering.
                applyManifestMetadata(novelId, outcome)
                val updated = db.novelDao().findById(novelId)
                if (updated != null) {
                    val idx = libraryViewModel.novels.indexOfFirst { it.id == updated.id }
                    if (idx >= 0) libraryViewModel.novels[idx] = updated
                }
                // C2: the real library folder is now the one representation of this
                // fiction - drop its catalogue row and cached files.
                dropCatalogueEntry(slug)
                metadataViewModel.addFictionState.value = AddFictionState.Hidden
            } catch (e: Exception) {
                metadataViewModel.addFictionState.value = AddFictionState.Error("Couldn't add this fiction: ${e.message}")
            }
        }
    }

    // Moved from MainActivity (Stage 2.5). "Sync all Rae ARK's novels" (EmptyLibraryPrompt's
    // primary first-run action, see HomeScreen.kt). Downloads every fiction
    // FictionLut.allEntries lists, several at a time (see downloadFiction and
    // NOVEL_SYNC_CONCURRENCY below), reusing exactly the same per-fiction
    // download/persist steps as addFictionByName above - this is just that same flow
    // fanned out with one shared progress dialog instead of one dialog per fiction.
    //
    // Differences from the original version (see docs/foundational/architecture.md, Sync invariants S8 and #2):
    //
    // - Each fiction now calls scanSingleSyncedNovel (scoped to just the one
    //   just-downloaded folder) instead of a full startScan() pass. The old version's
    //   full-rescan-per-iteration was O(M x N) SAF directory listings for M fictions
    //   being synced into a library of N - every already-synced novel got re-listed
    //   and re-fingerprinted on every single loop iteration, not just the one that
    //   actually changed. This keeps the same "show up as it finishes" UX (each
    //   fiction is still discovered and added to `novels` the moment its own download
    //   completes) without paying for a full-library rescan per fiction.
    // - A SourceGoneException for one fiction (the relay 404s on its manifest - e.g. a
    //   stale FictionLut entry) no longer aborts the whole batch. It's reported and
    //   skipped, and the batch continues with the rest - a single stale/removed entry
    //   shouldn't block every other fiction from syncing. Any other exception (a real
    //   relay/network problem) still stops the batch, same as before, so a genuine
    //   outage isn't masked by "finished anyway."
    // - Previously ran one fiction fully to completion (manifest fetch, every file,
    //   the scan, the DB writes) before the next fiction's manifest fetch even
    //   started - for a library of 1000 that serialized 1000 round-trips of pure
    //   network latency that could otherwise overlap, even though each fiction's own
    //   files were already downloading concurrently one level down (see
    //   SyncManager.DOWNLOAD_CONCURRENCY). downloadFiction below now runs several
    //   fictions' full sequences at once, bounded by NOVEL_SYNC_CONCURRENCY.

    // One fiction's outcome from a syncAllRaeArkNovels batch - folded into the
    // skipped/syncedCount/lastCoverUrl summary only after every concurrent task has
    // finished (see syncAllRaeArkNovels), rather than mutated from inside each task
    // the way the old sequential loop's local vars were.
    private sealed class SyncAllItemResult {
        data class Synced(val coverUrl: String?) : SyncAllItemResult()
        data class Skipped(val displayName: String) : SyncAllItemResult()
    }

    fun syncAllRaeArkNovels(libraryRoot: DocumentFile) {
        val entries = FictionLut.allEntries(getApplication())
        if (entries.isEmpty()) {
            syncAllState.value = SyncAllState.Error("No fictions are listed to sync.")
            return
        }
        syncAllState.value = SyncAllState.InProgress("Starting...")
        viewModelScope.launch {
            val completed = AtomicInteger(0)
            val total = entries.size
            try {
                val results = coroutineScope {
                    val semaphore = Semaphore(NOVEL_SYNC_CONCURRENCY)
                    entries.map { entry ->
                        async {
                            semaphore.withPermit {
                                downloadFiction(entry, libraryRoot, completed, total)
                            }
                        }
                    }.awaitAll()
                }
                val skipped = results.filterIsInstance<SyncAllItemResult.Skipped>().map { it.displayName }
                val syncedCount = results.count { it is SyncAllItemResult.Synced }
                // Cover of some novel this batch actually downloaded+scanned, for the
                // Done dialog (see SyncAllState's doc comment) - "most recent" no longer
                // means anything once novels finish in whatever order their own
                // network calls happen to land in, so this just prefers any synced
                // novel that has one over the placeholder.
                val lastCoverUrl = results.filterIsInstance<SyncAllItemResult.Synced>()
                    .lastOrNull { it.coverUrl != null }?.coverUrl

                val summary = StringBuilder("Synced $syncedCount novel(s).")
                if (skipped.isNotEmpty()) {
                    summary.append(" Skipped (source unavailable): ${skipped.joinToString(", ")}.")
                }
                syncAllState.value = SyncAllState.Done(summary.toString(), lastCoverUrl)
                // Micro-haptics (util/Haptics.kt) - batch actually finished, not an
                // Error outcome (that one's left silent; a buzz on failure would read
                // as a confirmation, not a warning).
                Haptics.confirm(getApplication())
            } catch (e: Exception) {
                // coroutineScope propagates the first non-SourceGoneException failure
                // out here after cancelling every other in-flight novel (structured
                // concurrency) - same "a real relay/network problem stops the whole
                // batch" outcome the old sequential version had for anything that
                // wasn't a SourceGoneException, just reached by cancellation instead
                // of a loop simply never reaching its next iteration.
                syncAllState.value = SyncAllState.Error("Sync stopped: ${e.message}")
            }
        }
    }

    // One fiction's full download+scan+persist sequence, exactly what the old
    // sequential loop's body did per iteration - factored out so syncAllRaeArkNovels
    // can run several of these at once instead of one after another. `completed` is
    // shared and only ever incremented (AtomicInteger.incrementAndGet), so the
    // progress text stays meaningful ("3/1000 synced") even though several of these
    // run concurrently and finish in no particular order.
    private suspend fun downloadFiction(
        entry: Pair<String, String>,
        libraryRoot: DocumentFile,
        completed: AtomicInteger,
        total: Int
    ): SyncAllItemResult {
        val (displayName, slug) = entry
        val url = relayBaseUrlForSlug(slug)
        withContext(Dispatchers.Main) {
            syncAllState.value =
                SyncAllState.InProgress("${completed.get()}/$total synced - fetching \"$displayName\"...")
        }
        return try {
            // `displayName` (from FictionLut.allEntries) becomes the actual on-disk
            // folder name, same as addFictionByName above.
            val (folderName, outcome) = syncManager.downloadInitial(url, libraryRoot, displayName) { message ->
                withContext(Dispatchers.Main) {
                    syncAllState.value =
                        SyncAllState.InProgress("${completed.get()}/$total synced - $displayName: $message")
                }
            }
            val folder = libraryRoot.findFile(folderName)
                ?: throw IOException("\"$displayName\" went missing before it could be scanned")
            val novelId = UUID.nameUUIDFromBytes(
                (libraryRoot.uri.toString() + ":" + folder.uri.toString()).toByteArray()
            ).toString()
            // Scoped scan of just this fiction's folder (see docs/foundational/architecture.md,
            // Sync invariants S8) - shows up in the library as soon as it's done, same as
            // before, without re-scanning every other already-synced novel.
            scanSingleSyncedNovel(libraryRoot, folder) { message ->
                withContext(Dispatchers.Main) {
                    syncAllState.value =
                        SyncAllState.InProgress("${completed.get()}/$total synced - $displayName: $message")
                }
            }
            db.syncedFileDao().upsertAll(outcome.files.map { it.copy(novelId = novelId) })
            db.novelDao().updateSyncState(novelId, url, outcome.newVersion, System.currentTimeMillis(), folderName)
            // Pre-existing gap fixed here: "Sync all Rae ARK's novels" never called
            // applyManifestMetadata, unlike addFictionByName - every novel added this
            // way landed with publicationStatus/announcements/chapterManifestMeta/
            // chaptersPerWeek silently dropped on the floor even though
            // SyncOutcome carried them the whole time. Same post-scan,
            // post-updateSyncState ordering as addFictionByName.
            applyManifestMetadata(novelId, outcome)
            val updated = db.novelDao().findById(novelId)
            if (updated != null) {
                withContext(Dispatchers.Main) {
                    val idx = libraryViewModel.novels.indexOfFirst { it.id == updated.id }
                    if (idx >= 0) libraryViewModel.novels[idx] = updated
                }
            }
            // C2 applies to "Sync all" too - it follows fictions just like addFictionByName.
            dropCatalogueEntry(slug)
            val justCompleted = completed.incrementAndGet()
            withContext(Dispatchers.Main) {
                syncAllState.value = SyncAllState.InProgress(
                    "$justCompleted/$total synced - $displayName done",
                    // scanSingleSyncedNovel above already picked up cover.* from disk
                    // into `updated.coverUri` (falling back to remoteCoverUrl, same
                    // resolution order every other cover call site uses) - show it the
                    // moment this novel finishes.
                    updated?.coverUri ?: updated?.remoteCoverUrl
                )
            }
            SyncAllItemResult.Synced(updated?.coverUri ?: updated?.remoteCoverUrl)
        } catch (e: SourceGoneException) {
            // This one fiction isn't served here anymore (stale LUT entry, or the
            // author removed it) - report and move on rather than blocking every
            // other fiction in the batch behind it.
            completed.incrementAndGet()
            SyncAllItemResult.Skipped(displayName)
        }
    }

    // Rung 2 of docs/implementation/catalogue-browse-redesign.md. One fiction's
    // fetch+upsert for the catalogue ingestion pass - same per-item shape as
    // downloadFiction above (bounded concurrency, "one bad apple doesn't block the
    // batch"), but deliberately not fanned out through syncAllRaeArkNovels' own
    // machinery: that function's downloadFiction only swallows SourceGoneException and
    // lets everything else cancel the whole batch (structured concurrency), which is
    // the right call for a user-initiated "Sync all" someone is actively waiting on.
    // This is a lighter-weight, on-demand background refresh - C4 asks for *any*
    // per-slug failure (a stale LUT entry, a transient network blip, a malformed
    // manifest) to just skip that one entry, never abort the rest.
    private sealed class CatalogueRefreshItemResult {
        object Fetched : CatalogueRefreshItemResult()
        data class Skipped(val displayName: String, val reason: String) : CatalogueRefreshItemResult()
    }

    // Plain suspend function, not auto-launched into viewModelScope with a dialog-
    // driving state the way syncAllRaeArkNovels is - Rungs 1-4 are "independently
    // testable without touching a screen" (the redesign doc's own words), and this is
    // the data-layer half of that: callable directly from a test, or from whatever
    // Rung 5+ screen ends up triggering it (a pull-to-refresh, Browse's own open).
    // No dialog/progress state exists yet for the same reason - one gets added once
    // there's an actual screen to drive, not speculatively ahead of it.
    suspend fun refreshCatalogue(): CatalogueRefreshSummary = coroutineScope {
        val entries = FictionLut.allEntries(getApplication())
        if (entries.isEmpty()) return@coroutineScope CatalogueRefreshSummary(fetchedCount = 0, skipped = emptyList())

        // "For every (displayName, slug) FictionLut already knows about that isn't
        // already in the library (no NovelEntity whose syncSourceUrl equals
        // relayBaseUrlForSlug(slug))" - the redesign doc's own ingestion-scope
        // sentence, applied here rather than ingesting every fiction and only
        // filtering afterward. This is the forward half of C2 (never creating a
        // catalogue row for an already-followed fiction); Rung 4 wires the reverse
        // direction (deleting one the moment addFictionByName succeeds for its slug).
        val followedUrls = db.novelDao().all().mapNotNull { it.syncSourceUrl }.toSet()
        val toIngest = entries.filterNot { (_, slug) -> relayBaseUrlForSlug(slug) in followedUrls }

        val semaphore = Semaphore(CATALOGUE_FETCH_CONCURRENCY)
        val results = toIngest.map { entry ->
            async { semaphore.withPermit { fetchCatalogueItem(entry) } }
        }.awaitAll()

        CatalogueRefreshSummary(
            fetchedCount = results.count { it is CatalogueRefreshItemResult.Fetched },
            skipped = results.filterIsInstance<CatalogueRefreshItemResult.Skipped>().map { it.displayName }
        )
    }

    private suspend fun fetchCatalogueItem(entry: Pair<String, String>): CatalogueRefreshItemResult {
        val (displayName, slug) = entry
        return try {
            val fetched = syncManager.fetchCatalogueEntry(slug, displayName)
            // Rung 3: persist the cover/metadata bytes fetchCatalogueEntry hands back
            // (C1's SyncPaths.sanitize is applied inside CatalogueStorage). A failed
            // write only costs this entry its cover - the text metadata is still worth
            // showing - so it falls back to coverPath = null instead of skipping the
            // whole slug.
            val coverPath = try {
                withContext(Dispatchers.IO) {
                    catalogueStorage.write(
                        slug = slug,
                        coverBytes = fetched.coverBytes,
                        coverFileName = fetched.coverFileName,
                        metadataBytes = fetched.metadataBytes
                    )
                }
            } catch (e: IOException) {
                null
            }
            db.catalogueEntryDao().upsert(fetched.entry.copy(coverPath = coverPath))
            CatalogueRefreshItemResult.Fetched
        } catch (e: Exception) {
            // C4: one slug's failure never aborts the rest - and deliberately broader
            // than downloadFiction's catch above (SourceGoneException only). A
            // catalogue refresh is a convenience pass over fictions nobody has
            // followed yet, not a download the person is actively waiting on, so
            // nothing here is worth stopping the other entries for - this one just
            // doesn't refresh this pass (C3: visible via its unchanged/absent
            // cachedAt, retried next refresh, not retried right now).
            CatalogueRefreshItemResult.Skipped(displayName, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    // Moved from MainActivity (Stage 2.5). Originally also patched MainActivity's own
    // `currentScreen` on a successful resync, when the novel being viewed in
    // NovelDetail was the one just updated, via an `onUpdated` callback (the same
    // report-the-result-via-a-plain-lambda shape MetadataViewModel.applyMetadata's
    // `onApplied` used for the identical reason) - Stage 3.3 (see
    // docs/foundational/design-notes.md) removed that parameter: NovelDetail is now a
    // "novelDetail/{novelId}" route that resolves its own NovelEntity from
    // libraryViewModel.novels on every recomposition, so the `novels` patch below is
    // enough on its own, with nothing left needing a callback into currentScreen.
    //
    // Re-syncs an already-added fiction against its relay (see docs/foundational/architecture.md, Sync, Stage
    // 3). SyncManager.sync's SyncOutcome.files is documented as the complete new file
    // set, not a delta (see SyncManager.kt) - so on a real change this replaces
    // SyncedFileEntity wholesale rather than patching it, and only re-runs startScan
    // when something actually changed, so an "already up to date" check stays a single
    // manifest fetch instead of paying for a full rescan every time (docs/foundational/architecture.md, Sync invariants
    // S5).
    // `allowRecreateMissingFolder` is only ever true when the user has explicitly
    // confirmed it via the sync-resolution dialog (see resolveMissingFolderBySyncing
    // below) - an automatic/background-triggered call always leaves it false, so a
    // missing local folder surfaces as a NeedsResolution prompt instead of a silent
    // redownload (see docs/foundational/architecture.md, Sync invariants S6 and SyncManager.sync's own doc comment).
    fun checkForUpdates(
        novel: NovelEntity,
        libraryRoot: DocumentFile,
        allowRecreateMissingFolder: Boolean = false
    ) {
        syncCheckState.value = SyncCheckState.InProgress(novel, "Checking for updates...")
        viewModelScope.launch {
            try {
                val sourceUrl = novel.syncSourceUrl
                    ?: throw IllegalStateException("This novel has no sync source")
                val knownFiles = db.syncedFileDao().forNovel(novel.id)
                val outcome = syncManager.sync(novel, libraryRoot, knownFiles, allowRecreateMissingFolder) { message ->
                    withContext(Dispatchers.Main) {
                        syncCheckState.value = SyncCheckState.InProgress(novel, message)
                    }
                }
                // Pre-existing gap fixed here: checkForUpdates never called
                // applyManifestMetadata at all - the one call site this is most load-
                // bearing for, since SyncOutcome's own doc comment explains status/
                // announcements/chaptersPerWeek are populated even when outcome.changed
                // is false specifically so "check for updates" notices a publisher
                // flipping ongoing->hiatus without a version bump. Unconditional - not
                // inside `if (outcome.changed)` below - for exactly that reason.
                applyManifestMetadata(novel.id, outcome)
                // Hoisted out of the `if (outcome.changed)` block below so the Done state
                // can use it too - see the bug this fixes just below.
                var refreshedNovel: NovelEntity? = null
                if (!outcome.changed) {
                    // applyManifestMetadata just above can still have written a new
                    // publicationStatus/chaptersPerWeek even on this branch (that's the
                    // whole point - see the comment above) - re-read and patch
                    // libraryViewModel.novels the same way the `outcome.changed` branch
                    // does below, so e.g. a status flip to "Hiatus" actually shows up
                    // without the novel's files having changed at all.
                    val updated = db.novelDao().findById(novel.id)
                    if (updated != null) {
                        val idx = libraryViewModel.novels.indexOfFirst { it.id == updated.id }
                        if (idx >= 0) libraryViewModel.novels[idx] = updated
                    }
                    refreshedNovel = updated
                }
                if (outcome.changed) {
                    db.syncedFileDao().deleteForNovel(novel.id)
                    db.syncedFileDao().upsertAll(outcome.files.map { it.copy(novelId = novel.id) })
                    // outcome.folderName is always set on this branch (see SyncManager.sync -
                    // it's only ever null on the early "already up to date" return, which
                    // can't be true here since outcome.changed is true). The fallback chain
                    // is just defense-in-depth against DocumentFile.name's nullable type.
                    db.novelDao().updateSyncState(
                        novel.id, sourceUrl, outcome.newVersion, System.currentTimeMillis(),
                        outcome.folderName ?: novel.syncFolderName ?: SyncManager.slugForUrl(sourceUrl)
                    )
                    // This resync succeeded, so whatever made the novel MISSING_LOCALLY
                    // (or that this call is a deliberate resolution retry for) no longer
                    // applies - clear it back to ACTIVE rather than leaving a stale status.
                    db.novelDao().updateSyncStatus(novel.id, SyncStatus.ACTIVE.name)
                    libraryViewModel.startScan(libraryRoot)
                    val updated = db.novelDao().findById(novel.id)
                    if (updated != null) {
                        val idx = libraryViewModel.novels.indexOfFirst { it.id == updated.id }
                        if (idx >= 0) libraryViewModel.novels[idx] = updated
                    }
                    refreshedNovel = updated
                }
                // Bug fix: this used to pass the pre-sync `novel` parameter here, which
                // still had the OLD coverUri/remoteCoverUrl captured before the sync ran -
                // so SyncProgressDialog fell back to the placeholder emoji even right after
                // a sync that just downloaded a brand new cover.* (e.g. a novel's very
                // first sync, which starts with coverUri == null). `refreshedNovel` is the
                // post-rescan entity that startScan actually wrote coverUri/etc onto -
                // falls back to `novel` only when nothing changed, in which case they're
                // equivalent anyway.
                syncCheckState.value = SyncCheckState.Done(
                    refreshedNovel ?: novel,
                    if (outcome.changed) "Updated to the latest version." else "Already up to date."
                )
            } catch (e: MissingLocalFolderException) {
                // See docs/foundational/architecture.md, Sync invariants S6: don't silently redownload and don't silently
                // drop the novel either - ask the user via syncResolutionState.
                db.novelDao().updateSyncStatus(novel.id, SyncStatus.MISSING_LOCALLY.name)
                val updated = db.novelDao().findById(novel.id)
                if (updated != null) {
                    withContext(Dispatchers.Main) {
                        val idx = libraryViewModel.novels.indexOfFirst { it.id == updated.id }
                        if (idx >= 0) libraryViewModel.novels[idx] = updated
                    }
                }
                syncCheckState.value = SyncCheckState.Idle
                syncResolutionState.value = SyncResolutionState.NeedsResolution(
                    updated ?: novel, SyncResolutionReason.MISSING_LOCALLY
                )
            } catch (e: SourceGoneException) {
                // See docs/foundational/architecture.md, Sync invariants S6: distinguishable from a generic network
                // failure so the prompt can say what's actually true - the source is
                // gone, not just unreachable right now. Local content stays readable.
                db.novelDao().updateSyncStatus(novel.id, SyncStatus.SOURCE_GONE.name)
                val updated = db.novelDao().findById(novel.id)
                if (updated != null) {
                    withContext(Dispatchers.Main) {
                        val idx = libraryViewModel.novels.indexOfFirst { it.id == updated.id }
                        if (idx >= 0) libraryViewModel.novels[idx] = updated
                    }
                }
                syncCheckState.value = SyncCheckState.Idle
                syncResolutionState.value = SyncResolutionState.NeedsResolution(
                    updated ?: novel, SyncResolutionReason.SOURCE_GONE
                )
            } catch (e: Exception) {
                syncCheckState.value = SyncCheckState.Error(novel, "Sync failed: ${e.message}")
            }
        }
    }

    // Resolution actions offered from SyncResolutionDialog once checkForUpdates hits a
    // MissingLocalFolderException or SourceGoneException (see above and
    // docs/foundational/architecture.md, Sync invariants S6). All three are explicit, user-triggered choices - none of
    // them ever fire automatically. Moved from MainActivity (Stage 2.5); each originally
    // gained a callback where the original touched currentScreen, for the same reason
    // checkForUpdates' onUpdated above did - Stage 3.3 (see docs/foundational/design-notes.md)
    // removed all three: a resync/unlink's `novels` patch is picked up on its own by the
    // "novelDetail/{novelId}" route these can fire on top of, and a removal resolves to
    // a null novel there, which that route's own logic already sends back to Home for.

    // "Sync again": the user has confirmed they want the folder recreated and the
    // fiction redownloaded from scratch - re-runs checkForUpdates with
    // allowRecreateMissingFolder = true, the one path that's allowed to recreate a
    // missing folder.
    fun resolveMissingFolderBySyncing(novel: NovelEntity, libraryRoot: DocumentFile) {
        syncResolutionState.value = SyncResolutionState.Idle
        checkForUpdates(novel, libraryRoot, allowRecreateMissingFolder = true)
    }

    // "Remove from library": the user confirms the deletion they'd otherwise have had
    // done to them silently by the old stale-removal cascade - same NovelDao.delete
    // cascade (arcs/chapters/overrides/progress/synced_files all cascade away with it).
    fun resolveByRemovingFromLibrary(novel: NovelEntity) {
        syncResolutionState.value = SyncResolutionState.Idle
        viewModelScope.launch {
            db.novelDao().delete(novel.id)
            db.syncedFileDao().deleteForNovel(novel.id)
            withContext(Dispatchers.Main) {
                libraryViewModel.novels.removeAll { it.id == novel.id }
            }
        }
    }

    // "Unlink": only offered for SOURCE_GONE. Drops the sync relationship (this novel
    // reverts to a plain local one) without touching any local content - there's
    // nothing left to sync against, but everything already downloaded keeps working.
    fun resolveSourceGoneByUnlinking(novel: NovelEntity) {
        syncResolutionState.value = SyncResolutionState.Idle
        viewModelScope.launch {
            db.novelDao().unlinkSyncSource(novel.id)
            val updated = db.novelDao().findById(novel.id)
            if (updated != null) {
                withContext(Dispatchers.Main) {
                    val idx = libraryViewModel.novels.indexOfFirst { it.id == updated.id }
                    if (idx >= 0) libraryViewModel.novels[idx] = updated
                }
            }
        }
    }

    companion object {
        // How many fictions downloadFiction (syncAllRaeArkNovels) runs at once - see
        // that function's own doc comment. Bounded for the same reason
        // SyncManager.DOWNLOAD_CONCURRENCY bounds per-file downloads within one novel:
        // high enough that overlapping several novels' network latency is where the
        // real win is, not so high that a library of 1000 opens 1000 connections to
        // the relay at once. Android's platform HttpURLConnection implementation only
        // keeps up to ~5 idle connections warm for reuse regardless of this number
        // (it's a global cap the app can't raise without bringing in a real HTTP
        // client), so pushing this much higher stops buying overlapping latency and
        // starts just paying for more simultaneous fresh TCP+TLS handshakes instead.
        private const val NOVEL_SYNC_CONCURRENCY = 4

        // How many fetchCatalogueItem calls refreshCatalogue runs at once - same
        // bounded-fan-out reasoning as NOVEL_SYNC_CONCURRENCY just above, but a
        // catalogue fetch is far lighter per fiction (one manifest + optional
        // metadata.json + optional cover.*, never the full chapter set
        // downloadFiction pulls down), so a higher bound is fine here without
        // approaching the ~5-idle-connection ceiling NOVEL_SYNC_CONCURRENCY's own
        // comment describes.
        private const val CATALOGUE_FETCH_CONCURRENCY = 6

        // Same manual-Factory shape as the other three ViewModel factories (rule 4 - no
        // DSL needed for one Factory method), extended with the Application instance
        // AndroidViewModel requires. Takes the Activity's existing db/scanner/
        // syncManager/libraryViewModel/metadataViewModel instances rather than
        // constructing its own, same "share the Activity's one instance of each
        // service/ViewModel" pattern the other factories use.
        fun factory(
            application: Application,
            db: AppDatabase,
            scanner: ScannerImpl,
            syncManager: SyncManager,
            libraryViewModel: LibraryViewModel,
            metadataViewModel: MetadataViewModel
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SyncViewModel(application, db, scanner, syncManager, libraryViewModel, metadataViewModel) as T
                }
            }
    }
}
