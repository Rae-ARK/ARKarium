package com.arkarium.app.viewmodel

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.arkarium.app.data.AppDatabase
import com.arkarium.app.data.AuthorEntity
import com.arkarium.app.data.ChapterContentRepository
import com.arkarium.app.data.ChapterLoadResult
import com.arkarium.app.data.ReaderPosition
import com.arkarium.app.data.paragraphForCharOffset
import com.arkarium.app.data.paragraphOffsets

// Finishes the MainActivity extraction (design-notes.md, Phase 2): the "reader/{novelId}/
// {chapterId}" route used to resolve its own data inline - author lookup, chapter text
// load, saved-position lookup/migration - straight against `db` and `contentRepo` from
// inside the NavHost. Those three reads live here now; the route only calls them from
// its LaunchedEffects and renders what comes back.
//
// Deliberately does NOT own "chapter missing, reload the novel's chapters" - that needs
// LibraryViewModel.loadNovelDetails/chapters, and a ViewModel must not reach into another
// ViewModel (design-notes.md), so it stayed on LibraryViewModel as restoreMissingChapter.
//
// `contentRepo` is typed as the ChapterContentRepository interface (a real seam exists
// here, unlike db) so a test can pass a fake. MainActivity builds the concrete one with
// applicationContext now that a ViewModel holds it - see onCreate.
class ReaderViewModel(
    private val db: AppDatabase,
    private val contentRepo: ChapterContentRepository
) : ViewModel() {

    // Author of the fiction currently open in the reader (moved from MainActivity).
    // Null covers both "not resolved yet" and "this fiction has no linked author".
    private val _readerAuthor = mutableStateOf<AuthorEntity?>(null)
    val readerAuthor: State<AuthorEntity?> = _readerAuthor

    // Resolved once per novel, not per chapter - a Previous/Next hop shouldn't
    // re-fetch it. A null authorId (or a novel not found) clears it, same as the old
    // `novel?.authorId?.let { db.authorDao().findById(it) }`.
    suspend fun loadReaderAuthor(authorId: String?) {
        _readerAuthor.value = authorId?.let { db.authorDao().findById(it) }
    }

    suspend fun loadChapterContent(sourcePath: String): ChapterLoadResult =
        contentRepo.getTextContent(sourcePath)

    // Moved verbatim from the reader route. Only restorable when the saved row is for
    // *this* chapter (reading_progress is one row per novel). A CHAR_OFFSET row is
    // used as-is. A legacy PERCENTAGE row - anything written before rung 14 - is
    // converted once, in place: `fraction x raw chapter length` is only an estimate, but
    // paragraphForCharOffset snaps it to a real paragraph start the same way a genuine
    // CHAR_OFFSET restore does, and the converted row is written straight back as
    // CHAR_OFFSET so this is a one-time migration per chapter, not a re-estimate on every
    // open (see docs/arkarium/proposal/bugs/other/README.md).
    // Returns null for "nothing to restore".
    suspend fun resolveInitialPosition(novelId: String, chapterId: String, content: String): ReaderPosition? {
        val saved = db.readingProgressDao().forNovel(novelId)
        return if (saved == null || saved.chapterId != chapterId) {
            null
        } else if (saved.positionType == ReaderPosition.POSITION_TYPE) {
            ReaderPosition(saved.position.toInt(), saved.positionSnippet ?: "")
        } else if (content.isNotEmpty()) {
            val approxOffset = (saved.position.coerceIn(0f, 1f) * content.length).toInt()
            val paragraph = paragraphForCharOffset(paragraphOffsets(content), approxOffset)
            if (paragraph != null) {
                val converted = ReaderPosition(
                    paragraph.rawStart,
                    ReaderPosition.snippetFrom(content, paragraph.rawStart)
                )
                db.readingProgressDao().upsert(
                    saved.copy(
                        position = converted.charOffset.toFloat(),
                        positionType = ReaderPosition.POSITION_TYPE,
                        positionSnippet = converted.snippet
                    )
                )
                converted
            } else {
                null
            }
        } else {
            null
        }
    }

    companion object {
        // Same manual-Factory shape as the other ViewModels (rule 4).
        fun factory(db: AppDatabase, contentRepo: ChapterContentRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ReaderViewModel(db, contentRepo) as T
                }
            }
    }
}
