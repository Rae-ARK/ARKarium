# Design notes

> **Kind:** foundational · **Verified:** 2026-11-07 @ a621542 · **Truth lives in:** code structure, `app/src/test/`

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
  default. The voice is chosen on its own `settings/tts/voice` page
  (`tts_voice_name`, picked from the engine's own voice list, one language at
  a time) rather than in a dialog on `settings/tts` itself. Engine choice
  still links out to the system TTS setting; no engine switcher, no
  `tts_engine_package` key.
- **The voice is applied after engine init, never silently dropped.**
  `TtsPlaybackService.setInitialRateAndPitch` only records the seed
  (rate/pitch/voice name); it's applied the moment the engine's `TextToSpeech`
  init callback reports success, whichever of "service bound" and "engine
  ready" happens second - never attempted while the engine might still be
  initializing, where `setVoice`/`setSpeechRate`/`setPitch` fail silently and
  never get a second chance. Choosing "System default" applies the engine's
  own `TextToSpeech.getDefaultVoice()` live rather than being a no-op.
- **A failed chunk stops the session and says why, instead of advancing.**
  An engine error (lost connection on an online voice, a voice whose data
  isn't installed, a synthesis failure) used to be treated as "chunk
  finished" and silently advance, including into auto-continue. It now stops
  the session the same way a manual Stop does and reports a message
  (`ttsErrorMessage`) instead.
- **A voice preview plays the same line for every voice.** The voice page's
  own short-lived `TextToSpeech` instance (not the shared
  `TtsPlaybackService`) speaks one fixed sample sentence whenever a row is
  selected, so voices are being compared against the same words rather than
  each other's.
- **Behavior-preserving moves first.** Extractions land as mechanical moves,
  each stage independently shippable and revertable, ordered smallest and
  lowest-risk first.
- **"Early development" is a description, not a label to change.** The version
  and status earn a 1.0 by lowering real risk (untested, hard-to-change core),
  not by editing a string.
- **Reader settings split (reader-settings.md).** Font size, line height and
  reading mode stay live-adjustable in the reader's "Reader Preferences"
  pill, seeded once per chapter from `settings/reader` defaults the pill
  never writes back to - same split TTS settings already use for rate.
  Reading mode's unset state means "follow the app theme", not a fourth
  concrete mode, so it's stored as an absent key rather than a sentinel
  value. Font family left the pill entirely for its own `settings/reader`
  row (it's "set and forget", not something read in-session). Reader
  keep-screen-on is a second flag alongside (not instead of) the TTS one;
  either being true keeps the screen on, and only the plain
  `FLAG_KEEP_SCREEN_ON` is used - no idle timer. The brightness slider is
  opt-in and only appears in the pill once enabled on `settings/reader`;
  there is no persisted brightness value, matching Speed's pill-only,
  session-scoped behavior. Multi-document, the day/night button, the volume
  slider and column count remain documented-only in reader-settings.md,
  not built - see that doc's own suggested order for what's next.
- **One fixed library folder, no user-picked SAF tree.** `resolveLibraryRoot()`
  always resolves to `Android/media/com.arkarium.app/`
  (`context.getExternalMediaDirs()[0]`) - visible to file managers and PCs
  over USB, unlike `Android/data`, with no runtime permission or persisted
  URI-grant bookkeeping to manage. The old SAF opt-in was built defensively,
  never requested, and was the only reason `novelId` identity was tied to a
  URI that could change or vanish (closed `engineering-backlog.md`'s F3/F5
  "Large" tickets outright) and the only path paying ~3 content-provider
  queries per file instead of plain filesystem calls.
  `LibrarySettingsScreen`'s old "Use custom folder" switch is now a purely
  informational row with a "Custom folder removed" dialog, shown once on
  upgrade for anyone who had it on. See "Library scan" in
  [`architecture.md`](architecture.md) for the mechanism.
- **Flag decisions instead of guessing.** When a change involves a real
  decision with more than one reasonable answer - not just a style
  preference, but something that changes behavior, scope or what gets
  built - ask the owner rather than picking silently and moving on. Default
  to asking before proceeding on anything that fits that bar; this applies
  to work on this codebase generally, not to any one feature.

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

## Settings redesign: outcome (done)

Index page plus `settings/theme|library|splash` (pure extractions) and the new
`settings/tts` page (four `PreferencesManager` keys, `rememberChapterTts()`
seeded from them, auto-continue, keep-screen-on). The four keys' defaults and
write/read shape are covered by `PreferencesManagerTest`, against pure,
Context-free functions (`ttsDefaultRateOf`/etc.) `PreferencesManager.kt`'s
flows map through - not a Robolectric/instrumented test, and not a
`SettingsPreferences`-style interface seam (no second implementation is
planned for these four keys, and nothing but `PreferencesManager` would
consume one - see "Code philosophy" #1 above).

## Theme color calibration (done)

`colorSchemeFor()`'s Light and Dark cases used to call `lightColorScheme()`/
`darkColorScheme()` bare, which means Material3's stock seed (`#6750A4`, a
fixed violet) for every role - that's the "white with a purple gradient"
look. `warmPaperColorScheme()` had its own hand-picked hex values, but only
for 10 of the ~17 roles `lightColorScheme()` takes; the rest (notably
`primaryContainer`/`onPrimaryContainer`, used by e.g. Home's "Browse All
Novels" card) fell back to that same stock violet, clashing with the rest of
the warm/sepia palette.

Fix: every role for all three themes is now derived from one real seed color
per theme, run through Google's Material Color Utilities (HCT + TonalSpot -
the same algorithm `lightColorScheme()`/`darkColorScheme()`'s own baseline
uses, just with a deliberate seed instead of the library default):

- **Light / Dark** share one seed, a deep teal (`#2B6F77`), so the two read as
  one identity rather than unrelated palettes. New functions
  `lightColorSchemeApp()` / `darkColorSchemeApp()`.
- **Warm Paper** keeps its existing brown primary (`#8B5E34`) and its
  existing hand-tuned cream/sepia neutrals (background/surface/
  surfaceVariant/onBackground/onSurface/onSurfaceVariant - already
  well-spaced in HCT, tones 93.6/90.9/86.4, no reason to redo them), but now
  derives secondary/tertiary/error/outline/outlineVariant/inverse*/
  surfaceTint from that same seed too, so nothing in it falls back to stock
  violet anymore.

`colorSchemeFor()` and `AppThemeTest` are otherwise unchanged - this was a
palette swap, not a structural one.

## Testing strategy

- Plain logic: JVM unit tests in `app/src/test`, no device. Majority tier.
- ViewModels: JVM tests with `kotlinx-coroutines-test`, fakes behind interfaces.
- Compose UI / Room-backed paths: instrumented or Robolectric later; not a priority.
