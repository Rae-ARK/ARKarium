# UI/UX issues

> **Kind:** proposal · **Status:** open issues only · **Verified:** 2026-11-07 @ a621542

Open usability, visual, or feature-gap issues that are not crashes or wrong
behavior (those go in [`bugs/`](bugs/README.md)). Delete an entry when fixed.

Format: `### <short title>`, then **Where** · **Problem** · **Idea**.

> Read-aloud's playback-controls gap (pause/resume, skip, a controls bar, highlight)
> is promoted to [`../implementation/tts-playback-controls.md`](../implementation/tts-playback-controls.md) -
> see that ladder for current state, not here.

### Arc cover enlarger is undiscoverable

**Where:** `ArcTabCard` and `EnlargedCoverDialog` in `ui/NovelDetailScreen.kt`. The arc cover is the same image the novel header and reader show, so the enlarger lives only on the arc tab strip by design.
**Problem:** the enlarged view opens only on a double-tap, and nothing hints that it exists. Closing is already covered (back button, tap outside the card, system back).
**Idea:** add a hint that the cover can be opened: a small magnifier badge on the thumbnail, or a one-time tip. A single-tap is not an option because it already selects the arc.
