# Next fixes — sync robustness

> **Status:** Known issues, not yet built. Found during a design-review pass
> over `SyncManager.kt` / `FictionLut.kt` / `ScannerImpl.kt` after the
> single-origin-by-name sync landed (see `docs/SYNC_MVP.md`). None of these
> block what's already shipped; all four are worth fixing before treating
> sync as production-solid, since sync is the mechanism the whole
> direct-to-reader model depends on being trustworthy.

---

## 1. `sync()`'s folder-recreation fallback can duplicate a novel

`downloadInitial()` and `sync()` handle "does this novel's folder already
exist" inconsistently:

```kotlin
// downloadInitial — checks for an existing folder first
val folder = libraryRoot.findFile(slug) ?: libraryRoot.createDirectory(slug) ?: throw ...

// sync() — does NOT check findFile first
val folder = findNovelFolder(libraryRoot, novel.id) ?: libraryRoot.createDirectory(slugForUrl(baseUrl)) ?: throw ...
```

`findNovelFolder` re-derives `novelId` from `root.uri + child.uri`
(`UUID.nameUUIDFromBytes`). If that ever fails to match an existing folder —
e.g. after switching storage mode in Settings, or a SAF re-permission grant
that mints a new tree URI (both explicitly called out in `ScannerImpl`'s own
comments as producing a fresh `novelId`) — `sync()` falls straight to
`createDirectory(slugForUrl(baseUrl))` with no existence check. Many SAF
providers don't enforce folder-name uniqueness, so this can silently create
a **second** folder with the same display name but a different underlying
document URI. The scanner then discovers two folders, each with its own
derived `novelId`; only the freshly-written one has current content, and the
old one becomes an orphaned duplicate that never updates again.

**Fix:** mirror `downloadInitial`'s `findFile`-before-`createDirectory`
pattern in `sync()`'s fallback branch.

**Also worth considering:** store the folder name on `NovelEntity` directly
(e.g. `syncFolderName`) instead of recomputing `slugForUrl(baseUrl)` every
time sync runs, so there's one fewer place where "recompute" and "look up"
can drift apart.

---

## 2. No graceful handling when a synced novel's folder disappears — in either direction

This needs to be a single explicit decision point, not two accidental ones.
Right now it's a race between two silent behaviors depending on which code
path happens to run first:

**Path A — local folder deleted, then a normal `startScan` runs** (app
launch, manual rescan, or any sync action that calls `startScan`):

`scanRoot()` just lists `root.listFiles()` — the deleted folder isn't
there, so `onDiscovered` never fires for it. `onScanCompleted`'s
stale-removal then deletes the `NovelEntity` row outright:

```kotlin
val staleIds = db.novelDao().all().map { it.id }.filter { it !in seenNovelIds }.toSet()
staleIds.forEach { db.novelDao().delete(it) }
```

`SyncedFileEntity` cascades away with it (`onDelete = ForeignKey.CASCADE`
on `novel_id`). The novel — and its whole sync relationship — is just gone.
No distinction between "user deleted a hand-added novel" (correct to just
remove) and "user deleted a synced novel that could simply be re-pulled"
(probably not what they wanted, or at least not without asking).

**Path B — local folder deleted, but "Check for updates" runs before any
`startScan` has cleaned up the DB row:**

```kotlin
val folder = findNovelFolder(libraryRoot, novel.id)
    ?: libraryRoot.createDirectory(slugForUrl(baseUrl))   // ← folder's gone, so this fires
    ?: throw IOException(...)
```

`findNovelFolder` returns null, falls straight to `createDirectory`, and
**silently redownloads the entire fiction** into a freshly created folder.
The user deleted it on purpose; the app just puts it back with no
confirmation.

**The reverse case** — the relay stops serving the fiction (author removed
it, or the LUT entry is gone) — has the same underlying gap: a 404 on
`manifest.json` throws a generic `IOException("HTTP 404 for ...")`,
indistinguishable at every call site from a transient network blip. Nothing
marks the novel's `syncSourceUrl` as dead; "Check for updates" just keeps
failing with the same opaque message forever, and local content (correctly,
per the offline-first design) stays fully readable but with no way to tell
the user "this source is gone, unlink it?"

**Fix:** treat both directions as the same missing-source problem, resolved
by asking the user rather than resolving it silently:

- Add a `syncStatus` column on `NovelEntity` — `ACTIVE`, `MISSING_LOCALLY`
  (folder gone, source still valid), `SOURCE_GONE` (relay 404s on manifest).
- `startScan`'s stale-removal should **not** auto-delete a novel that has a
  non-null `syncSourceUrl` — leave it for the user to resolve via a prompt
  ("This fiction's folder was removed — sync again, or remove it from your
  library?") instead of the scanner's cascade-delete.
- `sync()`'s fallback should not silently `createDirectory` when the local
  folder is missing — surface the same prompt instead.
- Distinguish a 404 on `manifest.json` from other `IOException`s in
  `SyncClient` (a distinct exception type, e.g. `SourceGoneException`) so
  `checkForUpdates` / `syncAllRaeArkNovels` can set `SOURCE_GONE` and word
  the prompt correctly, instead of a generic "Sync failed: ...".
- In `syncAllRaeArkNovels`, one `SOURCE_GONE` fiction currently aborts the
  whole batch (by design, per the existing code comment — "reported and
  stops the batch rather than silently skipping ahead"). Once `SOURCE_GONE`
  is a distinguishable case, the batch should skip past it and continue,
  reserving the abort-the-batch behavior for genuine, unexpected failures.

---

## 3. `FictionLut.lookup()` matching is too strict for real-world typing

```kotlin
private fun normalize(name: String): String =
    name.trim().lowercase().replace(Regex("\\s+"), " ")
```

Trim, lowercase, collapse whitespace — nothing else. No punctuation
stripping, no Unicode normalization (accents, smart quotes vs. straight
quotes). A user typing "Summoned By Mistake I Decided To Learn How To Live"
(no comma) or using a curly apostrophe from autocorrect gets a flat "not
found," even though the intended match is obvious.

The strictness is a deliberate choice (see the code comment: *"no fuzzy
matching, so a typo just means 'not found' rather than silently resolving
to the wrong fiction"*), which is the right call once the LUT has enough
entries that ambiguity is a real risk. With one entry today, it's pure
friction.

**Fix, in order of how much complexity is actually warranted right now:**

1. Strip punctuation (commas, colons, periods) before comparing, not just
   whitespace.
2. Normalize Unicode (NFKD) and fold smart-quote variants to their ASCII
   equivalents.
3. Only reach for real fuzzy (edit-distance) matching once the LUT has
   enough entries that ambiguity becomes a genuine risk — a unique
   substring/prefix match is safer and easier to reason about than a
   distance threshold while the table stays small.

---

## 4. `syncAllRaeArkNovels` rescans the entire library once per fiction

```kotlin
entries.forEachIndexed { index, (displayName, slug) ->
    ...
    startScan(libraryRoot)   // ← full scanRoot() pass, every iteration
    ...
}
```

`startScan` runs a complete `scanRoot()` over the *entire* library, not a
scoped scan of just the newly-downloaded folder. With N novels already in
the library and M being synced in this batch, this is roughly **O(M × N)**
SAF directory listings — every already-synced novel gets re-listed,
re-fingerprinted, and has its `metadata.json` re-read on every single
iteration of the loop, not just the one novel that actually changed. This
is very likely the cause of "sync feels slower than an older build" once a
library has grown past a handful of novels — a single-fiction sync
(`addFictionByName`, `checkForUpdates`) doesn't have this problem, since it
only ever needs one novel discovered.

The full-rescan-per-iteration choice is intentional per the existing
comment (*"so each one shows up in the library as soon as it's done,
instead of the whole list appearing to hang until the last download
finishes"*) — a reasonable UX goal, just an expensive way to achieve it.

**Fix:** call a scoped variant that only discovers/scans the one
just-downloaded folder — reusing `scanner.scanChaptersForNovel` directly
(the same call `scanRoot`'s own `onDiscovered` callback already makes
per-folder) instead of a full `scanRoot()` pass every loop iteration.
Preserves the "show up as it finishes" UX without paying for a full-library
rescan per fiction.
