# Reader settings page

> **Kind:** proposal · **Status:** design only · **Verified:** 2026-11-07 @ 8e0a937
> **Origin:** a screenshot of a reference reader app's settings (2026-11-07) showing six reader switches. Related: [`peer-app-survey.md`](peer-app-survey.md) lists "per-title reader settings" among features seen in other apps.

Nothing here is built or decided. This sorts the six switches in the
screenshot into what ARKarium could adopt, what needs something else first,
and what it shouldn't copy, against what the reader does today.

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

## The six switches

| Reference switch | What it does there | ARKarium today | Recommendation |
|---|---|---|---|
| Full-screen mode | Hides the status and navigation bars. | Immersive hides only the app's own chrome. | **Adopt.** Small. |
| Brightness by swipe | A vertical swipe on the left side of the screen changes brightness. | No brightness control. | **Adopt the control, not the gesture.** See below. |
| Day-Night by touch | Touching the upper-left corner switches colour mode. | Three reading modes picked in the pill. | **Later, as a button first.** |
| Bookmark by touch | Touching the upper-right corner adds a bookmark. | No bookmarks. | **Not a setting yet.** Blocked. |
| Multi-document mode | Opens books in a new window; switch with the system's app switcher. | One activity, one reader. | **Don't adopt.** |
| Screen on | Keeps the screen on for 10 minutes longer than the system setting. | Only while read-aloud is speaking. | **Adopt, as a general reader option.** |

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

Not proposed. ARKarium is a single `MainActivity` with one `NavHost`. Opening
readers in separate task windows would create several of them at once, and they
would share one `TtsPlaybackService` that has a single `listener` (set in
`ChapterTtsState.attach`) and one foreground notification, so two open readers
would fight over read-aloud state. The cost is high and the use case (reading
two chapters side by side on a phone) is narrow.

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

## Page shape

A new "Reader" row in the Settings index (after Theme) opening `settings/reader`,
in the same shape as `settings/tts`: flat `PreferencesManager` keys, each with a
pure Context-free reader function and a round-trip test, no ViewModel (see
[`../foundational/design-notes.md`](../foundational/design-notes.md): "Settings is
an index; each option is its own page").

The page would look odd holding only two toggles while font size, line height,
font family and reading mode stay unpersisted. The natural fix is to give it
defaults for those four too, seeded the way the TTS defaults are: the pill still
adjusts live and never writes back to the default. That is the largest part of
this proposal and the one the owner should decide on (first question below).

## Suggested order

1. Persist the reader's four existing settings as defaults and add the empty
   page, since everything else hangs off it.
2. `reader_keep_screen_on`.
3. `reader_fullscreen`, alongside the edge-to-edge work in F17.
4. Brightness slider in the pill (no page setting).
5. Day/night button in the pill.
6. Bookmarks: separate proposal, after rung 9 and F9.

## Open questions for the owner

- Should the Reader page also hold defaults for font size, line height, font
  family and reading mode (and should "per-title" overrides come later, as the
  survey notes another app doing)?
- Brightness: slider in the pill, or a left-edge gesture despite the scroll
  conflict?
- Corner tap zones: wanted at all, or are buttons enough?
- When bookmarks are built, should they be per chapter position only or also
  carry a short note?
