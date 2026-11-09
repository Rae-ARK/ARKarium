# Multi-document reader mode

> **Kind:** implementation (ladder) · **Status:** rung 0 (this doc) done, rung 1 current · **Verified:** 2026-11-09 @ 0bfd6e5
> **Truth lives in:** `MainActivity.kt`, `ui/ReaderScreen.kt`, `ui/Tts.kt`, `ui/ReaderTtsLogic.kt`, `playback/TtsPlaybackService.kt`, `data/ReaderPosition.kt`, `data/PreferencesManager.kt`

Promoted from [`../proposal/reader-settings.md`](../proposal/reader-settings.md)'s
"Multi-document mode" entry (suggested-order item 6). That proposal was written
against the reader as it stood in early November; this pass re-read the actual
code it depends on (`reconcileTtsSession`, `ChapterTtsState`/`TtsPlaybackService`,
and the position-save path) and found three of its load-bearing claims no longer
hold. Rungs below are sequenced around fixing those first, since the feature as
proposed is built on top of them.

## Where the proposal and the code now disagree

The proposal's own plan reads: *"`TtsPlaybackService` keeps its single `listener`
exactly as it is now - that's already the 'lock'... nothing new to build there"*
and *"the owning window's session is stopped gracefully... its reading position
is written out immediately."* Neither is true today:

1. **The "lock" is destructive, not a lock.** `ChapterTtsState.attach()`
   (`ui/Tts.kt:168-170`) does `svc.listener = listener` unconditionally - no check
   for an existing listener. Two `ChapterTtsState` instances bound to the same
   `TtsPlaybackService` (which is what two reader back-stack entries would be,
   since `bindService` on the same `Intent` returns the one running instance)
   silently steal the listener slot from each other. `TtsPlaybackService.start()`
   (`playback/TtsPlaybackService.kt:631-666`) has no guard either - it sets
   `sessionKey` and flush-queues (`speakNextChunk(QUEUE_FLUSH)`, line 665)
   regardless of whatever session was previously active. So today, opening a
   second chapter and tapping play silently hijacks playback and cuts the first
   `ChapterTtsState` off from all future callbacks (`isSpeaking`/`isPaused`/chunk
   progress) with no error and no detach. This is the collision the confirm
   dialog is supposed to intercept - it isn't prevented, it's just unguarded.
2. **`stop()` doesn't write position.** `TtsPlaybackService.stop()`
   (`playback/TtsPlaybackService.kt:676-695`) only touches engine/queue/session
   state - `engine?.stop()`, clears `pendingChunks`/`pendingSpans`/`pendingIndex`,
   `sessionKey = null`, `releaseSessionResources()`, `stopForeground`/`stopSelf`.
   A grep of that file for `ReaderPosition`/`readingProgressDao` returns nothing;
   the service has no reference to either. Position-saving is entirely on the
   `ReaderScreen` side: it computes a scroll-derived `ReaderPosition` and hands it
   up through `onPositionChanged`/`onBack`/etc. to `MainActivity.saveReadingProgress`
   (`MainActivity.kt:394`), which only that specific composable instance can call,
   for its own chapter. `ChapterTtsState.stop()`/`TtsPlaybackService.stop()` have
   no path to any `ReaderPosition` at all, let alone the *other* window's. "Reuse
   the existing save path on graceful stop" is new plumbing, not a reuse.
3. **Position is char-offset, not scroll-fraction.** The proposal's "Position
   handling" section reasons from a scroll-fraction model. The actual resume
   anchor is `ReaderPosition.charOffset` (`data/ReaderPosition.kt:47`), written via
   `db.readingProgressDao().upsert(...)` with `position = position.charOffset.toFloat()`,
   `positionType = ReaderPosition.POSITION_TYPE` (`MainActivity.kt:394` on). This
   doesn't change the proposal's conclusion (write through on stop, don't hold it
   in memory) but changes what rung 3 below actually has to plumb.

There's a fourth problem the proposal doesn't mention at all:
**`ReadingProgressDao.upsert`/`forNovel` is one row per *novel*, not per chapter
and not per window** (`data/Dao.kt:239,242`). Two simultaneously-open chapters of
the *same* novel - not even a cross-novel case - already stomp each other's saved
position today, independent of anything TTS-related. Multi-document mode makes
this collision reachable in normal use for the first time; it isn't new, but it's
the thing that will actually bite first if rungs are built out of order.

## What's already fine, unmodified

- `reconcileTtsSession` (`ui/ReaderTtsLogic.kt:47-55`) is a pure function
  (`sessionActive`, `sessionKey`, `chapterId` in; an enum out), unit-tested in
  `ReaderTtsLogicTest.kt`. Its current behavior - "different `sessionKey` than
  mine → stop it as stale" - is exactly wrong for two legitimately-open windows
  (it would silently kill a sibling window's live playback the moment the second
  reader's `LaunchedEffect(chapter.id, tts.isAvailable)` runs), but the function
  itself doesn't need to change: rung 2 adds a new case the call site branches on
  *before* falling through to today's stale-session path, rather than touching
  this function's existing three branches or their tests.
- `showControls` (immersive chrome state, `ui/ReaderScreen.kt:556`) is
  `rememberSaveable` per composable, i.e. already per-back-stack-entry. Two reader
  entries won't interfere here.
- `libraryViewModel` (novels/chapters/arcs cache) is Activity-scoped and read-only
  from the reader's point of view - fine for two entries to share.
- The `reader_*` preference shape (`Key` / private `xxxOf(prefs)` mapper / public
  `Flow` val - e.g. `READER_FULLSCREEN_KEY`/`readerFullscreenOf`/`readerFullscreen`,
  `PreferencesManager.kt:109-113,335-336`) is a clean template for anything this
  ladder needs to persist.

One more shared-mutable-state spot worth flagging for whoever builds rung 4, not
blocking rungs 1-3: `keepScreenOn` is applied as a single Activity `window` flag
(`DisposableEffect(...)`, `ReaderScreen.kt:467-480`), not per-composable state. Two
reader instances would each toggle the *same* flag; whichever disposes last clears
it for both. Setting it is idempotent, so this doesn't break the single-reader
case, but it means "chapter A wants the screen kept on, chapter B doesn't" has no
correct answer yet with two windows open - OR-ing the two instances' wishes
(screen stays on if *either* wants it) is the obvious fix but isn't scoped here.

## Ladder

| Rung | State | What |
|---|---|---|
| 0 | done | This doc, replacing the proposal's "nothing new to build" assumption with the three corrections above. |
| 1 | current | **Make the listener collision loud instead of silent.** Give `TtsPlaybackService` a way to answer "is a session live, and if so whose (`sessionKey`) and what title (`currentChapterTitle`)" from outside the listener-push channel - it already has `isSpeaking`/`isPaused`/`sessionKey` as plain `private set` properties (`TtsPlaybackService.kt:196-234`), so this is exposing a read, not inventing state. In `ChapterTtsState.speak()` (`ui/Tts.kt:218-220`), before calling `service?.start(...)`, check whether the bound service already has a *different* non-null `sessionKey` mid-session (`isSpeaking || isPaused`); if so, don't start - surface that to the caller (a new return type or callback) instead of today's silent flush-and-steal. No UI yet; this rung only turns the hazard into something the next rung can hook a dialog onto, and is unit-testable as a pure decision given `(otherSessionKey, otherTitle, isSpeaking, isPaused, myChapterId)` inputs, same shape as `reconcileTtsSession`. |
| 2 | queued | **Confirm dialog in `ReaderScreen`.** When rung 1's check fires, show *"Stop reading [other chapter title] aloud and start this chapter instead?"* (Yes/No), matching the pattern description in the proposal. On Yes: call the *other* window's graceful stop (rung 3 makes this safe), then retry `speak()`. On No: leave the other session running, don't start this one. Needs `reconcileTtsSession`'s stale-session branch to not fire first for a legitimately-live sibling - add a case ahead of today's three (`sessionActive && sessionKey != chapterId && <session belongs to a still-open sibling>` → ask, don't stop) rather than changing the existing branches tested by `ReaderTtsLogicTest.kt`. |
| 3 | queued | **Write-through position on graceful stop.** `ChapterTtsState`/`TtsPlaybackService` have no access to a `ReaderPosition` today (see correction 2 above) - this rung plumbs it: either the stopped window's `ReaderScreen` instance saves its own current position *before* yielding the listener (simplest - it already can call the same `saveReadingProgress`-reachable path it uses on normal exit, no new code in the service layer), or `ChapterTtsState.stop()` grows an optional position-flush callback. Prefer the former unless rung 2's dialog needs the stop to happen from code that doesn't have a live `ReaderScreen` composition to call back into. |
| 4 | queued | **Fix the one-row-per-novel save collision** (correction 4) for two open chapters of the same novel - keyed by `(novelId, chapterId)` instead of `(novelId)` in `ReadingProgressEntity`/`ReadingProgressDao`, or scoped some other way; needs a migration. Blocks nothing above but should land before this ships, since it's reachable the moment two windows on the same novel are both open, which multi-document mode exists to allow. |
| 5 | queued | **Second back-stack entry.** The proposal's "a single `NavHost` with multiple reader back-stack entries... without the window-management or process-lifecycle complexity of separate tasks" still looks like the right call against the current nav graph - there's exactly one `composable("reader/{novelId}/{chapterId}", ...)` destination (`MainActivity.kt:1360-1365`), and Previous/Next already *replace* the current entry via `popUpTo(...) { inclusive = true }` (`MainActivity.kt:1572-1587`) rather than push, so "open a second document" becomes a genuine *push* (`navController.navigate(...)` without that `popUpTo`) from wherever a user action for "open in a new reader" is added - not yet decided. This is sequenced last because rungs 1-3 are what make a second simultaneously-live entry survive contact with TTS at all; building the nav push first would just make the existing collisions easier to hit. |

## Still open

- **Which user action opens a second document?** The proposal's own last open
  question - "is a second reader back-stack entry... an acceptable substitute for
  the reference app's separate task-switcher windows, or was the separate-window
  part of the original ask" - is unresolved, and rung 5 can't be scoped (long-press
  "open in new reader"? a button in `NovelDetailScreen`? always-push instead of
  Previous/Next's current replace-in-place?) until it is.
- **No settings-page toggle fits this.** The original ask that started this pass
  was "add a multi-document toggle to the reader settings page" - there's nothing
  to gate behind a boolean here. Multi-document isn't a default a new reading
  session seeds from (the shape every other `settings/reader` row follows, see
  that screen's own doc comment); it's whether a *second* back-stack entry can
  exist at all, decided by the nav action in rung 5, not by a persisted
  preference. If a toggle is still wanted once rung 5 lands, it'd most likely be
  an on/off for *whether the "open in new reader" action is offered at all*,
  which is a product decision for whoever resolves the question above, not a
  rung on its own.
- **keepScreenOn OR-ing across two windows** (flagged under "What's already fine,
  unmodified" above) - not blocking, but will read as a bug once rung 5 ships if
  left as-is.

## On completion

Delete this file, fold the "service's `listener`/`start()` had no collision guard
until this pass" and "position is char-offset keyed by `(novelId)` only, not
per-chapter" facts into [`../foundational/design-notes.md`](../foundational/design-notes.md),
and update `../README.md`.
