# UI/UX bugs (consolidated)

> **Kind:** proposal · **Status:** open — 6 of 7 confirmed open by source read, 1 fixed, 1 needs on-device confirmation · **Verified:** 2026-11-09 @ ecf4228
> **Origin:** owner reports (two WhatsApp sessions, 2026-11-09) + a line-by-line re-check of every file each report names, against the commit above — not a transcription of the reports as given. Where a report's claim no longer matches source (fixed since reported, or a different file than named), that's called out explicitly rather than filed as-is.

**Tags used below:** **V** — verified directly by reading the cited lines in this
commit. **I** — inferred from the surrounding code; the mechanism is
consistent with the code but not confirmed on a device. **U** — unconfirmed,
flagged by the owner but not yet reproducible from source alone; needs a
device repro before it can be ranked.

Single-file by request, unlike every other entry in `other/`. Split into
one file per bug (matching `../README.md`'s format) once any of these needs
its own long-running fix thread.

---

## 1. WebView leak on leaving "About Me" — **already fixed, not open**

**Where:** `ui/WebViewScreen.kt`.

The report describes `AndroidView(factory = { WebView(context)... })` with no
release callback, so leaving the screen would detach the view without ever
calling `destroy()`, pinning the Activity `Context` via the WebView's native
Chromium engine.

**Current state (V):** the `AndroidView` call already carries
`onRelease = { webView -> ... }` that detaches the view from its parent,
calls `stopLoading()`, `clearHistory()`, then `destroy()` — in that order,
which matches Android's own WebView-memory guidance (destroying before
detaching is the ordering that guidance specifically says can still leak).
A comment directly above it documents the original leak and the fix. There
is nothing left to do here; kept in this file only so the report has a
record of being checked rather than silently dropped.

**Status:** Fixed. No action.

---

## 2. Persisted SAF folder permission is never released — **open**

**Where:** `MainActivity.kt`, `pickFolder` (lines 258–281).

```kotlin
private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
    uri?.let { selectedUri ->
        contentResolver.takePersistableUriPermission(selectedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ...
```

**Confirmed (V):** `releasePersistableUriPermission` does not appear
anywhere in `app/src/main`. Every "Use custom folder" → pick-a-folder round
trip takes a new persistable grant on `selectedUri`; the previous value of
`prefsManager.libraryUri` (about to be overwritten two lines later by
`setLibraryUri`) is never read back and released first.

**Why it matters:** Android caps persisted URI grants per app (OEM-dependent,
commonly low hundreds). A user who re-picks their library folder repeatedly —
new phone setup, moving files around, just trying "custom folder" a few
times — accumulates one permanent grant per pick with no corresponding
release. Eventually `takePersistableUriPermission` throws a
`SecurityException`: a crash with no visible cause, since nothing in the UI
suggests grants are piling up.

**Fix direction:** before (or immediately after) taking the new grant, read
the current `prefsManager.libraryUri`, and if it's non-null and different
from `selectedUri`, call
`contentResolver.releasePersistableUriPermission(oldUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)`
on it. Needs the old value read *before* `setLibraryUri` overwrites it —
`prefsManager` is a suspend API, so this has to happen inside the existing
`lifecycleScope.launch` block, ahead of the `setLibraryUri` call at line 272.

**Severity:** Low-frequency, high-severity-when-hit — an unrecoverable crash
with a cause invisible from the UI. Fix is small and self-contained.

---

## 3. Stale-novel reconciliation cascade-deletes reading history on a partial scan — **open**

**Where:** `viewmodel/LibraryViewModel.kt`, `startScan`'s `onScanCompleted`
callback (lines 160–196).

```kotlin
onScanCompleted = { sawAnyNovelFolders ->
    val allNovels = db.novelDao().all()
    if (!sawAnyNovelFolders && allNovels.isNotEmpty()) {
        // skip cleanup - listing looked totally empty, probably failed
    } else {
        val stale = allNovels.filter { it.id !in seenNovelIds }
        val staleLocal = stale.filter { it.syncSourceUrl == null }
        staleLocal.forEach { db.novelDao().delete(it.id) }   // cascades
        ...
```

**Confirmed (V):** `NovelEntity` deletion cascades through `chapters` →
`chapter_read_events` and `reading_progress` (the `ForeignKey(onDelete = CASCADE)`
chain in `Entities.kt`). "Stale novel cleanup" doesn't drop a row — it wipes
that novel's entire reading history and streak contribution.

**The mechanism:** the guard at line 171 only covers the all-zero case — a
scan that finds *no* folders at all. There's no equivalent guard for a
*partial* listing: a scan that finds some folders, but fewer than the DB
already has rows for. A SAF provider is allowed to return a partial or empty
listing (DocumentsProvider not fully warmed up, a removable/network-backed
`DocumentFile` tree still populating, ordinary I/O jitter right after process
start). When that happens, a novel genuinely still on disk is simply missing
from `seenNovelIds` for this one pass, gets diagnosed as "deleted from disk,"
and is cascade-deleted along with every read event it ever logged. Because
streak/weekly-count are computed as a flat `COUNT(*)`/`DISTINCT read_date`
over the whole `chapter_read_events` table rather than per-novel, losing one
unrelated novel's history is enough to zero the global streak even though
the novel the person is actively reading was never touched.

A shortcut launch (`MainActivity.kt` ~864, `pendingContinueReadingShortcut`)
widens the exposure further: it jumps into the reader the instant exactly one
novel is in `libraryViewModel.novels`, without waiting for `scanRoot()` to
finish enumerating the rest of the library — so the person can read, back
out, and have their own just-written `reading_progress` row land, all while
a scan pass is still in flight. If *that* pass's listing is the flaky one and
happens to miss the novel just read, the fresh save and the novel's whole
history both go in the same reconciliation pass — "both gone," distinct from
the "one unrelated novel's streak contribution silently vanishes" shape seen
on an ordinary relaunch.

**Fix direction:** treat "fewer novels seen than exist in the DB" with the
same caution the all-zero case already gets, instead of trusting one pass to
prove deletion — either require a local novel to be missing across two
consecutive scans before cascading the delete, or mark it
`MISSING_LOCALLY`-equivalent (non-destructive) the way synced novels already
are, and only actually delete on an explicit user action.

**Needs from the owner before fixing:** (1) custom SAF folder vs. default
internal storage — `DocumentFile.fromFile` essentially never returns a
partial listing, a real SAF tree is far more exposed; (2) rough library size,
since a longer `scanRoot()` pass widens the shortcut-launch race window.

**Severity:** Data loss (reading history, streak), triggered by ordinary use
rather than an edge case. Highest-severity item in this file.

---

## 4. "Fetch info" results can apply to a novel that's no longer on screen — **open**

**Where:** `viewmodel/MetadataViewModel.kt` (`fetchMetadataFor`, lines
49–63) and `MainActivity.kt` (the dialog `when` block, lines 2002–2042).

```kotlin
fun fetchMetadataFor(novel: NovelEntity) {
    metadataSearchState.value = MetadataSearchState.Loading(novel)
    viewModelScope.launch {
        val results = metadataProvider.search(novel.title)
        metadataSearchState.value = MetadataSearchState.Results(novel, results)
        ...
```

**Confirmed (V):** `metadataSearchState` is a single app-wide
`mutableStateOf` on the ViewModel, and the `when` block that renders it sits
**after** `NavHost`/`Surface`/`Scaffold` all close (lines 1998–2000) — it's a
top-level overlay, not scoped to whatever route is current. The search runs
on `viewModelScope`, which correctly survives navigation, but nothing
cancels or re-scopes it to "only matters while this `NovelDetail` is on
screen."

**Mechanism:** tap "Fetch info" on Novel A, back out to Home or open Novel
B's detail page before the lookup returns, and the moment it resolves, a
`MetadataSearchDialog` titled "Novel A" pops up over whatever's currently on
screen. Tapping a candidate at that point calls `applyMetadata(state.novel, ...)`
— `state.novel` is still Novel A, regardless of what's visually in front of
the person — writing fetched metadata onto a novel they aren't even looking
at, with nothing in the UI to flag what just happened. The `Error` state has
the same shape: a stale "couldn't reach metadata source" dialog can surface
long after the person has moved on.

**Fix direction:** either (a) scope `metadataSearchState` to the
`NovelDetail` route — clear it in a `DisposableEffect`/`onDispose` keyed on
`novelId` when that route leaves composition, same pattern the reader
already uses for its own chapter-scoped state — or (b) keep it global but
have the dialog check `state.novel.id` against the currently-active
`novelDetail/{novelId}` route before rendering, and silently drop/requeue a
result for a novel no longer in view instead of applying it blind.

**Severity:** Medium — silent misattribution of fetched metadata onto the
wrong novel, no crash or data loss, but no indication to the user that it
happened.

---

## 5. Leaving the Reader races the outer Scaffold's inset switch, leaving a gap above the next screen's top bar — **open, newly found**

**Where:** `ReaderScreen.kt`, `applyWindowLayout` (304–317) and the
`DisposableEffect(fullscreenEnabled, isLandscape)` whose `onDispose` resets
window state (611–665, specifically 659–664); `MainActivity.kt`, the
`LaunchedEffect(currentRoute)` that re-applies edge-to-edge for non-reader
routes (979–983) and `Scaffold`'s `contentWindowInsets` switch (1007–1010).

**This matches what was reported** ("the top bar lowers down and there's a
vacant space between the status bar and the top bar... happens coming back
from the chapter reader... also happens in reader settings"). It is **not**
gated on the reader's own full-screen setting, which is consistent with what
was described (it happens whether or not full-screen was on inside the
reader) — the mechanism below is unconditional.

**The mechanism (I — consistent with source, not yet measured on-device):**

Two separate effects, in two separate composables, both race to decide the
window's edge-to-edge state the instant a reader route is left:

1. **Reader's own `onDispose` (ReaderScreen.kt:659–664)** unconditionally
   calls `applyWindowLayout(window, edgeToEdge = false, drawIntoCutout = false)`
   — i.e. `WindowCompat.setDecorFitsSystemWindows(window, true)`. This hands
   inset management back to the system: the system itself now resizes the
   content view below the status bar, and the raw status-bar inset Compose
   would read goes to zero (already consumed).
2. **MainActivity's `LaunchedEffect(currentRoute)` (MainActivity.kt:979–983)**
   re-applies `edgeToEdge = true, drawIntoCutout = true` the moment
   `currentRoute` is no longer `reader/*` — correct for every other screen,
   since `Scaffold`'s `contentWindowInsets` for those routes is
   `ScaffoldDefaults.contentWindowInsets` (`WindowInsets.safeDrawing`), which
   expects an edge-to-edge window to actually have a real status-bar inset to
   pad by (MainActivity.kt:1007–1010).

These are a `DisposableEffect`'s `onDispose` (runs synchronously as part of
applying composition changes, in the composable that's leaving) and a
`LaunchedEffect` (launches a coroutine *after* the recomposition that changed
`currentRoute`) — there's no ordering guarantee between "the window's actual
`decorFitsSystemWindows` flag" and "what `Scaffold` already believes
`contentWindowInsets` should be for the new route," because `Scaffold`'s
conditional on `currentRoute` updates synchronously with composition, while
the window flag updates asynchronously via effect 2's coroutine. For one or
more frames: `Scaffold` has already switched to `contentWindowInsets =
safeDrawing` (padding content down by the status-bar height a second time),
while the window is still non-edge-to-edge from effect 1 (the system has
*already* pushed the whole content view down by the status-bar height once).
Both paddings land in the same frame → the reported gap between the real
status bar and the screen's own `TopAppBar`, which (per the audit below)
zeroes its own `windowInsets` and relies entirely on this outer `Scaffold`
padding being correct.

**Every non-reader screen shares the exposure.** Audited every `TopAppBar(`
call site in `ui/`: all 15 non-reader screens zero their own insets the same
way (`windowInsets = WindowInsets(0, 0, 0, 0)`) and rely solely on the outer
`Scaffold`'s `contentWindowInsets` — `AuthorPageScreen`, `ChapterEditorScreen`,
`FictionBrowseScreen`, `HomeScreen`, `LegalDocumentScreen`,
`LibrarySettingsScreen`, `NoticeBoardScreen`, `NovelDetailScreen`,
`ReaderSettingsScreen`, `SettingsScreen`, `SplashSettingsScreen`,
`ThemeSettingsScreen`, `TtsSettingsScreen`, `TtsVoicePickerScreen`,
`WebViewScreen`. (`ReaderScreen` itself is the one exception by design — see
its own comment at line 936.) So this isn't specific to the "fiction" page
and Reader Settings — those are just the two places it happened to be
reproduced; architecturally it's exposed on **every** screen reachable
directly from a Reader exit, which in practice is most of them (Reader's
`onBack`/`onBackToFiction`/Previous/Next can each land on a different route).
Home not showing it is plausible rather than contradictory — the bottom-bar
tab switch (`popUpTo("home"){saveState=true}` + `restoreState=true`) takes a
different recomposition path back to Home than a direct `popBackStack()`
onto `NovelDetail`/`FictionBrowse`/`ReaderSettings` does, which could close
or widen the race window differently — but it hasn't been separately
confirmed clean, only not yet seen.

**Fix direction:** stop having two independent places decide the window's
edge-to-edge state for the same transition. The cleanest fix is to delete
Reader's own `onDispose` window-state reset entirely and let
`MainActivity`'s `LaunchedEffect(currentRoute)` be the single owner of
"what should the window's edge-to-edge/cutout mode be for whatever route is
now current" — it already has the correct unconditional branch for every
non-reader route; Reader's `onDispose` fighting it for one frame is the bug,
not a needed safeguard (Reader's own `DisposableEffect` already reapplies the
correct reader-specific state every time it's live, keyed on
`fullscreenEnabled, isLandscape`, so there's no case where leaving Reader
needs its *own* reset instead of just deferring to the route-level effect
that's about to run anyway). If `onDispose` must stay (e.g. to guarantee
system bars are shown again immediately, before `MainActivity`'s effect gets
a chance to run), it should stop touching `decorFitsSystemWindows` at all —
only `controller?.show(WindowInsetsCompat.Type.systemBars())` — and leave the
edge-to-edge/cutout flags to `MainActivity` exclusively.

**Needs confirmation:** `adb shell dumpsys gfxinfo`-style frame capture or a
screen recording isn't necessary to *see* this one (it's a static one-frame
layout glitch, not jank), but confirming the fix means checking there's no
longer a visible flash on Reader → FictionBrowse, Reader → ReaderSettings,
and explicitly re-testing Reader → Home to settle whether it's actually
clean or just not yet reproduced.

**Severity:** Medium — cosmetic (a one-frame layout gap), but reproducible
on what the audit shows is most of the app's non-reader surface, and
directly reported by the owner as present with or without the reader's own
full-screen setting.

---

## 6. Bottom-tab roots inconsistently show a back arrow — correcting the report's attribution

**Where:** `ui/SettingsScreen.kt` (191–198), `ui/FictionBrowseScreen.kt`
(119–122), `ui/NoticeBoardScreen.kt` (32), `ui/HomeScreen.kt` (no
`navigationIcon` anywhere), `MainActivity.kt` `composable("settings")`
(1711–1713) and the `"fictionBrowse?..."` composable (1163–1165),
`navigation/AppBottomBar.kt` (`AppDestination`, `isBottomBarRoute`).

The report flagged Notice Board specifically as having an odd software back
button in the top-left corner. **Read directly (V): `NoticeBoardScreen`'s
`TopAppBar` call (line 32) passes no `navigationIcon` at all** — Material3's
`TopAppBar` never renders a navigation-icon slot unless one is supplied, so
there is no way for this screen's own code to be producing a back arrow.
`HomeScreen` is the same — no `navigationIcon`.

**What's actually inconsistent:** `SettingsScreen` and `FictionBrowseScreen`
— the other two of the four bottom-tab roots (`AppDestination.SETTINGS`,
`AppDestination.BROWSE` in `AppBottomBar.kt`) — **do** each wire a back arrow
to `navController.popBackStack()` (`MainActivity.kt:1163` and `:1711`). Per
`isBottomBarRoute`'s own doc comment, all four of `Home`/`Browse`/`Notice
Board`/`Settings` are meant to be reached as tab switches via the bottom bar
(`popUpTo("home"){saveState=true}`, not a push-and-pop back-stack
relationship) — the same reasoning that correctly gives Home and Notice
Board no back arrow applies equally to Settings and Browse, which still
carry one left over from before the bottom-bar rework merged them into tabs.

So: if a software back arrow was seen top-left, it's near-certain it was on
the **Settings** or **Browse** tab, not Notice Board — worth confirming
which tab was actually open when it was seen, since the fix differs by
screen (remove the leftover `navigationIcon`/`onBack` wiring from
`SettingsScreen`/`FictionBrowseScreen` and their `MainActivity` call sites,
to match what `HomeScreen`/`NoticeBoardScreen` already do correctly) from
what it would be if Notice Board really does show one on-device (which
would mean something outside this file's own composable — not found in
source).

**Severity:** Low — not broken (popping back from a tab root still lands
somewhere sane), but inconsistent with the tab-bar paradigm the other two
roots already follow, and a stray back arrow on a destination that's
supposed to be switched to, not navigated into, is exactly the kind of
"seems odd" the report called out.

---

## 7. Landscape cutout: screens other than Reader reportedly draw real widgets into the cutout area, not just colour-matched background — **unconfirmed (U), needs device repro**

**Where:** `MainActivity.kt`, the "landscape-tablet.md's cutout fix" comment
and `LaunchedEffect(currentRoute)` (964–983, 989–1006); candidates for where
content could be escaping the padded area: `ui/FictionBrowseScreen.kt`'s two
`ModalBottomSheet`s (189, 229) and `navigation/AppBottomBar.kt`'s
`NavigationBar` (no explicit `windowInsets` override at all).

**What the design comment says is supposed to happen (V):** for every
non-reader route, the window draws edge-to-edge under the display cutout
(`drawIntoCutout = true`) specifically so `Scaffold`'s `containerColor`
colour-matches the cutout strip instead of the platform letterboxing it
solid black — the comment at MainActivity.kt:1003–1006 is explicit that this
is a *background-colour* fix: "Scaffold's default `contentWindowInsets`
still keeps content itself clear of the cutout/status bar," i.e. nothing is
meant to actually render inside that strip, only the colour is meant to
reach into it.

**What was reported:** on every screen except Reader, something is now
rendering real widgets into that space in landscape, not just background
colour — the "dreaded black-bar syndrome" fix apparently overshot into
content actually occupying the area it was only meant to paint.

**Why this can't be fully pinned down from source alone:** the content
inside the `Surface`/`NavHost` subtree is padded correctly — it sits behind
`innerPadding`, which is derived from `Scaffold`'s `contentWindowInsets`
(`safeDrawing`, which includes the cutout) for every non-reader route, so an
ordinary screen's own layout shouldn't be able to reach into the cutout. The
two most plausible places content *can* bypass that padding are both
genuinely present in this codebase and worth checking first on-device:

- **`ModalBottomSheet`** (`FictionBrowseScreen.kt:189, 229`) renders in its
  own window layer, not as a child positioned inside the `Surface`'s padded
  bounds — a full-width bottom sheet opened in landscape is a concrete
  candidate for extending into a side cutout unless Material3's own
  `ModalBottomSheet` handles the display-cutout inset itself (this app
  doesn't pass it anything that would).
- **`AppBottomBar`'s `NavigationBar`** (no `windowInsets` override found —
  V) takes whatever Material3's `NavigationBarDefaults.windowInsets`
  resolves to by default; if that default doesn't include the side cutout
  inset in landscape the way it includes the bottom nav-bar inset, a
  full-width bottom bar would sit under the cutout rather than clear of it.

Pinning which of these (or something else entirely) it actually is needs
either a library-version check against the exact Material3 release this
project pins (not fetchable in the environment this was investigated in —
only PyPI/npm/crates/GitHub registries were reachable, not Google's Maven)
or, more directly, an on-device repro: rotate to landscape on each
non-reader screen and check specifically whether (a) the bottom sheet, (b)
the bottom nav bar, or (c) something in the screen's own content is the one
actually sitting under the cutout, versus just colour-matched behind it.

**Severity:** Unranked until reproduced — could be purely cosmetic
(colour-matched is fine either way) or could mean real tap targets are
rendering under a notch/camera-hole where they're visually or physically
unreachable, which would be a functional regression, not just a visual one.

---

## Summary

| # | Bug | Status |
|---|---|---|
| 1 | WebView never destroyed leaving About Me | **Fixed** — confirmed in source, no action |
| 2 | SAF folder grant never released on re-pick | Open — confirmed, fix direction given |
| 3 | Partial-scan reconcile cascade-deletes reading history | Open — confirmed, highest severity, needs owner input before fixing |
| 4 | "Fetch info" result can land on the wrong novel | Open — confirmed, fix direction given |
| 5 | Reader-exit inset race leaves a gap above the next top bar | Open — newly found, root cause identified, exposure audited across all 15 non-reader screens |
| 6 | Inconsistent back arrow on bottom-tab roots | Open — misattributed to Notice Board in the report; actually Settings + Browse |
| 7 | Widgets reportedly drawn into the landscape cutout | Unconfirmed — two concrete suspects named, needs on-device repro to pin down |
