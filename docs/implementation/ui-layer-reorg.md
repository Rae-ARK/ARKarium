# UI-layer reorganisation: MainActivity, ReaderScreen, TtsPlaybackService

> **Kind:** implementation · **Status:** A1 done, rung A2 next · **Verified:** 2026-11-10 @ 8cb0978
> **A1 result:** `MainActivity.kt` 2387 -> 2165 lines (-222); new `navigation/AppDialogHost.kt`, 265 lines. Behavior-preserving - same five dialogs, same state reads, same callbacks, just called from one composable instead of written out inline.
> **Origin:** a review pass prompted by `MainActivity.kt` crossing its
> pre-[`design-notes.md`](../foundational/design-notes.md#mainactivity-refactor-outcome-done)
> size again (2387 lines vs. 1706 before that refactor, 1428 after it), run
> against the whole `app/src/main/java` tree to check which other files have
> the same shape of problem.

## What's actually wrong, per file

Line counts alone don't justify a rewrite - a file can legitimately be long
because the problem is long. The question for each file below is whether its
growth is *structural* (one function/class doing several unrelated jobs) or
just *proportional* (a genuinely single-purpose file that grew because the
feature did). Only the structural cases get a rung.

| File | Lines | Shape of the problem |
|---|---|---|
| `MainActivity.kt` | 2387 | Structural. `renderMainContent()` alone is ~1550 lines: all 17 `NavHost` route bodies and all 5 dialog-host `when` blocks are written inline instead of as their own composables. This is exactly the problem [Phase 3](../foundational/design-notes.md#mainactivity-refactor-outcome-done) fixed for navigation *state* (`Screen` → `NavController`) but never fixed for navigation *wiring* - each `composable("...") { ... }` lambda got the full callback graph for its screen pasted in place, with nowhere else to put it at the time. |
| `ReaderScreen.kt` | 2082 | Structural. One `@Composable fun ReaderScreen(...)` runs from line 316 to 1708 - 1392 lines in a single function. The file already has the right instinct below it (`ProgressReadout`, `TtsControlsBar`, `ChapterNavRow`, `AboutAuthorCard`, etc. are already separate private composables) - the top-level function itself is just the one piece that was never broken up the same way. |
| `TtsPlaybackService.kt` | 1291 | Structural. One `Service` class, ~1042 lines, mixing five concerns that already have service-sized boundaries elsewhere in this codebase: notification building, `MediaSession` wiring, audio-focus plumbing (the *policy* is already split out, in `TtsAudioFocusPolicy.kt` - the `AudioManager` calls that use it aren't), the TextToSpeech engine lifecycle, and the playback queue/chunk state machine. |
| `NovelDetailScreen.kt` | 957 | Borderline, not urgent. The composable itself is 565 lines (95-660); the rest of the file is already-separate helper composables (`ArcTabCard`, `EnlargedCoverDialog`, etc.), the same healthy shape `ReaderScreen.kt` has below its one oversized function. Tracked as a queued rung (D1) in case it crosses ReaderScreen's threshold later, not acted on now. |
| `ScannerImpl.kt` (744), `PreferencesManager.kt` (528), `SyncManager.kt` (624), `SyncViewModel.kt` (580), `HomeScreen.kt` (687), `ReaderSettingsScreen.kt` (531) | - | Proportional. Each is one service/screen doing one job; `wc -l` is higher than it was mostly because the feature surface (sync resolution states, reader settings rows, TTS defaults) grew. No rung for these - revisit only if one of them stops being a single function/class doing one job, same test as above. |

This doc only covers the four structural rows. Nothing here changes what any
screen looks like or how the app behaves - every stage is a pure,
behavior-preserving extraction, same rule [Phase 1/3 of the original
refactor](../foundational/design-notes.md#mainactivity-refactor-outcome-done)
followed ("Behavior-preserving moves first" in Settled decisions). The
[Code philosophy](../foundational/design-notes.md#code-philosophy) rules
(service-oriented, layers depend downward, functional by default, minimal
boilerplate) apply unchanged - none of this needs a new ViewModel, a new
service abstraction, or a new pattern; it's the existing patterns applied to
code that never got run through them.

## Phase A - MainActivity: route/dialog bodies out of `renderMainContent()`

Same shape as the original Phase 3 (NavHost migration), one level down: that
phase moved *which screen to show* out of the Activity; this phase moves
*how to wire that screen's callbacks* out too. Each stage below is one
`git mv`-sized, independently shippable extraction - a route's full body
(state reads, `LaunchedEffect`s, the screen call and its callbacks) moves
into a `@Composable` function in `navigation/`, and `MainActivity`'s
`NavHost` block calls it instead. No stage changes a route string, an
argument, or a callback's behavior.

| Rung | Item | Lines moved | Status | Target file |
|---|---|---|---|---|
| A1 | Dialog host: the 5 trailing `when` blocks (`metadataSearchState`, `addFictionState`, `syncAllState`, `syncCheckState`, `syncResolutionState`) | ~220 | done | `navigation/AppDialogHost.kt` (one `AppDialogHost(...)` composable, called once after `Scaffold`) |
| A2 | Settings + legal routes: `settings`, `noticeBoard`, `settings/theme`, `settings/library`, `settings/splash`, `settings/reader`, `settings/tts`, `settings/tts/voice`, `privacy_policy`, `terms`, `about_me` (11 routes) | ~320 | queued | `navigation/SettingsRoutes.kt` |
| A3 | `home` + `fictionBrowse` | ~100 | queued | `navigation/LibraryRoutes.kt` |
| A4 | `novelDetail` + `chapterEditor` | ~145 | queued | `navigation/NovelRoutes.kt` |
| A5 | `reader` + `author` - the single biggest block (the `reader` route alone is ~360 lines, by far the largest of the 17) | ~390 | queued | `navigation/ReaderRoutes.kt` |
| A6 | Wire-up: `NavHost {}` in `MainActivity` becomes 17 one-line `composable(...) { RouteFn(...) }` calls; delete now-empty imports | - | queued, after A1-A5 | `MainActivity.kt` |

Each extracted route function takes the Activity's services/ViewModels and
`NavHostController` as plain constructor-style parameters (same shape
`HomeScreen`/`NovelDetailScreen`/etc. already take their data as parameters
today - these aren't new composables in spirit, just composables that used
to be typed inline instead of named). `currentNovelDetailId` and the other
handful of fields `renderMainContent()` reads across route bodies move with
whichever rung first needs them; if more than one rung ends up needing the
same field, it stays a parameter passed into each, not a new shared object -
rule 4 (minimal boilerplate), same reasoning `design-notes.md` already gives
for not having one god ViewModel.

**Expected result:** `MainActivity.kt`'s ~839 lines of fields/`onCreate()`/
helper functions (the part this review found no structural problem with)
are untouched; `renderMainContent()` shrinks from ~1550 lines to roughly the
size of its own `setContent {}` scaffolding plus 17 one-line route
registrations - call it 900-1000 lines total for the file, back below the
1428-line state Phase 3 left it in, not just below today's 2387.

## Phase B - ReaderScreen: breaking up the one 1392-line composable

`ReaderScreen()` (line 316-1708) currently does, inline, in one function:
top bar, bottom bar/footer, the reader-settings pop-up pill, fullscreen/
system-bar window effects, scroll-position tracking and restore (feeding
`ReaderPosition.kt`/`paragraphOffsets`), and the TTS controls wiring (on top
of the already-extracted `ReaderTtsLogic.kt`/`ReaderBlocks.kt` pure logic).
Each stage pulls one of those out as its own composable or plain function,
in a new `ui/reader/` sub-package so `ReaderScreen.kt` stops being both "the
screen" and "every screen's package" at once.

| Rung | Item | Status | Target file |
|---|---|---|---|
| B1 | Top bar + bottom chapter-nav footer | queued | `ui/reader/ReaderChrome.kt` |
| B2 | Reader-settings pill/pop-up panel | queued | `ui/reader/ReaderSettingsPill.kt` |
| B3 | Window/fullscreen/cutout `LaunchedEffect`s (`applyWindowLayout` callers, insets handling) | queued | `ui/reader/ReaderWindowEffects.kt` |
| B4 | Scroll-position tracking/restore (`onGloballyPositioned` anchor capture, `paragraphForCharOffset` restore) | queued | `ui/reader/ReaderPositionTracking.kt`, alongside existing `data/ReaderPosition.kt` |
| B5 | Everything that's left - TTS bar wiring plus the composable's own orchestration (calls B1-B4, renders the chapter body via existing `ReaderBlocks.kt`) | queued, after B1-B4 | stays in `ReaderScreen.kt` |

Same rule as Phase A: nothing here is new state or a new pattern, just
existing `LaunchedEffect`/composable blocks moved to their own named
function so `ReaderScreen()` itself reads as an outline instead of the
entire screen. Target: `ReaderScreen()` itself down to roughly 300-400
lines of orchestration; `ReaderScreen.kt` overall likely stays the biggest
single screen file in the app even after this (it has the most going on),
just no longer one function doing it all.

## Phase C - TtsPlaybackService: five concerns, one class

`TtsPlaybackService` (line 218-1260) is the Service `Context`/lifecycle
*does* need to own - `onCreate`/`onBind`/`onStartCommand`/`onDestroy` and
the play/pause/skip/queue state machine (`start`/`stop`/`pause`/`resume`/
`skipNext`/`skipPrevious`/`jumpTo`/`speakNextChunk`/`onUtteranceFinished`/
`onChunkError`) can't move anywhere else without turning the Service into a
thin shell around a second stateful class, which would just relocate the
same amount of state (rule 4: not worth it unless something else needs that
seam). What *can* move out, same as `TtsAudioFocusPolicy.kt` already did for
the audio-focus decision table, is everything the Service only builds and
hands to the OS:

| Rung | Item | Status | Target file |
|---|---|---|---|
| C1 | Notification building (`ensureChannel`, `transportPendingIntent`, `transportIcon`, `transportLabel`, `buildNotification`, `refreshNotification`, `loadAlbumArt`) | queued | `playback/TtsNotificationBuilder.kt`, alongside existing `TtsNotificationActions.kt` |
| C2 | `MediaSession` wiring (`createMediaSession`, `updateMediaSession`, `currentPositionMs`, `positionMsAtChunkStart`, `releaseSessionResources`) | queued | `playback/TtsMediaSessionController.kt` |
| C3 | Audio-focus/noisy-receiver plumbing (`requestAudioFocus`, `abandonAudioFocus`, `registerNoisyReceiver`, `unregisterNoisyReceiver`) - the `AudioManager`/`BroadcastReceiver` calls that act on `TtsAudioFocusPolicy`'s existing decision table | queued | folded into `TtsAudioFocusPolicy.kt` as a small controller alongside the policy functions it already has |

Each takes the Service (or just its binder/callbacks) as a constructor
parameter and is instantiated once in `onCreate`, same "service owns a
resource, Service becomes its caller" shape the four Phase-2 ViewModels
already used against `MainActivity` - this is that same move one layer
down. **Expected result:** the Service class itself drops from ~1042 lines
to roughly 500-600, holding only the playback state machine and the handful
of lifecycle callbacks that must stay on a `Service`.

## Phase D - NovelDetailScreen (queued, not current work)

One rung, held for later: if `NovelDetailScreen()`'s own composable (today
565 lines) crosses roughly the size `ReaderScreen()` was before Phase B, or
picks up a second concern the way `ReaderScreen()` picked up settings/
window-effects/position-tracking on top of rendering, split it the same way
- most likely the arc tab strip/header block out to its own composable,
since `ArcTabCard`/`CoverEnlargeRipple`/etc. are already separate and the
arc-strip rendering inside the main function is the next largest
self-contained piece. Not started; this row exists so it isn't rediscovered
from scratch next time someone runs this same review.

## Order and why

A before B before C: Phase A is the lowest-risk (pure UI wiring, same
pattern already proven twice in this codebase) and unblocks the biggest
single number (`MainActivity.kt`, already past its old 1706-line trigger
point); B and C are each independent of A and of each other and can run in
either order or in parallel once A lands - listed B-then-C here only because
`ReaderScreen` is the bigger outstanding file. D is explicitly not scheduled.

## Testing

No new testing tier needed - this follows the same split
[`design-notes.md`](../foundational/design-notes.md#testing-strategy)
already describes. Extracted composables are UI and stay manually/
instrumented-tested like the screens they came from; `TtsNotificationBuilder`/
`TtsMediaSessionController`'s pure decision points (which icon/label a
transport action gets, what a notification's content should say for a given
state) get a JVM test the same way `TtsAudioFocusPolicyTest` already covers
`TtsAudioFocusPolicy`, if and when C1/C2 produce a pure function worth
isolating - not a goal in itself, a byproduct if one falls out.

## Exit

When Phase A (or A+B+C together, if done in one pass) lands, update this
ladder's rows to `done`, record the before/after line counts the way the
original refactor's commits did, and - once every phase here is either done
or explicitly deferred - fold the outcome into
[`design-notes.md`](../foundational/design-notes.md#mainactivity-refactor-outcome-done)
as a second table under the existing one, same lifecycle rule every other
ladder in this folder follows.
