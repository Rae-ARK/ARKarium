# Performance: SAF I/O, build/startup, recomposition, images

> **Kind:** proposal · **Status:** nothing built yet; measurement should come before most of it · **Verified:** 2026-11-07 @ 5426a3d
> **Origin:** [`engineering-review.md`](engineering-review.md) F5, F12–F15.

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

**Bundled in the same toolchain bump (D4):** Kotlin 2.x (unlocks KSP for F1/F2
and strong-skipping-by-default for F14), a current Compose BOM, and
Navigation 2.8+ (unlocks predictive back, F17). Treat as one coordinated
upgrade rather than four separate ones, since they share the same
compatibility matrix.

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

### F14. Lazy lists mostly unkeyed; derived data not remembered; non-lifecycle-aware Flow collection

**Severity:** Low–Medium. **Tags:** V for what's in the code, E for the Kotlin/Compose-compiler-version guidance.
**Where:** every `LazyColumn`/`LazyRow` under `ui/` except
`ChapterEditorScreen.kt`; `ui/NovelDetailScreen.kt` (chapter filter/drop/take
in the composable body); `collectAsState()` call sites throughout `ui/`.

`items(...)` is called with no `key =` in `AuthorPageScreen.kt`,
`FictionBrowseScreen.kt`, `HomeScreen.kt` (three separate rows),
`LegalDocumentScreen.kt`, `MetadataMatchDialog.kt` and `NovelDetailScreen.kt` —
only `ChapterEditorScreen.kt` supplies one. Without a stable key, Compose
can't tell that an item in the next composition is "the same" item that moved
or changed, which both costs more recomposition when the list changes and
blocks `animateItem()` placement animation (see `animation-and-polish.md`
F16) from working correctly.

`NovelDetailScreen` re-runs `filter`/`drop`/`take` over the chapter list
directly in the composable body, so it reruns on every recomposition the
screen gets, not just when the chapter list or filter actually changes — at
a few hundred chapters this is a small but avoidable per-keystroke cost that
`remember(...)` around the inputs removes for free.

`collectAsState()` (not `collectAsStateWithLifecycle()`) is used throughout;
the lifecycle-aware variant is the recommended default on Android because it
stops collecting while the screen is backgrounded. The project's upstream
Flow source is DataStore, so the practical cost here is small, but it's a
one-line change per site.

The project is on Kotlin 1.9.10 / Compose compiler 1.5.3. "Strong skipping"
became the Compose compiler's default behavior in Kotlin 2.0.20; before that,
a composable taking an unstable parameter type (a plain `List<T>`, for
example) is never skipped, so anything typed `List<ChapterEntity>` etc.
recomposes whenever its caller does, keys or no keys. This is part of the
same D4 toolchain-upgrade decision as F12.

**Before changing anything:** enable the Compose compiler's metrics/reports
output to see which composables are actually non-skippable today, rather
than guessing from the parameter types alone.

### F15. No shared image loader, no crossfade or placeholder on cover art

**Severity:** Low. **Tags:** V.
**Where:** no `Application` subclass anywhere under `app/src/main` (checked —
none exists); Coil's `AsyncImage` calls across `ui/`.

`AsyncImage` with bounded sizes (already in place) is the right call for a
list — `rememberAsyncImagePainter` alone loads at the image's original
dimensions, and `SubcomposeAsyncImage` is too slow for a list context — but
there's no `Application` class to hold a single shared Coil `ImageLoader`, so
each `AsyncImage` call effectively gets Coil's default loader with no
explicit shared memory/disk cache configuration, no `crossfade(true)`, and no
placeholder — covers pop in abruptly rather than fading in, and there's
nothing shown while a cover is loading.

**Direction:** add an `Application` subclass implementing
`ImageLoaderFactory`, configure one `ImageLoader` with a shared memory and
disk cache and `crossfade(true)`, and give `AsyncImage` a `placeholder`. The
planned full-screen cover viewer (`uiux.md`, "Arc covers are too small to
see") needs its own full-resolution request separate from the list
thumbnail's bounded one — don't reuse the same request for both.
