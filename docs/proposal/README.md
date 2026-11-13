# Proposal

Ideas, planned features, open bug reports, and UI/UX issues that are
documented but **not being worked on**. This is the **future**.

| Doc | Kind | Holds |
|---|---|---|
| [`ideas.md`](ideas.md) | backlog | Small features and gaps, one row each. |
| [`reader-settings.md`](reader-settings.md) | feature | A "Reader" settings page: which of a reference app's six reader switches fit ARKarium, and what must exist first. The cheap batch (defaults, font family, keep-screen-on, fullscreen, opt-in brightness slider) is built - see [`architecture.md`](../foundational/architecture.md)'s "Reading and local state"; this doc now covers what's left (multi-document, day/night button, volume slider, column count, bookmarks). |
| [`notice-board.md`](notice-board.md) | feature | A persistent "which fictions got new chapters" log screen, closing the gap left by today's transient-only notification; phase 2 (sketched, not designed) turns it into a launcher home-screen widget. |
| [`bugs/`](bugs/README.md) | reports | Open bugs, split into `critical/` and `other/`. |
| [`engineering-review.md`](engineering-review.md) | review | Findings index (F1–F28) from a code-plus-research review; links out to the detail docs below. |
| [`data-safety.md`](data-safety.md) | review detail | F1, F3, F23: Room's destructive migration fallback, `novelId` identity, downgrade crash-loop. |
| [`manifest-signing.md`](manifest-signing.md) | review detail | F4: the sync manifest proves integrity, not authenticity. |
| [`performance.md`](performance.md) | review detail | F5, F12–F15, F24–F28: SAF I/O cost, build/startup, recomposition, images, missing FK indexes, scan write/listing cost, streak calculation. |
| [`reader-playback.md`](reader-playback.md) | review detail | F9, F10, F22: reading-position storage, TTS's composable-scoped lifecycle, audio focus/`MediaSession`. |
| [`animation-and-polish.md`](animation-and-polish.md) | review detail | F16–F17, F19: animation, predictive back/edge-to-edge, reading typography. |
| [`peer-app-survey.md`](peer-app-survey.md) | review | Feature/UX survey of peer reading apps (Mihon family, Readest, KOReader/OPDS, itch.io) against ARKarium's own stated principles. |

**Rules for this folder**

- Nothing here is promised or built. Claims about "today" must still be true (they are checked like any other doc).
- Promote to [`../implementation/`](../implementation/README.md) when someone commits to building it: `git mv` it, then split into rungs.
- Delete a proposal that is rejected or obsolete; git history keeps it.
- Fixed bugs and issues are deleted, not struck through.