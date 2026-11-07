# Proposal

Ideas, planned features, open bug reports, and UI/UX issues that are
documented but **not being worked on**. This is the **future**.

| Doc | Kind | Holds |
|---|---|---|
| [`ideas.md`](ideas.md) | backlog | Small features and gaps, one row each. |
| [`tts-multivoice.md`](tts-multivoice.md) | feature | Character-aware multi-voice TTS (the planned differentiator). |
| [`relay-chain.md`](relay-chain.md) | feature | Multi-relay fallback. |
| [`backend-social.md`](backend-social.md) | feature | Gating, comments, ratings, position sync without owning a backend. |
| [`bugs.md`](bugs.md) | reports | Open bugs. |
| [`uiux.md`](uiux.md) | reports | Open UI/UX issues. |

**Rules for this folder**

- Nothing here is promised or built. Claims about "today" must still be true (they are checked like any other doc).
- Promote to [`../implementation/`](../implementation/README.md) when someone commits to building it: `git mv` it, then split into rungs.
- Delete a proposal that is rejected or obsolete; git history keeps it.
- Fixed bugs and issues are deleted, not struck through.
