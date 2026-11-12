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

The no-drift rule (keeping all of this true) is in [`../README.md`](../README.md).
