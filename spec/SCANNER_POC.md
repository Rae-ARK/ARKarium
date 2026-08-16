Scanner POC — heuristics, UX and fingerprints
=============================================

Goals
- Provide a fast, progressive scan UX for messy local folders.
- Produce a normalized index (Room) and allow one-time manual fixes.
- Be resilient to inconsistent filenames and nested volumes/arc folders.

Heuristics (ranked)
1. Leading number + delimiter: `^\s*(0*\d{1,4})\D+(.+?)` catches `01 - Title`, `001.Title`, `12_The Fall`.
2. `Chapter` prefix: `Chapter\s*(\d+)\D+(.+)` for `Chapter 12 - Title`.
3. Pure numeric filenames: `012.txt` -> derive number 12 and title from parent folder or `Chapter 12`.
4. Filename tokenization: replace `_`/`-`/`.` with spaces, strip digits-only names, title-case the result.
5. EPUB/PDF TOC mapping where available.

Fingerprinting
- Keep per-source fingerprint: `uri`, `lastModified`, `size`. If DocumentFile doesn't provide `lastModified`, store a hash of sampled bytes (cheap) as fallback.
- On rescan: compare fingerprint to decide whether to reparse chapters or reuse cached Room rows.

Progressive scan UX
- Run the scanner in a background coroutine/worker and stream `ScanEvent`s.
- UI should show discovered items as cards appear and a small progress indicator (e.g., "scanning 12/40 novels...").
- Surface non-fatal parsing warnings on the novel card and offer a diagnostics view to bulk-fix or run a normalization pass.

Graceful degradation
- Auto-generate covers from title initial + hash-derived color when no image found.
- Best-effort title-casing and whitespace normalization.
- Allow in-app override (persisted) for chapter order/titles.

Manual repair workflow
- On the Novel page: an "Edit" action to reorder chapters, rename a chapter, or mark items as volume/arc.
- Persist edits into Room as overrides layered on top of the scanned index.
