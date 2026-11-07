# What ARKarium is

> **Kind:** foundational · **Verified:** 2026-11-06 @ b4fcc2f · **Truth lives in:** `README.md`, this file

ARKarium is a native reader for independently published fiction, built around
**direct author-to-reader distribution**. The author's repository is the
canonical source; content is published as static files and synced into the
reader's local library. There is no platform backend in the reading path.

It is not a Royal Road clone. The aim is to control the chain that matters
(publish format → distribution → reading experience) while keeping the
infrastructure boring.

## Principles

1. **Direct-to-reader.** No giant central platform between author and reader.
2. **Local-first.** Once synced, reading needs no connectivity. The network
   answers "do I have the newest content?", never "may I read this?".
3. **Static-first.** Prefer static files and simple protocols over backend.
4. **Few dependencies.** Every external service is a failure, cost, or
   control point. Flatten the graph.
5. **Experience is the moat.** The value is what the client does with the
   content (reader UX, sync, and later, character-aware TTS).
6. **Creator-controlled.** The author's repo stays canonical; novels remain
   the author's works under their own terms.
7. **Progressive sophistication.** Start with TXT + JSON + PNG; add
   complexity only for a concrete reader benefit.

## Clients

| Client | State |
|---|---|
| Android (this repo, `app/`) | Early development. Successor to the ARKster reader. |
| Desktop, iOS | Possible later; would consume the same content format. |
| Web | Discovery only; lives in a separate repo (Horizon ARK Studio). |

## Relationship to sibling projects

ARKster (Android) and ARKium (desktop) are earlier, **GPLv3** readers by the
same author. ARKarium reuses ideas and some logic from ARKster (chapter
sort-tier convention, author pages, rescan safety); those origins are kept as
frozen reference in [`../../reference/arkster/`](../../reference/arkster/README.md).
ARKarium itself is proprietary. If code from a GPL project is ever carried
over, audit copyright ownership and dependency licenses first.

## Where to go next

- How it is built: [`architecture.md`](architecture.md)
- Why it is built that way: [`design-notes.md`](design-notes.md)
- Ideas not yet built: [`../proposal/`](../proposal/README.md)
