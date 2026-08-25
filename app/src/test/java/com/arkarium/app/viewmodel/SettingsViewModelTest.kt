package com.arkarium.app.viewmodel

import com.arkarium.app.data.SettingsPreferences
import com.arkarium.app.data.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// SettingsViewModel depends on SettingsPreferences (an interface implemented by
// PreferencesManager, see data/PreferencesManager.kt), not the concrete class -
// specifically so it can be driven here by a fake backed by plain MutableStateFlows,
// with no DataStore, Context, or Robolectric involved. See docs/arkarium/REFACTOR_PLAN.md
// Stage 2.2.
private class FakeSettingsPreferences(
    initialTheme: Theme = Theme.LIGHT,
    initialSystemDefaultLightVariant: Theme = Theme.LIGHT,
    initialUseCustomFolder: Boolean = false,
    initialLibraryUri: String? = null
) : SettingsPreferences {
    private val themeFlow = MutableStateFlow(initialTheme)
    private val systemDefaultLightVariantFlow = MutableStateFlow(initialSystemDefaultLightVariant)
    private val useCustomFolderFlow = MutableStateFlow(initialUseCustomFolder)
    private val libraryUriFlow = MutableStateFlow(initialLibraryUri)

    override val theme: Flow<Theme> get() = themeFlow
    override val systemDefaultLightVariant: Flow<Theme> get() = systemDefaultLightVariantFlow
    override val useCustomFolder: Flow<Boolean> get() = useCustomFolderFlow
    override val libraryUri: Flow<String?> get() = libraryUriFlow

    override suspend fun setTheme(theme: Theme) {
        themeFlow.value = theme
    }

    override suspend fun setSystemDefaultLightVariant(variant: Theme) {
        systemDefaultLightVariantFlow.value = variant
    }

    override suspend fun setUseCustomFolder(enabled: Boolean) {
        useCustomFolderFlow.value = enabled
    }

    override suspend fun setLibraryUri(uri: String) {
        libraryUriFlow.value = uri
    }
}

class SettingsViewModelTest {

    // Shared between Dispatchers.setMain below and runTest(testDispatcher) in each
    // test, so viewModelScope's Dispatchers.Main.immediate work and the test's own
    // advanceUntilIdle() calls run against the same virtual-time scheduler - without
    // this, advanceUntilIdle() wouldn't advance the coroutines SettingsViewModel
    // launches internally (stateIn's upstream collection, setTheme's viewModelScope.
    // launch, etc.), since they'd be queued on a different scheduler entirely.
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // Each StateFlow is built with SharingStarted.WhileSubscribed(...), so - same as
    // any real StateFlow built that way - it only starts collecting its upstream Flow
    // once something subscribes; reading .value beforehand would just see the
    // constructor's hardcoded default. These background collectors are the test's
    // stand-in for "a composable calling collectAsState()".
    private fun subscribeToAll(viewModel: SettingsViewModel, scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch { viewModel.currentTheme.collect {} }
        scope.launch { viewModel.currentSystemDefaultLightVariant.collect {} }
        scope.launch { viewModel.useCustomFolder.collect {} }
        scope.launch { viewModel.savedUri.collect {} }
    }

    @Test
    fun `once subscribed, exposes SettingsPreferences' current values`() = runTest(testDispatcher) {
        val prefs = FakeSettingsPreferences(
            initialTheme = Theme.DARK,
            initialSystemDefaultLightVariant = Theme.WARM_PAPER,
            initialUseCustomFolder = true,
            initialLibraryUri = "content://tree/abc"
        )
        val viewModel = SettingsViewModel(prefs)
        subscribeToAll(viewModel, backgroundScope)
        advanceUntilIdle()

        assertEquals(Theme.DARK, viewModel.currentTheme.value)
        assertEquals(Theme.WARM_PAPER, viewModel.currentSystemDefaultLightVariant.value)
        assertEquals(true, viewModel.useCustomFolder.value)
        assertEquals("content://tree/abc", viewModel.savedUri.value)
    }

    @Test
    fun `setTheme forwards to SettingsPreferences and the StateFlow reflects it`() = runTest(testDispatcher) {
        val prefs = FakeSettingsPreferences()
        val viewModel = SettingsViewModel(prefs)
        subscribeToAll(viewModel, backgroundScope)
        advanceUntilIdle()

        viewModel.setTheme(Theme.DARK)
        advanceUntilIdle()

        assertEquals(Theme.DARK, viewModel.currentTheme.value)
    }

    @Test
    fun `setSystemDefaultLightVariant forwards to SettingsPreferences`() = runTest(testDispatcher) {
        val prefs = FakeSettingsPreferences()
        val viewModel = SettingsViewModel(prefs)
        subscribeToAll(viewModel, backgroundScope)
        advanceUntilIdle()

        viewModel.setSystemDefaultLightVariant(Theme.WARM_PAPER)
        advanceUntilIdle()

        assertEquals(Theme.WARM_PAPER, viewModel.currentSystemDefaultLightVariant.value)
    }

    @Test
    fun `setUseCustomFolder forwards to SettingsPreferences`() = runTest(testDispatcher) {
        val prefs = FakeSettingsPreferences()
        val viewModel = SettingsViewModel(prefs)
        subscribeToAll(viewModel, backgroundScope)
        advanceUntilIdle()

        viewModel.setUseCustomFolder(true)
        advanceUntilIdle()

        assertEquals(true, viewModel.useCustomFolder.value)
    }

    @Test
    fun `setLibraryUri forwards to SettingsPreferences`() = runTest(testDispatcher) {
        val prefs = FakeSettingsPreferences()
        val viewModel = SettingsViewModel(prefs)
        subscribeToAll(viewModel, backgroundScope)
        advanceUntilIdle()

        viewModel.setLibraryUri("content://tree/new-folder")
        advanceUntilIdle()

        assertEquals("content://tree/new-folder", viewModel.savedUri.value)
    }
}
