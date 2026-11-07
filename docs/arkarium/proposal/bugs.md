# Bug reports

> **Kind:** proposal · **Status:** open reports only · **Verified:** 2026-11-07 @ b39eafe

Open, unfixed bugs. A fixed bug is deleted from here; if the cause taught a
rule, it goes in [`../foundational/`](../foundational/README.md) (see sync
invariants S6–S9 in `architecture.md` for the pattern), otherwise the commit
message is the record.

Format: `### <short title>`, then **Seen** · **Where** · **Repro** · **Expected / Actual**, and **Cause** / **Status** when known.
Historic ARKster-era bug history is frozen in `../../reference/arkster/bugs.md`.

### Rotating the device stops an active read-aloud session

**Priority:** high. **Seen:** 0.8, reported by owner (as part of a broader "rotation restarts the app" report - the splash-replay half of that report is fixed, see below; this is the half that isn't). Cause below comes from reading the code; not yet reproduced on a device.
**Where:** `MainActivity` (manifest - no `android:configChanges`), `ui/Tts.kt` `rememberChapterTts()`.
**Repro:** open a chapter, start read-aloud, rotate portrait ↔ landscape.
**Expected:** playback continues from where it was.
**Actual:** playback stops; resuming requires pressing Play again (and starts the current chunk over, not mid-chunk).

**Cause (from code):** the `<activity>` in `AndroidManifest.xml` declares no
`android:configChanges`, so any config change (not just rotation - dark
mode, locale, font scale too) destroys and recreates `MainActivity`.
`rememberChapterTts()` holds its `TextToSpeech` engine and chunk queue in
`remember { ChapterTtsState() }` plus a `DisposableEffect(Unit)` that builds
the engine; both are torn down and rebuilt from scratch on recreation, same
as every other `remember`-scoped composable in the tree. Unlike `showSplash`
below, this one has no cheap fix: a live `TextToSpeech` engine and its
in-flight utterance aren't the kind of state `rememberSaveable` can persist
through a Bundle (it isn't serializable, and "resume what was mid-utterance
when destroyed" isn't something either reconstructs).

**Direction:** this is the same restructuring [`reader-playback.md`](reader-playback.md)
F10 already calls for independent of this bug - moving playback into a
`MediaSessionService` makes it survive Activity recreation (and backgrounding,
and navigation away from the reader) for free, since a Service's lifecycle is
independent of the Activity's. Not worth a narrower one-off fix here; fix F10
and this bug is fixed as a side effect.

**Not verified:** how much else besides TTS is lost on recreation (dialog
state, scroll positions, current `NavHost` route). The `NavHost` back stack
and `rememberScrollState()` should survive via their own `rememberSaveable`
use, but this hasn't been checked on a device.

### "Notify me when new chapters are available" toggle needs on-device confirmation

**Seen:** 0.8, reported by owner (as "toggle is not visible"). **Status:** the undiscoverability half is fixed (see below); step 1 and the device check in step 3 below are still open and need the owner, not just code.
**Where:** `ui/NovelDetailScreen.kt` (the toggle row), `MainActivity.kt` (what it passes as `onToggleNotify`).

**Cause (from code), as originally found:** the row rendered only when
`onToggleNotify != null`, and `MainActivity` passes that only when
`novel.syncSourceUrl != null` - so the row was entirely absent, not just
disabled, for any novel that isn't synced (one added by dropping a folder in
by hand, or one that was unlinked - see sync invariant S6 in
`architecture.md`). The background worker only checks novels whose toggle is
on.

**Fixed:** the row now always renders. For a local novel it shows disabled
with "Only available for fictions synced from a source" instead of
disappearing, so the feature is discoverable even where it can't be turned
on (resolve option 2 from the original writeup).

**Still open - needs the owner on a real device, not something fixable from reading the code:**
1. Confirm which novel the original report was actually about. If it was
   added via "Add fiction" or "Sync all" (so `syncSourceUrl` should be set)
   and the toggle was *still* missing rather than just unlabeled, that's a
   second, different bug in the sync-detection path itself - re-open this
   entry with that novel's details if so.
2. Check that turning the toggle on, on API 33+, actually prompts for
   `POST_NOTIFICATIONS` and that a notification arrives when a new chapter
   shows up - `MainActivity`'s `notificationPermission` launcher and
   `NewChapterCheckWorker` exist and read correctly in isolation, but that
   end-to-end path has no test and hasn't been run on a device.
