package com.arkarium.app.viewmodel

import com.arkarium.app.data.AppDatabase
import com.arkarium.app.data.PreferencesManager
import com.arkarium.app.data.ScannerImpl
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

// Rung 4.2 of docs/implementation/test-coverage.md. Deliberately narrow: of
// LibraryViewModel's whole public surface, only reportScanSetupError is covered here.
//
// Why not more:
// - startScan / loadNovelDetails need a real DocumentFile tree and a real Room
//   database - out of scope per the ladder, same tier applyMetadata was left at for
//   rung 4.1's MetadataViewModelTest.
// - refreshRecentlyRead only touches Room, not DocumentFile, so it looked like it
//   might be the one other function cheap enough to add here - but "cheap" would mean
//   an in-memory AppDatabase, and even Room.inMemoryDatabaseBuilder(...) still needs a
//   real Context to call it with. That's exactly the hole design-notes.md's Testing
//   strategy section already named ("no in-memory Room without Robolectric, which is
//   deliberately not adopted"), so refreshRecentlyRead stays deferred, not added here.
//
// reportScanSetupError is testable at all despite LibraryViewModel's constructor
// taking concrete AppDatabase/ScannerImpl (no interface seam - see design-notes.md,
// "ViewModels take interfaces only where a seam exists") only because its own body
// never reaches into either one: it just writes this ViewModel's own scanProgress/
// scanMessage state (see its doc comment on LibraryViewModel). The db/scanner mocks
// below exist purely to satisfy the constructor - neither test ever stubs a return
// value or asserts a call happened; verifyNoInteractions instead asserts the opposite,
// that db and scanner are never touched by reportScanSetupError at all.
class LibraryViewModelTest {

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
    fun `reportScanSetupError sets scanMessage and clears scanProgress`() = runTest(testDispatcher) {
        val db = mock(AppDatabase::class.java)
        val scanner = mock(ScannerImpl::class.java)
        val prefsManager = mock(PreferencesManager::class.java)
        val viewModel = LibraryViewModel(db, scanner, prefsManager)

        viewModel.reportScanSetupError("Couldn't open the library folder")

        assertEquals("Couldn't open the library folder", viewModel.scanMessage.value)
        assertNull(viewModel.scanProgress.value)
        verifyNoInteractions(db, scanner, prefsManager)
    }

    @Test
    fun `reportScanSetupError on a second call replaces the first message`() = runTest(testDispatcher) {
        val db = mock(AppDatabase::class.java)
        val scanner = mock(ScannerImpl::class.java)
        val prefsManager = mock(PreferencesManager::class.java)
        val viewModel = LibraryViewModel(db, scanner, prefsManager)

        viewModel.reportScanSetupError("first failure")
        viewModel.reportScanSetupError("second failure")

        assertEquals("second failure", viewModel.scanMessage.value)
        assertNull(viewModel.scanProgress.value)
        verifyNoInteractions(db, scanner, prefsManager)
    }
}
