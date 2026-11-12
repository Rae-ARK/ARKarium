package com.arkarium.app.viewmodel

import com.arkarium.app.data.SettingsPreferences
import com.arkarium.app.data.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
// with no DataStore, Context, or Robolectric involved. See docs/arkarium/foundational/design-notes.md
// Stage 2.2.
//
// useCustomFolder/libraryUri were dropped from SettingsPreferences by
// docs/arkarium/proposal/remove-saf-support.md ("accepted") - shouldShowSafRemovedNotice/
// dismissSafRemovedNotice() took their place, so this fake and the tests below follow suit.
private class FakeSettingsPreferences(
    initialTheme: Theme = Theme.LIGHT,
    initialSystemDefaultLightVariant: Theme = Theme.LIGHT,
    initialShouldShowSafRemovedNotice: Boolean = false
) : SettingsPreferences {
    private val themeFlow = MutableStateFlow(initialTheme)
    private val systemDefaultLightVariantFlow = MutableStateFlow(initialSystemDefaultLightVariant)
    private val shouldShowSafRemovedNoticeFlow = MutableStateFlow(initialShouldShowSafRemovedNotice)

    override val theme: Flow<Theme> get() = themeFlow
    override val systemDefaultLightVariant: Flow<Theme> get() = systemDefaultLightVariantFlow
    override val shouldShowSafRemovedNotice: Flow<Boolean> get() = shouldShowSafRemovedNoticeFlow

    override suspend fun setTheme(theme: Theme) {
        themeFlow.value = theme
    }

    override suspend fun setSystemDefaultLightVariant(variant: Theme) {
        systemDefaultLightVariantFlow.value = variant
    }

    override suspend fun dismissSafRemovedNotice() {
        shouldShowSafRemovedNoticeFlow.value = false
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
        scope.launch { viewModel.shouldShowSafRemovedNotice.collect {} }
    }

    @Test
    fun `once subscribed, exposes SettingsPreferences' current values`() = runTest(testDispatcher) {
        val prefs = FakeSettingsPreferences(
            initialTheme = Theme.DARK,
            initialSystemDefaultLightVariant = Theme.WARM_PAPER,
            initialShouldShowSafRemovedNotice = true
        )
        val viewModel = SettingsViewModel(prefs)
        subscribeToAll(viewModel, backgroundScope)
        advanceUntilIdle()

        assertEquals(Theme.DARK, viewModel.currentTheme.value)
        assertEquals(Theme.WARM_PAPER, viewModel.currentSystemDefaultLightVariant.value)
        assertEquals(true, viewModel.shouldShowSafRemovedNotice.value)
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
    fun `dismissSafRemovedNotice forwards to SettingsPreferences`() = runTest(testDispatcher) {
        val prefs = FakeSettingsPreferences(initialShouldShowSafRemovedNotice = true)
        val viewModel = SettingsViewModel(prefs)
        subscribeToAll(viewModel, backgroundScope)
        advanceUntilIdle()

        assertEquals(true, viewModel.shouldShowSafRemovedNotice.value)

        viewModel.dismissSafRemovedNotice()
        advanceUntilIdle()

        assertEquals(false, viewModel.shouldShowSafRemovedNotice.value)
    }
}
