# App shortcuts: two more candidates

> **Kind:** proposal · **Status:** design only, nothing built · **Verified:** 2026-11-08 @ 8fa5c91
> **Origin:** follow-up to the "Continue Reading" static shortcut (built this
> pass - see `../foundational/architecture.md`'s Entry points section), asked
> to look at what else apps in this space typically offer. External claims
> below (what other apps actually ship) were **not** independently
> re-verified past what's cited - web search turned up surprisingly little
> hard evidence of specific competitor `shortcuts.xml` contents (closed-source
> apps don't publish one; the open-source Mihon-family apps and LNReader
> this project already tracks - see
> [`peer-app-survey.md`](../proposal/peer-app-survey.md) - didn't turn up a public
> shortcuts implementation either in the time spent). The two candidates
> below lean instead on Android's own canonical shortcut patterns and one
> concrete, unambiguous piece of reader demand - re-verify any competitor
> specifics before citing them further.

## What's built today

One static shortcut, "Continue Reading" (`res/xml/shortcuts.xml`): long-press
the launcher icon, jump straight into whichever novel `reading_progress` says
was read most recently, at its saved chapter. See `architecture.md`'s Entry
points section for the full mechanics.

## Candidate 1: pin a specific novel to the home screen

**What:** a "Pin to home screen" action (NovelDetailScreen's overflow menu is
the natural spot) that calls `ShortcutManagerCompat.requestPinShortcut` for
*that* novel specifically - the user gets an icon on their home screen for
one particular fiction, not the library as a whole.

**Why this one:** this is the one piece of grounding here that isn't
inference from general conventions - a reader on the Choice of Games forum
asked for exactly this (a shortcut that "opens a specific book directly" so
they can "skip opening the app and searching for the book inside it")
[forum.choiceofgames.com/t/android-app-feature-request-shortcuts-to-books](https://forum.choiceofgames.com/t/android-app-feature-request-shortcuts-to-books/102399).
It's also one of the three shortcut categories Android's own docs describe
as first-class, not a workaround -
[developer.android.com/.../managing-shortcuts](https://developer.android.com/develop/ui/views/launch/shortcuts/managing-shortcuts)
covers pinned shortcuts as pinned copies of a static or dynamic shortcut that
stay launchable even after the app stops publishing the original. For a
library of several ongoing fictions, "Continue Reading" alone only ever
surfaces the single most-recent one - a reader bouncing between two or three
active reads has no way to jump straight to the one that isn't most-recent
without going through Home first. A pinned per-novel shortcut is the direct
answer to that, and it's the kind of thing a reader picks for themselves
(pin the 2-3 they're actively juggling) rather than something the app has to
guess at.

**How it'd fit the existing code:** the plumbing is almost entirely already
built. The pin action's intent would carry the novel's id as an extra, the
same shape `EXTRA_CONTINUE_READING_SHORTCUT` already uses - but scoped
(`EXTRA_PIN_NOVEL_ID` or similar) rather than "most recent, whichever that
is". `MainActivity`'s handling would mirror `pendingContinueReadingShortcut`'s
`LaunchedEffect` almost line for line, except resolving *that* `novelId`'s
`ReadingProgressDao.forNovel` instead of going through `recentNovelIds(1)`
first - functionally the same per-novel resolution Home's own
`onContinueReading` row already does. Re-resolving the chapter at tap time
(rather than baking a fixed chapterId into the pinned intent once) matters
here specifically: a pinned shortcut can sit on the home screen for months,
and the whole point is that it keeps landing on wherever the user currently
is in that novel, not wherever they were the day they pinned it.

**Open questions:** `requestPinShortcut` on most launchers shows a system
confirmation dialog the app can't suppress or know the outcome of
synchronously - fine for a one-off user-initiated action, just worth noting
so nobody expects a silent "shortcut added" toast. Icon would need to be the
novel's cover (`NovelEntity.coverUri`), cropped/badged, not the static
bookmark glyph `ic_shortcut_continue_reading.xml` uses - covers are already
loaded via Coil elsewhere in the app, but `ShortcutManagerCompat` needs an
`IconCompat` built from a bitmap, not a Compose painter, so that's a real
(if small) new code path, not a reuse of existing image-loading calls as-is.

## Candidate 2: dynamic shortcuts for more than just the one most-recent novel

**What:** replace (or supplement) the single static "Continue Reading" entry
with a handful of dynamic shortcuts, one per recently-read novel, each
labeled with that novel's own title - "Continue *Summoned By Mistake*",
"Continue *The Wandering Inn*" - rather than one generic "Continue Reading"
that only ever means whichever novel was read last.

**Why this one:** this is Android's own textbook example for dynamic
shortcuts, not an inference from a specific competitor - the managing-shortcuts
guide above illustrates the whole dynamic-shortcuts feature with a chat app
that publishes its four most recent conversations as shortcuts, swapping the
oldest out as new ones arrive. "Most recently read novels" is the same
shape as "most recent conversations", and `reading_progress` already has the
exact query this needs: `ReadingProgressDao.recentNovelIds(limit)`, already
ordered by `updated_at DESC`, already parameterized (it's what the static
shortcut's `LaunchedEffect` calls with `limit = 1` today - this is the same
call with a bigger limit). A reader with two or three fictions in rotation
gets direct, titled jumps to each one instead of the static shortcut's
single "whichever one I touched last" slot, which stops being useful the
moment they read a second book before finishing the first.

**How it'd fit the existing code:** `ShortcutManagerCompat.setDynamicShortcuts`
(from `androidx.core:core-ktx`, already a dependency - no new library needed)
called with up to N `ShortcutInfoCompat` built from `recentNovelIds(N)` plus
each id's `NovelDao.findById` for its title. The natural refresh point is
wherever reading progress is actually written - `saveReadingProgress`
(mentioned in `architecture.md`'s Reading and local state section as the
sole writer backing the Home streak card) is already the single choke point
for "a chapter's progress just changed", so it's the one place this needs
wiring in, not scattered across every reader exit path. Each shortcut's
target intent is the same `reader/{novelId}/{chapterId}` route as the static
shortcut and Candidate 1 - all three (static, Candidate 1's pinned, and
this one) end up resolving through the same per-novel lookup, just reached
different ways.

**Open questions, the one worth flagging loudest:** Android enforces a cap
on the combined number of static + dynamic shortcuts an activity can publish
(`ShortcutManager.getMaxShortcutCountPerActivity()`, launcher-dependent but
commonly small - 4 or 5). Static "Continue Reading" plus N dynamic
per-novel entries isn't additive headroom to spend freely; past 1-2 dynamic
entries this proposal should probably **replace** the static shortcut
outright (a dynamic shortcut can carry the exact same "most recent" slot
as its first entry) rather than run both. That's a design call for whoever
picks this up, not resolved here - this doc is scoping the two ideas, not
sequencing or sizing them.

## Not pursued here

A generic "Search" or "Add Fiction" shortcut (the pattern most mainstream
content apps reach for first, per general Android app-shortcut write-ups -
e.g. [dev.to/adammc331/creating-android-app-shortcuts](https://dev.to/adammc331/creating-android-app-shortcuts))
was considered and set aside: both already sit one tap away on Home's own
top bar (`onSearch`, `onAddFictionClick`), so a shortcut would save
approximately nothing a reader doesn't already have close at hand - unlike
Candidates 1 and 2, which each reach something that's otherwise two or more
taps (and, for a non-most-recent novel, a scroll through the library) away.
