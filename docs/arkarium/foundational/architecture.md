# Architecture

> **Kind:** foundational · **Verified:** 2026-11-10 @ (this patch) · **Truth lives in:** `app/src/main/java/com/arkarium/app/`

Describes what is **built**. Unbuilt ideas live in [`../proposal/`](../proposal/README.md).
If code and this file disagree, the code wins: fix this file in the same change.

## Stack

Android, Kotlin, Jetpack Compose, Navigation Compose, Room (schema version in
`data/AppDatabase.kt`), DataStore preferences, WorkManager. `minSdk`/`targetSdk`
and the app version are in `app/build.gradle.kts` (single source of truth; the
running app reads `BuildConfig`). CI: `.github/workflows/` (`build.yml`, `release.yml`).

## Layers

Dependencies point down only. See [`design-notes.md`](design-notes.md) for why.

| Layer | Where | Role |
|---|---|---|
| UI | `ui/`, `MainActivity.kt` | Compose screens; one `NavHost`; renders state, forwards intent. |
| ViewModels | `viewmodel/` | `SettingsViewModel`, `LibraryViewModel`, `MetadataViewModel`, `SyncViewModel`. Feature-scoped UI state. |
| Services | `data/` | `ScannerImpl` (folder tree), `SyncManager` (network + manifest diff), `PreferencesManager`, `TextChapterContentRepository`, `GoogleBooksMetadataProvider` (behind `NovelMetadataProvider`), `NewChapterNotifier`, `NewChapterCheckWorker`. |
| Persistence | `data/AppDatabase.kt`, `Dao.kt`, `Entities.kt` | Room tables: novels, authors, arcs, chapters, chapter_overrides, scan_fingerprints, reading_progress, chapter_read_events, synced_files. |
| Pure logic | `data/LibraryScan.kt`, `ui/theme/AppTheme.kt`, `data/ReadingStats.kt` | Plain functions/objects, JVM-testable. |

Navigation destinations (all in `MainActivity`'s `NavHost`): home, fiction
browse, novel detail, chapter editor, reader, author, settings (+
`settings/theme|library|splash|reader|tts|tts/voice`), privacy policy, terms,
about.

## Content format

A fiction is a folder; the scanner understands it whether it was dropped in
by hand or written by sync.

```text
Fiction Name/
├── metadata.json   cover.*   author.json (optional)
└── arcs/<Arc>/ metadata.json, cover.*, NNN.txt ...
authors/<id>.json   (shared, at library root, not per fiction)
```

Chapter bodies are plain `.txt`. `data/ChapterMarkup.kt` defines three inline
tags (`<chapter break>`, `<author>`, `<news>`); unmatched tags render literally.
A line that is one character three times over (`***`, `---`, `###`) is also a
chapter break, no tag needed (`isBreakLine`); the `reader_break_lines` switch on the
Reader settings page (on by default) turns that off, and the tags keep working
either way.
Chapter order uses a filename sort-tier convention (`~` bonus, `!` closing),
inherited from ARKster. (ARKster's own bug history that documented this
origin was a frozen copy here; it's been removed now that ARKster has moved
on — this convention is ARKarium's own to maintain going forward.)

## Library scan

`ScannerImpl.scanRoot` walks the library root — the one fixed
`Android/media/com.arkarium.app/` folder, via `resolveLibraryRoot`; there is
no user-picked folder anymore, see
[`../proposal/remove-saf-support.md`](../proposal/remove-saf-support.md). It
is incremental and diff-based:

- Per-novel fingerprints (`scan_fingerprints`) skip unchanged novels.
- Changed novels are upserted in place, so `chapter_overrides` (manual
  title/position edits) survive rescans.
- Novels whose folder is gone are removed, **except** synced ones (see S6).
- A scanned novel without a `syncSourceUrl` is checked against `FictionLut`
  by title (see S10): a folder that reached the library by some route other
  than this app's own sync flow is still recognized as one of Rae ARK's own.
- `novelId` is `UUID.nameUUIDFromBytes(root.uri + ":" + folder.uri)`, so
  nothing needs to persist a `DocumentFile` across restarts.
- `scanSingleNovel` scans one folder (used by sync).
- Rescan safety (never wiping a novel's history on an ordinary scan) traces
  back to a bug ARKster hit first; see `../proposal/bugs/README.md` for how
  ARKarium tracks and fixes recurrences of this class of bug in its own code.

## Sync

One fixed relay host, `RELAY_HOST` in `data/SyncManager.kt`: a static host
serving each fiction under `RELAY_HOST + slug + "/"`. No relay chain, no auth,
no background-driven resolution of conflicts.

### Manifest
`manifest.json` at the fiction root: `{ "version": <int>, "files": { "<relative path>": { "size", "sha256" } } }`.
`version` is a publisher-bumped integer; the client only asks "did it go up".
Manifest generation is currently a throwaway publisher-side script.

### Client flow
1. Fetch manifest; if `version` equals `novels.sync_source_version`, stop.
2. Diff `files` against `synced_files` (the diff's source of truth, both directions).
3. Download new/changed files, verify sha256, write; delete files no longer listed.
4. Whole-file replace; no delta or resume (chapters are small).

### Scan reuse
Sync writes into the same library root the scanner reads. A synced novel is
discovered like any other folder (same `novelId` scheme, same overrides,
author linking, etc.). No separate "remote novel" path in the UI.

`authors/` manifest entries are written to `libraryRoot/authors/`, not into
the novel's folder, because that is the only place the scanner looks.

### Entry points
- **Add fiction** (home top bar): user types a fiction *name*; `FictionLut`
  resolves it to a slug via an encrypted bundled asset (`assets/fiction_lut.dat`,
  built by `tools/gen_fiction_lut.py`). This is obfuscation against casual
  poking, not access control (threat model in `FictionLut.kt`).
- **Sync all Rae ARK's novels**: first-run action on the empty library; loops
  the same download → scan → persist flow over every LUT entry.
- **Check for updates**: per-novel, on the novel detail screen, shown once a novel has a `syncSourceUrl`.
- **Background check**: `NewChapterCheckWorker` (WorkManager, periodic; interval
  in the worker) re-syncs novels whose "notify me" toggle is on and posts a
  notification via `NewChapterNotifier` if chapters were added. It never
  silently resolves a missing folder or gone source.
- **"Continue Reading" app shortcut**: static shortcut (`res/xml/shortcuts.xml`,
  manifest `<meta-data>` on `MainActivity`), long-press the launcher icon.
  Carries no novel id - unlike the notification tap above, which already knows
  which novel it's for - so `MainActivity`'s `pendingContinueReadingShortcut`
  LaunchedEffect resolves it at tap time via `ReadingProgressDao.recentNovelIds(1)`
  (whichever novel's `reading_progress` row was updated most recently) and jumps
  straight into that chapter, same route (`reader/{novelId}/{chapterId}`) Home's
  own Continue Reading row uses. A cold start from the shortcut races `startScan`
  the same way a notification cold start does, so resolution is gated on `novels`
  being non-empty, not fired from the intent handler itself; unresolved (the
  target novel just hasn't landed in `novels` yet) leaves
  `pendingContinueReadingShortcut` set so the effect retries as `novels` keeps
  filling in, the same retry-safe shape `pendingNotificationNovelId` already
  used - clearing it unconditionally every run (fixed) meant losing that race
  once was permanent: the shortcut opened Home and nothing else ever happened,
  with no second chance once the rest of the scan landed. Only a genuinely
  empty `recentNovelIds` (no reading history, ever) clears the flag outright.
  No reading history at all → falls through to Home, same as Home's own
  Continue Reading row when `ReadingProgressDao.forNovel` comes back null.
  The launch intent is consumed once: on a recreated Activity `getIntent()` is
  still the original shortcut intent, so `onCreate` only handles it when
  `savedInstanceState == null` (see Lifecycle ownership).
- **Home's "Continue Reading" row**: `HomeScreen`'s `continueReadingNovels`
  param, wired to `LibraryViewModel.recentlyRead` (itself built from
  `ReadingProgressDao.recentNovelIds()`) - **not** `inProgressNovels`
  (`NovelEntity.status == "IN_PROGRESS"`, a shelf label independent of whether
  any chapter was ever actually opened). Wiring it to `inProgressNovels` was a
  bug: a status-only novel has no `reading_progress` row, so `onContinueReading`'s
  own `ReadingProgressDao.forNovel` lookup came back null for it and fell
  through to its own "else" branch - navigate to `novelDetail/{id}`, not the
  reader - which looked like "Continue Reading opens the novel's own page
  instead of where I left off." Sourcing the row from `recentlyRead` instead
  means every novel offered there came from `reading_progress` in the first
  place, so that lookup can't miss.

### Sync invariants
Each is a decision that cost something to learn; code comments cite them as `S<n>`.

- **S1. Deletions are tracked, not inferred.** `synced_files` drives both
  download and delete. A rename must not leave the old file behind.
- **S2. Manifest paths are untrusted.** `SyncPaths.sanitize` rejects `..`,
  leading `/`, and backslashes before any file is created (`UnsafePathException`).
- **S3. Sync owns only what it wrote.** It never touches a file in a synced
  folder that is not in `synced_files`. Direct hand-edits to a synced file are
  overwritten on resync (accepted); `chapter_overrides` are never touched.
- **S4. Partial failure is safe.** A `synced_files` row is committed only for a
  file that was hash-verified and written; `sync_source_version` is bumped only
  after every file in the pass succeeded.
- **S5. Version lives on the novel.** `sync_source_version` on `novels` makes
  the no-change case one fetch plus an integer compare.
- **S6. Missing folder / gone source is asked, never silent.** `NovelEntity.syncStatus`
  is `ACTIVE`, `MISSING_LOCALLY`, or `SOURCE_GONE`. Scan stale-removal does not
  delete a novel with a `syncSourceUrl`. A 404 on the manifest throws
  `SourceGoneException`; a missing folder throws `MissingLocalFolderException`
  unless the caller opted into recreating it. The user resolves via
  `SyncResolutionDialog` (sync again / remove from library / unlink). Sync-all
  skips a `SOURCE_GONE` fiction and continues; unexpected failures still abort the batch.
- **S7. Name lookup is exact after normalization.** `FictionLut` trims,
  lowercases, folds smart quotes, applies NFKD, strips `, : . ; ! ?`, collapses
  whitespace. No fuzzy matching, so a typo means "not found", never the wrong fiction.
- **S8. Batch sync scans one folder, not the library.** Per-fiction
  `scanSingleNovel` instead of a full `scanRoot` per iteration (was O(M×N)).
- **S9. Folder name is not identity.** New synced folders are named after the
  fiction (sanitized, de-duplicated) and the exact name is persisted in
  `syncFolderName`; relocation checks it first, then legacy hash/slug lookups.
  `sync()` checks for an existing folder before creating one.
- **S10. Scan, not just Add/Sync, can earn `syncSourceUrl`.** A folder that
  never went through this app's own sync flow (hand-copied in, restored from
  another device, extracted from a shared zip) used to scan as purely local
  forever, even when it really was one of Rae ARK's own — hiding "Notify me"
  and "Check for updates". `ScannerImpl.buildNovelEntity` now runs the same
  `FictionLut` lookup (S7) "Add fiction" uses; a match only ever fills in a
  row whose `syncSourceUrl` is still null, never overriding an already-set or
  deliberately-unlinked one. One known gap: a `SOURCE_GONE` unlink can in
  theory be re-recognized if the bundled `FictionLut` data hasn't caught up
  with the relay yet — flagged in `NovelDao.unlinkSyncSource`'s comment,
  accepted as rare and self-correcting rather than fixed with a new column.

## Reading and local state

- `ReaderScreen`: Royal Road-style layout, cover fallback (arc → fiction), Previous/Next
  (replace, not push, so Back returns to the novel), "Reader Preferences" pill
  (font size, line spacing, speech rate, Light/Sepia/Dark, and an opt-in
  brightness slider - see Reader settings below). Font family is **not** in
  the pill; it moved to `settings/reader` as a "set and forget" choice.
- **Progress readout recomposition scope** (`other/scrolling-bug.md` cause 1):
  the "n% • page n of n" readout used to call `currentProgress()` - a direct
  `scrollState.value`/`maxValue` read - inline in `ReaderScreen`'s own
  `Column`/`Box`. Neither introduces a recomposition-scope boundary, so the
  read was attributed to `ReaderScreen` itself: every scroll offset change
  invalidated the whole screen, including the per-paragraph loop and its
  `onGloballyPositioned`/`onTextLayout` lambdas, on every scroll frame while
  the controls pill was visible. Fixed by moving the readout into its own
  `ProgressReadout` composable and reading progress there through
  `derivedStateOf`: the scroll read is now scoped to just that `Text`, and
  `derivedStateOf` only triggers a recomposition when the rounded percent or
  page number actually changes, not once per scroll tick. The bug file's
  other suspected causes (per-paragraph `onGloballyPositioned` cost, eager
  composition of the whole chapter, build config with R8 off and no baseline
  profile) are unchanged and still open - see the file for the ranked list;
  it stays until the `gfxinfo`/Layout Inspector numbers it calls for are
  recorded.
- **Reader settings (`settings/reader`, `PreferencesManager` keys
  `reader_*`), per [`../proposal/reader-settings.md`](../proposal/reader-settings.md):**
  persisted defaults for font size, line height and reading mode that the
  pill seeds from once per chapter and never writes back to; font family's
  own row; `reader_keep_screen_on` (combines with, doesn't override, the TTS
  one - either true keeps the screen on, plain `FLAG_KEEP_SCREEN_ON`, no
  idle timer); `reader_fullscreen` (hides system bars via
  `WindowInsetsControllerCompat`, swipe-to-reveal, while in immersive mode;
  edge-to-edge keys off that immersive condition in *either* orientation, not
  landscape only - an earlier landscape-only version of this left portrait
  letterboxed at the cutout the moment the status bar hid, since `DEFAULT`'s
  portrait carve-out only holds while the status bar is showing. Drawing into
  the cutout (`layoutInDisplayCutoutMode` SHORT_EDGES) is broader than
  edge-to-edge: it's also on in plain landscape whenever the setting is
  enabled, bars showing or not, since `DEFAULT` letterboxes in landscape
  regardless of immersion - without this a reader who rotates without going
  immersive still saw a black letterboxed strip where the cutout is, instead
  of the reading background the root Column's `windowInsetsPadding` already
  paints there. Every `layoutInDisplayCutoutMode` change also forces
  `ViewCompat.requestApplyInsets` - Compose's own insets reads don't pick up
  an attribute change made after `setContent` on their own, which was the
  cause of a second bug (stuck cutout padding and a lowered top bar after
  *leaving* immersive mode in portrait: Compose kept padding by the last
  inset SHORT_EDGES had delivered). Edge-to-edge/cutout-drawing then widened
  twice more, both times to close a gap between the real status bar and this
  screen's own `TopAppBar` that the narrower condition still left open: first
  to cover landscape with the setting *off* (`DEFAULT` letterboxes landscape
  regardless of the setting, so a reader who rotated without enabling
  full-screen hit the same dead strip bug 2 above describes), then to cover
  portrait with the setting off too - unlike the cutout, the status-bar inset
  a non-edge-to-edge window is relied on to zero out is not consistently
  zeroed by `decorFitsSystemWindows(true)` across every API level/OEM skin in
  this app's `minSdk`-to-`compileSdk` range, so the TopAppBar's own
  deliberately-unzeroed inset (it self-pads below a real status bar exactly
  as it already does during full-screen's swipe-to-reveal transient bars) can
  still report a real height on top of whatever the window itself reserved,
  landing as a double-counted gap. Reader is now edge-to-edge any time it's
  on screen, independent of both the setting and orientation, with system-bar
  hide/show staying tied to the setting alone; the TopAppBar's own inset is
  the single remaining source of the gap's size in every combination it's
  actually in composition for. That carried a fix-on-a-fix: `TopAppBar` is
  itself gated on `showControls` (chrome visible), a flag independent of the
  full-screen setting - tap-to-hide-chrome with the setting off leaves
  `showControls` false while the real status bar is still showing, which,
  once the window went unconditionally edge-to-edge, left nothing padding
  for it at all (content drew under the still-visible bar, not a gap above
  it - the opposite-looking symptom of the same root cause, worst in
  landscape since the cutout padding only coincidentally lands near the top
  in portrait). Fixed by reserving the same status-bar inset directly on the
  "Top bar" `Column` whenever `!showControls.value && !fullscreenEnabled` -
  exactly the state with no other consumer of it - so it never stacks with
  `TopAppBar`'s own pad once chrome returns. See `ReaderScreen.kt`'s
  `applyWindowLayout` and the `DisposableEffect` that calls it, and the "Top
  bar" `Column`'s own modifier just below it);
  `reader_break_lines` (default **on**, the only reader switch that is: whether a
  bare `***`-style line draws as a chapter-break divider, see `ChapterMarkup.kt`);
  and `reader_brightness_slider_enabled`, which only toggles whether the
  pill's brightness slider is shown - the brightness value itself isn't
  persisted. Reading mode's unset state means "follow the app theme" (an
  absent key, not a fourth mode). Multi-document, a day/night button, a
  volume slider and column count are documented in that proposal but **not
  built**.
- `reading_progress` per novel/chapter; `chapter_read_events` back the Home
  "reading stats" card (streak, chapters this week). A chapter counts as read at 90% scroll (`ReadingStats`).
- Encoding fallback UTF-8 → ISO-8859-1 in `TextChapterContentRepository`.
- Global uncaught-exception handler persists the last crash (tagged with version), shown next launch.
- Library search matches title and author only, not chapter text.

- **Tap-a-word lookup** (`util/WordLookup.kt`): tapping a word in the chapter
  body hands it to whatever dictionary/translate app is already installed via
  `ACTION_PROCESS_TEXT` (`EXTRA_PROCESS_TEXT_READONLY` - a lookup, not an
  edit), wrapped in `Intent.createChooser` so more than one handler prompts a
  choice; no handler installed is a silent no-op (`ActivityNotFoundException`
  caught), not a crash. No bundled dictionary and nothing new to maintain -
  the device's own installed apps answer the lookup, same as long-pressing
  text in Chrome or a `TextView`. Wired into each paragraph `Text`'s own
  `pointerInput`/`detectTapGestures`, inside its bounds only, so a tap on a
  word is handled there instead of bubbling up to the reading `Column`'s
  `clickable` (tap-to-toggle-controls) - the same tap-on-text-vs-tap-on-margin
  split other reading apps use. Word boundaries (`WordLookup.wordAt`) expand
  from the tapped character through letters/digits and both the straight and
  curly apostrophe (so a contraction comes back whole either way the source
  text quotes it), then trims a dangling apostrophe left at either edge; a
  tap on whitespace or bare punctuation resolves to no word and does
  nothing, rather than falling through to the controls toggle. No text
  selection/copy - that's a separate, unbuilt feature; this is strictly tap,
  not long-press-and-drag.

## Lifecycle ownership
One authority per thing; everything else holds a reference, never a second copy.
`MainActivity` is destroyed and recreated by rotation, multi-window resize, memory
reclaim and process restore, so nothing that must outlive one instance may live
(or be installed) there.

| Thing | Owner | Notes |
|---|---|---|
| Crash recorder (`Thread` default handler) | `ArkariumApp` | Installed once per process. It used to be installed in `MainActivity.onCreate`, stacking one Activity-capturing wrapper per recreation. |
| Database | `AppDatabase.create()` singleton | Activity, worker and ViewModels share the one instance. |
| Preferences | `context.dataStore` (top-level, one per file) | `PreferencesManager` instances are cheap views over it. |
| Library / sync / settings state | the ViewModels | Survive recreation; rebuilt only on process death. |
| `LibraryViewModel.chapters` | a *cache* of the last loaded novel | Never the authority on whether a chapter exists - the database is. The reader route asks it before leaving. |
| Reading position | `reading_progress`, written only by `MainActivity.saveReadingProgress` | Write is `NonCancellable`; callers use `AppScope.io` (checkpoints) or `lifecycleScope` (in-app exits). |
| Read-aloud engine and session | `TtsPlaybackService` | UI binds/unbinds; the engine is not Compose state. |
| Window flags (keep-on, brightness, full-screen) | `ReaderScreen` effects | Each restored in its own `onDispose`. |
| One-shot launch intents (shortcut, notification) | `MainActivity.onCreate` / `onNewIntent` | Handled only on a fresh launch (`savedInstanceState == null`); an unresolved one is carried through `onSaveInstanceState`. `getIntent()` after a recreation is the *original* launch intent and must not be re-read. |

Rules this table encodes:
- Third-party lifecycle (Room, DataStore, `TextToSpeech`, Navigation's restored back
  stack) is treated as "may be gone or stale at any time": a restored route
  re-resolves what it needs from the database instead of trusting an in-memory cache.
  (Before [`../proposal/remove-saf-support.md`](../proposal/remove-saf-support.md),
  this list also included SAF grants on a user-picked folder; the library root is
  now fixed, so there is no such grant to go stale.)
- A position is only ever *reported* once it is real. `ReaderScreen.currentAnchor()`
  returns the saved position, not the top of the chapter, until that position has been
  applied or the reader has taken over by dragging.
- The reader is not composed until the saved position is resolved (found or genuinely
  absent), and the restore waits for the seeded reader settings and the reflow they
  cause.

## Text-to-speech (as built)

System `android.speech.tts.TextToSpeech` only (`ui/Tts.kt`,
`playback/TtsPlaybackService.kt`, `rememberChapterTts`); no engine switcher,
"Change voice" (on `settings/tts`) still links to the system TTS setting.
Chapter text is spoken in chunks (`splitForTts`, whole `BreakIterator`
sentences packed per chunk) with live rate (pill) and persisted defaults on
`settings/tts`: default rate, pitch, auto-continue to next chapter, keep
screen on while speaking (`PreferencesManager` keys `tts_*`). The **voice**
is chosen in-app on its own `settings/tts/voice` page (`TtsVoicePickerScreen`):
one language at a time, favorites starred first (`tts_voice_name`,
`tts_favorite_voices`), applied the moment the engine reports ready so a
choice made before init finishes is never dropped. A chunk that fails stops
the session and reports why instead of silently advancing. Multi-voice,
character-aware reading is **not built**: see [`../proposal/tts-multivoice.md`](../proposal/tts-multivoice.md).

## Micro-haptics

`util/Haptics.kt` - one shared object, two one-shot `Vibrator.vibrate()`
calls (`confirm`, a short double-click; `tick`, a single short pulse), each
fired from the call site that owns the relevant state transition rather than
from a View/composable's `HapticFeedbackConstants`, since two of the three
sites below aren't composables:

- **Chapter complete**: `confirm`, from `MainActivity.saveReadingProgress`'s
  `ReadingStats.isCompletionThreshold` branch - the same gate that upserts
  `ChapterReadEventEntity`, so this fires once per chapter actually finished,
  not per scroll tick or per `saveReadingProgress` call.
- **Sync done**: `confirm`, from `SyncViewModel.syncAllRaeArkNovels`'s
  `syncAllState.value = SyncAllState.Done(...)`. Not fired on
  `SyncAllState.Error` - a buzz on failure would read as confirmation, not
  warning.
- **TTS session start/stop**: `tick`, from `TtsPlaybackService.start()` (after
  audio focus is granted, not on the early-return paths) and `.stop()` (the
  explicit stop `stop()` already is, per its own doc comment - `pause()` goes
  through `pauseInternal` instead, so pausing doesn't also buzz).

`VIBRATE` is a normal, install-time-granted permission (`AndroidManifest.xml`);
nothing to prompt for at runtime. No vibrator on the device (e.g. some
tablets) is a no-op, not a crash - `Haptics` returns null and every caller
already just returns past that.

## Metadata and extras

- `GoogleBooksMetadataProvider` suggests metadata the user can apply; once
  applied, rescans keep it per the rules in `mergeNovelForRescan` (`data/LibraryScan.kt`).
- Splash screen with a synthesized guitar chord (`audio/`); logo geometry from `tools/gen_logo_points.py`.
- Legal screens (privacy, terms, about) are static.
