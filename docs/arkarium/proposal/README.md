# Proposal

Ideas, planned features, open bug reports, and UI/UX issues that are
documented but **not being worked on**. This is the **future**.

| Doc | Kind | Holds |
|---|---|---|
| [`ideas.md`](ideas.md) | backlog | Small features and gaps, one row each. |
| [`tts-multivoice.md`](tts-multivoice.md) | feature | Character-aware multi-voice TTS (the planned differentiator). |
| [`tts-voice-picker.md`](tts-voice-picker.md) | feature | In-app single-voice/language picker for `settings/tts` ("Change voice" currently only links out to system settings and does nothing). |
| [`relay-chain.md`](relay-chain.md) | feature | Multi-relay fallback. |
| [`backend-social.md`](backend-social.md) | feature | Gating, comments, ratings, position sync without owning a backend. |
| [`bugs/`](bugs/README.md) | reports | Open bugs, split into `critical/` and `other/`. |
| [`uiux.md`](uiux.md) | reports | Open UI/UX issues. |
| [`engineering-review.md`](engineering-review.md) | review | Findings index (F1–F21) from a code-plus-research review; links out to the detail docs below. |
| [`data-safety.md`](data-safety.md) | review detail | F1–F3: Room's destructive migration fallback, missing schema/tests, `novelId` identity. |
| [`manifest-signing.md`](manifest-signing.md) | review detail | F4: the sync manifest proves integrity, not authenticity. |
| [`performance.md`](performance.md) | review detail | F5, F12–F15: SAF I/O cost, build/startup, recomposition, images. |
| [`robustness.md`](robustness.md) | review detail | F6–F8, F11, F18, F20: cancellation, silent failure, duplicate DB instances, text splitting, WebView, a11y. |
| [`reader-playback.md`](reader-playback.md) | review detail | F9–F10: reading-position storage and TTS's composable-scoped lifecycle. |
| [`animation-and-polish.md`](animation-and-polish.md) | review detail | F16–F17, F19: animation, predictive back/edge-to-edge, reading typography. |

**Rules for this folder**

- Nothing here is promised or built. Claims about "today" must still be true (they are checked like any other doc).
- Promote to [`../implementation/`](../implementation/README.md) when someone commits to building it: `git mv` it, then split into rungs.
- Delete a proposal that is rejected or obsolete; git history keeps it.
- Fixed bugs and issues are deleted, not struck through.
