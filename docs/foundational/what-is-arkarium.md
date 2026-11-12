# What ARKarium is

> **Kind:** foundational · **Verified:** 2026-11-13 @ (this patch) · **Truth lives in:** `README.md`, this file

ARKarium is a native reader for independently published fiction, built around
**direct author-to-reader distribution**. The author's repository is the
canonical source; content is published as static files and synced into the
reader's local library. There is no platform backend in the reading path.

**It is Rae ARK's own app for his own readers - not a general-purpose
reading platform.** There is exactly one author and one relay today
(`RELAY_HOST` in `data/SyncManager.kt` serves only Rae ARK's own fictions;
`FictionLut`'s full entry list is "every fiction Rae ARK has published," not
a storefront catalogue with a per-author filter). This isn't a current
limitation waiting to be lifted - it's the premise. A feature that would only
make sense for a platform serving many unrelated authors or readers
(cross-author discovery, public ratings/rankings, a marketplace, social
features between strangers) is out of scope by default, not just unbuilt;
see [`backend-social.md`](../far-future-concern/backend-social.md) for what a
genuine move in that direction would actually require. A multi-author
version is conceivable later (`../proposal/ideas.md`'s "Publisher pipeline
(ARKlight)" row sketches what that would need) but is a distinct, larger
decision, not where this app already is.

It is not a Royal Road clone, and not a Kindle/Libby-style app either - both
of those serve many authors' and publishers' work to an open readership. The
aim here is narrower: control the chain that matters for one author's work
reaching his own readers (publish format → distribution → reading
experience) while keeping the infrastructure boring.

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
sort-tier convention, author pages, rescan safety). A frozen copy of
ARKster's docs used to be kept here as reference; it was removed
(2026-11-10) once it went stale — ARKster has since moved on and closed most
of what it described. These origins are now just history: if a bug in this
inherited logic resurfaces, it's ARKarium's own to diagnose and fix against
this repo's code, not against ARKster's (possibly outdated) account of it.
ARKarium itself is proprietary. If code from a GPL project is ever carried
over, audit copyright ownership and dependency licenses first.

## Where to go next

- How it is built: [`architecture.md`](architecture.md)
- Why it is built that way: [`design-notes.md`](design-notes.md)
- Ideas not yet built: [`../proposal/`](../proposal/README.md)
