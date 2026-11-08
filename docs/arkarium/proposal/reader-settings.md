# Reader settings page

> **Kind:** proposal · **Status:** suggested-order items 1-5 built, 6-10 still design only · **Verified:** 2026-11-08 @ 8e0a937
> **Origin:** a screenshot of a reference reader app's settings (2026-11-07) showing six reader switches, plus three items the owner raised separately on 2026-11-08 (multi-document's TTS collision, column count, volume). Related: [`peer-app-survey.md`](peer-app-survey.md) lists "per-title reader settings" among features seen in other apps.

**2026-11-08 update:** the owner approved building the "cheap" batch now -
suggested-order items 1 through 5 below - and documenting the rest for a
later pass. That batch is built: persisted font size/line height/reading-mode
defaults plus the `settings/reader` page, the font family row,
`reader_keep_screen_on`, `reader_fullscreen`, and an opt-in brightness slider
in the pill (see `design-notes.md`'s "Reader settings split" entry for the
shape that landed - the brightness slider in particular is opt-in via a
switch on `settings/reader`, which this proposal's own Brightness section
below did not call for). Items 6-10 (multi-document, day/night button,
volume slider, column count, bookmarks) are unchanged from this proposal -
still design only, nothing built.

This sorts the six switches from the screenshot, plus the three items added
on 2026-11-08, into what ARKarium could adopt, what needs something else
first, and what it shouldn't copy, against what the reader did before this
pass.

> **Update:** the Reader Preferences pill no longer holds any settings of its own - it opens
> Settings > Reader, which is now the only place size, spacing, mode, font and brightness are
> changed. Wherever this doc says "in the pill", read "on the Reader settings page". See the
> backlog's "Reader settings - one place, not two" section.

## What the reader does today

All of these were read in `ui/ReaderScreen.kt` and `MainActivity.kt` this pass.

- **No reader preferences are persisted.** Font size (18), line height (1.8),
  font family and reading mode are `remember` state in `ReaderScreen`; the code
  comments say so deliberately. They survive Previous/Next because the
  composable does, and reset when the reader is left.
- **Reading mode** is Light / Sepia / Dark, chosen in the "Reader Preferences"
  pill and seeded from the app theme.
- **Immersive mode is app chrome only.** A tap anywhere toggles the top bar,
  pill and progress readout. Nothing hides the system status or navigation
  bars; the only system-bar call in the app is the status-bar icon colour.
- **Keep screen on exists only for read-aloud**: `tts_keep_screen_on`, applied
  as `FLAG_KEEP_SCREEN_ON` while a session is speaking.
- **No bookmarks.** Reading position is a saved scroll fraction per chapter.
- **No brightness control** and no tap-zone gestures.

## The switches

| Reference switch | What it does there | ARKarium today | Recommendation |
|---|---|---|---|
| Full-screen mode | Hides the status and navigation bars. | Immersive hides only the app's own chrome. | **Adopt.** Small. |
| Brightness by swipe | A vertical swipe on the left side of the screen changes brightness. | No brightness control. | **Adopt the control, not the gesture.** See below. |
| Day-Night by touch | Touching the upper-left corner switches colour mode. | Three reading modes picked in the pill. | **Later, as a button first.** |
| Bookmark by touch | Touching the upper-right corner adds a bookmark. | No bookmarks. | **Not a setting yet.** Blocked. |
| Multi-document mode | Opens books in a new window; switch with the system's app switcher. | One activity, one reader. | **Adopt, with a single read-aloud session.** See below. |
| Screen on | Keeps the screen on for 10 minutes longer than the system setting. | Only while read-aloud is speaking. | **Adopt, as a general reader option.** |
| Column count (landscape) | Not in the reference screenshot; raised separately by the owner. | One column always. | **Adopt.** See below. |
| Volume slider | Not in the reference screenshot; raised separately by the owner. | System volume only, no in-app control. | **Adopt, alongside brightness.** See below. |

### Full-screen mode

A `reader_fullscreen` boolean, default false (the codebase's habit is no
behavior change until someone opts in). When on, hiding the app chrome with the
existing tap also hides the system bars through `WindowInsetsControllerCompat`
with transient-bars-by-swipe, and leaving the reader restores them. Two things
to settle when it's built: restoring the bars on every exit path (Back,
Previous/Next, navigating to `settings/tts` mid-read-aloud), and the display
cutout in landscape, which can leave a black strip unless the window is
allowed to extend into it. Edge-to-edge in general is
[`animation-and-polish.md`](animation-and-polish.md) F17; do this with it in
mind rather than around it.

### Brightness

ARKarium's reader is one vertically scrolling page. A vertical swipe on the
left side is a scroll start, so a swipe-to-change-brightness zone would take
scrolling away from that strip, and a swipe that starts there while reading
would change brightness instead of moving the text. The reference app turns
pages, so the same gesture doesn't compete with anything there.

Recommended instead: a brightness slider in the existing Reader Preferences
pill, setting the window's `screenBrightness` for the reader's lifetime and
restoring "follow the system" when the reader is left. No setting is needed
for that. If the owner still wants a gesture, it needs a narrow dedicated
strip and a decision on whether it takes priority over scrolling.

### Day-Night by touch

A corner tap would be an exception carved out of the tap-anywhere immersive
toggle, with a risk of accidental triggers, and with three modes it isn't even
"day/night". A button in the pill that flips between the last two modes gives
the same one-tap reach without a hidden zone. Corner zones can follow if the
button proves too hidden.

### Bookmark by touch

This is a feature, not a setting. ARKarium has no bookmark model, and two
things should land first:

- a Room table, which means the schema export and migration tests in the
  engineering backlog's rung 9 ([`../implementation/engineering-backlog.md`](../implementation/engineering-backlog.md));
- a position that doesn't drift. A bookmark stored as a scroll fraction would
  move whenever the font size or the text changes; F9 in
  [`reader-playback.md`](reader-playback.md) is the offset-based anchor that
  fixes this.

When those exist, a bookmark button in the reader's chrome is the safer first
version, with a corner tap as an option later. It deserves its own proposal
then.

### Multi-document mode

Revised from "don't adopt" after the owner's counter-proposal: keep one global
read-aloud session rather than one per window, and resolve a collision with a
confirm dialog instead of blocking it.

This matches how the surveyed reference apps that pair multiple open
documents with audio handle it (see `peer-app-survey.md` additions): there's
no such thing as two simultaneously live playback sessions fighting over a
lock, because the app only ever runs one. A second "open document" is a
second navigation destination, not a second player. Opening audio elsewhere
stops (or, in a couple of surveyed players, hands off) the running one first.
Several of the surveyed changelogs are explicitly about getting that handoff
right rather than about the lock itself - save-before-switch races (an
in-flight periodic save landing on the new book after the switch instead of
the old one, clobbering its position) and media-session state resync after
Android rebuilds the playback service mid-session are the recurring failure
modes, not "two sessions ran at once."

Concretely, for ARKarium:

- `TtsPlaybackService` keeps its single `listener` exactly as it is now -
  that's already the "lock," since only one `ChapterTtsState` can be attached
  at a time. Nothing new to build there.
- A second reader window calling `tts.speak()` while another window's session
  is live (`isSpeaking || isPaused` true, attached to the other window) shows
  a confirm dialog: *"Stop reading [other chapter title] aloud and start
  this chapter instead?"* with Yes/No, the same shape as the "Confirm
  Audio-Changing Settings" pattern a couple of the surveyed apps use for other
  audio-interrupting actions.
- On Yes: the owning window's session is stopped gracefully (not killed -
  `ChapterTtsState.stop()`, which already flushes the queue cleanly), its
  reading position is written out immediately, and the new window's
  `tts.speak()` proceeds and takes the listener.
- **Position handling needs one change from what the owner proposed.** The
  owner's wording was "saved temporarily in the activity, lasting as long as
  the document isn't closed" - that's safe for a simple tab/window switch,
  but Android is free to kill a *backgrounded* window's process under memory
  pressure while the task-switcher entry for it still exists, same risk the
  Saga changelog's save-race bugfix exists to guard against from the other
  direction. An Activity-only in-memory position would be silently lost in
  that case even though the document "wasn't closed." Writing the position to
  the existing per-chapter scroll-fraction store on the stop (not just holding
  it in memory) costs nothing extra here, since the reader already persists
  that on normal exit - stopping-for-handoff should use the same path rather
  than a separate in-memory holding spot.
- Rendering readers in separate task windows (Android's multi-window /
  "multi-instance" task launch) rather than tabs inside one `NavHost` is the
  one piece of the original multi-document idea this doesn't need: a single
  `NavHost` with multiple reader back-stack entries gets the "more than one
  book open" behavior the owner wants without the window-management or
  process-lifecycle complexity of separate tasks. Worth confirming with the
  owner that "new window via the system app switcher" wasn't itself the
  wanted part, since the switch above assumes it isn't.

### Screen on

A `reader_keep_screen_on` boolean, default false, holding `FLAG_KEEP_SCREEN_ON`
while the reader is on screen. The existing effect in `ReaderScreen` already
manages the flag for read-aloud; the two should combine as "reader option on,
or read-aloud speaking with its own option on" so neither clears the other's
flag. The screenshot doesn't say how the reference app achieves "10 minutes
longer than the system setting". Android has no direct way to extend the system
timeout by a fixed amount; the plain options are the window flag alone, or the
flag plus an app-side idle timer that clears it after some minutes without
input. The idle timer is the better fit for someone who falls asleep reading.

### Column count (landscape)

Owner-proposed, not from the screenshot. One / Two / Three columns, default
Two, landscape only (portrait stays single-column - a phone in portrait is
too narrow for a second column to be worth the reading-order complexity).
Lives as a fourth segmented row in the Reader Preferences pill, same pattern
as Font and Mode. Implementation-wise this is a layout change to the chapter
body composable (splitting the already-measured text into N
`androidx.compose.foundation.layout.Row`-arranged columns, read top-to-bottom
within a column before moving to the next) rather than a new text-rendering
path, but it hasn't been prototyped - flag it as the one item in this
proposal whose cost is a guess until someone tries it against a real chapter.

### Volume slider

Owner-proposed, not from the screenshot. Controls read-aloud volume
specifically (via `AudioTrack`/`TextToSpeech` volume, not the device media
stream), alongside Speed in the pill, following the "if you have Brightness,
and TTS, this is a no-brainer" reasoning - the pill already breaks the
no-settings-page rule for a live, per-session audio control, so a second one
costs little. Only shown when `tts.isAvailable`, same gating as Speed. Needs
one decision the brightness slider didn't: whether the value should persist
as a default (like Speed, which is read from `PreferencesManager` in
`settings/tts`) or stay session-only (like Brightness, which intentionally
has no setting). Speed's existing precedent argues for persisting it.

### Font family

Moved out of the Reader Preferences pill into Settings - still a reader
setting, just not a per-session one. The owner's reasoning: font family is a
"set and forget" choice nobody is expected to flip per chapter or even per
title, unlike Text size, Spacing, Speed and Mode, which people do adjust
in-session (lighting changes, eyes get tired, a particular chapter runs late
at night). That's a real difference in kind from the other four pill
controls, not just a preference about where a toggle lives, so it gets its
own row in the Settings index rather than a defaults-for-the-pill treatment.
The "Dyslexia-friendly" option's current implementation (SansSerif plus
0.5sp tracking, not an actual dyslexia-oriented typeface) carries over
unchanged by this move; whether to rename it or back it with a real font
(e.g. OpenDyslexic) is a separate decision this proposal doesn't take.

## Page shape

A new "Reader" row in the Settings index (after Theme) opening `settings/reader`,
in the same shape as `settings/tts`: flat `PreferencesManager` keys, each with a
pure Context-free reader function and a round-trip test, no ViewModel (see
[`../foundational/design-notes.md`](../foundational/design-notes.md): "Settings is
an index; each option is its own page").

The page would look odd holding only two toggles while font size, line
height and reading mode stay unpersisted. The natural fix is to give it
defaults for those three too, seeded the way the TTS defaults are: the pill
still adjusts live and never writes back to the default. Font family gets
its own row instead (see above) since it's leaving the pill entirely rather
than getting a seeded default. Column count likely wants the same seeded-
default treatment as font size/spacing/mode once it exists, rather than its
own Settings row, since the owner's framing ("single, double, or triple,
that's it") reads as pill-live rather than set-and-forget. Whether font
size/spacing/mode get defaults at all is the largest remaining open part of
this proposal (first question below).

## Suggested order

1. Persist the reader's existing font size, line height and reading mode as
   defaults and add the empty page, since everything else hangs off it.
2. Font family row in Settings (independent of 1 - it was never one of the
   pill's seeded defaults to begin with).
3. `reader_keep_screen_on`.
4. `reader_fullscreen`, alongside the edge-to-edge work in F17.
5. Brightness slider in the pill (no page setting).
6. Multi-document (single-session TTS collision handling, see above) - after
   1, since the position-on-handoff write depends on the same persistence
   path.
7. Day/night button in the pill.
8. Volume slider in the pill, persisted alongside Speed.
9. Column count in the pill, landscape only - prototype the column-split
   layout first; cost is unconfirmed (see above).
10. Bookmarks: separate proposal, after rung 9 and F9.

## Open questions for the owner

- ~~Should the Reader page also hold defaults for font size, line height and
  reading mode (and should "per-title" overrides come later, as the survey
  notes another app doing)?~~ **Answered 2026-11-08:** yes, defaults for all
  three; "per-title" overrides are not part of this pass. Unset reading mode
  keeps following the app theme rather than getting a pinned-default option.
- Brightness: slider in the pill, or a left-edge gesture despite the scroll
  conflict?
- Corner tap zones: wanted at all, or are buttons enough?
- When bookmarks are built, should they be per chapter position only or also
  carry a short note?
- Multi-document: is a second reader back-stack entry inside the existing
  `NavHost` an acceptable substitute for the reference app's separate
  task-switcher windows, or was the separate-window part of the original ask?
- "Dyslexia-friendly" font option: rename it to describe what it actually
  does, or invest in a real dyslexia-oriented typeface? Unchanged by the move
  to Settings, but flagged again since this is the natural point to revisit it.
