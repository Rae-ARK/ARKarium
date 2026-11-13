# Notice Board: a persistent new-chapters log (and, later, a home-screen widget)

> **Kind:** proposal · **Status:** index page and Rae's Journal built; Pigeon Rally is still a stub (no data layer) · **Verified:** 2026-11-13 @ 66846bc
> **Origin:** owner request - a dedicated screen listing which fictions have
> received new chapters, explicitly scoped in two phases: the in-app screen
> first, a launcher home-screen widget built on top of it once the screen
> itself is working.

## Index and pages (stubs built)

The Notice Board tab (`AppDestination.NOTICE_BOARD`, `ui/NoticeBoardScreen.kt`) is an
**index**: a skeuomorphic cork board with one pinned paper note per section; tapping a
note opens that section's own page. Drawn entirely with Compose shapes (no bitmaps), with
fixed "physical" colors - only the cork's darkness follows the theme.

| Note | Route | Screen | Status |
|---|---|---|---|
| **Pigeon Rally** | `noticeBoard/pigeonRally` | `PigeonRallyScreen.kt` | stub - the new-chapters log below, styled as scrolls delivered by pigeon (`ScrollPaper` is the shared look the real rows reuse) |
| **Rae's Journal** | `noticeBoard/journal` | `RaesJournalScreen.kt` | built - the author's writing journal, read from the RSS feed `https://rae-ark.horizonarkstudio.workers.dev/feed.xml` (see below) |

Both sub-routes are drill-down pages, not tabs: the bottom bar hides on them and Back
returns to the board. Pigeon Rally has nothing behind it yet - no `chapter_update_events`
table or DAO.

### Rae's Journal - built

`data/JournalFeed.kt` (parser, `JournalFeedClient`, `JournalRepository`),
`viewmodel/JournalViewModel.kt`, `ui/RaesJournalScreen.kt`.

- **Fetch:** a plain `HttpURLConnection` GET on `Dispatchers.IO` (15 s timeouts, response
  capped at 1 MiB), same convention as `SyncClient` - no new dependency.
- **Parse:** RSS 2.0 only, via the platform DOM parser (no new dependency), DOCTYPEs
  refused where the parser supports it. Entries stay in the feed's own order; duplicate
  guids collapse to one.
- **Offline copy:** the last response that *parsed* is saved to the app's cache dir
  (`journal-feed.xml`). If a later fetch fails or returns something unparseable, the page
  shows that copy under a banner saying when it was saved; with no copy it shows the
  error and a "Try again" button. Nothing is stored in Room, so there is no schema bump.
- **Refresh:** opening the page re-fetches unless a fresh copy is under ten minutes old;
  the app-bar button always re-fetches.
- **Screen:** one card per entry - title, date (or "Undated"), fiction tags, and the text
  collapsed to five lines until tapped. No link-out: entries have no URL of their own.
- **Second origin:** the feed lives on a different host from the fiction relay, so the app
  now talks to two. That is the one place `architecture.md`'s single-relay stance has an
  exception; the relay's own sync invariants (S1-S10) don't cover it.

### Rae's Journal feed - shape, as of 2026-11-13

Noted from the live feed:

- RSS 2.0, about ten items, newest first.
- Every item's `<link>` is the same journal page, so an entry's identity is its `<guid>`
  (`isPermaLink="false"`), and there is no per-entry URL to open.
- `<pubDate>` is optional - some entries only carry a month or season in their guid - so a
  missing date renders as undated, and ordering trusts the feed's own order.
- `<description>` is plain text; `<category>` holds the fiction tags as one string
  (`SBM`, `SBM · EPMC`).
- A different host from the fiction relay (`RELAY_HOST`), so this adds a second origin the
  app talks to - worth deciding how that fits `architecture.md`'s single-relay stance
  before building the fetch.

## What's built today (for the new-chapters log)

"New chapter available" is entirely **transient**. `NewChapterCheckWorker`
(background, periodic via WorkManager) and `SyncViewModel.checkForUpdates`
(foreground, the manual "Check for updates" action) both detect new chapters
the same way - sync a novel, and if `SyncManager.sync`'s outcome says
`changed`, rescan and diff the chapter id set before/after (chapter ids are
deterministic, see `NewChapterCheckWorker.checkOneNovel`'s doc comment, so an
existing chapter never shows up as "new" just because a rescan touched its
row again). The background path's diff feeds straight into
`NewChapterNotifier.notify` - one Android system notification, per novel,
right then. The foreground path's `outcome.changed` only ever drives
in-dialog progress text (`SyncCheckState.InProgress`/`Done`) - it doesn't
even post a notification, let alone log anything.

Neither path writes the event anywhere. Dismiss or miss that one system
notification (as any transient notification can be, swiped away, buried
under others, phone was off) and the fact that novel X got a new chapter
is gone - nothing in the app itself says "here's what you've missed." The
closest existing thing, `LibraryViewModel.recentlyRead`/"Continue Reading"
on Home, tracks what *you've read*, not what *arrived*; a novel with a new
chapter you haven't opened yet doesn't move in that list at all.

## What's proposed

A new screen, **Notice Board**: a persistent, reverse-chronological log of
"this fiction got new chapters" events - what most other apps would call a
notifications/activity page, scoped specifically to new-chapter arrivals
(not a general activity feed; sync errors, settings changes, etc. stay out
of it).

### Data model

A new Room entity, `ChapterUpdateEventEntity` (`data/Entities.kt`, alongside
`ChapterReadEventEntity` - same "one row per event, not a running counter"
shape that one already established):

```kotlin
@Entity(tableName = "chapter_update_events")
data class ChapterUpdateEventEntity(
    @PrimaryKey val id: String,       // UUID, same pattern as other event rows
    val novelId: String,
    val newChapterCount: Int,         // from the same diff NewChapterNotifier already gets
    val latestChapterTitle: String?,  // ditto
    val detectedAt: Long,
    val seen: Boolean = false         // unread/read state for the badge (below)
)
```

One row per *sync that found new chapters*, not one row per chapter - a
sync that pulls in 3 chapters at once is one Notice Board entry ("3 new
chapters"), matching what the existing notification text already says, not
three separate rows. `NewChapterCheckWorker.checkOneNovel` inserts a row
right alongside its existing `NewChapterNotifier.notify` call (same
`newChapterCount`/`latestChapterTitle` it already computed - no new query),
and `SyncViewModel.checkForUpdates`'s `outcome.changed` branch gets the
same insert added, which closes the gap noted above where the manual path
currently logs nothing at all.

### The screen

- **Entry point:** Notice Board is already a bottom-bar tab, and Pigeon Rally is one of
  its pinned notes. The glanceable cue that something arrived is an unread-count badge
  (`chapter_update_events` where `seen = false`), the same job a badge-on-icon does
  anywhere else - on the tab, on the note, or as a bell on `HomeScreen`'s top bar (not
  decided).
- **List:** reverse-chronological, one row per event - novel cover
  (`ReaderCoverThumbnail`, already built and reused elsewhere), novel title,
  "3 new chapters" / the latest chapter's title, relative time
  (`detectedAt`). Tapping a row marks it `seen` and navigates to the novel
  (`novelDetail/{id}`) - straight to the reader was considered, but landing
  on the novel page first matches what tapping a novel anywhere else in the
  app already does, and a reader who wants to jump straight into the new
  chapter still has "Continue Reading"/the chapter list one tap further in.
- **Read state:** opening Notice Board itself doesn't mark everything seen
  (that would make the badge useless the moment you glance at the list
  without reading anything) - only actually tapping a row does. A "Mark all
  read" action is the cheap escape hatch for clearing a backlog without
  opening each one.
- **Empty state:** "No updates yet" - same tone/treatment as any other
  empty list in the app (Home's own empty library state is the closest
  existing example to match).

### Explicitly out of scope for this phase

- Not a general notification center - sync errors, "Notify me" toggle
  changes, etc. are not Notice Board entries.
- Not a replacement for the existing system notification - that stays for
  the "phone's locked, tell me right now" case; Notice Board is the
  "what did I miss" case. The two are complementary (same event, two
  surfaces), not either/or.
- No per-novel mute/filter inside Notice Board itself for this phase -
  that's what the existing per-novel "Notify me" toggle on `NovelDetailScreen`
  already controls upstream (an event is only ever logged for a novel that
  toggle is on for, same `notifyEnabledSynced()` query `NewChapterCheckWorker`
  already gates on).

## Phase 2 (later, once the screen above is built and stable): a home-screen widget

The owner's own framing: once Notice Board exists as a screen, turn it into
something a reader can "hang in their launcher home screen" - an Android App
Widget surfacing which fictions currently have unseen new chapters, glanceable
without opening the app at all. Sketched at a high level only; this is **not**
designed in detail here, deliberately, since the screen above is the
prerequisite and doesn't exist yet:

- Built on the same `chapter_update_events` table the screen reads - the
  widget is a second renderer of the same unseen-events query, not a
  separate detection path.
- Classic `AppWidgetProvider`/`RemoteViews` (not Glance) is the likely fit
  given `minSdk 24` and that nothing else in the app currently pulls in
  Jetpack Compose-for-widgets - but this should be re-weighed once the
  screen's own data layer is settled, not decided now.
- Update trigger: piggyback on `NewChapterCheckWorker`'s existing periodic
  run (call `AppWidgetManager.updateAppWidget` right after it writes new
  `chapter_update_events` rows) rather than giving the widget its own
  separate polling schedule.
- Tapping a widget row/the widget itself deep-links into Notice Board (or
  straight to that novel) via the same route the screen's own row-tap uses.

## How it'd fit the existing code

- `data/Entities.kt` / `data/AppDatabase.kt` / `data/Dao.kt`: new entity
  above, a DAO (`insert`, `unseenCount(): Flow<Int>` for the badge,
  `recent(limit): Flow<List<ChapterUpdateEventEntity>>` for the list,
  `markSeen(id)`, `markAllSeen()`), a schema bump (see `app/schemas/` -
  every prior entity addition in this project has needed one).
- `data/NewChapterCheckWorker.kt`: one insert added next to the existing
  `NewChapterNotifier.notify` call in `checkOneNovel`.
- `viewmodel/SyncViewModel.kt`: one insert added in `checkForUpdates`'s
  `outcome.changed` branch - closes the "manual check logs nothing" gap
  noted above.
- New `viewmodel/NoticeBoardViewModel.kt` (or a few `@Composable`
  `collectAsState` reads straight off the DAO's `Flow`s, if the screen turns
  out simple enough not to need its own ViewModel - matches
  `docs/foundational/design-notes.md`'s own reasoning for why some
  screens get a ViewModel and some don't) backing a new `ui/NoticeBoardScreen.kt`
  and a `notice_board` route in `MainActivity`'s `NavHost`.
- `ui/HomeScreen.kt`: bell `IconButton` + badge, next to the existing two.
