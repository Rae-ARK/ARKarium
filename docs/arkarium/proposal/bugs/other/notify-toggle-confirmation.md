# "Notify me when new chapters are available" toggle needs on-device confirmation

> **Kind:** proposal · **Status:** resolved · **Verified:** 2026-11-08 @ (this patch)

**Seen:** 0.8, reported by owner (as "toggle is not visible"). **Status:** resolved - the
undiscoverability half was fixed first (see below); the on-device check (step 2 below)
came back clean, and it surfaced exactly the "second, different bug" step 1 called out
as a possibility, which is now also fixed (see "Sync-detection bug" below).
**Where:** `ui/NovelDetailScreen.kt` (the toggle row), `MainActivity.kt` (what it passes as
`onToggleNotify`), `data/ScannerImpl.kt` / `data/LibraryScan.kt` (the sync-detection fix).

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

**On-device check, resolved:**
1. The original report's novel was confirmed on-device: it wasn't added via "Add
   fiction"/"Sync all" at all, but by placing one of Rae ARK's own already-published
   fictions straight onto disk (an existing on-disk copy, not something this build ever
   ran a sync pass against). That's exactly the "second, different bug in the
   sync-detection path itself" this entry's step 1 flagged as a possibility - see
   "Sync-detection bug" below for the fix.
2. Confirmed on a real device: turning the toggle on, on API 33+, prompts for
   `POST_NOTIFICATIONS`, and a notification arrives when a new chapter shows up.
   `MainActivity`'s `notificationPermission` launcher and `NewChapterCheckWorker` work
   end-to-end as written; no further work needed here.

**Sync-detection bug, now fixed:** a fiction that is genuinely one of Rae ARK's own,
but whose on-disk folder reached the library by any route other than this app's own
sync flow (hand-copied in, restored from another device, extracted from a shared zip),
scanned as indistinguishable from a purely local novel - `ScannerImpl` never set
`syncSourceUrl` for anything it scanned, only `SyncManager`'s download flow did. That's
exactly what left "Notify me" disabled and "Check for updates" absent on
`NovelDetailScreen` for it: both are gated on `novel.syncSourceUrl != null` (see
`MainActivity.kt` above), and a folder that was never actually synced through this app
had no way to ever earn that column.

`ScannerImpl.buildNovelEntity` now recognizes this the same way "Add fiction" does:
`FictionLut.lookup` against the novel's resolved title (exact match after normalization
- Sync invariants S7), the same bundled name→slug table "Add fiction" and "Sync all"
already use. A match sets the built `NovelEntity`'s `syncSourceUrl` to
`relayBaseUrlForSlug(slug)`. `LibraryScan.mergeNovelForRescan` now lets a freshly
recognized `syncSourceUrl` fill in an existing row's column only when that row's own
`syncSourceUrl` is still null - an already-linked (or already-unlinked-elsewhere) novel
is never touched, so this can only turn "never recognized" into "recognized," never
override a different source. A library that already had this novel scanned as local
before this fix picks it up on its very next rescan, with no re-add required; a fresh
install recognizes it immediately. See `ScannerImpl.buildNovelEntity`'s and
`mergeNovelForRescan`'s own comments for the exact mechanics, and
`NovelDao.unlinkSyncSource`'s comment for one narrow known gap this introduces (a
SOURCE_GONE unlink can theoretically be re-recognized if the bundled LUT hasn't caught
up with the relay yet - flagged there, not solved with a new column until it actually
bites someone).
