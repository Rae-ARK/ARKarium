# Reader position and TTS playback lifecycle

> **Kind:** proposal · **Status:** F10 and F22 fixed in code; F9 done in code at paragraph granularity, narrower than this doc's own direction (backlog rung 14) — on-device verification still open for all three · **Verified:** 2026-11-08 @ (this patch)
> **Origin:** [`engineering-review.md`](engineering-review.md) F9, F10, F22. Related: the playback-controls gap in [`uiux.md`](uiux.md).

### F9. Reading position is stored as a scroll fraction, not an anchor

**Severity:** Medium. **Tags:** V (storage and computation read in code), I (drift across font/width not measured on a device).
**Where:** `ui/ReaderScreen.kt`, `currentProgress()`; `data/Entities.kt`,
`ReadingProgressEntity.position`/`positionType`.

**What the code does:** `currentProgress()` returns
`scrollState.value.toFloat() / maxValue`, saved as a `Float` with
`positionType = "PERCENTAGE"` (one of five values the column already supports
— `CHAR_OFFSET`, `HTML_LOCATION`, `EPUB_CFI`, `PAGE_INDEX`, `PERCENTAGE` — see
`Entities.kt`'s doc comment — so the schema is already prepared for something
better than the one value currently written).

**Why it matters:** a pixel-scroll fraction is a function of the exact layout
the text happened to render at. Font size, line spacing, screen width, and
non-uniform line heights (a centered scene break, a callout box) all change
where a given fraction actually lands. The planned backend position-sync
proposal ([`backend-social.md`](backend-social.md)) means this fraction would
need to resolve to a sensible place on a *different* device's layout, which
compounds the drift.

**Fixed in code, narrower than this direction** (backlog rung 14): each
paragraph is anchored by its raw-text offset and restored by scrolling to the
paragraph whose offset matches a saved `CHAR_OFFSET` row, not by a
line-level `TextLayoutResult.getLineForOffset`/`getLineTop` restore within a
single `Text` composable spanning the chapter — see the backlog rung for why
(each paragraph is its own composable here) and `ReaderPosition.kt`'s doc
comment for the detail. The snippet fallback below is stored
(`positionSnippet`) but not yet checked against the live chapter body before
restoring. Original direction, kept for the record of what a finer,
mid-paragraph restore would still need:

**Direction:** store a character offset into the chapter's text plus a short
surrounding-text snippet (before/after a few words), the same shape Readium's
locator model uses. Restore it with `TextLayoutResult.getLineForOffset` and
`getLineTop` rather than re-deriving a scroll pixel from a stored fraction.
The snippet is a fallback for the rare case the raw offset no longer lines up
(chapter content edited since the position was saved). The same offset is
the natural anchor for the TTS highlight in F10/`uiux.md` below — one
position representation serving both features is simpler than inventing a
second one for playback.

**Two gaps left by rung 14, both still open:**

- **The snippet fallback isn't read back yet.** `positionSnippet` is stored
  on every save but never checked against the live chapter text before
  restoring, so an offset that no longer lines up (chapter content edited
  since the position was saved) isn't caught — it just restores to whatever
  paragraph the stale offset happens to land in now, possibly the wrong one.
  A verification step (`raw.regionMatches(offset, snippet, ...)`, falling
  back to `raw.indexOf(snippet)`, and otherwise staying at the top rather
  than guessing) would close this.
- **Upgrading from a pre-rung-14 build loses the in-progress position.** A
  row still carrying the old `PERCENTAGE` type is no longer restored into at
  all — see
  `bugs/other/legacy-percentage-position-lost-on-upgrade.md` (bug file since deleted; see bugs/other/continue-reading-position-lost.md)
  for the gap and a possible one-time-conversion fix.

### F10. TTS playback is tied to a composable's lifecycle — fixed

**Severity:** High. **Tags:** V.
**Where:** `ui/Tts.kt`, `rememberChapterTts()`; new
`playback/TtsPlaybackService.kt`; `ui/ReaderScreen.kt` (`keepScreenOnEnabled`
workaround, still present — see "Still open" below).

**Was:** the `TextToSpeech` engine and the chunk queue
(`pendingChunks`/`pendingIndex`) lived inside `rememberChapterTts()`, scoped
by `remember` to the composable that called it, with `DisposableEffect`'s
`onDispose` calling `shutdown()`. `remember` doesn't survive the composable
leaving composition, so leaving `ReaderScreen` (back, or navigating to the
next/previous chapter, which per `MainActivity`'s own comment replaces the
backStackEntry rather than recomposing in place) or rotating the device —
which separately causes `MainActivity` to be recreated — both tore down and
rebuilt the composable, stopping an active read-aloud session. This was also
the cause of the now-fixed `bugs/` entry "Rotating the device stops an
active read-aloud session" (its file deleted; this doc is the record).

**Fixed (commit 823c914):** playback — the `TextToSpeech` engine and the
chunk queue, moved unchanged — now lives in `TtsPlaybackService`, a separate
Android service built with `applicationContext`, independent of any
composable's or Activity's lifecycle, with a `mediaPlayback` foreground
service type and (at `targetSdk` 34) the `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
permission declared in the manifest. `ChapterTtsState` (`ui/Tts.kt`) is now a
thin controller that binds to the service and mirrors its state rather than
owning the engine; `rememberChapterTts()`'s `DisposableEffect` binds/unbinds
instead of constructing/shutting down a `TextToSpeech`. The service is
started (`ContextCompat.startForegroundService`), not merely bound, for the
duration of a session — see the service's own doc comment for why a
bound-only service would still drop playback in the brief zero-client window
a rotation's unbind-then-rebind causes.

**Still open — the structural piece, not the lifecycle bug:** this fix is
scoped to keeping an existing read-aloud session alive. `TtsPlaybackService`
now has pause/resume/skip and a controls bar in the reader itself (see
[`../implementation/tts-playback-controls.md`](../implementation/tts-playback-controls.md),
rungs 1-2), and the foreground notification now has transport actions too
(that ladder's rung 4, in code, not yet verified on a device). The
multi-voice segment list in [`tts-multivoice.md`](tts-multivoice.md) can also
now build on `TtsPlaybackService` rather than needing its own service, but
isn't built yet. Also not yet verified on a device (see this doc's commit message):
whether the service's process genuinely survives a config change on real
hardware, and whether the `mediaPlayback` notification behaves consistently
across OEM skins.

**Sequencing:** F9 (anchor-based position, now done — see above) was
unaffected by this fix. `engineering-review.md`'s suggested order treated F9
and the controls/highlight work in `uiux.md` as one coordinated change with
F10, on the theory that the chunk index ↔ text-offset mapping the service's
queue uses is the same mapping F9's anchor needs to restore a position
precisely — worth rechecking once the controls/highlight work (rungs 5–6 of
[`../implementation/tts-playback-controls.md`](../implementation/tts-playback-controls.md))
is built, since F9 landed with the paragraph-level anchor above rather than
that mapping. Rung 5 is now in code and does provide half of it - `splitForTtsSpans`
gives each chunk's character range in the chapter body (`TtsPlaybackService.chunkSpan`) -
so wiring F9's anchor to a chunk index is no longer blocked on that mapping existing.

### F22. `TtsPlaybackService` requests no audio focus and has no `MediaSession`

**Severity:** Medium — correctness, not polish. **Tags:** V.
**Where:** `playback/TtsPlaybackService.kt` (confirmed by searching for
`AudioFocus`, `AUDIO_BECOMING_NOISY`, and `MediaSession` across the file and
finding none).

Now that playback survives backgrounding (F10, above), the gaps F10's fix
didn't touch start to matter in practice, where before a rotation would have
killed the session before anyone noticed them:

- **No audio focus request.** `TtsPlaybackService` never calls
  `AudioManager.requestAudioFocus` (or the `AudioFocusRequest` builder).
  Starting a read-aloud session while music or another app's audio is
  already playing overlaps both rather than ducking or pausing either one,
  and nothing here pauses TTS when a phone call or another app *requests*
  focus away from it either.
- **No `ACTION_AUDIO_BECOMING_NOISY` handling.** Unplugging wired headphones
  or disconnecting Bluetooth audio mid-chapter doesn't pause playback — it
  switches to the speaker and keeps reading aloud, which is the one case
  Android explicitly ships a broadcast for specifically so apps don't do
  this.
- **No `MediaSession`.** Without one, lock-screen media controls, Bluetooth
  headset buttons (including the `AudioManager` route change above), and
  Android Auto/Wear integration have nothing to attach to — this is also a
  prerequisite for the richer transport-action notification
  `reader-playback.md`'s F10 section above already flags as rung 4 of
  [`../implementation/tts-playback-controls.md`](../implementation/tts-playback-controls.md).

**Fixed in code** (backlog rung 10): audio focus, becoming-noisy pause and a framework
`MediaSession` are in `TtsPlaybackService`, and the notification transport buttons
(`MediaStyle`) landed with rung 4 of the playback-controls ladder. Original direction, kept for the record:

**Direction:** build this on `TtsPlaybackService` directly rather than as a
separate piece — a `MediaSessionCompat` plus `AudioFocusRequest` with
`setOnAudioFocusChangeListener` (pause on `AUDIOFOCUS_LOSS`/
`AUDIOFOCUS_LOSS_TRANSIENT`, lower TTS volume or pause on
`_TRANSIENT_CAN_DUCK` depending on preference) and a registered
`BroadcastReceiver` for `ACTION_AUDIO_BECOMING_NOISY` that pauses the same
way. All three share the service's existing pause/resume entry points from
the F10 fix, so this is additive to that work, not a rework of it.
