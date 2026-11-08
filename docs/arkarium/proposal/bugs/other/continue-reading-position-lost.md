# Continue Reading lands at the top of the chapter

> **Seen:** owner-reported, still reproducing after the PERCENTAGE→CHAR_OFFSET
> conversion fix · **Status:** fix applied, **needs on-device confirmation** ·
> **Verified:** 2026-11-09 (code reading only; not built or run)

**Where:** `MainActivity.kt` (reader route, `saveReadingProgress`, intent handling),
`ReaderScreen.kt` (`currentAnchor`, restore effect).

**Repro:** read part-way into a chapter, leave (Back, Home, rotate, or let the process
die), reopen via Continue Reading (Home row or launcher shortcut). Most reliable with
non-default reader settings (font size / line height) or a chapter that loads slowly.

**Expected / Actual:** reader reopens at the paragraph it was left on / reopens at the
top, and the saved position is then overwritten with the top.

**Causes found (all fixed):**
1. Race: `ReaderScreen` decides once, at first composition, whether there is a position
   to restore. It was composed as soon as the chapter text loaded, usually before the
   `reading_progress` lookup finished, so a late answer was ignored. Now the route
   gates the reader on `initialPositionResolved`.
2. Clobber: the pause/dispose checkpoint saved `currentAnchor()` before the restore had
   landed - scroll still 0 - overwriting the real position. `currentAnchor()` now
   reports the not-yet-applied position until restore completes or the reader drags.
3. Drift: the restore ran against the first layout, made with placeholder font
   settings, then the real settings reflowed the text. The restore now waits for the
   seeded settings and two frames of reflow.
4. Re-fire: after any recreation the original shortcut/notification intent was handled
   again, navigating the reader away. Handled only on a fresh launch now; unresolved
   ones survive via `onSaveInstanceState`.
5. Process restore: a restored `reader/...` route found the empty chapter cache and
   bounced to Home. It now asks the database first.
6. Cancellation: the save ran under the caller's scope; it is now `NonCancellable`.
7. Side effect: the completion haptic fired on every re-save of a finished chapter; now
   once per chapter per day.

**To close:** confirm on a device (cold shortcut, rotate mid-chapter, kill process).
