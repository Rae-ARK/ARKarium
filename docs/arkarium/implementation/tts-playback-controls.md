# TTS playback controls (pause/resume, skip, controls bar)

> **Kind:** implementation (ladder) · **Status:** rungs 1-2a done, rung 3 current · **Verified:** 2026-11-07 @ a621542
> **Truth lives in:** `playback/TtsPlaybackService.kt`, `ui/Tts.kt`, `ui/ReaderScreen.kt`, `ui/ReaderTtsLogic.kt`, `ui/WaterRipple.kt`

Promoted from [`../proposal/uiux.md`](../proposal/uiux.md)'s "Read-aloud has no
playback controls and no 'currently reading' highlight" entry, now deleted from
that file - see this doc's history (or `git log -p` on that line) for the
original writeup. Builds on `TtsPlaybackService`
([`reader-playback.md`](../proposal/reader-playback.md) F10), which already keeps a
read-aloud session alive across rotation and chapter navigation - this ladder
is the "controls surface" that doc's "Still open" section flagged as not built
yet.

## Ladder

| Rung | State | What |
|---|---|---|
| 0 | done | Design doc (this one). |
| 1 | done | `TtsPlaybackService`: `pause()`/`resume()`/`skipNext()`/`skipPrevious()`, chunk granularity. Pause is modeled as "stop, remember `pendingIndex`, resume from that chunk" (not a real `TextToSpeech` feature); skip jumps `pendingIndex` directly and only re-queues speech if something was actually playing. `ChapterTtsState` (`ui/Tts.kt`) mirrors a new `isPaused` and passes all four calls through. |
| 2 | done | Reader UI (`ReaderScreen.kt`). The top-bar speaker glyph (`Icons.Filled.VolumeUp`, shown outside immersive mode only) starts read-aloud (a paused session resumes, a playing one is left alone) and enters immersive mode. The bottom edge is one slot that swaps: outside immersive mode the "Reader Preferences" pill, in immersive mode - while a session exists, playing or paused - `TtsControlsBar` (stop, settings, skip back, play/pause, skip forward); `readerBottomPill` (`ReaderTtsLogic.kt`) decides which. The swap plays a water ripple (`WaterRipple.kt`: a circular reveal plus expanding rings). Close (X) stops the session, which removes the pill; the gear opens `settings/tts` via `onOpenTtsSettings`. |
| 2a | done | The session lives in `TtsPlaybackService`, so it outlives the reader screen. `showControls` (the immersive flag) is `rememberSaveable`, and once the service re-binds, `reconcileTtsSession` handles a session already running: reading this chapter, it returns the reader to immersive mode so the pill is back (covers returning from `settings/tts`, rotation, reopening the chapter); reading another chapter, it is stopped as stale (Previous/Next). `TtsPlaybackService.sessionKey` - the chapter id passed to `ChapterTtsState.speak()` - is how the two are told apart. |
| **3** | **current** | Chunk-level progress indicator inside `TtsControlsBar`. `TtsProgressStub()` is the deliberate placeholder left for this - see "Rung 3" below. |
| 4 | queued | Media-notification transport actions (pause/resume/skip on the existing foreground notification via `MediaSessionCompat`), reusing the same `TtsPlaybackService` calls rung 1 added. |
| 5 | queued | Chunk-level "currently reading" highlight + auto-scroll in `ReaderScreen`, using the chunk index → text range mapping `splitForTts` already gives for free. |
| 6 | queued | Word-level highlight enhancement via `UtteranceProgressListener.onRangeStart` (API 26+; `minSdk` is 24, so rung 5's chunk-level version stays as the fallback branch, not replaced). |

## Rung 3: remaining work

`TtsProgressStub()` in `ReaderScreen.kt` is currently a no-op composable - it
renders nothing, deliberately, as a marked call site for this rung rather than
something to rediscover later. A chunk isn't a stable unit to show a position
in (chunk count depends on `TTS_MAX_CHUNK_CHARS`, not on anything a reader
would recognize as a "page"), so before wiring in real numbers, decide:

- Show `chunk N of M` directly (simplest, ships now, but the number is
  meaningless to a reader - "chunk 3 of 11" isn't "page 3 of 11"), or
- Wait for F9 in [`reader-playback.md`](../proposal/reader-playback.md) (character-offset
  position) and derive a page-like estimate from that instead, the same way
  `ReaderScreen`'s existing non-TTS progress readout derives
  `estimatedTotalPages` from character count.

**Done when:** `TtsProgressStub()` renders something real between the
stop/settings group and the skip/play/skip group in `TtsControlsBar`.

## On completion

Delete this file, fold the "pause is modeled as stop+remember-index, not a
real engine feature" decision into
[`../foundational/design-notes.md`](../foundational/design-notes.md), and
update `../README.md`.
