# ARKster — Author Page & Chapter Page Redesign (staged plan)

## Status
**Stage 5 — QA / polish, complete.** All prior stages (1: data layer, 2:
`AuthorPageScreen.kt`, 3: `ReaderScreen.kt` chapter page redesign, 4:
navigation & wiring) are also complete - the whole feature described in
this document has shipped. Stage 0's per-fiction `author.json` placement
was superseded at Stage 1 kickoff by a root-level `authors/` folder (see
"Where author.json lives" below).

## Motivation
ARKster's whole pitch (see `README.md`) is making a folder of novels *feel*
like a private Royal Road site. Two pieces of that experience are currently
missing:

1. **Chapter page parity.** Royal Road's chapter page has an "About the
   author" card under the chapter body (avatar, short bio, fiction count,
   a link into the full profile) plus Previous/Next chapter navigation.
   `ReaderScreen.kt` today has neither — only a back arrow and the
   font/spacing/reading-mode controls.
2. **No author identity.** `NovelEntity.author` is a bare string. There is no
   place to show who wrote a fiction beyond that one line on the fiction
   page, and nothing resembling Royal Road's author profile (stats,
   personal info, bio, links).

This plan closes both gaps using a new optional `author.json`, following the
same "optional, user-authored, never blocks a scan" pattern `metadata.json`
already established in `ScannerImpl.readLocalMetadata`.

## Where `author.json` lives
**Superseded from Stage 0:** not one `author.json` per fiction folder.
Instead, a single **`authors/` folder at the library root**, sibling to the
fiction folders, holding one JSON file per author:

```
MyLibraryRoot/
  authors/
    rae-ark.json       <- one author, id = filename ("rae-ark")
    rae-ark.jpg         <- optional avatar, referenced by rae-ark.json's "avatar"
    some-other-author.json
  Some Fiction/
    metadata.json       <- optional "authorId": "rae-ark" links this fiction to the author above
    cover.jpg
    0001 - Chapter One.txt
    Arc 1 - Whatever/
      ...
  Some Other Fiction/
    metadata.json        <- "authorId": "rae-ark" again - same author, two fictions
    ...
```

Rationale for the root-level `authors/` folder instead of per-fiction:
- One author routinely writes multiple fictions; a root-level folder lets
  every fiction that shares an author point at the *same* file instead of
  duplicating (and inevitably desyncing) bio/avatar/stats copies per folder.
- It directly answers Stage 0's "Open question 2" (author dedup): identity
  is now the JSON's filename/id, not a fuzzy name match, so two fictions by
  "Rae ARK" are unambiguously the same author without relying on exact
  string equality of a free-text name field. A name-based fallback still
  exists for fictions that don't set `authorId` (see below), but it's a
  fallback, not the primary mechanism.
- A single scanned library folder can still contain fictions by different
  authors - each just carries its own `authorId` (or none).

Like `metadata.json`, every part of this is optional. No `authors/` folder,
no `authorId` in a given fiction's `metadata.json`, or no name match: that
fiction simply has no author card / no author page entry point. Never a scan
failure - same guarantee `readLocalMetadata` already gives, extended to
`readAuthorMetadata`.

### Author identity: filename is the id, not a hand-validated field
Rather than asking the user to invent a globally-unique id and having the
scanner reject the scan if two files claim the same one, the id rides on
something the filesystem already guarantees is unique *within* `authors/`:
the filename. `authors/rae-ark.json` has id `rae-ark` unless the JSON itself
sets `"id"` to something else (useful if you want to rename the file later
without breaking existing `authorId` links).

If a JSON's own `"id"` override collides with an id already claimed earlier
in the same scan, that's the one edge case an id *can* still collide - the
first file wins, the later one is skipped, and it's surfaced through the
same `onProgress` soft-warning channel every other scan issue already uses
(lost permissions, skipped novel folders, etc). It never throws and never
aborts the scan, matching every other malformed-input case in `ScannerImpl`.

## `authors/<id>.json` schema (draft)
```json
{
  "id": "rae-ark",
  "name": "Rae ARK",
  "avatar": "rae-ark.jpg",
  "bio": "Short bio text, shown both truncated (chapter card) and in full (author page).",
  "joined": "2025-04-24",
  "location": "",
  "gender": "Female",
  "links": {
    "twitter": "https://twitter.com/handle",
    "website": "https://example.com"
  },
  "stats": {
    "followers": 6,
    "favorites": 1,
    "reviews_received": 1,
    "ratings_received": 3
  }
}
```

Notes:
- `id` is optional; defaults to the filename (see above).
- `avatar` is a filename relative to the `authors/` folder now (not the
  fiction folder), resolved the same way `findCoverUri` resolves `cover.*`.
- All other fields optional; absent fields just don't render (same
  philosophy as `metadata.json`'s title/author/description/genres/
  publishedDate).
- `stats` are **manually authored, display-only numbers** — this is an
  offline, no-accounts app (per README), so there is no real follower/review
  system to compute these from. They exist purely so the profile screen can
  visually match Royal Road's stat strip for users who want that; the doc
  and, later, the UI copy must make clear these are not live counts.
- Fiction count and total word count are deliberately **not** stored in the
  json — see "Open questions" below on how/whether those get computed
  instead of hand-maintained.

## Linking a fiction to an author
Each fiction's existing `metadata.json` gains one new optional field:
```json
{ "authorId": "rae-ark" }
```
Resolution order in the scanner, per fiction:
1. `metadata.json`'s `authorId`, if present and it matches an id found in
   `authors/` during this same scan.
2. Otherwise, `metadata.json`'s existing free-text `author` field, matched
   case-insensitively against `authors/*.json` `name` values.
3. Otherwise, no author link - identical to today's behavior.

## Staging order

### Stage 0 — Documentation (this change)
This file. Establishes the `author.json` contract and the stage sequence
below. No app code touched.

### Stage 1 — Data layer
**Decisions made at kickoff (superseding the open items below this doc had):**
- Storage is a new `authors` Room table + `AuthorDao`, keyed by the id
  described above, refreshed on every full rescan (parallel to how `arcs`
  are diffed/removed in `scanChaptersForNovel`).
- `NovelEntity` gets a new nullable `author_id` column (plain, not a Room
  `@ForeignKey` - see code comment on why: it's added via `ALTER TABLE` to
  an existing table, and SQLite can't retroactively attach an enforced FK
  constraint that way) resolved per the "Linking a fiction to an author"
  section above.
- `ScannerImpl.scanRoot` gains a `scanAuthorsFolder()` step that runs once
  per scan (not per-novel) and an `onAuthorsDiscovered` callback, mirroring
  the existing `onDiscovered`-does-the-DB-write style already used for
  novels rather than having `ScannerImpl` own a `db` reference for this path.
- The avatar file is resolved the same way `findCoverUri` resolves
  `cover.*`, just scoped to the `authors/` folder instead of the fiction
  folder.

### Stage 2 — Author page UI
**Decisions made at kickoff:**
- New `AuthorPageScreen.kt` composable: banner + overlapping avatar, name,
  a stat strip (Follows / Favorites / Reviews / Fictions - the first three
  are the manually-authored display-only numbers from `author.json`'s
  `stats`, rendered as "—" when unset rather than a misleading 0; Fictions
  is computed from the novels actually linked to this author, not stored),
  a "Personal Information" card (joined/location/gender/links - only
  rendered when at least one is set), a full (non-truncated) "About" card
  for `bio` when present, and an "Author Information" card. "Total Words"
  (Stage 0/1's open question 1) is still not shown - no `wordCount` exists
  anywhere to read yet, so this stays deferred rather than inventing a
  number.
- Royal Road's Follow/Block buttons and "Activity" feed are **omitted**,
  not kept as inert styling: there's no real follow relationship in an
  offline, account-free app to act on (see README), and an Activity card
  would have nothing genuine to show without fabricating data.
- The screen takes its `AuthorEntity` and the already-resolved
  `List<NovelEntity>` (via `NovelDao.byAuthor`) as plain parameters - it
  does not query the database itself, matching every other screen's
  stateless-composable style in this codebase.
- Not yet wired into navigation or given a real entry point - that's
  Stage 4, per the original staging order below.

### Stage 3 — Chapter page (`ReaderScreen.kt`) redesign
**Decisions made at kickoff:**
- Header row above the chapter body: fiction title as a "back to fiction"
  link (own `onBackToFiction: (Float) -> Unit` callback, same progress-on-
  exit shape as `onBack` so this exit path saves reading progress too) plus
  the arc name when the chapter belongs to one. Deliberately a *separate*
  callback from the existing top-app-bar `onBack` rather than repurposing
  it - `onBack` keeps its current caller-chosen destination (e.g. Home)
  unchanged; this stage doesn't touch that behavior.
- `ReaderScreen` gained `onPrevious`/`onNext: ((Float) -> Unit)?` - `null`
  (not just a no-op lambda) means "no such chapter", so the buttons render
  disabled at the first/last chapter instead of merely doing nothing when
  tapped. The nav row is rendered once above and once below the chapter
  body, both driven by the same two callbacks.
- Neighbor resolution lives in `MainActivity`, not `ScannerImpl` or
  `ReaderScreen` itself: it finds the current chapter's index in the
  already-loaded, override-applied `chapters` list (the same list
  `NovelDetailScreen` renders) and looks at index ± 1. This uncovered a
  pre-existing gap - the "Continue Reading" path on `HomeScreen` jumped
  straight into the reader without ever calling `loadNovelDetails()` first,
  unlike every other path into the reader, so `chapters`/`arcs` could be
  stale or empty there. Fixed as part of this stage (`onContinueReading`
  now calls `loadNovelDetails()` first) since Previous/Next would otherwise
  silently miscompute on that one entry point.
- "About the author" card at the end of the chapter, sourced from the same
  `AuthorEntity` Stage 2 reads (resolved once per fiction, in
  `MainActivity`, at the two points that create `Screen.Reader` - not
  re-resolved on every Previous/Next hop, since the author doesn't change
  within one fiction). Avatar + name + a bio excerpt truncated to 160
  characters (`AboutAuthorCard`'s `BIO_EXCERPT_LENGTH`), vs the untruncated
  bio Stage 2's author page shows. No linked author means no card, same as
  every other optional-metadata absence in this app.
- The card's tap target (`onAuthorClick`) is wired as a parameter but left
  at its no-op default in `MainActivity` - actually routing it to
  `AuthorPageScreen` is Stage 4's job (adding the nav route), not this
  stage's, matching how Stage 2 also stopped short of nav wiring.
- Existing font size / line spacing / reading-mode (Light/Sepia/Dark)
  controls stay as-is — those are ARKster reader features Royal Road's web
  chapter page doesn't have, and are out of scope for this change.

### Stage 4 — Navigation & wiring
- Add an `author/{...}` route to the nav graph in `MainActivity.kt`.
- Wire tap targets from `NovelDetailScreen` (fiction page byline) and the
  Stage 3 "About the author" card's `onAuthorClick` into that route.

**Implementation notes:**
- The route is `Screen.Author(authorId: String, from: Screen)` rather than a
  bare `object`/id-only route - it carries the screen it was entered from so
  `onBack` returns to wherever the tap came from (fiction page or chapter
  page) instead of always bouncing to Home, matching how every other screen
  in this app already round-trips back to its actual caller.
- `NovelDetailScreen`'s "by {author}" byline is only a tap target when
  `novel.authorId != null` - a fiction with no resolved author link renders
  the same plain, non-interactive text it always has (see Stage 3's "no
  linked author -> no card" precedent).
- `MainActivity` owns the author page's data the same way it already owns
  `chapters`/`arcs` for `NovelDetailScreen`: `authorPageAuthor`/
  `authorPageNovels` state, populated by a new `loadAuthorPage(authorId)`
  (mirrors `loadNovelDetails`) triggered from a `LaunchedEffect(authorId)`
  in the `Screen.Author` branch - not reloaded on unrelated recompositions.
- `AuthorPageScreen`'s "Fictions" list is backed by `NovelDao.byAuthor`
  (already existed from Stage 1), so it reflects every fiction currently
  linked to that author, not just the one the user tapped in from.
- If `authorId` doesn't resolve to a row in `authors` (author.json removed
  from disk since the fiction/chapter page last read it), the screen simply
  renders nothing rather than crashing - same "never fail on missing
  optional metadata" guarantee as the rest of the app.

### Stage 5 — QA / polish
Exercise the same edge cases `metadata.json` already has to handle:
- No `authors/` folder present (today's default — nothing should change).
- Multiple fictions sharing the same `authorId` (expected, common case).
- Two fictions with the same free-text `author` name but no `authorId` set
  on either (name-fallback match, not id match).
- Two `authors/*.json` files whose `"id"` override collides - first wins,
  second is skipped with a soft warning, scan still completes.
- Malformed/partial JSON in an author file.
- Missing or unreadable avatar file.
- Very long bio text in the chapter-page card vs. the full author page.

**QA pass results (code review - no device/emulator available in this
environment, so this is static verification of `ScannerImpl`/
`MainActivity`/the UI screens against each case, not an instrumented test
run):**
- No `authors/` folder: `findAuthorsFolder` returns null, `scanRoot` treats
  `discoveredAuthors` as empty, every fiction resolves `authorId = null` -
  confirmed, byline/avatar/card all render exactly as before this feature.
- Shared `authorId` across fictions: `NovelDao.byAuthor` returns every row
  with that id - confirmed, no special-casing needed.
- Free-text name fallback: `authorIdByNormalizedName` is seeded from
  `discoveredAuthors` before the fiction loop runs, case/whitespace-
  normalized on both sides - confirmed.
- Id collision on `authors/*.json`'s own `"id"` override (including two
  filenames differing only by case): `scanAuthorsFolder`'s `seenIds` check
  lowercases before comparing, first file wins, later one skipped via
  `onProgress`, scan continues - confirmed, no fix needed.
- Malformed JSON (including valid-but-wrong-shape JSON, e.g. a top-level
  array instead of an object): `readAuthorMetadata`'s `JSONObject(text)`
  throws, caught, returns null; caller still adds an `AuthorEntity` using
  the filename as both id and name - confirmed fail-soft, matches
  `readLocalMetadata`'s behavior for fiction `metadata.json`.
- Missing/unreadable avatar: `resolveAuthorAvatarUri`/`findAuthorAvatarUri`
  return null when nothing matches, both `AuthorAvatar` (author page) and
  `ReaderAuthorAvatar` (chapter card) render the 🖋️ placeholder - confirmed.
- Long bio: the author page's "About" card renders it unclamped in a
  scrolling `LazyColumn` (no overflow risk); the chapter-page card clamps to
  `BIO_EXCERPT_LENGTH` (160 chars) *and* `maxLines = 3` - confirmed, no
  visual regression either way.
- **Bug found and fixed:** author *linking* didn't heal itself. Once a
  fiction's `authorId` resolved once, `MainActivity`'s `onDiscovered` merge
  (`scanned.copy(... authorId = scanned.authorId ?: existing.authorId ...)`)
  fell back to the old id whenever a later scan resolved to null - which
  happens precisely when the linked `authors/<id>.json` is deleted or
  renamed. So deleting an author file never actually unlinked the fictions
  that pointed at it; they'd keep showing a byline link/"About the author"
  card that led to a page which came back empty (`AuthorDao.findById`
  returning null). Unlike the free-text `author`/`description`/`genres`
  fields around it - which legitimately want "scan found nothing, keep the
  old value" fallback, since they're only refreshed from *this fiction's
  own* `metadata.json` - `authorId`'s resolution also depends on the
  *external* `authors/` folder's contents and is fully recomputed every
  scan regardless of whether `metadata.json` itself changed at all. Fixed
  by dropping `authorId` from that fallback entirely, so it behaves like
  `coverUri` already does elsewhere in the same `copy()` call: the freshly
  scanned value - including null - always wins.

## Explicitly out of scope
- Any real Follow/notification/social behavior — ARKster is offline-first
  and account-free per `README.md`; nothing in this feature introduces
  network calls or accounts.
- In-app editing of `author.json`. Same as `metadata.json` today, it's
  read-only from the app's perspective; users hand-edit or script it.

## Open questions
1. Does "Total Words" on the author page require a new `wordCount` column
   (on `NovelEntity` and/or `ChapterEntity`), computed at scan time? Or is
   that stat simply omitted for the first version of this feature? *(Still
   open - not needed for Stage 1's data layer.)*
2. ~~Author identity/dedup across fictions~~ **Resolved at Stage 1 kickoff**:
   root-level `authors/<id>.json`, id defaults to filename, optional
   `authorId` in a fiction's `metadata.json` links unambiguously; free-text
   name match is a fallback only. See "Where `author.json` lives" above.
3. Fallback when a fiction has a `cover.*` but no linked author/avatar —
   does the author card simply not render, or fall back to a generic
   placeholder avatar (consistent with the existing auto-generated cover
   placeholder logic in the scanner)? *(Still open - a Stage 2/3 UI
   question, not a Stage 1 data-layer one.)*

Remaining open items should be resolved before the Stage they block begins.
