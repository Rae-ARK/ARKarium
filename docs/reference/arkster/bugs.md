# Bug Report — ARKster

Investigated against commit `44acecb` ("Added new features part 3").

Reported by the maintainer, three issues. Note up front: the maintainer's
running app is described as "at least 4 commits old" ahead of what's on
GitHub (author page / chapter redesign completed "until stage 3"), so parts
of Bug 3 below reference UI that isn't in this repo snapshot yet. Where
that's the case it's called out explicitly rather than guessed at.

---

## Bug 1 — "First image" issue, happens sometimes

**Status: needs reproduction detail.**

The report references a first screenshot showing this happening
intermittently, but the screenshot itself isn't available in this session
(only the text of the conversation carried over). Nothing in the
description alone (no filename, screen, or stack trace) is specific enough
to point at a code path with confidence, so this bug is not yet
root-caused. Needs one of:
- the screenshot re-attached, or
- a short repro description (which screen, what's expected vs. what shows,
  whether it's a cover image, a chapter image, or something else, and
  what "sometimes" correlates with — first scan vs. rescan, specific
  novel, cold start, etc.)

Will be root-caused and patched once that's available.

---

## Bug 2 — Afterword/interlude ordering inside arc folders

**Status: root-caused.**

**Symptom:** Within an arc folder, chapters like `Interlude` and `Afterword`
don't sort to the end of that arc's chapter list the way they should (e.g.
Arc 1 should read `... -> Ch 5 -> Interlude -> Afterword`), and instead land
wherever their filename happens to sort.

**Root cause:**

- `ScannerImpl.parseChapter()` (`app/src/main/java/com/arkster/app/data/ScannerImpl.kt:238`)
  only recognizes a chapter number via one heuristic: a leading integer
  followed by a space, e.g. `026 Not Again.txt`. Any file that doesn't
  match that pattern — including `Afterword.txt`, `Interlude.txt`,
  `Author's Afterword.txt` — falls into the fallback branch and gets
  `number = null`.
- Chapters are then read back out via
  `ChapterDao.forNovel/forArc` (`app/src/main/java/com/arkster/app/data/Dao.kt:92,95`),
  which is `ORDER BY number, title`. SQLite sorts `NULL` before any
  non-null value in ascending order, so every afterword/interlude/bonus
  chapter — having `number = null` — sorts to the *front* of the arc, not
  the back, and multiple such chapters within the same arc then sort
  alphabetically by title (`Afterword` before `Interlude`) rather than in
  any author-intended order.
- `MainActivity.loadNovelDetails()` (`app/src/main/java/com/arkster/app/MainActivity.kt:229-240`)
  takes that same DB order as `rawIndexById` and uses it as the default
  sort key (unless a manual `positionOverride` exists), so the bad
  ordering flows straight through to every screen that lists chapters.
- There is currently no keyword or marker-based detection at all for
  "this is bonus/closing content" — the scanner treats these files purely
  as untitled/unnumbered chapters.

**Proposed fix (already discussed, not yet in this patch):** a filename
marker convention read by the scanner, independent of English keywords:
- no prefix → regular chapter, ordered by its own number/filename as today
- `~` prefix → bonus/side content (interlude, extra chapter, SS, omake,
  side story, etc.), sorted after all regular chapters in that folder
- `!` prefix → closing/meta content (afterword, author's note), always
  sorted last in that folder
- optional number right after the symbol (e.g. `~2 Side Story.txt`)
  controls order within its own tier; otherwise falls back to filename
- the marker is stripped before display, so `!Afterword.txt` shows as
  "Afterword"
- for existing libraries with no marker, keep a best-effort keyword
  fallback (`afterword`, `interlude`, etc.) so nothing already scanned
  regresses to being unordered, but the marker is authoritative once
  present

This requires changes to `parseChapter()`/`parseChaptersInFolder()` in
`ScannerImpl.kt` to compute an explicit sort tier + in-tier order (rather
than relying solely on `ORDER BY number, title`), and likely a schema
addition (e.g. a `sortTier` column) so the ordering survives without
re-parsing on every read.

---

## Bug 3 — Arc covers not showing up / reader page cover fallback

**Status: partially root-caused; part of this depends on code not yet in
this repo.**

### 3a. Arc covers prepared per-arc aren't showing up (3rd screenshot)

- `ScannerImpl.scanChaptersForNovel()` already looks for a per-arc cover:
  `findCoverUri(arcFolder)` at `ScannerImpl.kt:170`, stored on
  `ArcEntity.coverUri` (`ScannerImpl.kt:171`).
- `findCoverUri()` (`ScannerImpl.kt:300-303`) only matches a file whose
  *entire* name is exactly `cover.jpg` / `cover.png` / `cover.webp`
  (`Regex("(?i)cover\\.(jpg|png|webp)$")` matched against the whole
  filename, case-insensitively, but no other characters allowed before
  `cover`). Any arc cover named differently — `Arc1_Cover.jpg`,
  `cover (1).png`, `Cover_Art.jpg`, anything with a suffix/prefix/space —
  is silently skipped and `arcCoverUri` ends up `null`.
- So whether this reproduces depends on the exact filenames used per arc.
  Worth confirming the maintainer's actual arc cover filenames against
  that regex; if they don't match it exactly, that's the whole bug.
- Separately: `arcDao().upsert(arc)` runs on every scan, so as long as
  `findCoverUri` *does* resolve a URI, it should persist correctly —
  this looks like purely a filename-matching gap, not a persistence bug.

### 3b. Reader page should show arc cover -> fiction cover -> placeholder

- This repo's `ReaderScreen.kt` (as of `44acecb`) has no cover image logic
  at all — the only image-ish element on the page is `ReaderAuthorAvatar`
  (`ReaderScreen.kt:487-508`), which renders the *author's* avatar with an
  emoji placeholder, not a novel/arc cover.
- The maintainer's note that "the cover on the reader's page which you've
  added" implies this exists in their local build (ahead of what's
  pushed). Since that code isn't in this repo snapshot, the exact current
  fallback behavior there can't be inspected directly yet — only the
  requirement can be recorded here:
  1. If the chapter being read belongs to an arc, and that arc has a
     `coverUri`, use it.
  2. Else if the novel has a `coverUri`, use it.
  3. Else show the existing placeholder.
- Once the newer reader-header code is available (either pushed, or
  pasted in), this needs to be pointed at `arcId` -> `ArcEntity.coverUri`
  -> `NovelEntity.coverUri` -> placeholder, in that order, rather than
  whatever single source it currently reads from.

---

## Summary of concrete code-level causes found

| Bug | File | Line(s) | Cause |
|---|---|---|---|
| 1 | — | — | Unknown — needs screenshot/repro |
| 2 | `ScannerImpl.kt` | 238-250 | No sort-tier concept; unnumbered chapters get `number = null` |
| 2 | `Dao.kt` | 92, 95 | `ORDER BY number, title` puts `NULL` first, not last |
| 2 | `MainActivity.kt` | 233, 240 | Default chapter order inherited straight from DB order above |
| 3a | `ScannerImpl.kt` | 300-303 | `findCoverUri` regex requires filename to be exactly `cover.<ext>` |
| 3b | `ReaderScreen.kt` | (not present in this commit) | No arc/novel/placeholder cover fallback chain on this screen yet |

---

## Stage 2 status (this patch)

Fixed:

- **Bug 2** — `ScannerImpl.parseChapter()` now reads a `~`/`!` filename-prefix
  marker (bonus/closing content) and returns an explicit `sortTier`, stored
  on a new `ChapterEntity.sortTier` column (migration `MIGRATION_7_8`, DB
  v7 → v8). `ChapterDao.forNovel`/`forArc` now `ORDER BY sort_tier, number,
  title` so bonus/closing content always lands after every regular chapter,
  closing always last, instead of relying on `number` alone (which was
  `null` — and so sorted first — for anything unnumbered). A keyword-based
  fallback (`afterword`, `interlude`, `side story`, `omake`, `extra
  chapter`, `bonus chapter`) still applies to files with no marker, so
  existing libraries don't regress until they're renamed to use the new
  convention. `ScannerImpl.CURRENT_SCAN_VERSION` bumped 4 → 5 so every
  already-scanned novel gets reparsed once and picks up tiers on next scan.
- **Bug 3a** — `findCoverUri()` now prefers an exact `cover.<ext>` match
  (unchanged default), but falls back to any image in the folder whose
  name contains "cover" as a standalone word (`Arc1_Cover.jpg`,
  `cover (1).png`, `COVER_ART.jpg`, etc.), so per-arc covers that don't
  happen to be named exactly `cover.*` are picked up.
- **Extra, requested this round**: author avatars now also resolve via a
  same-folder convention file — `authors/<id>.png` or `authors/<name>.png`
  (jpg/webp also accepted) — the same pattern as a novel/arc's `cover.*`.
  This only kicks in when `author.json` has no `"avatar"` field, or that
  field doesn't resolve to a real file; an explicit `"avatar"` in the JSON
  still wins when present. New helper: `findAuthorAvatarUri()` in
  `ScannerImpl.kt`.

Still open:

- **Bug 1** — still needs the screenshot/repro detail from the original
  report; not addressed in this patch.
- **Bug 3b** — the reader-page cover fallback chain (arc → novel →
  placeholder) still can't be patched here since that UI isn't in this
  repo snapshot yet. Once it's pushed (or pasted in), it needs to read
  `arcId` → `ArcEntity.coverUri` → `NovelEntity.coverUri` → placeholder in
  that order.

### Files touched this stage
- `app/src/main/java/com/arkster/app/data/Entities.kt` — `ChapterEntity.sortTier`
- `app/src/main/java/com/arkster/app/data/Dao.kt` — `ORDER BY sort_tier, number, title`
- `app/src/main/java/com/arkster/app/data/AppDatabase.kt` — DB v7 → v8, `MIGRATION_7_8`
- `app/src/main/java/com/arkster/app/data/ScannerImpl.kt` — marker-based `parseChapter`, broadened `findCoverUri`, new `findAuthorAvatarUri`, `CURRENT_SCAN_VERSION` 4 → 5

### Not yet in this patch, worth confirming
- Whatever number-formatting an author uses after a marker (`~2 ...`) is
  parsed with the same plain `(\d+)\s+` heuristic as regular chapters —
  fine for `~2 Side Story.txt`, but a marker glued straight to a number
  with no space (`~2Side Story.txt`) won't match and falls back to
  filename-order within its tier. Flag if that's a real pattern in use.

---

## Stage 3 status

Fixed:

- **Bug 3b** — the reader page now shows a cover thumbnail next to the
  fiction-title breadcrumb, resolved arc → fiction → placeholder exactly
  as requested:
  - `MainActivity.kt` (the `Screen.Reader` branch) now computes
    `readerCoverUri = arcs.firstOrNull { it.id == reader.chapter.arcId }?.coverUri
    ?: novel?.coverUri` and passes it into `ReaderScreen` as a new
    `coverUri: String?` parameter (defaults to `null`, so this is
    source-compatible with any other call site).
  - `ReaderScreen.kt` renders it via a new `ReaderCoverThumbnail`
    composable — same `AsyncImage`-or-emoji-placeholder pattern already
    used by `ReaderAuthorAvatar`, so a genuinely missing cover (neither
    arc nor fiction has one) shows the 📕 placeholder instead of a blank
    gap or a crash.
  - The resolution order is deliberately done in `MainActivity.kt`, not
    `ReaderScreen.kt`, matching how `novelTitle`/`arcTitle` are already
    resolved outside the screen composable — keeps `ReaderScreen` decoupled
    from `ArcEntity`/`NovelEntity`.

Still open:

- **Bug 1** — still needs the screenshot/repro detail from the original
  report; not addressed in this patch either. Nothing in the three stages
  so far touches whatever "first img" refers to.

### Files touched this stage
- `app/src/main/java/com/arkster/app/ui/ReaderScreen.kt` — `coverUri` param, `ReaderCoverThumbnail`
- `app/src/main/java/com/arkster/app/MainActivity.kt` — resolves `readerCoverUri` and passes it through

---

## Stage 4 status

Fixed:

- **Bug 2 follow-up — closing/bonus content still sorting to the bottom of
  the whole novel, not the end of its own arc.** Reported again after
  Stage 2's fix shipped: an arc's Afterword-type file (whatever marker or
  keyword puts it at `sort_tier` 1/2) was landing at the very bottom of the
  novel's full chapter list instead of right after that arc's own last
  chapter.

  **Root cause:** Stage 2's fix (`ORDER BY sort_tier, number, title` on
  `ChapterDao.forNovel`) sorts `sort_tier` *globally across the entire
  novel* - it has no idea arcs exist. So every tier-0 (regular) chapter
  from *every* arc sorts before *any* tier-1/2 chapter from *any* arc, and
  only then do the tier-1/2 chapters get ordered among themselves. An early
  arc's Afterword (tier 2) therefore sorts after a later arc's regular
  chapters, landing at the bottom of the whole book rather than the end of
  its own arc.

  This didn't show up in the per-arc detail tab (`NovelDetailScreen`
  filters the same flat list down to one `arcId`, and filtering preserves
  relative order - which happens to still be correct *within* one arc's
  subset, since that arc's own tier-0 items are always ahead of its own
  tier-2 items in the global order too). It only surfaces in "All
  Chapters" and in chapter-to-chapter Previous/Next navigation
  (`MainActivity`'s Previous/Next resolution walks this same flat
  `chapters` list - see `AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md` Stage 3),
  both of which showed the bug exactly as reported.

  **Fix:** `ChapterDao.forNovel` now groups by arc *before* applying
  `sort_tier`/`number`/`title` - root-level chapters (`arc_id IS NULL`,
  i.e. the novel folder itself, always scanned before any arc subfolder -
  see `ScannerImpl.scanChaptersForNovel`) come first, then each arc in its
  own `ArcEntity.position` order, and only *within* that grouping does the
  tier/number/title tie-break apply. A `LEFT JOIN arcs` supplies
  `arcs.position` for the ordering; the query still projects only
  `chapters.*` so it maps straight to `ChapterEntity` as before. No schema
  change, no migration - this was purely a query-ordering bug.
  `ChapterDao.forArc` was already correct (it only ever queries one arc at
  a time, so there was nothing to group) and is unchanged.

Still open:

- **Bug 1** — still needs the screenshot/repro detail from the original
  report; not addressed in this or any prior stage.

### Files touched this stage
- `app/src/main/java/com/arkster/app/data/Dao.kt` — `ChapterDao.forNovel` now arc-grouped before tier/number/title

---

## Stage 5 status

Fixed:

- **Arc folder sort order.** Reported: arcs weren't listing in their
  intended order at all (independent of the chapter-tier bug above - this
  is about the *arcs themselves*, e.g. tabs reading "Arc 2, Arc 1, Arc 3").

  **Root cause:** `ScannerImpl.scanChaptersForNovel` builds `arcFolders`
  straight from `novelFolder.listFiles()`, `.filter`ed down to folders
  matching `ARC_PATTERNS`, then assigns `ArcEntity.position = idx` off
  that filtered list's enumeration order. SAF's `DocumentFile.listFiles()`
  makes no ordering guarantee at all - most providers return entries in
  filesystem/creation order, not alphabetically - so `position` was
  effectively whatever order the storage provider happened to return
  folders in, not the arc's actual numeric order.

  **Fix:** `arcFolders` is now explicitly sorted before `position` is
  assigned, using a new `arcSortNumber()` helper that extracts the numeric
  group `ARC_PATTERNS` already captures (e.g. `2` out of `"Arc 2 -
  Whatever"`) and sorts on that *numerically* - not lexicographically,
  since a plain string sort would still get `"Arc 10"` before `"Arc 2"`
  wrong the same way the missing sort did. A folder that matches an arc
  pattern but has no number after it (just `"Arc"`/`"Volume"` alone) falls
  back to sorting after every numbered arc, by name. `CURRENT_SCAN_VERSION`
  bumped 5 → 6: `scanChaptersForNovel` returns early on an unchanged
  fingerprint *before* it ever reaches arc detection, so an already-scanned
  novel with arcs stored in the old, possibly-wrong order needed one more
  forced full rescan to self-correct rather than silently keeping stale
  positions forever.

- **Arc covers not appearing anywhere on the fiction page.** Reported as
  "I thought I fixed it" - understandably, since Bug 3a above *did* fix
  arc cover resolution during scanning, and Bug 3b *did* wire
  `ArcEntity.coverUri` into the reader page's small breadcrumb thumbnail.
  Neither of those touches the fiction/table-of-contents page
  (`NovelDetailScreen`) at all - it was never wired to show an arc's cover
  anywhere, tabs included, so a correctly-resolved `ArcEntity.coverUri`
  still had nowhere to render on this specific screen. Not a regression of
  either prior fix - a gap neither one was scoped to close.

  **Fix:** `NovelDetailScreen` now shows a small cover thumbnail + arc name
  + chapter count header whenever an actual arc tab (not "All Chapters")
  is selected, using the same `NovelCoverThumb` composable and 📚
  placeholder-on-missing-cover behavior the novel-level header above it
  already uses. Reusing that composable surfaced a latent sizing bug in it
  worth calling out separately: its `AsyncImage` hardcoded
  `.fillMaxWidth().height(136.dp)` regardless of the *caller's* modifier,
  which happened to be invisible while the composable was only ever used
  at exactly that size (the novel header) - reused at the arc header's
  smaller 48x68dp size, the image would have overflowed/clipped inside a
  much smaller Box. Changed to `.fillMaxSize()` so it actually honors
  whatever size the caller passes in.

- **"All Chapters" ordering (arc 1's chapters, then arc 2's, then arc
  3's, ...).** No new code beyond the two fixes above plus Stage 4's -
  this is exactly what falls out once arc `position` is assigned correctly
  (this stage) and `ChapterDao.forNovel` groups by that position before
  applying tier/number/title within each group (Stage 4). Verified by
  re-reading both queries together rather than re-testing in isolation:
  root-level chapters (no arc) first, then each arc's own chapters in
  `position` order, tier/number/title breaking ties only within a single
  arc's block - never spilling into a neighboring arc the way Stage 4's
  bug did.

### Files touched this stage
- `app/src/main/java/com/arkster/app/data/ScannerImpl.kt` — `arcFolders` sorted by `arcSortNumber()` before `position` assignment; `CURRENT_SCAN_VERSION` 5 → 6
- `app/src/main/java/com/arkster/app/ui/NovelDetailScreen.kt` — per-arc cover header; `NovelCoverThumb` sizing fix (`fillMaxSize()` instead of a hardcoded height)

---

## Bug 4 — Manual rescan can make novels disappear from the library

**Status: root-caused and fixed.**

**Symptom:** Tapping "Rescan Library" in Settings could make the whole
library flash empty while it re-populated, and in some cases a novel would
stay missing from the list afterward even though nothing about that
novel's folder had actually changed.

**Root cause:**

- `SettingsScreen`'s `onRescan` called `novels.clear()` (`MainActivity.kt`,
  in the `Screen.Settings` composable) immediately before `startScan()`,
  wiping the in-memory `novels` list up front rather than reconciling it
  against a completed scan.
- `novels` is never loaded from the DB directly anywhere else in the app —
  it's built *only* by `scanRoot`'s `onDiscovered` callback firing once per
  novel folder found. So clearing it first meant the Home screen showed an
  empty library until each novel trickled back in asynchronously.
- Worse: `scanRoot`'s per-child loop (`ScannerImpl.kt`) already
  catches and skips (rather than propagates) an exception for any single
  novel folder it can't read, via `onProgress("Skipped $name: ...")`. A
  novel skipped this way never calls `onDiscovered`, so after a
  `novels.clear()` it simply never comes back — permanently missing from
  the UI after one transient SAF hiccup during a rescan, even though its
  row and all its chapters/arcs were still completely intact in the DB.
- Unlike arcs, chapters, and authors — which all already have a
  diff-and-remove pattern (`seenArcIds`/`seenChapterIds` in
  `scanChaptersForNovel`, `seenIds` in `onAuthorsDiscovered`) — novels
  themselves had no equivalent stale-removal step against the DB at all.
  `novels.clear()` in Settings was a UI-only stand-in for that missing
  piece, and didn't actually delete anything from the DB either: a
  genuinely deleted novel's row (and its arcs/chapters/chapter_overrides/
  reading_progress/scan_fingerprint rows) would sit orphaned forever.

**Fix:**

- `ScannerImpl.scanRoot` now takes an `onScanCompleted` callback, fired
  exactly once, only after the children loop finishes a full,
  uninterrupted pass — never on the early `SecurityException`/"folder no
  longer accessible" returns, and never from a single skipped child. This
  is the signal that it's actually safe to reconcile "which novels still
  exist" against the DB.
- `MainActivity.startScan` now tracks `seenNovelIds` as novels are
  successfully upserted during the scan, and in `onScanCompleted` diffs
  that against `db.novelDao().all()`, deleting any DB row not seen (which
  cascades to that novel's arcs/chapters/chapter_overrides/reading_progress/
  scan_fingerprint rows via their existing `onDelete = CASCADE` foreign
  keys — see `Entities.kt`) and removing the same IDs from the in-memory
  `novels` list. Added `NovelDao.delete(novelId)` to support this.
- Removed the `novels.clear()` call from Settings' `onRescan` entirely —
  the list is now only ever trimmed by this reconciliation step, after a
  scan has actually confirmed a novel's folder is gone, not preemptively.

**Accepted tradeoff:** same as the existing arc/chapter pattern — a novel
folder that fails to read for a transient reason during one scan pass is
still treated as "gone" and removed, since `seenNovelIds` only gets an ID
added on a successful upsert. This mirrors `seenChapterIds`/`seenArcIds`'s
existing behavior rather than introducing new risk; it just no longer adds
the *additional* failure mode of clearing everything before the scan even
starts.

### Files touched this stage
- `app/src/main/java/com/arkster/app/data/ScannerImpl.kt` — new `onScanCompleted` param on `scanRoot`, fired after a genuine full pass
- `app/src/main/java/com/arkster/app/data/Dao.kt` — new `NovelDao.delete(novelId)`
- `app/src/main/java/com/arkster/app/MainActivity.kt` — `seenNovelIds` tracking + reconciliation in `startScan`'s `onScanCompleted`; removed `novels.clear()` from Settings' `onRescan`
