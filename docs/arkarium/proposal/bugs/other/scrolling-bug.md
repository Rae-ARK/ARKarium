# Reader scrolling is not smooth: scroll state is read during composition, every paragraph reports its position, and the whole chapter is composed eagerly

> **Kind:** proposal · **Status:** open — cause 1 fixed (unmeasured), causes 2-4 untouched · **Verified:** 2026-11-08 @ 09adbfe
> **Origin:** owner report ("scrolling isn't smooth"), code read of `ReaderScreen.kt` and `app/build.gradle.kts`, and a search of current Compose performance guidance.

**Seen:** reported by the owner on a device; build type and device model not
recorded (add them). Not measured. **Tags:** V (read directly in code), I
(inferred, cost or impact not measured), E (external guidance).
**Where:** `ui/ReaderScreen.kt` — `currentProgress()` (597–599) and the
progress readout (1171–1181); the scroll container (719–723); the
per-paragraph loop and its `onGloballyPositioned` (828–880, state write at
871–873); `paragraphContentTop` and the two `LaunchedEffect`s keyed on its
`.size` (533, 577). `app/build.gradle.kts` — `isMinifyEnabled = false` (76),
compiler 1.5.3 (88), Compose BOM 2023.10.01 (118).

**Repro:**

1. Install a **signed release build** on the weakest supported device. Debug
   builds are not a valid baseline (E).
2. Open a long chapter (several hundred paragraphs).
3. Fling up and down with the controls visible. Note any stutter.
4. Tap the reading area to enter immersive mode (the progress readout is not
   composed there). Fling again.
5. Quantify each mode: `adb shell dumpsys gfxinfo com.arkarium.app reset`,
   fling for ten seconds, then `adb shell dumpsys gfxinfo com.arkarium.app`.
   Record janky-frame % and the 90th/95th/99th percentile frame times.

**Expected / Actual:** expected, steady frames in both modes. Actual,
reported as not smooth. Prediction from the code (I): clearly worse with the
controls visible than in immersive mode. Steps 3–5 are what confirm or refute
that.

**Cause (suspects, ranked by confidence):**

1. **Scroll state read in composition (V for the code, I for the impact).**
   `currentProgress()` reads `scrollState.value` and `maxValue`. It is called
   directly in the readout's `Box` (1178–1179), under `if (showControls.value)`.
   `Column` and `Box` are inline, so there is no recomposition-scope boundary:
   the read is attributed to `ReaderScreen`'s own scope, and every scroll
   offset change invalidates the whole screen. Each recomposition re-runs
   `segments.forEach` (828), which emits a `Text` per paragraph with new
   `onTextLayout` and `onGloballyPositioned` lambda instances (868–873), so
   those `Text` calls most likely cannot be skipped. There is no
   `derivedStateOf` anywhere in the file (V). Guidance (E): defer
   frequently-changing reads, prefer lambda modifiers, and use `derivedStateOf`
   for state like scroll position.
2. **Per-paragraph position callbacks (V for the code, I for the impact).**
   Every non-blank paragraph registers `onGloballyPositioned` and writes
   `positionInRoot().y + scrollState.value - scrollContainerTop` into the
   `paragraphContentTop` state map (871–873). As I understand it, scrolling
   changes each child's root position, so these callbacks run for every
   paragraph on every scroll frame. Nothing reads the map during composition
   (only `LaunchedEffect` keys and `currentAnchor()`; V), so this is cost
   without recomposition, magnitude unknown. Per-item
   `onGloballyPositioned` in large lists is a known cost (E).
3. **Eager composition (V).** `Column(…verticalScroll(scrollState))` composes
   every paragraph as soon as the screen composes (E). Open-time cost and
   relayout on a font or spacing change scale with chapter length. It also
   amplifies cause 1: each whole-screen recomposition touches all N paragraphs.
4. **Build configuration (V for the config, I for the impact).** R8 is off,
   there is no baseline profile (no `baseline-prof.txt`, no
   `profileinstaller`), and Compose is the 2023.10 BOM. The official first
   check for a slow Compose app is a release build with R8 (E); Baseline
   Profiles precompile critical journeys (E), and code is interpreted right
   after install without one (E). The Compose docs also state that 1.9 and
   later match Views for scroll jank (E).

**Evidence still needed (why this stays unconfirmed):** the `gfxinfo` numbers
from the repro, in both modes; Layout Inspector's recomposition count for
`ReaderScreen` during a fling (expected to climb if cause 1 is right); and a
counter log inside the `onGloballyPositioned` lambda during a fling (expected
to fire per frame if cause 2 is right).

**Possible fix (cheapest first; re-measure after each):**

1. **Done.** Replaced the direct read with a derived, deferred one, in a new
   `ProgressReadout` composable (`ReaderScreen.kt`) so only that `Text` is in
   the invalidated scope, with `currentPage` deriving from the same
   `derivedStateOf` block. Not re-measured yet - the `gfxinfo`/Layout
   Inspector numbers under "Evidence still needed" below are still
   outstanding, so this item isn't struck from "Evidence still needed" and
   the bug stays open until they're recorded.
2. If the counter confirms cause 2: store each paragraph's `LayoutCoordinates`
   in a plain (non-state) map and call `positionInRoot()` only when saving the
   anchor, restoring, or following TTS. **Caveat:** the restore effect (533)
   and the TTS-follow effect (577) re-run on `paragraphContentTop.size`; a
   plain map breaks that trigger, so give them an explicit one (for example a
   counter that increments the first time each paragraph reports).
3. Build hygiene (backlog F12): enable R8 with keep rules, add a baseline
   profile, and move the Kotlin/Compose versions forward.
4. Only if jank remains after 1–3: a `LazyColumn` keyed per paragraph, with
   `LazyListState` replacing `paragraphContentTop` and progress from
   `firstVisibleItemIndex`. It fits the paragraph-anchored position model, but
   it is a larger change and affects text selection across paragraphs and
   TTS follow (`animateScrollToItem`). `estimatedTotalPages` is already
   content-length-based (623), so the page readout is unaffected.

**Severity:** Medium — nothing is lost and the flow works, but reading is the
app's primary activity and the jank would be felt on every chapter. Not
critical by the `critical/` criteria (the flow is not completely broken).

**Done when:** with the controls visible, janky-frame % and P99 frame time
are not meaningfully worse than in immersive mode; Layout Inspector shows
`ReaderScreen`'s recomposition count flat during a fling; and the before and
after numbers are recorded in this file before it is deleted.
