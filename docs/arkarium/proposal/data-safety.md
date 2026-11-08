# Data safety: Room migrations and novel identity

> **Kind:** proposal · **Status:** F1, F2 and F23 fixed; F3 open (D3 decided, not yet built) · **Verified:** 2026-11-08 @ (this patch)
> **Origin:** [`engineering-review.md`](engineering-review.md) F1–F3, F23.

### F1. Room wiped all user data when a migration was missing

**Severity:** High. **Tags:** V (verified against code).
**Where:** `data/AppDatabase.kt`, `AppDatabase.create()`.

**Was:** 12 explicit migrations (`MIGRATION_1_2` … `MIGRATION_12_13`) chained with
`.addMigrations(...)`, followed by `.fallbackToDestructiveMigration()`, no
`room.schemaLocation`, no committed schema JSON, no migration test.

**Why it mattered:** `fallbackToDestructiveMigration()` drops and recreates the
whole database for any version Room has no explicit path for. The tables with
no other source of truth are `chapter_overrides` (manual edits a rescan can't
regenerate), `reading_progress` (current position per novel) and
`chapter_read_events` (streak history). A single missed or mis-numbered
migration step would silently delete all three for every installed user on
upgrade.

**Fixed (commit 5426a3d):** the global fallback is removed. An unhandled
version now throws `IllegalStateException` at `Room.databaseBuilder(...).build()`
time instead of destroying data, which surfaces as a crash in testing rather
than data loss in production.

**Closed (backlog rung 9):** `room.schemaLocation` is set, KSP replaced KAPT,
`app/schemas/com.arkarium.app.data.AppDatabase/14.json` is committed (via the
`room-schema.yml` workflow, commit 56140c7), and `MigrationChainTest` +
`MigrationTest` exist. `design-notes.md`'s "not a priority" ranking for
instrumented/Robolectric tests stands for the rest of the Compose UI layer;
this is the one place it doesn't apply. `MigrationTest`'s on-device replay
itself is still unrun (CI doesn't run `androidTest`) — see
[`../implementation/engineering-backlog.md`](../implementation/engineering-backlog.md)
rung 9.

**Decided (D2):** yes, but scoped to migrations specifically — one
instrumented `MigrationTestHelper` test per migration (maybe one CI emulator
job), not a broader push into instrumented/Robolectric coverage for the
Compose UI layer. `design-notes.md`'s "not a priority" ranking for
UI-layer instrumented tests stands; this finding is just the one place that
ranking doesn't apply, because a missed migration path is a crash (since the
fix above) or, before it, was silent data loss — not a UI regression a
screenshot test would catch anyway.

### F23. Downgrade had no recovery path — fixed

**Was:** removing the global fallback (F1) made `Room.databaseBuilder(...).build()`
throw `IllegalStateException` for *any* unhandled version, which includes a
**downgrade**: installing an older APK (whose `AppDatabase` only knows
migrations up through some lower version) over data a newer APK already
wrote. This is easy to hit by accident, not just through misuse: sideloading
an older release from GitHub (no Play Store version-gating), restoring an
Auto Backup from a newer install onto a device that got rolled back via
MDM/OEM tooling, or a tester reinstalling a prior build to bisect a
regression all land here. The crash itself was recoverable — clearing app
data gets the app running again — but that's a worse outcome than the
exception's own message suggested, since clearing app data removes the very
`chapter_overrides`/`reading_progress`/`chapter_read_events` F1's fix was
meant to protect, and the person hitting it had no way to know that short of
reading the stack trace.

**Fixed (backlog rung 2):** `AppDatabase.create()` catches the
`IllegalStateException` Room throws for any unhandled version and inspects
its message for the "migration from X to Y" shape; when X > Y (a downgrade)
it rethrows as `DatabaseDowngradeException`, a dedicated subtype carrying a
user-facing message, instead of Room's raw text. `MainActivity.onCreate()`
catches it ahead of the existing catch-all and shows "This library was last
opened by a newer version of ARKarium — update the app rather than
reinstalling an older version" instead of the class-name-plus-stack-trace
crash screen every other startup failure gets. This is a UI/error-message
change, not a data migration — it doesn't weaken the no-destructive-fallback
fix above, it just gives the one case that fix doesn't make safe-by-default
a message instead of a stack trace.

### F2. No exported schema, no migration tests, KAPT instead of KSP

Folded into F1 above — same root cause, same fix. **Fixed** (backlog rung 9): KSP,
`room.schemaLocation`, the committed `14.json`, `MigrationChainTest`, `MigrationTest`.

### F3. `novelId` depends on SAF URIs, so re-picking the folder or restoring a backup loses data

> **Related, not the same bug, and now fixed:** a scan used to be able to
> cascade-delete a local novel's history on an *ordinary* scan — no folder
> re-pick needed — when a novel was skipped or the folder listing came back
> empty/partial. See
> [`../implementation/engineering-backlog.md`](../implementation/engineering-backlog.md) rung 1.
> That rung's step 3 (soft-delete instead of cascade) was **not** included —
> it's bundled with F3's identity work (rung 12, below) per that rung's own
> note — so a confirmed-gone local novel is still deleted outright for now;
> fixing it will also cover most of the damage described below,
> independently of the D3 decision.

**Severity:** Medium. **Tags:** V (id derivation and rescan merge logic both read in code), I (not run on a device).
**Where:** `data/ScannerImpl.kt` `buildNovelEntity()` (id derivation), `viewmodel/LibraryViewModel.kt` `startScan()` (rescan merge/stale handling), `AndroidManifest.xml` (`android:allowBackup="true"`, no `fullBackupContent` rules).

**Correction to the original review:** the original writeup assumed a synced
novel has a stable id derived from the relay slug. That is not what the code
does. `buildNovelEntity()` derives **every** novel's id, local or synced, the
same way: `UUID.nameUUIDFromBytes(root.uri + ":" + child.uri)`. The relay slug
(`SyncManager.slugForUrl`, S9 in `architecture.md`) only names the on-disk
*folder*; the Room `novelId` is SAF-URI-derived regardless of sync status. So
re-picking the library's root folder changes every novel's id, synced
included.

**What actually happens on a rescan after re-picking the folder**
(`LibraryViewModel.startScan`):
- `onDiscovered` looks up the existing row by the newly-computed id
  (`db.novelDao().findById(scanned.id)`), not by anything stable like
  `syncSourceUrl`. With a new root URI this lookup always misses, so the scan
  treats every novel as brand new and inserts a second row under the new id.
- At `onScanCompleted`, the *old* rows (still present under their old ids,
  since this pass never "saw" those ids) are classified stale. A stale
  **local** novel (`syncSourceUrl == null`) is deleted outright — `ON DELETE
  CASCADE` takes its `chapter_overrides`, `reading_progress` and
  `chapter_read_events` with it in the same transaction. This is an actual
  data loss, not just an orphaned row.
- A stale **synced** novel is instead marked `MISSING_LOCALLY` and kept, per
  S6. Its history isn't deleted, but the library now shows two entries for
  the same fiction: the new one (fresh, no progress/overrides) and the old
  one sitting at `MISSING_LOCALLY` with the real history attached to an id
  the rest of the app no longer scans into.

**`allowBackup`:** `AndroidManifest.xml` sets `android:allowBackup="true"` with
no `android:fullBackupContent` rules. Auto Backup restores `arkarium.db`
verbatim onto a new device, but the SAF tree permission grants its URIs
depend on belong to the old device and don't resolve on the new one — every
local novel's folder link is dead on first launch after restore, and the next
scan hits the same delete-on-stale path above.

**Decision needed (D3):** whether novels get an identifier independent of the
SAF URI — e.g. a UID minted once and written into each novel folder's
`metadata.json` (local) or taken from `syncSourceUrl` directly (synced),
read back on every scan instead of re-derived from the root+child URI — or
whether this is accepted as a known limitation. This also affects F5 (any
per-directory metadata cache assumes the id is stable across rescans) and the
stale-deletion step in `startScan` above, which would need to stop keying off
`novelId` entirely if the id itself becomes unstable in the interim.

**If accepted as a limitation in the meantime:** at minimum, stop the
cascade-delete-on-stale path from firing on a root-folder change specifically
— e.g. require explicit user confirmation before `startScan` treats a
previously-seen local novel as gone — and add `android:fullBackupContent`
rules that exclude `arkarium.db`, so a restore doesn't silently hand the user
a library it's about to delete.

**Decided (D3):** give novels an opaque id, stored in Room, independent of
the SAF root+child URI:
- **Synced novels** derive the id from `syncSourceUrl` directly (already
  stable across both a folder re-pick and a backup restore, since it names
  the relay source, not the on-disk location).
- **Local novels** get a UID minted once. On a scan that can't find a
  matching stored id (new install, folder re-picked, backup restored onto a
  device whose SAF grants don't resolve), re-link by a best-effort match on
  title, folder name, and chapter count, with the match surfaced to the user
  for confirmation rather than applied silently — a false match is worse
  than a false "new novel."
- **Don't write the UID (or any ARKarium-owned file) into the user's library
  folder unless they opt in.** Writing into a folder the user doesn't fully
  control as ARKarium's own storage is a bigger behavior change than this
  finding needs, and it conflicts with folders that are also synced via
  something else (cloud storage, Syncthing) where an unexpected new file
  could itself cause a conflict. Store the minted UID in Room, keyed by
  whatever re-link signal was confirmed; only write it into the folder's
  `metadata.json` if the user has explicitly turned that on.

This also resolves F5's note below about any per-directory cache needing a
stable id — the id itself is now stable by construction, independent of this
decision being implemented.
