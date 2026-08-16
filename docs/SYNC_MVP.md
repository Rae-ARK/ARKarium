# Sync MVP — manifest + hash diff + static host

> **Status:** Stages 1–3 implemented. Stage 4+ (multi-relay fallback, auth,
> background sync, TTS metadata) remains design-only — see "Deliberately
> punted" below.
> **Relationship to `docs/ARKarium_Idea.md`:** this is the load-bearing subset of
> that doc's "Static relay architecture" and "Manifests and synchronization"
> sections, cut down to what's needed for a first working version. Multi-relay
> fallback chains, auth/paid content, and semantic TTS metadata are explicitly
> out of scope here — see "Deliberately punted" below.

## Implementation status

- **Stage 1 (schema)** — `SyncedFileEntity` table, `sync_source_url` /
  `sync_source_version` / `last_synced_at` columns on `NovelEntity`,
  migration 8→9, `SyncedFileDao`, `NovelDao.updateSyncState`. See §5.
- **Stage 2 (sync engine)** — `SyncManager.kt`: `SyncClient` (manifest
  fetch/parse), `SyncPaths.sanitize` (path-traversal guard, Consideration #2),
  `SyncManager.downloadInitial` / `.sync` (hash diff, verified download,
  stale-file deletion, Considerations #1 and #4). Never touches Room directly.
- **Stage 3 (UI wiring)** — "Add fiction from URL" (Settings → prompts for a
  relay base URL → `SyncManager.downloadInitial` → the same `startScan` pass
  every other novel goes through → sync bookkeeping attached once the scan
  assigns the folder a real novel id) and "Check for updates" (a per-novel
  action on `NovelDetailScreen`, shown only once a novel has a
  `syncSourceUrl` → `SyncManager.sync` → wholesale `SyncedFileEntity`
  replacement → rescan only if something actually changed). Both are plain
  manual, foreground actions — no background/scheduled sync yet (see
  "Deliberately punted").

## Why cut it down

The idea doc's relay-chain-with-fallback story is real, but it's v2 polish. The
actual load-bearing idea is just:

> manifest + hash diff + static host

That's genuinely simple to build, and proving it out is more valuable right now
than building resilience infrastructure for a distribution network that doesn't
have any content flowing through it yet.

---

## 1. Manifest format

Unchanged from the idea doc, minus the relay-chain field:

```json
{
  "version": 12,
  "files": {
    "arcs/arc-1/001.txt": { "size": 18472, "sha256": "..." },
    "cover.png": { "size": 40211, "sha256": "..." }
  }
}
```

No `"next"` relay field for MVP — a novel points at exactly one base URL
(single-origin). `version` is a plain incrementing integer the publisher bumps
on every change; the client only needs to know "did this go up," not parse or
diff it semantically.

## 2. Relay = a static host, nothing else

Cloudflare Pages, an R2 bucket, GitHub Pages, or any plain HTTP static host
works. It serves the exact same folder layout the scanner already understands
(`metadata.json`, `cover.*`, `arcs/.../*.txt`) plus `manifest.json` at the
root. No Worker, no redirect logic, no dynamic behavior of any kind — that
machinery is only needed once there's an actual *chain* of relays to fall
back through, which is out of scope for v1.

## 3. Client sync, deliberately dumb

1. Fetch `manifest.json` from the novel's base URL.
2. Compare its top-level `version` against the last-synced version for this
   novel. If unchanged, stop — no further requests.
3. Otherwise, diff the `files` map against what was last synced (see §6 on
   why this needs its own small table rather than reusing `ScanFingerprintEntity`).
4. Download anything new or changed, verify the hash, write it into local
   storage.
5. Remove anything that was previously synced but is no longer listed in the
   new manifest (see Consideration #1 below — this step is easy to omit by
   accident and was omitted from the first pass of this design).

No delta/binary patching, no partial-file resume — whole-file replace on any
mismatch. Chapters are tiny `.txt` files; bandwidth waste at this scale is a
non-issue.

## 4. Reusing the existing scan pipeline

Because of the SAF-folder-selection work already in the app, the sync target
*is* the same library folder `ScannerImpl` already reads from (either the
user-picked SAF tree or the app's own private storage — see
`MainActivity.resolveLibraryRoot`). A synced novel isn't a special case: the
sync client writes files into a folder under that same root, and
`ScannerImpl.scanRoot` discovers it exactly like a manually-dropped-in folder
— arcs, chapters, `chapter_overrides`, author linking, all of it, for free.
No new scanning logic, no separate "remote novel" rendering path in the UI.

Concretely: "Add fiction from URL" creates a folder, downloads the manifest's
files into it, and then triggers the exact same `startScan` pass that already
runs on every cold launch. The newly-synced folder is discovered the same way
any other novel folder is, and gets assigned the same deterministic
`novelId` scheme `ScannerImpl` already uses
(`UUID.nameUUIDFromBytes(root.uri + ":" + folder.uri)`). This means sync
tracking never has to invent or persist its own novel identity — it can
always re-derive "which folder does this synced novel live in" from
`libraryRoot` + `novelId` alone, in either storage mode, without holding onto
a `DocumentFile` reference across app restarts.

## 5. Minimal new surface area

- `NovelEntity` gains three nullable columns: `sourceUrl` (null = purely
  local), `sourceVersion` (last-synced manifest version, for the cheap
  skip-if-unchanged check), `lastSyncedAt`.
- New `SyncedFileEntity(novelId, relativePath, sha256, size)` table, one row
  per file this app has actually written for a given synced novel. This is
  the source of truth the diff in §3 runs against — see Consideration #1 for
  why it can't just be inferred from `ScanFingerprintEntity`.
- One new screen/action: "Add fiction from URL" — paste a relay base URL,
  runs the initial sync.
- One button: "Check for updates," per-novel (and/or a global "sync all" in
  Settings) — re-fetches the manifest, diffs, applies changes.

## 6. Deliberately punted to later

- Multi-relay fallback chain (the "Relay A → Relay B → Relay C" resilience
  story from the idea doc).
- Any auth/paid-content gating.
- ARKlight as a real publish-side pipeline — for MVP, generating
  `manifest.json` can be a throwaway script that walks a folder and writes
  out sha256s. It doesn't need to ship as part of the app.
- Background/scheduled sync — manual button only.
- Semantic TTS metadata riding along in the manifest — much later, and wants
  the manifest format itself proven out first.

---

## Future considerations (raised in design review, not yet built)

These don't block starting on the MVP as scoped above, but are cheap to bake
in now and expensive to retrofit once synced novels exist in the wild. Listed
here so they aren't silently lost between the idea and the implementation.

1. **Deletions must be tracked explicitly, not inferred.** The naive version
   of the diff in §3 only covers new/changed files. If a publisher deletes or
   renames a chapter, a diff that only checks "is this manifest path already
   present locally" never notices — it just doesn't re-download something
   already there, and the orphaned old file sits in the folder forever
   (worse, a rename looks identical to "add a new chapter" and leaves the old
   one behind indefinitely). `SyncedFileEntity` needs to be the diff's source
   of truth on *both* sides: download paths present in the new manifest but
   absent from `SyncedFileEntity`, **and** delete paths present in
   `SyncedFileEntity` but absent from the new manifest. This is why sync
   needs its own tracking table rather than reusing `ScanFingerprintEntity`
   (which fingerprints a whole novel folder for rescan-skip purposes, not
   per-file provenance).

2. **`relativePath` from the manifest is untrusted input.** A manifest is
   just a JSON file on a host outside this app's control. Writing its
   `files` keys directly into the SAF/local folder without validating they
   contain no `..` segments, no leading `/`, and no backslashes is a real
   path-traversal risk the moment "Add fiction from URL" accepts an arbitrary
   URL. This needs a sanitizer in the sync client itself, applied before any
   path is used to create a file or directory — not deferred to a later
   hardening pass.

3. **Sync-target reuse has one sharp edge.** Writing synced content into the
   same folder the scanner already reads is the right call (see §4), but it
   means: (a) a synced novel and a manually-dropped-in novel could collide if
   they land on the same folder name, and (b) a resync's whole-file-replace
   could silently clobber a local hand-edit to a `.txt` file the user made
   directly, outside the app. Manual *overrides* (the `chapter_overrides`
   Room table — title/position edits made through the app's own editor)
   survive fine regardless, since sync never touches that table. Manual
   *file edits* made directly to a synced file would not survive a resync.
   For MVP this is an accepted, explicitly-stated tradeoff rather than a
   silently-inherited bug — sync should only ever create/overwrite/delete
   files it itself wrote (tracked via `SyncedFileEntity`), and should never
   touch a file in a synced folder that isn't in that table, which limits
   the blast radius of (b) to files sync actually owns.

4. **No defined behavior for a sync that fails partway through.** "Check for
   updates" fetching a manifest over a flaky connection, or dying mid-way
   through a multi-file download — what does `SyncedFileEntity` look like
   afterward? Given the product's stated local-first/offline-first value
   (see `docs/ARKarium_Idea.md`), a sync that aborts partway through should
   never leave the tracking table claiming a file was synced when it wasn't,
   or vice versa. This doesn't need a full transactional design for MVP, but
   the failure mode shouldn't be silently ignored either — at minimum, only
   commit `SyncedFileEntity` rows for files that were actually verified
   (hash-checked) and written successfully, and leave `sourceVersion`
   un-bumped until every file in that sync pass has succeeded.

5. **Track the manifest `version` on `NovelEntity` itself.** Without storing
   the last-synced `version` somewhere queryable per-novel, "check for
   updates" has no cheap way to skip a no-op sync — it would have to
   re-fetch and re-diff the full file list every time just to discover
   nothing changed. Storing `sourceVersion` on `NovelEntity` (§5) makes the
   common case ("nothing changed since last check") a single manifest fetch
   and an integer comparison, not a full re-hash.

None of the above changes the shape of the MVP in §1–§5 — they're
constraints on how it's built, not scope additions. Items 1 and 2 in
particular should be part of the first implementation rather than a
follow-up, since they're cheap now and become a migration/compat problem
once real synced novels exist in someone's library.

## Open question

"Add fiction from URL" as manual URL paste is the MVP entry point. A nicer
version (QR code, deep link from a discovery website) is just a different
front door onto the same downloadInitial/sync flow described above, so it
isn't a blocker — it can be layered on without touching anything in §1–§6.
