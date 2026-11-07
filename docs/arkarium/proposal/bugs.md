# Bug reports

> **Kind:** proposal · **Status:** open reports only · **Verified:** 2026-11-07 @ 823c914

Open, unfixed bugs. A fixed bug is deleted from here; if the cause taught a
rule, it goes in [`../foundational/`](../foundational/README.md) (see sync
invariants S6–S9 in `architecture.md` for the pattern), otherwise the commit
message is the record.

Format: `### <short title>`, then **Seen** · **Where** · **Repro** · **Expected / Actual**, and **Cause** / **Status** when known.
Historic ARKster-era bug history is frozen in `../../reference/arkster/bugs.md`.

### "Notify me when new chapters are available" toggle needs on-device confirmation

**Seen:** 0.8, reported by owner (as "toggle is not visible"). **Status:** the undiscoverability half is fixed (see below); step 1 and the device check in step 3 below are still open and need the owner, not just code.
**Where:** `ui/NovelDetailScreen.kt` (the toggle row), `MainActivity.kt` (what it passes as `onToggleNotify`).

**Cause (from code), as originally found:** the row rendered only when
`onToggleNotify != null`, and `MainActivity` passes that only when
`novel.syncSourceUrl != null` - so the row was entirely absent, not just
disabled, for any novel that isn't synced (one added by dropping a folder in
by hand, or one that was unlinked - see sync invariant S6 in
`architecture.md`). The background worker only checks novels whose toggle is
on.

**Fixed:** the row now always renders. For a local novel it shows disabled
with "Only available for fictions synced from a source" instead of
disappearing, so the feature is discoverable even where it can't be turned
on (resolve option 2 from the original writeup).

**Still open - needs the owner on a real device, not something fixable from reading the code:**
1. Confirm which novel the original report was actually about. If it was
   added via "Add fiction" or "Sync all" (so `syncSourceUrl` should be set)
   and the toggle was *still* missing rather than just unlabeled, that's a
   second, different bug in the sync-detection path itself - re-open this
   entry with that novel's details if so.
2. Check that turning the toggle on, on API 33+, actually prompts for
   `POST_NOTIFICATIONS` and that a notification arrives when a new chapter
   shows up - `MainActivity`'s `notificationPermission` launcher and
   `NewChapterCheckWorker` exist and read correctly in isolation, but that
   end-to-end path has no test and hasn't been run on a device.
