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

## Architecture philosophy

Everything in this plan, and every future change to the codebase, is expected to
follow one governing philosophy: **service-oriented, separated by concern, minimal
boilerplate, functional by default, with classes/objects reserved for where they
actually earn their keep.** Concretely, that means four rules:

1. **Service-oriented architecture.** Anything that owns an external resource or a
   unit of business capability - the DocumentFile tree (`ScannerImpl`), the network +
   manifest diff (`SyncManager`), remote lookups (`GoogleBooksMetadataProvider`), local
   file content (`TextChapterContentRepository`), persisted settings
   (`PreferencesManager`), notifications (`NewChapterNotifier`) - is a small service
   with an explicit boundary: a narrow public API, its own file, and (where more than
   one implementation is plausible, as with metadata lookup) an interface the rest of
   the app depends on instead of the concrete type. `ScannerImpl`, `SyncManager`, and
   `TextChapterContentRepository` already follow this well - each does exactly one job,
   takes its dependencies through its constructor, and never reaches into a sibling
   service's internals. That's the shape every future service, and every service pulled
   out of `MainActivity` in Phase 2 below, should match.
2. **Separation of concerns over convenience.** The Activity/Composable layer renders
   state and forwards user intent; ViewModels (Phase 2) hold and update UI state;
   services hold business logic and I/O; Room DAOs hold persistence. A layer should
   depend only on the layer(s) below it, never sideways into another feature's state or
   upward into UI types (`ScannerImpl` and `SyncManager` already take an `AppDatabase`
   but no Compose/Activity type, which is why they're straightforward to unit test).
   `MainActivity.kt`'s core problem (see above) is exactly that all four layers -
   navigation, UI state, business logic, and orchestration - currently collapse into
   one file; Phase 2 is this rule applied to the biggest offender.
3. **Functional by default.** Business logic that transforms data - merging,
   resolving, parsing, deciding - should default to a plain function (ideally a pure
   one: same inputs, same output, no hidden state or I/O), not a method on a stateful
   class invented to hold it. `resolveTheme()`/`colorSchemeFor()` (Phase 1, already
   pulled out) and `mergeNovelForRescan()` (candidate for Phase 2) are the template:
   free functions taking plain values in and returning plain values out, which is what
   makes them unit-testable with zero setup. Reach for a class only when there's
   actual state or a resource to own across calls (an open connection, a cache, a
   `Context`, something with a lifecycle) - not as a default container for logic that
   has neither.
4. **Minimal boilerplate.** Prefer the smallest construct that expresses the intent:
   a top-level `fun` over a class with one method, a `data class`/`sealed class` over a
   hand-rolled equivalent, extension functions over wrapper types, and no interface
   until a second implementation (real or concretely planned) actually needs one.
   `NewChapterNotifier` (a plain `object`, since there's only ever one notifier and no
   per-instance state) and the sealed `*State` classes in `navigation/AppState.kt`
   (Phase 1) are existing examples worth extending, not replacing, in later phases.

Phase 2's ViewModel split and Phase 3's `NavHost` migration below are both direct
applications of rule 2; the "pull business logic into small functions like
`resolveTheme`" note in Phase 1 is rule 3; and the guidance not to reach for one giant
`MainViewModel` is rule 4 (a single god ViewModel is boilerplate-minimal in the wrong
direction - it "saves" a few files at the cost of recreating the separation-of-concerns
problem this plan exists to fix).

## Phase 1 - done in this patch (no behavior change)

Pure, mechanical extraction, done as three independent stages. Nothing in any stage
changes what the app does; each only moves code so later phases have smaller, focused
pieces to work with. The stages have no dependency on each other (each touches a
disjoint slice of `MainActivity.kt`) and landed together here only because they're
small - in general each stage below is its own shippable, revertable commit.

- **Stage 1.1 - navigation/dialog state -> `navigation/AppState.kt`.** `Screen`
  (navigation destinations) and the `MetadataSearchState` / `AddFictionState` /
  `SyncAllState` / `SyncCheckState` / `SyncResolutionState` sealed classes, moved out
  of `MainActivity.kt` verbatim.
- **Stage 1.2 - theme decision logic -> `ui/theme/AppTheme.kt`.**
  `warmPaperColorScheme()`, `colorSchemeFor()`, and `resolveTheme()`, moved out of
  `MainActivity.kt` verbatim. `resolveTheme` takes and returns plain `Theme` values
  with no Compose/Android types, which is what makes it possible to unit-test
  directly (Stage 1.3) - a template for pulling more decision logic out of the
  Activity into small functions like it (rule 3).
- **Stage 1.3 - test infrastructure.** `testImplementation` deps for JUnit4 and
  `kotlinx-coroutines-test`, plus `AppThemeTest`, the repo's first unit test, covering
  `resolveTheme`'s day/night substitution logic. Deliberately sequenced after 1.2 -
  there's nothing to test until a pure function exists to test.

`MainActivity.kt`: 1706 -> 1601 lines. Every call site (`resolveTheme(...)`,
`colorSchemeFor(...)`, `Screen.Home`, etc.) is unchanged; only the imports moved.

## Phase 2 - state/business logic into ViewModels (Stages 2.1-2.3 done, 2.4-2.5 not started)

Split the Activity's `mutableStateOf` fields and their surrounding logic into a small
number of feature-scoped ViewModels backed by `StateFlow`, rather than one
`MainViewModel` that just reassembles the same god-object problem under a new name.
Unlike Phase 1, these stages aren't fully independent - each is still its own
commit/PR, but the order below is deliberate:

- **Stage 2.1 - done in this patch - pure functions first, no ViewModel yet.**
  `mergeNovelForRescan` and `resolveLibraryRoot` moved out of `MainActivity` into
  `data/LibraryScan.kt` as plain top-level functions, same shape as Phase 1's
  `resolveTheme` move. `resolveLibraryRoot` only ever touched `Context` to build a
  `DocumentFile`, so it now takes `context` as an explicit parameter instead of
  relying on an Activity's implicit `this` - every call site inside
  `MainActivity` passes `this@MainActivity` (several sites are inside
  `lifecycleScope.launch { }`/Composable lambdas, where a bare `this` would resolve
  to the wrong receiver). `mergeNovelForRescan`'s signature is unchanged. This was
  the lowest-risk stage - a mechanical, behavior-preserving move like all of Phase 1 -
  and it unblocks unit tests for `mergeNovelForRescan`'s field-by-field merge rules
  before any ViewModel wiring exists to get in the way (rule 3): see
  `LibraryScanTest`, covering the no-existing-row passthrough, the pageSize/
  readingStatus carry-over, the remote-vs-local description/genres precedence, the
  `authorsFolderFound` fallback, and the sync-bookkeeping carry-over.
  `MainActivity.kt`: 1726 -> 1670 lines.
- **Stage 2.2 - `SettingsViewModel`.** `currentTheme`, `currentSystemDefaultLightVariant`,
  `useCustomFolder`, `savedUri` - thin, mostly a `StateFlow`-shaped wrapper around
  `PreferencesManager`'s existing surface (or an extension of it). Done first among
  the four ViewModels because it has no dependency on `novels` or any other
  cross-feature state, so it's the smallest possible proof that the
  Activity -> ViewModel -> service call chain works end to end before tackling
  bigger state.
- **Stage 2.3 - done in this patch - `LibraryViewModel`.** `novels`, `chapters`,
  `arcs`, `recentlyRead`, `inProgressNovels`, `overriddenChapterIds`/
  `arcStartChapterIds`, scan progress/message, and `startScan` (now calling the
  Stage 2.1 functions instead of Activity-private ones). `loadNovelDetails` and
  `refreshRecentlyRead` moved with it too, even though neither is named in this
  bullet - they're the only functions that ever write chapters/arcs/
  overriddenChapterIds/arcStartChapterIds and recentlyRead/inProgressNovels
  respectively, so once those fields moved here the functions that populate them had
  to as well (rule 2). `saveChapterEdits` stays on `MainActivity` - it never touches
  `chapters`/`arcs` directly, only via a `libraryViewModel.loadNovelDetails(novel)`
  call at the end, same "own the DB write, delegate the refresh" split
  `checkForUpdates`/`setNotifyEnabled` already use for `novels`. Comes before
  Sync/Metadata below because both of those read the library's novel list
  (`SyncViewModel`'s `scanSingleSyncedNovel` path, `MetadataViewModel`'s "apply to
  novel" step) - giving `LibraryViewModel` a settled shape first means Stages 2.4-2.5
  have a real list to depend on instead of guessing its eventual shape. One deviation
  from the plan as originally written: `novels`/`chapters`/`arcs`/`recentlyRead`/
  `inProgressNovels` are exposed as `SnapshotStateList`s (`mutableStateListOf`), not
  `StateFlow<List<...>>` - every one of them is mutated incrementally in place
  (index-set, add, removeAll) by MainActivity and by `LibraryViewModel` itself, which
  is exactly the shape Compose's snapshot state system is built for; wrapping each
  mutation in a copy-the-whole-list `StateFlow.update{}` would be strictly more
  boilerplate for the same behavior (rule 4) - see the class doc comment in
  `viewmodel/LibraryViewModel.kt` for the full rationale. `scanSingleSyncedNovel`
  itself is NOT moved in this stage - per the Stage 2.5 note below, it stays on
  `MainActivity` (reaching into `libraryViewModel.novels` directly) until
  `SyncViewModel` is ready to take it. `MainActivity.kt`: 1690 -> 1529 lines;
  `viewmodel/LibraryViewModel.kt`: 225 lines (new).
- **Stage 2.4 - `MetadataViewModel`.** `metadataSearchState`, `addFictionState`, and
  the fetch/apply-metadata logic, calling `GoogleBooksMetadataProvider` and reading
  `LibraryViewModel`'s novel list. Ordered before Sync since it's the simpler of the
  two remaining ViewModels (one external service, no multi-step resolution states).
- **Stage 2.5 - `SyncViewModel`.** `syncAllState`, `syncCheckState`,
  `syncResolutionState`, and the sync/resolution logic against `SyncManager`. Last,
  both because it's the largest remaining slice of Activity state/logic and because
  its `scanSingleSyncedNovel` path calls into the library-scan functions Stage 2.1
  extracted and reads/updates the novel list Stage 2.3 already moved.

Constructor-injected dependencies (`AppDatabase`, `ScannerImpl`, `SyncManager`,
`PreferencesManager`, `TextChapterContentRepository`) already exist as fields on
`MainActivity` and mostly just need to move down into these ViewModels, using
`AndroidViewModel`/`viewModelFactory` for the pieces that still need `Context` (e.g.
`getExternalFilesDir`, `contentResolver`).

Applying the architecture philosophy above across all five stages:

- **Stays a free function (rule 3), just relocated:** `mergeNovelForRescan` and
  `resolveLibraryRoot` (Stage 2.1) - top-level functions, not private methods buried
  inside `LibraryViewModel`, so they keep being unit-tested exactly like
  `resolveTheme` today, independent of any ViewModel/Android test harness.
- **Becomes ViewModel state (rule 2):** the `mutableStateOf`/`mutableStateListOf`
  fields themselves (`novels`, `syncAllState`, `metadataSearchState`, etc., Stages
  2.2-2.5) and the suspend functions that only exist to update them in response to a
  user action - these are UI state and belong on the ViewModel, not on a service.
- **Stays a service, unchanged (rule 1):** `ScannerImpl`, `SyncManager`,
  `PreferencesManager`, `TextChapterContentRepository`, and
  `GoogleBooksMetadataProvider` don't move or change shape in any of the five stages -
  the ViewModels above just become their new callers instead of `MainActivity`. If a
  future feature needs new business logic that isn't UI state (e.g. a new sync
  strategy), it becomes a new service or a new function on an existing one, never a
  growth spurt on a ViewModel.

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
