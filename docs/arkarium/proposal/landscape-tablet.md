# Landscape / tablet redesign

> **Kind:** feature · **Status:** cutout fix built (see commit `40b0bfa`); nav
> rail, Home two-column layout, Home settings tag shelves, fiction-page
> split, and the reader's landscape pane are all design only, nothing built
> · **Verified:** 2026-11-09 (code reading only; not built or run)
> **Origin:** owner's own framing, posed as a question ("does this seem like
> a good idea for tablet/landscape users?") before being approved. The
> recommendations below are what was given in answer to that question,
> written up here per the follow-up request to document it as a proposal
> rather than just leave it in chat.

A 6.67" phone rotated to landscape is roughly 410dp tall and 890dp wide. That
shape is wide but *height-compact* - Android's own adaptive-navigation
guidance treats a navigation rail as the better fit for it than a bottom
bar, since height is the scarce resource and a rail reclaims the strip a
bottom bar would otherwise eat. The same rotation also hands SAF-style
build-in display cutouts a landscape-only letterboxing default (see the
cutout section below) that most of this app never opted out of.

## What ARKarium does today

All read in `MainActivity.kt`, `navigation/AppBottomBar.kt`, `ui/HomeScreen.kt`,
`ui/SettingsScreen.kt`, `ui/NovelDetailScreen.kt` and `ui/ReaderScreen.kt` this
pass:

- **One fixed bottom bar** (`AppBottomBar`, four tabs: Home, Browse, Notice
  Board, Settings), shown via `Scaffold(bottomBar = ...)` for exactly those
  four routes (`isBottomBarRoute`). No orientation or width check anywhere in
  that path - it is the bottom bar in portrait and in landscape alike, phone
  or tablet.
- **Home's top bar** carries a Settings icon (`onSettingsClick`) and an "Add
  fiction" icon. Settings is reachable two ways at once - this icon and the
  bottom bar's own Settings tab - since the bottom-bar rework landed.
- **Home is a single `LazyColumn`**, same order and same single-column width
  at any screen size: search bar, reading-stats card, Featured, Continue
  Reading, Trending, New Releases. Nothing in `HomeScreen.kt` reads
  `LocalConfiguration` or branches on orientation.
- **The fiction page** (`NovelDetailScreen.kt`) is a single scrolling column:
  cover/title/synopsis/buttons, then the chapter list, top to bottom. Same
  story - no width/orientation branch.
- **The reader** is a single vertical `verticalScroll` column of paragraph
  `Text` composables at any width; the "Reader Preferences" pill supplies
  font/size/spacing but not a second column.
- **The display cutout** was, until `40b0bfa` on this pass, letterboxed
  solid black by the platform on every route except Reader. Reader's own
  `applyWindowLayout` (opts into edge-to-edge + `LAYOUT_IN_DISPLAY_
  CUTOUT_MODE_SHORT_EDGES`, tied to its full-screen setting) was the only
  place in the app that drew its own background under the cutout instead of
  taking the platform default - now reused app-wide for every non-reader
  route, independent of the rest of this proposal.
- **Tags/genres exist as data already**: `NovelEntity.genres`, a
  comma-separated string set per novel by the scanner/metadata fetch. Nothing
  reads it to build a shelf; Trending and New Releases in `HomeScreen.kt` are
  `novelsSnapshot.shuffled().take(n)` - not real signals, just a random
  sample of the local library repeated under two different headers.

## Nav rail, replacing the bottom bar in landscape

**Recommendation: adopt**, gated on orientation and width, not a blanket
replacement. Android's default behavior already keeps the bottom bar
whenever either dimension is "compact" (roughly <600dp); a 6.67" phone in
landscape is ~410dp tall, so the platform default would *not* give a rail
here on its own - this has to be an explicit override, not just upgrading a
library.

- A new `WindowInfo` read off `LocalConfiguration` (`screenWidthDp` /
  `screenHeightDp`), not a `material3-window-size-class` dependency - the
  project doesn't currently depend on that artifact, and the only thing
  needed here is "is this landscape and wide enough," not the library's
  fuller breakpoint taxonomy.
- `AppBottomBar`'s four `AppDestination` entries and `isBottomBarRoute` are
  reused as-is for a new `AppNavRail` (`NavigationRail` + `NavigationRailItem`,
  same `matchesRoute`/tab-switch semantics) - no duplicate enum, no second
  source of truth for which four routes get navigation chrome.
- **Side is a Home-settings toggle** (left/right), defaulting to left. Stored
  in `PreferencesManager` the same way every other UI preference already is
  (a new `stringPreferencesKey`, same shape as `theme`/`reader_reading_mode`).
  It only takes effect in landscape; portrait is unchanged either way.
- Wiring: `MainActivity`'s current `Scaffold(bottomBar = ...)` stays
  structurally as-is for portrait. In landscape it moves outside a wrapping
  `Row` instead - `AppNavRail` on the configured side, `Scaffold` (with
  `bottomBar = {}`) taking the rest via `Modifier.weight(1f)` - so Reader's
  existing `contentWindowInsets` special-case (zero insets for `reader/...`
  routes, see that code's own comment) is untouched; the `Row` sits outside
  Scaffold entirely and doesn't interact with it.
- The rail's own edge (whichever side faces the display cutout, if the rail
  is on that side) needs to pad itself by `WindowInsets.displayCutout` same
  as any other edge content, now that the cutout fix above makes that inset
  real instead of pre-letterboxed away.

## Settings icon: purge it from Home's top bar

**Recommendation: adopt**, but only once the rail/bar actually reaches
Settings - it already does today (bottom-bar tab), so this is safe to do
independently of the rail work: delete `onSettingsClick`'s `IconButton` in
`HomeScreen.kt`'s `TopAppBar.actions`, leave "Add fiction" in place. One line
removed, no ordering dependency on anything else in this doc.

## Home: two columns in landscape, plus a new Home Settings page

**Recommendation: adopt**, with one swap from how it was first framed.
Left = personal/returning-reader items, right = discovery:

- **Left column**: search bar, reading-stats card (streak + chapters this
  week), Continue Reading, Featured.
- **Right column**: Trending, New Releases, and whichever tag shelves the new
  Home Settings page has turned on.

(Continue Reading is the primary returning action, so it's listed ahead of
Featured on the left rather than after it, even though the original framing
put Featured before Continue Reading - a small reorder, not a scope change.)

Portrait keeps today's single-column order unchanged; only landscape-wide
splits into the two `LazyColumn`s above, each scrolling independently inside
a `Row`.

**New Home Settings page** (`settings/home`, reached from the Settings index,
not the top bar): scans the library's `NovelEntity.genres` for distinct
tags, normalizes case (`"LitRPG"`/`"litrpg"` count as one), shows each with
its novel count, and lets the owner toggle shelves on/off - same card-row
look `SettingsScreen.kt` already uses. Selected tags persist as a
`stringSetPreferencesKey`. A tag whose last novel is removed just stops
showing a shelf; the toggle itself isn't force-cleared, so it comes back the
moment a novel with that tag exists again.

**Trending and New Releases aren't real signals today** (see "What ARKarium
does today" above) - worth fixing in the same pass, since tag shelves sitting
next to two `shuffled()` calls would be an odd mix of real and fake data on
one screen:
- New Releases: sort by `metadataFetchedAt` / scan-added time, not a random
  sample.
- Trending: sort by recent `chapter_read_events` activity (the same table the
  streak/weekly-count card already reads), not a random sample.

## Fiction page: two panes in landscape

**Recommendation: adopt.** Left pane: cover, title, author, synopsis,
action buttons (the part that's read once and acted on). Right pane: the
chapter table of contents, its own independent scroll. Each pane scrolls on
its own `rememberScrollState()`/`LazyColumn` state rather than sharing one,
since the left pane's content (cover + synopsis + buttons) won't fit in
~410dp of height at any reasonable size and needs to scroll independently of
the chapter list.

## Reader: landscape, without touching the position logic

**Recommendation: adopt the pane version, not a literal two-column pager.**
This is the one place the original framing is pushed back on. A real
two-column *reader* - text flowing from the bottom of column one to the top
of column two, Kindle-style - needs horizontal paging. ARKarium's reader is
one vertical `verticalScroll` whose saved position (`ReaderPosition.
charOffset`, see `data/ReaderPosition.kt`) is an index into that single
scroll's paragraph layout. Paging would mean rebuilding the scroll container,
the restore effect, the TTS auto-scroll target, and `currentAnchor()`
together - exactly the surface area the two bug docs in `bugs/other/` were
just fixed on. Doing that now would risk reopening either of them for a
cosmetic landscape mode.

Instead: **left pane** - cover thumbnail, novel/arc title, chapter
prev/next, progress readout, read-aloud controls (the controls that are
currently floating chrome over the single column). **Right pane** - the
existing `verticalScroll` column, line length capped to roughly 65-75
characters so long lines don't hurt readability at tablet widths, otherwise
untouched: same `paragraphOffsets`, same `currentAnchor()`, same restore
effect, same TTS auto-scroll. A true paginated two-page spread stays a later,
separate, opt-in mode rather than folding it into this pass.

## Suggested build order

Each step is independently testable on a device before the next starts:

1. `WindowInfo` helper + the Home-settings-icon purge + (done, `40b0bfa`) the
   cutout fix across every route.
2. `AppNavRail` + the left/right setting, replacing the bottom bar in
   landscape only.
3. Home's two-column layout + the new `settings/home` page + real
   Trending/New-Releases sorts.
4. The fiction page's two-pane split.
5. The reader's landscape left-pane/right-pane split, last and on its own -
   deliberately after everything else, so any regression it introduces is
   easy to isolate from the rest of this pass.
