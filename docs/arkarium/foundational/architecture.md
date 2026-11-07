# Architecture

> **Kind:** foundational · **Verified:** 2026-11-06 @ b4fcc2f · **Truth lives in:** `app/src/main/java/com/arkarium/app/`

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
browse, novel detail, chapter editor, reader, author, settings (+ `settings/theme|library|splash|tts`),
privacy policy, terms, about.

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
Chapter order uses a filename sort-tier convention (`~` bonus, `!` closing),
inherited from ARKster: see `reference/arkster/bugs.md` (Bug 2).

## Library scan

`ScannerImpl.scanRoot` walks the library root (a SAF tree the user picked, or
app-private storage; `resolveLibraryRoot`). It is incremental and diff-based:

- Per-novel fingerprints (`scan_fingerprints`) skip unchanged novels.
- Changed novels are upserted in place, so `chapter_overrides` (manual
  title/position edits) survive rescans.
- Novels whose folder is gone are removed, **except** synced ones (see S6).
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

## Reading and local state

- `ReaderScreen`: Royal Road-style layout, cover fallback (arc → fiction), Previous/Next
  (replace, not push, so Back returns to the novel), "Reader Preferences" pill
  (font size, line spacing, speech rate, font family, Light/Sepia/Dark).
- `reading_progress` per novel/chapter; `chapter_read_events` back the Home
  "reading stats" card (streak, chapters this week). A chapter counts as read at 90% scroll (`ReadingStats`).
- Encoding fallback UTF-8 → ISO-8859-1 in `TextChapterContentRepository`.
- Global uncaught-exception handler persists the last crash (tagged with version), shown next launch.
- Library search matches title and author only, not chapter text.

## Text-to-speech (as built)

System `android.speech.tts.TextToSpeech` only (`ui/Tts.kt`, `rememberChapterTts`).
No voices bundled; "Change voice" links to the system TTS setting. Chapter text
is spoken in chunks with live rate (pill) and persisted defaults on
`settings/tts`: default rate, pitch, auto-continue to next chapter, keep screen
on while speaking (`PreferencesManager` keys `tts_*`). Multi-voice,
character-aware reading is **not built**: see [`../proposal/tts-multivoice.md`](../proposal/tts-multivoice.md).

## Metadata and extras

- `GoogleBooksMetadataProvider` suggests metadata the user can apply; once
  applied, rescans keep it per the rules in `mergeNovelForRescan` (`data/LibraryScan.kt`).
- Splash screen with a synthesized guitar chord (`audio/`); logo geometry from `tools/gen_logo_points.py`.
- Legal screens (privacy, terms, about) are static.
