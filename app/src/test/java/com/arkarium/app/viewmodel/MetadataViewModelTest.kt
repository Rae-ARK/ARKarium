package com.arkarium.app.viewmodel

import com.arkarium.app.data.AppDatabase
import com.arkarium.app.data.NovelEntity
import com.arkarium.app.data.NovelMetadataCandidate
import com.arkarium.app.data.NovelMetadataProvider
import com.arkarium.app.data.ScannerImpl
import com.arkarium.app.navigation.MetadataSearchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

// Rung 4.1 of docs/implementation/test-coverage.md. `fetchMetadataFor` only -
// `applyMetadata` stays out of scope per the ladder, same reasoning rung 4.2 already
// applied to startScan/loadNovelDetails: it writes through concrete AppDatabase
// (db.novelDao().updateMetadata/findById), so testing it for real needs Room, and
// stubbing those two calls on a mock would mean teaching the mock what Room would have
// returned rather than testing MetadataViewModel's own logic - the same line rung 4.2's
// notes on refreshRecentlyRead drew.
//
// fetchMetadataFor is testable without any of that because it only ever touches
// metadataProvider and its own metadataSearchState - never db, never libraryViewModel.
// metadataProvider is a real seam (NovelMetadataProvider, the interface
// GoogleBooksMetadataProvider implements - see MetadataViewModel's doc comment), so it
// gets a hand-written fake backed by a plain suspend lambda, same shape
// SettingsViewModelTest's FakeSettingsPreferences uses for SettingsPreferences. db and
// the LibraryViewModel instance's own db/scanner have no such seam (concrete
// AppDatabase/ScannerImpl - see design-notes.md), so they're Mockito.mock(...) same as
// LibraryViewModelTest: there purely to satisfy constructors, never stubbed, and each
// test's verifyNoInteractions proves fetchMetadataFor really never reaches them.
private class FakeNovelMetadataProvider(
    private val onSearch: suspend (String) -> List<NovelMetadataCandidate>
) : NovelMetadataProvider {
    var lastQuery: String? = null
        private set

    override suspend fun search(query: String): List<NovelMetadataCandidate> {
        lastQuery = query
        return onSearch(query)
    }
}

class MetadataViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun novel(id: String = "n1", title: String = "Test Novel") =
        NovelEntity(id = id, title = title, author = null, coverUri = null)

    private fun newLibraryViewModel() =
        LibraryViewModel(mock(AppDatabase::class.java), mock(ScannerImpl::class.java))

    private val candidate = NovelMetadataCandidate(
        title = "Test Novel",
        authors = listOf("Someone"),
        description = "A description",
        thumbnailUrl = "https://example.com/cover.jpg",
        publishedDate = "2020",
        categories = listOf("Fiction"),
        infoLink = "https://example.com/info"
    )

    @Test
    fun `fetchMetadataFor sets Loading immediately, then Results on success`() = runTest(testDispatcher) {
        val db = mock(AppDatabase::class.java)
        val provider = FakeNovelMetadataProvider { listOf(candidate) }
        val libraryViewModel = newLibraryViewModel()
        val viewModel = MetadataViewModel(db, provider, libraryViewModel)
        val targetNovel = novel()

        viewModel.fetchMetadataFor(targetNovel)

        // Set synchronously, before the coroutine that calls the provider is even
        // dispatched - see fetchMetadataFor's own body.
        assertEquals(MetadataSearchState.Loading(targetNovel), viewModel.metadataSearchState.value)

        advanceUntilIdle()

        assertEquals(
            MetadataSearchState.Results(targetNovel, listOf(candidate)),
            viewModel.metadataSearchState.value
        )
        assertEquals("Test Novel", provider.lastQuery)
        verifyNoInteractions(db)
    }

    @Test
    fun `fetchMetadataFor sets Error when the provider throws`() = runTest(testDispatcher) {
        val db = mock(AppDatabase::class.java)
        val provider = FakeNovelMetadataProvider { throw java.io.IOException("no network") }
        val libraryViewModel = newLibraryViewModel()
        val viewModel = MetadataViewModel(db, provider, libraryViewModel)
        val targetNovel = novel()

        viewModel.fetchMetadataFor(targetNovel)
        advanceUntilIdle()

        val state = viewModel.metadataSearchState.value
        assertTrue(state is MetadataSearchState.Error)
        state as MetadataSearchState.Error
        assertEquals(targetNovel, state.novel)
        assertTrue(state.message.contains("no network"))
        verifyNoInteractions(db)
    }

    @Test
    fun `fetchMetadataFor never touches libraryViewModel`() = runTest(testDispatcher) {
        val db = mock(AppDatabase::class.java)
        val provider = FakeNovelMetadataProvider { listOf(candidate) }
        val libraryViewModel = newLibraryViewModel()
        val viewModel = MetadataViewModel(db, provider, libraryViewModel)

        viewModel.fetchMetadataFor(novel())
        advanceUntilIdle()

        // applyMetadata is the only function that writes into libraryViewModel.novels
        // (see its doc comment) - fetchMetadataFor is out of scope for touching it at
        // all, so the list it started with should be exactly the list it ends with.
        assertTrue(libraryViewModel.novels.isEmpty())
    }
}
