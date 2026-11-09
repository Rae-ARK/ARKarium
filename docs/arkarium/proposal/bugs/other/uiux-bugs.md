# UI/UX bugs (consolidated)

> **Kind:** proposal · **Status:** open — all 8 fixed in source; most still await an on-device pass (bug 1 needs none — see its own entry) · **Verified:** 2026-11-09 @ (this patch)
> **Origin:** owner reports (two WhatsApp sessions, 2026-11-09) + a line-by-line re-check of every file each report names, against the commit above — not a transcription of the reports as given. Where a report's claim no longer matches source (fixed since reported, or a different file than named), that's called out explicitly rather than filed as-is. #8 is a follow-on finding from a direct request to re-check Reader's own top-bar handling in portrait, not a separate owner report.
>
> This status line previously read "closed" while several entries below still called for an on-device pass and both `README.md` index tables already said "open" — fixed here to match this file's own entries and its listing, per the lifecycle rule against leaving a doc that contradicts its own truth (`../../README.md`).

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

## 2. Persisted SAF folder permission is never released — **fixed**

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

**Status:** Fixed — `MainActivity.kt`'s `pickFolder` now reads the previous
`prefsManager.libraryUri` before `setLibraryUri` overwrites it and releases
it (if non-null and different from the new pick) via
`releasePersistableUriPermission`, guarded against a `SecurityException` if
the grant was already gone.

---

## 3. Stale-novel reconciliation cascade-deletes reading history on a partial scan — **fixed**

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

**Status:** Fixed — went with the "require missing across two consecutive
scans" direction rather than the MISSING_LOCALLY-equivalent one, since the
latter would need a new non-destructive UI resolution flow for purely-local
novels that doesn't exist today (unlike synced novels, which already have
`SyncResolutionDialog`'s "Remove from library"). `LibraryViewModel` now
tracks a `missingLocalOnce` set of local novel ids missing from the most
recently completed scan; a local novel is only cascade-deleted once it's
in that set *and* still missing on the next scan. Anything rediscovered
clears its flag. Kept in-memory (not persisted) deliberately: a process
restart losing this set only costs one extra "benefit of the doubt" scan,
never makes deletion happen sooner or on less evidence than before.

---

## 4. "Fetch info" results can apply to a novel that's no longer on screen — **fixed**

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

**Status:** Fixed — went with fix direction (b). `MainActivity.kt` now
derives `currentNovelDetailId` from the active back-stack entry whenever the
current route is `novelDetail/{novelId}`, and the `Results`/`Error` dialog
branches only render (and `Results`' candidate selection only applies) when
`state.novel.id == currentNovelDetailId`; otherwise the state is silently
reset to `Idle` instead of popping up over whatever's now on screen.
`Loading` is unaffected (no apply action to misattribute, so it's always
safe to show).

---

## 5. Leaving the Reader races the outer Scaffold's inset switch, leaving a gap above the next screen's top bar — **fixed**

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

**Status:** Fixed — took the "`onDispose` must stay, but stop touching
`decorFitsSystemWindows`" option. `ReaderScreen.kt`'s `onDispose` no longer
calls `applyWindowLayout(window, edgeToEdge = false, drawIntoCutout = false)`;
it only calls `controller?.show(WindowInsetsCompat.Type.systemBars())` to
guarantee the system bars reappear immediately. `MainActivity`'s
`LaunchedEffect(currentRoute)` is now the single owner of the window's
edge-to-edge/cutout state for every route, removing the race.

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
screen from what it would be if Notice Board really does show one on-device
(which would mean something outside this file's own composable — not found
in source).

**Confirmed: Home needs no back button, and already has none.** `HomeScreen`
is a bottom-tab root reached purely by tapping its tab, never pushed onto
the back stack the way a drill-down screen is — there is nothing to "go
back" to from a tab in that sense, the bottom bar already is the
navigation. `HomeScreen.kt` has no `navigationIcon` anywhere, which is the
correct baseline, not an oversight. `NoticeBoardScreen` already matches that
baseline too (confirmed above). Settings and Browse are the two that don't
match it yet.

**Fix:** removed the leftover `navigationIcon { IconButton(onClick = onBack) {...} }`
block from `SettingsScreen.kt` and `FictionBrowseScreen.kt`, dropped the
now-unused `onBack` parameter from both composables, and dropped the
matching `onBack = { navController.popBackStack() }` wiring from their two
`MainActivity.kt` call sites — bringing both in line with what
`HomeScreen`/`NoticeBoardScreen` already did correctly. Also removed, per
owner request, `HomeScreen`'s own redundant top-bar Settings icon
(`onSettingsClick`) and its `MainActivity.kt` wiring — the bottom bar is now
the single way to reach every one of the four tab roots, Settings included,
matching the same "one way to switch tabs" paradigm this bug is about.

**Status:** Fixed.

**Severity:** Low — not broken (popping back from a tab root still lands
somewhere sane), but inconsistent with the tab-bar paradigm the other two
roots already follow correctly, and a stray back arrow on a destination
that's supposed to be switched to, not navigated into, is exactly the kind
of "seems odd" the report called out.

---

## 7. Landscape cutout: screens other than Reader draw real widgets into the cutout area, not just colour-matched background — **fixed**

**Where:** `MainActivity.kt`, the "landscape-tablet.md's cutout fix" comment
and `LaunchedEffect(currentRoute)` (964–983, 989–1006); the one place this
is done correctly, `ui/ReaderScreen.kt`'s root `Column` (925–932); the two
places it's missing, `ui/FictionBrowseScreen.kt`'s `ModalBottomSheet`s (189,
229) and `navigation/AppBottomBar.kt`'s `NavigationBar`.

**What the design comment says is supposed to happen (V):** for every
non-reader route, the window draws edge-to-edge under the display cutout
(`drawIntoCutout = true`) specifically so `Scaffold`'s `containerColor`
colour-matches the cutout strip instead of the platform letterboxing it
solid black — MainActivity.kt:1003–1006 is explicit that this is a
*background-colour* fix only: "Scaffold's default `contentWindowInsets`
still keeps content itself clear of the cutout/status bar." Nothing is
meant to render inside that strip — only the colour is meant to reach into
it.

**You asked me to check how Reader handles this, since Reader is the one
screen that's fine in landscape — and that comparison is what pins the
cause.** Reader's root `Column` (925–932) does this:

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .background(backgroundColor)
        .windowInsetsPadding(WindowInsets.displayCutout)
) {
```

`windowInsetsPadding(WindowInsets.displayCutout)` pads *every child of that
Column* — top bar when shown, body text, the footer bar — away from the
cutout on whatever side it's actually on (a side edge in landscape, the top
in portrait). Reader doesn't lean on the outer `Scaffold` for this at all;
it manages the cutout inset itself, directly on its own content, which is
exactly why it's the one screen that's fine in landscape regardless of what
the outer `Scaffold`/`MainActivity` machinery is doing.

**Confirmed (V): this is the only call to `windowInsetsPadding(WindowInsets.displayCutout)`
in the entire app.** Nothing else in `ui/` uses it. Every non-reader screen
instead relies entirely on `MainActivity`'s outer `Scaffold.contentWindowInsets`
(`safeDrawing`, which includes the cutout) being consumed once via
`innerPadding` — and that's correct, but only for content that is actually
inside the `Surface` the `innerPadding` is applied to. Two things in this
codebase sit outside that padded subtree and have no `windowInsetsPadding`
of their own to compensate, which is why they're the two concrete suspects
for what's rendering under the notch:

- **`AppBottomBar`'s `NavigationBar`** (`navigation/AppBottomBar.kt`) is
  passed to `Scaffold`'s `bottomBar` slot (MainActivity.kt:1011), which lays
  it out as a full-width sibling of the padded content area, not a child of
  it — `innerPadding` never touches it. Nothing in `AppBottomBar.kt` applies
  `windowInsetsPadding(WindowInsets.displayCutout)` or passes a `windowInsets`
  override to `NavigationBar` that would. In landscape, with the cutout on a
  side edge, a full-width bottom bar with no horizontal cutout padding of
  its own is the leading candidate for tab icons/labels sitting under it.
- **`ModalBottomSheet`** (`FictionBrowseScreen.kt:189, 229`) renders in its
  own window layer, entirely outside the `Surface`'s padded bounds — same
  gap, same missing `windowInsetsPadding`, for whenever the sort/filter
  sheet is opened in landscape.

**Fix direction:** apply the same pattern Reader already proves works —
`windowInsetsPadding(WindowInsets.displayCutout)` (horizontal is enough here,
since these two are bottom-anchored, not full-screen) — to `AppBottomBar`'s
`NavigationBar` and to the content of both `ModalBottomSheet`s. This is not
a new mechanism to design; it's applying the one that already exists and
already works in this exact codebase to the two places that don't have it
yet.

**Still worth an on-device pass:** confirming only these two are responsible
(versus something else inside a specific screen's own content escaping its
padding some other way) and confirming the fix removes the notch overlap in
landscape on both the bottom bar and an opened sheet.

**Severity:** Medium-to-high depending on what's actually under the cutout —
if it's just the bottom bar's background/ripple extending under it,
cosmetic; if a `NavigationBarItem`'s tap target or a bottom-sheet control
ends up physically under the notch/camera housing, that's an unreachable
control, not just a visual regression.

**Status:** Fixed — applied `windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))`
(horizontal only, since both are bottom-anchored, not full-screen) to
`AppBottomBar`'s `NavigationBar` and to the content `Column` of both of
`FictionBrowseScreen`'s `ModalBottomSheet`s (sort and filter). Still worth
the on-device landscape pass the original note called for to confirm the
notch overlap is actually gone.

---

## 8. Reader's top bar pops in/out instantly in portrait while every other `showControls` widget on the same screen animates — **fixed**

**Where:** `ui/ReaderScreen.kt` — the top-bar call site (originally 972–1008,
now wrapped at 988–1030) versus the footer `Column`'s own
`.animateContentSize()` (1277–1287, see that Column's own comment) and the
bottom pill's `RippleReveal`/`AnimatedVisibility` swap (`WaterRipple.kt`,
`RippleReveal`, used at both `ReaderScreen.kt` bottom-pill call sites).

**You asked me to check how Reader draws its widgets, since the top bar was
reported as not handling portrait correctly and something in there was
causing jank — this is what source shows.** `showControls` gates three
separate widgets on this screen: the `TopAppBar`, the footer (progress
readout + Reader Preferences pill), and indirectly the bottom pill swap.
Two of the three were already animated — the footer explicitly wraps itself
in `.animateContentSize()` so its own height change doesn't "jump
instantly" (its own comment's words), and the pill swap goes through
`RippleReveal`, which is `AnimatedVisibility` under the hood. The top bar
was the one exception: a bare `if (showControls.value) { TopAppBar(...) }`
with nothing animating its appearance or removal from composition at all.

**Confirmed (V):** `ui/ReaderScreen.kt` had exactly one `if (showControls.value)`
block with no `AnimatedVisibility`/`animateContentSize` anywhere around it —
the `TopAppBar` call. Every other `showControls`-gated widget on the same
screen already used one of the two animated patterns above.

**The mechanism (I — consistent with source, not yet measured on-device):**
tapping the reading area to toggle chrome flips `showControls` once, but
that single state change drove two different motions in the same frame: the
footer smoothly animating its height via its own `animateContentSize`
spring, while the scrollable body's `weight(1f)` `Column` — sandwiched
between the top bar and the footer — had its *top* edge jump instantly the
moment the `TopAppBar` was added to or removed from composition, since
nothing animated that addition/removal. Portrait is where this is most
visible: the top bar sits flush under the status bar/cutout with nothing
above it to mask a one-frame snap, and it's the first thing a reader's eye
tracks when tapping to reveal or hide chrome. (Landscape has the same
missing animation, but the cutout there is a side strip the top bar barely
touches, so the jump is less perceptible — this is a portrait-dominant
symptom of an orientation-independent cause, not a portrait-only bug.)

**Fix direction:** apply the same pattern the footer already proves works
in this exact screen — wrap the top bar's conditional in a `Column` that
stays in composition with `.animateContentSize()` on it, rather than
reaching for a third, different animation API (`AnimatedVisibility` directly
on the `TopAppBar`) that wouldn't share the footer's animation spec and
could still visibly desync from it.

**Status:** Fixed — the `if (showControls.value) { TopAppBar(...) }` block
is now `Column(modifier = Modifier.fillMaxWidth().animateContentSize()) { if (showControls.value) { TopAppBar(...) } }`,
matching the footer's own technique and default animation spec so both
edges of the chrome move in step instead of one popping and the other
easing. No new imports needed — `animateContentSize` was already imported
for the footer.

**Still worth an on-device pass:** confirming the two edges now read as a
single synchronized motion rather than merely "both animated but still
slightly offset," and checking the same transition in landscape now that
it's animated there too.

**Severity:** Low-to-medium — cosmetic (a one-frame layout snap, not lost
functionality or an unreachable control), but on the screen that is the
app's primary activity, triggered by the single most common gesture in it
(tap to toggle chrome), and reproducible every time rather than on an edge
case.

---

## Summary

| # | Bug | Status |
|---|---|---|
| 1 | WebView never destroyed leaving About Me | **Fixed** — confirmed in source, no action |
| 2 | SAF folder grant never released on re-pick | **Fixed** — old grant released before the new one overwrites it |
| 3 | Partial-scan reconcile cascade-deletes reading history | **Fixed** — now requires two consecutive missed scans before cascade-deleting a local novel |
| 4 | "Fetch info" result can land on the wrong novel | **Fixed** — dialog now checks the result's novel against the active route, silently drops stale results |
| 5 | Reader-exit inset race leaves a gap above the next top bar | **Fixed** — Reader's `onDispose` no longer fights `MainActivity`'s route-level effect for window-state ownership |
| 6 | Inconsistent back arrow on bottom-tab roots | **Fixed** — removed from Settings + Browse; Home's redundant top-bar Settings shortcut also removed per owner request, bottom bar is now the only way between tabs |
| 7 | Widgets drawn into the landscape cutout | **Fixed** — `AppBottomBar` and `FictionBrowseScreen`'s `ModalBottomSheet`s now pad for `WindowInsets.displayCutout`, same pattern Reader already used |
| 8 | Reader's top bar doesn't animate with `showControls`, unlike the footer/pill | **Fixed** — top bar now wrapped in `.animateContentSize()`, matching the footer's own pattern |
