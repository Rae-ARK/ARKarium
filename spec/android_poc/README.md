ARKarium Android POC
===================

This folder contains Kotlin pseudocode and notes for a v0.1 proof-of-concept scanner and reader foundation:

- `BookSource.kt` — `BookSource` interface and small notes.
- `FolderSource.kt` — Folder-backed source pseudocode (SAF `DocumentFile`-based).
- `ChapterContentRepository.kt` — abstraction for providing chapter content to the UI.
- `Scanner.kt` — incremental scanner pseudocode showing fingerprints and upsert logic.
- `SCANNER_POC.md` — heuristics and UX notes for progressive scanning and graceful degradation.

These files are not a full Android project; they are design-level pseudocode intended to be translated into `ViewModel`/`Repository` implementations in an Android app.

Next steps: port these classes into the app module, implement Room schemas, and wire them to a Compose-based UI.
