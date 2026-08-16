# Next fixes — sync robustness

> **Status:** Known issues, not yet built. Found during a design-review pass
> over `SyncManager.kt` / `FictionLut.kt` / `ScannerImpl.kt` after the
> single-origin-by-name sync landed (see `docs/arkarium/SYNC_MVP.md`). None of these
> block what's already shipped; all four are worth fixing before treating
> sync as production-solid, since sync is the mechanism the whole
> direct-to-reader model depends on being trustworthy.

---

## 5. Fixed — folder name doubled as sync identity (MIGRATION_10_11)

Synced folders were named `synced-<hash-of-baseUrl>` (`SyncManager.slugForUrl`).
That hash was quietly doing two jobs at once: it was the folder's on-disk name
*and* the only thing `sync()` used to relocate that folder on every later sync
(`findFile(slugForUrl(baseUrl))`, once `findNovelFolder`'s id-based lookup
missed). That's fine as long as the folder name is a value nothing ever wants
to be human-readable - the moment it's changed to the fiction's actual title
(so a library folder browsed outside the app reads as a real name instead of
a hash), those two jobs pull apart: two fictions can share a title, a title
can collide with an unrelated folder, and relocation-by-recomputing-the-name
stops working the instant the name means something other than "hash of the
URL."

Fixed by decoupling the two: `downloadInitial` now names a new synced folder
after the fiction itself (sanitized, de-duplicated against existing siblings -
see `SyncManager.sanitizeFolderName`/`uniqueFolderName`), and the *exact*
resolved folder name is persisted as its own column, `NovelEntity.syncFolderName`
(`sync_folder_name`, added in `MIGRATION_10_11`). `sync()`'s folder-relocation
now checks that persisted name first, falling back to the old id-hash/slug
lookups only for novels synced by a pre-migration build (see `SyncManager.sync`'s
folder-relocation comment for the full priority order). Sync bookkeeping no
longer has to agree with the folder's display name for anything to work.

## 6. Fixed — "Use custom folder" row could push the Switch off-screen

`SettingsScreen`'s "Use custom folder" row laid its label/description `Column`
out unweighted next to the `Switch`. An unweighted `Column` in a `Row` is
measured with loose (unbounded) width, so the description text - long enough
to need wrapping - never actually wrapped against the space left for it; it
just claimed as much width as it wanted and pushed the `Switch` out past the
screen edge instead of sitting next to it. Fixed by giving the `Column`
`Modifier.weight(1f)`, which forces it to share the row with the `Switch` and
wrap its text within its own share.
