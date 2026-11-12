package com.arkarium.app.viewmodel

import com.arkarium.app.data.AppDatabase
import com.arkarium.app.data.ChapterContent
import com.arkarium.app.data.ChapterContentRepository
import com.arkarium.app.data.ChapterLoadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

// Same narrow shape as LibraryViewModelTest: only the ReaderViewModel paths that don't
// need a real Room database. resolveInitialPosition and a non-null loadReaderAuthor read
// DAOs, so they stay deferred with the rest of the db-backed surface (design-notes.md,
// "no in-memory Room without Robolectric").
class ReaderViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadReaderAuthor with no authorId leaves readerAuthor null without touching db`() = runTest(testDispatcher) {
        val db = mock(AppDatabase::class.java)
        val viewModel = ReaderViewModel(db, FakeContentRepo(ChapterLoadResult.Failed("unused")))

        viewModel.loadReaderAuthor(null)

        assertNull(viewModel.readerAuthor.value)
        verifyNoInteractions(db)
    }

    @Test
    fun `loadChapterContent returns whatever the content repository returns`() = runTest(testDispatcher) {
        val db = mock(AppDatabase::class.java)
        val ok = ChapterLoadResult.Ok(ChapterContent.Text("Chapter body"))
        val repo = FakeContentRepo(ok)
        val viewModel = ReaderViewModel(db, repo)

        val result = viewModel.loadChapterContent("content://novel/ch1.txt")

        assertEquals(ok, result)
        assertEquals("content://novel/ch1.txt", repo.lastSourcePath)
        verifyNoInteractions(db)
    }

    private class FakeContentRepo(private val result: ChapterLoadResult) : ChapterContentRepository {
        var lastSourcePath: String? = null
        override suspend fun getTextContent(sourcePath: String): ChapterLoadResult {
            lastSourcePath = sourcePath
            return result
        }
    }
}
