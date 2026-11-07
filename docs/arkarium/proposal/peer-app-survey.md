# Peer app survey: ARKarium against local-first readers and serial-fiction clients

> **Kind:** proposal · **Status:** research only, nothing built or decided · **Verified:** 2026-11-07 @ b777145
> **Origin:** notes from a separate review session (code read plus web
> research into peer apps), not an authoritative audit. External claims
> about peer apps (their current feature sets, store listings, community
> complaints) were **not** re-checked this pass — re-verify before citing
> any of them publicly.

This is a product/UX survey, not a code review — see
[`engineering-review.md`](engineering-review.md) for that. It compares
ARKarium against three groups of peer apps — read against
[`../foundational/what-is-arkarium.md`](../foundational/what-is-arkarium.md)'s
own stated principles first, so the comparison is against what ARKarium
says it is trying to be, not an arbitrary feature checklist.

## Philosophy check: ARKarium against the local-first ideals

Martin Kleppmann's local-first ideals (fast, multi-device, offline,
collaboration, longevity, privacy, user control) are a gradient to trade off
against, not a checklist every app must max out. Against that gradient:
no-spinner reading path, offline-by-default, no account system, and the
static-files-plus-author-repo model (see `architecture.md`'s Sync section)
satisfy fast/offline/privacy/longevity by construction — the explicit goal
of "software that outlives any vendor backend" is a longevity bet, not just
a slogan. The weak ideal is **multi-device**: today that structurally needs
either the planned small backend ([`backend-social.md`](backend-social.md))
or a manual folder copy. That's a real gap, not a failure — most
self-hosted/local-first software in this category grades the same way,
multi-device being the one ideal that tends to need *something* server-side
or at minimum a second device talking to the first.

## Three peer groups, three different lessons

| Group | Apps looked at | What's notable |
|---|---|---|
| Mihon family (plugin-based aggregator readers) | Mihon, LNReader, IReader, Hibana | Closest UX peer, philosophically opposite architecture — LNReader states it has no affiliation with the sites its plugins scrape and has stopped bundling plugin repositories, which is exactly the fragility ARKarium's author-canonical model is built to avoid. |
| Local-first / self-hosted ecosystem | Readest, KOReader + OPDS | Architecturally closest to ARKarium's own values; weaker on dedicated reading/TTS UX than the Mihon family. |
| Creator-direct tooling | itch.io | Not a reading app at all — a model for the *publisher* side ARKarium's author-pipeline needs. |

Net: borrow reading/library UX from the first group, architecture and
publisher tooling from the second and third.

### What specific peer apps do, worth knowing about

| App | Worth noting |
|---|---|
| Hibana (Compose, Mihon-style) | An Updates tab across the whole library, scheduled update-check intervals, read-aloud with paragraph highlighting via a foreground service, and progress re-matched by chapter number when a novel migrates sources. |
| Matane | A short first-run setup flow, an offline "What's new" note shown once after an update, per-title reader settings, a cover-size slider, a page color derived from the cover. |
| Mihon | Categories, scheduled updates, trackers, backups. |
| LNReader | Reading and TTS continue in the background; backups go to Google Drive; lets users inject their own CSS/JS into chapters (see the security caveat below). |
| Readest | A dedicated TTS settings tab with highlight opacity and a hotkey to highlight the sentence being read aloud; reads books in place from external folders without copying — the same model ARKarium already uses via SAF. |
| itch.io | The upload/publish dialog previews new/modified/deleted files before pushing, and builds can be pushed hidden/unlisted before going public. |

## Suggestions

### 1. Serial-fiction metadata (cheap, fits the existing schema)

Confirmed against `data/ScannerImpl.kt`'s `LocalMetadata` — today
`metadata.json` carries only `title`, `author`, `authorId`, `description`,
`genres`, `publishedDate`. Serial-fiction readers consistently ask for more
than that:

- **Publication status** (ongoing/hiatus/dropped/complete). Readers on
  serial-fiction platforms specifically want a notification when a fiction
  comes off hiatus. Add a `status` field to `metadata.json` and the sync
  manifest, and notify on a change — same mechanism as the existing
  new-chapter check.
- **Word count and estimated read time per chapter.** Common on self-hosted
  serial-fiction platforms; cheap to compute at publish time and ship as
  manifest metadata rather than computed on-device.
- **Announcements outside the chapter stream.** ARKarium's `<news>` callout
  (`data/ChapterMarkup.kt`, rendered in `ui/ReaderScreen.kt`) lives *inside*
  a chapter file today. A manifest-level announcements list (not tied to any
  one chapter) avoids an announcement disturbing bookmark/progress state the
  way inserting one into a chapter file would.
- **Collapsible, TTS-skippable author notes.** The `<author>` callout
  already exists; long pre/post-chapter author notes are a known annoyance
  on mobile when shown inline and expanded by default. Make the callout
  collapsed by default, and give it an explicit "skip in read-aloud" flag —
  this is a presentation change to existing markup, not a new feature.
- **Author-owned support links** (Patreon/Ko-fi style) in `metadata.json` or
  `author.json`, shown in the reader without being read aloud by TTS. This
  fits "creator-controlled" (`what-is-arkarium.md` principle 6) better than
  any platform-run monetization could — the author owns the link, not
  ARKarium.

### 2. Library UX from the Mihon family

An Updates tab, unread badges, comfortable/compact/list display modes,
tri-state filters, and categories — all things Hibana ships together.
`chapter_read_events` (confirmed in `data/Entities.kt`) already backs most
of the "unread/read" state this needs; this is mostly UI work on top of data
that already exists. A one-time "what's new" screen after an update and a
short first-run setup are both small, high-polish additions in the same
vein as `uiux.md`'s existing entries.

### 3. Multi-device without a platform — options to weigh against the backend plan

`backend-social.md` already scopes "comments, ratings, reading-position
sync" as needing a small backend (Firebase Spark, per that doc) because it's
genuinely per-user, queryable, cross-network state. Two backend-free
alternatives worth having in the same doc as options, not replacements,
since they solve a narrower problem (same-network or same-account devices,
not "sync from anywhere"):

- **A sidecar `progress.json`**, written into the library folder itself
  rather than a database the backend owns. Pairing this with any
  folder-sync tool a user already runs (Syncthing, a shared cloud-storage
  folder) gives multi-device sync with zero ARKarium-run backend — the same
  "the content format is the sync protocol" idea the static-manifest sync
  already relies on, just applied to progress instead of chapters.
- **OPDS's Progression draft** (used by the KOReader/`kosync` ecosystem) is
  a GET/PUT-per-publication shape with a 409 response when the stored
  position is newer than the one being written — worth copying the shape of
  if `backend-social.md`'s position-sync endpoint is ever built, and worth
  checking F9's anchor representation (`reader-playback.md`) against for
  compatibility, since both are solving "what does a reading position mean
  across different layouts/devices."

### 4. Publisher tooling — the itch.io lesson

[`ideas.md`](ideas.md) already lists "Publisher pipeline (ARKlight)" and
calls today's manifest generation a "throwaway script." itch.io's model
suggests two specific things worth adding to that eventual tool: a
diff preview (new/changed/removed files) before publishing, and a
hidden/unlisted channel for drafts an author can test before making a
release public. Both pair naturally with
[`manifest-signing.md`](manifest-signing.md)'s D5 decision to sign at
publish time in this same pipeline, since the signing step and a diff
preview both need to inspect the same file set before anything is pushed.

### 5. Reader details

- **TTS chunk granularity.** ARKarium's ~3800-character TTS chunks
  (`ui/Tts.kt`'s `TTS_MAX_CHUNK_CHARS`, see `robustness.md` F11) already
  avoid the "sentence-at-a-time is choppy" complaint some peer apps' users
  raise — keep the chunk size, and highlight within a chunk rather than
  switching to sentence-level chunks.
- **Table-stakes playback controls**: lock-screen controls, sleep timer,
  auto-advance, 0.75–2x speed. `ideas.md`'s F21 functional-gaps row plus
  [`reader-playback.md`](reader-playback.md)'s F22 (audio focus/
  `MediaSession`) cover the infrastructure this needs; a sleep timer itself
  is still just a backlog item.
- **Author-supplied style, kept narrow.** LNReader's arbitrary CSS/JS
  injection is powerful but is also a security surface most readers don't
  need, and doesn't fit ARKarium's Compose-rendered (not WebView-rendered)
  chapter view anyway. A small allow-listed set of author style tokens
  (an accent color, a choice from a bundled font list) gets most of the
  "per-novel presentation" value without an injection surface.

### 6. One architectural option, explicitly a values call

Publishing an OPDS 1.2 feed from the author pipeline would make ARKarium
content readable in any OPDS client (Calibre, KOReader, etc.) — good for
longevity, but in tension with principle 5 ("experience is the moat"), since
the entire pitch of a dedicated client is that it does more with the content
than a generic one can. It would also need an actual EPUB build per
arc/volume, since chapters are plain TXT today. Not a technical
recommendation either way — flagged here as a decision ARKarium's owner
should make deliberately, not default into.

## Where ARKarium is already ahead

Some serial-fiction platforms gate offline chapter downloads behind a paid
tier. ARKarium's offline-by-default model (principle 2) means that's not a
tier at all here — worth using as a pitch line, not just an internal
talking point.

## Shortlist (suggested priority order)

1. Status/hiatus notifications, plus word count and read time — small
   manifest schema change, see Suggestion 1.
2. Updates tab, unread badges, display modes — UI work on data that already
   exists (`chapter_read_events`).
3. A sidecar `progress.json` as a backend-free multi-device option,
   documented as an addition to `backend-social.md`'s options, not a
   replacement for its Firebase plan.
4. Publisher tooling: diff preview, a draft/unlisted channel, signing at
   push time (ties directly to `manifest-signing.md` D5).
5. Collapsible, TTS-skippable author notes, plus author-owned support
   links.

Items 1 and 3 touch the sync manifest and reading-position schema
respectively — worth drafting those schema changes before building UI on
top of either, the same sequencing reasoning `data-safety.md` F3 and
`reader-playback.md` F9 already apply to their own schema questions.
