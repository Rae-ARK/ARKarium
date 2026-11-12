# Animation, platform readiness, and reading typography

> **Kind:** proposal · **Status:** F16 built in full; F17 and F19 still open · **Verified:** 2026-11-13 @ 99cb9ff
> **Origin:** [`engineering-review.md`](engineering-review.md) F16, F17, F19.

### F16. Almost no animation outside the splash — built

**Severity:** Low. **Tags:** V.
**Where:** `navigation/NavMotion.kt` (screen transitions), `ui/WaterRipple.kt`
(the reader's bottom-pill swap), `ArkariumApp.kt` (shared `ImageLoader`,
cover crossfade), `ui/NovelDetailScreen.kt` (chapter list placement
animation), `ui/ReaderScreen.kt` (reading-mode color animation, chrome fade),
`ui/SyncDialogs.kt` (sync status crossfade).

Every row below is now built:

| Place | Today | Direction |
|---|---|---|
| Home → detail → reader nav | Material shared-axis X (300ms, ~30dp slide + fade-through) forward and back; a fade-through for Previous/Next chapter - `NavMotion` | A cover shared-element transition once the Compose/Navigation upgrade in F17 lands |
| Chapter list changes (sync adds a chapter, a filter is applied) | `Modifier.animateItemPlacement()` on each row (see `NovelDetailScreen.kt`'s `itemsIndexed` chapter list) | `animateItem()` once the Compose BOM bump in `performance.md` F12 lands - `animateItemPlacement()` is the version this project's current BOM (2023.10.01) actually has; `animateItem()` needs foundation 1.7.0+ |
| Cover load | Crossfades in (200ms) via the shared Coil `ImageLoader` built in `ArkariumApp.newImageLoader()` (`ImageLoaderFactory`), with a 25%-of-memory and 2%-of-disk cache - every `AsyncImage` call picks it up automatically, no per-call-site change needed | Done; a `placeholder` per call-site is still open if a loading-state sketch is wanted later |
| Reading mode switch (Light/Sepia/Dark) | `animateColorAsState` on both `backgroundColor` and `textColor`, 200ms tween, in `ReaderScreen.kt` | Done |
| Reader chrome, TTS controls | The bottom pill swaps Reader Preferences ↔ read-aloud controls with a water ripple; the top bar and progress readout each fade via `AnimatedVisibility` (210ms in / 90ms out) layered on the existing `animateContentSize()` that already owns their height change | Done; a draw-phase TTS highlight once the chunk-highlight work in [`../implementation/tts-playback-controls.md`](../implementation/tts-playback-controls.md) rung 5 lands (its prerequisite, `reader-playback.md` F10, is already done) |
| Sync progress | `AnimatedContent` crossfades the one status line (arc name / result / error) in `SyncProgressDialog` | Done; no determinate progress bar - deliberate, see that composable's own doc comment on why there's no file-count signal to drive one from |

**Rules this was built to, so animation doesn't cost frames or break
accessibility settings:**
- Animate `alpha`/`scale`/`translation` through `graphicsLayer {}` or a
  lambda-based modifier (`offset {}`), not through a property that forces
  layout (animating a `Text`'s font size or line spacing re-lays-out the
  whole chapter every frame — a snap is the correct choice there, not an
  animation).
- Exits should be faster than entrances (Material's motion tokens pair a
  longer decelerate-entrance with a shorter accelerate-exit) - applied as
  210ms-in/90ms-out throughout the new work above.
- Compose's own animation APIs already respect the system's "remove
  animations" accessibility setting. Anything manual or time-based — the
  splash's chord audio in particular — does not get that for free and needs
  its own check against the animator duration scale setting.
- The old row-striping pattern (`index % 2` as the alternating-row color
  source) would have visibly flickered once placement animation was in
  play, since a moved item's *position* changes but that derived its color
  from position rather than from the item's own stable key. Fixed alongside
  `animateItemPlacement()` itself: striping in `NovelDetailScreen.kt` now
  keys off `chapter.id.hashCode()`, not `index`.

### F17. Not ready for predictive back or edge-to-edge

**Severity:** Low. **Tags:** V for current versions/flags, E for the exact
version/API requirements (re-check against current Android docs before
scheduling this).
**Where:** `app/build.gradle.kts` (`navigation-compose:2.7.5`);
`AndroidManifest.xml` (no `android:enableOnBackInvokedCallback`);
display-cutout inset handling now exists app-wide (`WindowInsets.displayCutout`,
built per [`landscape-tablet.md`](../far-future-concern/landscape-tablet.md)'s cutout fix) plus
Reader's own status-bar show/hide for its full-screen toggle, but no general
system-bar edge-to-edge handling (`systemBarsPadding`, `safeDrawing`, etc.)
anywhere under `ui/` — most screens zero out their `Scaffold`'s
`windowInsets`/`contentWindowInsets` instead of consuming them.

Predictive back in Navigation Compose needs `2.8.0-alpha06` or later plus the
manifest flag; this project is on `2.7.5` with no flag, so it gets the plain
back-stack pop with no predictive preview. Edge-to-edge: the display cutout is
now handled app-wide, but general system-bar insets (status/navigation bars)
still aren't formally handled, and — not re-verified this pass, flagged **E**
— targeting a newer API level is expected to make edge-to-edge mandatory
rather than opt-in, which would break layouts that don't already account for
system bar insets. Confirm the exact enforcement version against current
Android docs before `compileSdk`/`targetSdk` is bumped past 34.

**Direction:** bundle with the Navigation upgrade in `performance.md` F12/D4
(2.8+, the manifest flag) for predictive back. Edge-to-edge is a separate,
larger pass — add `WindowInsets` handling screen by screen — and should be
scheduled ahead of any `targetSdk` bump past whatever version makes it
mandatory, not reactively after a bump breaks layouts.

### F19. Typography: justified text with no hyphenation, uncapped line width

**Severity:** Low. **Tags:** V for `TextAlign.Justify`'s presence, I for the
visual effect (rivers/line-length, not inspected on a device this pass).
**Where:** `ui/ReaderScreen.kt`, the paragraph-block `Text` in the chapter's
`LazyColumn` (see `readerBlocks()`'s doc comment for the block split) — its
`textAlign = TextAlign.Justify`.

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
