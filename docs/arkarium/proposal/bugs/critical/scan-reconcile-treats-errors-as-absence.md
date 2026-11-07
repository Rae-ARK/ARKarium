# Scan reconcile treats a skipped or empty listing as "the novel is gone"

> **Kind:** proposal · **Status:** open · **Verified:** 2026-11-07 @ b777145
> **Origin:** notes from a separate review session. Adjacent to
> [`../../data-safety.md`](../../data-safety.md) F3 but a different cause —
> this fires on an ordinary scan, with no folder re-pick needed.

**Seen:** not yet reported by a user; found reading `ScannerImpl.kt` and
`LibraryViewModel.kt`. **Tags:** V for both mechanisms below (code read
directly), E for the `DocumentFile.listFiles()` claim (platform behavior,
confirmed via Android's own `queryChildDocuments()` documentation and a
written account of real document providers hitting it — not run on-device
this pass).
**Where:** `data/ScannerImpl.kt` `scanRoot()` (the per-child `try`/`catch` and
`root.listFiles()`); `viewmodel/LibraryViewModel.kt` `startScan()`
(`seenNovelIds`, the `onScanCompleted` stale-reconcile block).

## Mechanism 1 — a skipped novel is reconciled as deleted

`ScannerImpl.scanRoot()` catches a per-child failure and moves on without
aborting the scan:

```kotlin
children.forEachIndexed { index, child ->
    if (child.isDirectory) {
        try {
            val novel = buildNovelEntity(root, child, title, authorsById, authorIdByNormalizedName)
            onDiscovered(novel, child)
        } catch (e: Exception) {
            // Don't let one bad/inaccessible novel folder abort the whole scan.
            onProgress(index + 1, total, "Skipped ${child.name}: ${e.message}")
        }
    }
}
onScanCompleted()
```

`onScanCompleted` fires unconditionally once the loop finishes — it has no
way to know a child was skipped rather than genuinely absent.

`LibraryViewModel.startScan()` only records a novel as "seen" after the
*whole* per-novel pipeline succeeds — `onDiscovered`'s own `try` wraps
`mergeNovelForRescan`, `db.novelDao().upsert(novel)`, **and** the
`seenNovelIds.add(novel.id)` call together, with chapter scanning after it in
the same block:

```kotlin
onDiscovered = { scanned, novelFolder ->
    try {
        val existing = db.novelDao().findById(scanned.id)
        val novel = mergeNovelForRescan(scanned, existing, authorsFolderFound)
        db.novelDao().upsert(novel)
        seenNovelIds.add(novel.id)
        // ... then scanChaptersForNovel ...
    } catch (e: Exception) {
        // scanned.title's id was never added to seenNovelIds
    }
},
onScanCompleted = {
    val stale = db.novelDao().all().filter { it.id !in seenNovelIds }
    val staleLocal = stale.filter { it.syncSourceUrl == null }
    staleLocal.forEach { db.novelDao().delete(it.id) }   // CASCADE
    // ...
}
```

So a novel skipped for either reason — `buildNovelEntity` throwing in
`ScannerImpl`, or `mergeNovelForRescan`/`upsert`/`scanChaptersForNovel`
throwing in the view model — never reaches `seenNovelIds`. At
`onScanCompleted` it is indistinguishable from a novel whose folder was
genuinely deleted. If it's a local novel (`syncSourceUrl == null`), `delete()`
fires, and `ON DELETE CASCADE` (see `data/Entities.kt`, same foreign keys
`data-safety.md` F3 cites) takes its `chapter_overrides`, `reading_progress`
and `chapter_read_events` with it in the same transaction — a manual edit, the
current reading position, and the whole streak history, gone, for a folder
that never actually left.

A transient SAF provider error (a flaky cloud-backed document provider, an SD
card or USB OTG drive momentarily busy, a single malformed `metadata.json`
throwing somewhere `buildNovelEntity` doesn't already catch) is exactly the
kind of thing that triggers this once and self-resolves on the next scan —
except the data it cascaded away on the bad pass doesn't come back with it.

## Mechanism 2 — a failed or partial directory listing reads as "zero novels"

Before the loop above even starts, `scanRoot()` does:

```kotlin
if (!root.canRead()) {
    onProgress(0, 0, "Library folder permission was revoked")
    return@withContext
}
// ...
val children = root.listFiles()
    .filterNot { it.isDirectory && it.name?.equals("authors", ignoreCase = true) == true }
```

`canRead()` and the outer `SecurityException` catch handle a *revoked* grant,
but `root.listFiles()` itself is trusted at face value. Per
`DocumentsContract.queryChildDocuments()`'s own documentation, a document
provider is allowed to return a cursor representing a subset — including an
empty one — while it is still fetching results, with nothing in the
`listFiles()` return value to distinguish that from "this folder has no
children." A provider hiccup (the same class of flakiness mechanism 1
already has to tolerate) or a removable volume dropping mid-enumeration can
therefore make an otherwise-healthy library scan see zero children. The loop
above runs zero times, `seenNovelIds` stays empty, and `onScanCompleted`
reconciles *every* local novel in the database as stale — full
cascade-delete.

**Severity:** High by the fold's own criteria — this is data loss (manual
chapter edits, reading position, and read-streak history) on a core flow
(library scan), reachable without the folder re-pick `data-safety.md` F3
already covers, and it can be triggered by nothing worse than a flaky
provider on an otherwise-untouched library.

## Fix, independent of F3/D3

None of the three steps below need F3's novel-identity decision first:

1. **Compute the id and mark it seen before any I/O that can fail.** The id
   is pure URI hashing (`buildNovelEntity`'s
   `UUID.nameUUIDFromBytes(root.uri + ":" + child.uri)` today, or whatever
   `data-safety.md` D3 lands on). Add it to `seenNovelIds` as soon as the
   child directory is found, not after `mergeNovelForRescan`/`upsert`/chapter
   scanning all succeed — a skip downstream of that point should still count
   as "this novel exists, something about reading it failed," not "this
   novel doesn't exist."
2. **Don't reconcile stale novels when the listing came back empty but the
   DB isn't.** If `children` (post-filter) is empty and
   `db.novelDao().all()` isn't, treat that as a signal the listing likely
   failed rather than a real "library now has zero novels," and skip the
   stale-reconcile step for that pass (surface a status message instead).
3. **Soft-delete instead of cascading.** When a local novel is confirmed
   genuinely gone, mark it `MISSING_LOCALLY` (reusing the status
   `data-safety.md`'s synced-novel path already has) with an explicit
   "Remove" action in the UI, instead of `db.novelDao().delete(it.id)`
   cascading immediately. This also removes most of the damage even if steps
   1–2 miss some other path into the same reconcile code.

Step 3 overlaps with — but doesn't require — `data-safety.md` F3's own
"soft-delete" suggestion for the root-re-pick case; implementing it here
covers both causes with one change.

## Repro (not yet run on a device)

1. Scan a library with at least one local (non-synced) novel so it is
   already in the DB.
2. Make exactly one scan pass either throw inside `buildNovelEntity`/
   `mergeNovelForRescan`/`upsert`/`scanChaptersForNovel` for that novel, or
   return zero children from `root.listFiles()` for the whole root (a mock
   `DocumentFile`/provider can simulate either; reproducing it against a real
   flaky provider needs a device).
3. Expected: the novel's row, and its `chapter_overrides`/
   `reading_progress`/`chapter_read_events`, are unaffected by a single bad
   pass. Actual (per the code read above): the row and all three are deleted
   in the same transaction.
