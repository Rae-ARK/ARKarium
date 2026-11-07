# UI/UX issues

> **Kind:** proposal · **Status:** open issues only · **Verified:** 2026-11-07 @ b4fcc2f

Open usability, visual, or feature-gap issues that are not crashes or wrong
behavior (those go in [`bugs.md`](bugs.md)). Delete an entry when fixed.

Format: `### <short title>`, then **Where** · **Problem** · **Idea**.

### Read-aloud has no playback controls and no "currently reading" highlight

**Priority:** high; readers expect both wherever audio plays.
**Where:** `ui/ReaderScreen.kt` (one Play/Stop button in the top bar), `ui/Tts.kt` (`ChapterTtsState`).
**Problem:** today the only control is a single Play/Stop toggle, and the text gives no sign of where speech is. Missing:
- Pause/resume (Stop discards position), skip back/forward (sentence or paragraph), a scrub or progress indicator, and a controls surface that stays reachable while listening (and with the screen off, via a media notification).
- Highlight of the text being spoken, with the view following it.

**State of the code (what makes this easy or hard):**
- Speech is queued in chunks (`splitForTts`, `pendingChunks`/`pendingIndex`), one utterance per chunk, so chunk-level pause, skip and highlight need only a position that maps chunk index → text range. That is small.
- Pause is not a `TextToSpeech` feature; model it as "stop, remember `pendingIndex`, resume from that chunk".
- Word-level highlight needs `UtteranceProgressListener.onRangeStart`, which is API 26+; `minSdk` is 24, so provide a chunk-level fallback.
- Rotation currently stops the session (see the rotation bug in [`bugs.md`](bugs.md)); fix that first or controls will feel broken.

**Idea:** build chunk-level first (controls + highlight + auto-scroll), word-level as an enhancement. Keep the model compatible with the segment list in [`tts-multivoice.md`](tts-multivoice.md), where a "segment" is the same unit as a chunk. Tap-to-start-from-here is a natural follow-up.

### Arc covers are too small to see

**Where:** arc cards on `ui/NovelDetailScreen.kt` (cover uses arc → fiction fallback); arc cover also appears as the reader's cover.
**Problem:** the covers render as small thumbnails, so cover art cannot be appreciated or read.
**Idea:** double-tap a cover to open it enlarged (full-screen viewer, pinch-to-zoom, tap or back to dismiss). Applies to fiction covers too. Decide: double-tap as asked (note it is undiscoverable; consider also a single-tap or a magnifier hint), and how a failed or missing image behaves (the generated placeholder cover should not open a viewer). Note an existing `clickable` on the card means a double-tap gesture must not break single-tap navigation: use `combinedClickable` with `onDoubleClick`, and expect a short single-tap delay while Compose waits to rule out a double-tap.
