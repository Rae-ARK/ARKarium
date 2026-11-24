# Commenting

> **Kind:** foundational · **Verified:** 2026-11-24 @ cab90f4 · **Truth lives in:** `app/` itself

**Read this before editing any code in this repo.** It's not a style
preference; the sync/manifest/scanner cluster has had real drift traced back
to the opposite habit (comments restating history instead of the code
stating its own invariants), and this doc is the fix going forward.

## The default: don't write the comment, remove the need for it

A comment explaining *what* code does is usually a sign the code itself
isn't saying it clearly enough. Before adding one, try, in order:

1. **Name the thing.** A magic number or an opaque condition becomes a named
   constant or a named intermediate value. The code now reads like the
   comment would have, so the comment is redundant.
2. **Extract a function.** A multi-clause condition or a multi-step
   procedure gets a name (`isReadyToSync(...)`, `mergeNovelForRescan(...)`).
   The call site needs no comment because the name carries the intent.
3. **Let the type say it.** `Int?` beats `Int` + "`-1` means absent"; a
   sealed class beats an int flag + "`2` means syncing"; a non-null
   constructor param beats a comment saying "caller must set this." A type
   is checked by the compiler on every build; a comment is checked by
   nobody.

If, after trying these, the code still needs a sentence to be understood,
write the comment - but write *why*, not *what the code already says in
Kotlin*.

## Why this is the rule here specifically

Measured across this codebase's Kotlin source on 2026-11-24, comment density
was not evenly spread. The sync/manifest/scanner cluster ran well above the
rest of the codebase, with a couple of files carrying more comment lines
than code lines, and a recurring pattern of comments that were really a
changelog pasted into the source: *"Bug 2 follow-up"* (`Dao.kt`),
*"Pre-existing gap fixed here"* (`SyncManager.kt`, `SyncViewModel.kt`), and
similar. That phrasing describes a PR, not the code as it exists now - it
belongs in a commit message, not a file that will outlive the fix it
describes.

This isn't hypothetical: `Entities.kt`'s comment on
`ChapterManifestMetaEntity` still cites `ChapterDao.updateManifestMeta` -
code that is not what calls it anymore (`ChapterManifestMetaDao.upsert`
does, via `applyManifestMetadata`). The comment survived a rename/refactor
that the code didn't carry it through, because nothing enforces that a
comment stays true. A test fails loudly when it's wrong; a comment just
sits there, wrong, until someone happens to read carefully enough to notice.

The files hit hardest by this (`ScannerImpl.kt`, `SyncViewModel.kt`,
`SyncManager.kt`, `Manifest.kt`) are also the ones with the most
order-dependent logic - rescan-before-apply, id stability across rewrites,
the fingerprint short-circuit. A comment saying "do X before Y, already
fixed once" is standing in for something a test or an assertion should be
doing instead: it asks the next person to read carefully and not break the
ordering again, rather than making breaking it fail the build.

## What to do instead, for this kind of thing

- **History goes in the commit, not the file.** "This used to be broken,
  here's what changed" is exactly what `git log` and `git blame` are for.
  If it's worth knowing in six months, it's worth a clear commit message;
  it is not worth a comment that will one day describe code that no longer
  exists.
- **Order-dependence becomes a guard, not a warning.** Where sequencing
  actually matters (rescan-before-apply, id stability, the fingerprint
  short-circuit), prefer a precondition check, a function boundary that
  makes the order impossible to get wrong, or a test that pins the
  behavior - over a comment asking the reader to be careful.
- **A real "why" comment is still welcome.** Explaining a non-obvious
  performance trade-off, or citing the source of an algorithm/format this
  code implements, is the kind of thing code can't say on its own. Keep
  those.
- **If you find a stale one while you're in a file anyway, fix or delete
  it.** You don't owe the whole file a sweep, but don't leave a comment you
  know is wrong for the next reader.

## Scope

This applies to inline comments in `app/`. It does not change the rules for
doc comments on public APIs (KDoc) describing a contract - state, error
conditions, thread-safety - which are documentation, not narration, and are
covered by ["No-drift rule"](../README.md#no-drift-rule) like the rest of
`docs/`.
