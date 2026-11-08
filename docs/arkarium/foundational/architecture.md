# Architecture

> **Kind:** foundational · **Verified:** 2026-11-08 @ (this patch) · **Truth lives in:** `app/src/main/java/com/arkarium/app/`

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
inherited from ARKster: see `reference/arkster/bugs.md` (Bug 2).

## Library scan

`ScannerImpl.scanRoot` walks the library root (a SAF tree the user picked, or
app-private storage; `resolveLibraryRoot`). It is incremental and diff-based:

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
- Rescan safety history (the "novels disappear" bug) is in `reference/arkster/bugs.md` (Bug 4).

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
  being non-empty, not fired from the intent handler itself. No reading history
  yet → falls through to Home, same as Home's own Continue Reading row when
  `ReadingProgressDao.forNovel` comes back null.

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
- **Reader settings (`settings/reader`, `PreferencesManager` keys
  `reader_*`), per [`../proposal/reader-settings.md`](../proposal/reader-settings.md):**
  persisted defaults for font size, line height and reading mode that the
  pill seeds from once per chapter and never writes back to; font family's
  own row; `reader_keep_screen_on` (combines with, doesn't override, the TTS
  one - either true keeps the screen on, plain `FLAG_KEEP_SCREEN_ON`, no
  idle timer); `reader_fullscreen` (hides system bars via
  `WindowInsetsControllerCompat`, swipe-to-reveal, while in immersive mode;
  `layoutInDisplayCutoutMode` SHORT_EDGES and edge-to-edge both key off that
  same immersive condition in *either* orientation, not landscape only - an
  earlier landscape-only version of this left portrait letterboxed at the
  cutout the moment the status bar hid, since `DEFAULT`'s portrait carve-out
  only holds while the status bar is showing - see `ReaderScreen.kt`'s
  `applyWindowLayout`);
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

## Metadata and extras

- `GoogleBooksMetadataProvider` suggests metadata the user can apply; once
  applied, rescans keep it per the rules in `mergeNovelForRescan` (`data/LibraryScan.kt`).
- Splash screen with a synthesized guitar chord (`audio/`); logo geometry from `tools/gen_logo_points.py`.
- Legal screens (privacy, terms, about) are static.
