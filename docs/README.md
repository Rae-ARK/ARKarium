# Docs index

Two parts, on purpose, so it is never ambiguous which project a doc describes.

| Folder | What it is |
|---|---|
| [`arkarium/`](arkarium/README.md) | **This app.** Organised by lifecycle: `proposal` (future) → `implementation` (present) → `foundational` (permanent). Start at [`arkarium/foundational/what-is-arkarium.md`](arkarium/foundational/what-is-arkarium.md). |
| [`reference/`](reference/README.md) | Frozen docs from other projects (currently ARKster). Not specs for this app. |

## No-drift rule

Docs rot silently; this section is the guard. Whoever changes the code owns the docs for that change.

**With every change** (same commit):

- Changed behavior, a screen, a table, or the sync protocol → update the doc that describes it.
- Finished or started a rung → update its state in the ladder and in `implementation/README.md`.
- Moved, renamed, or deleted a doc → fix every pointer: `grep -rn "<old name>" app/ docs/ README.md`.
- Added a doc → add it to its folder's `README.md` index.
- Verified a doc against the code → bump its `Verified:` line (date and short commit). Never bump it without actually checking.

**Periodically** (every release bump, and at least monthly):

1. Run `python3 tools/check_docs.py` (links, indexes, code→doc pointers, stale `Verified:` dates). Fix everything it reports.
2. Skim `foundational/architecture.md` against `app/` for things that no longer exist or are missing.
3. Skim `implementation/` for a ladder whose rungs are all done (graduate it) or stalled (demote it back to `proposal/`).
4. Skim `proposal/` for items now built (delete or graduate) or dead (delete).

**Writing rules** (these keep docs maintainable):

- One fact, one home. Link instead of repeating.
- Point at code (`file`, `symbol`) rather than pasting it; avoid line numbers and counts that go stale.
- State only what is true now. History belongs to `git log`, not to the doc.
- Foundational docs describe what exists; plans carry "will", and only in `proposal/` and `implementation/`.
- Be short. If a doc outgrows its purpose, split or cut it.

## Reference material

[`reference/arkster/`](reference/arkster/README.md) holds copies of docs from the
predecessor project [ARKster](https://github.com/Rae-ARK/ARKster) (GPLv3). They
are frozen prior art: not maintained here, not edited in place. Source comments
cite `bugs.md` and `AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md` because the logic they
describe came from ARKster. Anything that becomes relevant to ARKarium is
re-specified as a new doc in `arkarium/`.
