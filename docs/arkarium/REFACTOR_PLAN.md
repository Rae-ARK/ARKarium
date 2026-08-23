# MainActivity refactor & testing plan

`MainActivity.kt` has grown into the app's de-facto navigation graph, ViewModel, and
event bus all at once - all `Screen` routing, ~10 pieces of dialog/progress state, and
most business logic (scan, sync, metadata fetch) live directly on the Activity as
`mutableStateOf`/`mutableStateListOf` fields. That's a well-known Compose anti-pattern
(sometimes called a "god Activity"): it works, but it's hard to unit-test (everything
is entangled with `Context`/`lifecycleScope`), and every unrelated change risks
touching the same file.

This plan follows current Android/Compose architecture guidance:

- Android Developers, *Where to hoist state* - screen-level state and business logic
  belong in a `ViewModel`, which is the "source of truth and lowest common ancestor
  for UI state." Composables should consume that state, not own it.
  https://developer.android.com/develop/ui/compose/state-hoisting
- Android Developers, *Migrate Jetpack Navigation to Navigation Compose* - hoist a
  single `NavController` at the top of the composable tree that needs it (the `App`
  composable), rather than hand-rolling navigation state.
  https://developer.android.com/develop/ui/compose/migrate/migration-scenarios/navigation
- The widely-cited "ViewModel + StateFlow + sealed UiState" pattern: the ViewModel is
  unit-testable as plain Kotlin (no Compose/Robolectric needed), `StateFlow` is
  lifecycle-aware, and sealed state classes force call sites to handle every case
  explicitly - which this codebase already does well for its dialog states
  (`MetadataSearchState`, `SyncCheckState`, etc.), just not yet via `StateFlow`.
- Common warning against "god ViewModels" that re-accumulate navigation state and
  unrelated business logic once everything is *just* moved out of the Activity - the
  plan below splits by feature area rather than creating one giant `MainViewModel`.

## Phase 1 - done in this patch (no behavior change)

Pure, mechanical extraction. Nothing here changes what the app does; it only moves
code so later phases have smaller, focused pieces to work with.

- `navigation/AppState.kt` - `Screen` (navigation destinations) and the
  `MetadataSearchState` / `AddFictionState` / `SyncAllState` / `SyncCheckState` /
  `SyncResolutionState` sealed classes, moved out of `MainActivity.kt` verbatim.
- `ui/theme/AppTheme.kt` - `warmPaperColorScheme()`, `colorSchemeFor()`, and
  `resolveTheme()`, moved out of `MainActivity.kt` verbatim. `resolveTheme` takes and
  returns plain `Theme` values with no Compose/Android types, which is what makes it
  possible to unit-test directly (see below) - a template for pulling more decision
  logic out of the Activity into small functions like it.
- Test infrastructure - `testImplementation` deps for JUnit4 and
  `kotlinx-coroutines-test`, plus `AppThemeTest`, the repo's first unit test, covering
  `resolveTheme`'s day/night substitution logic.

`MainActivity.kt`: 1706 -> 1601 lines. Every call site (`resolveTheme(...)`,
`colorSchemeFor(...)`, `Screen.Home`, etc.) is unchanged; only the imports moved.

## Phase 2 - state/business logic into ViewModels (not yet started)

Split the Activity's `mutableStateOf` fields and their surrounding logic into a small
number of feature-scoped ViewModels backed by `StateFlow`, rather than one
`MainViewModel` that just reassembles the same god-object problem under a new name:

- `LibraryViewModel` - `novels`, `chapters`, `arcs`, `recentlyRead`,
  `inProgressNovels`, scan progress/message, and the scan/rescan logic
  (`resolveLibraryRoot`, `mergeNovelForRescan`, `startScan`).
- `SyncViewModel` - `syncAllState`, `syncCheckState`, `syncResolutionState`, and the
  sync/resolution logic.
- `MetadataViewModel` - `metadataSearchState`, `addFictionState`, and the
  fetch/apply-metadata logic.
- `SettingsViewModel` (or extend `PreferencesManager`'s existing surface) -
  `currentTheme`, `currentSystemDefaultLightVariant`, `useCustomFolder`, `savedUri`.

Constructor-injected dependencies (`AppDatabase`, `ScannerImpl`, `SyncManager`,
`PreferencesManager`, `TextChapterContentRepository`) already exist as fields on
`MainActivity` and mostly just need to move down into these ViewModels, using
`AndroidViewModel`/`viewModelFactory` for the pieces that still need `Context` (e.g.
`getExternalFilesDir`, `contentResolver`).

## Phase 3 - Navigation Compose (not yet started)

`androidx.navigation:navigation-compose` is already a declared dependency
(`app/build.gradle.kts`) but unused - `Screen` is currently routed by hand with a
single `mutableStateOf<Screen>` and a large `when` block. Migrating that `when` block
into a `NavHost` with one composable route per `Screen` case, and a single
`NavController` hoisted in the top-level `App` composable, removes the need for
`Screen` to carry manual back-stack context (e.g. `Screen.Author`'s `from` field) since
`NavController` handles the back stack itself.

## Testing strategy going forward

- Plain Kotlin logic (parsing, merging, resolving - like `resolveTheme` and
  `mergeNovelForRescan`) gets JVM unit tests under `app/src/test`, no device/emulator
  required. This is the fastest, cheapest tier and should cover the majority of
  business logic once it's out of the Activity.
- ViewModels from Phase 2 get JVM unit tests too, using `kotlinx-coroutines-test`
  (already added in this patch) to drive `StateFlow`/suspend functions without a real
  dispatcher or Android framework.
- Compose UI screens can get instrumented/Robolectric tests later if needed, but
  aren't the priority - the goal of Phases 1-2 is specifically to move enough logic out
  of Compose/Activity code that most of it never needs a UI test harness at all.

## On "early development" / pre-1.0 status

Version 0.5 and the "Early development" README status aren't things a patch can
honestly "fix" - they're accurate descriptions of where the project actually is, and
changing the label without changing the substance would just make the status wrong in
the other direction. This plan (and the tests it starts) is the concrete path toward
earning a 1.0 label: as MainActivity's logic moves into tested, focused units, "early
development" should become true because the risk it's flagging - a large, untested,
hard-to-change core file - has actually gone down, not because a version string moved.
