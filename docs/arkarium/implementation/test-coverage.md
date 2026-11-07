# ViewModel test coverage

> **Kind:** implementation (ladder) · **Status:** not started · **Verified:** 2026-11-06 @ b4fcc2f
> **Truth lives in:** `app/src/test/`, `app/src/main/java/com/arkarium/app/viewmodel/`

Goal: close the ViewModel test gap left after the `MainActivity` refactor.
Only `SettingsViewModel` has a test today (`SettingsViewModelTest`); other
tests: `AppThemeTest`, `LibraryScanTest`, `ReaderTtsLogicTest`. Rules and strategy:
[`../foundational/design-notes.md`](../foundational/design-notes.md).

This work adds test files only. It must not change any ViewModel's public
surface or touch `MainActivity`.

## Ladder

| Rung | State | What |
|---|---|---|
| 4.1 | next | `MetadataViewModelTest`: `fetchMetadataFor` with a fake `NovelMetadataProvider` (a suspend lambda). `applyMetadata` out of scope (writes through concrete `AppDatabase`). |
| 4.2 | queued | `LibraryViewModelTest`, narrow: `reportScanSetupError` (pure state write). `refreshRecentlyRead` only if an in-memory `AppDatabase` is cheap; else deferred. `startScan` / `loadNovelDetails` need `DocumentFile` and Room: out of scope. |
| 4.3 | blocked | `SyncViewModelTest`. Every public function touches `db`, `scanner`, or `syncManager`. Unblocks only if an interface seam over `SyncManager` is introduced or Robolectric is adopted. Placeholder, not a task. |

## Unblocking 4.3 needs a decision

Either an interface over `SyncManager` (a ViewModel-shape change, so its own
proposal) or adopting Robolectric (reverses a current testing-strategy choice).
Record the choice in `design-notes.md` when made.
