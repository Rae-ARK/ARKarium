# Other (non-critical) bugs

Wrong or undiscoverable behavior, edge cases, and items that need on-device
confirmation before they can be closed. See [`../README.md`](../README.md)
for the format and the critical/other split.

| Bug | Status |
|---|---|
| [`continue-reading-position-lost.md`](continue-reading-position-lost.md) | fix applied (7 causes), needs on-device confirmation |
| [`continue-reading-completion-fraction.md`](continue-reading-completion-fraction.md) | fix applied (streak/weekly-count/haptic), needs on-device confirmation |
| [`scrolling-bug.md`](scrolling-bug.md) | open — cause 1 (progress readout recomposition scope) fixed, unmeasured; causes 2-4 untouched; moved here from `critical/` (its own severity line already said Medium, not critical) |
| [`uiux-bugs.md`](uiux-bugs.md) | open — 6 of 7 confirmed by source re-check (1 already fixed); cutout-widget cause pinned by contrast with Reader's own working handling |

Fixed and deleted per the rule in `../README.md`:

- `legacy-percentage-position-lost-on-upgrade.md` — owner-reported as
  "Continue Reading doesn't take me to where I left off." Fixed by
  converting a legacy `PERCENTAGE` row to `CHAR_OFFSET` the first time it's
  read, instead of restoring it to null — see `MainActivity.kt`'s
  `initialReaderPosition` effect.
