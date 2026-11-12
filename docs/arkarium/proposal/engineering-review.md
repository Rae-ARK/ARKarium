# Engineering review: findings and proposed work

> **Kind:** proposal · **Status:** **partially decided** — F1, F6, F7, F8, F10, F11, F18, F20, F22, F23 fixed in code; F14 partially fixed in code (`NovelDetailScreen` only, see below); F3, F5 won't-fix (SAF support removed instead, see below); F9 done in code at paragraph granularity (narrower than this doc's own direction, on-device verification still open); D2, D3, D4, D5, D6 decided (see each); D1 (the per-finding accept/defer/reject table below) still open; F24–F28 added 2026-11-12, all open · **Verified:** 2026-11-12 @ (this patch)
> **Origin:** notes from a separate review session (code read plus web research), not an authoritative audit.
> **Checked since:** each finding was re-read against the code on 2026-11-07. External guidance (library, platform, vendor behavior) was **not** re-checked except where a finding below says otherwise. The detail docs this file links to did not exist when this table was first written; they were filled in and F3 was corrected (see C3/C4) on the same date. A follow-up pass the same day added F22/F23 and recorded D2–D6; see each linked detail doc for what was re-verified for those.
> **Correction (2026-11-12):** F3 and F5 below still read "Open" as of the SAF-removal commits (`de4ab98`, `2323b66`) landing — this table was never updated alongside the SAF-removal decision (now "Settled decisions" in [`../foundational/design-notes.md`](../foundational/design-notes.md)) and [`../implementation/engineering-backlog.md`](../implementation/engineering-backlog.md) (rungs 12/15), which both correctly show won't-fix. Fixed here to match.

Nothing here is decided or built. This file is the review entry point: it lists
every finding, how well it is established, and what the owner must decide.
Detail lives in the linked proposals.

## How to read the tags

| Tag | Meaning |
|---|---|
| **V** | Verified: the code behavior was read and matches the claim. |
| **I** | Inferred: follows from the code but not demonstrated (no device run, no measurement). |
| **E** | External: depends on platform or library guidance that was not re-checked. Re-verify before acting. |
| **C** | Corrected: the review, or an existing doc, was wrong; see notes below. |

## Findings

Severity is the reviewer's judgment of impact if left alone.

| ID | Finding | Sev | Tags | Detail |
|---|---|---|---|---|
| F1 | Room wipes all user data when a migration is missing (`fallbackToDestructiveMigration`) | High | V | **Fixed** (fallback removed 5426a3d; schema export + migration tests done, backlog rung 9) — [`data-safety.md`](data-safety.md) |
| F2 | No exported schema, no migration tests, KAPT instead of KSP | High | V, E | **Fixed** (backlog rung 9) — [`data-safety.md`](data-safety.md) |
| F3 | `novelId` depends on SAF URIs for every novel, synced included; re-picking the folder deletes local novels' history and duplicates synced ones (corrected, see C3) | Med | V, I | **Won't-fix** — SAF support removed instead, see "Settled decisions" in [`../foundational/design-notes.md`](../foundational/design-notes.md); detail in [`data-safety.md`](data-safety.md) |
| F4 | Manifest proves integrity, not authenticity; no rollback or freeze protection | Med | V, E | Open — [`manifest-signing.md`](manifest-signing.md) |
| F5 | SAF scan and sync cost several provider queries per file | Med | V, E | **Won't-fix** — SAF support removed instead, see "Settled decisions" in [`../foundational/design-notes.md`](../foundational/design-notes.md); detail in [`performance.md`](performance.md) |
| F6 | Cancellation swallowed in the new-chapter worker | Med | V | **Fixed** (5426a3d) |
| F7 | Unreadable chapter is shown as an empty chapter | Med | V | **Fixed** (5426a3d, then sealed result type + retry screen) — [`../implementation/engineering-backlog.md`](../implementation/engineering-backlog.md) rung 7 |
| F8 | Two code paths create separate `AppDatabase` instances | Low | V, E | **Fixed** — [`../implementation/engineering-backlog.md`](../implementation/engineering-backlog.md) rung 3 |
| F9 | Reading position is a scroll fraction, so it drifts with font, spacing, width | Med | V, I | **Done in code** (paragraph-granularity anchor, narrower than planned; backlog rung 14) — [`reader-playback.md`](reader-playback.md) |
| F10 | TTS lives in a composable, so it dies on rotation or leaving the reader | High | V | **Fixed** (service-based playback, 823c914); controls surface still open — [`reader-playback.md`](reader-playback.md) |
| F11 | `splitForTts` hard-cuts and only breaks on `\n . ! ?` | Low | V, I | **Fixed** (unit-tested; not run on a device) — [`../implementation/engineering-backlog.md`](../implementation/engineering-backlog.md) rung 6 |
| F12 | R8 off, no baseline profile, no benchmarks, old Kotlin/Compose toolchain | Med | V | [`performance.md`](performance.md) |
| F13 | Splash: about 2.75 s per cold start, per-frame recomposition, chord synthesized on the main thread, no audio focus | Med | V (cost unmeasured) | [`performance.md`](performance.md) |
| F14 | Lazy lists mostly unkeyed; chapter filtering not remembered; non-lifecycle-aware collection | Low–Med | V, E | **Partially fixed** (`NovelDetailScreen` keyed + filters remembered; `AuthorPageScreen`, `FictionBrowseScreen`, `HomeScreen`, `LegalDocumentScreen`, `MetadataMatchDialog` and the lifecycle-unaware `collectAsState()` sites still open) — [`performance.md`](performance.md) |
| F15 | No shared image loader, no crossfade on covers | Low | V | [`performance.md`](performance.md) |
| F16 | Almost no animation outside the splash | Low | V | [`animation-and-polish.md`](animation-and-polish.md) |
| F17 | Not ready for predictive back or edge-to-edge | Low | V, E | [`animation-and-polish.md`](animation-and-polish.md) |
| F18 | WebView enables JS and DOM storage and follows any link | Low | V | **Fixed** (links; JS/DOM storage stay on) — [`../implementation/engineering-backlog.md`](../implementation/engineering-backlog.md) rung 5 |
| F19 | Typography: justified text, no hyphenation, uncapped line width | Low | V, I | [`animation-and-polish.md`](animation-and-polish.md) |
| F20 | Some icons have `contentDescription = null` | Low | V | **Audited**, 4 fixes, no on-device screen-reader pass — [`../implementation/engineering-backlog.md`](../implementation/engineering-backlog.md) rung 8 |
| F21 | Functional gaps: read state on rows, single-scroll chapter list, sleep timer, immersive mode, full-text search | n/a | V | [`ideas.md`](ideas.md) |
| F22 | `TtsPlaybackService` requests no audio focus, doesn't handle `ACTION_AUDIO_BECOMING_NOISY`, has no `MediaSession` | Med | V | **Fixed** (backlog rung 10; notification transport buttons since added, tts-playback-controls rung 4) — [`reader-playback.md`](reader-playback.md) |
| F23 | Room throws uncaught on a version **downgrade** (older APK over newer data); only recovery shown to the user is a crash, and clearing app data to recover destroys the same data F1's fix was meant to protect | Med | V | **Fixed** (backlog rung 2) — [`data-safety.md`](data-safety.md) |
| F24 | Six foreign-key columns have no index (`chapters.novel_id`, `chapters.arc_id`, `arcs.novel_id`, `chapter_overrides.chapter_id`, `reading_progress.chapter_id`, `scan_fingerprints.novel_id`), so per-novel lookups and `ON DELETE CASCADE` are table scans (corrected, see C5) | Med | V, I | Open; needs a migration — [`performance.md`](performance.md) |
| F25 | The scan commits one row per transaction (no `withTransaction` anywhere) | Low–Med | V, I | Open — [`performance.md`](performance.md) |
| F26 | The same novel folder is listed several times per scan (constant factor; not IPC since SAF removal, see C5) | Low | V, I | Open, opportunistic — [`performance.md`](performance.md) |
| F27 | The scan is sequential across novels | Low | V, I | Open, recommend defer until after F25 — [`performance.md`](performance.md) |
| F28 | `currentStreakDays` builds 3,650 date strings to read the first few | Low | V | Open — [`performance.md`](performance.md) |

A related but separate finding from the same follow-up pass — a scan could
cascade-delete a local novel's history on an *ordinary* scan (no folder
re-pick, no version change) when a novel was skipped or the folder listing
came back empty — was filed as a bug rather than a numbered finding here,
since it was a correctness bug in already-shipped code, not an open design
question. It's fixed; see
[`../implementation/engineering-backlog.md`](../implementation/engineering-backlog.md) rung 1.

## Corrections

- **C1.** [`relay-chain.md`](relay-chain.md) said the hash check makes a mirror safe to trust. It does not: a mirror can serve a self-consistent manifest and matching files. Fixed in the same change; signing (F4) is a prerequisite for that proposal.
- **C2.** The review noted a source claiming `catch (e: Exception)` does not catch coroutine cancellation. That source is wrong; on the JVM `CancellationException` is an `IllegalStateException`, so it is caught. F6 stands.
- **C3.** F3 assumed a synced novel's id is stable (derived from the relay slug) and only a local novel's id depends on the SAF URI. Re-reading `ScannerImpl.buildNovelEntity()` shows every novel's id — synced included — is `UUID.nameUUIDFromBytes(root.uri + ":" + child.uri)`; the relay slug only names the on-disk folder, not the Room row. Re-picking the library root therefore affects synced novels too, and `LibraryViewModel.startScan`'s stale-row handling makes the practical effect worse than "orphaned": a stale **local** novel is cascade-deleted (real data loss), a stale **synced** novel is kept at `MISSING_LOCALLY` while a duplicate fresh row is created under the new id. See [`data-safety.md`](data-safety.md) F3 for the corrected writeup.
- **C4.** D3 below referred to "bug 5 in `bugs.md`". No such entry exists — `bugs/` currently holds one open bug, not about local-folder identity. There never was a bug 5; the decision this review needs is answered directly in [`data-safety.md`](data-safety.md) F3 instead, see D3's corrected wording.
- **C5.** F24–F28 came from a separate local review pass and were re-read against the code on 2026-11-12 before being recorded. Two claims did not survive: the index finding first listed every foreign-key table, but `reading_progress`, `chapter_read_events` and `synced_files` are already covered by their primary keys (F24 now lists only the six that aren't); and the repeated-`listFiles()` finding argued each call is a binder round-trip through a `DocumentsProvider`, which stopped being true when SAF support was removed (the root is `DocumentFile.fromFile`, plain `java.io.File`), so F26 is a tidiness item, not a scan-time win. The Compose findings from that pass (unremembered chapter filtering, unkeyed lazy lists) are not new findings: they are F14, which now carries the extra detail. The reader screen is not affected by either; see [`bugs/other/scrolling-bug.md`](bugs/other/scrolling-bug.md).

## What the review judged sound (no action)

Hash-verified sync, invariants S1–S9, incremental fingerprinting, the docs checker.

## Decisions needed from the owner

| # | Question | Affects |
|---|---|---|
| # | Question | Affects | Answer |
|---|---|---|---|
| D1 | For each finding: accept, defer, or reject? (fill in the table below) | all | Open — see the Finding/Decision table below, still blank |
| D2 | Adopt instrumented tests for Room migrations? [`design-notes.md`](../foundational/design-notes.md) currently ranks instrumented tests "not a priority"; F2 needs them. | F2 | **Decided:** yes, scoped to migrations only (one `MigrationTestHelper` test per migration); the rest of `design-notes.md`'s "not a priority" ranking stands. Detail in [`data-safety.md`](data-safety.md) F1. |
| D3 | Give novels an id independent of the SAF root+child URI (see [`data-safety.md`](data-safety.md) F3), or accept the current cascade-delete-on-rescan/duplicate-row behavior as a known limitation? | F3, F5 | **Decided:** opaque id, independent of the URI. Synced novels derive it from `syncSourceUrl`; local novels get a minted UID, re-linked by title/folder-name/chapter-count with user confirmation on a mismatch, never written into the user's folder unless they opt in. Detail in [`data-safety.md`](data-safety.md) F3. |
| D4 | Upgrade Kotlin to 2.x, Compose BOM and Navigation as one enabling step? It unlocks KSP, strong skipping, shared-element transitions, predictive back. | F2, F12, F14, F17 | **Decided:** yes, but staged — Kotlin 2.x + compiler plugin, then Compose BOM, then Navigation, each benchmarked separately rather than bumped together. Detail in [`performance.md`](performance.md) F12. |
| D5 | Who holds the manifest signing key, and how are rotation and expiry handled? | F4 | **Decided:** offline key, signing done at publish time in the author's own pipeline (never inside the relay/Worker), long or no `expires` initially. Rotation procedure and the ECDSA-vs-Ed25519 choice still open. Detail in [`manifest-signing.md`](manifest-signing.md). |
| D6 | Splash policy: once per install/update, tap to skip, chord default? | F13 | **Decided:** full sequence once per install/update, ~300ms fade otherwise, tap-to-skip always available, chord **off by default**. Detail in [`performance.md`](performance.md) F13. |

| Finding | Decision | Date |
|---|---|---|
| F1–F28 | — (per-finding accept/defer/reject still open — see D1) | — |

## Suggested order (reviewer's, adjusted after verification)

1. F1 + F2: remove the global fallback, export schemas, add migration tests. Hours; prevents the worst failure.
2. F6 + F7: small diffs. Rotation bug in [`bugs/`](bugs/README.md) alongside.
3. Start the measurement track: Compose compiler reports and a Macrobenchmark (F12, F14), so later performance work is data-driven.
4. ~~F5: SAF directory listing, after measuring on a real custom-folder library.~~ Moot — SAF support was removed instead (see "Settled decisions" in [`../foundational/design-notes.md`](../foundational/design-notes.md)); there is no SAF directory listing left to measure or optimize.
5. F4: signing, before building the relay chain.
6. F10 is fixed (service-based playback, 823c914). F9 + the TTS controls issue (originally `uiux.md`, since deleted - promoted into [`../implementation/tts-playback-controls.md`](../implementation/tts-playback-controls.md)) remain one coordinated change, now buildable on top of that service. F22 (audio focus/becoming-noisy/`MediaSession`) builds on the same service and can land independently of F9.
7. F13, F15–F17, F19: polish, in whatever order the D4 and D6 decisions (now recorded above) allow.
8. F23 is fixed (backlog rung 2): an `IllegalStateException` catch and a friendlier message in `AppDatabase.create()`, landed after F1's fix (5426a3d), which it builds on directly.

## On acceptance

Per the lifecycle rule, `git mv` an accepted proposal to `../implementation/`,
split it into rungs, and update this table. Delete findings that are rejected or fixed.
