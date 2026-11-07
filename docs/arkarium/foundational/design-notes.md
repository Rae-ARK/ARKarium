# Design notes

> **Kind:** foundational · **Verified:** 2026-11-06 @ b4fcc2f · **Truth lives in:** code structure, `app/src/test/`

The rules the code is held to, and the decisions that are settled. Each entry
is a *why*; the *what* is in [`architecture.md`](architecture.md).

## Code philosophy

**Service-oriented, separated by concern, minimal boilerplate, functional by
default.** Four rules:

1. **Services own resources.** Anything holding an external resource or a unit
   of business capability (folder tree, network, preferences, remote lookup) is
   a small service with a narrow API, its own file, and constructor-injected
   dependencies. Add an interface only when a second implementation is real or
   concretely planned (e.g. `NovelMetadataProvider`).
2. **Layers depend downward.** UI renders state and forwards intent;
   ViewModels hold UI state; services hold logic and I/O; DAOs hold
   persistence. No sideways reach into another feature's state, no upward
   reach into UI types from a service.
3. **Functional by default.** Logic that transforms data is a plain (ideally
   pure) top-level function. Use a class only for state or a resource with a
   lifecycle. Templates: `resolveTheme`, `mergeNovelForRescan`.
4. **Minimal boilerplate.** Smallest construct that says it: top-level `fun`
   over one-method class, `data`/`sealed class` over hand-rolled, no one-ViewModel-per-screen
   by default, and never one giant `MainViewModel`.

## Settled decisions

- **ViewModels split by feature, not one per screen.** A ViewModel is for
  state with real logic on top (theme resolution, folder URI, sync
  resolution). Flat preference reads/writes (splash, TTS defaults) go straight
  through `PreferencesManager`, hoisted at the Activity. No `TtsSettingsViewModel`.
- **Library lists are `SnapshotStateList`, not `StateFlow<List>`.** They are
  mutated incrementally in place, which is what Compose snapshot state is for.
- **ViewModels take interfaces only where a seam exists** (`SettingsPreferences`,
  `NovelMetadataProvider`); they take concrete `AppDatabase`, `ScannerImpl`, `SyncManager`.
  Consequence: `Sync`/`Library`/`Metadata` ViewModels are mostly untested at the
  ViewModel tier (no in-memory Room without Robolectric, which is deliberately not adopted).
- **Cross-ViewModel results go back via plain lambdas** (`onApplied`, `onUpdated`),
  never by a ViewModel writing navigation state. Since the `NavHost` migration
  most of these became unnecessary because screens read novels by id from `LibraryViewModel`.
- **Navigation is `NavController` only.** Reader Previous/Next *replaces* the
  route (`popUpTo` inclusive), so Back returns to the novel, not the prior chapter.
  The author screen has no `from`; Back is `popBackStack()`.
- **Settings is an index; each option is its own page.** Back-stack is one deeper than a flat screen; accepted, not to be relitigated.
- **TTS settings split.** Speech rate stays in the reader pill (adjusted while
  reading). Pitch, default rate, auto-continue, keep-screen-on are global
  defaults on `settings/tts`. The pill's live rate never writes back to the
  default. Engine choice links out to the system TTS setting; no in-app
  picker, no `tts_engine_package` key.
- **Behavior-preserving moves first.** Extractions land as mechanical moves,
  each stage independently shippable and revertable, ordered smallest and
  lowest-risk first.
- **"Early development" is a description, not a label to change.** The version
  and status earn a 1.0 by lowering real risk (untested, hard-to-change core),
  not by editing a string.

## MainActivity refactor: outcome (done)

`MainActivity.kt` had become navigation graph, ViewModel, and event bus at
once (1706 lines). Source comments cite these stage numbers; this table is
what they resolve to.

| Phase | Stage | Result |
|---|---|---|
| 1 | 1.1 | `Screen` and dialog state classes → `navigation/AppState.kt` |
| 1 | 1.2 | Theme logic → `ui/theme/AppTheme.kt` (`resolveTheme`, `colorSchemeFor`) |
| 1 | 1.3 | Unit-test infra (JUnit4, coroutines-test), first test `AppThemeTest` |
| 2 | 2.1 | `mergeNovelForRescan`, `resolveLibraryRoot` → `data/LibraryScan.kt` |
| 2 | 2.2 | `SettingsViewModel` |
| 2 | 2.3 | `LibraryViewModel` (novels, chapters, arcs, scan state, `startScan`) |
| 2 | 2.4 | `MetadataViewModel` |
| 2 | 2.5 | `SyncViewModel` (an `AndroidViewModel`: `FictionLut` needs `Context`; also owns `addFictionByName`) |
| 3 | 3.1–3.4 | `NavHost` migration: static screens → Home/Browse → NovelDetail/ChapterEditor (by `novelId`) → Reader/Author |
| 3 | 3.5 | `Screen` and `currentScreen` deleted; `MainActivity` ≈ 1428 lines |

Phase 4 (closing the ViewModel test gap) is not done: see
[`../implementation/test-coverage.md`](../implementation/test-coverage.md).

## Settings redesign: outcome (Stages 0–3 done)

Index page plus `settings/theme|library|splash` (pure extractions) and the new
`settings/tts` page (four `PreferencesManager` keys, `rememberChapterTts()`
seeded from them, auto-continue, keep-screen-on). Remaining work is on
[`../implementation/settings-tts.md`](../implementation/settings-tts.md).

## Testing strategy

- Plain logic: JVM unit tests in `app/src/test`, no device. Majority tier.
- ViewModels: JVM tests with `kotlinx-coroutines-test`, fakes behind interfaces.
- Compose UI / Room-backed paths: instrumented or Robolectric later; not a priority.
