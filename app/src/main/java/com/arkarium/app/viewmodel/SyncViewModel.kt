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
import com.arkarium.app.data.FictionLut
import com.arkarium.app.data.MissingLocalFolderException
import com.arkarium.app.data.NovelEntity
import com.arkarium.app.data.ScannerImpl
import com.arkarium.app.data.SourceGoneException
import com.arkarium.app.data.SyncManager
import com.arkarium.app.data.SyncStatus
import com.arkarium.app.data.mergeNovelForRescan
import com.arkarium.app.data.relayBaseUrlForSlug
import com.arkarium.app.navigation.AddFictionState
import com.arkarium.app.navigation.SyncAllState
import com.arkarium.app.navigation.SyncCheckState
import com.arkarium.app.navigation.SyncResolutionReason
import com.arkarium.app.navigation.SyncResolutionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

// Stage 2.5 of Phase 2 (docs/arkarium/REFACTOR_PLAN.md) - the fourth and last of the
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
    private val metadataViewModel: MetadataViewModel
) : AndroidViewModel(application) {

    val syncAllState = mutableStateOf<SyncAllState>(SyncAllState.Idle)
    val syncCheckState = mutableStateOf<SyncCheckState>(SyncCheckState.Idle)
    val syncResolutionState = mutableStateOf<SyncResolutionState>(SyncResolutionState.Idle)

    // Moved verbatim from MainActivity (Stage 2.5). Scoped counterpart to
    // LibraryViewModel.startScan (see docs/arkarium/NEXT_FIXES.md #4): runs the exact
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
    // end (see docs/arkarium/SYNC_MVP.md §4/Stage 3, and the later move to
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
                val updated = db.novelDao().findById(novelId)
                if (updated != null) {
                    val idx = libraryViewModel.novels.indexOfFirst { it.id == updated.id }
                    if (idx >= 0) libraryViewModel.novels[idx] = updated
                }
                metadataViewModel.addFictionState.value = AddFictionState.Hidden
            } catch (e: Exception) {
                metadataViewModel.addFictionState.value = AddFictionState.Error("Couldn't add this fiction: ${e.message}")
            }
        }
    }

    // Moved from MainActivity (Stage 2.5). "Sync all Rae ARK's novels" (EmptyLibraryPrompt's
    // primary first-run action, see HomeScreen.kt). Downloads every fiction
    // FictionLut.allEntries lists, one after another, reusing exactly the same
    // per-fiction download/persist steps as addFictionByName above - this is just that
    // same flow run in a loop with one shared progress dialog instead of one dialog per
    // fiction.
    //
    // Two differences from the original version (see docs/arkarium/NEXT_FIXES.md #4 and #2):
    //
    // - Each iteration now calls scanSingleSyncedNovel (scoped to just the one
    //   just-downloaded folder) instead of a full startScan() pass. The old version's
    //   full-rescan-per-iteration was O(M x N) SAF directory listings for M fictions
    //   being synced into a library of N - every already-synced novel got re-listed
    //   and re-fingerprinted on every single loop iteration, not just the one that
    //   actually changed. This keeps the same "show up as it finishes" UX (each
    //   fiction is still discovered and added to `novels` the moment its own download
    //   completes) without paying for a full-library rescan per fiction.
    // - A SourceGoneException for one fiction (the relay 404s on its manifest - e.g. a
    //   stale FictionLut entry) no longer aborts the whole batch. It's reported and
    //   skipped, and the batch continues with the next fiction - a single stale/removed
    //   entry shouldn't block every other fiction from syncing. Any other exception
    //   (a real relay/network problem) still stops the batch, same as before, so a
    //   genuine outage isn't masked by "finished anyway."
    fun syncAllRaeArkNovels(libraryRoot: DocumentFile) {
        val entries = FictionLut.allEntries(getApplication())
        if (entries.isEmpty()) {
            syncAllState.value = SyncAllState.Error("No fictions are listed to sync.")
            return
        }
        syncAllState.value = SyncAllState.InProgress("Starting...")
        viewModelScope.launch {
            val skipped = mutableListOf<String>()
            var syncedCount = 0
            try {
                for ((index, entry) in entries.withIndex()) {
                    val (displayName, slug) = entry
                    val url = relayBaseUrlForSlug(slug)
                    withContext(Dispatchers.Main) {
                        syncAllState.value =
                            SyncAllState.InProgress("${index + 1}/${entries.size}: Fetching \"$displayName\"...")
                    }
                    try {
                        // `displayName` (from FictionLut.allEntries) becomes the actual
                        // on-disk folder name, same as addFictionByName above.
                        val (folderName, outcome) = syncManager.downloadInitial(url, libraryRoot, displayName) { message ->
                            withContext(Dispatchers.Main) {
                                syncAllState.value =
                                    SyncAllState.InProgress("${index + 1}/${entries.size}: $displayName - $message")
                            }
                        }
                        val folder = libraryRoot.findFile(folderName)
                            ?: throw IOException("\"$displayName\" went missing before it could be scanned")
                        val novelId = UUID.nameUUIDFromBytes(
                            (libraryRoot.uri.toString() + ":" + folder.uri.toString()).toByteArray()
                        ).toString()
                        // Scoped scan of just this fiction's folder (see docs/arkarium/NEXT_FIXES.md
                        // #4) - shows up in the library as soon as it's done, same as
                        // before, without re-scanning every other already-synced novel.
                        scanSingleSyncedNovel(libraryRoot, folder) { message ->
                            withContext(Dispatchers.Main) {
                                syncAllState.value =
                                    SyncAllState.InProgress("${index + 1}/${entries.size}: $displayName - $message")
                            }
                        }
                        db.syncedFileDao().upsertAll(outcome.files.map { it.copy(novelId = novelId) })
                        db.novelDao().updateSyncState(novelId, url, outcome.newVersion, System.currentTimeMillis(), folderName)
                        val updated = db.novelDao().findById(novelId)
                        if (updated != null) {
                            withContext(Dispatchers.Main) {
                                val idx = libraryViewModel.novels.indexOfFirst { it.id == updated.id }
                                if (idx >= 0) libraryViewModel.novels[idx] = updated
                            }
                        }
                        syncedCount++
                    } catch (e: SourceGoneException) {
                        // This one fiction isn't served here anymore (stale LUT entry, or
                        // the author removed it) - report and move on rather than blocking
                        // every other fiction in the batch behind it.
                        skipped.add(displayName)
                    }
                }
                val summary = StringBuilder("Synced $syncedCount novel(s).")
                if (skipped.isNotEmpty()) {
                    summary.append(" Skipped (source unavailable): ${skipped.joinToString(", ")}.")
                }
                syncAllState.value = SyncAllState.Done(summary.toString())
            } catch (e: Exception) {
                syncAllState.value = SyncAllState.Error("Sync stopped: ${e.message}")
            }
        }
    }

    // Moved from MainActivity (Stage 2.5). Originally also patched MainActivity's own
    // `currentScreen` on a successful resync, when the novel being viewed in
    // NovelDetail was the one just updated, via an `onUpdated` callback (the same
    // report-the-result-via-a-plain-lambda shape MetadataViewModel.applyMetadata's
    // `onApplied` used for the identical reason) - Stage 3.3 (see
    // docs/arkarium/REFACTOR_PLAN.md) removed that parameter: NovelDetail is now a
    // "novelDetail/{novelId}" route that resolves its own NovelEntity from
    // libraryViewModel.novels on every recomposition, so the `novels` patch below is
    // enough on its own, with nothing left needing a callback into currentScreen.
    //
    // Re-syncs an already-added fiction against its relay (see docs/arkarium/SYNC_MVP.md, Stage
    // 3). SyncManager.sync's SyncOutcome.files is documented as the complete new file
    // set, not a delta (see SyncManager.kt) - so on a real change this replaces
    // SyncedFileEntity wholesale rather than patching it, and only re-runs startScan
    // when something actually changed, so an "already up to date" check stays a single
    // manifest fetch instead of paying for a full rescan every time (docs/arkarium/SYNC_MVP.md
    // "Future considerations" #5).
    // `allowRecreateMissingFolder` is only ever true when the user has explicitly
    // confirmed it via the sync-resolution dialog (see resolveMissingFolderBySyncing
    // below) - an automatic/background-triggered call always leaves it false, so a
    // missing local folder surfaces as a NeedsResolution prompt instead of a silent
    // redownload (see docs/arkarium/NEXT_FIXES.md #2 and SyncManager.sync's own doc comment).
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
                }
                syncCheckState.value = SyncCheckState.Done(
                    novel,
                    if (outcome.changed) "Updated to the latest version." else "Already up to date."
                )
            } catch (e: MissingLocalFolderException) {
                // See docs/arkarium/NEXT_FIXES.md #2: don't silently redownload and don't silently
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
                // See docs/arkarium/NEXT_FIXES.md #2: distinguishable from a generic network
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
    // docs/arkarium/NEXT_FIXES.md #2). All three are explicit, user-triggered choices - none of
    // them ever fire automatically. Moved from MainActivity (Stage 2.5); each originally
    // gained a callback where the original touched currentScreen, for the same reason
    // checkForUpdates' onUpdated above did - Stage 3.3 (see docs/arkarium/REFACTOR_PLAN.md)
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
