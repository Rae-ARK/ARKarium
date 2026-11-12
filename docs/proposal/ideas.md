# Ideas and feature backlog

> **Kind:** proposal · **Status:** untouched, documented only · **Verified:** 2026-11-10 @ (this patch)

Small items live here. An item that needs real design gets its own file in this folder and a row in the index. Promote to
[`../implementation/`](../implementation/README.md) when someone commits to building it.

| Idea | Notes |
|---|---|
| TTS sleep timer | Stop read-aloud after a chosen time. A "Timer" row in the reference app's TTS settings; also called out as table stakes in [`peer-app-survey.md`](peer-app-survey.md). The service already has `stop()`; the open part is the UI and whether "end of chapter" is an option. |
| TTS multilingual mode | A "Multilingual mode" switch in the reference app, for books that mix languages. ARKarium speaks a whole chapter in one voice, so Japanese inside an English chapter is read by an English voice. Would mean choosing a voice per chunk, probably from the text's script. Related to, and much smaller than, [`tts-multivoice.md`](../far-future-concern/tts-multivoice.md). |
| Chapter-content search | Library search matches title and author only. Fine for a few novels, weak once a dozen 400-chapter novels are synced. |
| EPUB/PDF ingestion | Not implemented. ARKster sketched a staged plan for this (`epub://` source scheme, `epublib` LGPL suggestion) but it describes ARKster's own schema, not ARKarium's, and would need to be re-specified from scratch against ARKarium's schema and sync manifest if picked up. |
| Publisher pipeline (ARKlight) | Manifest generation is a throwaway script. A maintained publish-side tool is needed before a multi-author ecosystem. A diff preview and a hidden/draft publish channel, modeled on itch.io, are suggested in [`peer-app-survey.md`](peer-app-survey.md) Suggestion 4; pairs with [`manifest-signing.md`](manifest-signing.md) D5 (sign at publish time, in this same pipeline). |
| Background sync beyond new-chapter checks | Only novels with the "notify me" toggle are checked in the background today. Sync-all, scheduled full sync not built. An Updates tab / unread badges / display modes, borrowed from the Mihon-family apps, are suggested in [`peer-app-survey.md`](peer-app-survey.md) Suggestion 2. |
| Auth / paid-content gating | None; everything synced is public. Needs a server-side check: see [`backend-social.md`](../far-future-concern/backend-social.md). |
| Reading extras | Illustrations in chapters, maps, character profiles, lore/reference panels, interactive elements, audio assets, pronunciation dictionaries, custom typography, per-chapter presentation. Collapsible/TTS-skippable author notes and author-owned support links are a narrower, cheaper version of this, see [`peer-app-survey.md`](peer-app-survey.md) Suggestion 1. |
| Serial-fiction manifest metadata | Publication status (ongoing/hiatus/dropped/complete), word count and read time per chapter, and manifest-level announcements (distinct from the in-chapter `<news>` callout). See [`peer-app-survey.md`](peer-app-survey.md) Suggestion 1. |
| Versioned editions / offline downloads | Beyond current manifest `version`. |
| Other clients | Desktop (ARKium-derived), iOS. Same content format. |
| Comments, ratings, reading-position sync | Needs a small backend: see [`backend-social.md`](../far-future-concern/backend-social.md). A sidecar `progress.json` and OPDS Progression's GET/PUT shape are backend-free alternatives worth weighing alongside it for same-network/same-account devices specifically — see [`peer-app-survey.md`](peer-app-survey.md) Suggestion 3. |
