# ARKarium docs

Docs for the app in this repo, organised by **lifecycle**: work flows
`proposal → implementation → foundational` (future → present → past, kept
because it still matters).

| Folder | Time | Holds |
|---|---|---|
| [`proposal/`](proposal/README.md) | future | Documented but untouched: ideas, features, bug reports, UI/UX issues. |
| [`implementation/`](implementation/README.md) | present | Work in progress as a staged rung ladder. |
| [`foundational/`](foundational/README.md) | past, permanent | What ARKarium is, how it is built, why. |

Start with [`foundational/what-is-arkarium.md`](foundational/what-is-arkarium.md).

## Lifecycle rule

1. A new idea or report starts in `proposal/`.
2. When someone commits to building it, `git mv` it to `implementation/` and split it into rungs.
3. When the last rung ships, move only the **decisions and why** into `foundational/` and delete the ladder.

Never leave a doc in a folder that no longer describes its truth. Moving a doc
is the update.

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
