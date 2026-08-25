package com.arkarium.app.navigation

// Extracted from MainActivity.kt as part of the MainActivity slim-down (see
// docs/arkarium/REFACTOR_PLAN.md). These sealed classes describe *what* the app can be
// showing at any given moment; MainActivity.kt (and eventually a MainViewModel) still
// own *when* each state changes. Splitting the destination/state contracts into their
// own file makes them independently readable and testable without pulling in the
// Activity's Compose/lifecycle/Context dependencies.

import com.arkarium.app.data.NovelEntity
import com.arkarium.app.data.NovelMetadataCandidate

sealed class Screen {
    object Home : Screen()
    // Stage 3.3 (see docs/arkarium/REFACTOR_PLAN.md): carries only novelId, not a full
    // NovelEntity. NovelDetail and ChapterEditor are the first two Screen cases whose
    // destination resolves its own data (via `libraryViewModel.novels.firstOrNull { it.id
    // == novelId }`, same lookup Reader's composable already used for readerAuthor/chapter
    // neighbors) instead of having it handed in as a payload - which is what let Stage
    // 2.4/2.5's onApplied/onUpdated callbacks (MetadataViewModel.applyMetadata,
    // SyncViewModel.checkForUpdates/resolveMissingFolderBySyncing/
    // resolveSourceGoneByUnlinking) drop the currentScreen-patching they existed for: a
    // ViewModel patching its own `novels` SnapshotStateList is now enough on its own for
    // the destination to recompose with the new data. Still used as a plain data value by
    // Screen.Author's `from` field (Author hasn't migrated off currentScreen-based
    // routing yet - that's Stage 3.4), just no longer as a currentScreen case itself.
    data class NovelDetail(val novelId: String) : Screen()
    // Stage 3.4 (see docs/arkarium/REFACTOR_PLAN.md): carries only novelId/chapterId,
    // same "resolve it yourself from libraryViewModel" shape Stage 3.3 gave NovelDetail/
    // ChapterEditor above - the reader/{novelId}/{chapterId} destination looks up the
    // ChapterEntity and loads its body text itself instead of having either handed in
    // as Screen payload. No longer constructed anywhere (the NavHost route below is
    // reached via navController.navigate("reader/...") directly) - kept only so this
    // sealed class' one remaining exhaustive `when` (MainActivity's now-fully-dead
    // "legacy" branch) still compiles until Stage 3.5's cleanup removes Screen entirely.
    data class Reader(val novelId: String, val chapterId: String) : Screen()
    data class ChapterEditor(val novelId: String) : Screen()
    // Stage 3.4 (see docs/arkarium/REFACTOR_PLAN.md): `from` is dropped - it existed
    // purely so onBack knew whether to return to the fiction page byline or the
    // reader's "About the author" card, which NavController's own back stack now
    // handles for free via a plain navController.popBackStack(). Same "no longer
    // constructed anywhere, kept only for `when` exhaustiveness" status as Reader above.
    data class Author(val authorId: String) : Screen()
    object Settings : Screen()
    // initialQuery seeds FictionBrowseScreen's own search field - see Home's onSearch
    // below. Previously this was `object FictionBrowse`, so the text typed into Home's
    // search bar had nowhere to go and was silently discarded on navigation; the browse
    // screen always opened with an empty query even though its own title/author filter
    // already worked fine once you retyped it there.
    data class FictionBrowse(val initialQuery: String = "") : Screen()
    object PrivacyPolicy : Screen()
    object TermsAndConditions : Screen()
    object AboutMe : Screen()
}

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
