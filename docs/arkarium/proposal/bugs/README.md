# Bug reports

> **Kind:** proposal · **Status:** open reports only · **Verified:** 2026-11-12 @ (this patch)

Open, unfixed bugs, one file per bug. A fixed bug's file is deleted; if the
cause taught a rule, it goes in
[`../../foundational/`](../../foundational/README.md) (see sync invariants
S6–S10 in `architecture.md` for the pattern), otherwise the commit message is
the record.

Split by severity into two folders:

| Folder | Criteria |
|---|---|
| [`critical/`](critical/README.md) | Crashes, data loss, or a core flow (sync, reading, TTS playback) completely broken for everyone. |
| [`other/`](other/README.md) | Everything else: wrong or undiscoverable behavior, edge cases, items that need on-device confirmation before they can be closed. |

Format per bug file: `# <short title>`, then **Seen** · **Where** · **Repro**
· **Expected / Actual**, and **Cause** / **Status** when known.

The frozen copy of ARKster's bug history that used to live at
`reference/arkster/bugs.md` has been removed (2026-11-10) — it had gone stale
now that ARKster has moved on and closed most of what it described. Bugs in
logic ARKarium inherited from ARKster (sort tiers, rescan safety, author
pages) are tracked here like any other ARKarium bug if they resurface.

## Open bugs

| Bug | Severity |
|---|---|
| [`other/scrolling-bug.md`](other/scrolling-bug.md) | other — reader scrolling reported not smooth; the code causes found 2026-11-08 are fixed (reader is now a `LazyColumn`), unconfirmed on a device, and it's unclear which screen the stutter was on |
| [`other/uiux-bugs.md`](other/uiux-bugs.md) | open — all 10 fixed in source, most awaiting an on-device pass; #10 is a fix-on-a-fix, found by re-reading forward from #9's own change rather than a separate owner repro |

The six previously tracked here are all fixed and their files deleted per
the rule above:

- Scan reconcile cascade-deleting a novel's history on an ordinary scan —
  see [`../../implementation/engineering-backlog.md`](../../implementation/engineering-backlog.md) rung 1.
- "Notify me" toggle undiscoverability and the sync-detection bug it
  surfaced — see [`../../implementation/engineering-backlog.md`](../../implementation/engineering-backlog.md) rung 11,
  and sync invariant S10 in [`../../foundational/architecture.md`](../../foundational/architecture.md).
- Upgrading from a pre-rung-14 build silently resetting in-progress chapters
  to the top (owner-reported as "Continue Reading doesn't take me to where I
  left off") — see `other/README.md`'s own note on it, and `MainActivity.kt`'s
  `initialReaderPosition` effect.
- First-run empty-library screen's "set up your own folder" button pointing
  at a SAF folder picker already removed (see "Settled decisions" in
  [`../../foundational/design-notes.md`](../../foundational/design-notes.md))
  — see `other/README.md`'s own note on it, and `HomeScreen.kt`'s
  `EmptyLibraryPrompt`.
- Continue Reading landing at the top of a chapter instead of where the reader
  left off, from seven separate causes (a composition race, a clobbered
  checkpoint, layout drift, the launch intent re-firing, a process-restore
  cache miss, a swallowed cancellation, a duplicate haptic) — confirmed
  working on a device; see `MainActivity.kt`'s `initialReaderPosition` effect
  and `ReaderScreen.kt`'s `currentAnchor()`.
- Streak, weekly count and the completion haptic going dead after the schema
  change from scroll percentage to a raw character offset: "percent read" was
  being derived from the wrong quantity. Fixed by keeping the resume position
  and the completion fraction as separate fields — see `ReaderPosition.kt`'s
  `completionFraction` and `ReadingStats.kt`. Confirmed working on a device.
