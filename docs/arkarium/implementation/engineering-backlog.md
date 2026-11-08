# Engineering backlog: merged roadmap

> **Kind:** implementation · **Status:** rungs 1–8, 10 and 11 done, 9 done in code but needs one build to commit its schema (see below), 14 done in code at paragraph granularity (see below - needs the same build as rung 9, plus on-device verification, before it's really done), 12, 13, 15–18 queued · **Verified:** 2026-11-08 @ (this patch)
> **Origin:** a follow-up review pass over the open findings in
> [`../proposal/engineering-review.md`](../proposal/engineering-review.md) and its
> detail docs, the two open entries in [`../proposal/bugs/`](../proposal/bugs/README.md),
> [`../proposal/uiux.md`](../proposal/uiux.md), and [`../proposal/ideas.md`](../proposal/ideas.md).
> `engineering-review.md`'s own "Suggested order" treats severity and effort
> separately; this ladder merges every open item (including the UI-only and
> newest-critical ones it doesn't cover) into one list, ranked by effort first
> and by severity/urgency within similar effort.

This is **not** one feature — it's the whole backlog as a single ordered
ladder, because the ask was "what's easiest, then what's most urgent
regardless of that." Each rung still links to the detail doc that owns the
actual design discussion; this file only tracks order and status. When a
rung's real work is big enough to need its own staged ladder (F3, F4, the
Kotlin/Compose/Navigation bump), promote it to its own file the normal way
and point this row at that instead.

| Rung | Item | Effort | Status | Detail |
|---|---|---|---|---|
| 1 | Scan reconcile cascade-deletes a novel's history on an ordinary scan (steps 1–2: mark seen before I/O that can fail; don't reconcile-as-stale on an empty listing while the DB isn't) | Tiny | **done** | [`../proposal/bugs/critical/scan-reconcile-treats-errors-as-absence.md`](../proposal/bugs/critical/scan-reconcile-treats-errors-as-absence.md) |
| 2 | F23 — Room downgrade throws uncaught, only "recovery" is clearing app data | Tiny | **done** | [`../proposal/data-safety.md`](../proposal/data-safety.md) |
| 3 | F8 — two separate `AppDatabase` instances (`MainActivity` + the worker) | Tiny | **done** | [`../proposal/robustness.md`](../proposal/robustness.md) |
| 4 | Arc cover enlarger is undiscoverable (one-time ripple + tip) | Tiny | **done** | [`../proposal/uiux.md`](../proposal/uiux.md) |
| 5 | F18 — WebView follows any link (`shouldOverrideUrlLoading`; JS/DOM storage intentionally stay on) | Small | **done** | [`../proposal/robustness.md`](../proposal/robustness.md) |
| 6 | F11 — TTS chunking can cut mid-emoji, ignores CJK punctuation (`BreakIterator`) | Small | **done** | [`../proposal/robustness.md`](../proposal/robustness.md) |
| 7 | F7 remainder — unreadable chapter has no retry, just an error string (sealed result type + retry button) | Small | **done** | [`../proposal/robustness.md`](../proposal/robustness.md) |
| 8 | F20 — accessibility audit of `contentDescription = null` across ~13 files | Small | **done** | [`../proposal/robustness.md`](../proposal/robustness.md) |
| 9 | F1/F2 remainder — commit Room schema, add migration tests, move KAPT → KSP | Medium | **done in code - schema JSON still to generate and commit** (see below) | [`../proposal/data-safety.md`](../proposal/data-safety.md) |
| 10 | F22 — TTS audio focus, `MediaSession`, `ACTION_AUDIO_BECOMING_NOISY` | Medium | **done** | [`../proposal/reader-playback.md`](../proposal/reader-playback.md) |
| 11 | "Notify me" toggle bug, remaining steps - confirmed on-device, which surfaced a real sync-detection bug in place of the original remaining steps (see below) | Medium | **done** | [`../proposal/bugs/other/notify-toggle-confirmation.md`](../proposal/bugs/other/notify-toggle-confirmation.md) |
| 12 | F3 — novel identity tied to SAF URI (D3 decided: opaque id, re-link-with-confirmation) | Large | queued — own ladder when started | [`../proposal/data-safety.md`](../proposal/data-safety.md) |
| 13 | F4 — manifest signing (D5 decided: key custody; ECDSA vs Ed25519 still open) | Large | queued — own ladder when started | [`../proposal/manifest-signing.md`](../proposal/manifest-signing.md) |
| 14 | F9 — reading position drifts (scroll fraction → offset-based anchor; bundled with TTS controls/highlight) | Large | **done in code, at paragraph granularity - see below** | [`../proposal/reader-playback.md`](../proposal/reader-playback.md) |
| 15 | F5 — SAF scan does ~3 provider queries per file (per-directory cache) | Large | blocked — needs rung 12 (F3) first for a stable id | [`../proposal/performance.md`](../proposal/performance.md) |
| 16 | F12/F14/F17 — Kotlin 2.x / Compose BOM / Navigation upgrade (D4 decided: staged) | Large | queued — own ladder when started | [`../proposal/performance.md`](../proposal/performance.md) |
| 17 | F13 — splash cold-start cost (D6 decided: policy) | Large | queued | [`../proposal/performance.md`](../proposal/performance.md) |
| 18 | F15, F16, F19 — image crossfade, general animation, reader typography | Large | queued | [`../proposal/performance.md`](../proposal/performance.md), [`../proposal/animation-and-polish.md`](../proposal/animation-and-polish.md) |

**Not yet started at all, no decision made, not on this ladder:** EPUB/PDF
ingestion, the ARKlight publisher pipeline, background sync beyond the
notify-toggle, auth/paid gating, and backend-social features — all in
[`../proposal/ideas.md`](../proposal/ideas.md), all genuinely big, and none
blocking anything above.

## Rung 1 — what changed

`ScannerImpl.scanRoot()` now marks a novel folder's id "seen" the moment the
folder is found — before `buildNovelEntity`, `mergeNovelForRescan`, the
Room `upsert`, or chapter scanning run — via a new `onNovelFolderSeen`
callback, so a failure anywhere in that downstream pipeline no longer looks
identical to "this novel doesn't exist" at reconcile time. `scanRoot()` also
now reports whether it saw *any* novel folder in the (post-`authors/`-filter)
listing; `LibraryViewModel.startScan()` only runs the stale-reconcile step
when either a folder was seen or the DB was already empty, so a listing that
comes back empty while the DB is not just surfaces a status message instead
of cascade-deleting every local novel. Step 3 (soft-delete instead of
cascade) is **not** included in this rung — it's bundled with F3's identity
work (rung 12) per the detail doc's own note — so a confirmed-gone local
novel is still deleted outright for now; only the two false-positive paths
above are fixed.

## Rung 2 — what changed

`AppDatabase.create()` now catches the `IllegalStateException` Room throws
for any unhandled version and inspects its message for the "migration from
X to Y" shape Room's own exception text uses. When X > Y (a downgrade) it
rethrows as a new `DatabaseDowngradeException` carrying a user-facing
message instead of the raw Room text; any other unhandled-version shape
(including a genuine missing upgrade migration) passes through unchanged.
`MainActivity.onCreate()` catches `DatabaseDowngradeException` ahead of the
existing catch-all and renders it as a plain message screen ("This library
was last opened by a newer version of ARKarium...") instead of the
class-name-plus-stack-trace crash screen every other startup failure still
gets.

## Rungs 3–8 — what changed

- **Rung 3 (F8):** `AppDatabase.create()` hands out one per-process instance instead of
  `MainActivity` and `NewChapterCheckWorker` each building their own, as
  [`robustness.md`](../proposal/robustness.md) F8 describes.
- **Rung 4:** the first time someone opens a fiction that has arcs, the "All Chapters" cover
  ripples and a "Double-tap a cover to see it full-size" tip appears under the strip. Tapping the
  tip or double-tapping a cover sets `has_seen_cover_enlarge_hint` in `PreferencesManager`, and it
  never shows again. This replaced the permanent magnifier badge. While DataStore hasn't answered
  yet the flag is `null` and nothing is shown, so a returning user never gets a one-frame flash.
- **Rung 5 (F18):** `WebViewScreen` overrides `shouldOverrideUrlLoading`. Links to the same host
  as the page it was opened on (ignoring `www.`) stay in the WebView; every other `http(s)` or
  `mailto:` link goes to the system via `ACTION_VIEW`, and any other scheme is dropped. JS and DOM
  storage stay on because the author's site needs them.
- **Rung 6 (F11):** `splitForTts` now packs whole sentences from `java.text.BreakIterator` into
  each chunk, so CJK `。！？` are break points and a decimal point isn't. Only a single sentence
  longer than the limit is hard-cut, at the last whitespace or CJK comma if there is one and never
  between the halves of a surrogate pair. It's `internal` now and covered by `SplitForTtsTest`.
- **Rung 7 (F7 remainder):** `ChapterContentRepository.getTextContent` returns a sealed
  `ChapterLoadResult` (`Ok` / `Failed`) instead of throwing `ChapterReadException`, which is gone.
  On `Failed` the reader shows `ChapterLoadErrorScreen` with **Try again** (re-runs the load in
  place) and **Back**, rather than passing an error string to `ReaderScreen` as the chapter's text.
- **Rung 8 (F20):** all 32 `contentDescription = null` sites were checked. 29 are decorative
  (an icon beside its own text, or a cover/avatar beside the title) and stay `null`. Fixed: the
  enlarged-cover dialog's image is now named, the reader's "back to fiction" row has an
  `onClickLabel`, the notify row's switch is merged with its label even when disabled, and arc
  covers get a TalkBack custom action "View cover full-size" because double-tap is TalkBack's
  activate gesture and can't trigger the app's own double-tap.

## Rung 11 — what changed

The on-device confirmation this rung was waiting on came back: the notify-toggle
undiscoverability fix (already shipped before this rung) is confirmed working, the
API 33+ permission/notification path is confirmed working end to end, and the owner's
original report turned out to be about a fiction that reached the library's disk by a
route other than this app's own sync flow - exactly the "second, different bug in the
sync-detection path itself" the detail doc's step 1 flagged as a possibility. That bug,
not the originally-queued "remaining steps," is what this rung actually fixes - see
[`../proposal/bugs/other/notify-toggle-confirmation.md`](../proposal/bugs/other/notify-toggle-confirmation.md)
for the full writeup.

`ScannerImpl.buildNovelEntity` now runs the same `FictionLut` lookup "Add fiction" uses
against each scanned novel's resolved title; an exact normalized match (Sync invariants
S7) sets the freshly-built `NovelEntity.syncSourceUrl` to `relayBaseUrlForSlug(slug)`,
recognizing it as one of Rae ARK's own fictions even though this particular on-disk copy
was never downloaded by this app. `LibraryScan.mergeNovelForRescan`'s merge rule for
`syncSourceUrl` changed from always-carry-the-existing-row's-value to
`existing.syncSourceUrl ?: scanned.syncSourceUrl` - still never lets a scan override an
already-set source (so a different source, or a novel the user deliberately unlinked via
`SyncResolutionDialog`, is untouched), but now lets a freshly recognized value fill in a
row that currently has none. A novel already sitting in the library as "local" before
this fix picks up `syncSourceUrl` - and with it the "Notify me" toggle and "Check for
updates" - on its very next rescan; nothing needs re-adding. `LibraryScanTest` covers
both directions (fills in when existing has nothing; never overrides when existing
already has a source). One narrow known gap from this - a `SOURCE_GONE` unlink that's
re-recognized if the bundled `fiction_lut.dat` hasn't caught up with the relay losing
that fiction yet - is flagged in `NovelDao.unlinkSyncSource`'s comment rather than fixed
with a new column, since it should be rare and self-corrects on the LUT's own next
regeneration.

## Rung 9 — what changed

Room now builds with **KSP instead of KAPT** (`com.google.devtools.ksp` 1.9.10-1.0.13, matching
the Kotlin plugin), and exports its schema to `app/schemas` (`room.schemaLocation`). The
migration list is one array, `AppDatabase.ALL_MIGRATIONS`, which `create()` and both tests
use, and the version is a constant (`APP_DATABASE_VERSION`) that `@Database` reads.

Two tests: `MigrationChainTest` (JVM, runs in CI) checks the list is an unbroken
1 → 2 → … → current chain with no gaps, overlaps or duplicates. `MigrationTest`
(`src/androidTest`, needs a device/emulator: `./gradlew connectedDebugAndroidTest`) replays
real upgrades from every committed schema file and lets Room validate the result.

**Not done, and can't be from this sandbox:** the schema JSON itself. It is generated by a
real build, which needs Maven/Google, both blocked here. One `./gradlew assembleDebug`
creates `app/schemas/com.arkarium.app.data.AppDatabase/13.json`; commit it. Until then
`MigrationTest.currentVersionSchemaIsCommitted` fails on purpose. Versions 1-12 never had an
exported schema, so the instrumented test can only replay from 13 onward; migrations 1-12
are covered by the chain test only. CI does not run `androidTest`.

## Rung 10 — what changed

`TtsPlaybackService` now takes **audio focus** (`CONTENT_TYPE_SPEECH`) before it starts,
pauses on loss, and does not start at all while another app holds focus (a call), reporting
that instead. A temporary loss (a call, a prompt) pauses and **resumes on regain**; a
permanent loss (music started) pauses and waits for the reader to press play. A deliberate
pause is never undone by a later regain. Transient-can-duck pauses rather than ducks, since
ducked narration under other audio is just two overlapping voices.
**`ACTION_AUDIO_BECOMING_NOISY`** (headphones unplugged, Bluetooth dropped) pauses; the
receiver is registered only while speaking. A framework **`MediaSession`** is active for the
session, so lock-screen controls and headset buttons drive play/pause/next/previous/stop
through the same methods the in-app controls use. The decision table is in
`TtsAudioFocusPolicy.kt` with `TtsAudioFocusPolicyTest`. The service is untested on a device.

Left for later: transport buttons in the notification itself need `MediaStyle`, which lives
in `androidx.media`, a dependency this project doesn't have. Rung 4 of
`tts-playback-controls.md` is where that belongs.

## Rung 14 — what changed

`ReadingProgressEntity` gains a `position_snippet` column (`MIGRATION_13_14`,
`APP_DATABASE_VERSION` 13 → 14) and a new `positionType` value, `CHAR_OFFSET`
(`ReaderPosition.kt`), alongside the existing `PERCENTAGE`. `ReaderScreen` now saves
and restores by that offset instead of a scroll-pixel fraction: `ChapterMarkup.kt`'s
new `paragraphOffsets()` pairs every `Paragraph` segment with where its text starts
in the chapter's raw body (`ChapterMarkupOffsetsTest` checks this stays in lockstep
with `parseChapterSegments()` itself, including the unterminated-tag and all-markup
edge cases), and each paragraph's own `Text` composable reports where it actually
lands on screen via `onGloballyPositioned`. Leaving a chapter saves the paragraph at
the top of the viewport by its raw-text offset (`currentAnchor()`); opening one with
a saved `CHAR_OFFSET` row for that exact chapter scrolls back to that paragraph
(`paragraphForCharOffset`) once its position is known, rather than always starting at
the top the way every chapter open did before this rung (restoring into a chapter at
all was never wired up - "Continue Reading" only used the saved row to pick *which*
chapter to open).

**Narrower than the proposal doc's direction, honestly:** F9 describes a character
offset restored via `TextLayoutResult.getLineForOffset`/`getLineTop` - line-level,
within whichever paragraph the reader was in. Each paragraph is its own `Text`
composable here, not one `TextLayoutResult` spanning the chapter body, so that needs
per-paragraph line math this rung doesn't add; what's here anchors to the *start of
a paragraph* instead. That's still the actual fix for what F9 flags (a position tied
to content instead of to a pixel fraction that only means the same thing at one
font size/width), just coarser than a mid-paragraph restore would be - see
`ReaderPosition.kt`'s doc comment for the detail. The snippet fallback the proposal
doc calls for is stored (`positionSnippet`) but not yet checked against the live
chapter body before restoring - captured for the row to carry, not yet read back.

**Not done, and can't be from this sandbox** (same limitation as rung 9): the
`14.json` schema export and `MigrationTest`'s on-device replay. Nothing here has run
on a device or even built with a real Gradle/Android toolchain - this sandbox has no
network path to `google()`/`mavenCentral()`, only to a handful of other package
registries, so `./gradlew` itself cannot run. The paragraph-to-pixel math
(`onGloballyPositioned` + `scrollContainerTop`) is exactly the kind of thing that
looks right on paper and needs a real screen to confirm - nothing here substitutes
for that.

## Reader settings — one place, not two

The "Reader Preferences" pill in the reader used to open a panel duplicating font size,
spacing and mode from Settings > Reader (plus read-aloud speed, which is also in TTS
settings, and a session-only brightness slider). That panel is gone; the pill now opens the
Reader settings page, which is the only place any of it is changed. Brightness moved with
it: the existing switch now means "set brightness while reading", with a stored level
slider (`reader_brightness`, -1 = follow system) shown under it. Font family is a row that
opens a card of eight options, each previewed in its own face - all Android system
families (Serif, Sans, Sans light, Sans medium, Condensed, Monospace, Casual) plus the
existing Dyslexia-friendly, so no font files are bundled. Stored names are unchanged.

**Full-screen and the notch:** while full-screen + immersive + **landscape**, the window now
draws into the display cutout (`SHORT_EDGES`) and stops fitting itself inside the system
insets, so the background covers the whole screen; the page is re-padded by the cutout's own
horizontal insets only, so text never sits under the notch. Portrait and reverse portrait are
left alone, as the cutout is at the top there and the status bar is already hidden.
Everything is restored on every exit path. Needs checking on a notched device, in both
landscape directions.
