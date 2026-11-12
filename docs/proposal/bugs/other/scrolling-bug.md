# Scrolling is not smooth: the known code causes are fixed but nothing is measured, and the stutter's screen is unrecorded

> **Kind:** proposal · **Status:** open — causes 1-3 and 5 are gone in code (unmeasured), cause 4 untouched · **Verified:** 2026-11-12 @ (this patch)
> **Origin:** owner report ("scrolling isn't smooth"); first code read of `ReaderScreen.kt` on 2026-11-08, re-read against the current code on 2026-11-12 after the reader moved to a `LazyColumn`.

**Seen:** reported by the owner on a device; build type, device model **and
which screen** were not recorded (add all three). Not measured. **Tags:** V
(read directly in code), I (inferred, cost or impact not measured), E
(external guidance).
**Where:** `ui/ReaderScreen.kt` and `ui/ReaderBlocks.kt` (the reader);
`ui/NovelDetailScreen.kt` (the chapter list, cause 5); `app/build.gradle.kts`
(`isMinifyEnabled = false`, Compose BOM 2023.10.01).

**Repro:**

1. Install a **signed release build** on the weakest supported device. Debug
   builds are not a valid baseline (E).
2. Reader: open a long chapter, fling with the controls visible, then tap to
   enter immersive mode and fling again. Fiction page: fling the chapter list.
3. Quantify each: `adb shell dumpsys gfxinfo com.arkarium.app reset`, fling for
   ten seconds, then `adb shell dumpsys gfxinfo com.arkarium.app`. Record
   janky-frame % and the 90th/95th/99th percentile frame times.

**Expected / Actual:** expected, steady frames everywhere. Actual, reported as
not smooth, on a screen nobody wrote down.

**Cause (suspects, ranked by confidence):**

1. **Scroll state read in composition — fixed in code (V).** The progress
   readout was reading scroll state in `ReaderScreen`'s own scope, so every
   scroll offset change invalidated the whole screen. It is now a separate
   `ProgressReadout` composable that derives its values with `derivedStateOf`,
   so only that `Text` is invalidated. The only other `layoutInfo` reads are
   inside `currentProgress()`/`currentAnchor()`, which run on save, not per
   frame.
2. **Per-paragraph position callbacks — gone (V).** The `onGloballyPositioned`
   per paragraph and the `paragraphContentTop` state map no longer exist;
   position and progress come from the `LazyListState`.
3. **Eager composition — gone (V).** The body is a `LazyColumn` over
   `readerBlocks()`, so only blocks near the viewport are composed. Items are
   keyed (by block index, which is stable for a given chapter and settings).
4. **Build configuration (V for the config, I for the impact).** R8 is off,
   there is no baseline profile, Compose is the 2023.10 BOM. The official first
   check for a slow Compose app is a release build with R8 (E). Untouched; see
   `performance.md` F12.
5. **Fiction page chapter list — fixed in code (V), unmeasured.**
   `NovelDetailScreen` is a different screen from the reader, so none of
   1-3 apply to it either way. Its chapter filtering used to be recomputed on
   every recomposition of a very large composable and its lazy rows were
   unkeyed; both are now fixed (`performance.md` F14 — the filters are
   `remember`ed and `items`/`itemsIndexed` all supply `key =`). Scrolling
   itself never changed the state those filters read, so this was always an
   unproven fit for *fling* jank specifically — it would explain jank after
   *toggling* something (expanding the description, switching tabs, typing in
   search), not a plain fling. The fix landed anyway since it was cheap and is
   good practice regardless; only worth re-opening if the stutter turns out to
   have been on this screen and the fix didn't help.

**Evidence still needed (why this stays unconfirmed):** which screen the stutter
was on; the `gfxinfo` numbers from the repro, in both reader modes and on the
chapter list; Layout Inspector's recomposition count for `ReaderScreen` during a
fling (expected flat now) and for `NovelDetailScreen` during a fling and during
a tab switch (cause 5 predicts a jump on the switch, not the fling).

**Possible fix (cheapest first; re-measure after each):**

1. Record the screen, build type and device, then take the `gfxinfo` numbers
   above. Nothing below is worth doing before this, since the reader's code
   suspects are already fixed.
2. If it was the reader: build hygiene (backlog F12) — R8 with keep rules, a
   baseline profile, then the staged toolchain bump (D4), benchmarking each step.
3. If it was the fiction page: already done — the F14 changes (`remember`
   around the arc and search filters, `key =` on its `items(...)` calls) have
   landed. Re-measure; if jank remains, cause 5 wasn't it and this bug has no
   remaining code-level suspect on that screen.

**Severity:** Medium — nothing is lost and the flow works, but reading is the
app's primary activity. Not critical by the `critical/` criteria.

**Done when:** the screen is identified; its janky-frame % and P99 frame time
are recorded and acceptable; for the reader, with the controls visible they are
not meaningfully worse than in immersive mode; and the before and after numbers
are recorded in this file before it is deleted.
