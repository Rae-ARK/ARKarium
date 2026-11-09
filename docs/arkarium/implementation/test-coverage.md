# ViewModel test coverage

> **Kind:** implementation (ladder) · **Status:** 4.2 done, 4.1 next, 4.3 blocked · **Verified:** 2026-11-09 @ (this patch)
> **Truth lives in:** `app/src/test/`, `app/src/main/java/com/arkarium/app/viewmodel/`

Goal: close the ViewModel test gap left after the `MainActivity` refactor.
Only `SettingsViewModel` has a test today (`SettingsViewModelTest`); other
tests: `AppThemeTest`, `LibraryScanTest`, `ReaderTtsLogicTest`, `SplitForTtsTest`, `WebViewLinkPolicyTest`. Rules and strategy:
[`../foundational/design-notes.md`](../foundational/design-notes.md).

This work adds test files only. It must not change any ViewModel's public
surface or touch `MainActivity`.

## Ladder

| Rung | State | What |
|---|---|---|
| 4.1 | next | `MetadataViewModelTest`: `fetchMetadataFor` with a fake `NovelMetadataProvider` (a suspend lambda). `applyMetadata` out of scope (writes through concrete `AppDatabase`). |
| 4.2 | **done** | `LibraryViewModelTest`, narrow: `reportScanSetupError` (pure state write). `refreshRecentlyRead` deferred - see below. `startScan` / `loadNovelDetails` need `DocumentFile` and Room: out of scope. |
| 4.3 | blocked | `SyncViewModelTest`. Every public function touches `db`, `scanner`, or `syncManager`. Unblocks only if an interface seam over `SyncManager` is introduced or Robolectric is adopted. Placeholder, not a task. |

## Rung 4.2 — what changed

`LibraryViewModelTest` covers `reportScanSetupError` only, with two cases: the
plain set, and a second call overwriting the first message. Both construct
`LibraryViewModel` with `Mockito.mock(AppDatabase::class.java)` and
`Mockito.mock(ScannerImpl::class.java)` rather than real instances -
`reportScanSetupError` never calls either, so the mocks only need to satisfy
the constructor, and each test's `verifyNoInteractions(db, scanner)` makes
that "never called" claim part of the test rather than a comment. `mockito-core`
is now a `testImplementation` dependency for this reason (see its doc comment
in `app/build.gradle.kts`) - the first use of a mocking library in this
codebase's unit tests, where every other ViewModel test so far (`SettingsViewModelTest`)
has used a hand-written fake behind an interface seam instead. The two aren't
in tension: `SettingsPreferences` has a real seam to fake against, `AppDatabase`/
`ScannerImpl` don't (see design-notes.md, "ViewModels take interfaces only
where a seam exists"), and Mockito here is standing in for "an unused concrete
dependency the constructor still demands," not stubbing any behavior.

`refreshRecentlyRead` stayed out, despite only needing Room, not `DocumentFile`.
The ladder's own phrasing - "only if an in-memory `AppDatabase` is cheap" - turned
on whether `Room.inMemoryDatabaseBuilder(...)` is reachable from a plain JVM
test, and it isn't: that builder still takes a real `Context` argument, the
same requirement design-notes.md's Testing strategy section already flagged
("no in-memory Room without Robolectric, which is deliberately not adopted").
A `Mockito.mock(AppDatabase::class.java)` can satisfy the constructor precisely
because nothing calls it; `refreshRecentlyRead` is built entirely out of calls
into `db` (`readingProgressDao().recentNovelIds()`, `novelDao().byStatus(...)`,
plus `refreshReadingStats()`'s own DAO calls), so a mock would have to be
taught to return real-shaped data for each one - at that point it's not
standing in for an unused dependency anymore, it's reimplementing Room's query
behavior by hand, which is exactly the kind of brittle, logic-duplicating test
rule 3/4 in `design-notes.md`'s code philosophy argue against. `refreshRecentlyRead`
stays deferred until the 4.3 decision (interface seam vs. Robolectric) is made;
whichever direction 4.3 unblocks `SyncManager` with would also be the natural
way to make `AppDatabase` queryable from a plain JVM test.

## Unblocking 4.3 needs a decision

Either an interface over `SyncManager` (a ViewModel-shape change, so its own
proposal) or adopting Robolectric (reverses a current testing-strategy choice).
Record the choice in `design-notes.md` when made.
