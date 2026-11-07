# Reader position and TTS playback lifecycle

> **Kind:** proposal · **Status:** design needed, nothing built yet · **Verified:** 2026-11-07 @ 5426a3d
> **Origin:** [`engineering-review.md`](engineering-review.md) F9, F10. Related: the rotation bug in [`bugs.md`](bugs.md), and the playback-controls gap in [`uiux.md`](uiux.md).

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

### F10. TTS playback is tied to a composable's lifecycle

**Severity:** High. **Tags:** V.
**Where:** `ui/Tts.kt`, `rememberChapterTts()` (`DisposableEffect(Unit) { ...
onDispose { shutdown() } }`); `ui/ReaderScreen.kt` (`keepScreenOnEnabled`
workaround).

**What the code does:** the `TextToSpeech` engine and the chunk queue
(`pendingChunks`/`pendingIndex`) live inside `rememberChapterTts()`, scoped by
`remember` to the composable that calls it. `DisposableEffect`'s `onDispose`
calls `shutdown()`.

**Why it matters:** `remember` doesn't survive the composable leaving
composition. Leaving `ReaderScreen` (back, or navigating to the next/previous
chapter, which per `MainActivity`'s own comment replaces the backStackEntry
rather than recomposing in place) or rotating the device — see the rotation
bug in `bugs.md`, which separately causes `MainActivity` to be recreated —
both tear down and rebuild the composable, so an active read-aloud session
stops. "Keep screen on" (`keepScreenOnEnabled`) is a workaround for the
screen-off case specifically, not a fix for the underlying lifecycle
mismatch, and does nothing for the rotation or navigation cases.

**Direction:** move playback — the `TextToSpeech` engine and the chunk queue —
into a `MediaSessionService`: a separate Android service, independent of any
composable's lifecycle, with a `mediaPlayback` foreground service type (and,
at `targetSdk` 34, the `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission). The UI
binds to it via a controller instead of owning the engine directly. This is
also the structural fix the controls gap in [`uiux.md`](uiux.md) needs
(pause/resume, skip, a notification surface) and the natural home for the
multi-voice segment list in [`tts-multivoice.md`](tts-multivoice.md) — build
this once, not per-feature.

**Sequencing:** F9 (anchor-based position) and F10 (service-based playback)
were flagged in `engineering-review.md`'s suggested order as one coordinated
change, together with the controls/highlight work in `uiux.md` — the chunk
index ↔ text-offset mapping F10's queue needs is the same mapping F9's anchor
needs to restore a position precisely, so building them separately risks two
slightly different position representations.
