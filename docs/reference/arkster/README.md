# ARKster docs — pulled in for reference

**These describe [Rae-ARK/ARKster](https://github.com/Rae-ARK/ARKster), a
separate, earlier, GPLv3-licensed Android reader project — not ARKarium.**
They're kept here, unmodified from source, because several things in
ARKarium's own code and docs trace back to design decisions made in ARKster
first, and the source comments referencing them (`bugs.md`, etc.) would
otherwise point at nothing.

Do not edit these in place. If you need to update them, re-pull from
ARKster's own `docs/` folder. If an idea from here becomes something
ARKarium needs going forward, write it as a new ARKarium-native doc under
`docs/arkarium/` instead — these are historical record, not a living spec
for this app.

## What's here and why

- **`bugs.md`** — Bug investigation + fix history across 5 stages: chapter
  sort ordering (the `~`/`!` bonus/closing filename-prefix convention that
  became ARKarium's `sortTier`), arc-cover filename matching, reader-page
  cover fallback chain, arc folder ordering, and the "rescan makes novels
  disappear" bug — the direct ancestor of what ARKarium's own source
  comments cite as **Bug 4** in `MainActivity.kt`/`ScannerImpl.kt`.
- **`EPUB_SUPPORT.md`** — ARKster's staged plan (Stage 0–4, not yet built
  even there) for EPUB ingestion: composite `epub://` sourcePath scheme,
  chapter identity via manifest id, cover extraction, and a recommendation
  to use `epublib` (LGPL) rather than hand-rolling OPF/NCX parsing. This is
  the doc `app/README.md`'s "Known limitations" section points at.
- **`AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md`** — the (in ARKster, fully shipped)
  design behind the `authors/` folder convention, author pages, and chapter
  Previous/Next navigation. ARKarium's own `authors/`-folder sync
  special-casing (see `docs/arkarium/SYNC_MVP.md`) assumes this convention
  already exists — this is where it was originally specified.

## What's deliberately *not* here

ARKster's other docs (`ARKster_ANDROID_DESIGN.md`, and the `done and dealt
with/` status summaries — `UI_OVERHAUL_SUMMARY.md`, `V0.2_COMPLETION_
SUMMARY.md`, `V0.2_ROADMAP.md`) weren't pulled in, since nothing in
ARKarium's own source or docs currently references them. If that changes,
add them here the same way, with the same "why" note.
