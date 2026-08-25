package com.arkarium.app.viewmodel

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.arkarium.app.data.AppDatabase
import com.arkarium.app.data.NovelEntity
import com.arkarium.app.data.NovelMetadataCandidate
import com.arkarium.app.data.NovelMetadataProvider
import com.arkarium.app.navigation.AddFictionState
import com.arkarium.app.navigation.MetadataSearchState
import kotlinx.coroutines.launch

// Stage 2.4 of Phase 2 (docs/arkarium/REFACTOR_PLAN.md) - the third of the plan's four
// feature-scoped ViewModels pulled out of MainActivity. Owns metadataSearchState and
// addFictionState (the two dialog-driving sealed states this stage is scoped to) plus
// fetchMetadataFor/applyMetadata, the "fetch/apply-metadata logic" the plan names -
// the only functions that ever write metadataSearchState based on a completed async
// result rather than a direct UI dismiss/click. addFictionByName - the other writer of
// addFictionState - deliberately stays on MainActivity: unlike fetchMetadataFor/
// applyMetadata it doesn't call GoogleBooksMetadataProvider at all, it calls
// SyncManager/FictionLut (Stage 2.5's territory) and libraryViewModel.startScan, so
// moving it here would just relocate Sync logic through a Metadata-named door. It
// keeps writing metadataViewModel.addFictionState.value directly, the same "state
// lives on the ViewModel, an external writer sets it via its public property" shape
// MainActivity's own currentScreen already uses for navigation.
//
// Takes NovelMetadataProvider (the interface GoogleBooksMetadataProvider implements),
// not the concrete class - same reasoning as SettingsViewModel taking
// SettingsPreferences over PreferencesManager: MetadataViewModelTest can hand
// fetchMetadataFor a fake backed by a plain suspend lambda, no network involved. `db`
// stays concrete AppDatabase, matching LibraryViewModel's precedent (Room has no
// interface split in this codebase yet, and Room/DocumentFile logic isn't unit-testable
// without Robolectric either way) - applyMetadata is exercised by hand/instrumented
// tests for now, same tier LibraryViewModel's startScan is in.
//
// Takes LibraryViewModel itself (not just its `novels` list) as a constructor
// dependency, matching the plan's note that Stage 2.5's SyncViewModel will need the
// same reference for its own novel-list reads/writes - one shared "the ViewModel that
// owns the library's novel list" dependency shape across both remaining stages, rather
// than each stage inventing its own way to reach `novels`.
class MetadataViewModel(
    private val db: AppDatabase,
    private val metadataProvider: NovelMetadataProvider,
    private val libraryViewModel: LibraryViewModel
) : ViewModel() {

    val metadataSearchState = mutableStateOf<MetadataSearchState>(MetadataSearchState.Idle)
    val addFictionState = mutableStateOf<AddFictionState>(AddFictionState.Hidden)

    // Moved verbatim from MainActivity (Stage 2.4). Kicks off a "Fetch info" search for
    // one novel - user-triggered only (see NovelDetailScreen's info action), never
    // called automatically during a scan.
    fun fetchMetadataFor(novel: NovelEntity) {
        metadataSearchState.value = MetadataSearchState.Loading(novel)
        viewModelScope.launch {
            try {
                val results = metadataProvider.search(novel.title)
                metadataSearchState.value = MetadataSearchState.Results(novel, results)
            } catch (e: Exception) {
                metadataSearchState.value = MetadataSearchState.Error(novel, "Couldn't reach the metadata source: ${e.message}")
            }
        }
    }

    // Moved from MainActivity (Stage 2.4), with one shape change: the original also
    // patched MainActivity's own `currentScreen` when the novel being viewed in
    // NovelDetail was the one just updated, so the screen reflected the new info
    // immediately without navigating away and back. currentScreen is Activity
    // navigation state (Phase 3 - NavHost migration - hasn't happened yet), so this
    // ViewModel has no business writing it directly; `onApplied` is the same
    // "report the result back to the caller via a plain lambda" shape ScannerImpl's
    // onDiscovered/onProgress callbacks already use elsewhere in this codebase; the
    // updated novel is only ever passed to it once the DB write and the
    // libraryViewModel.novels patch have both already landed. MainActivity's call site
    // is the one place that still touches currentScreen for this flow.
    fun applyMetadata(
        novel: NovelEntity,
        candidate: NovelMetadataCandidate,
        onApplied: (NovelEntity) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                db.novelDao().updateMetadata(
                    novelId = novel.id,
                    description = candidate.description,
                    genres = candidate.categories.joinToString(", ").ifBlank { null },
                    remoteCoverUrl = candidate.thumbnailUrl,
                    publishedDate = candidate.publishedDate,
                    externalSourceUrl = candidate.infoLink,
                    fetchedAt = System.currentTimeMillis()
                )
                val updated = db.novelDao().findById(novel.id) ?: return@launch
                val idx = libraryViewModel.novels.indexOfFirst { it.id == updated.id }
                if (idx >= 0) libraryViewModel.novels[idx] = updated
                metadataSearchState.value = MetadataSearchState.Idle
                onApplied(updated)
            } catch (e: Exception) {
                metadataSearchState.value = MetadataSearchState.Error(novel, "Couldn't save the fetched info: ${e.message}")
            }
        }
    }

    companion object {
        // Same manual-Factory shape as SettingsViewModel.factory/LibraryViewModel.factory
        // (rule 4 - no DSL needed for one Factory method). Takes the Activity's existing
        // AppDatabase and libraryViewModel instances rather than constructing its own,
        // same "share the Activity's one instance of each service/ViewModel" pattern the
        // other factories use; the caller constructs GoogleBooksMetadataProvider (it's
        // stateless and can't throw, so there's nothing this factory needs to own about
        // its lifecycle) and passes it in as the NovelMetadataProvider it implements.
        fun factory(
            db: AppDatabase,
            metadataProvider: NovelMetadataProvider,
            libraryViewModel: LibraryViewModel
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MetadataViewModel(db, metadataProvider, libraryViewModel) as T
                }
            }
    }
}
