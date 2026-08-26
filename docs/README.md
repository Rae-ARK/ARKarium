# Docs index

This folder is split in two, on purpose, so it's never ambiguous which
project a doc describes:

## `arkarium/` — this app's own docs

Live design/status docs for **ARKarium itself**. If you're working on this
codebase, this is what you read and what you keep updated.

- [`IDEA.md`](arkarium/IDEA.md) — the founding vision: direct author-to-reader
  distribution, static relay architecture, manifests/sync, the TTS "killer
  feature," licensing philosophy.
- [`SYNC_MVP.md`](arkarium/SYNC_MVP.md) — the cut-down, actually-implemented
  version of sync (manifest fetch, hash diff, single-origin-by-name).
- [`FUTURE_PLANS.md`](arkarium/FUTURE_PLANS.md) — gap analysis against the v1
  vision, plus the TTS architecture first pass.
- [`NEXT_FIXES.md`](arkarium/NEXT_FIXES.md) — known sync-robustness issues.
- [`SETTINGS_REDESIGN.md`](arkarium/SETTINGS_REDESIGN.md) — one-page-per-option
  Settings redesign plan, plus a new Text-to-Speech settings page for TTS
  controls that don't belong in the reader's Reader Preferences pill.
  Stages 0-2 done; Stage 3 (the TTS page) in progress, split into
  sub-stages 3.1-3.5 - see the doc for the staged rollout.

## `reference/arkster/` — predecessor project, historical reference only

[ARKster](https://github.com/Rae-ARK/ARKster) is a separate, earlier,
**GPLv3** Android reader project this app's `bugs.md`/`AUTHOR_PAGE_AND_
CHAPTER_REDESIGN.md`/`EPUB_SUPPORT.md` source comments trace back to. These
copies are **frozen prior art**, not live ARKarium specs:

- They describe ARKster's own code (`com.arkster.app` package, ARKster's
  own file layout), not ARKarium's.
- They are **not maintained as part of this repo** — don't edit them here;
  if ARKster's docs change upstream, re-pull them.
- Anything from here that becomes relevant to ARKarium should be
  re-specified as an ARKarium-native doc under `arkarium/`, not by editing
  these copies in place.

See [`reference/arkster/README.md`](reference/arkster/README.md) for exactly
what's here and why each file was pulled in.

### Why these three specifically

Several `app/src` comments in this repo cite `bugs.md` (rescan/dedup/sort-tier
bug history) and other docs that don't exist in *this* repo — because the
underlying logic (chapter sort-tier convention, rescan-safety fix, author
pages) was carried over from ARKster, where these docs live. Pulling them in
here as reference resolves the dangling pointers without inventing history
that wasn't actually written down.

`app/README.md`'s "Known limitations" pointer to EPUB support now resolves to
[`reference/arkster/EPUB_SUPPORT.md`](reference/arkster/EPUB_SUPPORT.md) —
ARKster's staged plan for it. It hasn't been built or even re-specified for
ARKarium yet; treat it as prior art to adapt from, not a ready-to-implement
ARKarium doc (schema/package names, sync-manifest integration, etc. would all
need re-deriving for this app's actual architecture).
