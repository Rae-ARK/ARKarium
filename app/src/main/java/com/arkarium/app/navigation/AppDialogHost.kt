package com.arkarium.app.navigation

// Phase A, rung A1 of docs/arkarium/implementation/ui-layer-reorg.md - the metadata/sync
// dialog `when` blocks that used to sit inline at the end of MainActivity's
// renderMainContent(), as five siblings of the top-level Scaffold. Pure extraction: same
// five `when` blocks, same state reads, same callbacks, just gathered into one
// `@Composable` so MainActivity's `setContent {}` calls `AppDialogHost(...)` once instead
// of repeating all five inline. No dialog's conditions, dismiss behavior, or ViewModel
// writes changed - see that doc for the rest of the ladder this rung belongs to.

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import com.arkarium.app.data.resolveLibraryRoot
import com.arkarium.app.ui.AddFictionByNameDialog
import com.arkarium.app.ui.MetadataSearchDialog
import com.arkarium.app.ui.SyncProgressDialog
import com.arkarium.app.ui.SyncResolutionDialog
import com.arkarium.app.viewmodel.MetadataViewModel
import com.arkarium.app.viewmodel.SyncViewModel

// Takes the two ViewModels these dialogs drive off, plus the handful of fields the
// dialogs' own callbacks need (context for resolveLibraryRoot, useCustomFolder/savedUri
// for the library root it resolves against, currentNovelDetailId for the two Bug-4-fix
// "is this dialog's novel actually the one on screen" checks) - same shape every other
// extracted route in this ladder takes its data as parameters rather than reaching back
// into an Activity.
@Composable
fun AppDialogHost(
    metadataViewModel: MetadataViewModel,
    syncViewModel: SyncViewModel,
    currentNovelDetailId: String?,
    useCustomFolder: State<Boolean>,
    savedUri: State<String?>,
    context: Context
) {
    when (val state = metadataViewModel.metadataSearchState.value) {
        is MetadataSearchState.Loading -> {
            // Loading has no novel.id to compare against the active route yet
            // (the search just started), and no apply action to misattribute -
            // safe to always show, same as before this fix.
            MetadataSearchDialog(
                novelTitle = state.novel.title,
                isLoading = true,
                errorMessage = null,
                candidates = emptyList(),
                onCandidateSelected = {},
                onDismiss = { metadataViewModel.metadataSearchState.value = MetadataSearchState.Idle }
            )
        }
        is MetadataSearchState.Results -> {
            // Bug 4 fix: only render - and only allow applying - a Results
            // dialog for the novel that's actually on screen right now. If the
            // user navigated to a different novel (or away entirely) before
            // this resolved, silently drop it instead of popping it up over
            // whatever they're looking at now; they can just hit "Fetch info"
            // again from that novel's own page if they want it.
            if (state.novel.id == currentNovelDetailId) {
                MetadataSearchDialog(
                    novelTitle = state.novel.title,
                    isLoading = false,
                    errorMessage = null,
                    candidates = state.candidates,
                    // The currently-open "novelDetail/{novelId}" route (if this is
                    // the novel it's showing) picks up the new info on its own,
                    // via the libraryViewModel.novels patch applyMetadata already
                    // does - Stage 3.3 (see docs/arkarium/foundational/design-notes.md) dropped
                    // the onApplied callback this call site used to pass to also
                    // patch currentScreen for the same reason.
                    onCandidateSelected = { candidate ->
                        metadataViewModel.applyMetadata(state.novel, candidate)
                    },
                    onDismiss = { metadataViewModel.metadataSearchState.value = MetadataSearchState.Idle }
                )
            } else {
                LaunchedEffect(state.novel.id) {
                    metadataViewModel.metadataSearchState.value = MetadataSearchState.Idle
                }
            }
        }
        is MetadataSearchState.Error -> {
            // Bug 4 fix: same reasoning as Results above - a stale "couldn't
            // reach metadata source" dialog shouldn't surface long after the
            // user has moved on to a different novel.
            if (state.novel.id == currentNovelDetailId) {
                MetadataSearchDialog(
                    novelTitle = state.novel.title,
                    isLoading = false,
                    errorMessage = state.message,
                    candidates = emptyList(),
                    onCandidateSelected = {},
                    onDismiss = { metadataViewModel.metadataSearchState.value = MetadataSearchState.Idle }
                )
            } else {
                LaunchedEffect(state.novel.id) {
                    metadataViewModel.metadataSearchState.value = MetadataSearchState.Idle
                }
            }
        }
        is MetadataSearchState.Idle -> {}
    }

    // "Add fiction" (home screen icon) and "Check for updates"
    // (NovelDetailScreen) - see docs/arkarium/foundational/architecture.md, Sync: entry points, and the later
    // move to single-origin name lookup via FictionLut.
    when (val state = metadataViewModel.addFictionState.value) {
        AddFictionState.Hidden -> {}
        AddFictionState.EnteringName -> {
            AddFictionByNameDialog(
                isLoading = false,
                progressMessage = "",
                errorMessage = null,
                onConfirm = { name ->
                    val root = resolveLibraryRoot(context, useCustomFolder.value, savedUri.value)
                    if (root != null) {
                        syncViewModel.addFictionByName(name, root)
                    } else {
                        metadataViewModel.addFictionState.value =
                            AddFictionState.Error("No library folder is set up yet - pick one in Settings first.")
                    }
                },
                onDismiss = { metadataViewModel.addFictionState.value = AddFictionState.Hidden }
            )
        }
        is AddFictionState.InProgress -> {
            AddFictionByNameDialog(
                isLoading = true,
                progressMessage = state.message,
                errorMessage = null,
                onConfirm = {},
                onDismiss = {}
            )
        }
        is AddFictionState.Error -> {
            AddFictionByNameDialog(
                isLoading = false,
                progressMessage = "",
                errorMessage = state.message,
                onConfirm = { name ->
                    val root = resolveLibraryRoot(context, useCustomFolder.value, savedUri.value)
                    if (root != null) {
                        syncViewModel.addFictionByName(name, root)
                    } else {
                        metadataViewModel.addFictionState.value =
                            AddFictionState.Error("No library folder is set up yet - pick one in Settings first.")
                    }
                },
                onDismiss = { metadataViewModel.addFictionState.value = AddFictionState.Hidden }
            )
        }
    }

    // "Sync all Rae ARK's novels" (EmptyLibraryPrompt's primary first-run
    // action, see HomeScreen.kt / syncAllRaeArkNovels above). Reuses
    // SyncProgressDialog, same as the per-novel "Check for updates" dialog
    // below - just with "Rae ARK's novels" standing in for a single title.
    // coverUrl comes from SyncAllState.InProgress/Done's own coverUrl field -
    // the most-recently-synced novel's cover in this batch, carried forward by
    // SyncViewModel.syncAllRaeArkNovels (see that function and SyncAllState's
    // doc comment). Null (falling back to the placeholder) only until the
    // first novel in the batch actually finishes.
    when (val state = syncViewModel.syncAllState.value) {
        SyncAllState.Idle -> {}
        is SyncAllState.InProgress -> {
            SyncProgressDialog(
                novelTitle = "Rae ARK's novels",
                coverUrl = state.coverUrl,
                isLoading = true,
                message = state.message,
                errorMessage = null,
                onDismiss = {}
            )
        }
        is SyncAllState.Done -> {
            SyncProgressDialog(
                novelTitle = "Rae ARK's novels",
                coverUrl = state.coverUrl,
                isLoading = false,
                message = state.message,
                errorMessage = null,
                onDismiss = { syncViewModel.syncAllState.value = SyncAllState.Idle }
            )
        }
        is SyncAllState.Error -> {
            SyncProgressDialog(
                novelTitle = "Rae ARK's novels",
                isLoading = false,
                message = "",
                errorMessage = state.message,
                onDismiss = { syncViewModel.syncAllState.value = SyncAllState.Idle }
            )
        }
    }

    when (val state = syncViewModel.syncCheckState.value) {
        SyncCheckState.Idle -> {}
        is SyncCheckState.InProgress -> {
            SyncProgressDialog(
                novelTitle = state.novel.title,
                // Local cover.* first, remote-fetched cover as fallback -
                // same resolution order NovelCardVertical/NovelContinueCard
                // already use elsewhere, so the dialog shows the same art
                // the reader already recognizes from the library grid.
                coverUrl = state.novel.coverUri ?: state.novel.remoteCoverUrl,
                isLoading = true,
                message = state.message,
                errorMessage = null,
                onDismiss = {}
            )
        }
        is SyncCheckState.Done -> {
            SyncProgressDialog(
                novelTitle = state.novel.title,
                coverUrl = state.novel.coverUri ?: state.novel.remoteCoverUrl,
                isLoading = false,
                message = state.message,
                errorMessage = null,
                onDismiss = { syncViewModel.syncCheckState.value = SyncCheckState.Idle }
            )
        }
        is SyncCheckState.Error -> {
            SyncProgressDialog(
                novelTitle = state.novel.title,
                coverUrl = state.novel.coverUri ?: state.novel.remoteCoverUrl,
                isLoading = false,
                message = "",
                errorMessage = state.message,
                onDismiss = { syncViewModel.syncCheckState.value = SyncCheckState.Idle }
            )
        }
    }

    // See docs/arkarium/foundational/architecture.md, Sync invariants S6 - offered whenever checkForUpdates hits a
    // missing-local-folder or source-gone situation, in place of silently
    // resolving either one.
    when (val state = syncViewModel.syncResolutionState.value) {
        SyncResolutionState.Idle -> {}
        is SyncResolutionState.NeedsResolution -> {
            // Stage 3.3 (see docs/arkarium/foundational/design-notes.md) dropped all three
            // onUpdated/onRemoved callbacks these three resolve* calls used to
            // take: the "novelDetail/{novelId}" route this dialog can fire on
            // top of resolves its novel from libraryViewModel.novels on every
            // recomposition, so a resync/unlink's `novels` patch is picked up
            // on its own, and a removal resolves to a null novel there, which
            // that route's own LaunchedEffect already sends back to Home for.
            SyncResolutionDialog(
                novelTitle = state.novel.title,
                isMissingLocally = state.reason == SyncResolutionReason.MISSING_LOCALLY,
                onSyncAgain = {
                    resolveLibraryRoot(context, useCustomFolder.value, savedUri.value)?.let { root ->
                        syncViewModel.resolveMissingFolderBySyncing(state.novel, root)
                    }
                },
                onRemoveFromLibrary = {
                    syncViewModel.resolveByRemovingFromLibrary(state.novel)
                },
                onUnlink = {
                    syncViewModel.resolveSourceGoneByUnlinking(state.novel)
                },
                onDismiss = { syncViewModel.syncResolutionState.value = SyncResolutionState.Idle }
            )
        }
    }
}
