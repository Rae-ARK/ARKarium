# Reader position and TTS playback lifecycle

> **Kind:** proposal · **Status:** F10 fixed in code, F9 still open · **Verified:** 2026-11-07 @ 823c914
> **Origin:** [`engineering-review.md`](engineering-review.md) F9, F10. Related: the playback-controls gap in [`uiux.md`](uiux.md).

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

**Direction:** store a character offset into the chapter's text plus a short
surrounding-text snippet (before/after a few words), the same shape Readium's
locator model uses. Restore it with `TextLayoutResult.getLineForOffset` and
`getLineTop` rather than re-deriving a scroll pixel from a stored fraction.
The snippet is a fallback for the rare case the raw offset no longer lines up
(chapter content edited since the position was saved). The same offset is
the natural anchor for the TTS highlight in F10/`uiux.md` below — one
position representation serving both features is simpler than inventing a
second one for playback.

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
the cause of the now-fixed `bugs.md` entry "Rotating the device stops an
active read-aloud session" (deleted from that file; this doc is the record).

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
scoped to keeping an existing read-aloud session alive; it does not add
pause/resume, skip, or a notification with real transport controls (it shows
a plain "Reading aloud: <chapter>" notification with no actions). That
controls surface — [`uiux.md`](uiux.md)'s gap — and the multi-voice segment
list in [`tts-multivoice.md`](tts-multivoice.md) can now build on
`TtsPlaybackService` rather than needing their own service, but neither is
built yet. Also not yet verified on a device (see this doc's commit message):
whether the service's process genuinely survives a config change on real
hardware, and whether the `mediaPlayback` notification behaves consistently
across OEM skins.

**Sequencing:** F9 (anchor-based position) is unaffected by this fix and
remains open. `engineering-review.md`'s suggested order still treats F9 and
the controls/highlight work in `uiux.md` as one coordinated change with
F10 — the chunk index ↔ text-offset mapping the service's queue uses is the
same mapping F9's anchor needs to restore a position precisely, so building
F9 separately risks two slightly different position representations.
