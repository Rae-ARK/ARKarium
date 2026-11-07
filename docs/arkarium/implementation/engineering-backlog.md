# Engineering backlog: merged roadmap

> **Kind:** implementation · **Status:** rungs 1–8 done, rest queued · **Verified:** 2026-11-07 @ 9cc65b9
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
| 9 | F1/F2 remainder — commit Room schema, add migration tests, move KAPT → KSP | Medium | **next** | [`../proposal/data-safety.md`](../proposal/data-safety.md) |
| 10 | F22 — TTS audio focus, `MediaSession`, `ACTION_AUDIO_BECOMING_NOISY` | Medium | queued | [`../proposal/reader-playback.md`](../proposal/reader-playback.md) |
| 11 | "Notify me" toggle bug, remaining steps (needs a real device, API 33+ permission path) | Medium | queued — not a coding task until confirmed on-device | [`../proposal/bugs/other/notify-toggle-confirmation.md`](../proposal/bugs/other/notify-toggle-confirmation.md) |
| 12 | F3 — novel identity tied to SAF URI (D3 decided: opaque id, re-link-with-confirmation) | Large | queued — own ladder when started | [`../proposal/data-safety.md`](../proposal/data-safety.md) |
| 13 | F4 — manifest signing (D5 decided: key custody; ECDSA vs Ed25519 still open) | Large | queued — own ladder when started | [`../proposal/manifest-signing.md`](../proposal/manifest-signing.md) |
| 14 | F9 — reading position drifts (scroll fraction → offset-based anchor; bundled with TTS controls/highlight) | Large | queued | [`../proposal/reader-playback.md`](../proposal/reader-playback.md) |
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
