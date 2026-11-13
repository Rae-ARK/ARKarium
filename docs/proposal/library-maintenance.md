# Library Settings: maintenance, import/export, and diagnostics

> **Kind:** proposal · **Status:** design only, nothing built · **Verified:** 2026-11-13 @ (this patch)
> **Origin:** a review of a reference app's Library Settings screen (2026-11-13), split
> by the owner into items to implement now and items to document for a later pass. The
> implemented half (Scanning behavior's four items, Storage & organization's breakdown/
> clear-cache/open-folder, Remove missing novels, Wi-Fi-only sync) landed in
> `ui/LibrarySettingsScreen.kt` and its backing `PreferencesManager`/`LibraryViewModel`/
> `LibraryFileWatcher`/`LibraryScanWorker` changes - see those files' own doc comments.
> This doc covers everything from that review that wasn't tagged "Implement this".

Each section below is a `docs/proposal`-style sketch, not a committed plan: what the
item is, why it isn't built yet, and what it would need. Promote one to
[`../implementation/`](../implementation/README.md) (its own file, split into rungs)
when someone commits to building it.

## Scanning behavior

**Scan log / history** - what got added, changed, or removed on the last scan pass,
so "why didn't my new chapter show up" has somewhere to look. Needs a new table
(`scan_log_entries`: timestamp, novel id, kind `ADDED`/`CHANGED`/`REMOVED`/`SKIPPED`,
a short reason string for `SKIPPED`) written by `handleNovelDiscovered`/
`handleScanCompleted` (`LibraryViewModel.kt`) and `LibraryScanWorker.doWork`, plus a
simple list screen off Library Settings. The two scan paths already produce most of
this information as `_scanMessage` strings - the work is making it structured and
persisted instead of a single overwritten in-memory `String`, and deciding a
retention/trim policy (a novel-dropping-in party could otherwise grow this table
without bound).

**Rename/reorganize detection rules** - `ScannerImpl`'s chapter-numbering heuristic
(filename pattern -> chapter number/order) is invisible today; a user whose files
don't match it just gets a wrong or missing order with no explanation. Two possible
directions: (a) a read-only "Here's the pattern ARKarium expects" help card linking
filename examples to what each part means, or (b) letting the user pick from a short
list of supported conventions (`Chapter 12`, `012 - Title`, bare `12.txt`, etc.) and
validating against it at scan time. (b) is more useful but a materially bigger scope -
it would need the convention to become a per-novel (or per-library) stored preference
`ScannerImpl.parseChapter` reads, and a way to show which files in a folder didn't
match whatever convention is active.

## Library health / maintenance

**Validate library** - a dry-run pass that reports problems (duplicate chapter
numbers within a novel, unparseable filenames `ScannerImpl.parseChapter` skipped,
novels missing a cover, `chapter_overrides` rows pointing at a chapter id that no
longer exists) without writing anything. Shares most of its walk with a normal scan
(`ScannerImpl.scanRoot`/`scanChaptersForNovel`) but needs a second, read-only
variant of `onDiscovered`/chapter-parsing that collects findings into a report
instead of upserting rows - probably a new `ScannerImpl.validateRoot` alongside
`scanRoot` rather than a flag threaded through the real one, since the two have
different enough contracts (one mutates the DB, one must not) to be worth keeping
separate.

**Rebuild index / full rescan** - a scan that ignores whatever
fingerprint/mtime-based shortcut a future performance pass adds (see
[`performance.md`](performance.md) F24-F28) and re-reads every file from scratch.
Today's `startScan`/`LibraryScanWorker` are already effectively this, since there is
no such shortcut yet to bypass - this item only becomes meaningful once one exists,
at which point it's a second entry point into the same scan path with that shortcut
forced off, not a new scan implementation of its own.

## Import / export / backup

**Export reading progress / library metadata** - a single JSON file (every
`NovelEntity`'s id/title/author plus its `ReadingProgressEntity` and
`notifyNewChapters`) via `ACTION_CREATE_DOCUMENT`, so progress survives copying the
novel folders themselves to a new device. The novel-id formula
(`UUID.nameUUIDFromBytes` over root-uri + child-uri, see `ScannerImpl.novelIdFor` and
`computeStorageBreakdown`'s duplicate of it) is derived from folder identity, not
novel content - an export has to key rows by something stable across a folder copy
(title/author, or a recomputed id against the new install's own root) rather than the
exported id verbatim, or every entry will simply fail to match anything on import.

**Import from another device's export** - the natural pair to the above: read that
same JSON, resolve each entry against novels the importing install already scanned
(via the title/author key above), and merge `ReadingProgressEntity`/
`notifyNewChapters` onto matches. Needs a conflict rule for a novel that already has
newer local progress than the import - "newest `updatedAt` wins" is the obvious
default, but should be confirmed before building rather than assumed.

## Sync-adjacent

**Check-for-updates frequency** - today's `NewChapterCheckWorker` runs on a fixed
6-hour `PeriodicWorkRequestBuilder` interval (see that file's `schedule()`); this item
is a Library Settings control to change that interval instead. `PeriodicWorkRequest`
can't have its interval changed in place - `schedule()` would need to take the
interval as a parameter and re-enqueue with `ExistingPeriodicWorkPolicy.UPDATE` (the
same shape `wifiOnlySync`'s reactive collector in `MainActivity.onCreate` already
uses to apply a *constraint* change live), with a reasonable floor (WorkManager
itself won't run periodic work more often than 15 minutes) to stop a Library Settings
control from implying a cadence the OS won't actually honor.

## Diagnostics

**Permission/access status** - a plain "library folder: accessible" vs. broken-state
row. Mostly bookkeeping now that the library root is a fixed
`Android/media/com.arkarium.app/` folder rather than a user-picked SAF tree (see
`LibraryScan.kt`'s `resolveLibraryRoot` doc comment) - there's no permission grant
left to go stale the way the old custom-folder picker could. The check that's still
meaningful is `libraryRootFile(context) != null` and `.isDirectory`/`.canRead()` on
the result (the same null case `resolveLibraryRoot`, `computeStorageBreakdown`, and
`startLibraryFileWatcher` already treat as "shared storage isn't available right
now") - this item is really just surfacing that existing check as a status row
instead of only showing up as a scan-setup error.

**Send diagnostic info / logs** - a share-sheet export of recent `_scanMessage`
history (see Scan log/history above - this item and that one would likely ship
together, since there's little to send without it), the device's Android version/
OEM, and whatever the crash recorder already has (`ArkariumApp`'s uncaught-exception
handler, the same trace `MainActivity`'s `renderCrashScreen` shows after a crash).
Given how much of this app's correctness depends on filesystem quirks that vary by
device/OEM (SAF listing races, `FileObserver` behavior differences - see
`LibraryFileWatcher`'s own doc comment on the API 29 split), a one-tap diagnostic
bundle would meaningfully shorten the back-and-forth on a bug report that starts
"it doesn't see my files."
