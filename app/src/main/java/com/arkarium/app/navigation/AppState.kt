package com.arkarium.app.navigation

// Extracted from MainActivity.kt as part of the MainActivity slim-down (see
// docs/arkarium/REFACTOR_PLAN.md). These sealed classes describe *what* the app can be
// showing at any given moment; MainActivity.kt (and eventually a MainViewModel) still
// own *when* each state changes. Splitting the destination/state contracts into their
// own file makes them independently readable and testable without pulling in the
// Activity's Compose/lifecycle/Context dependencies.

import com.arkarium.app.data.NovelEntity
import com.arkarium.app.data.NovelMetadataCandidate

// The Screen sealed class (navigation destinations) that used to live here was removed
// in Stage 3.5 (see docs/arkarium/REFACTOR_PLAN.md) - Stages 3.1-3.4 migrated every
// destination onto NavController-based routes ("home", "novelDetail/{novelId}", etc.),
// after which Screen, MainActivity's currentScreen field, and the "legacy" NavHost
// destination that switched on it were only reachable via a dead `when` branch. Only the
// dialog-driving sealed classes below (MetadataSearchState, AddFictionState, etc.) are
// still in scope for this file - they drive dialogs layered over NavHost's content, not
// Screen destinations, so Phase 3's migration never touched them.

// Drives the "Fetch info" dialog from NovelDetailScreen. Idle = dialog hidden.
sealed class MetadataSearchState {
    object Idle : MetadataSearchState()
    data class Loading(val novel: NovelEntity) : MetadataSearchState()
    data class Results(val novel: NovelEntity, val candidates: List<NovelMetadataCandidate>) : MetadataSearchState()
    data class Error(val novel: NovelEntity, val message: String) : MetadataSearchState()
}

// Drives the "Add fiction" dialog from the home screen icon (see docs/arkarium/SYNC_MVP.md,
// Stage 3, and the later move to single-origin name lookup via FictionLut). Hidden =
// dialog not shown at all; EnteringName = dialog shown with an empty field and nothing
// in flight yet.
sealed class AddFictionState {
    object Hidden : AddFictionState()
    object EnteringName : AddFictionState()
    data class InProgress(val message: String) : AddFictionState()
    data class Error(val message: String) : AddFictionState()
}

// Drives "Sync all Rae ARK's novels" (see docs/arkarium/SYNC_MVP.md, Stage 3, and
// EmptyLibraryPrompt in HomeScreen.kt). Idle = dialog hidden. Reuses SyncProgressDialog
// (originally built for one novel's "check for updates" pass) by treating the whole
// batch as a single progress stream - it's the same shape (loading -> done/error), just
// with a "1/5: ..." style message instead of a single file's.
sealed class SyncAllState {
    object Idle : SyncAllState()
    data class InProgress(val message: String) : SyncAllState()
    data class Done(val message: String) : SyncAllState()
    data class Error(val message: String) : SyncAllState()
}

// Drives the "Check for updates" progress dialog from NovelDetailScreen (see
// docs/arkarium/SYNC_MVP.md, Stage 3). Idle = dialog hidden.
sealed class SyncCheckState {
    object Idle : SyncCheckState()
    data class InProgress(val novel: NovelEntity, val message: String) : SyncCheckState()
    data class Done(val novel: NovelEntity, val message: String) : SyncCheckState()
    data class Error(val novel: NovelEntity, val message: String) : SyncCheckState()
}

// See docs/arkarium/NEXT_FIXES.md #2. Drives SyncResolutionDialog, shown when checkForUpdates
// hits a situation that shouldn't be resolved silently in either direction: the
// synced novel's local folder is gone (MISSING_LOCALLY - resolved by resyncing or
// removing the novel) or the relay no longer serves it (SOURCE_GONE - resolved by
// unlinking, since local content stays readable either way).
enum class SyncResolutionReason { MISSING_LOCALLY, SOURCE_GONE }

sealed class SyncResolutionState {
    object Idle : SyncResolutionState()
    data class NeedsResolution(val novel: NovelEntity, val reason: SyncResolutionReason) : SyncResolutionState()
}
