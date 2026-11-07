# Ideas and feature backlog

> **Kind:** proposal · **Status:** untouched, documented only · **Verified:** 2026-11-06 @ b4fcc2f

Small items live here. An item that needs real design gets its own file in this folder and a row in the index. Promote to
[`../implementation/`](../implementation/README.md) when someone commits to building it.

| Idea | Notes |
|---|---|
| Chapter-content search | Library search matches title and author only. Fine for a few novels, weak once a dozen 400-chapter novels are synced. |
| EPUB/PDF ingestion | Not implemented. Prior art (staged plan, `epub://` source scheme, `epublib` LGPL suggestion) in [`../../reference/arkster/EPUB_SUPPORT.md`](../../reference/arkster/EPUB_SUPPORT.md): it describes ARKster's schema and must be re-specified for ARKarium's schema and sync manifest. |
| Publisher pipeline (ARKlight) | Manifest generation is a throwaway script. A maintained publish-side tool is needed before a multi-author ecosystem. |
| Background sync beyond new-chapter checks | Only novels with the "notify me" toggle are checked in the background today. Sync-all, scheduled full sync not built. |
| Auth / paid-content gating | None; everything synced is public. Needs a server-side check: see [`backend-social.md`](backend-social.md). |
| Reading extras | Illustrations in chapters, maps, character profiles, lore/reference panels, interactive elements, audio assets, pronunciation dictionaries, custom typography, per-chapter presentation. |
| Versioned editions / offline downloads | Beyond current manifest `version`. |
| Other clients | Desktop (ARKium-derived), iOS. Same content format. |
| Comments, ratings, reading-position sync | Needs a small backend: see [`backend-social.md`](backend-social.md). |
