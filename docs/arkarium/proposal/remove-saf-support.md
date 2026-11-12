# Remove SAF support: one fixed library folder at Android/media/

> **Kind:** proposal · **Status:** accepted — implemented (see
> [`engineering-backlog.md`](../implementation/engineering-backlog.md) rungs 12 and 15,
> closed won't-fix below) · **Verified:** 2026-11-12 @ (this patch)
> **Origin:** follow-up to [`engineering-backlog.md`](../implementation/engineering-backlog.md)
> rungs 12 (F3) and 15 (F5), and [`performance.md`](performance.md)'s F5 writeup
> and [`data-safety.md`](data-safety.md)'s F3 writeup. Both are open "Large"
> tickets that exist only because the library root can be a user-picked SAF
> tree; this proposes removing that path instead of fixing around it.

## What was built before this change

`ScannerImpl.scanRoot` walked the library root as a `DocumentFile`, resolved by
`resolveLibraryRoot(context, useCustomFolder, savedUri)` in `data/LibraryScan.kt`
(a private duplicate also lived in `data/NewChapterCheckWorker.kt` for the
background worker):

- **Default** (`useCustomFolder = false`): `context.getExternalFilesDir(null)`,
  i.e. `Android/data/com.arkarium.app/files/`.
- **Opt-in** (`useCustomFolder = true`): a SAF tree the user picked via
  `ACTION_OPEN_DOCUMENT_TREE`, with a persisted URI permission taken in
  `MainActivity`'s `pickFolder` callback and released on re-pick.

The SAF path existed for a narrow case — someone who already keeps novels in a
shared folder (synced via Syncthing, a cloud drive, or shared with ARKster)
and wants ARKarium to read from that same folder instead of its own sandbox.
Nothing in this repo's docs or backlog showed it had ever been requested or
used; it was built defensively, not in response to demand.

This dual path was the direct cause of F3 (`novelId` identity tied to the SAF
URI, Large, queued) and F5 (SAF scan costs ~3 content-provider queries per
file, Large, blocked on F3) — both open only because a SAF-backed root could
change or vanish out from under the app at any time.

## What changed

One fixed library location, chosen at build time, never by the user:

```
Android/media/com.arkarium.app/
```

via `context.getExternalMediaDirs()[0]`, in place of the old
`getExternalFilesDir(null)` default and the SAF branch entirely.

- No "Use custom folder" switch, no folder picker, no persisted URI
  permission grant/release logic.
- `resolveLibraryRoot()` collapsed to an unconditional
  `DocumentFile.fromFile(context.getExternalMediaDirs().firstOrNull())`.
- Users add novels by dragging a folder into `Android/media/com.arkarium.app/`
  from a PC, a file manager, or ARKarium's own sync — no in-app picker step.
- A fiction folder dropped in this way is picked up by the existing
  incremental scanner exactly as before; nothing about the scan logic itself
  changed, only what `resolveLibraryRoot` hands it.
- `ui/LibrarySettingsScreen.kt`'s "Use custom folder" row **stays on screen**
  rather than being deleted outright — it is now purely informational. Its
  switch and Select/Change Folder button are gone; in their place is an info
  icon that opens a "Custom folder removed" dialog explaining where the
  library lives now. The same dialog opens automatically, once, for anyone
  upgrading from a build where the old switch was actually on, via
  `PreferencesManager.shouldShowSafRemovedNotice`/`dismissSafRemovedNotice()`.
  Rescan Library is unchanged.

## Why `Android/media/` over `Android/data/` or SAF

| | `Android/data/` (old default) | SAF custom folder (old opt-in) | `Android/media/` (now) |
| --- | --- | --- | --- |
| Visible to file managers / PC via USB | No — blocked outright since Android 13, closing a loophole third-party file managers had used ([Esper](https://esper.io/blog/android-dessert-bites-28-file-manager-loophole-closed-73891524)) | Yes, but only the one tree the user explicitly picked | Yes, by design — `Android/media` is the shared-storage area meant for exactly this ([Android CDD §7.6](https://mobibrw.com/Android_10.0.0_r40/raw/compatibility/cdd/7_hardware-compatibility/7_6_memory-and-storage.md)) |
| Needs a runtime permission or picker | No | Yes — `ACTION_OPEN_DOCUMENT_TREE` plus a persisted URI grant | No |
| Removed on uninstall | Yes | N/A (lives outside the app's storage) | Yes |
| Read/write access model | App-sandboxed only | Whatever tree the user grants; can change or vanish at any time | Any app can read/write it, the same looseness WhatsApp and Telegram rely on for their own media folders ([Dolphin emulator storage discussion](https://bugs.dolphin-emu.org/issues/12764)) |
| File-path performance | Fast — real filesystem, bypasses FUSE for `Android/data` ([Android scoped storage docs](https://source.android.com/docs/core/storage/scoped?authuser=1)) | Slow — ~3 content-provider queries per file, exactly F5 | Fast — same FUSE-bypass real-filesystem path as `Android/data` |

Two caveats worth stating plainly:

- `getExternalMediaDirs()` carries a deprecated annotation in the SDK, but
  remains functional with no removal timeline, and is still used in
  production by major apps such as WhatsApp and Telegram; the Dolphin
  emulator project weighed this exact trade-off and reached the same
  conclusion ([bugs.dolphin-emu.org/issues/12764](https://bugs.dolphin-emu.org/issues/12764)).
- `LibrarySettingsScreen.kt`'s old copy told users to drop novel folders into
  the app's `Android/data` folder — that instruction had been broken since
  Android 13 restricted third-party file manager access to `Android/data` and
  `Android/obb`
  ([Esper](https://esper.io/blog/android-dessert-bites-28-file-manager-loophole-closed-73891524)).
  Moving the default to `Android/media/com.arkarium.app/` wasn't just
  cleanup — it fixed a genuine existing defect in the old build.

## Engineering cost (actual)

Traced against the codebase at the time this change landed.

| File | Change |
| --- | --- |
| `MainActivity.kt` | Removed `pickFolder` ActivityResultContract + persist/release URI-permission logic; dropped `useCustomFolder`/`savedUri` wiring at every call site |
| `ui/LibrarySettingsScreen.kt` | Removed the switch and Select/Change Folder button; added the info-icon dialog and the one-time migration-notice plumbing |
| `data/PreferencesManager.kt` | Removed `useCustomFolder`/`libraryUri` from `SettingsPreferences`; added `shouldShowSafRemovedNotice`/`dismissSafRemovedNotice()`, backed by the old (now read-only) `USE_CUSTOM_FOLDER_KEY` so an upgrading install can still be shown the one-time notice |
| `viewmodel/SettingsViewModel.kt` | Removed exposed `useCustomFolder`/`savedUri` StateFlows and setters; added `shouldShowSafRemovedNotice`/`dismissSafRemovedNotice()` |
| `navigation/AppDialogHost.kt` | Removed the `useCustomFolder`/`savedUri` params; `resolveLibraryRoot(context)` calls simplified |
| `data/NewChapterCheckWorker.kt` | Collapsed its private duplicate `resolveLibraryRoot` down to a call into the shared one; dropped the now-unused `PreferencesManager` construction |
| `data/LibraryScan.kt` | Collapsed `resolveLibraryRoot` to the unconditional `Android/media` resolution |
| `data/ScannerImpl.kt`, `ui/ThemeSettingsScreen.kt` | Comment references updated |
| `test/.../SettingsViewModelTest.kt` | `FakeSettingsPreferences` trimmed to match the new interface; `useCustomFolder`/`setLibraryUri` tests replaced with a `dismissSafRemovedNotice` test |

No `AndroidManifest.xml` changes — `ACTION_OPEN_DOCUMENT_TREE` never required a
declared permission. No instrumentation/UI tests referenced this feature,
only the one unit test file above.

Both open backlog items this was blocking are now closed outright:

- **F3** (novel identity tied to SAF URI, Large, queued) — the `novelId` hash
  includes `root.uri`; with the root permanently fixed, this instability is
  gone for good rather than needing its own design ladder.
- **F5** (SAF scan costs ~3 provider queries per file, Large, blocked on F3) —
  moot now that there's no SAF path to optimize; `DocumentFile.fromFile()`
  already does plain filesystem calls.

See [`engineering-backlog.md`](../implementation/engineering-backlog.md),
rungs 12 and 15, both now marked won't-fix pointing here.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Anyone who already enabled "Use custom folder" on a shipped build silently loses visibility into that folder's novels on next launch (files aren't deleted, the app just stops pointing at them) | `LibrarySettingsScreen` shows a one-time migration dialog on upgrade if `useCustomFolder` was true, telling the user where their novels still are and that custom-folder support is gone. Given the repo shows 0 stars/watchers and no evidence of real users, this risk was close to zero in practice. |
| Already-persisted SAF URI grants aren't explicitly released | Harmless — they sit unused until uninstall, no crash or leak risk. |
| `getExternalMediaDirs()` is marked deprecated in the SDK | No announced removal; WhatsApp, Telegram and others depend on the same API in production today. Worth re-checking before each major Android target bump, not a reason to avoid it now. |
| Removing a configurability option is sometimes seen as a regression by power users | Nothing in ARKarium's own docs or backlog showed a user ever requesting this; it was built defensively, not in response to demand. ARKster remains the answer for anyone who genuinely wants to point a reader at an arbitrary folder. |

## Outcome

Accepted and implemented. SAF support removed; the library always reads from
`Android/media/com.arkarium.app/`.
