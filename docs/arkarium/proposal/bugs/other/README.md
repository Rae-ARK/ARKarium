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
