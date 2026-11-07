# Engineering review: findings and proposed work

> **Kind:** proposal · **Status:** **awaiting review** · **Verified:** 2026-11-07 @ b4fcc2f
> **Origin:** notes from a separate review session (code read plus web research), not an authoritative audit.
> **Checked since:** each finding was re-read against the code on 2026-11-07. External guidance (library, platform, vendor behavior) was **not** re-checked.

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
| F1 | Room wipes all user data when a migration is missing (`fallbackToDestructiveMigration`) | High | V | [`data-safety.md`](data-safety.md) |
| F2 | No exported schema, no migration tests, KAPT instead of KSP | High | V, E | [`data-safety.md`](data-safety.md) |
| F3 | `novelId` depends on SAF URIs; re-picking the folder or restoring a backup orphans overrides and progress | Med | V, I | [`data-safety.md`](data-safety.md) |
| F4 | Manifest proves integrity, not authenticity; no rollback or freeze protection | Med | V, E | [`manifest-signing.md`](manifest-signing.md) |
| F5 | SAF scan and sync cost several provider queries per file | Med | V, E | [`performance.md`](performance.md) |
| F6 | Cancellation swallowed in the new-chapter worker | Med | V | [`robustness.md`](robustness.md) |
| F7 | Unreadable chapter is shown as an empty chapter | Med | V | [`robustness.md`](robustness.md) |
| F8 | Two code paths create separate `AppDatabase` instances | Low | V, E | [`robustness.md`](robustness.md) |
| F9 | Reading position is a scroll fraction, so it drifts with font, spacing, width | Med | V, I | [`reader-playback.md`](reader-playback.md) |
| F10 | TTS lives in a composable, so it dies on rotation or leaving the reader | High | V | [`reader-playback.md`](reader-playback.md) |
| F11 | `splitForTts` hard-cuts and only breaks on `\n . ! ?` | Low | V, I | [`robustness.md`](robustness.md) |
| F12 | R8 off, no baseline profile, no benchmarks, old Kotlin/Compose toolchain | Med | V | [`performance.md`](performance.md) |
| F13 | Splash: about 2.75 s per cold start, per-frame recomposition, chord synthesized on the main thread, no audio focus | Med | V (cost unmeasured) | [`performance.md`](performance.md) |
| F14 | Lazy lists mostly unkeyed; chapter filtering not remembered; non-lifecycle-aware collection | Low–Med | V, E | [`performance.md`](performance.md) |
| F15 | No shared image loader, no crossfade on covers | Low | V | [`performance.md`](performance.md) |
| F16 | Almost no animation outside the splash | Low | V | [`animation-and-polish.md`](animation-and-polish.md) |
| F17 | Not ready for predictive back or edge-to-edge | Low | V, E | [`animation-and-polish.md`](animation-and-polish.md) |
| F18 | WebView enables JS and DOM storage and follows any link | Low | V | [`robustness.md`](robustness.md) |
| F19 | Typography: justified text, no hyphenation, uncapped line width | Low | V, I | [`animation-and-polish.md`](animation-and-polish.md) |
| F20 | Some icons have `contentDescription = null` | Low | V | [`robustness.md`](robustness.md) |
| F21 | Functional gaps: read state on rows, single-scroll chapter list, sleep timer, immersive mode, full-text search | n/a | V | [`ideas.md`](ideas.md) |

## Corrections

- **C1.** [`relay-chain.md`](relay-chain.md) said the hash check makes a mirror safe to trust. It does not: a mirror can serve a self-consistent manifest and matching files. Fixed in the same change; signing (F4) is a prerequisite for that proposal.
- **C2.** The review noted a source claiming `catch (e: Exception)` does not catch coroutine cancellation. That source is wrong; on the JVM `CancellationException` is an `IllegalStateException`, so it is caught. F6 stands.

## What the review judged sound (no action)

Hash-verified sync, invariants S1–S9, incremental fingerprinting, the docs checker.

## Decisions needed from the owner

| # | Question | Affects |
|---|---|---|
| D1 | For each finding: accept, defer, or reject? (fill in the table below) | all |
| D2 | Adopt instrumented tests for Room migrations? [`design-notes.md`](../foundational/design-notes.md) currently ranks instrumented tests "not a priority"; F2 needs them. | F2 |
| D3 | Outcome of bug 5 in [`bugs.md`](bugs.md) (local folders hidden or kept)? It shrinks or removes F3. | F3, F5 |
| D4 | Upgrade Kotlin to 2.x, Compose BOM and Navigation as one enabling step? It unlocks KSP, strong skipping, shared-element transitions, predictive back. | F2, F12, F14, F17 |
| D5 | Who holds the manifest signing key, and how are rotation and expiry handled? | F4 |
| D6 | Splash policy: once per install/update, tap to skip, chord default? | F13 |

| Finding | Decision | Date |
|---|---|---|
| F1–F21 | — | — |

## Suggested order (reviewer's, adjusted after verification)

1. F1 + F2: remove the global fallback, export schemas, add migration tests. Hours; prevents the worst failure.
2. F6 + F7: small diffs. Rotation bug in [`bugs.md`](bugs.md) alongside.
3. Start the measurement track: Compose compiler reports and a Macrobenchmark (F12, F14), so later performance work is data-driven.
4. F5: SAF directory listing, after measuring on a real custom-folder library.
5. F4: signing, before building the relay chain.
6. F9 + F10 + the TTS controls issue in [`uiux.md`](uiux.md): one coordinated change.
7. F13, F15–F17, F19: polish, in whatever order the D4 and D6 decisions allow.

## On acceptance

Per the lifecycle rule, `git mv` an accepted proposal to `../implementation/`,
split it into rungs, and update this table. Delete findings that are rejected or fixed.
