# Performance: SAF I/O, build/startup, recomposition, images, database and scan cost

> **Kind:** proposal · **Status:** F14, F15, F25, F26 and F28 fixed in code (all unmeasured — no device/benchmark run); F27 reviewed and deferred, no code change (see below); F24 and everything else not yet built; D4 (toolchain) and D6 (splash policy) decided, measurement should come before the rest · **Verified:** 2026-11-13 @ 56c19c4 (F15's shared `ImageLoader` only — F25/F26/F28 fixes and F27's deferral as of the prior patch, F24 and the rest as of 2026-11-12, the F14 additions/closure as of that same pass)
> **Origin:** [`engineering-review.md`](engineering-review.md) F5, F12–F15, F24–F28.

### F5. SAF scan and sync cost several provider queries per file

**Severity:** Medium. **Tags:** V (query pattern read in code), E (binder-transaction cost not re-measured this pass).
**Where:** `data/ScannerImpl.kt` `computeFingerprint()` and the chapter-parsing
loop (`listFiles()`, per-entry `isFile`, `lastModified()`, `length()`);
`data/SyncManager.kt` (per-file `dir.findFile(name)?.delete()`).

**What the code does:** fingerprinting a novel folder walks every file and
reads `isFile`, `lastModified()` and `length()` on each — on a SAF
tree-backed `DocumentFile` (one the user picked via the folder chooser,
*not* the app-private default, see Scope below) each of those is its own
`ContentResolver` query into the storage provider, i.e. roughly three
provider round-trips per chapter file. This scan runs on every launch
(`computeFingerprint` feeds the "did anything change" check `startScan`
makes before deciding whether to rescan at all). `SyncManager`'s per-file
delete calls `dir.findFile(name)`, which itself is implemented as
`listFiles()` plus a linear name comparison — so deleting N files out of one
arc folder is roughly O(N²) `listFiles()`-equivalent provider queries, not
O(N).

**Scope — who this actually affects:** the default library root comes from
`DocumentFile.fromFile(context.getExternalFilesDir(...))`, which is backed by
plain `java.io.File` and is cheap — none of the above applies to a user who
never picked a custom folder. This is specifically a problem for users who
chose their own SAF-backed library folder.

**Direction:** one `DocumentsContract.buildChildDocumentsUriUsingTree` query
per directory, projecting `DOCUMENT_ID`, `DISPLAY_NAME`, `MIME_TYPE`, `SIZE`
and `LAST_MODIFIED` together, building a name → metadata map for that
directory once and sharing it between the fingerprint scan and `SyncManager`'s
delete/diff pass, instead of each issuing its own per-entry queries. Rough
effect: turns about 3N provider queries into about 1 per directory for
fingerprinting, and turns `SyncManager`'s O(N²) into O(N).

**Depends on F3's decision:** any per-directory metadata cache keyed by
`novelId` assumes that id stays stable across rescans of the same folder —
see `data-safety.md` F3, which found that assumption doesn't currently hold
even for synced novels. Settle F3's identity question before building a
cache on top of it, or the cache itself becomes another place stale ids
accumulate.

### F12. R8 off, no baseline profile, no benchmarks, dated toolchain

**Severity:** Medium. **Tags:** V.
**Where:** `app/build.gradle.kts` (`isMinifyEnabled = false`,
`isShrinkResources = false`, Compose BOM `2023.10.01`, Kotlin `1.9.10`,
`kotlinCompilerExtensionVersion = "1.5.3"`, `compileSdk`/`targetSdk` 34); no
`benchmark` module or baseline profile anywhere in the project.

R8 is off by deliberate choice today (the comment in `build.gradle.kts` says
proguard rules for Room/Compose reflection aren't written or tested yet), not
an oversight — turning it on needs that rule-writing done first, not just a
flag flip. DEX layout optimization (what a baseline profile actually buys)
requires an R8-processed release build, so baseline profiles are blocked on
the same prerequisite. There are no Macrobenchmark tests in the project to
measure any of this against, before or after.

**Direction, in dependency order:** (1) write and test Room/Compose R8 keep
rules, turn `isMinifyEnabled` on; (2) add a `:benchmark` module with
Macrobenchmark, starting with cold startup and a chapter-list scroll as the
two journeys to track; (3) generate a baseline profile from those journeys.
Measure before and after each step rather than assuming the commonly-cited
improvement figures transfer to an app this size.

**Bundled in the same toolchain bump (D4):** Kotlin 2.x (unlocks
strong-skipping-by-default for F14), a current Compose BOM, and Navigation
2.8+ (unlocks predictive back, F17). KSP itself is no longer part of this
bump's case — it already replaced KAPT for F1/F2 against the current,
pre-2.x Kotlin plugin (backlog rung 9). Treat the remaining pieces as one
coordinated upgrade rather than three separate ones, since they share the
same compatibility matrix.

**Decided (D4):** yes, but staged rather than as one single bump — Kotlin
2.x plus the Compose compiler plugin first, then the Compose BOM, then
Navigation, each as its own change. Benchmark (once the `:benchmark` module
in step 2 above exists) between each step, so that if cold start or scroll
performance regresses, there's exactly one suspect rather than three
simultaneous candidate causes.

### F13. Splash: ~2.75s per cold start, per-frame recomposition, main-thread synthesis

**Severity:** Medium. **Tags:** V for what the code does; cost itself unmeasured (no Macrobenchmark exists yet, see F12).
**Where:** `ui/SplashScreen.kt`, `audio/SplashChordPlayer.kt`.

**What the code does:** the splash timeline is
`reconstructDurationMs(1400) + crossfadeDurationMs(380) + HOLD_MS(650) +
FADE_OUT_MS(320)` ≈ 2.75s on every cold start unless disabled in settings.
`val elapsedMs = playtime.value * totalMs` is read directly in the
composable body, so the entire `SplashScreen` composable recomposes every
frame of the animation rather than only the draw/layout phases that actually
need the new value. `SplashChordPlayer.play()` synthesizes the chord
(`GuitarChordSynth.buildCMajorStrumPcm16()`, five strings × 2.3s × 44.1kHz ≈
507,000 samples) inside a `DisposableEffect`, i.e. on the main thread, before
the first animated frame can show. The player requests no audio focus, and
music defaults on, so it plays over whatever else the user has playing.
There's no use of the Android 12+ `SplashScreen` API, so a device running 12+
shows its own system splash first, then this one — two splashes back to
back.

**Direction:**
- Adopt the `SplashScreen` API (`setKeepOnScreenCondition` /
  `setOnExitAnimationListener`) so the system splash hands off into this
  exit animation instead of showing both in sequence.
- Play the full ~2.75s sequence once per install/update (store a flag,
  compare against `BuildConfig.VERSION_CODE`), a short (~300ms) fade on
  every other launch, with a tap-to-skip always available.
- Move `elapsedMs`'s consumers into `Modifier.graphicsLayer { alpha = ... }`
  / the `Canvas` draw lambda instead of reading `playtime.value` in the
  composable body, so recomposition doesn't happen every frame.
- Move chord synthesis to `Dispatchers.Default`, or better, synthesize once
  and cache the PCM (or ship a small pre-rendered OGG) instead of
  regenerating it every cold start.
- Default the chord off, or request transient audio focus before playing it.

**Decided (D6):** full ~2.75s splash sequence once per install/update
(the `BuildConfig.VERSION_CODE` comparison already proposed above), a short
~300ms fade on every other launch, tap-to-skip always available, and the
chord **off by default** rather than requesting audio focus — simpler than
building and maintaining transient-focus handling for a one-off decorative
sound, and it removes F13's "plays over whatever else the user has playing"
problem outright instead of just making it briefer.

### F14. Lazy lists mostly unkeyed; derived data not remembered; non-lifecycle-aware Flow collection

**Severity:** Low–Medium. **Tags:** V for what's in the code, E for the Kotlin/Compose-compiler-version guidance.
**Where:** every `LazyColumn`/`LazyRow` under `ui/` (all now keyed — see
below) and every `collectAsState()` call site in `ui/` (all now
`collectAsStateWithLifecycle()` — see below).
**Status: fixed in code (V), unmeasured.** Nothing left open under `ui/`;
on-device measurement (recomposition counts, scroll jank) is still open,
same as the rest of this doc pending the Macrobenchmark setup in F12.

**`NovelDetailScreen.kt` — fixed in code (V), unmeasured.** Its three lazy
lists (`items(genreList)`, `itemsIndexed(arcs)`, `itemsIndexed(chaptersToShow)`)
now all supply `key =` — the genre string itself, `arc.id`, and `chapter.id`
respectively — and the arc/search chapter filtering that used to run directly
in the composable body is now wrapped in `remember(chapters,
selectedTabIndex.intValue)` and `remember(chaptersInArc, searchQuery)`, the
exact shape this section used to recommend. Expanding the description or
dismissing the cover viewer no longer reruns either filter, since neither
reads `descriptionExpanded` or `showEnlargedCover`. This also closes
`bugs/other/scrolling-bug.md` cause 5 in code, though that bug stays open
pending the on-device measurement its "Done when" asks for — scrolling itself
never changed the state those filters read, so cause 5 was always a weak fit
for *fling* jank specifically; the fix is good practice regardless and
unblocks `animateItem()` for this screen's lists (`animation-and-polish.md`
F16).

**Fixed in code (V), unmeasured.** `AuthorPageScreen.kt` and
`FictionBrowseScreen.kt` were already keyed from an earlier pass. This pass
added `key =` to `HomeScreen.kt`'s remaining two rows (`trendingNovels`,
`newReleases` — the third, `continueReadingNovels`, was already keyed),
`LegalDocumentScreen.kt`'s `itemsIndexed(sections)` (keyed on
`section.heading`, the closest thing that section list has to an id), and
`MetadataMatchDialog.kt`'s `items(candidates)` (keyed on `infoLink`, falling
back to a title+authors+publishedDate composite for the rare candidate with
no `infoLink`, since `NovelMetadataCandidate` has no id of its own). Every
`LazyColumn`/`LazyRow` under `ui/` is now keyed.

`collectAsState()` has been replaced with `collectAsStateWithLifecycle()` at
every site in `ui/`: the one in `Tts.kt` (`rememberChapterTts()`'s
`autoContinue`) and the six in `ReaderScreen.kt` (`keepScreenOnEnabled`,
`readerKeepScreenOnEnabled`, `fullscreenEnabled`, `breakLinesEnabled`,
`brightnessOverrideEnabled`, `brightnessLevel`). This needed adding
`androidx.lifecycle:lifecycle-runtime-compose:2.6.2` to
`app/build.gradle.kts` (the `collectAsStateWithLifecycle()` extension isn't
pulled in by `lifecycle-runtime-ktx`, which the project already depended on).
The project's upstream Flow source is DataStore, so the practical cost here
is small, but it's now the lifecycle-aware default everywhere in `ui/`
rather than a one-line change left pending per site. `MainActivity.kt`'s
`collectAsState()` sites are outside this finding's stated scope (`ui/`) and
are not touched here.

The project is on Kotlin 1.9.10 / Compose compiler 1.5.3. "Strong skipping"
became the Compose compiler's default behavior in Kotlin 2.0.20; before that,
a composable taking an unstable parameter type (a plain `List<T>`, for
example) is never skipped, so anything typed `List<ChapterEntity>` etc.
recomposes whenever its caller does, keys or no keys. This is part of the
same D4 toolchain-upgrade decision as F12.

**Before changing anything:** enable the Compose compiler's metrics/reports
output to see which composables are actually non-skippable today, rather
than guessing from the parameter types alone.

### F15. No shared image loader, no crossfade or placeholder on cover art — built

**Severity:** Low. **Tags:** V.
**Where:** `ArkariumApp.kt` now implements `ImageLoaderFactory`; Coil's
`AsyncImage` calls across `ui/` pick the shared loader up automatically.

`AsyncImage` with bounded sizes (already in place) is the right call for a
list — `rememberAsyncImagePainter` alone loads at the image's original
dimensions, and `SubcomposeAsyncImage` is too slow for a list context.
Previously there was no `Application` class to hold a single shared Coil
`ImageLoader`, so each `AsyncImage` call effectively got Coil's default
loader with no explicit shared memory/disk cache configuration, no
`crossfade(true)`, and no placeholder — covers popped in abruptly rather
than fading in, and nothing was shown while a cover was loading.

**Built:** `ArkariumApp` now implements `ImageLoaderFactory` and its
`newImageLoader()` builds one `ImageLoader` with a 200ms crossfade, a
25%-of-memory `MemoryCache`, and a 2%-of-disk `DiskCache` under
`cacheDir/image_cache`. Coil caches that loader itself once built, so every
`AsyncImage` call site picked it up with no per-call-site change needed —
see `animation-and-polish.md` F16 for the user-facing side of this (the
cover crossfade itself). Still open: no `placeholder` per call site, and the
planned full-screen cover viewer (`uiux.md`, "Arc covers are too small to
see") still needs its own full-resolution request separate from the list
thumbnail's bounded one — don't reuse the same request for both.

### F24. Foreign-key columns have no index

**Severity:** Medium. **Tags:** V (no `indices`/`Index(...)` on any entity, no `CREATE INDEX` in any migration), I (cost not measured; scales with total rows across the whole library).
**Where:** `data/Entities.kt`; the `forNovel`/`forArc`-style queries in `data/Dao.kt`; every `ON DELETE CASCADE`.

SQLite indexes a table's primary key and nothing else: a `ForeignKey(...)`
declaration does not create an index on its child column. Room only warns about
this. So these lookups and cascades are table scans:

| Table.column | Queried or cascaded by |
|---|---|
| `chapters.novel_id` | `ChapterDao` per-novel queries; novel delete |
| `chapters.arc_id` | chapters-in-arc query; the arc `SET_NULL` on arc delete |
| `arcs.novel_id` | `ArcDao` per-novel query; novel delete |
| `chapter_overrides.chapter_id` | override lookups and the join from the novel's chapters; chapter delete |
| `reading_progress.chapter_id` | chapter delete cascade |
| `scan_fingerprints.novel_id` | per-novel fingerprint lookup; novel delete |

**Correction to the first write-up of this finding:** three tables are already
covered by their keys and are *not* affected. `reading_progress` (primary key
`novel_id`), `chapter_read_events` (primary key `(chapter_id, read_date)`, so
`chapter_id` leads) and `synced_files` (primary key `(novel_id, relative_path)`,
so `novel_id` leads). `chapter_read_events.novel_id` is not a declared foreign
key at all.

**Why it matters:** the cascade is the worst case. Deleting a parent row makes
SQLite find every child row, and with no index on the child column that is a
full scan of the child table per deleted parent, then again for each table below
it. A rescan that removes several stale novels does this several times over,
and nearly every screen open runs one of the per-novel queries above. It is
invisible at tens of novels and grows linearly with chapter count.

**Direction:** add `indices = [Index("novel_id")]` and the other columns in the
table to the matching `@Entity` declarations. This is a schema change, so it
follows the F1/F2 rules in [`data-safety.md`](data-safety.md): a new version
(`APP_DATABASE_VERSION` is 14 today), an explicit `MIGRATION_14_15` that runs
`CREATE INDEX`, the exported schema committed, and a `MigrationTestHelper`
test for it. No destructive fallback. Measure first if a number is wanted: an
`EXPLAIN QUERY PLAN` on `chapters WHERE novel_id = ?` shows `SCAN` today and
`SEARCH ... USING INDEX` after.

### F25. The scan writes one row per transaction

**Severity:** Low–Medium. **Tags:** V (the pattern; there is no `withTransaction` or `@Transaction` anywhere in `app/`), I (the per-commit cost is not measured on a device).
**Where:** `data/ScannerImpl.kt` — the per-chapter `chapterDao().upsert(...)` in the chapter-parsing loop, the per-arc `arcDao().upsert(...)`, and the per-row stale deletes (`arcDao().delete`, `chapterDao().delete`).

Each bare suspend DAO call is its own implicit transaction, so a 300-chapter
novel does about 300 separate commits for one logical unit of work. Still O(n)
in chapters, but the constant is a journal commit, not a list append.

**Direction:** do each novel's upserts and stale deletes inside one
`db.withTransaction { }`, or use list-taking DAO methods (`@Upsert` over a
`List`, `DELETE ... WHERE id IN (:ids)`). One transaction per novel is also
the natural atomicity boundary: a scan that dies midway through a novel would
no longer leave it half-written, which relates to the rescan-safety rules
(`onNovelFolderSeen`) the scanner already depends on. Check that interaction
before changing the order of writes.

**Fixed in code**, unmeasured on a device. `scanChaptersForNovel` now wraps every
arc upsert, stale-arc delete, chapter upsert and stale-chapter delete, plus the
fingerprint save, in one `db.withTransaction { }` (`androidx.room.withTransaction`,
already available via the existing `room-ktx` dependency — no new one needed), so
one rescanned novel now does a single commit instead of one per row. The checked
interaction with `onNovelFolderSeen`: that callback fires in `scanRoot`, before
`scanChaptersForNovel` is even reached, so it's unaffected either way — a novel
whose transaction here throws is simply retried whole (not half-applied) on the
next scan, which is the atomicity boundary this finding asked for. `onProgress`
calls are deliberately kept outside the transaction block (see the code comment)
rather than wrapped inside it, so a slow or UI-bound progress callback can't hold
a SQLite transaction open.

### F26. The same folder is listed several times per novel

**Severity:** Low. **Tags:** V (the repeated calls), I (constant factor only).
**Where:** `data/ScannerImpl.kt` — `findCoverUri`, `readLocalMetadata`, `computeFingerprint` (twice), the arc-folder detection, and `parseChaptersInFolder` each call `listFiles()` on the same novel folder; `findAuthorAvatarUri` re-lists the whole `authors/` folder once per author.

**Scope correction:** the first write-up argued each `listFiles()` is a binder
round-trip through a `DocumentsProvider`. That was true of the SAF tree sources
this app no longer has (see F5 and "Settled decisions" in
[`../foundational/design-notes.md`](../foundational/design-notes.md)). The
library root is now `DocumentFile.fromFile(...)`, which is backed by plain
`java.io.File`, so each call is a local directory read, not IPC. The
redundancy is real but its cost is a few extra `readdir` calls per novel; don't
expect a visible scan-time change.

**Direction:** list each folder once, pass the `List<DocumentFile>` to the
helpers, and build the authors-folder avatar lookup once as a name → file map.
Mostly a readability and tidiness change; do it opportunistically when the
scanner is next touched, not as its own project.

**Fixed in code**, unmeasured (expected to be a tidiness win, not a visible
scan-time change — see the scope correction above). `findCoverUri` and
`readLocalMetadata` now take an already-listed `List<DocumentFile>` instead of
listing their folder argument themselves; `buildNovelEntity` lists the novel
folder once and passes that list to both. `scanChaptersForNovel` lists the novel
folder once (`novelChildren`) and shares it between `computeFingerprint`'s
root-level pass, arc-folder detection, and the root-level `parseChaptersInFolder`
call; each arc folder is likewise listed once and shared between `findCoverUri`
and that arc's `parseChaptersInFolder` call. `scanAuthorsFolder` lists
`authors/` once and builds the `.json` list and the avatar-image list from that
one listing, instead of `findAuthorAvatarUri`/`resolveAuthorAvatarUri` each
re-listing the whole folder per author.

**Not fully eliminated, by design:** `computeFingerprint`'s own per-subfolder
walk (needed for every subfolder's file metadata, arc-matching or not) still
runs before arc folders are listed for the arc loop, because it has to run
unconditionally — including on the common "nothing changed, skip rescan" path,
before the function even knows whether arc processing will happen at all. So on
an actual rescan (fingerprint changed), an arc folder is still listed once more
by `computeFingerprint`'s directory walk, on top of the once-shared listing arc
processing now uses — one remaining duplicate per arc folder, down from three.
Removing that last one would mean restructuring `computeFingerprint` to hand
back its own per-directory listings for reuse, which is more than this
finding's severity (Low) calls for; flagged here rather than silently
left unmentioned.

### F27. The scan is strictly sequential across novels

**Severity:** Low. **Tags:** V (sequential `forEachIndexed` in `scanRoot`), I (any gain unmeasured).
**Where:** `data/ScannerImpl.kt` `scanRoot`.

Each novel folder is discovered and scanned before the next starts. The work
is independent per folder, so in principle it could fan out. **Recommendation:
don't, yet.** SQLite has a single writer, so once F25 batches the writes the
parallel gain shrinks, and the loop carries ordering the rest of the app relies
on: `onProgress` counts, and `onNovelFolderSeen` being recorded *before* a
novel's pipeline runs so a failure still leaves the id marked as present. Fan-out
would have to preserve both. Revisit only if a measured scan on a large library
is still slow after F25.

**Reviewed, deferred — no code change.** F25 has landed (see above), which is
exactly the precondition this recommendation named for revisiting the question.
The recommendation itself still stands as written: nothing has been measured on
a device yet (this whole proposal doc is still unmeasured — see the status
line), and fanning out `scanRoot`'s per-novel loop would need `onProgress` and
`onNovelFolderSeen` to still behave correctly under concurrency, which is real
design work, not a quick follow-on to F25's transaction batching. Revisit once a
measured scan on a large library is shown to still be slow with F25 in place —
not before.

### F28. The streak calculation builds ten years of date strings to read the first few

**Severity:** Low. **Tags:** V.
**Where:** `data/ReadingStats.kt` `currentStreakDays` and `dayKeysBackwardFrom`.

`currentStreakDays` calls `dayKeysBackwardFrom(today, 3650)`, which eagerly
builds a 3,650-element `List<String>` (a `Calendar.add` and a date format each)
before the loop that walks it, and that loop stops at the first missing day, so
usually within days. The cost is O(3650) time and space per Home stats refresh
to answer an O(streak length) question; `readDates.isEmpty()` already
short-circuits only the empty case.

**Direction:** generate the keys lazily (a `Sequence` or an inline walk with
one `Calendar` stepping back a day at a time) so the work is proportional to the
streak. Keep the current "today may be unread, yesterday counts" rule exactly;
there are no tests around it, so add one for that rule first.

**Fixed in code.** `currentStreakDays` now walks one calendar day at a time with
a local `Calendar` and a `nextKey()` closure, instead of calling
`dayKeysBackwardFrom(today, 3650)` to build the full 10-year key list up front;
the loop stops at the first missing day exactly as before, so the real cost is
now proportional to the streak length rather than always 3,650 formatted dates.
The 3650-day cap is preserved as a hard safety bound on the walk itself, not
dropped. `dayKeysBackwardFrom` and `dayKeysBackward` are unchanged and still back
`windowStartKey`, which only ever needs a small, caller-supplied day count, not
the 3650-day case this finding was about. `ReadingStatsTest.kt` (new) covers the
"today may be unread, yesterday counts" rule, a mid-streak gap, a month/year
boundary crossing, and the 3650-day cap itself — none of which had any test
coverage before this.
