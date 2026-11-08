# Streak / chapters-this-week don't update; "Continue Reading" still feels broken

> **Seen:** owner-reported, "started happening after I did the DB schema
> update" · **Status:** fix applied, **needs on-device confirmation** ·
> **Verified:** 2026-11-09 (code reading only; not built or run)

**Where:** `data/ReaderPosition.kt`, `data/ReadingStats.kt`,
`ui/ReaderScreen.kt` (`currentAnchor`, TTS `onChapterFinished`),
`MainActivity.kt` (`saveReadingProgress`).

**Repro:** read an ordinary chapter top to bottom by scrolling (no TTS
auto-continue), leave the chapter (Next, Back, or close it). Check Home's
"day streak" / "chapters this week" card, and listen for the completion
haptic.

**Expected / Actual:** the chapter counts toward the streak and weekly total,
and a short haptic confirms it finished / nothing updates, and the owner
can read three chapters in a row with no change in either counter.

**Cause:** rung 14 (F9, `reader-playback.md`) changed
`ReadingProgressEntity.position` from a `0f..1f` scroll fraction
(`PERCENTAGE`) to a raw character offset (`CHAR_OFFSET`) - the paragraph
sitting at the *top of the viewport*, used to resume into the right
paragraph. `saveReadingProgress` kept comparing against
`ReadingStats.COMPLETION_THRESHOLD` (0.9f) by deriving a fraction as
`position.charOffset.toFloat() / chapterLength`. That fraction is the wrong
measurement for "how much of the chapter was scrolled through": the
top-of-viewport paragraph when scrolled all the way to the bottom is
typically only 60-85% of the way through the chapter's raw text (a 5-screen
chapter's last screenful starts around the 80% mark), not ≥90%. So the
derived fraction almost never crossed the threshold for an ordinarily-read
chapter - the one path rung 14 didn't touch, TTS auto-continue's explicit
`content.length` anchor, happened to still read as "finished" by coincidence,
which is why this silently affected manual reading specifically. Both the
`chapterReadEventDao` row (streak/weekly-count) and the one-time completion
haptic are gated on the same `finishedNow` check, so both symptoms share this
one cause.

**Fix:** split "where to resume" from "how much was read" into two
independent fields on `ReaderPosition`: `charOffset` (unchanged - the resume
anchor) and a new `completionFraction` (the real scroll fraction,
`scrollState.value / scrollState.maxValue`, already computed by
`currentProgress()`). `currentAnchor()` now fills both. TTS's
`onChapterFinished` sets `completionFraction = 1f` explicitly (it finished
the chapter by ear, not by scroll position). `saveReadingProgress` compares
`position.completionFraction` against `COMPLETION_THRESHOLD` directly and no
longer needs a `chapterLength` parameter at all - removed from its signature
and all six call sites.

**Separately checked and not re-broken:** the actual position-restore path
(`initialPositionResolved` gating, the legacy `PERCENTAGE`→`CHAR_OFFSET`
conversion, `paragraphForCharOffset`/`paragraphContentTop` lookup) was
re-read end to end against this report and still looks correct on paper -
no second bug found there. If "Continue Reading" is still landing at the
wrong spot after this patch (as opposed to the streak/haptic not updating,
which this fix directly addresses), it's a different, so-far-undiscovered
cause; please say which of the two - wrong spot vs. counters not moving -
still reproduces after testing this patch, so a re-audit can start from
whichever one is confirmed.

**To close:** confirm on a device - read 2-3 chapters to the bottom by
scrolling (not via TTS auto-continue), check the streak/weekly-count card
updates and the completion haptic fires, then separately confirm whether
Continue Reading itself now lands in the right place.
