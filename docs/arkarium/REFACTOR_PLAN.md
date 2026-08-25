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

## Phase 2 - state/business logic into ViewModels (all five stages done)

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
- **Stage 2.4 - done in this patch - `MetadataViewModel`.** `metadataSearchState`,
  `addFictionState`, and `fetchMetadataFor`/`applyMetadata`, calling
  `GoogleBooksMetadataProvider` (through the `NovelMetadataProvider` interface it
  implements - see below) and reading/updating `LibraryViewModel`'s novel list.
  Ordered before Sync since it's the simpler of the two remaining ViewModels (one
  external service, no multi-step resolution states). One deviation from the plan as
  originally written: `addFictionState` moved here (it's named in this bullet), but
  `addFictionByName` - the function that does most of its writing - did NOT move with
  it. Unlike `fetchMetadataFor`/`applyMetadata`, `addFictionByName` never touches
  `GoogleBooksMetadataProvider`; it calls `SyncManager`/`FictionLut` and
  `libraryViewModel.startScan`, which is Stage 2.5's territory, not this stage's - so
  moving it here would just relocate Sync logic through a Metadata-named door (rule 1).
  It stays on `MainActivity` and now writes `metadataViewModel.addFictionState.value`
  directly, the same "state lives on the ViewModel, an external writer sets it via its
  public property" shape `MainActivity`'s own `currentScreen` already uses for
  navigation - not a rule-2 violation, since unlike `chapters`/`overriddenChapterIds`
  in Stage 2.3, `addFictionState` was never meant to be internal-write-only: even
  before this stage, `MainActivity`'s own UI callbacks (`onAddFictionClick`, both
  dialogs' `onDismiss`) wrote it directly from outside the function that owned it.
  `applyMetadata` also lost one piece of behavior it can no longer own: patching
  `currentScreen` when the novel just updated is the one currently open in
  `NovelDetail`, since `currentScreen` is Activity navigation state (Phase 3 hasn't
  happened yet) and this ViewModel has no business writing it. `applyMetadata` now
  takes an `onApplied: (NovelEntity) -> Unit = {}` callback - the same
  report-the-result-via-a-plain-lambda shape `ScannerImpl`'s `onDiscovered`/`onProgress`
  already use - and `MainActivity`'s call site passes the `currentScreen` patch as that
  callback, so the net behavior is unchanged. `MetadataViewModel` takes
  `NovelMetadataProvider` (the interface `GoogleBooksMetadataProvider` implements), not
  the concrete class - same reasoning as `SettingsViewModel` taking
  `SettingsPreferences` over `PreferencesManager` - but takes concrete `AppDatabase`
  and `LibraryViewModel`, matching `LibraryViewModel`'s own precedent of not
  abstracting Room away; `applyMetadata` (and, unlike `LibraryViewModel`, this stage's
  `MetadataViewModel` as a whole) is consequently untested at the ViewModel tier for
  the same reason `LibraryViewModel.startScan` is - no in-memory `AppDatabase` is
  available in a plain JVM test without Robolectric, which this codebase's testing
  strategy deliberately doesn't reach for yet (see "Testing strategy going forward"
  below). `MainActivity.kt`: 1529 -> 1509 lines; `viewmodel/MetadataViewModel.kt`: 125
  lines (new).
- **Stage 2.5 - `SyncViewModel`.** `syncAllState`, `syncCheckState`,
  `syncResolutionState`, and the sync/resolution logic against `SyncManager`. Last,
  both because it's the largest remaining slice of Activity state/logic and because
  its `scanSingleSyncedNovel` path calls into the library-scan functions Stage 2.1
  extracted and reads/updates the novel list Stage 2.3 already moved.
  `scanSingleSyncedNovel`, `syncAllRaeArkNovels`, `checkForUpdates`, and the three
  `resolveXxx` actions offered from `SyncResolutionDialog`
  (`resolveMissingFolderBySyncing`/`resolveByRemovingFromLibrary`/
  `resolveSourceGoneByUnlinking`) all moved verbatim, matching the plan as written -
  and `addFictionByName` moved too, even though it isn't named in this bullet: per
  Stage 2.4's own note, it never touches `GoogleBooksMetadataProvider`, only
  `SyncManager`/`FictionLut` and `libraryViewModel.startScan` - the exact
  download-then-scan-then-persist shape `syncAllRaeArkNovels` already has - so leaving
  it on `MainActivity` after this stage would mean the Activity was still doing Sync
  business logic, just under a different function name. It keeps writing
  `metadataViewModel.addFictionState.value` directly, the same "state lives on the
  ViewModel, an external writer sets it via its public property" shape `MainActivity`
  itself used for it before this stage.
  `SyncViewModel` is an `AndroidViewModel`, not a plain `ViewModel` - the only one of
  the four that needs to be, since `FictionLut.lookup`/`allEntries` both take a
  `Context` to read a bundled asset. `db`/`scanner`/`syncManager` stay concrete types,
  matching `LibraryViewModel`/`MetadataViewModel`'s own precedent of not abstracting
  Room/SAF/network away; this class is consequently untested at the ViewModel tier for
  the same reason `MetadataViewModel.applyMetadata` is. Every place the pre-stage code
  patched `MainActivity`'s `currentScreen` directly inside one of these functions
  (`checkForUpdates` on a successful resync, `resolveMissingFolderBySyncing` via
  `checkForUpdates`, `resolveByRemovingFromLibrary` when the removed novel was open,
  `resolveSourceGoneByUnlinking` on a successful unlink) now does it via an
  `onUpdated`/`onRemoved` callback instead, the same `onApplied` shape
  `MetadataViewModel.applyMetadata` already established in Stage 2.4 for the identical
  reason - `currentScreen` is Activity navigation state (Phase 3 hasn't happened yet),
  so none of these ViewModels have any business writing it directly.
  `MainActivity.kt`: 1509 -> 1205 lines; `viewmodel/SyncViewModel.kt`: 483 lines (new).

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
 
Like Phase 2, this is split into independently-shippable stages rather than one big
`when`-block-to-`NavHost` swap, ordered smallest/lowest-risk first so each stage proves
the `NavHost`/`NavController` wiring on a shrinking, better-understood surface before
the stage that actually needs it (Reader's Previous/Next chapter hops, Author's `from`
back-stack context) is attempted. Unlike Phase 2's stages - which split along
*ownership* lines (each ViewModel took a disjoint slice of state) - these split along
*data-shape* lines: how much of a `Screen` case's payload can already be resolved from
a ViewModel (`libraryViewModel.novels`, etc.) via a scalar id argument, versus how much
still needs to travel through the destination itself.
 
- **Stage 3.1 - `NavHost` scaffolding + the four argument-free screens.Done** Add the
  `NavController`/`NavHost` skeleton in the top-level `App` composable (replacing the
  outer `when (currentScreen.value)` only for these four cases; every other `Screen`
  case keeps routing through the old mechanism for now, so this stage doesn't have to
  touch `Screen.NovelDetail`/`Screen.Reader`/etc. or any of their call sites) and
  migrate `Screen.Settings`, `Screen.PrivacyPolicy`, `Screen.TermsAndConditions`, and
  `Screen.AboutMe` - the only four destinations that carry zero payload of their own
  (`Settings` reads everything it needs from `settingsViewModel`/`prefsManager`
  already; the three legal/about screens are static). Each becomes a fixed-route
  `composable("settings") { ... }` etc. with `navController.popBackStack()` standing in
  for `onBack`'s `currentScreen.value = Screen.Home`/`Screen.Settings` writes. Smallest
  possible slice to prove `NavHost` coexists with the still-manual remainder of the
  `when` block before anything with real navigation arguments is on the line.
- **Stage 3.2 - `Screen.Home` and `Screen.FictionBrowse`.** `Home` becomes the
  `NavHost`'s `startDestination` (replacing `currentScreen`'s
  `mutableStateOf<Screen>(Screen.Home)` default). `FictionBrowse(initialQuery: String
  = "")` is the first destination with a real argument, but a single optional `String`
  is exactly what Navigation Compose's argument system is built for
  (`navArgument("initialQuery") { defaultValue = "" }` on a
  `"fictionBrowse?initialQuery={initialQuery}"` route) - no new lookup pattern needed
  yet. Both screens' actual content (`novels`, `inProgressNovels`) already comes from
  `libraryViewModel`, not from the `Screen` payload, so this stage is still just
  routing, not data-fetching.
- **Stage 3.3 - `Screen.NovelDetail` and `Screen.ChapterEditor`.** The first stage that
  changes a `Screen` case's shape: both currently carry a full `NovelEntity` as a data
  class field (`Screen.NovelDetail(val novel: NovelEntity)`), which is how every
  `onUpdated`/`onApplied` callback added in Stages 2.4/2.5
  (`metadataViewModel.applyMetadata`'s `onApplied`, `syncViewModel.checkForUpdates`'s
  `onUpdated`, `resolveMissingFolderBySyncing`/`resolveSourceGoneByUnlinking`'s
  `onUpdated`) ends up patching `currentScreen.value = Screen.NovelDetail(updated)` -
  there's no other way to get the freshly-synced/metadata'd `NovelEntity` onto the
  currently-showing screen when the screen *is* the data. Once the destination instead
  takes a `novelId: String` route argument and resolves the novel itself via
  `libraryViewModel.novels.firstOrNull { it.id == novelId }` (same lookup
  `Screen.Reader`'s composable already does today for `readerAuthor`/chapter
  neighbors), that whole class of callback becomes unnecessary: `libraryViewModel`
  patching its own `novels` list (which every one of those functions already does) is
  enough for the composable to recompose with the new data on its own, since it's
  reading from a `SnapshotStateList` either way. This stage should delete the
  `onUpdated`/`onApplied` callback parameters added across Stages 2.4/2.5 (or leave
  them as unused no-op defaults if any other caller still wants the notification) once
  their one caller stops needing them.
- **Stage 3.4 - `Screen.Reader` and `Screen.Author`.** Last, and the only stage that
  actually needs `NavController`'s back-stack handling rather than just its routing:
  `Screen.Author(val authorId: String, val from: Screen)` exists purely to remember
  where to `onBack` to (the fiction page byline or the reader's "About the author"
  card), which `NavController`'s own back stack makes redundant - `from` is dropped
  entirely and `onBack` becomes a plain `navController.popBackStack()`. `Reader`'s
  `onPrevious`/`onNext` currently work by replacing `currentScreen.value` with a new
  `Screen.Reader(...)` for the neighboring chapter (see `MainActivity`'s existing
  `previousChapter`/`nextChapter` lookups against `libraryViewModel.chapters`) - under
  `NavHost` this becomes `navController.navigate("reader/$novelId/${next.id}") {
  popUpTo("reader/{novelId}/{chapterId}") { inclusive = true } }` (replace, not push),
  so Back from chapter 5 returns to `NovelDetail`, not to chapter 4 - matching today's
  behavior, where `onPrevious`/`onNext` never touch a back stack because there wasn't
  one. `readerAuthor`/`readerCoverUri` resolution (today: an Activity `mutableStateOf`
  set once on screen entry) moves to a `LaunchedEffect(novelId)` keyed off the route
  argument, the same pattern `Screen.Author`'s own `LaunchedEffect(screen.authorId)`
  already uses.
- **Stage 3.5 - cleanup.** Delete `navigation/AppState.kt`'s `Screen` sealed class, the
  `currentScreen`/`showSplash` interplay's now-dead branches, and any
  `this@MainActivity`-scoped navigation helpers Stages 3.1-3.4 left orphaned once every
  case routes through `NavHost`. `MetadataSearchState`/`AddFictionState`/
  `SyncAllState`/`SyncCheckState`/`SyncResolutionState` stay in `AppState.kt`
  unchanged - they drive dialogs layered over the content, not `Screen` destinations,
  so they're outside this migration's scope regardless of which mechanism routes the
  screen underneath them.
Every stage keeps `Screen`'s *content* (which composable renders, with which data)
identical to today - only how the destination is reached and how far back "Back"
goes changes. None of the four services or five Stage-2 ViewModels change in this
phase (rule 1); `NavController` replaces exactly one thing, the hand-rolled
`currentScreen: MutableState<Screen>` + `when` block, matching rule 2 (navigation is
its own concern, currently smeared across the Activity alongside three other layers -
see this doc's opening paragraph).

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
