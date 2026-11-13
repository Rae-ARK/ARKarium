# Catalogue Browse: discovering fictions before you've followed them

> **Kind:** implementation · **Status:** implemented, Rungs 1-7 done · **Verified:** 2026-11-13 @ 64db766
> **Origin:** owner request - Browse should show every fiction Rae ARK publishes,
> not just the ones already synced into the library, with a Follow step that
> gates reading. Promoted from `proposal/` once the field list below was
> corrected (see "Catalogue ingestion" below) and staged into rungs.

Per [`what-is-arkarium.md`](../foundational/what-is-arkarium.md): this is a
catalogue of **one author's own fictions**, not a discovery surface across
authors. "Browse" here means "see what Rae ARK has published that you
haven't followed yet," never "explore what's out there" - there's nothing
else out there for this app to show.

## What's built today

`FictionBrowseScreen` (`ui/FictionBrowseScreen.kt`) only ever renders
`libraryViewModel.novels` - i.e. fictions already scanned into the library,
whether by hand, by "Add fiction," or by "Sync all Rae ARK's novels." There is
no "discover something you don't have yet" surface: you either already know a
fiction's exact title to type into "Add fiction" (`SyncDialogs.AddFictionByNameDialog`),
or you run "Sync all Rae ARK's novels" and download everything at once.

Two things already do almost all of the work this proposal needs:

- **`FictionLut.allEntries(context)`** is, today, the full list of every
  fiction the relay serves - "the relay only ever hosts Rae ARK's own
  fictions... this doubles as all of Rae ARK's novels," per its own doc
  comment. No network call needed to know what exists; it's bundled in the APK.
- **`SyncViewModel.addFictionByName(name, libraryRoot)`** is already the
  complete fetch-manifest -> download-every-file -> scan -> persist pipeline,
  driving `AddFictionState` (`Hidden` / `EnteringName` / `InProgress` /
  `Error`), rendered by the existing `AddFictionByNameDialog` via
  `AppDialogHost`.

So "show me what I don't have yet, let me start reading it" is mostly a
**presentation** problem, not a new sync engine: the relay, the manifest
format, and the add-fiction pipeline don't need to change.

## What's proposed

### Catalogue ingestion - no new relay endpoint

For every `(displayName, slug)` `FictionLut` already knows about that **isn't**
already in the library (no `NovelEntity` whose `syncSourceUrl` equals
`relayBaseUrlForSlug(slug)`), fetch that fiction's own `manifest.json` and, if
listed, its `metadata.json` - exactly the two files sync already fetches for a
followed novel, just without downloading every chapter file behind them:

- `SyncClient.fetchManifest(baseUrl)` (already exists) for the file list and
  `version`.
- `SyncClient.downloadFile(baseUrl, "metadata.json")` (already exists,
  `downloadFile` is generic) if `metadata.json` is one of the manifest's
  entries - same optional, all-fields-optional schema `ScannerImpl.readLocalMetadata`
  already parses locally (`title`, `author`, `authorId`, `description`,
  `genres`, `publishedDate`). No new publisher-side file format.
- The root-level `cover.*` entry (same "Content format" convention
  architecture.md already documents), downloaded the same way.
- **Chapter count** - count manifest entries matching `arcs/*/*.txt` at the
  top level of their arc folder. This is a count, not a table of contents:
  the manifest has no per-chapter titles, only relative paths, so a catalogue
  entry can say "42 chapters" but not what chapter 17 is called.

This deliberately reuses the one existing relay shape (`RELAY_HOST + slug +
"/"`, manifest + optional metadata.json + cover) instead of asking for a new
aggregate "catalogue.json" from the publisher side.

The earlier framing of this idea (views/favorites/readers/reviews,
ScribbleHub-shaped stats) stays dropped entirely - ARKarium is a
direct-to-reader app for one author's own readers, not a storefront
aggregating platform metrics, and that kind of data needs reader-side
telemetry no backend here collects, not publish-time content. That's a
structural gap, not a missing-field one, so it doesn't close just because
the manifest grows.

**Total word count, publication status and release cadence are different** -
genuinely real, computable from the manifest as of the "Serial-fiction
manifest metadata" work (`PublicationStatus`, `ChapterManifestMetaEntity.
wordCount`, `Manifest.chaptersPerWeek` - see `NovelEntity`'s matching
columns). A catalogue entry can show:

- **Total words** - sum of `wordCount` across the manifest's chapter entries;
  `null`, not a fabricated `0`, when none of them have it set.
- **Status** - `PublicationStatus` name straight off the manifest's `status`.
- **Chapters/week** - the author's own declared cadence
  (`Manifest.chaptersPerWeek`), *not* computed from real publish timestamps -
  the manifest has no per-chapter publish time, only a bumped `version`
  integer, so this is "what the author says," same trust level as `status`,
  not a derived figure.

### Storage

A small new Room table, matching how every other structured list in this app
is held (`architecture.md`'s Persistence layer), rather than a cache
re-parsed from disk on every Browse open:

```kotlin
@Entity(tableName = "catalogue_entries")
data class CatalogueEntryEntity(
    @PrimaryKey val slug: String,
    val title: String,
    val author: String?,
    val description: String?,
    val genres: String?,
    val coverPath: String?,     // file:// path under the app's private cache, below
    val chapterCount: Int,
    val publishedDate: String?,
    // New vs. this doc's original sketch - see "Catalogue ingestion" above.
    val totalWordCount: Int?,      // sum of chapter wordCount; null if none set
    val status: String?,           // PublicationStatus name
    val chaptersPerWeek: Double?,  // author-declared cadence
    val cachedAt: Long          // when this row was last refreshed - see C3
)
```

Covers and `metadata.json` are written to the app's own **private internal
storage** (`context.filesDir/catalogue/<slug>/`), not the SAF-based library
root (`Android/media/com.arkarium.app/`) the scanner walks, and not the
`Android/data/.../catalogue/` path floated in the original idea. Three
reasons for that change:

1. It needs no storage permission or SAF grant at all - a catalogue entry
   isn't part of the user's library yet, so it shouldn't need the same
   access the real library content does.
2. `ScannerImpl.scanRoot` only ever walks the library root; keeping
   catalogue files fully outside that tree means zero risk of a
   not-yet-followed fiction's leftover files ever being misread as a real,
   locally-added novel.
3. It's fully disposable - every byte under `catalogue/` is re-derivable from
   the relay at any time, the same relationship `FictionLut`'s own decrypted,
   in-memory-only cache has to its encrypted asset. Deleting the app's data
   should lose nothing that can't just be re-fetched.

### Catalogue invariants

Same convention as the Sync invariants (`S1`-`S10`) in architecture.md -
each one is a decision, not an accident:

- **C1. A slug is untrusted input before it's a folder name.** It comes from
  a bundled asset built by our own tooling, but `slug` still goes through
  `SyncPaths.sanitize` (already exists, already used for manifest paths)
  before it's ever used to build `catalogue/<slug>/` - defense in depth, not
  a response to a known bad slug.
- **C2. A catalogue entry disappears the moment its fiction is followed.**
  Once `addFictionByName` succeeds for a slug, the corresponding
  `catalogue_entries` row (and its cached files) is deleted rather than kept
  around - there is exactly one on-disk representation of a fiction you have:
  the real synced library folder, not a stale duplicate under `catalogue/`.
- **C3. Staleness is visible, not silently trusted.** `cachedAt` is when this
  row was last actually re-fetched; nothing here claims it reflects the
  relay's current state beyond that point. See "Explicitly out of scope"
  below for what a real "recently updated" signal would need.
- **C4. A catalogue fetch failure for one fiction doesn't block the rest.**
  Same shape as `syncAllRaeArkNovels`' per-fiction `SourceGoneException`
  handling - one 404 or network hiccup skips that slug for this pass; it
  isn't retried until the next refresh, and doesn't abort the others.

### Browse screen changes

`FictionBrowseScreen` renders a merged grid: library novels (today's
behavior, unchanged) plus not-yet-followed `CatalogueEntryEntity` rows. A
catalogue card:

- Shows cover, title, author, same as `EnhancedNovelCard` - but **no status
  badge** (there's no reading status before you've followed it) and **no
  star row** (there was never real rating data backing that row even for
  library novels - see the existing engineering note on `EnhancedNovelCard`;
  this proposal doesn't invent one for catalogue entries either).
- Tapping it opens the fiction's detail page in preview mode (below), not
  the reader - same navigation shape as tapping a library novel.

Sort: `MOST_POPULAR` and `HIGHEST_RATED` don't apply to catalogue entries for
the same reason they were already flagged as placeholders for library
novels - there's no population of ARKarium readers to be popular among, and
nothing in the manifest/metadata format carries a platform rating. Realistic
options for a merged list are `NEWEST` (by `publishedDate` where present) and
alphabetical. A true `RECENTLY_UPDATED` would need the relay to carry some
notion of "when did this version actually publish" - it currently doesn't
(`manifest.json`'s `version` is just a bumped integer, no timestamp) - this
is the same gap `ideas.md`'s "Serial-fiction manifest metadata" row already
flags, not a new one.

### Novel detail screen changes

`NovelDetailScreen` already gates most of what a catalogue preview needs
behind existing nullable params - `onSyncClick`, `onToggleNotify` - so this
is additive, not a rewrite:

- A catalogue-preview mode (how the caller signals it: e.g. passing
  `chapters = emptyList()` plus a new `catalogueChapterCount: Int?` used only
  to render the "N Chapters" header) shows the cover, title, author,
  description, genres - everything already in `CatalogueEntryEntity` - but
  the chapter-list area renders `catalogueChapterCount` greyed, unclickable
  placeholder rows (plain "Chapter 1" .. "Chapter N" - there are no real
  titles to show, see the chapter-count caveat above) instead of real
  `ChapterEntity` rows.
- The "Notify me when new chapters are available" toggle needs **no new
  code**: it's already only ever offered (`onToggleNotify != null`) for a
  synced novel, and a catalogue entry simply isn't one yet. It starts working
  the instant the real `NovelEntity` exists, same as it does for any other
  newly-added fiction today.
- **Follow is the existing "Add fiction" action, relabeled - not a new
  pipeline.** The primary button calls `syncViewModel.addFictionByName(title, libraryRoot)`
  exactly as `AddFictionByNameDialog` does today, reusing `AddFictionState`'s
  existing `InProgress`/`Error` rendering - no new progress UI. Follow
  triggers the full download immediately; chapters un-grey only once that
  pipeline actually finishes and the real `NovelEntity` + `ChapterEntity` rows
  exist, same as any other "Add fiction" completion today. A slug-based
  variant of `addFictionByName` that skips the redundant name-\>slug
  `FictionLut.lookup` (the catalogue entry already resolved it) is a cheap
  follow-up, not required for this to work.
- Once `addFictionByName` succeeds, the screen needs to stop being a
  catalogue preview and start being the real thing: the caller re-resolves
  whichever of (now-real `NovelEntity` exists for this slug) is true after
  `AddFictionState` returns to `Hidden`, and recomposes with real `chapters`
  loaded the normal way (`libraryViewModel.loadNovelDetails`). This is the
  one genuinely new piece of wiring in this proposal - everything else above
  is existing behavior viewed from a new angle.

### Navigation

No new destination - this stays on the existing Browse tab and the existing
`novelDetail/{id}` route, per the usual "follow the existing pattern instead
of inventing a parallel one" approach the rest of this app already takes.
The one open wrinkle: today `{id}` is always a real `NovelEntity.id`; a
catalogue entry has a `slug` instead. Simplest fix once this is actually
built: resolve `id` against `novels` first, falling back to
`catalogue_entries.slug` if nothing matches - not designed further here.

## Rungs

Each rung below is independently shippable and behavior-preserving up to the
point it lands - a rung marked `done` never leaves Browse or NovelDetail in a
half-working state for a user who hasn't followed anything new yet. Rungs
1-4 are backend/data-layer and independently testable without touching a
screen; none of 5-7 is shippable alone before Rung 4 exists (a Browse grid
showing catalogue cards with no deletion hook would leave a permanent
duplicate the moment someone actually follows one).

| Rung | Item | Depends on | Status | Target file(s) |
|---|---|---|---|---|
| 1 | `CatalogueEntryEntity` + `CatalogueEntryDao` + `MIGRATION_17_18` (schema above) | - | done | `Entities.kt`, `Dao.kt`, `AppDatabase.kt` |
| 2 | Catalogue ingestion pass - per-slug fetch manifest/metadata/cover, compute `chapterCount`/`totalWordCount`, upsert; one slug's failure never aborts the rest (C4) | 1 | done | `SyncManager.kt` (new `fetchCatalogueEntry(slug)`, reusing `fetchManifest`/`downloadFile`), `SyncViewModel.kt` (per-item loop, same shape as `downloadFiction`) |
| 3 | Private-storage writer (`context.filesDir/catalogue/<slug>/`) for cover + metadata.json, slug through `SyncPaths.sanitize` first (C1) | 2 | done | `CatalogueStorage.kt` (new), wired in `SyncViewModel.fetchCatalogueItem` |
| 4 | C2 deletion hook: `addFictionByName` deletes the matching `catalogue_entries` row (+ cached files) on success | 1 | done | `SyncViewModel.kt` (`dropCatalogueEntry`, also called from "Sync all"'s `downloadFiction`) |
| 5 | `FictionBrowseScreen`: merge into one `sealed BrowseItem` list; catalogue cards get no status badge, no star row; `NEWEST`/alphabetical sort only for catalogue items | 1 | done | `FictionBrowseScreen.kt` |
| 6 | `NovelDetailScreen` catalogue-preview mode (above) - status/cadence row reads `CatalogueEntryEntity.status`/`chaptersPerWeek` the same way it already reads `NovelEntity`'s | 1, 4, 5 | done | `NovelDetailScreen.kt` |
| 7 | Navigation fallback: `novelDetail/{id}` resolves `novels` first, falls back to `catalogue_entries.slug`; recompose in place (no new back-stack entry) once Follow succeeds | 6 | done | `MainActivity.kt` |

## Explicitly out of scope

- **Any ScribbleHub-shaped platform stat** (views, favorites, readers,
  reviews) - not fetched, not stored, not shown. If Rae ARK ever wants a
  "popular with ARKarium readers" signal, that needs ARKarium's own
  telemetry, which is a `backend-social.md`-sized question, not this
  proposal's.
- **A real "recently updated" signal** - blocked on a manifest timestamp
  field that doesn't exist yet (see Sort above); tracked by `ideas.md`'s
  existing "Serial-fiction manifest metadata" row, not re-specified here.
- **Scheduled/background catalogue refresh.** This proposal only covers an
  on-demand refresh (e.g. opening Browse, or a pull-to-refresh); whether it
  also deserves a periodic WorkManager pass like `NewChapterCheckWorker`'s is
  a separate decision.
- **Multi-author catalogues.** `FictionLut.allEntries` being "all of Rae
  ARK's novels" with no per-author filter is today's single-author reality,
  called out in the LUT's own doc comment; a real multi-publisher catalogue
  is squarely `ideas.md`'s "Publisher pipeline (ARKlight)" territory, not
  this proposal's.

## Open questions

- Exact `novelDetail/{id}` route handling for a slug with no `NovelEntity`
  yet (above) - needs a NavHost change, not just a screen-level one.
- Whether "Follow" or "Subscribe" is the label shown to readers - functionally
  identical either way; this doc doesn't pick one.
