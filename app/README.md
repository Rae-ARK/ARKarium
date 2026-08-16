ARKarium Android app
======================================

This is the Android Compose implementation of ARKarium. Current version: see
`app/build.gradle.kts`'s `versionName` (the single source of truth - the running
app reads this back via `BuildConfig.VERSION_NAME` and shows it in Settings and
on the crash screens, so it's never hand-typed a second time in code).

Features
- SAF-based folder selection and persistent permission handling
- Progressive folder scanning that discovers novels, arcs, and chapters
- Diff-based incremental rescan: unchanged novels are skipped by fingerprint,
  changed ones are upserted in place (manual chapter overrides survive), and
  novels whose folder is genuinely gone are removed - see `bugs.md` Bug 4
- Room-based indexing of novels, arcs, chapters, and authors
- Library screen showing discovered novels
- Novel detail screen: tabbed "All Chapters" + per-arc view, chapter search,
  zebra striping, Bonus/Extra tags for interludes/afterwords
- Author pages (`author.json`-backed), linked from the fiction page and chapter page
- Reader screen: Royal Road-style layout with arc/fiction cover fallback,
  Previous/Next navigation, an explicit "Reader Preferences" pill for font size,
  line spacing, and Light/Sepia/Dark mode
- Manual chapter title/position overrides, persisted independently of rescans
- Text encoding fallback (UTF-8 → ISO-8859-1)
- Persistent storage of selected library URI (restores on app restart)
- Global uncaught-exception handler with a persisted last-crash trace (tagged
  with the version it happened on) shown on next launch

How to build and run
1. Open this folder in Android Studio.
2. Sync Gradle (File → Sync Now).
3. Build and run on an Android device (API 24+).
4. Tap "Select library folder" to pick a folder containing novel subfolders.
5. Wait for scan to complete, then tap a novel to see chapters, and tap a chapter to read.

Known limitations
- EPUB/PDF support not yet implemented (see `docs/EPUB_SUPPORT.md`).
- Library-wide search matches title/author only; it doesn't search chapter contents.

Architecture notes
- `PreferencesManager` handles persistent preference storage (DataStore).
- `ScannerImpl` enumerates folders and parses chapter filenames using ranked heuristics,
  with fingerprint-based incremental rescan and diff-based upsert/removal for
  novels, arcs, chapters, and authors.
- `ChapterContentRepository` abstracts text file reading with encoding fallback.
- Navigation is managed via sealed class `Screen` enum in `MainActivity`.

