# UI/UX issues

> **Kind:** proposal · **Status:** open issues only · **Verified:** 2026-11-07 @ b4fcc2f

Open usability, visual, or feature-gap issues that are not crashes or wrong
behavior (those go in [`bugs.md`](bugs.md)). Delete an entry when fixed.

Format: `### <short title>`, then **Where** · **Problem** · **Idea**.

> Read-aloud's playback-controls gap (pause/resume, skip, a controls bar, highlight)
> is promoted to [`../implementation/tts-playback-controls.md`](../implementation/tts-playback-controls.md) -
> see that ladder for current state, not here.

### Arc covers are too small to see

**Where:** arc cards on `ui/NovelDetailScreen.kt` (cover uses arc → fiction fallback); arc cover also appears as the reader's cover.
**Problem:** the covers render as small thumbnails, so cover art cannot be appreciated or read.
**Idea:** double-tap a cover to open it enlarged (full-screen viewer, pinch-to-zoom, tap or back to dismiss). Applies to fiction covers too. Decide: double-tap as asked (note it is undiscoverable; consider also a single-tap or a magnifier hint), and how a failed or missing image behaves (the generated placeholder cover should not open a viewer). Note an existing `clickable` on the card means a double-tap gesture must not break single-tap navigation: use `combinedClickable` with `onDoubleClick`, and expect a short single-tap delay while Compose waits to rule out a double-tap.
