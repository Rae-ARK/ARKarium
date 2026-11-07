# Bug reports

> **Kind:** proposal · **Status:** open reports only · **Verified:** 2026-11-07 @ b4fcc2f

Open, unfixed bugs. A fixed bug is deleted from here; if the cause taught a
rule, it goes in [`../foundational/`](../foundational/README.md) (see sync
invariants S6–S9 in `architecture.md` for the pattern), otherwise the commit
message is the record.

Format: `### <short title>`, then **Seen** · **Where** · **Repro** · **Expected / Actual**, and **Cause** / **Status** when known.
Historic ARKster-era bug history is frozen in `../../reference/arkster/bugs.md`.

### Rotating the device restarts the app (splash and chord replay, state lost)

**Priority:** high. **Seen:** 0.8, reported by owner. Cause below comes from reading the code; not yet reproduced on a device.
**Where:** `MainActivity` (manifest + `showSplash`), `ui/Tts.kt`.
**Repro:** open the app, get past the splash, rotate portrait ↔ landscape.
**Expected:** same screen, same state, no splash.
**Actual:** the branded splash and guitar chord play again and the app appears to start over.

**Cause (from code):**
- The `<activity>` in `AndroidManifest.xml` declares no `android:configChanges`, so a rotation destroys and recreates `MainActivity`.
- `showSplash` is a plain `mutableStateOf(true)` field on the Activity, so every recreation re-initializes it to `true` and the splash (and its audio) plays again. It is not saved across recreation.
- Anything else held only in Activity fields or `remember { }` is also reset. Known case: `rememberChapterTts()` uses `remember`, so an active read-aloud session is stopped by rotation.
- Not verified: how much else is lost (dialog state, scroll positions, current NavHost route). The `NavHost` back stack and `rememberScrollState()` should survive, but check on a device.

**Direction (options, not decided):** (a) make `showSplash` survive recreation (hold it in a ViewModel or `rememberSaveable`) so the splash is once-per-process, not once-per-Activity; (b) additionally handle orientation without recreation via `configChanges`. (a) is the real fix; (b) alone hides the problem for rotation but not for other config changes (dark mode, locale, font scale). Then audit what state is lost on recreation, starting with TTS.

### "Notify me when new chapters are available" toggle is not visible

**Seen:** 0.8, reported by owner. **Status:** needs triage; likely hidden by design, but the design is unexplained to the user.
**Where:** `ui/NovelDetailScreen.kt` (the toggle row), `MainActivity.kt` (what it passes as `onToggleNotify`).
**Repro:** open a novel's detail page and look for the toggle under the description.
**Expected:** a way to turn on new-chapter notifications.
**Actual:** no toggle on the page, and none in Settings.

**Cause (from code):** the feature exists. The row renders only when `onToggleNotify != null`, and `MainActivity` passes it only when `novel.syncSourceUrl != null`. So it is hidden for any novel that is not synced: one added by dropping a folder in by hand, or one that was unlinked (see sync invariant S6 in `architecture.md`). The background worker only checks novels whose toggle is on.

**To resolve:**
1. Confirm which novel was open. If it was added via "Add fiction" or "Sync all" and the toggle is still missing, that is a real bug in the gating or the layout: capture the novel's `syncSourceUrl`.
2. If it was a local novel, nothing is broken, but the feature is undiscoverable. Options: show the row disabled with a one-line reason ("Only available for synced fictions"), and/or a global switch in `settings/`.
3. Either way, check on a device that turning it on on API 33+ prompts for `POST_NOTIFICATIONS` and that a notification actually arrives; that end-to-end path has no test.
