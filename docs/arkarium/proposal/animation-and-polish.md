# Animation, platform readiness, and reading typography

> **Kind:** proposal · **Status:** screen transitions and the reader's bottom-pill swap built; the rest open · **Verified:** 2026-11-07 @ a621542
> **Origin:** [`engineering-review.md`](engineering-review.md) F16, F17, F19.

### F16. Almost no animation outside the splash

**Severity:** Low. **Tags:** V.
**Where:** outside the splash, motion exists in `navigation/NavMotion.kt` (screen
transitions), `ui/WaterRipple.kt` (the reader's bottom-pill swap) and one
`animateContentSize()` on the reader's preferences panel.

List changes, sync progress updates, and the Light/Sepia/Dark theme switch
still cut instantly, with no deliberate motion design outside the splash — which, per `performance.md` F13, is already the most expensive
single thing in the app and arguably animated for too long, not too little.

**Where animation would actually earn its keep** (effort roughly
hours-to-a-day each, see `engineering-review.md`'s suggested order item 6/7):

| Place | Today | Direction |
|---|---|---|
| Home → detail → reader nav | Material shared-axis X (300ms, ~30dp slide + fade-through) forward and back; a fade-through for Previous/Next chapter - `NavMotion` | A cover shared-element transition once the Compose/Navigation upgrade in F17 lands |
| Chapter list changes (sync adds a chapter, a filter is applied) | Instant | `animateItem()` — needs the stable `key =` values from `performance.md` F14 first |
| Cover load | Pops in | Crossfade via the shared `ImageLoader` in `performance.md` F15 |
| Reading mode switch (Light/Sepia/Dark) | Snaps | `animateColorAsState` on the background, ~200ms |
| Reader chrome, TTS controls | The bottom pill swaps Reader Preferences ↔ read-aloud controls with a water ripple; the top bar and progress readout still snap | `AnimatedVisibility` fade for the top bar and readout; a draw-phase TTS highlight once `reader-playback.md` F10 exists |
| Sync progress | Text swaps | `AnimatedContent` on the arc label; `animateFloatAsState` for a determinate progress bar |

**Rules to follow once this is built**, so animation doesn't cost frames or
break accessibility settings:
- Animate `alpha`/`scale`/`translation` through `graphicsLayer {}` or a
  lambda-based modifier (`offset {}`), not through a property that forces
  layout (animating a `Text`'s font size or line spacing re-lays-out the
  whole chapter every frame — a snap is the correct choice there, not an
  animation).
- Exits should be faster than entrances (Material's motion tokens pair a
  longer decelerate-entrance with a shorter accelerate-exit).
- Compose's own animation APIs already respect the system's "remove
  animations" accessibility setting. Anything manual or time-based — the
  splash's chord audio in particular — does not get that for free and needs
  its own check against the animator duration scale setting.
- The existing row-striping pattern (`index % 2` as the alternating-row
  color source) will visibly flicker once `animateItem()` placement
  animation is in play, since a moved item's *position* changes but this
  derives its color from position rather than from the item's own stable
  key. Derive striping from the key instead, or drop it, before adding
  `animateItem()` to that list.

### F17. Not ready for predictive back or edge-to-edge

**Severity:** Low. **Tags:** V for current versions/flags, E for the exact
version/API requirements (re-check against current Android docs before
scheduling this).
**Where:** `app/build.gradle.kts` (`navigation-compose:2.7.5`);
`AndroidManifest.xml` (no `android:enableOnBackInvokedCallback`); no inset
handling (`WindowInsets`, `systemBarsPadding`, etc.) anywhere under `ui/`.

Predictive back in Navigation Compose needs `2.8.0-alpha06` or later plus the
manifest flag; this project is on `2.7.5` with no flag, so it gets the plain
back-stack pop with no predictive preview. Edge-to-edge: there's no inset
handling anywhere in the UI layer today, and — not re-verified this pass,
flagged **E** — targeting a newer API level is expected to make edge-to-edge
mandatory rather than opt-in, which would break layouts that don't already
account for system bar insets. Confirm the exact enforcement version against
current Android docs before `compileSdk`/`targetSdk` is bumped past 34.

**Direction:** bundle with the Navigation upgrade in `performance.md` F12/D4
(2.8+, the manifest flag) for predictive back. Edge-to-edge is a separate,
larger pass — add `WindowInsets` handling screen by screen — and should be
scheduled ahead of any `targetSdk` bump past whatever version makes it
mandatory, not reactively after a bump breaks layouts.

### F19. Typography: justified text with no hyphenation, uncapped line width

**Severity:** Low. **Tags:** V for `TextAlign.Justify`'s presence, I for the
visual effect (rivers/line-length, not inspected on a device this pass).
**Where:** `ui/ReaderScreen.kt:428`, the chapter body `Text`'s `textAlign =
TextAlign.Justify`.

Justified text without hyphenation tends to produce visible "rivers" of
whitespace on narrow phone widths, since the justifier can only stretch
inter-word spacing, not break words. There's no line-width cap, so on a wide
or landscape phone the chapter body can also run to a line length that's
uncomfortable to read in one pass.

**Direction:** cap the text column's max width so lines stay in a reasonable
reading-length range regardless of screen width, and enable hyphenation once
on a Compose version that supports it (check the current Compose text API —
this project is on an old BOM per `performance.md` F12, and hyphenation
support has moved across Compose releases, so re-check rather than assuming
this project's current version has it).
