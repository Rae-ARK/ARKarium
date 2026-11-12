package com.arkarium.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.arkarium.app.data.PreferencesManager
import com.arkarium.app.data.SettingsPreferences
import com.arkarium.app.data.Theme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Stage 2.2 of Phase 2 (docs/arkarium/foundational/design-notes.md) - the first of the plan's four
// feature-scoped ViewModels pulled out of MainActivity. Deliberately the smallest of
// the four: it owns no state MainActivity didn't already have (currentTheme,
// currentSystemDefaultLightVariant, and - per the SAF-removal decision, see
// "Settled decisions" in docs/arkarium/foundational/design-notes.md -
// shouldShowSafRemovedNotice in place of the old useCustomFolder/savedUri pair), it just re-exposes
// SettingsPreferences' Flows as StateFlows - so call sites keep reading `.value` via
// collectAsState() the same way they read the old mutableStateOf fields - and
// forwards writes back to it. All persistence/validation logic stays on
// PreferencesManager (architecture rule 1 - service, unchanged); this class is
// purely the Activity -> ViewModel -> service call chain the plan calls out, proven
// here with the smallest possible slice of state before LibraryViewModel (2.3),
// MetadataViewModel (2.4), and SyncViewModel (2.5) take on anything bigger.
//
// Takes a SettingsPreferences rather than a concrete PreferencesManager so
// SettingsViewModelTest can hand it a fake backed by plain MutableStateFlows -
// no DataStore/Context/Robolectric needed, matching the plan's Phase 2 testing
// strategy.
class SettingsViewModel(private val prefs: SettingsPreferences) : ViewModel() {

    // WhileSubscribed(5_000) rather than Eagerly: these flows should keep collecting
    // for a few seconds across a configuration change (screen rotation tears down and
    // immediately re-attaches the collecting composable) without a needless resubscribe,
    // but shouldn't keep DataStore's underlying Flow alive forever once nothing is
    // actually observing it. 5 seconds is the value the AOSP/Android docs use as their
    // own worked example for exactly this "survive a configuration change" case.
    // Seeded with SYSTEM_DEFAULT, matching PreferencesManager.theme's own unset-key
    // default, so the brief window before DataStore's first real emission renders the
    // same theme the persisted default resolves to instead of a hard-coded Light flash.
    val currentTheme: StateFlow<Theme> = prefs.theme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Theme.SYSTEM_DEFAULT)

    val currentSystemDefaultLightVariant: StateFlow<Theme> = prefs.systemDefaultLightVariant
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Theme.LIGHT)

    // Seeded false (don't show) rather than true, so the dialog never flashes on
    // first composition before DataStore's real value has loaded - the same
    // "show nothing until we actually know" posture every other StateFlow here uses.
    val shouldShowSafRemovedNotice: StateFlow<Boolean> = prefs.shouldShowSafRemovedNotice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setTheme(theme: Theme) {
        viewModelScope.launch { prefs.setTheme(theme) }
    }

    fun setSystemDefaultLightVariant(variant: Theme) {
        viewModelScope.launch { prefs.setSystemDefaultLightVariant(variant) }
    }

    fun dismissSafRemovedNotice() {
        viewModelScope.launch { prefs.dismissSafRemovedNotice() }
    }

    companion object {
        // Manual ViewModelProvider.Factory rather than the androidx.lifecycle.viewmodel
        // `viewModelFactory { }` DSL - this only needs to satisfy one Factory method, so
        // the DSL doesn't buy anything here (rule 4: minimal boilerplate cuts both ways -
        // reaching for a DSL to save two lines isn't actually smaller). Takes the
        // Activity's existing PreferencesManager instance rather than building its own,
        // since MainActivity already constructs exactly one in onCreate and every other
        // service (ScannerImpl, SyncManager, etc.) is shared the same way.
        fun factory(prefsManager: PreferencesManager): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(prefsManager) as T
                }
            }
    }
}
