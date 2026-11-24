# Other (non-critical) bugs

Wrong or undiscoverable behavior, edge cases, and items that need on-device
confirmation before they can be closed. See [`../README.md`](../README.md)
for the format and the critical/other split.

| Bug | Status |
|---|---|
| [`scrolling-bug.md`](scrolling-bug.md) | open — causes 1-3 are gone in code (progress readout is scoped and deferred; the reader is a keyed `LazyColumn`), unmeasured; cause 4 (build config, F12) untouched; which screen the stutter was on is unrecorded |
| [`uiux-bugs.md`](uiux-bugs.md) | open — all 10 fixed in source, most awaiting an on-device pass; #10 is a fix-on-a-fix, found by re-reading forward from #9's own change rather than a separate owner repro |

Fixed and deleted per the rule in `../README.md`:

- `legacy-percentage-position-lost-on-upgrade.md` — owner-reported as
  "Continue Reading doesn't take me to where I left off." Fixed by
  converting a legacy `PERCENTAGE` row to `CHAR_OFFSET` the first time it's
  read, instead of restoring it to null — see `MainActivity.kt`'s
  `initialReaderPosition` effect.
- `empty-library-dead-folder-button.md` — the first-run empty-library screen's
  "or set up your own folder in Settings" button pointed at the SAF folder
  picker the SAF-removal decision (see "Settled decisions" in
  [`../../../foundational/design-notes.md`](../../../foundational/design-notes.md))
  had already removed. Fixed by deleting the
  button (and `HomeScreen`'s now-unused `onSelectFolderClick` param and its
  `MainActivity.kt` wiring) rather than rewording it, since Sync All and the
  top bar's "Add fiction" action already cover first-run entry points.
- `continue-reading-position-lost.md` — Continue Reading opened at the top of
  the chapter instead of the saved spot; seven separate causes, all fixed and
  confirmed on a device. See `MainActivity.kt`'s `initialReaderPosition` effect
  and `ReaderScreen.kt`'s `currentAnchor()`.
- `continue-reading-completion-fraction.md` — streak, weekly count and the
  completion haptic stopped updating once position became a character offset.
  Fixed by splitting resume position from completion fraction; confirmed on a
  device. See `ReaderPosition.kt` and `ReadingStats.kt`.
- Notification and sync reliability, from `urgent-review.md` (never committed
  here, only cited from source comments; F1-F8 and the F4 cadence item, all
  fixed in code; only F1 was reproduced on a device, the rest are confirmed in
  code and still need a device pass):
  - the fiction page kept its old chapter list after a successful "Check for
    updates", and a notification tap opened it empty or stale, because nothing
    refreshed the in-memory chapter list - now sync invariant S12;
  - the background worker swallowed every per-novel failure and could save the
    new sync version before the rescan, losing a notification permanently -
    now `Result.retry()`, a persisted last-checked time and error shown under
    the toggle, and sync invariant S11;
  - the check ran only about every 6 hours - now about every 2, plus a one-time
    check when the toggle is switched on and when the app comes to the
    foreground (see `architecture.md`, Entry points);
  - unlinking left `notify_new_chapters` set and the next scan re-linked any
    Rae ARK title - now `sync_unlinked`, see S10;
  - denying the notification permission gave no feedback, and the toggle could
    show ON while nothing was checked - now an inline warning, with an "Open
    settings" link for the permission case;
  - the splash Animation switch did nothing after the first launch and the
    chord was cut off hard - now three-way animation and music settings, see
    `architecture.md`, Metadata and extras.
  Minor items from the same review (launcher icon used as the notification
  small icon, "fiction you follow" wording, a manual check and the worker
  double-reporting if they overlap) were not part of the fix list and are not
  tracked here.
- Home screen scrolling (urgent-review.md's F9; the doc itself was never
  committed here, only ever cited from source comments) — no stable `key`s on
  Home's top-level `LazyColumn` sections, so the conditionally-loaded Continue
  Reading section shifted content under the reader's finger once it loaded in
  after the first frame; Featured/Trending/New Releases reshuffled on any
  novel field change instead of only when library membership changed; bottom
  spacing used `Modifier.padding()` on the `LazyColumn` itself, shrinking its
  viewport and clipping the last row instead of using `contentPadding`; and
  the search query wasn't `rememberSaveable`, so it was lost on rotation or
  process death. All four fixed — see `HomeScreen.kt`.
