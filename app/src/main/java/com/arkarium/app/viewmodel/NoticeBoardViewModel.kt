package com.arkarium.app.viewmodel

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.arkarium.app.data.AppDatabase
import com.arkarium.app.data.ChapterUpdateEventEntity
import kotlinx.coroutines.launch

// Backs Notice Board's Pigeon Rally page (docs/proposal/notice-board.md) - the
// persistent, reverse-chronological "this fiction got new chapters" log.
// chapter_update_events is written elsewhere (NewChapterCheckWorker.checkOneNovel for
// the background path, SyncViewModel.checkForUpdates for the manual "Check for
// updates" action); this class only ever reads it back and flips `seen`.
//
// mutableStateListOf/mutableIntStateOf, not a Flow<...> collected from
// ChapterUpdateEventDao - same convention LibraryViewModel's class doc comment
// explains for `novels`/`chapters`/etc: every field here is refreshed wholesale by
// re-querying after a write, which is simpler than wiring a Room Flow through
// collectAsStateWithLifecycle for a screen this is the only consumer of.
class NoticeBoardViewModel(private val db: AppDatabase) : ViewModel() {

    // Newest first (same ORDER BY as the DAO query) - the whole list this page shows.
    // A capped LIMIT (ChapterUpdateEventDao.recent's default) rather than literally
    // unbounded: this is a glanceable activity log, not an archive meant to be
    // scrolled back through indefinitely.
    val events = mutableStateListOf<ChapterUpdateEventEntity>()

    // Backs the badge on the Pigeon Rally note (NoticeBoardScreen) and, later, the
    // bell on HomeScreen's top bar - see notice-board.md's "Entry point" bullet.
    val unseenCount = mutableIntStateOf(0)

    // Called whenever Notice Board or Pigeon Rally opens. Deliberately does NOT mark
    // anything seen - see ChapterUpdateEventEntity's doc comment: only a row actually
    // being tapped, or "Mark all read", does that. This just re-reads both fields from
    // the DB so a background-worker-written row (or another screen's markSeen/
    // markAllSeen call) shows up.
    fun refresh() {
        viewModelScope.launch {
            val recent = db.chapterUpdateEventDao().recent()
            events.clear()
            events.addAll(recent)
            unseenCount.intValue = db.chapterUpdateEventDao().unseenCount()
        }
    }

    // Tapping a row (PigeonRallyScreen.onEventClick) - marks it read, then refreshes
    // both fields so the badge count and this row's own look (ScrollRow's
    // seen/unseen styling) update together rather than the badge lagging a
    // recomposition behind.
    fun markSeen(id: String) {
        viewModelScope.launch {
            db.chapterUpdateEventDao().markSeen(id)
            refresh()
        }
    }

    // "Mark all read" - the cheap escape hatch notice-board.md calls for, clearing a
    // whole backlog without opening each row individually.
    fun markAllSeen() {
        viewModelScope.launch {
            db.chapterUpdateEventDao().markAllSeen()
            refresh()
        }
    }

    companion object {
        fun factory(db: AppDatabase): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return NoticeBoardViewModel(db) as T
                }
            }
    }
}
